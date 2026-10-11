/* The app's side of the MuPDF worker: one worker, promise per request. */
import type { NotebookEntry } from "@notable/schema"
import { entryToWire, type DocKey, type WorkerRequest, type WorkerResponse } from "./pdfProtocol.ts"

/** Omit over each member of a union (plain Omit would merge the members). */
type Request = WorkerRequest extends infer R ? (R extends WorkerRequest ? Omit<R, "id"> : never) : never

type Pending = { resolve: (r: WorkerResponse) => void; reject: (e: Error) => void; progress?: (done: number, total: number) => void }

let worker: Worker | undefined
let seq = 0
const pending = new Map<number, Pending>()

function call(req: Request, transfer: Transferable[] = [], progress?: Pending["progress"]): Promise<WorkerResponse> {
  if (!worker) {
    worker = new Worker(new URL("./pdf.worker.ts", import.meta.url), { type: "module" })
    worker.onmessage = (ev: MessageEvent<WorkerResponse>) => {
      const m = ev.data, p = pending.get(m.id)
      if (!p) return
      if (m.type === "progress") return p.progress?.(m.done, m.total)
      pending.delete(m.id)
      if (m.type === "error") p.reject(new Error(m.message))
      else p.resolve(m)
    }
    // A crashed worker (e.g. out of memory) fails everything in flight; the next call starts a new one.
    worker.onerror = ev => {
      ev.preventDefault()
      const err = new Error(ev.message || "The document engine stopped unexpectedly.")
      for (const p of pending.values()) p.reject(err)
      pending.clear()
      worker?.terminate()
      worker = undefined
    }
  }
  const id = ++seq
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject, progress })
    worker!.postMessage({ ...req, id } as WorkerRequest, transfer)
  })
}

export async function pageSizes(key: DocKey): Promise<[number, number][]> {
  const r = await call({ type: "sizes", key })
  if (r.type !== "sizes") throw new Error("unexpected worker reply")
  return r.sizes
}

export async function renderPage(key: DocKey, index: number, width: number): Promise<ImageBitmap> {
  const r = await call({ type: "render", key, index, width })
  if (r.type !== "render") throw new Error("unexpected worker reply")
  return r.bitmap
}

/** `doc` is Automerge save() bytes of the notebook doc. */
export async function projectPdf(doc: Uint8Array, entry: NotebookEntry, progress?: (done: number, total: number) => void): Promise<Uint8Array> {
  const r = await call({ type: "project", doc, entry: entryToWire(entry) }, [doc.buffer], progress)
  if (r.type !== "project") throw new Error("unexpected worker reply")
  return r.pdf
}
