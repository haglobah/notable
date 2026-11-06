/*
 * The app's side of the MuPDF worker (worker.ts) and of the blobs: one worker per reader, a promise
 * per request. Each app says how to start its worker and where its blobs are.
 */
import type { NotebookEntry } from "@notable/schema"
import { entryToWire, type DocKey, type WorkerRequest, type WorkerResponse } from "./pdfProtocol.ts"
import type { BlobUrl } from "./worker.ts"

export type ReaderOptions = {
  /** Starts the app's worker entry, which calls servePdfWorker (`new Worker(new URL(…), {type: "module"})`). */
  worker: () => Worker
  /** Where a blob is; the same as the worker's. */
  blobUrl: BlobUrl
}

export type Reader = {
  /** Width and height in pt of every page of a source document. */
  pageSizes(key: DocKey): Promise<[number, number][]>
  /** A document page as a bitmap [width] device px wide. */
  renderPage(key: DocKey, index: number, width: number): Promise<ImageBitmap>
  /** Download-as-PDF; `doc` is Automerge save() bytes of the notebook doc. */
  projectPdf(doc: Uint8Array, entry: NotebookEntry, progress?: (done: number, total: number) => void): Promise<Uint8Array>
  /** An image blob (page images, image backgrounds); the last few are kept. */
  loadImage(hash: string): Promise<ImageBitmap>
}

/** Omit over each member of a union (plain Omit would merge the members). */
type Request = WorkerRequest extends infer R ? (R extends WorkerRequest ? Omit<R, "id"> : never) : never

type Pending = { resolve: (r: WorkerResponse) => void; reject: (e: Error) => void; progress?: (done: number, total: number) => void }

export function createReader(opts: ReaderOptions): Reader {
  let worker: Worker | undefined
  let seq = 0
  const pending = new Map<number, Pending>()

  function call(req: Request, transfer: Transferable[] = [], progress?: Pending["progress"]): Promise<WorkerResponse> {
    if (!worker) {
      worker = opts.worker()
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

  const images = new Map<string, Promise<ImageBitmap>>()

  return {
    async pageSizes(key) {
      const r = await call({ type: "sizes", key })
      if (r.type !== "sizes") throw new Error("unexpected worker reply")
      return r.sizes
    },
    async renderPage(key, index, width) {
      const r = await call({ type: "render", key, index, width })
      if (r.type !== "render") throw new Error("unexpected worker reply")
      return r.bitmap
    },
    async projectPdf(doc, entry, progress) {
      const r = await call({ type: "project", doc, entry: entryToWire(entry) }, [doc.buffer], progress)
      if (r.type !== "project") throw new Error("unexpected worker reply")
      return r.pdf
    },
    loadImage(hash) {
      let p = images.get(hash)
      if (!p) {
        p = fetch(opts.blobUrl(hash), { credentials: "same-origin" }).then(async r => {
          if (!r.ok) throw new Error(`image ${hash.slice(0, 12)}…: HTTP ${r.status}`)
          return createImageBitmap(await r.blob())
        })
        p.catch(() => images.delete(hash))
        images.set(hash, p)
        if (images.size > 16) images.delete(images.keys().next().value!)
      }
      return p
    },
  }
}
