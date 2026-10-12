// Deleting a page while another device draws on it: the drawing brings the page back (ADR-001,
// "Delete against draw"). A delete marks the page `deleted: true` and keeps its content; every
// stroke or image write sets `deleted: false`. Readers treat a page as deleted only when every
// concurrent value of `deleted` is true.
import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync, existsSync } from "node:fs"
import * as A from "@automerge/automerge"
import {
  notebookValue, strokeValue, deletePage, markPageLive, readNotebook, notebookFromJson, SchemaError, SCHEMA_VERSION,
  type Notebook, type Stroke,
} from "../src/index.ts"

const fixture = (name: string) => new URL(`../fixtures/${name}`, import.meta.url)
const expected: Notebook = notebookFromJson(JSON.parse(readFileSync(fixture("notebook-v1.json"), "utf8")))
const P = "p-2"
const drawn: Stroke = { ...Object.values(expected.pages[P].strokes)[0], id: "drawn-after-delete" }
type D = Record<string, any>

const base = () => A.from(notebookValue(expected) as D)
const draw = (d: D) => { d.pages[P].strokes[drawn.id] = strokeValue(drawn); markPageLive(d, P) }

test("a page deleted on one doc and drawn on in another comes back with the stroke", () => {
  const origin = base()
  const deleter = A.change(A.clone(origin), d => deletePage(d, P))
  const drawer = A.change(A.clone(origin), d => draw(d))
  for (const merged of [A.merge(A.clone(deleter), drawer), A.merge(A.clone(drawer), deleter)]) {
    const nb = readNotebook(A.load(A.save(merged)))
    assert.ok(P in nb.pages, "the page is back")
    assert.deepEqual(Object.keys(nb.pages[P].strokes).sort(), [...Object.keys(expected.pages[P].strokes), drawn.id].sort())
    assert.deepEqual(nb.pageOrder, ["p-1", "p-3", P], "back at the end: the delete removed it from the order")
  }
})

test("a plain delete stays deleted and raises the doc's version", () => {
  const doc = A.change(base(), d => deletePage(d, P))
  const nb = readNotebook(A.load(A.save(doc)))
  assert.equal(P in nb.pages, false)
  assert.deepEqual(nb.pageOrder, ["p-1", "p-3"])
  assert.equal((doc as D).version, 2)
})

test("a delete made after seeing a stroke wins", () => {
  const drawnOn = A.change(base(), d => draw(d))
  const doc = A.change(drawnOn, d => deletePage(d, P))
  assert.equal(P in readNotebook(doc).pages, false)
})

test("a stroke written after seeing the delete brings the page back into the order", () => {
  const deleted = A.change(base(), d => deletePage(d, P))
  const doc = A.change(deleted, d => draw(d))
  const nb = readNotebook(doc)
  assert.ok(drawn.id in nb.pages[P].strokes)
  assert.deepEqual(nb.pageOrder, ["p-1", "p-3", P])
  assert.equal((doc as D).pageOrder.length, 3, "inserted once")
})

test("deleted must be a boolean", () => {
  const doc = A.change(base(), d => { d.pages[P].deleted = new A.ImmutableString("yes") })
  assert.throws(() => readNotebook(doc), (e: unknown) => e instanceof SchemaError && e.message.includes(`pages.${P}.deleted`))
})

test("readers accept version 2 and refuse a newer one", () => {
  assert.equal(SCHEMA_VERSION, 2)
  assert.doesNotThrow(() => readNotebook(A.change(base(), d => { d.version = 2 })))
  assert.throws(() => readNotebook(A.change(base(), d => { d.version = 3 })), /newer/)
})

test("Kotlin-written docs: delete against draw keeps the page, a plain delete removes it", () => {
  for (const name of ["page-delete-vs-draw-from-kotlin.automerge", "page-deleted-from-kotlin.automerge"]) {
    assert.ok(existsSync(fixture(name)), `missing ${name}; run the Kotlin GoldenFixtureTest with UPDATE_FIXTURES=1`)
  }
  const kept = readNotebook(A.load(readFileSync(fixture("page-delete-vs-draw-from-kotlin.automerge"))))
  assert.ok(drawn.id in kept.pages[P].strokes)
  assert.deepEqual(kept.pageOrder, ["p-1", "p-3", P])
  const gone = readNotebook(A.load(readFileSync(fixture("page-deleted-from-kotlin.automerge"))))
  assert.equal(P in gone.pages, false)
  assert.deepEqual(gone.pageOrder, ["p-1", "p-3"])
})

test("a second stroke after a synced first one still beats a concurrent delete", () => {
  const synced = A.change(base(), d => draw(d)) // deleted: false, seen by both
  const deleter = A.change(A.clone(synced), d => deletePage(d, P))
  const drawer = A.change(A.clone(synced), d => { d.pages[P].strokes.again = strokeValue({ ...drawn, id: "again" }); markPageLive(d, P) })
  const nb = readNotebook(A.merge(A.clone(deleter), drawer))
  assert.ok("again" in nb.pages[P].strokes)
})
