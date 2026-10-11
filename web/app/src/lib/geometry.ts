/*
 * Where things sit on a page, mirroring the Android app so the reader and the downloaded PDF put
 * every stroke where it was drawn. Pure: no DOM, no MuPDF; shared by the reader, the PDF worker
 * and the tests.
 *
 * Stroke coordinates are Android canvas pixels. The canvas is as wide as the device's shorter
 * screen side (`canvasWidth`), and a PDF or EPUB page is scaled to exactly that width
 * (backgrounds.kt drawBitmapToCanvas), so 1 px = pageWidthPt / canvasWidth pt.
 */
import { DEFAULT_CANVAS_WIDTH, decodeStrokePoints, type EpubLayout, type Notebook, type NotebookEntry, type Page, type Stroke } from "@notable/schema"

/** Note Air 5C portrait: 2480 / 1860. Native pages are at least one screen tall. */
export const DEFAULT_CANVAS_HEIGHT_RATIO = 2480 / 1860
/** Native templates (backgrounds.kt). */
export const LINE_HEIGHT = 80
export const DOT_SIZE = 6
export const HEX_VERTICAL_COUNT = 26
/** Android's PDF export page width (AppSettings.kt A4_WIDTH). Native pages are exported this wide. */
export const NATIVE_PAGE_WIDTH_PT = 595
/** Room for the last stroke, as in PageContentRenderer.computeContentDimensions. */
const CONTENT_MARGIN = 50

export const NATIVE_TEMPLATES = ["blank", "dotted", "lined", "squared", "hexed"] as const
export type NativeTemplate = (typeof NATIVE_TEMPLATES)[number]

export type PageBackground =
  | { kind: "document"; format: "pdf"; blob: string; index: number }
  | { kind: "document"; format: "epub"; blob: string; index: number; layout: EpubLayout }
  | { kind: "native"; template: NativeTemplate }
  | { kind: "image"; blob: string; repeat: boolean; cover: boolean }
  /** Shown as a blank page with a note; never silently replaced by something else. */
  | { kind: "missing"; reason: string }

export type Rect = [x0: number, y0: number, x1: number, y1: number]

export const canvasWidthOf = (nb: Notebook) => nb.canvasWidth ?? DEFAULT_CANVAS_WIDTH

export function toPagePoint(x: number, y: number, canvasWidth: number, bounds: Rect): [number, number] {
  const s = (bounds[2] - bounds[0]) / canvasWidth
  return [bounds[0] + x * s, bounds[1] + y * s]
}

export function resolveBackground(nb: Notebook, entry: NotebookEntry, pageId: string): PageBackground {
  const page = nb.pages[pageId]
  if (!page) return { kind: "missing", reason: `no page ${pageId}` }
  const bg = page.background
  const docBlob = bg.blob ?? nb.defaultBackground.blob ?? (entry.source.kind === "none" ? null : entry.source.blob)
  const document = (index: number): PageBackground => {
    if (!docBlob) return { kind: "missing", reason: `${bg.type} background without a document` }
    if (entry.source.kind === "epub" && entry.source.blob === docBlob) {
      return { kind: "document", format: "epub", blob: docBlob, index, layout: entry.source.layout }
    }
    return { kind: "document", format: "pdf", blob: docBlob, index }
  }
  const pdfN = /^pdf(\d+)$/.exec(bg.type)
  if (pdfN) return document(Number(pdfN[1]))
  switch (bg.type) {
    case "autoPdf":
      return document(nb.pageOrder.indexOf(pageId))
    case "native": {
      const t = bg.native ?? "blank"
      return (NATIVE_TEMPLATES as readonly string[]).includes(t)
        ? { kind: "native", template: t as NativeTemplate }
        : { kind: "missing", reason: `unknown native background '${t}'` }
    }
    case "image":
    case "imagerepeating":
    case "coverImage":
      return bg.blob
        ? { kind: "image", blob: bg.blob, repeat: bg.type === "imagerepeating", cover: bg.type === "coverImage" }
        : { kind: "missing", reason: `${bg.type} background without an image` }
    default:
      return { kind: "missing", reason: `unknown background type '${bg.type}'` }
  }
}

/** Height in canvas px of a page that has no fixed size (native and image backgrounds). */
export function contentHeight(page: Page, canvasWidth: number): number {
  const bottoms = [
    ...Object.values(page.strokes).map(s => s.bbox.bottom),
    ...Object.values(page.images).map(i => i.rect.y + i.rect.height),
  ]
  const screen = canvasWidth * DEFAULT_CANVAS_HEIGHT_RATIO
  return bottoms.length ? Math.max(Math.max(...bottoms) + CONTENT_MARGIN, screen) : screen
}

export type StrokeStyle = {
  rgb: [number, number, number]
  alpha: number
  /** Width in canvas px at a point's normalized pressure. */
  width: (pressure: number | null) => number
  /** Average width over the stroke, for formats with one width per stroke (PDF /Ink). */
  meanWidth: (pressures: (number | null)[]) => number
}

/** Pens whose line follows pen pressure on the Boox (Onyx Fountain, Brush, calligraphy, charcoal). */
const PRESSURE_PENS = new Set(["FOUNTAIN", "BRUSH", "CALLIGRAPHY", "PENCIL", "CHARCOAL"])
/** penStrokes.kt drawMarkerStroke: alpha 100. */
const MARKER_ALPHA = 100 / 255

export function argb(color: number) {
  const c = color >>> 0
  return { a: (c >>> 24) / 255, r: ((c >>> 16) & 0xff) / 255, g: ((c >>> 8) & 0xff) / 255, b: (c & 0xff) / 255 }
}

export function strokeStyle(s: Stroke): StrokeStyle {
  const { a, r, g, b } = argb(s.color)
  const pressured = PRESSURE_PENS.has(s.pen)
  const width = (p: number | null) => (pressured && p !== null ? s.size * (0.35 + 0.9 * Math.min(1, Math.max(0, p))) : s.size)
  return {
    rgb: [r, g, b],
    alpha: s.pen === "MARKER" ? MARKER_ALPHA * a : a,
    width,
    meanWidth: ps => (ps.length ? ps.reduce<number>((sum, p) => sum + width(p), 0) / ps.length : s.size),
  }
}

/** Decoded points, cached per stroke entry: strokes are immutable, so id + updatedAt identify them. */
const decoded = new Map<string, ReturnType<typeof decodeStrokePoints>["points"]>()
export function strokePoints(s: Stroke) {
  const key = `${s.id}@${s.updatedAt.getTime()}#${s.points.length}`
  let pts = decoded.get(key)
  if (!pts) {
    pts = decodeStrokePoints(s.points).points
    if (decoded.size > 20000) decoded.clear()
    decoded.set(key, pts)
  }
  return pts
}
