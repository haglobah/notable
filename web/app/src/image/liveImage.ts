/*
 * The live code image in the PWA (ADR-002): the registry of definitions and the extension point
 * "commands". The TS twin of app/.../image/LiveImage.kt; both must load, fail and fall back alike
 * (both test suites load web/schema/fixtures/code-v1.json).
 *
 * Values live in the runtime under their name and are looked up at each call (late binding). A
 * definition that throws while evaluated, doesn't parse, or needs a newer host API is failed on
 * this device and dropped; a built-in of its name runs instead. Nothing here is persisted.
 */
import type { CodeDef } from "@notable/schema"
import type { CommandInfo, DefStatus, ImageSide } from "@notable/repl"
import { HOST_API, JsRuntime, type ImageHost } from "./engine.ts"

export type Mode =
  | { m: "starting" }
  | { m: "running" }
  | { m: "safe"; reason: string }
  | { m: "unavailable"; reason: string }

export const modeText = (mode: Mode) =>
  mode.m === "safe" ? `safe mode (${mode.reason})` : mode.m === "unavailable" ? `unavailable (${mode.reason})` : mode.m

/** A command the kernel ships; a loaded definition of the same id replaces it. */
export type BuiltIn = { id: string; title: string; run: (image: LiveImage) => void }

export type RunResult = { ok: true; value: string | null } | { ok: false; error: string }

const js = (s: string) => JSON.stringify(s)
const sameDef = (a: CodeDef, b: CodeDef) =>
  a.name === b.name && a.source === b.source && a.hostApi === b.hostApi && a.device === b.device && a.updatedAt.getTime() === b.updatedAt.getTime()

export class LiveImage implements ImageSide {
  readonly hostApi = HOST_API
  private status: Mode = { m: "starting" }
  private states = new Map<string, DefStatus>()
  private imageCommands = new Map<string, CommandInfo>()
  private capture: string[] | null = null
  private listeners = new Set<() => void>()
  private readonly runtime: JsRuntime
  private readonly host: ImageHost
  private readonly builtIns: BuiltIn[]

  constructor(runtime: JsRuntime, host: ImageHost, builtIns: BuiltIn[]) {
    this.runtime = runtime
    this.builtIns = builtIns
    this.host = { ...host, log: line => { this.capture?.push(line); host.log(line) } }
  }

  /** Called after every change of status or commands (for the UI). */
  subscribe(f: () => void): () => void {
    this.listeners.add(f)
    return () => this.listeners.delete(f)
  }
  private changed() { for (const f of this.listeners) f() }

  async start(prelude: string, safeReason: string | null) {
    if (safeReason !== null) this.status = { m: "safe", reason: safeReason }
    else {
      const r = await this.runtime.start(prelude, this.host)
      this.status = r.ok ? { m: "running" } : { m: "unavailable", reason: r.error }
    }
    this.changed()
  }

  get running() { return this.status.m === "running" }
  mode() { return modeText(this.status) }
  modeValue() { return this.status }
  defs() { return [...this.states.values()] }

  /** Brings the registry in line with [defs] (the code doc's, whole). */
  apply(defs: Record<string, CodeDef>) {
    if (!this.running) {
      this.states = new Map(Object.values(defs).map(d => [d.name, { def: d, state: "failed", error: `not evaluated: ${this.mode()}` }]))
      this.changed()
      return
    }
    for (const name of this.states.keys()) if (!(name in defs)) this.runtime.eval(`__image.drop(${js(name)});null`, "kernel")
    const next = new Map<string, DefStatus>()
    for (const name of Object.keys(defs).sort()) {
      const old = this.states.get(name)
      next.set(name, old && sameDef(old.def, defs[name]) ? old : this.evaluate(defs[name]))
    }
    this.states = next
    this.refreshCommands()
    this.changed()
  }

