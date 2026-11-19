// Read next and the trash (docs/plans/library-features.md). The Kotlin twin is TrashSchemaTest.
import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync, existsSync } from "node:fs"
import * as A from "@automerge/automerge"
import {
  libraryValue, libraryFromJson, libraryToJson, readLibrary, searchLibrary, readNext, trashOf, expiredTrash, isTrashed,
  setQueued, setEntryTrashed, setFolderTrashed, moveEntry, moveFolder, deleteEntry, deleteFolder, type Library, type TrashItem,
} from "../src/index.ts"

const fixture = (name: string) => new URL(`../fixtures/${name}`, import.meta.url)
const json = (name: string) => JSON.parse(readFileSync(fixture(name), "utf8"))
const lib: Library = libraryFromJson(json("library-trash-v1.json"))
const expect: {
  now: string; readNext: string[]; trash: [string, string][]; expired: [string, string][]; trashed: string[]
  cases: { query: string; ids: string[] }[]
} = json("trash-v1.json")

type D = Record<string, any>
const doc = () => A.from(libraryValue(lib) as D)
const pairs = (items: TrashItem[]) => items.map(t => t.kind === "folder" ? ["folder", t.folder.id] : ["notebook", t.entry.id])

test("queuedAt and trashedAt survive save/load, null apart from absent", () => {
  const back = readLibrary(A.load(A.save(doc())))
  assert.deepEqual(back, lib)
  assert.equal(back.notebooks["n-off"].queuedAt, null)
  assert.ok(!("queuedAt" in back.notebooks["n-old"]))
  assert.equal(back.folders["f-keep"].trashedAt, null)
  assert.ok(!("trashedAt" in back.folders["f-sub"]))
  assert.deepEqual(libraryFromJson(libraryToJson(back)), lib)
})

test("golden trash library written by TS reads back exactly", () => {
  assert.deepEqual(readLibrary(A.load(readFileSync(fixture("library-trash-v1.automerge")))), lib)
})

test("trash library written by Kotlin reads back exactly", t => {
  if (!existsSync(fixture("library-trash-from-kotlin.automerge"))) {
    return t.skip("missing library-trash-from-kotlin.automerge; run the Kotlin TrashSchemaTest with UPDATE_FIXTURES=1")
  }
  assert.deepEqual(readLibrary(A.load(readFileSync(fixture("library-trash-from-kotlin.automerge")))), lib)
})

test("trashedAt and queuedAt are timestamps or null", () => {
  assert.throws(() => readLibrary(A.change(doc(), x => { x.notebooks["n-dune"].queuedAt = 5 })), /notebooks\.n-dune\.queuedAt: expected a timestamp/)
  assert.throws(() => readLibrary(A.change(doc(), x => { x.folders["f-old"].trashedAt = "x" })), /folders\.f-old\.trashedAt/)
})

test("Read next list", () => {
  assert.deepEqual(readNext(lib).map(e => e.id), expect.readNext)
})

test("trash, expired trash, and what is in the trash", () => {
  assert.deepEqual(pairs(trashOf(lib)), expect.trash)
  assert.deepEqual(pairs(expiredTrash(lib, new Date(expect.now))), expect.expired)
  assert.deepEqual(Object.values(lib.notebooks).filter(e => isTrashed(lib, e)).map(e => e.id).sort(), [...expect.trashed].sort())
})

for (const c of expect.cases) {
  test(`search ${JSON.stringify(c.query)} leaves out the trash`, () => {
    assert.deepEqual(searchLibrary(lib, c.query).map(e => e.id), c.ids)
  })
}

test("setters write one field each", () => {
  const at = new Date("2026-11-19T12:00:00.000Z")
  let d = A.change(doc(), x => { setQueued(x, "n-off", at); setEntryTrashed(x, "n-dune", at); setFolderTrashed(x, "f-keep", at) })
  let l = readLibrary(d)
  assert.deepEqual(l.notebooks["n-off"].queuedAt, at)
  assert.deepEqual(l.notebooks["n-dune"].trashedAt, at)
  assert.ok(isTrashed(l, l.notebooks["n-back"]))
  d = A.change(d, x => { setQueued(x, "n-off", null); setEntryTrashed(x, "n-dune", null); setFolderTrashed(x, "f-keep", null) })
  l = readLibrary(d)
  assert.equal(l.notebooks["n-off"].queuedAt, null)
  assert.ok(!isTrashed(l, l.notebooks["n-dune"]) && !isTrashed(l, l.notebooks["n-back"]))
  assert.throws(() => A.change(doc(), x => setFolderTrashed(x, "nope", at)), /no folder nope/)
})

test("moves set one place and the edited date; a folder can't go into itself", () => {
  const at = new Date("2026-11-19T12:00:00.000Z")
  let d = A.change(doc(), x => { moveEntry(x, "n-dune", "f-keep", at); assert.ok(moveFolder(x, "f-keep", "f-old", at)) })
  let l = readLibrary(d)
  assert.equal(l.notebooks["n-dune"].folderId, "f-keep")
  assert.deepEqual(l.notebooks["n-dune"].updatedAt, at)
  assert.equal(l.folders["f-keep"].parentId, "f-old")
  d = A.change(d, x => { assert.equal(moveFolder(x, "f-old", "f-sub", at), false); assert.equal(moveFolder(x, "f-old", "f-old", at), false) })
  assert.equal(readLibrary(d).folders["f-old"].parentId, null)
  d = A.change(d, x => { moveEntry(x, "n-dune", null, at) })
  assert.equal(readLibrary(d).notebooks["n-dune"].folderId, null)
})

test("deleting a folder takes its folders and entries with it, as on the Boox", () => {
  const d = A.change(doc(), x => { deleteFolder(x, "f-old"); deleteEntry(x, "n-gone"); deleteEntry(x, "nope") })
  const l = readLibrary(d)
  assert.ok(!("f-old" in l.folders) && !("f-sub" in l.folders) && "f-keep" in l.folders)
  assert.ok(!("n-sub" in l.notebooks) && !("n-gone" in l.notebooks) && "n-dune" in l.notebooks)
  assert.deepEqual(trashOf(l).map(t => t.kind === "folder" ? t.folder.id : t.entry.id), ["n-old"])
})
