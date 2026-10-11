/*
 * Download-as-PDF (ADR-001 "PDF as a projection"): notebook doc + source blobs → one ordinary PDF.
 *
 * - PDF-backed pages are grafted from the original file, so its text, links and fonts survive.
 * - EPUB-backed pages are laid out with the entry's fixed layout and written as PDF first.
 * - Native and image pages become A4-wide pages as tall as their content, with the template drawn
 *   as vector content, as Android's PDF export sizes them.
 * - Strokes become /Ink annotations (with appearance streams, so every reader shows them) and page
 *   images become image stamps, all placed with the Android canvas → page mapping in geometry.ts.
 * - The notebook doc (Automerge save()) and a small JSON travel along as embedded files, so a
 *   re-upload can find its notebook again.
 *
 * No DOM: runs in Node (tests) and in a Web Worker (the app).
 */
import * as A from "@automerge/automerge"
import { readNotebook, type NotebookEntry, type Page } from "@notable/schema"
import {
  canvasWidthOf, contentHeight, resolveBackground, strokePoints, strokeStyle, toPagePoint,
  DEFAULT_CANVAS_HEIGHT_RATIO, DOT_SIZE, HEX_VERTICAL_COUNT, LINE_HEIGHT, NATIVE_PAGE_WIDTH_PT,
  type PageBackground, type Rect,
} from "./geometry.ts"

type Mupdf = typeof import("mupdf")
type PDFDocument = InstanceType<Mupdf["PDFDocument"]>

export const EMBEDDED_DOC = "notable.automerge"
export const EMBEDDED_META = "notable.json"

export class ProjectionError extends Error {}

/** Resolves a blob's bytes by sha256. Rejects when it can't. */
export type BlobSource = (hash: string) => Promise<Uint8Array>
export type Progress = (done: number, total: number) => void

let mupdfModule: Promise<Mupdf> | undefined
const loadMupdf = () => (mupdfModule ??= import("mupdf"))

