/* What a library card shows: the notebook's first page, cropped to a portrait card like Android's. */
import type { Notebook, NotebookEntry, Page } from "@notable/schema"
import { canvasWidthOf, resolveBackground } from "./geometry.ts"

/** Height / width of a card, as NotebookCard's 3:4 aspect ratio. */
export const COVER_RATIO = 4 / 3

export const coverPageId = (nb: Notebook): string | null => nb.pageOrder[0] ?? null

/** Device pixels for a card [cssWidth] wide; scale maps canvas px to them. */
export function coverPixels(cssWidth: number, dpr: number, canvasWidth: number) {
  if (cssWidth <= 0) return null
  const w = Math.round(cssWidth * Math.min(dpr || 1, 2))
  return { w, h: Math.round(w * COVER_RATIO), scale: w / canvasWidth }
}

/** Strokes are immutable, so count + edit times tell a change apart. */
export function inkSignature(pg: Page): string {
  let t = 0
  for (const s of Object.values(pg.strokes)) t += s.updatedAt.getTime()
  for (const i of Object.values(pg.images)) t += i.updatedAt.getTime()
  return `${Object.keys(pg.strokes).length}:${Object.keys(pg.images).length}:${t}`
}

/** Changes whenever the cover would look different: its page, background, ink or canvas width. */
export function coverSignature(nb: Notebook, entry: NotebookEntry): string {
  const id = coverPageId(nb)
  const pg = id ? nb.pages[id] : undefined
  if (!id || !pg) return "none"
  return `${id}|${canvasWidthOf(nb)}|${JSON.stringify(resolveBackground(nb, entry, id))}|${inkSignature(pg)}`
}
