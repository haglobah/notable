/*
 * The Android ↔ PWA projection contract. Builds a few notebooks that cover every page and stroke
 * kind, runs the real project() on them, and summarizes the PDFs (page bounds, drawn content,
 * annotations, embedded files). `npm run projection-fixture` writes the summary to
 * web/schema/fixtures/projection-v1.json; Kotlin's ProjectionParityTest plans the same notebooks
 * and must match it. `scripts/compare-projection.ts` uses summarize() on a PDF from the device.
 */
import { createHash } from "node:crypto"
import * as A from "@automerge/automerge"
import * as mupdf from "mupdf"
import type { Background, Image, NotebookEntry, Page, Source } from "@notable/schema"
import { EMBEDDED_DOC, EMBEDDED_META, project } from "../src/lib/project.ts"
import { makeEpub, makePdf } from "./files.ts"
import { BLACK, RED, nativeBg, notebookDoc, page, stroke } from "./fixtures.ts"

const T = new Date("2026-10-11T10:00:00Z")
const sha = (b: Uint8Array) => createHash("sha256").update(b).digest("hex")

export type Annotation =
  | { type: "Ink"; rect: number[]; inkList: number[][][]; color: number[]; opacity: number; borderWidth: number }
  | { type: "Stamp"; rect: number[] }

export type PageSummary = { bounds: number[]; contents: string; annotations: Annotation[] }

export type Summary = {
  pages: PageSummary[]
  embedded: string[]
  /** Heads of the embedded notebook doc. */
  docHeads: string[]
  meta: unknown
  title: string | undefined
}

export type Case = {
  name: string
  entry: NotebookEntry
  doc: A.Doc<unknown>
  blobs: Record<string, Uint8Array>
}

/** Page bounds of each source document (EPUBs laid out), and pixel sizes of images: what Kotlin can't compute without MuPDF. */
export function sourceInfo(c: Case) {
  const documents: Record<string, number[][]> = {}
  const images: Record<string, number[]> = {}
  const src = c.entry.source
  for (const [hash, bytes] of Object.entries(c.blobs)) {
    if (src.kind !== "none" && src.blob === hash) continue
    try {
      const img = new mupdf.Image(bytes)
      images[hash] = [img.getWidth(), img.getHeight()]
      img.destroy()
    } catch { /* a document */ }
  }
  const pdfBlobs = new Set<string>()
  const nb = A.toJS(c.doc) as { defaultBackground: Background; pages: Record<string, { background: Background }> }
  for (const b of [nb.defaultBackground, ...Object.values(nb.pages).map(p => p.background)]) {
    if (b.blob && !(String(b.blob) in images)) pdfBlobs.add(String(b.blob))
  }
  if (src.kind !== "none") pdfBlobs.add(src.blob)
  for (const hash of pdfBlobs) {
    const bytes = c.blobs[hash]
    if (!bytes) continue
    if (src.kind === "epub" && src.blob === hash) {
      const book = mupdf.Document.openDocument(bytes, "application/epub+zip")
      book.layout(src.layout.w, src.layout.h, src.layout.em)
      documents[`epub:${hash}`] = Array.from({ length: book.countPages() }, (_, i) => [...book.loadPage(i).getBounds()])
      book.destroy()
    }
    const pdf = src.kind === "epub" && src.blob === hash ? null : new mupdf.PDFDocument(bytes)
    if (pdf) {
      documents[`pdf:${hash}`] = Array.from({ length: pdf.countPages() }, (_, i) => [...pdf.loadPage(i).getBounds()])
      pdf.destroy()
    }
  }
  return { documents, images }
}

export function summarize(bytes: Uint8Array): Summary {
  const pdf = new mupdf.PDFDocument(bytes)
  try {
    const pages: PageSummary[] = []
    for (let i = 0; i < pdf.countPages(); i++) {
      const p = pdf.loadPage(i)
      const contents = p.getObject().get("Contents")
      pages.push({
        bounds: [...p.getBounds()],
        contents: contents.isStream() ? contents.readStream().asString() : "",
        annotations: p.getAnnotations().map((a): Annotation => {
          const rect = [...a.getBounds()]
          if (a.getType() === "Stamp") return { type: "Stamp", rect }
          if (a.getType() !== "Ink") throw new Error(`unexpected annotation ${a.getType()}`)
          return {
            type: "Ink", rect, inkList: a.getInkList().map(s => s.map(pt => [pt[0], pt[1]])),
            color: [...a.getColor()], opacity: a.getOpacity(), borderWidth: a.getBorderWidth(),
          }
        }),
      })
    }
    const files = pdf.getEmbeddedFiles()
    const content = (name: string) => pdf.getEmbeddedFileContents(files[name])!.asUint8Array()
    return {
      pages,
      embedded: Object.keys(files).sort(),
      docHeads: files[EMBEDDED_DOC] ? A.getHeads(A.load(content(EMBEDDED_DOC))) : [],
      meta: files[EMBEDDED_META] ? JSON.parse(new TextDecoder().decode(content(EMBEDDED_META))) : null,
      title: pdf.getMetaData("info:Title"),
    }
  } finally {
    pdf.destroy()
  }
}

