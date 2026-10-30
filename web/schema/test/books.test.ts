import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync, existsSync } from "node:fs"
import * as A from "@automerge/automerge"
import {
  queryLibrary, naturalCompare, facetsOf, type SortKey,
  libraryValue, libraryFromJson, readLibrary, markRead, setFinished, searchLibrary, parseQuery, SchemaError,
  notebookFromJson, notebookValue, readNotebook, setOpenPage, setRating, setReading,
  type Library,
} from "../src/index.ts"

const fixture = (name: string) => new URL(`../fixtures/${name}`, import.meta.url)
const json = (name: string) => JSON.parse(readFileSync(fixture(name), "utf8"))
const books: Library = libraryFromJson(json("library-books-v1.json"))
const search: {
  library: unknown
  cases: { query: string; ids: string[] }[]
  browse: { query: string; sort?: SortKey; descending?: boolean; folderId?: string | null; subfolders?: boolean; ids: string[] }[]
  natural: [string, string, number][]
} = json("search-v1.json")

type D = Record<string, any>
const doc = () => A.from(libraryValue(books) as D)

test("book fields survive save/load", () => {
  assert.deepEqual(readLibrary(A.load(A.save(doc()))), books)
})

test("golden books library written by TS reads back exactly", () => {
  assert.deepEqual(readLibrary(A.load(readFileSync(fixture("library-books-v1.automerge")))), books)
})

test("books library written by Kotlin reads back exactly", t => {
  if (!existsSync(fixture("library-books-from-kotlin.automerge"))) {
    return t.skip("missing library-books-from-kotlin.automerge; run the Kotlin GoldenFixtureTest with UPDATE_FIXTURES=1")
  }
  assert.deepEqual(readLibrary(A.load(readFileSync(fixture("library-books-from-kotlin.automerge")))), books)
})

test("entries without book fields read without them", () => {
  const e = readLibrary(doc()).notebooks["p-anal"]
  assert.ok(!("meta" in e) && !("readAt" in e) && !("finishedAt" in e))
})

test("finishedAt null reads as null, not absent", () => {
  assert.equal(readLibrary(doc()).notebooks["b-earth"].finishedAt, null)
})

test("meta missing a key is refused, naming the path", () => {
  const d = A.change(doc(), x => { delete x.notebooks["b-dune"].meta.isbn })
  assert.throws(() => readLibrary(d), (e: Error) => e instanceof SchemaError && e.message === "notebooks.b-dune.meta.isbn: missing")
})

test("an author stored as Text is refused", () => {
  const d = A.change(doc(), x => { x.notebooks["b-dune"].meta.authors.insertAt(0, "plain text") })
  assert.throws(() => readLibrary(d), /notebooks\.b-dune\.meta\.authors\[0\]: expected a scalar string, got collaborative Text/)
})

test("rating and reading fields are strict, and setters write only what changed", () => {
  const bad = (f: (x: D) => void, msg: RegExp) => assert.throws(() => readLibrary(A.change(doc(), f)), msg)
  bad(x => { x.notebooks["b-dune"].rating = 11 }, /notebooks\.b-dune\.rating: expected 1 to 10, got 11/)
  bad(x => { x.notebooks["b-dune"].rating = 2.5 }, /notebooks\.b-dune\.rating: expected an integer/)
  bad(x => { x.notebooks["b-dune"].pageCount = -1 }, /notebooks\.b-dune\.pageCount: expected 0 or more/)
  assert.throws(() => A.change(doc(), x => setRating(x, "b-dune", 0)), /a rating is 1 to 10/)
  const rated = A.change(doc(), x => { setRating(x, "b-dune", 10); setReading(x, "b-dune", { pageCount: 612, readPage: 5 }) })
  assert.equal(readLibrary(rated).notebooks["b-dune"].rating, 10)
  assert.equal(readLibrary(rated).notebooks["b-dune"].readPage, 5)
  // Same values again: no change recorded.
  const again = A.change(rated, x => setReading(x, "b-dune", { pageCount: 612, readPage: 5 }))
  assert.deepEqual(A.getHeads(again), A.getHeads(rated))
  assert.equal(readLibrary(A.change(rated, x => setRating(x, "b-dune", null))).notebooks["b-dune"].rating, null)
})

