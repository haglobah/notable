/*
 * The PWA's QuickJS runtime (ADR-002): the same engine as Android, compiled to WASM. It runs in
 * its own WASM heap, with no DOM, cookies or fetch: definitions reach only what the prelude's
 * `notable` offers. Android's twin is app/.../image/JsRuntime.kt; keep errors in the same form,
 * "<file>:<line>: <Name>: <message>".
 */
import { newQuickJSWASMModuleFromVariant, shouldInterruptAfterDeadline, type QuickJSContext, type QuickJSRuntime, type QuickJSWASMModule } from "quickjs-emscripten-core"
import variant from "@jitl/quickjs-wasmfile-release-sync"

/** The host API version this kernel offers. Each version only adds to the one before. */
export const HOST_API = 1

/** What definitions can reach, through `notable` (web/schema/runtime/prelude.js). */
export interface ImageHost {
  log(line: string): void
  snack(text: string): void
  /** The library as JSON (the prelude's `notable.library`). */
  libraryJson(): string
  /** The open page as JSON, or "null". */
  pageJson(): string
}

export type Limits = { memoryBytes: number; stackBytes: number; evalTimeoutMs: number }
export const DEFAULT_LIMITS: Limits = { memoryBytes: 32 << 20, stackBytes: 512 << 10, evalTimeoutMs: 2000 }

export type EvalResult = { ok: true; value: string | null } | { ok: false; error: string }

let wasm: Promise<QuickJSWASMModule> | null = null
const loadWasm = () => (wasm ??= newQuickJSWASMModuleFromVariant(variant))

type Dumped = { name?: unknown; message?: unknown; stack?: unknown } | string | number | null | undefined

/** "def:command/x:3": the innermost stack frame with a file and a line. */
function location(stack: string): string | null {
  for (const raw of stack.split("\n")) {
    const frame = raw.trim().replace(/^at /, "")
    const text = frame.endsWith(")") && frame.includes("(") ? frame.slice(frame.lastIndexOf("(") + 1, -1) : frame
    const m = /^(.*?):(\d+)(?::\d+)?$/.exec(text)
    if (m && m[1] !== "" && m[1] !== "native") return `${m[1]}:${m[2]}`
  }
  return null
}

export function describe(e: Dumped, filename: string): string {
  if (e !== null && typeof e === "object") {
    const message = [e.name, e.message].filter(x => typeof x === "string" && x !== "").join(": ") || "error"
    const at = typeof e.stack === "string" ? location(e.stack) : null
    return `${at ?? filename}: ${message}`
  }
  return `${filename}: ${String(e)}`
}

export class JsRuntime {
  private rt: QuickJSRuntime | null = null
  private vm: QuickJSContext | null = null
  private readonly limits: Limits
  constructor(limits: Limits = DEFAULT_LIMITS) {
    this.limits = limits
  }

  /** A fresh runtime: [prelude] evaluated, `notable` bound to [host]. Replaces the old one. */
  async start(prelude: string, host: ImageHost): Promise<EvalResult> {
    const mod = await loadWasm()
    this.close()
    const rt = mod.newRuntime()
    rt.setMemoryLimit(this.limits.memoryBytes)
    rt.setMaxStackSize(this.limits.stackBytes)
    const vm = rt.newContext()
    const obj = vm.newObject()
    vm.setProp(obj, "hostApi", vm.newNumber(HOST_API))
    const fn = (name: string, f: (arg: string) => string | undefined) =>
      vm.newFunction(name, h => {
        const r = f(h === undefined ? "" : String(vm.dump(h)))
        return r === undefined ? undefined : vm.newString(r)
      }).consume(handle => vm.setProp(obj, name, handle))
    fn("log", line => { host.log(line); return undefined })
    fn("snack", text => { host.snack(text); return undefined })
    fn("library", () => host.libraryJson())
    fn("page", () => host.pageJson())
    vm.setProp(vm.global, "__host", obj)
    obj.dispose()
    this.rt = rt
    this.vm = vm
    const r = this.eval(prelude, "prelude.js")
    if (!r.ok) {
      this.close()
      return { ok: false, error: `the image prelude failed: ${r.error}` }
    }
    return r
  }

  /** Evaluates [code] as a script named [filename]; its completion value must be a string or null. */
  eval(code: string, filename: string): EvalResult {
    const { rt, vm } = this
    if (!rt || !vm) return { ok: false, error: "the image runtime is not running" }
    const deadline = Date.now() + this.limits.evalTimeoutMs
    rt.setInterruptHandler(shouldInterruptAfterDeadline(deadline))
    try {
      const r = vm.evalCode(code, filename)
      if (r.error) {
        const e = vm.dump(r.error) as Dumped
        r.error.dispose()
        const interrupted = typeof e === "object" && e !== null && e.message === "interrupted"
        return { ok: false, error: interrupted ? `${filename}: stopped after ${this.limits.evalTimeoutMs} ms` : describe(e, filename) }
      }
      const v = vm.dump(r.value)
      r.value.dispose()
      return { ok: true, value: v === undefined || v === null ? null : String(v) }
    } catch (e) {
      // The engine itself failed (e.g. out of WASM memory); this runtime is not usable any more.
      return { ok: false, error: `${filename}: ${(e as Error).message}` }
    } finally {
      rt.removeInterruptHandler()
    }
  }

  close() {
    try { this.vm?.dispose() } catch { /* already gone */ }
    try { this.rt?.dispose() } catch { /* already gone */ }
    this.vm = null
    this.rt = null
  }
}