function entryOf(id: string, title: string, source: Source, kind: "notebook" | "quickPage" = "notebook"): NotebookEntry {
  return { id, docUrl: `automerge:${id}Parity`, title, folderId: null, kind, source, createdAt: T, updatedAt: new Date("2026-10-11T12:34:56.789Z") }
}

function image(id: string, blob: string, x: number, y: number, width: number, height: number, at = T): Image {
  return { id, blob, rect: { x, y, width, height }, createdAt: at, updatedAt: at }
}

function withImages(p: Page, images: Image[]): Page {
  return { ...p, images: Object.fromEntries(images.map(i => [i.id, i])) }
}

function png(w: number, h: number, gray: number): Uint8Array {
  const pix = new mupdf.Pixmap(mupdf.ColorSpace.DeviceRGB, [0, 0, w, h], false)
  pix.clear(gray)
  const out = pix.asPNG()
  pix.destroy()
  return out
}

const MID = [[930, 1240], [1100, 1240], [1395, 1240]]
const later = (ms: number) => new Date(T.getTime() + ms)

export function cases(): Case[] {
  const pdf = makePdf(2)
  const pdfHash = sha(pdf)
  const epub = makeEpub(2)
  const epubHash = sha(epub)
  const img = png(40, 20, 128)
  const imgHash = sha(img)
  const tall = png(30, 90, 200)
  const tallHash = sha(tall)
  const pdfBg = (n: number): Background => ({ type: `pdf${n}`, native: null, blob: pdfHash })
  const epubBg = (type: string): Background => ({ type, native: null, blob: epubHash })
  const imageBg = (type: string, blob: string): Background => ({ type, native: null, blob })

  const pdfDoc = notebookDoc([
    page("p0", pdfBg(0), [
      stroke("s-ball", MID, { size: 9 }),
      stroke("s-fountain", [[100, 200, 0.1], [300, 260, 0.5], [500, 400, 0.95]], { pen: "FOUNTAIN", size: 12 }),
      stroke("s-dot", [[700, 700]], { size: 8 }),
    ]),
    withImages(page("p1", pdfBg(1), [stroke("s-marker", [[100, 100], [600, 100]], { pen: "MARKER", color: RED, size: 30 })]),
      [image("i-1", imgHash, 200, 300, 400, 200), image("i-0", imgHash, 900, 1500, 200, 100)]),
    page("p2", pdfBg(5), [stroke("s-lost", [[50, 50], [60, 80]])]),
  ])

  const epubDoc = notebookDoc([
    page("e0", epubBg("autoPdf")),
    page("e1", epubBg("pdf1"), [stroke("s-e", MID), stroke("s-pencil", [[10, 10, 0.3], [400, 30, 0.6]], { pen: "PENCIL", color: -12566464 })]),
  ], { defaultBackground: epubBg("autoPdf") })

  const native = notebookDoc([
    page("n-lined", nativeBg("lined"), [stroke("n-s", [[100, 100], [1300, 120]], { color: 0x80112233 | 0 })]),
    page("n-dotted", nativeBg("dotted")),
    page("n-squared", nativeBg("squared")),
    page("n-hexed", nativeBg("hexed"), [{ ...stroke("n-late", [[10, 10], [20, 20]]), createdAt: later(5) }, stroke("n-early", [[30, 30], [40, 40]])]),
    page("n-tall", nativeBg("blank"), [stroke("n-down", [[100, 100], [200, 3000]], { pen: "BRUSH", size: 20 })]),
    page("n-img", imageBg("image", imgHash)),
    page("n-repeat", imageBg("imagerepeating", tallHash), [stroke("n-r", [[10, 2500], [20, 2600]])]),
    page("n-cover", imageBg("coverImage", imgHash)),
    page("n-weird", nativeBg("weird")),
    page("n-noimg", { type: "image", native: null, blob: null }),
  ], { canvasWidth: 1404 })

  return [
    { name: "pdf", entry: entryOf("nb-pdf", "Paper: CRDTs", { kind: "pdf", blob: pdfHash }), doc: pdfDoc, blobs: { [pdfHash]: pdf, [imgHash]: img } },
    { name: "epub", entry: entryOf("nb-epub", "Book", { kind: "epub", blob: epubHash, layout: { w: 446, h: 595, em: 12 } }), doc: epubDoc, blobs: { [epubHash]: epub } },
    { name: "native", entry: entryOf("nb-native", "", { kind: "none" }), doc: native, blobs: { [imgHash]: img, [tallHash]: tall } },
  ]
}

export const blobSource = (blobs: Record<string, Uint8Array>) => async (h: string) => {
  const b = blobs[h]
  if (!b) throw new Error(`no blob ${h}`)
  return b
}

/** What the fixture holds per case: the inputs Kotlin needs, and project()'s output summarized. */
export async function fixtureCase(c: Case) {
  const out = await project(c.doc, c.entry, blobSource(c.blobs))
  return {
    name: c.name,
    entry: { ...c.entry, createdAt: c.entry.createdAt.toISOString(), updatedAt: c.entry.updatedAt.toISOString() },
    notebook: Buffer.from(A.save(c.doc)).toString("base64"),
    ...sourceInfo(c),
    expected: summarize(out),
  }
}

export { BLACK }
