/// <reference lib="webworker" />
/*
 * MuPDF off the main thread: page sizes and page bitmaps for the reader, and the whole
 * Download-as-PDF projection. Blobs come from /api/blobs, through the service worker's cache, so
 * documents read before stay readable and downloadable offline.
 */
import { docKeyId, entryFromWire, type DocKey, type WorkerRequest, type WorkerResponse } from "./pdfProtocol.ts"

declare const self: DedicatedWorkerGlobalScope

// mupdf.js initializes its WASM with top-level await. Importing it statically would keep this
// module from finishing evaluation, and messages that arrive before onmessage is set are dropped,
// so the reader's first requests were never answered. Load it on first use instead.
type Mupdf = typeof import("mupdf")
let mupdfModule: Promise<Mupdf> | undefined
const loadMupdf = () => (mupdfModule ??= import("mupdf"))

const post = (m: WorkerResponse, transfer: Transferable[] = []) => self.postMessage(m, transfer)

async function fetchBlob(hash: string): Promise<Uint8Array> {
  const res = await fetch(`/api/blobs/${hash}`, { credentials: "same-origin" })
  if (res.status === 401) throw new Error("Your session has ended. Log in again.")
  if (res.status === 404) throw new Error(`The file ${hash.slice(0, 12)}… isn't on the server.`)
  if (!res.ok) throw new Error(`Loading ${hash.slice(0, 12)}… failed: HTTP ${res.status}`)
  return new Uint8Array(await res.arrayBuffer())
}

/** A few open documents; opening a big PDF again for every page would be slow. */
const open = new Map<string, Promise<InstanceType<Mupdf["Document"]>>>()
const MAX_OPEN = 3

function document(key: DocKey) {
  const id = docKeyId(key)
  let d = open.get(id)
  if (d) {
    open.delete(id)
    open.set(id, d)
    return d
  }
  d = Promise.all([fetchBlob(key.blob), loadMupdf()]).then(([bytes, mupdf]) => {
    const doc = mupdf.Document.openDocument(bytes, key.format === "pdf" ? "application/pdf" : "application/epub+zip")
    if (key.format === "epub") doc.layout(key.layout.w, key.layout.h, key.layout.em)
    return doc
  })
  d.catch(() => open.delete(id))
  open.set(id, d)
  while (open.size > MAX_OPEN) {
    const [oldId, old] = open.entries().next().value!
    open.delete(oldId)
    old.then(doc => doc.destroy(), () => {})
  }
  return d
}

async function sizes(key: DocKey): Promise<[number, number][]> {
  const doc = await document(key)
  return Array.from({ length: doc.countPages() }, (_, i) => {
    const page = doc.loadPage(i)
    const b = page.getBounds()
    page.destroy()
    return [b[2] - b[0], b[3] - b[1]] as [number, number]
  })
}

async function render(key: DocKey, index: number, width: number): Promise<ImageBitmap> {
  const [doc, mupdf] = await Promise.all([document(key), loadMupdf()])
  if (index < 0 || index >= doc.countPages()) throw new Error(`page ${index + 1} does not exist`)
  const page = doc.loadPage(index)
  try {
    const b = page.getBounds()
    const s = width / (b[2] - b[0])
    const pix = page.toPixmap(mupdf.Matrix.scale(s, s), mupdf.ColorSpace.DeviceRGB, false)
    try {
      const w = pix.getWidth(), h = pix.getHeight(), stride = pix.getStride(), rgb = pix.getPixels()
      const rgba = new Uint8ClampedArray(w * h * 4)
      for (let y = 0; y < h; y++) {
        for (let x = 0, i = y * stride, o = y * w * 4; x < w; x++, i += 3, o += 4) {
          rgba[o] = rgb[i]; rgba[o + 1] = rgb[i + 1]; rgba[o + 2] = rgb[i + 2]; rgba[o + 3] = 255
        }
      }
      return await createImageBitmap(new ImageData(rgba, w, h))
    } finally {
      pix.destroy()
    }
  } finally {
    page.destroy()
  }
}

self.onmessage = async (ev: MessageEvent<WorkerRequest>) => {
  const req = ev.data
  try {
    if (req.type === "sizes") post({ id: req.id, type: "sizes", sizes: await sizes(req.key) })
    else if (req.type === "render") {
      const bitmap = await render(req.key, req.index, req.width)
      post({ id: req.id, type: "render", bitmap }, [bitmap])
    } else if (req.type === "project") {
      // Automerge's WASM is only needed for downloads; reading pages shouldn't wait for it.
      const [A, { project }] = await Promise.all([import("@automerge/automerge"), import("./project.ts")])
      const pdf = await project(A.load(req.doc), entryFromWire(req.entry), fetchBlob,
        (done, total) => post({ id: req.id, type: "progress", done, total }))
      post({ id: req.id, type: "project", pdf }, [pdf.buffer])
    }
  } catch (e) {
    post({ id: req.id, type: "error", message: (e as Error).message || String(e) })
  }
}
