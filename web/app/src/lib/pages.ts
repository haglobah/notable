/*
 * MuPDF, loaded on first use. The WASM is 3.6 MB brotli, so the library view never pays for it;
 * only adding a document (and, later, reading or downloading one) does.
 */
import type { EpubLayout } from "@notable/schema"
import { UploadFailure, type DocumentKind } from "./errors.ts"
import { epubMeta, pdfMeta, type Extracted } from "./bookMeta.ts"

/**
 * EPUB pages exist only for one layout (ADR-001). This is the Note Air 5C's screen at its physical
 * size: 1860 × 2480 px at 300 ppi is 446 × 595 pt, so text on the Boox is printed size.
 */
export const EPUB_LAYOUT: EpubLayout = { w: 446, h: 595, em: 12 }

type Mupdf = typeof import("mupdf")
let mupdf: Promise<Mupdf> | undefined
export const loadMupdf = () => (mupdf ??= import("mupdf"))

const MIME: Record<DocumentKind, string> = { pdf: "application/pdf", epub: "application/epub+zip" }

export async function countPages(bytes: Uint8Array, kind: DocumentKind): Promise<number> {
  return (await inspect(bytes, kind)).pageCount
}

/** The metadata, or why it couldn't be read: a file with unreadable metadata is still added. */
export type Inspected = { pageCount: number } & ({ ok: true; extracted: Extracted } | { ok: false; error: string })

/**
 * Opens the document the way every reader of it will, so a file MuPDF can't read is refused up
 * front, and reads its metadata (docs/plans/ebook-features.md).
 */
export async function inspect(bytes: Uint8Array, kind: DocumentKind): Promise<Inspected> {
  const m = await loadMupdf()
  let doc: InstanceType<Mupdf["Document"]> | undefined
  let pageCount: number
  let pdfInfo: Inspected | undefined
  try {
    doc = m.Document.openDocument(bytes, MIME[kind])
    if (kind === "epub") doc.layout(EPUB_LAYOUT.w, EPUB_LAYOUT.h, EPUB_LAYOUT.em)
    pageCount = doc.countPages()
    if (pageCount < 1) throw new Error("no pages")
    if (kind === "pdf") pdfInfo = await metaOf(pageCount, async () => pdfMeta(k => doc!.getMetaData(k)))
  } catch (e) {
    throw new UploadFailure("unreadable", `This file can't be opened as ${kind === "pdf" ? "a PDF" : "an EPUB"}: ${(e as Error).message}`)
  } finally {
    doc?.destroy()
  }
  return pdfInfo ?? metaOf(pageCount, () => epubMeta(bytes))
}

async function metaOf(pageCount: number, read: () => Promise<Extracted>): Promise<Inspected> {
  try {
    return { pageCount, ok: true, extracted: await read() }
  } catch (e) {
    return { pageCount, ok: false, error: (e as Error).message || String(e) }
  }
}
