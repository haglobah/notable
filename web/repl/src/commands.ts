/*
 * The REPL's commands on top of any transport (the CLI's WebSocket, the PWA panel's in-page
 * device). Each returns the text to print. History and rollback read the code doc's Automerge
 * history here, from the device's save() bytes: automerge-java can't walk history (ADR-002 plan).
 */
import * as A from "@automerge/automerge"
import { codeHistory, readCode, type DefVersion } from "@notable/schema"
import type { DefInfo, Ok, Reply, Request } from "./protocol.ts"

export class ReplError extends Error {
  readonly logs: string[]
  constructor(message: string, logs: string[] = []) {
    super(message)
    this.logs = logs
  }
}

/** Sends one request; rejects with ReplError when the device answers `ok: false`. */
export type Call = <O extends Request["op"]>(req: Extract<Request, { op: O }>) => Promise<Ok<O>>

/** Wraps a raw send into a [Call]. */
export const checked = (send: (req: Request) => Promise<Reply>): Call =>
  (async (req: Request) => {
    const r = await send(req)
    if (!r.ok) throw new ReplError(r.error, r.logs ?? [])
    return r
  }) as Call

const when = (iso: string) => iso.replace("T", " ").replace(/\.\d+Z$/, "Z")
const firstLine = (source: string) => {
  const s = source.trimEnd()
  const line = s.split("\n")[0]
  return line.length > 72 ? line.slice(0, 71) + "…" : line + (s.includes("\n") ? " …" : "")
}

export async function evalCode(call: Call, code: string): Promise<string> {
  try {
    const r = await call({ op: "eval", code })
    return [...r.logs.map(l => `log: ${l}`), r.value ?? "null"].join("\n")
  } catch (e) {
    if (e instanceof ReplError) throw new ReplError([...e.logs.map(l => `log: ${l}`), e.message].join("\n"), e.logs)
    throw e
  }
}

export async function def(call: Call, name: string, source: string, hostApi?: number): Promise<string> {
  const r = await call({ op: "def", name, source, ...(hostApi === undefined ? {} : { hostApi }) })
  return r.state === "failed" ? `${name}: written, but failed on the device: ${r.error}`
    : r.state === "pending" ? `${name}: written; the device hasn't evaluated it yet`
      : `${name}: loaded`
}

export async function undef(call: Call, name: string): Promise<string> {
  const r = await call({ op: "undef", name })
  return r.existed ? `${name}: removed` : `${name} was not defined`
}

const defLine = (d: DefInfo) =>
  `${d.state.padEnd(7)} ${d.name.padEnd(28)} api ${d.hostApi}  ${when(d.updatedAt)}  ${d.device}` +
  (d.error ? `\n        ${d.error}` : "") + (d.lastRunError ? `\n        last run: ${d.lastRunError}` : "")

export async function ls(call: Call): Promise<string> {
  const r = await call({ op: "ls" })
  const lines = [`image: ${r.mode}; code doc: ${r.codeDoc}`]
  lines.push(...(r.defs.length ? r.defs.map(defLine) : ["(no definitions)"]))
  if (r.commands.length) lines.push("commands: " + r.commands.map(c => `${c.title} (${c.id}${c.builtIn ? ", built-in" : ""})`).join(", "))
  return lines.join("\n")
}

export async function show(call: Call, name: string): Promise<string> {
  const { def: d } = await call({ op: "show", name })
  return `${defLine(d)}\n\n${d.source ?? ""}`
}

/** The device's code doc, loaded here with its history; null when it has none yet. */
export async function codeDoc(call: Call): Promise<A.Doc<unknown> | null> {
  const r = await call({ op: "doc" })
  return r.doc === null ? null : A.load(Uint8Array.from(atob(r.doc), c => c.charCodeAt(0)))
}

export async function versions(call: Call, name: string): Promise<DefVersion[]> {
  const doc = await codeDoc(call)
  return doc === null ? [] : codeHistory(doc, name)
}

export async function history(call: Call, name: string): Promise<string> {
  const doc = await codeDoc(call)
  const vs = doc === null ? [] : codeHistory(doc, name)
  if (vs.length === 0) return `${name} has no history`
  const defined = doc !== null && readCode(doc).defs[name] !== undefined
  const lines = vs.map((v, i) =>
    `#${i + 1}  ${when(v.def.updatedAt.toISOString())}  ${v.def.device}  api ${v.def.hostApi}${v.current ? "  (current)" : ""}\n    ${firstLine(v.def.source)}`)
  if (!defined) lines.push(`(${name} is not defined now)`)
  return lines.join("\n")
}

/**
 * Writes version [n] (1-based, as `history` numbers them) of [name] again as a new entry. Without
 * [n], the newest version before the current one, or the newest one when [name] is undefined.
 */
export async function rollback(call: Call, name: string, n?: number): Promise<string> {
  const vs = await versions(call, name)
  if (vs.length === 0) throw new ReplError(`${name} has no history`)
  let pick: DefVersion | undefined
  if (n !== undefined) {
    pick = vs[n - 1]
    if (!pick) throw new ReplError(`${name} has versions 1–${vs.length}`)
  } else {
    const cur = vs.findIndex(v => v.current)
    pick = cur === -1 ? vs.at(-1) : vs[cur - 1]
    if (!pick) throw new ReplError(`${name} has no version before the current one`)
  }
  const r = await def(call, name, pick.def.source, pick.def.hostApi)
  return `${r} (version #${vs.indexOf(pick) + 1} from ${when(pick.def.updatedAt.toISOString())})`
}
