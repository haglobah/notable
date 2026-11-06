/* Notebook docs for reader and projection tests, built through web/schema like the real clients. */
import * as A from "@automerge/automerge"
import { notebookValue, type Background, type Notebook, type NotebookEntry, type Page, type Source, type Stroke } from "@notable/schema"
import { encodeSb2 } from "./sb2.ts"

const T = new Date("2026-10-11T10:00:00Z")

export const BLACK = -16777216 // 0xFF000000 as Android's signed ARGB
export const RED = -65536 // 0xFFFF0000

export function stroke(id: string, points: number[][], o: { pen?: string; color?: number; size?: number } = {}): Stroke {
  const xs = points.map(p => p[0]), ys = points.map(p => p[1])
  return {
    id, pen: o.pen ?? "BALLPEN", color: o.color ?? BLACK, size: o.size ?? 6,
    bbox: { left: Math.min(...xs), top: Math.min(...ys), right: Math.max(...xs), bottom: Math.max(...ys) },
    points: encodeSb2(points), createdAt: T, updatedAt: T,
  }
}

export function page(id: string, background: Background, strokes: Stroke[] = []): Page {
  return { id, background, strokes: Object.fromEntries(strokes.map(s => [s.id, s])), images: {}, createdAt: T, updatedAt: T }
}

export function notebookDoc(pages: Page[], o: { canvasWidth?: number; defaultBackground?: Background } = {}): A.Doc<unknown> {
  const nb: Notebook = {
    notebookId: "nb-1",
    ...(o.canvasWidth === undefined ? {} : { canvasWidth: o.canvasWidth }),
    openPageId: pages[0]?.id ?? null,
    defaultBackground: o.defaultBackground ?? { type: "native", native: "blank", blob: null },
    pageOrder: pages.map(p => p.id),
    pages: Object.fromEntries(pages.map(p => [p.id, p])),
  }
  return A.from(notebookValue(nb) as Record<string, unknown>)
}

export function entry(title: string, source: Source): NotebookEntry {
  return { id: "nb-1", docUrl: "automerge:2j9knpCseyhnK8izDmLpGP5WMdZQ", title, folderId: null, kind: "notebook", source, createdAt: T, updatedAt: T }
}

export const pdfBg = (blob: string, n: number): Background => ({ type: `pdf${n}`, native: null, blob })
export const nativeBg = (template: string): Background => ({ type: "native", native: template, blob: null })
