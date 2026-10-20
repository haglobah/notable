/*
 * The live REPL's messages (ADR-002). Android answers them over a WebSocket on 127.0.0.1
 * (app/src/main/java/com/ethran/notable/image/ReplProtocol.kt); the PWA's REPL panel answers them
 * in the page (device.ts). Each request has an `id`, echoed in its reply.
 */

export type Request =
  | { op: "hello"; token?: string }
  /** Runs code in the live runtime; writes nothing to the doc. */
  | { op: "eval"; code: string }
  /** Commits a definition to the code doc, which syncs it to every device. */
  | { op: "def"; name: string; source: string; hostApi?: number }
  | { op: "undef"; name: string }
  | { op: "ls" }
  | { op: "show"; name: string }
  /** The code doc's save() bytes, base64, for history and rollback. */
  | { op: "doc" }

export type DefState = "loaded" | "failed" | "pending"

export type DefInfo = {
  name: string
  hostApi: number
  device: string
  /** ISO-8601 */
  updatedAt: string
  state: DefState
  error?: string
  lastRunError?: string
  source?: string
}

export type CommandInfo = { id: string; title: string; builtIn: boolean }

export type Replies = {
  hello: { device: string; hostApi: number; protocol: number }
  eval: { value: string | null; logs: string[] }
  def: { state: DefState; error?: string }
  undef: { existed: boolean }
  ls: { mode: string; codeDoc: string; defs: DefInfo[]; commands: CommandInfo[] }
  show: { def: DefInfo }
  doc: { doc: string | null }
}

export type Ok<O extends Request["op"]> = { id: unknown; ok: true } & Replies[O]
export type Failure = { id: unknown; ok: false; error: string; logs?: string[] }
export type Reply<O extends Request["op"] = Request["op"]> = Ok<O> | Failure

export const PROTOCOL = 1
export const DEFAULT_PORT = 7357
