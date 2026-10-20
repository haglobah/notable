/*
 * The device side of the REPL protocol, for a device whose image runs in JS: the PWA's REPL
 * panel, and the fake device in this package's tests. Android's twin is ReplProtocol.kt; keep the
 * replies the same.
 */
import { isDefName, type CodeDef } from "@notable/schema"
import { PROTOCOL, type CommandInfo, type DefInfo, type Reply, type Request } from "./protocol.ts"

/** One definition as the device's image sees it. */
export type DefStatus = { def: CodeDef; state: "loaded" | "failed"; error?: string; lastRunError?: string }

export interface ImageSide {
  readonly hostApi: number
  eval(code: string): Promise<{ value: string | null; error: string | null; logs: string[] }>
  mode(): string
  defs(): DefStatus[]
  commands(): CommandInfo[]
  /** Resolves once the image evaluated exactly [def] (it came back from the doc), or null on timeout. */
  whenApplied(def: CodeDef, timeoutMs: number): Promise<DefStatus | null>
}

export interface CodeDocSide {
  define(def: CodeDef): Promise<void>
  undefine(name: string): Promise<boolean>
  /** save() bytes, or null without a code doc. */
  save(): Promise<Uint8Array | null>
  /** "none", "ready", or "unreadable: why" */
  state(): string
}

export type Connection = { authorized: boolean }

export type ReplDeviceOptions = {
  /** Required on a socket; the in-page panel has none. */
  token: string | null
  device: string
  image: ImageSide
  docs: CodeDocSide
  now?: () => Date
  applyTimeoutMs?: number
}

const toBase64 = (b: Uint8Array) => {
  let s = ""
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000))
  return btoa(s)
}

export function defInfo(s: DefStatus, withSource: boolean): DefInfo {
  return {
    name: s.def.name, hostApi: s.def.hostApi, device: s.def.device, updatedAt: s.def.updatedAt.toISOString(), state: s.state,
    ...(s.error === undefined ? {} : { error: s.error }),
    ...(s.lastRunError === undefined ? {} : { lastRunError: s.lastRunError }),
    ...(withSource ? { source: s.def.source } : {}),
  }
}

export function replDevice(o: ReplDeviceOptions) {
  const now = o.now ?? (() => new Date())
  const hello = () => ({ device: o.device, hostApi: o.image.hostApi, protocol: PROTOCOL })

  async function answer(req: Request): Promise<Record<string, unknown>> {
    switch (req.op) {
      case "hello":
        return hello()
      case "eval": {
        const r = await o.image.eval(req.code)
        if (r.error !== null) throw Object.assign(new Error(r.error), { logs: r.logs })
        return { value: r.value, logs: r.logs }
      }
      case "def": {
        const hostApi = req.hostApi ?? o.image.hostApi
        if (!isDefName(req.name)) throw new Error(`def: '${req.name}' is not a definition name (kind/name, e.g. command/clear-page)`)
        if (!Number.isInteger(hostApi) || hostApi < 1) throw new Error("def: hostApi must be a positive integer")
        const def: CodeDef = { name: req.name, source: req.source, hostApi, updatedAt: now(), device: o.device }
        await o.docs.define(def)
        const s = await o.image.whenApplied(def, o.applyTimeoutMs ?? 5000)
        return { state: s?.state ?? "pending", ...(s?.error === undefined ? {} : { error: s.error }) }
      }
      case "undef":
        return { existed: await o.docs.undefine(req.name) }
      case "ls":
        return {
          mode: o.image.mode(),
          codeDoc: o.docs.state(),
          defs: o.image.defs().sort((a, b) => (a.def.name < b.def.name ? -1 : 1)).map(s => defInfo(s, false)),
          commands: o.image.commands(),
        }
      case "show": {
        const s = o.image.defs().find(d => d.def.name === req.name)
        if (!s) throw new Error(`no definition ${req.name}`)
        return { def: defInfo(s, true) }
      }
      case "doc": {
        const bytes = await o.docs.save()
        return { doc: bytes === null ? null : toBase64(bytes) }
      }
      default:
        throw new Error(`unknown op '${(req as { op: unknown }).op}'`)
    }
  }

  return {
    async handle(conn: Connection, text: string): Promise<{ text: string; close: boolean }> {
      let req: Request & { id?: unknown; token?: unknown }
      try {
        req = JSON.parse(text)
        if (req === null || typeof req !== "object") throw new Error()
      } catch {
        return { text: JSON.stringify({ id: null, ok: false, error: "not a JSON object" }), close: !conn.authorized }
      }
      const id = req.id ?? null
      if (!conn.authorized) {
        if (o.token !== null && (req.op !== "hello" || req.token !== o.token)) {
          return { text: JSON.stringify({ id, ok: false, error: "unauthorized" }), close: true }
        }
        conn.authorized = true
        if (req.op === "hello") return { text: JSON.stringify({ id, ok: true, ...hello() }), close: false }
      }
      try {
        return { text: JSON.stringify({ id, ok: true, ...(await answer(req)) }), close: false }
      } catch (e) {
        const logs = (e as { logs?: string[] }).logs
        return { text: JSON.stringify({ id, ok: false, error: (e as Error).message, ...(logs ? { logs } : {}) } satisfies Reply), close: false }
      }
    },
  }
}
