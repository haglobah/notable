import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { libraryFromJson, type Folder, type Library, type NotebookEntry } from "@notable/schema"
import { bylineOf, quickFirst } from "../src/lib/books.ts"
import { childFolders, countIn, edited, entriesIn, folderPath, kindOf } from "../src/lib/library.ts"

const d = (iso: string) => new Date(iso)
const folder = (id: string, title: string, parentId: string | null): Folder => ({ id, title, parentId, createdAt: d("2026-01-01"), updatedAt: d("2026-01-01") })
const entry = (id: string, folderId: string | null, updated: string, source: NotebookEntry["source"] = { kind: "none" }, kind: NotebookEntry["kind"] = "notebook"): NotebookEntry =>
  ({ id, docUrl: `automerge:${id}`, title: id, folderId, kind, source, createdAt: d("2026-01-01"), updatedAt: d(updated) })

const lib: Library = {
  folders: { a: folder("a", "Papers", null), b: folder("b", "2026", "a"), c: folder("c", "archive", null), loop1: folder("loop1", "x", "loop2"), loop2: folder("loop2", "y", "loop1") },
  notebooks: {
    old: entry("old", null, "2026-01-02"),
    new: entry("new", null, "2026-03-01", { kind: "pdf", blob: "h" }),
    inB: entry("inB", "b", "2026-02-01", { kind: "epub", blob: "h", layout: { w: 1, h: 1, em: 1 } }),
    quick: entry("quick", "a", "2026-02-01", { kind: "none" }, "quickPage"),
  },
}

test("folders sort by title, case- and number-aware", () => {
  assert.deepEqual(childFolders(lib, null).map(f => f.title), ["archive", "Papers"])
})

test("entries show the most recently changed first", () => {
  assert.deepEqual(entriesIn(lib, null).map(e => e.id), ["new", "old"])
})

test("the breadcrumb runs from the root and survives a parent cycle", () => {
  assert.deepEqual(folderPath(lib, "b").map(f => f.title), ["Papers", "2026"])
  assert.deepEqual(folderPath(lib, null), [])
  assert.ok(folderPath(lib, "loop1").length <= 2, "a cyclic parent chain must terminate")
  assert.deepEqual(folderPath(lib, "missing"), [])
})

test("counts include subfolders and documents", () => {
  assert.equal(countIn(lib, "a"), 2)
  assert.equal(countIn(lib, "c"), 0)
})

test("kinds are named the way people say them", () => {
  assert.deepEqual(["new", "inB", "old", "quick"].map(id => kindOf(lib.notebooks[id])), ["PDF", "EPUB", "Notebook", "Quick page"])
})

test("edit dates read as today, yesterday, a date, or a date with year", () => {
  const now = d("2026-10-11T15:00:00")
  assert.match(edited(d("2026-10-11T09:30:00"), now), /^today at /)
  assert.equal(edited(d("2026-10-10T22:00:00"), now), "yesterday")
  assert.doesNotMatch(edited(d("2026-03-04T12:00:00"), now), /2026/)
  assert.match(edited(d("2025-03-04T12:00:00"), now), /2025/)
})

const books = libraryFromJson(JSON.parse(readFileSync(new URL("../../schema/fixtures/library-books-v1.json", import.meta.url), "utf8")))

test("bylines name the authors and the series with its number", () => {
  assert.equal(bylineOf(books.notebooks["b-lhod"]), "Ursula K. Le Guin · Hainish Cycle 4")
  assert.equal(bylineOf(books.notebooks["p-crdt"]), "Martin Kleppmann and Annette Bieniusa")
  assert.equal(bylineOf({ ...books.notebooks["p-crdt"], meta: { ...books.notebooks["p-crdt"].meta!, authors: ["A", "B", "C"] } }), "A and others")
  assert.equal(bylineOf(books.notebooks["p-anal"]), null)
})

test("the quick-pages notebook comes first, the rest keep their order", () => {
  const es = [books.notebooks["b-dune"], books.notebooks["b-fr"], books.notebooks["n-journal"], books.notebooks["q-1"]]
  assert.deepEqual(quickFirst(books, es).map(e => e.id), ["n-journal", "b-dune", "b-fr", "q-1"])
  assert.deepEqual(quickFirst({ ...books, quickNotebookId: undefined }, es).map(e => e.id), ["b-dune", "b-fr", "n-journal", "q-1"])
  assert.deepEqual(quickFirst(books, es.slice(0, 2)).map(e => e.id), ["b-dune", "b-fr"])
})
