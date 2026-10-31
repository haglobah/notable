import { test } from "node:test"
import assert from "node:assert/strict"
import { directionLabel, hasTerms, parseSort, rawTokens, selected, setOnly, toggle, withoutFields } from "../src/lib/filters.ts"

test("raw tokens keep quotes and spelling", () => {
  assert.deepEqual(rawTokens(` Le  author:"K. Le Guin",camus  -is:finished `), ["Le", `author:"K. Le Guin",camus`, "-is:finished"])
})

test("toggling adds a value to the field's token, quoting where needed", () => {
  let q = "dune"
  q = toggle(q, "kind", "pdf")
  assert.equal(q, "dune kind:pdf")
  q = toggle(q, "kind", "epub")
  assert.equal(q, "dune kind:pdf,epub")
  q = toggle(q, "author", "Ursula K. Le Guin")
  assert.equal(q, `dune kind:pdf,epub author:"Ursula K. Le Guin"`)
  assert.deepEqual(selected(q, "author"), ["Ursula K. Le Guin"])
  q = toggle(q, "author", "ursula k. le guin")
  assert.equal(q, "dune kind:pdf,epub")
  q = toggle(toggle(q, "kind", "PDF"), "kind", "epub")
  assert.equal(q, "dune")
})

test("negated tokens aren't selections and stay", () => {
  assert.deepEqual(selected("-kind:pdf", "kind"), [])
  assert.equal(toggle("-kind:pdf", "kind", "epub"), "-kind:pdf kind:epub")
})

test("a second token of the field is folded into the first", () => {
  assert.equal(toggle("kind:pdf x KIND:epub", "kind", "notebook"), "kind:pdf,notebook x")
})

test("values with commas, colons or quotes are quoted, quotes dropped", () => {
  assert.equal(toggle("", "tag", "a, b"), `tag:"a, b"`)
  assert.equal(toggle("", "series", `The "Best": 1`), `series:"The Best: 1"`)
  assert.deepEqual(selected(`tag:"a, b",c`, "tag"), ["a, b", "c"])
})

test("clearing filters leaves the words", () => {
  assert.equal(withoutFields(`le guin is:reading kind:epub author:"x y"`, ["is", "kind", "author"]), "le guin")
})

test("relevance only matters with words", () => {
  assert.equal(hasTerms("is:reading kind:pdf"), false)
  assert.equal(hasTerms("-is:finished dune"), true)
})

test("sort from the URL, with each key's default direction", () => {
  assert.deepEqual(parseSort("title", undefined), { sort: "title", descending: false })
  assert.deepEqual(parseSort("read", undefined), { sort: "read", descending: true })
  assert.deepEqual(parseSort("read", "asc"), { sort: "read", descending: false })
  assert.equal(parseSort("bogus", "asc"), null)
  assert.equal(parseSort(undefined, undefined), null)
  assert.equal(directionLabel({ sort: "author", descending: true }), "Z to A")
  assert.equal(directionLabel({ sort: "added", descending: false }), "Oldest first")
  assert.equal(directionLabel({ sort: "length", descending: false }), "Shortest first")
  assert.equal(directionLabel({ sort: "rating", descending: true }), "Highest first")
  assert.equal(directionLabel({ sort: "progress", descending: false }), "Least read first")
  assert.deepEqual(parseSort("length", undefined), { sort: "length", descending: false })
})

test("a single choice replaces its field's token in place, or removes it", () => {
  assert.equal(setOnly("dune is:unread,finished kind:pdf", "is", "reading"), "dune is:reading kind:pdf")
  assert.equal(setOnly("dune", "is", "reading"), "dune is:reading")
  assert.equal(setOnly("dune is:reading kind:pdf", "is", null), "dune kind:pdf")
  assert.equal(setOnly("-is:finished", "is", "reading"), "-is:finished is:reading")
})
