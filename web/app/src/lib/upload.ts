/*
 * Adding a PDF or EPUB: read it with MuPDF, hash it, upload the bytes, then create the notebook doc
 * and its library entry. The docs are written only after the server has the file, so a failed
 * upload never leaves an entry pointing at a missing blob.
 */
import type { DocHandle, Repo } from "@automerge/automerge-repo"
import { sha256 } from "@noble/hashes/sha2.js"
import { bytesToHex } from "@noble/hashes/utils.js"
import { notebookEntryValue, notebookValue, type Background, type Page, type Source } from "@notable/schema"
import { UploadFailure, type DocumentKind } from "./errors.ts"
import { countPages, EPUB_LAYOUT } from "./pages.ts"

export { UploadFailure, EPUB_LAYOUT }
export type { DocumentKind }

export type Stage =
  | { stage: "reading" }
  | { stage: "hashing"; done: number; total: number }
  | { stage: "uploading"; done: number; total: number }
  | { stage: "saving" }

/** PUTs the file to /api/blobs/:hash. Throws UploadFailure. */
export type BlobTransport = (hash: string, file: Blob, onProgress: (done: number) => void) => Promise<void>

export function detectKind(file: File): DocumentKind | null {
  const name = file.name.toLowerCase()
  if (file.type === "application/pdf" || name.endsWith(".pdf")) return "pdf"
  if (file.type === "application/epub+zip" || name.endsWith(".epub")) return "epub"
  return null
}

const CHUNK = 4 * 1024 * 1024

/** Hashes in chunks, so a large file is never held twice. */
export async function sha256File(file: Blob, onProgress: (done: number) => void = () => {}, chunk = CHUNK): Promise<string> {
  const h = sha256.create()
  for (let at = 0; at < file.size; at += chunk) {
    const end = Math.min(at + chunk, file.size)
    h.update(new Uint8Array(await file.slice(at, end).arrayBuffer()))
    onProgress(end)
  }
  return bytesToHex(h.digest())
}

export const formatSize = (bytes: number) =>
  bytes >= 1024 * 1024 ? `${Math.round(bytes / 1024 / 1024)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`

/** Maps a server answer to the failure the person sees. */
export async function failureFor(status: number, body: () => Promise<string>): Promise<UploadFailure> {
  if (status === 413) return new UploadFailure("too-large", "This file is larger than the server accepts.")
  if (status === 400) return new UploadFailure("hash-mismatch", "The file changed or was damaged on the way. Try adding it again.")
  if (status === 401 || status === 403) return new UploadFailure("unauthorized", "Your session has ended. Log in again, then add the file.")
  let detail = ""
  try { detail = (JSON.parse(await body()) as { error?: string }).error ?? "" } catch { /* not JSON */ }
  return new UploadFailure("server", `The server couldn't store the file (${status}${detail ? `: ${detail}` : ""}).`)
}

const networkFailure = () => new UploadFailure("network", "The server can't be reached. Check your connection, then try again.")

/** fetch has no upload progress; it reports 0 and then the full size. Used in tests and as a fallback. */
export function fetchTransport(base = "", headers: Record<string, string> = {}): BlobTransport {
  return async (hash, file, onProgress) => {
    onProgress(0)
    let res: Response
    try {
      res = await fetch(`${base}/api/blobs/${hash}`, { method: "PUT", headers, body: file, credentials: "same-origin" })
    } catch {
      throw networkFailure()
    }
    if (res.status !== 201) throw await failureFor(res.status, () => res.text())
    onProgress(file.size)
  }
}

/** The browser transport: XMLHttpRequest is still the only way to see upload progress. */
export function xhrTransport(): BlobTransport {
  return (hash, file, onProgress) =>
    new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      xhr.open("PUT", `/api/blobs/${hash}`)
      xhr.upload.onprogress = e => onProgress(e.loaded)
      xhr.onerror = () => reject(networkFailure())
      xhr.onload = () => {
        if (xhr.status === 201) return resolve()
        void failureFor(xhr.status, async () => xhr.responseText).then(reject)
      }
      xhr.send(file)
    })
}

export type AddDocument = {
  file: File
  folderId: string | null
  repo: Repo
  library: DocHandle<unknown>
  transport: BlobTransport
  maxBlobBytes: number
  onStage?: (s: Stage) => void
}

export async function addDocument(o: AddDocument): Promise<{ notebookId: string; docUrl: string }> {
  const stage = o.onStage ?? (() => {})
  const kind = detectKind(o.file)
  if (!kind) throw new UploadFailure("unsupported", `${o.file.name} isn't a PDF or EPUB. Notable can add only those.`)
  if (o.file.size > o.maxBlobBytes) {
    throw new UploadFailure("too-large", `${o.file.name} is ${formatSize(o.file.size)}. The server accepts files up to ${formatSize(o.maxBlobBytes)}.`)
  }

  stage({ stage: "reading" })
  // MuPDF needs the whole file; this is the one full copy in memory.
  let bytes: Uint8Array | null = new Uint8Array(await o.file.arrayBuffer())
  const pageCount = await countPages(bytes, kind)
  bytes = null

  const total = o.file.size
  stage({ stage: "hashing", done: 0, total })
  const hash = await sha256File(o.file, done => stage({ stage: "hashing", done, total }))

  stage({ stage: "uploading", done: 0, total })
  await o.transport(hash, o.file, done => stage({ stage: "uploading", done, total }))

  stage({ stage: "saving" })
  const now = new Date()
  const notebookId = crypto.randomUUID()
  const background = (type: string): Background => ({ type, native: null, blob: hash })
  // Same shape as Android's PDF import: one page per document page, pinned to it with "pdfN".
  // EPUB pages use "pdfN" too; MuPDF opens either format, laid out with source.layout.
  const pages: Page[] = Array.from({ length: pageCount }, (_, i) => ({
    id: crypto.randomUUID(), background: background(`pdf${i}`), strokes: {}, images: {}, createdAt: now, updatedAt: now,
  }))
  const handle = o.repo.create(notebookValue({
    notebookId,
    openPageId: pages[0].id,
    defaultBackground: background("autoPdf"),
    pageOrder: pages.map(p => p.id),
    pages: Object.fromEntries(pages.map(p => [p.id, p])),
  }))
  const source: Source = kind === "pdf" ? { kind: "pdf", blob: hash } : { kind: "epub", blob: hash, layout: EPUB_LAYOUT }
  o.library.change((d: any) => {
    d.notebooks[notebookId] = notebookEntryValue({
      id: notebookId,
      docUrl: handle.url,
      title: o.file.name.replace(/\.(pdf|epub)$/i, ""),
      folderId: o.folderId,
      kind: "notebook",
      source,
      createdAt: now,
      updatedAt: now,
    })
  })
  return { notebookId, docUrl: handle.url }
}
