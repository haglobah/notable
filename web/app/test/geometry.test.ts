import { test } from "node:test"
import assert from "node:assert/strict"
import { decodeStrokePoints, readNotebook } from "@notable/schema"
import {
  canvasWidthOf, contentHeight, resolveBackground, strokeStyle, toPagePoint, DEFAULT_CANVAS_HEIGHT_RATIO,
} from "../src/lib/geometry.ts"
import { encodeSb2 } from "./sb2.ts"
import { BLACK, RED, entry, nativeBg, notebookDoc, page, pdfBg, stroke } from "./fixtures.ts"

test("test SB2 encoder round-trips through the schema decoder", () => {
  const pts = decodeStrokePoints(encodeSb2([[930, 1240, 0.5], [1395.25, 1240.5, 1]])).points
  assert.deepEqual(pts.map(p => [p.x, p.y]), [[930, 1240], [1395.25, 1240.5]])
  assert.ok(Math.abs(pts[0].pressure! - 0.5) < 1e-4)
})

test("canvas px map to page points by page width / canvas width, from the page's top-left", () => {
  // A4 page, Note Air 5C canvas: the page's 595 pt span the canvas's 1860 px.
  const [x, y] = toPagePoint(930, 1240, 1860, [0, 0, 595, 842])
  assert.ok(Math.abs(x - 297.5) < 1e-9 && Math.abs(y - 1240 * 595 / 1860) < 1e-9, `got ${x},${y}`)
  // A page whose box doesn't start at the origin (cropbox offset) keeps the offset.
  assert.deepEqual(toPagePoint(0, 0, 1860, [10, 20, 605, 862]), [10, 20])
})

test("canvasWidth comes from the doc, else the Note Air 5C default", () => {
  assert.equal(canvasWidthOf(readNotebook(notebookDoc([page("p", nativeBg("blank"))]))), 1860)
  assert.equal(canvasWidthOf(readNotebook(notebookDoc([page("p", nativeBg("blank"))], { canvasWidth: 1404 }))), 1404)
})

test("backgrounds resolve like Android's BackgroundType", () => {
  const pdfEntry = entry("Paper", { kind: "pdf", blob: "aa" })
  const nb = readNotebook(notebookDoc([
    page("p0", pdfBg("aa", 3)),
    page("p1", { type: "autoPdf", native: null, blob: null }),
    page("p2", nativeBg("lined")),
    page("p3", { type: "imagerepeating", native: null, blob: "bb" }),
    page("p4", { type: "coverImage", native: null, blob: "cc" }),
  ], { defaultBackground: { type: "autoPdf", native: null, blob: "aa" } }))
  assert.deepEqual(resolveBackground(nb, pdfEntry, "p0"), { kind: "document", format: "pdf", blob: "aa", index: 3 })
  // autoPdf follows the page's position in the notebook; its blob falls back to the notebook default.
  assert.deepEqual(resolveBackground(nb, pdfEntry, "p1"), { kind: "document", format: "pdf", blob: "aa", index: 1 })
  assert.deepEqual(resolveBackground(nb, pdfEntry, "p2"), { kind: "native", template: "lined" })
  assert.deepEqual(resolveBackground(nb, pdfEntry, "p3"), { kind: "image", blob: "bb", repeat: true, cover: false })
  assert.deepEqual(resolveBackground(nb, pdfEntry, "p4"), { kind: "image", blob: "cc", repeat: false, cover: true })
})

test("an EPUB-backed page carries the entry's fixed layout", () => {
  const layout = { w: 446, h: 595, em: 12 }
  const nb = readNotebook(notebookDoc([page("p0", pdfBg("ee", 2))]))
  assert.deepEqual(resolveBackground(nb, entry("Book", { kind: "epub", blob: "ee", layout }), "p0"),
    { kind: "document", format: "epub", blob: "ee", index: 2, layout })
})

test("unknown native templates and missing blobs resolve to 'missing', not to a guess", () => {
  const nb = readNotebook(notebookDoc([page("p0", nativeBg("zigzag")), page("p1", { type: "pdf0", native: null, blob: null })]))
  const e = entry("x", { kind: "none" })
  assert.equal(resolveBackground(nb, e, "p0").kind, "missing")
  assert.equal(resolveBackground(nb, e, "p1").kind, "missing")
})

test("native page height grows with content, at least one screen", () => {
  const nb = readNotebook(notebookDoc([
    page("short", nativeBg("blank"), [stroke("s", [[10, 10], [20, 20]])]),
    page("long", nativeBg("blank"), [stroke("s", [[10, 10], [20, 3000]])]),
  ]))
  assert.equal(contentHeight(nb.pages.short, 1860), 1860 * DEFAULT_CANVAS_HEIGHT_RATIO)
  assert.equal(contentHeight(nb.pages.long, 1860), 3050)
})

test("stroke style: signed ARGB, marker translucency, pressure pens vary width", () => {
  const ball = strokeStyle(stroke("a", [[0, 0]], { color: BLACK, size: 6 }))
  assert.deepEqual(ball.rgb, [0, 0, 0])
  assert.equal(ball.alpha, 1)
  assert.equal(ball.width(0.1), 6)
  assert.equal(ball.width(null), 6)
  const marker = strokeStyle(stroke("m", [[0, 0]], { pen: "MARKER", color: RED, size: 30 }))
  assert.deepEqual(marker.rgb, [1, 0, 0])
  assert.equal(marker.alpha, 100 / 255)
  const fountain = strokeStyle(stroke("f", [[0, 0]], { pen: "FOUNTAIN", size: 10 }))
  assert.ok(fountain.width(0.1) < fountain.width(0.9))
  assert.equal(fountain.width(null), 10)
})
