/* The CLI's side of the REPL socket: one connection, requests answered in order by id. */
import WebSocket from "ws"
import { checked, ReplError, type Call } from "./commands.ts"
import type { Ok, Reply, Request } from "./protocol.ts"

export type Connected = { call: Call; hello: Ok<"hello">; close: () => void }

export async function connect(url: string, token: string, timeoutMs = 10_000): Promise<Connected> {
  // No Origin header: the device refuses handshakes that carry one (browsers always do).
  const ws = new WebSocket(url)
  await new Promise<void>((resolve, reject) => {
    const t = setTimeout(() => reject(new ReplError(`no answer from ${url} within ${timeoutMs} ms`)), timeoutMs)
    ws.once("open", () => { clearTimeout(t); resolve() })
    ws.once("error", e => {
      clearTimeout(t)
      reject(new ReplError(`can't reach ${url}: ${e.message}. Is the app open, the REPL on, and adb forward running?`))
    })
  })
  let next = 1
  const waiting = new Map<number, { resolve: (r: Reply) => void; reject: (e: Error) => void }>()
  ws.on("message", data => {
    const r = JSON.parse(String(data)) as Reply
    const w = waiting.get(r.id as number)
    if (w) {
      waiting.delete(r.id as number)
      w.resolve(r)
    }
  })
  ws.on("close", (code, reason) => {
    for (const w of waiting.values()) w.reject(new ReplError(`the device closed the connection (${code}${reason.length ? ` ${reason}` : ""})`))
    waiting.clear()
  })
  const send = (req: Request) => new Promise<Reply>((resolve, reject) => {
    if (ws.readyState !== WebSocket.OPEN) return reject(new ReplError("not connected"))
    const id = next++
    waiting.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, ...req }))
  })
  const call = checked(send)
  try {
    const hello = await call({ op: "hello", token })
    return { call, hello, close: () => ws.close(1000) }
  } catch (e) {
    ws.terminate()
    throw e
  }
}
