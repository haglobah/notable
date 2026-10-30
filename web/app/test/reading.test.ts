import { test } from "node:test"
import assert from "node:assert/strict"
import type { Notebook, NotebookEntry, Page } from "@notable/schema"
import { mapToPages, pagesOfDocument, progressOf, progressText, sourceKey, splitSnippet } from "../src/lib/reading.ts"

const BLOB = "a".repeat(64), OTHER = "b".repeat(64)
const t = new Date("2026-10-30T00:00:00Z")
const page = (id: string, type: string, blob: string | null = BLOB): Page =>
  ({ id, background: { type, native: type === "native" ? "lined" : null, blob }, strokes: {}, images: {}, createdAt: t, updatedAt: t })
const entry: NotebookEntry = {
  id: "nb", docUrl: "automerge:x", title: "Book", folderId: null, kind: "notebook",
  source: { kind: "epub", blob: BLOB, layout: { w: 446, h: 595, em: 12 } }, createdAt: t, updatedAt: t,
}
// p0 → doc page 0; an inserted lined page; p2 and its duplicate both show doc page 2; doc page 1
// was deleted on the Boox; a page from another PDF.
const nb: Notebook = {
  notebookId: "nb", openPageId: "p2", defaultBackground: { type: "autoPdf", native: null, blob: BLOB },
  pageOrder: ["p0", "lined", "p2", "p2-copy", "other"],
  pages: Object.fromEntries([page("p0", "pdf0"), page("lined", "native", null), page("p2", "pdf2"), page("p2-copy", "pdf2"), page("other", "pdf1", OTHER)].map(p => [p.id, p])),
}

test("document pages map to the first notebook page that shows them", () => {
  const pages = pagesOfDocument(nb, entry, sourceKey(entry)!)
  assert.deepEqual([...pages], [[0, "p0"], [2, "p2"]])
})

test("items on pages the notebook lacks are counted, not dropped silently", () => {
  const pages = pagesOfDocument(nb, entry, sourceKey(entry)!)
  const r = mapToPages([{ page: 0, title: "One" }, { page: 1, title: "Two" }, { page: 2, title: "Three" }], pages)
  assert.deepEqual(r.mapped.map(x => [x.title, x.pageId]), [["One", "p0"], ["Three", "p2"]])
  assert.equal(r.missing, 1)
})

test("a plain notebook has no source document", () => {
  assert.equal(sourceKey({ ...entry, source: { kind: "none" } }), null)
  assert.deepEqual(sourceKey({ ...entry, source: { kind: "pdf", blob: OTHER } }), { format: "pdf", blob: OTHER })
})

test("progress is the open page's place in pageOrder", () => {
  assert.deepEqual(progressOf(nb), { index: 2, count: 5, fraction: 0.5 })
  assert.equal(progressOf({ ...nb, openPageId: null }), null)
  assert.equal(progressOf({ ...nb, openPageId: "gone" }), null)
  assert.deepEqual(progressOf({ ...nb, pageOrder: ["p0"], openPageId: "p0" }), { index: 0, count: 1, fraction: 1 })
})

test("progress text adds a percentage from ten pages on", () => {
  assert.equal(progressText({ index: 2, count: 5, fraction: 0.5 }), "Page 3 of 5")
  assert.equal(progressText({ index: 11, count: 300, fraction: 11 / 299 }), "Page 12 of 300, 4%")
})

test("snippets split around the first case-insensitive match", () => {
  assert.deepEqual(splitSnippet("…on Gethen hurries", "gethen"), ["…on ", "Gethen", " hurries"])
  assert.deepEqual(splitSnippet("no match", "x"), ["no match", "", ""])
})
