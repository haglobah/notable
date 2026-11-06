/* Messages between the app and the MuPDF worker (pdf.worker.ts). */
import type { EpubLayout, NotebookEntry } from "@notable/schema"
import type { PageBackground } from "./geometry.ts"

/** A source document as MuPDF opens it. EPUBs exist only for one layout, so it is part of the key. */
export type DocKey = { format: "pdf"; blob: string } | { format: "epub"; blob: string; layout: EpubLayout }

/** NotebookEntry with dates as ISO strings, so it survives structured cloning unchanged. */
export type EntryWire = Omit<NotebookEntry, "createdAt" | "updatedAt"> & { createdAt: string; updatedAt: string }

export type WorkerRequest =
  | { id: number; type: "sizes"; key: DocKey }
  | { id: number; type: "render"; key: DocKey; index: number; width: number }
  | { id: number; type: "project"; doc: Uint8Array; entry: EntryWire }

export type WorkerResponse =
  | { id: number; type: "sizes"; sizes: [number, number][] }
  | { id: number; type: "render"; bitmap: ImageBitmap }
  | { id: number; type: "project"; pdf: Uint8Array }
  | { id: number; type: "progress"; done: number; total: number }
  | { id: number; type: "error"; message: string }

export const docKeyId = (k: DocKey) => (k.format === "pdf" ? `pdf:${k.blob}` : `epub:${k.blob}:${k.layout.w}x${k.layout.h}@${k.layout.em}`)

/** The worker key of a page's source document; null for backgrounds MuPDF doesn't draw. */
export const docKeyOf = (bg: PageBackground): DocKey | null =>
  bg.kind !== "document" ? null : bg.format === "pdf" ? { format: "pdf", blob: bg.blob } : { format: "epub", blob: bg.blob, layout: bg.layout }

export const entryToWire = (e: NotebookEntry): EntryWire => ({ ...e, createdAt: e.createdAt.toISOString(), updatedAt: e.updatedAt.toISOString() })
export const entryFromWire = (e: EntryWire): NotebookEntry => ({ ...e, createdAt: new Date(e.createdAt), updatedAt: new Date(e.updatedAt) })