export async function project(doc: A.Doc<unknown>, entry: NotebookEntry, blobs: BlobSource, onProgress?: Progress): Promise<Uint8Array> {
  const mupdf = await loadMupdf()
  const nb = readNotebook(doc)
  const cw = canvasWidthOf(nb)
  const out = new mupdf.PDFDocument()
  const sources = new Map<string, PDFDocument>()
  const images = new Map<string, InstanceType<Mupdf["Image"]>>()
  const warnings: string[] = []

  const fetchBlob = async (hash: string, what: string) => {
    try {
      return await blobs(hash)
    } catch (e) {
      throw new ProjectionError(`${what} ${hash} could not be loaded: ${(e as Error).message}`)
    }
  }

  const sourceOf = async (bg: Extract<PageBackground, { kind: "document" }>): Promise<PDFDocument> => {
    const key = `${bg.format}:${bg.blob}`
    let src = sources.get(key)
    if (src) return src
    const bytes = await fetchBlob(bg.blob, bg.format === "pdf" ? "PDF" : "EPUB")
    try {
      if (bg.format === "pdf") {
        src = new mupdf.PDFDocument(bytes)
      } else {
        const book = mupdf.Document.openDocument(bytes, "application/epub+zip")
        book.layout(bg.layout.w, bg.layout.h, bg.layout.em)
        const buf = new mupdf.Buffer()
        const writer = new mupdf.DocumentWriter(buf, "pdf", "")
        for (let i = 0; i < book.countPages(); i++) {
          const p = book.loadPage(i)
          const dev = writer.beginPage(p.getBounds())
          p.run(dev, mupdf.Matrix.identity)
          writer.endPage()
        }
        writer.close()
        book.destroy()
        src = new mupdf.PDFDocument(buf)
      }
    } catch (e) {
      if (e instanceof ProjectionError) throw e
      throw new ProjectionError(`${bg.format.toUpperCase()} ${bg.blob} can't be opened: ${(e as Error).message}`)
    }
    sources.set(key, src)
    return src
  }

  const imageOf = async (hash: string) => {
    let img = images.get(hash)
    if (!img) {
      img = new mupdf.Image(await fetchBlob(hash, "Image"))
      images.set(hash, img)
    }
    return img
  }

  /** A page with our own content stream: native template or image background, in canvas px. */
  const addDrawnPage = async (page: Page, bg: PageBackground): Promise<Rect> => {
    const h = contentHeight(page, cw)
    const s = NATIVE_PAGE_WIDTH_PT / cw
    const W = NATIVE_PAGE_WIDTH_PT, H = h * s
    const xobjects: Record<string, unknown> = {}
    // Canvas px, y down → PDF points, y up.
    let ops = `q ${s} 0 0 ${-s} 0 ${H} cm\n`
    if (bg.kind === "native") ops += templateOps(bg.template, cw, h)
    if (bg.kind === "image") {
      const img = await imageOf(bg.blob)
      xobjects.Bg = out.addImage(img)
      const ih = (img.getHeight() * cw) / img.getWidth()
      for (let y = 0; y === 0 || (bg.repeat && y < h); y += ih) {
        ops += `q ${cw} 0 0 ${-ih} 0 ${y + ih} cm /Bg Do Q\n`
      }
      if (bg.cover) ops += coverBoxOps(cw)
    }
    ops += "Q\n"
    const resources = out.addObject({ XObject: xobjects })
    out.insertPage(-1, out.addPage([0, 0, W, H], 0, resources, ops))
    return [0, 0, W, H]
  }

  const total = nb.pageOrder.length
  try {
    for (const [i, pageId] of nb.pageOrder.entries()) {
      const page = nb.pages[pageId]
      const bg = resolveBackground(nb, entry, pageId)
      let bounds: Rect
      if (bg.kind === "document") {
        const src = await sourceOf(bg)
        if (bg.index >= 0 && bg.index < src.countPages()) {
          out.graftPage(-1, src, bg.index)
          bounds = out.loadPage(i).getBounds() as Rect
        } else {
          // Android draws a white page here ("out of pages in pdf"); keep the ink, say so.
          warnings.push(`page ${i + 1}: document page ${bg.index + 1} does not exist; drawn blank`)
          bounds = await addDrawnPage(page, { kind: "native", template: "blank" })
        }
      } else {
        if (bg.kind === "missing") warnings.push(`page ${i + 1}: ${bg.reason}; drawn blank`)
        bounds = await addDrawnPage(page, bg)
      }

      const pdfPage = out.loadPage(i)
      const scale = (bounds[2] - bounds[0]) / cw
      for (const im of Object.values(page.images).sort(byCreated)) {
        const a = pdfPage.createAnnotation("Stamp")
        const [x0, y0] = toPagePoint(im.rect.x, im.rect.y, cw, bounds)
        a.setRect([x0, y0, x0 + im.rect.width * scale, y0 + im.rect.height * scale])
        a.setStampImage(await imageOf(im.blob))
        a.update()
      }
      for (const st of Object.values(page.strokes).sort(byCreated)) {
        const pts = strokePoints(st)
        if (!pts.length) continue
        const style = strokeStyle(st)
        const mapped = pts.map(p => toPagePoint(p.x, p.y, cw, bounds))
        // A single tap is a dot: give the ink a tiny length so viewers draw its round cap.
        if (mapped.length === 1) mapped.push([mapped[0][0] + 0.01, mapped[0][1]])
        const a = pdfPage.createAnnotation("Ink")
        a.setInkList([mapped as [number, number][]])
        a.setColor(style.rgb)
        if (style.alpha < 1) a.setOpacity(style.alpha)
        a.setBorderWidth(style.meanWidth(pts.map(p => p.pressure)) * scale)
        a.update()
      }
      onProgress?.(i + 1, total)
    }

    const now = new Date()
    const embed = (name: string, mime: string, bytes: Uint8Array) =>
      out.insertEmbeddedFile(name, out.addEmbeddedFile(name, mime, bytes, now, now, true))
    embed(EMBEDDED_DOC, "application/octet-stream", A.save(doc))
    embed(EMBEDDED_META, "application/json", new TextEncoder().encode(JSON.stringify({
      format: "notable-projection", version: 1, notebookId: nb.notebookId, warnings,
      entry: {
        id: entry.id, docUrl: entry.docUrl, title: entry.title, kind: entry.kind, source: entry.source,
        createdAt: entry.createdAt.toISOString(), updatedAt: entry.updatedAt.toISOString(),
      },
    })))
    out.setMetaData("info:Title", entry.title || "Untitled")
    out.setMetaData("info:Producer", "Notable")
    return new Uint8Array(out.saveToBuffer("compress,garbage").asUint8Array())
  } finally {
    for (const s of sources.values()) s.destroy()
    for (const i of images.values()) i.destroy()
    out.destroy()
  }
}

