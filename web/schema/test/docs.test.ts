import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync, existsSync } from "node:fs"
import * as A from "@automerge/automerge"
import {
  libraryValue, notebookValue, strokeValue, folderValue,
  readLibrary, readNotebook, SchemaError, SCHEMA_VERSION,
  libraryFromJson, notebookFromJson,
  decodeStrokePoints,
  type Library, type Notebook,
} from "../src/index.ts"

const fixture = (name: string) => new URL(`../fixtures/${name}`, import.meta.url)
const json = (name: string) => JSON.parse(readFileSync(fixture(name), "utf8"))
const expectedLibrary: Library = libraryFromJson(json("library-v1.json"))
const expectedNotebook: Notebook = notebookFromJson(json("notebook-v1.json"))

const roundTrip = <T>(value: unknown): A.Doc<T> => A.load<T>(A.save(A.from(value as Record<string, unknown>)))

test("library survives save/load", () => {
  assert.deepEqual(readLibrary(roundTrip(libraryValue(expectedLibrary))), expectedLibrary)
})

test("notebook survives save/load", () => {
  assert.deepEqual(readNotebook(roundTrip(notebookValue(expectedNotebook))), expectedNotebook)
})

test("golden library written by TS reads back exactly", () => {
  const doc = A.load(readFileSync(fixture("library-v1.automerge")))
  assert.deepEqual(readLibrary(doc), expectedLibrary)
})

test("golden notebook written by TS reads back exactly", () => {
  const doc = A.load(readFileSync(fixture("notebook-v1.automerge")))
  assert.deepEqual(readNotebook(doc), expectedNotebook)
})

for (const name of ["library-from-kotlin.automerge", "notebook-from-kotlin.automerge"]) {
  test(`doc written by Kotlin reads back exactly: ${name}`, () => {
    assert.ok(existsSync(fixture(name)), `missing ${name}; run the Kotlin GoldenFixtureTest with UPDATE_FIXTURES=1`)
    const doc = A.load(readFileSync(fixture(name)))
    if (name.startsWith("library")) assert.deepEqual(readLibrary(doc), expectedLibrary)
    else assert.deepEqual(readNotebook(doc), expectedNotebook)
  })
}

test("golden notebook strokes hold decodable SB2 points", () => {
  for (const page of Object.values(expectedNotebook.pages)) {
    for (const s of Object.values(page.strokes)) {
      assert.ok(decodeStrokePoints(s.points).points.length > 0)
    }
  }
})

test("a stroke added in a later change merges into the page", () => {
  let doc = A.from(notebookValue(expectedNotebook) as Record<string, unknown>)
  const pageId = expectedNotebook.pageOrder[0]
  const stroke = Object.values(expectedNotebook.pages[pageId].strokes)[0]
  doc = A.change(doc, d => {
    (d as any).pages[pageId].strokes["added"] = strokeValue({ ...stroke, id: "added" })
  })
  const read = readNotebook(A.load(A.save(doc)))
  assert.deepEqual(read.pages[pageId].strokes["added"], { ...stroke, id: "added" })
})

test("rejects a collaborative Text where a scalar string is required", () => {
  let doc = A.from(libraryValue(expectedLibrary) as Record<string, unknown>)
  const folderId = Object.keys(expectedLibrary.folders)[0]
  doc = A.change(doc, d => { (d as any).folders[folderId].title = "plain JS string becomes Text" })
  assert.throws(() => readLibrary(doc), (e: unknown) =>
    e instanceof SchemaError && e.message.includes(`folders.${folderId}.title`))
})

test("rejects a doc of the wrong schema", () => {
  const doc = A.from(notebookValue(expectedNotebook) as Record<string, unknown>)
  assert.throws(() => readLibrary(doc), SchemaError)
})

test("rejects a newer schema version with an explicit message", () => {
  let doc = A.from(libraryValue(expectedLibrary) as Record<string, unknown>)
  doc = A.change(doc, d => { (d as any).version = SCHEMA_VERSION + 1 })
  assert.throws(() => readLibrary(doc), /newer/)
})

test("rejects a missing required field", () => {
  let doc = A.from(notebookValue(expectedNotebook) as Record<string, unknown>)
  const pageId = expectedNotebook.pageOrder[0]
  doc = A.change(doc, d => { delete (d as any).pages[pageId].background })
  assert.throws(() => readNotebook(doc), (e: unknown) =>
    e instanceof SchemaError && e.message.includes(`pages.${pageId}.background`))
})

// Concurrent "delete page" + "move page" can leave pageOrder pointing at a deleted page, holding a
// page twice, or missing a page. These are legal merge results, so the reader normalizes them.
test("drops pageOrder entries whose page was deleted", () => {
  let doc = A.from(notebookValue(expectedNotebook) as Record<string, unknown>)
  doc = A.change(doc, d => { (d as any).pageOrder.push(new A.ImmutableString("ghost")) })
  assert.deepEqual(readNotebook(doc).pageOrder, expectedNotebook.pageOrder)
})

test("keeps only the first of duplicated pageOrder entries", () => {
  let doc = A.from(notebookValue(expectedNotebook) as Record<string, unknown>)
  doc = A.change(doc, d => { (d as any).pageOrder.push(new A.ImmutableString(expectedNotebook.pageOrder[0])) })
  assert.deepEqual(readNotebook(doc).pageOrder, expectedNotebook.pageOrder)
})

test("appends pages missing from pageOrder, oldest first", () => {
  let doc = A.from(notebookValue(expectedNotebook) as Record<string, unknown>)
  const [first, ...rest] = expectedNotebook.pageOrder
  doc = A.change(doc, d => { (d as any).pageOrder.deleteAt(0) })
  assert.deepEqual(readNotebook(doc).pageOrder, [...rest, first])
})

test("folder values store scalar strings", () => {
  const f = Object.values(expectedLibrary.folders)[0]
  const v = folderValue(f) as Record<string, unknown>
  assert.ok(A.isImmutableString(v.title))
})

test("canvasWidth is optional: absent stays absent, present round-trips", () => {
  assert.equal("canvasWidth" in readNotebook(roundTrip(notebookValue(expectedNotebook))), false)
  const withWidth = { ...expectedNotebook, canvasWidth: 1860 }
  assert.deepEqual(readNotebook(roundTrip(notebookValue(withWidth))), withWidth)
})

test("canvasWidth must be a positive number", () => {
  const doc = roundTrip<Record<string, unknown>>(notebookValue(expectedNotebook))
  const bad = A.change(doc, d => { d.canvasWidth = 0 })
  assert.throws(() => readNotebook(bad), (e: unknown) => e instanceof SchemaError && /canvasWidth/.test((e as Error).message))
})
