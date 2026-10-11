/*
 * Canvas drawing for the reader. The context is scaled so 1 unit = 1 Android canvas px; the
 * shapes mirror backgrounds.kt and the PDF projection's templateOps.
 */
import type { Image, Stroke } from "@notable/schema"
import {
  DEFAULT_CANVAS_HEIGHT_RATIO, DOT_SIZE, HEX_VERTICAL_COUNT, LINE_HEIGHT, strokePoints, strokeStyle,
  type NativeTemplate,
} from "./geometry.ts"

const GRAY = "#888888"

export function drawTemplate(ctx: CanvasRenderingContext2D, template: NativeTemplate, w: number, h: number, scale: number) {
  ctx.fillStyle = "#ffffff"
  ctx.fillRect(0, 0, w, h)
  if (template === "blank") return
  ctx.strokeStyle = GRAY
  ctx.fillStyle = GRAY
  // At least one device pixel, so the template stays visible when zoomed out on a phone.
  ctx.lineWidth = Math.max(1, 1 / scale)
  ctx.beginPath()
  if (template === "lined" || template === "squared") {
    for (let y = LINE_HEIGHT; y <= h; y += LINE_HEIGHT) { ctx.moveTo(0, y); ctx.lineTo(w, y) }
    if (template === "squared") for (let x = LINE_HEIGHT; x <= w; x += LINE_HEIGHT) { ctx.moveTo(x, 0); ctx.lineTo(x, h) }
    ctx.stroke()
  } else if (template === "dotted") {
    const r = Math.max(DOT_SIZE / 2, 0.75 / scale)
    for (let y = LINE_HEIGHT; y <= h; y += LINE_HEIGHT)
      for (let x = LINE_HEIGHT; x <= w; x += LINE_HEIGHT) { ctx.moveTo(x + r, y); ctx.arc(x, y, r, 0, Math.PI * 2) }
    ctx.fill()
  } else if (template === "hexed") {
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
          const px = x + r * Math.cos(a), py = y + r * Math.sin(a)
          if (k) ctx.lineTo(px, py); else ctx.moveTo(px, py)
        }
        ctx.closePath()
      }
    }
    ctx.stroke()
  }
}

export function drawImageBackground(ctx: CanvasRenderingContext2D, img: ImageBitmap, w: number, h: number, repeat: boolean, cover: boolean) {
  ctx.fillStyle = "#ffffff"
  ctx.fillRect(0, 0, w, h)
  const ih = (img.height * w) / img.width
  for (let y = 0; y === 0 || (repeat && y < h); y += ih) ctx.drawImage(img, 0, y, w, ih)
  if (cover) {
    const H = w * DEFAULT_CANVAS_HEIGHT_RATIO
    const bw = w * 0.8, bh = H * 0.25
    ctx.beginPath()
    ctx.roundRect((w - bw) / 2, (H - bh) / 2, bw, bh, 64)
    ctx.fillStyle = "#ffffff"
    ctx.fill()
    ctx.lineWidth = 4
    ctx.strokeStyle = "#444444"
    ctx.stroke()
  }
}

export function drawImages(ctx: CanvasRenderingContext2D, images: Image[], bitmaps: Map<string, ImageBitmap>) {
  for (const im of images) {
    const bmp = bitmaps.get(im.blob)
    if (bmp) ctx.drawImage(bmp, im.rect.x, im.rect.y, im.rect.width, im.rect.height)
  }
}

export function drawStrokes(ctx: CanvasRenderingContext2D, strokes: Stroke[]) {
  ctx.lineCap = "round"
  ctx.lineJoin = "round"
  for (const s of strokes) {
    const pts = strokePoints(s)
    if (!pts.length) continue
    const st = strokeStyle(s)
    ctx.strokeStyle = `rgb(${st.rgb.map(c => Math.round(c * 255)).join(" ")})`
    ctx.globalAlpha = st.alpha
    const varies = pts.some(p => p.pressure !== null) && st.width(0) !== st.width(1)
    if (pts.length === 1) {
      ctx.fillStyle = ctx.strokeStyle
      ctx.beginPath()
      ctx.arc(pts[0].x, pts[0].y, st.width(pts[0].pressure) / 2, 0, Math.PI * 2)
      ctx.fill()
    } else if (!varies) {
      ctx.lineWidth = st.width(null)
      ctx.beginPath()
      ctx.moveTo(pts[0].x, pts[0].y)
      for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i].x, pts[i].y)
      ctx.stroke()
    } else {
      // Pressure pens: each segment at the width of its mean pressure; round caps hide the seams.
      for (let i = 1; i < pts.length; i++) {
        const a = pts[i - 1], b = pts[i]
        ctx.lineWidth = st.width(((a.pressure ?? 0) + (b.pressure ?? 0)) / 2)
        ctx.beginPath()
        ctx.moveTo(a.x, a.y)
        ctx.lineTo(b.x, b.y)
        ctx.stroke()
      }
    }
  }
  ctx.globalAlpha = 1
}