const byCreated = (a: { createdAt: Date; id: string }, b: { createdAt: Date; id: string }) =>
  a.createdAt.getTime() - b.createdAt.getTime() || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)

/** Color.GRAY, 1 px lines (backgrounds.kt defaultPaint). */
const GRAY = "0.533 G 0.533 g 1 w\n"

/** Notable's native templates in canvas px (backgrounds.kt, scroll 0, scale 1). */
function templateOps(template: string, w: number, h: number): string {
  let o = ""
  switch (template) {
    case "lined":
      o += GRAY
      for (let y = LINE_HEIGHT; y <= h; y += LINE_HEIGHT) o += `0 ${y} m ${w} ${y} l\n`
      return o + "S\n"
    case "squared":
      o += GRAY
      for (let y = LINE_HEIGHT; y <= h; y += LINE_HEIGHT) o += `0 ${y} m ${w} ${y} l\n`
      for (let x = LINE_HEIGHT; x <= w; x += LINE_HEIGHT) o += `${x} 0 m ${x} ${h} l\n`
      return o + "S\n"
    case "dotted":
      // Zero-length round-capped segments are dots of the line width.
      o += `0.533 G 1 J ${DOT_SIZE} w\n`
      for (let y = LINE_HEIGHT; y <= h; y += LINE_HEIGHT)
        for (let x = LINE_HEIGHT; x <= w; x += LINE_HEIGHT) o += `${x} ${y} m ${x} ${y} l\n`
      return o + "S\n"
    case "hexed": {
      o += GRAY
      const r = Math.max(w, h) / (HEX_VERTICAL_COUNT * 1.5)
      const hexH = r * 2, hexW = r * Math.sqrt(3)
      const rows = Math.floor(h / HEX_VERTICAL_COUNT), cols = Math.floor(w / hexW) + 1
      for (let row = 0; row <= rows; row++) {
        const y = row * hexH * 0.75
        if (y - r > h) break
        for (let col = 0; col <= cols; col++) {
          const x = col * hexW + (row % 2 ? hexW / 2 : 0) - hexW
          for (let k = 0; k < 6; k++) {
            const a = ((30 + 60 * k) * Math.PI) / 180
            o += `${(x + r * Math.cos(a)).toFixed(2)} ${(y + r * Math.sin(a)).toFixed(2)} ${k ? "l" : "m"}\n`
          }
          o += "h\n"
        }
      }
      return o + "S\n"
    }
    default:
      return ""
  }
}

/** The cover image's label box (backgrounds.kt drawTitleBox). */
function coverBoxOps(w: number): string {
  const H = w * DEFAULT_CANVAS_HEIGHT_RATIO
  const bw = w * 0.8, bh = H * 0.25, x = (w - bw) / 2, y = (H - bh) / 2, r = 64, k = r * 0.5523
  const path =
    `${x + r} ${y} m ${x + bw - r} ${y} l ${x + bw - r + k} ${y} ${x + bw} ${y + r - k} ${x + bw} ${y + r} c ` +
    `${x + bw} ${y + bh - r} l ${x + bw} ${y + bh - r + k} ${x + bw - r + k} ${y + bh} ${x + bw - r} ${y + bh} c ` +
    `${x + r} ${y + bh} l ${x + r - k} ${y + bh} ${x} ${y + bh - r + k} ${x} ${y + bh - r} c ` +
    `${x} ${y + r} l ${x} ${y + r - k} ${x + r - k} ${y} ${x + r} ${y} c h`
  return `1 g 0.267 G 4 w ${path} B\n`
}

/** Title → a file name every OS accepts. */
export function pdfFileName(title: string): string {
  let base = title.replace(/\.pdf$/i, "").replace(/[\u0000-\u001f\u007f/\\?%*:|"<>]+/g, " ").replace(/\s+/g, " ").trim()
  if (!base) base = "Untitled"
  return `${base.slice(0, 120).trim()}.pdf`
}