test("markRead and setFinished set one field and merge with a concurrent rename", () => {
  const base = doc()
  const reader = A.change(A.clone(base), x => { markRead(x, "b-dune", new Date("2026-10-30T09:00:00Z")); setFinished(x, "b-dune", new Date("2026-10-30T10:00:00Z")) })
  const renamer = A.change(A.clone(base), x => { x.notebooks["b-dune"].title = new A.ImmutableString("Dune (1965)") })
  const merged = A.merge(reader, renamer)
  const e = readLibrary(merged).notebooks["b-dune"]
  assert.equal(e.title, "Dune (1965)")
  assert.deepEqual(e.readAt, new Date("2026-10-30T09:00:00Z"))
  assert.deepEqual(e.finishedAt, new Date("2026-10-30T10:00:00Z"))
  const unread = readLibrary(A.change(merged, x => setFinished(x, "b-dune", null))).notebooks["b-dune"]
  assert.equal(unread.finishedAt, null)
})

test("setOpenPage writes a scalar id the reader accepts, and refuses unknown pages", () => {
  const nb = notebookFromJson(json("notebook-v1.json"))
  const d = A.change(A.from(notebookValue(nb) as D), x => setOpenPage(x, "p-3"))
  assert.equal(readNotebook(d).openPageId, "p-3")
  assert.throws(() => A.change(d, x => setOpenPage(x, "nope")), /no page nope/)
})

test("the search fixture's library is the books library", () => {
  assert.deepEqual(libraryFromJson(search.library), books)
})

for (const c of search.cases) {
  test(`search ${JSON.stringify(c.query)}`, () => {
    assert.deepEqual(searchLibrary(books, c.query).map(e => e.id), c.ids)
  })
}

for (const c of search.browse) {
  test(`browse ${JSON.stringify(c.query)} sort ${c.sort ?? "relevance"}${c.descending === undefined ? "" : c.descending ? " desc" : " asc"}${"folderId" in c ? ` in ${c.folderId}${c.subfolders ? " and below" : ""}` : ""}`, () => {
    const { ids, ...q } = c
    assert.deepEqual(queryLibrary(books, q).map(e => e.id), ids)
  })
}

test("natural order", () => {
  for (const [a, b, sign] of search.natural) assert.equal(Math.sign(naturalCompare(a, b)), sign, `${a} vs ${b}`)
})

test("query tokens: quotes, negation, fields, unknown fields, any-of values", () => {
  assert.deepEqual(parseQuery(`-"Le Guin" author:"K. Le" foo:bar - x kind:pdf,,epub, tag:"a,b",c`), [
    { negated: true, field: null, values: ["le guin"] },
    { negated: false, field: "authors", values: ["k. le"] },
    { negated: false, field: null, values: ["foo:bar"] },
    { negated: false, field: null, values: ["-"] },
    { negated: false, field: null, values: ["x"] },
    { negated: false, field: "kind", values: ["pdf", "epub"] },
    { negated: false, field: "subjects", values: ["a,b", "c"] },
  ])
})

test("facets count each value once per entry, most common first", () => {
  const f = facetsOf(Object.values(books.notebooks))
  assert.deepEqual(f.authors.slice(0, 2), [{ value: "Ursula K. Le Guin", count: 2 }, { value: "Albert Camus", count: 1 }])
  assert.deepEqual(f.tags[0], { value: "Science Fiction", count: 2 })
  assert.deepEqual(f.kinds, [{ value: "epub", count: 4 }, { value: "pdf", count: 2 }, { value: "notebook", count: 1 }, { value: "quickpage", count: 1 }])
  assert.deepEqual(f.states, [{ value: "unread", count: 4 }, { value: "reading", count: 3 }, { value: "finished", count: 1 }])
})