  private evaluate(def: CodeDef): DefStatus {
    const n = js(def.name)
    if (def.hostApi > HOST_API) {
      this.runtime.eval(`__image.drop(${n});null`, "kernel")
      return { def, state: "failed", error: `needs host API ${def.hostApi}; this device has ${HOST_API} (update the app)` }
    }
    // The source starts on line 1 of the file, so error lines match it.
    const r = this.runtime.eval(`__image.begin(${n});try{__image.put(${n},(` + def.source + "\n))}finally{__image.end()};null", `def:${def.name}`)
    if (r.ok) return { def, state: "loaded" }
    this.runtime.eval(`__image.drop(${n});null`, "kernel")
    return { def, state: "failed", error: r.error }
  }

  private refreshCommands() {
    const r = this.runtime.eval("__image.commands()", "kernel")
    const listed: { id: string; title: string | null; owner: string; invalid?: string }[] = r.ok ? JSON.parse(r.value ?? "[]") : []
    for (const c of listed) {
      const s = this.states.get(c.id)
      if (c.invalid && s?.state === "loaded") this.states.set(c.id, { def: s.def, state: "failed", error: `${c.id}: ${c.invalid}` })
    }
    this.imageCommands = new Map(listed.filter(c => !c.invalid).map(c => [c.id, {
      id: c.id, title: c.title ?? this.builtIns.find(b => b.id === c.id)?.title ?? c.id.slice(c.id.indexOf("/") + 1), builtIn: false,
    }]))
  }

  /** Built-ins keep their place; a loaded definition of the same id takes it over. */
  commands(): CommandInfo[] {
    const extra = [...this.imageCommands.values()].filter(c => !this.builtIns.some(b => b.id === c.id))
      .sort((a, b) => a.title.toLowerCase().localeCompare(b.title.toLowerCase()))
    return [...this.builtIns.map(b => this.imageCommands.get(b.id) ?? { id: b.id, title: b.title, builtIn: true }), ...extra]
  }

  /**
   * Runs command [id]: the image's version if loaded, else the built-in. When the image's version
   * throws, the built-in runs instead and the error is returned for the caller to show.
   */
  runCommand(id: string): RunResult {
    const builtIn = this.builtIns.find(b => b.id === id)
    let result: RunResult | null = null
    if (this.running && this.imageCommands.has(id)) {
      const r = this.runtime.eval(`__image.run(${js(id)})`, `command:${id}`)
      result = r.ok ? r : { ok: false, error: r.error }
      const owner = this.states.get(id)
      if (owner?.state === "loaded") {
        const { lastRunError: _, ...rest } = owner
        this.states.set(id, r.ok ? rest : { ...rest, lastRunError: r.error })
      }
    }
    if (result?.ok) return result
    if (!builtIn) return result ?? { ok: false, error: `no command ${id}` }
    try {
      builtIn.run(this)
    } catch (e) {
      return { ok: false, error: `built-in ${id} failed: ${(e as Error).message}` }
    }
    return result === null ? { ok: true, value: null } : { ok: false, error: `${result.error} (ran the built-in instead)` }
  }

  /** REPL: runs [code] in the live runtime, writing nothing to the doc. */
  async eval(code: string) {
    if (!this.running) return { value: null, error: `the image is not running: ${this.mode()}`, logs: [] }
    const logs: string[] = []
    this.capture = logs
    try {
      const r = this.runtime.eval(`__image.show((0, eval)(${js(code)}))`, "repl")
      return r.ok ? { value: r.value, error: null, logs } : { value: null, error: r.error, logs }
    } finally {
      this.capture = null
    }
  }

  /** Resolves once [def] was applied here (it came back from the code doc), or null on timeout. */
  whenApplied(def: CodeDef, timeoutMs: number): Promise<DefStatus | null> {
    const now = () => { const s = this.states.get(def.name); return s && sameDef(s.def, def) ? s : null }
    const hit = now()
    if (hit) return Promise.resolve(hit)
    return new Promise(resolve => {
      const t = setTimeout(() => { off(); resolve(null) }, timeoutMs)
      const off = this.subscribe(() => {
        const s = now()
        if (s) { clearTimeout(t); off(); resolve(s) }
      })
    })
  }
}
