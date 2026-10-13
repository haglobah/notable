import { test } from "node:test"
import assert from "node:assert/strict"
import { readNotebook } from "@notable/schema"
import { COVER_RATIO, coverPageId, coverPixels, coverSignature, inkSignature } from "../src/lib/cover.ts"
import { docKeyOf } from "../src/lib/pdfProtocol.ts"
import { entry, nativeBg, notebookDoc, page, pdfBg, stroke } from "./fixtures.ts"

test("the cover is the first page, as on Android's library card", () => {
  const nb = readNotebook(notebookDoc([page("a", nativeBg("lined")), page("b", nativeBg("blank"))]))
  assert.equal(coverPageId(nb), "a")
  assert.equal(coverPageId(readNotebook(notebookDoc([]))), null)
})

test("cover pixels are portrait 3:4, sharp on dense screens but capped at 2x", () => {
  assert.deepEqual(coverPixels(150, 1, 1860), { w: 150, h: Math.round(150 * COVER_RATIO), scale: 150 / 1860 })
  assert.equal(coverPixels(150, 2, 1860)?.w, 300)
  assert.equal(coverPixels(150, 3, 1860)?.w, 300)
  assert.equal(coverPixels(0, 2, 1860), null)
})

test("a new stroke on the cover page changes the signature; one on another page doesn't", () => {
  const e = entry("Paper", { kind: "none" })
  const before = readNotebook(notebookDoc([page("a", nativeBg("lined")), page("b", nativeBg("blank"))]))
  const onCover = readNotebook(notebookDoc([page("a", nativeBg("lined"), [stroke("s", [[1, 1], [9, 9]])]), page("b", nativeBg("blank"))]))
  const elsewhere = readNotebook(notebookDoc([page("a", nativeBg("lined")), page("b", nativeBg("blank"), [stroke("s", [[1, 1], [9, 9]])])]))
  assert.notEqual(coverSignature(onCover, e), coverSignature(before, e))
  assert.equal(coverSignature(elsewhere, e), coverSignature(before, e))
})

test("a changed cover background changes the signature", () => {
  const e = entry("Paper", { kind: "pdf", blob: "aa" })
  const a = readNotebook(notebookDoc([page("a", pdfBg("aa", 0))]))
  const b = readNotebook(notebookDoc([page("a", pdfBg("aa", 1))]))
  assert.notEqual(coverSignature(a, e), coverSignature(b, e))
})

test("ink signature counts strokes and their edit times", () => {
  assert.equal(inkSignature(page("a", nativeBg("blank"))), "0:0:0")
  assert.match(inkSignature(page("a", nativeBg("blank"), [stroke("s", [[1, 1]])])), /^1:0:\d+$/)
})

test("document backgrounds key the PDF worker; others don't", () => {
  assert.deepEqual(docKeyOf({ kind: "document", format: "pdf", blob: "aa", index: 2 }), { format: "pdf", blob: "aa" })
  assert.equal(docKeyOf({ kind: "native", template: "blank" }), null)
})
