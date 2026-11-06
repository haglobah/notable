/*
 * Painting pages onto canvases: a page's size, its background (document page, template or image)
 * and its ink (images and strokes), and a library cover. Browser only: canvases and the reader's
 * worker and blobs (client.ts).
 */
import type { Notebook, NotebookEntry, Page } from "@notable/schema"
import { canvasWidthOf, contentHeight, resolveBackground, type PageBackground } from "./geometry.ts"
import { drawImageBackground, drawImages, drawStrokes, drawTemplate } from "./draw.ts"
import type { Reader } from "./client.ts"
import { docKeyOf, type DocKey } from "./pdfProtocol.ts"
import { coverPageId, coverPixels } from "./cover.ts"

/** Device pixels of a canvas; `scale` maps canvas px (stroke coordinates) to them. */
export type Pixels = { w: number; h: number; scale: number }

/** Larger canvases hit iOS's per-canvas limit and a phone's memory. */
export const MAX_CANVAS_PIXELS = 12_000_000

/**
 * A page's height in canvas px: a document page is as tall as its page size makes it (A4 until
 * [size] is known), other pages as their content.
 */
export function pageHeight(page: Page, bg: PageBackground, canvasWidth: number, size: [number, number] | undefined): number {
  if (bg.kind !== "document") return contentHeight(page, canvasWidth)
  return size ? (canvasWidth * size[1]) / size[0] : canvasWidth * (842 / 595)
}

/** Pixels for a page [cssWidth] wide, sharp up to a device pixel ratio of 2 and within MAX_CANVAS_PIXELS. */
export function pagePixels(cssWidth: number, height: number, canvasWidth: number, devicePixelRatio: number): Pixels | null {
  if (!cssWidth) return null
  const ratio = height / canvasWidth
  let dpr = Math.min(devicePixelRatio || 1, 2)
  while (dpr > 0.5 && cssWidth * dpr * (cssWidth * dpr * ratio) > MAX_CANVAS_PIXELS) dpr *= 0.8
  const pw = Math.round(cssWidth * dpr)
  return { w: pw, h: Math.round(pw * ratio), scale: pw / canvasWidth }
}

/**
 * Draws a page's background on a canvas already [px] large. Rejects with what to tell the reader
 * when it can't. [alive] is asked before drawing what arrives later, so a canvas that moved on
 * keeps what it shows now.
 */
export async function paintBackground(reader: Reader, ctx: CanvasRenderingContext2D, bg: PageBackground, px: Pixels, canvasWidth: number, height: number, alive: () => boolean = () => true): Promise<void> {
  ctx.setTransform(px.scale, 0, 0, px.scale, 0, 0)
  if (bg.kind === "native") return drawTemplate(ctx, bg.template, canvasWidth, height, px.scale)
  ctx.fillStyle = "#ffffff"
  ctx.fillRect(0, 0, canvasWidth, height)
  if (bg.kind === "missing") throw new Error(`Background unavailable: ${bg.reason}.`)
  if (bg.kind === "image") {
    const img = await reader.loadImage(bg.blob)
    if (alive()) drawImageBackground(ctx, img, canvasWidth, height, bg.repeat, bg.cover)
    return
  }
  let bmp: ImageBitmap
  try {
    bmp = await reader.renderPage(docKeyOf(bg) as DocKey, bg.index, px.w)
  } catch (e) {
    throw new Error(`This page can't be shown: ${(e as Error).message}`)
  }
  if (alive()) {
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.drawImage(bmp, 0, 0, px.w, Math.round((bmp.height * px.w) / bmp.width))
    ctx.setTransform(px.scale, 0, 0, px.scale, 0, 0)
  }
  bmp.close()
}

/** Draws a page's images and strokes (oldest first); an image that can't be loaded is left out. */
export async function paintInk(reader: Reader, ctx: CanvasRenderingContext2D, page: Page, px: Pixels, alive: () => boolean = () => true): Promise<void> {
  const images = Object.values(page.images)
  const loaded = images.length ? await Promise.allSettled(images.map(i => reader.loadImage(i.blob).then(b => [i.blob, b] as const))) : []
  if (!alive()) return
  ctx.setTransform(px.scale, 0, 0, px.scale, 0, 0)
  drawImages(ctx, images, new Map(loaded.flatMap(r => (r.status === "fulfilled" ? [r.value] : []))))
  drawStrokes(ctx, Object.values(page.strokes).sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime()))
}

/**
 * The notebook's first page, background and ink, [cssWidth] wide and cropped to the card. Throws
 * when the page or its background can't be drawn; a missing ink image is left out, as in the reader.
 */
export async function paintCover(reader: Reader, nb: Notebook, entry: NotebookEntry, cssWidth: number, dpr: number): Promise<HTMLCanvasElement> {
  const id = coverPageId(nb)
  const pg = id ? nb.pages[id] : undefined
  if (!id || !pg) throw new Error("the notebook has no pages")
  const cw = canvasWidthOf(nb)
  const px = coverPixels(cssWidth, dpr, cw)
  if (!px) throw new Error("the card has no width")
  const canvas = document.createElement("canvas")
  canvas.width = px.w
  canvas.height = px.h
  const ctx = canvas.getContext("2d")!
  await paintBackground(reader, ctx, resolveBackground(nb, entry, id), px, cw, px.h / px.scale)
  await paintInk(reader, ctx, pg, px)
  return canvas
}
