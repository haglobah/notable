/* Loading page images and painting a library cover. Browser only: canvases and fetch. */
import type { Notebook, NotebookEntry } from "@notable/schema"
import { canvasWidthOf, resolveBackground } from "./geometry.ts"
import { drawImageBackground, drawImages, drawStrokes, drawTemplate } from "./draw.ts"
import { renderPage } from "./pdfClient.ts"
import { docKeyOf } from "./pdfProtocol.ts"
import { coverPageId, coverPixels } from "./cover.ts"

const imageCache = new Map<string, Promise<ImageBitmap>>()
export function loadImage(hash: string): Promise<ImageBitmap> {
  let p = imageCache.get(hash)
  if (!p) {
    p = fetch(`/api/blobs/${hash}`, { credentials: "same-origin" }).then(async r => {
      if (!r.ok) throw new Error(`image ${hash.slice(0, 12)}…: HTTP ${r.status}`)
      return createImageBitmap(await r.blob())
    })
    p.catch(() => imageCache.delete(hash))
    imageCache.set(hash, p)
    if (imageCache.size > 16) imageCache.delete(imageCache.keys().next().value!)
  }
  return p
}

/**
 * The notebook's first page, background and ink, [cssWidth] wide and cropped to the card. Throws
 * when the page or its background can't be drawn; a missing ink image is left out, as in the reader.
 */
export async function paintCover(nb: Notebook, entry: NotebookEntry, cssWidth: number, dpr: number): Promise<HTMLCanvasElement> {
  const id = coverPageId(nb)
  const pg = id ? nb.pages[id] : undefined
  if (!id || !pg) throw new Error("the notebook has no pages")
  const cw = canvasWidthOf(nb)
  const px = coverPixels(cssWidth, dpr, cw)
  if (!px) throw new Error("the card has no width")
  const h = px.h / px.scale
  const canvas = document.createElement("canvas")
  canvas.width = px.w
  canvas.height = px.h
  const ctx = canvas.getContext("2d")!
  ctx.setTransform(px.scale, 0, 0, px.scale, 0, 0)

  const bg = resolveBackground(nb, entry, id)
  if (bg.kind === "native") drawTemplate(ctx, bg.template, cw, h, px.scale)
  else {
    ctx.fillStyle = "#ffffff"
    ctx.fillRect(0, 0, cw, h)
    if (bg.kind === "missing") throw new Error(`background unavailable: ${bg.reason}`)
    if (bg.kind === "image") drawImageBackground(ctx, await loadImage(bg.blob), cw, h, bg.repeat, bg.cover)
    else {
      const bmp = await renderPage(docKeyOf(bg)!, bg.index, px.w)
      ctx.setTransform(1, 0, 0, 1, 0, 0)
      ctx.drawImage(bmp, 0, 0, px.w, Math.round((bmp.height * px.w) / bmp.width))
      bmp.close()
      ctx.setTransform(px.scale, 0, 0, px.scale, 0, 0)
    }
  }

  const images = Object.values(pg.images)
  const loaded = await Promise.allSettled(images.map(i => loadImage(i.blob).then(b => [i.blob, b] as const)))
  drawImages(ctx, images, new Map(loaded.flatMap(r => (r.status === "fulfilled" ? [r.value] : []))))
  drawStrokes(ctx, Object.values(pg.strokes).sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime()))
  return canvas
}
