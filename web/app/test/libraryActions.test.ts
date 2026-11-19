// Library management on the PWA (docs/plans/library-features.md); the Boox twin is LibraryEditsTest.
import { test } from "node:test"
import assert from "node:assert/strict"
import * as A from "@automerge/automerge"
import { libraryValue, readLibrary, trashOf, type Folder, type Library, type NotebookEntry } from "@notable/schema"
import { childFolders, countIn, entriesIn, recentEntries } from "../src/lib/library.ts"
import { deleteForever, moveItems, restore, setItemsFinished, setItemsQueued, trashItems } from "../src/lib/libraryActions.ts"

const d = (iso: string) => new Date(iso)
const folder = (id: string, parentId: string | null, extra: Partial<Folder> = {}): Folder =>
  ({ id, title: id, parentId, createdAt: d("2026-01-01"), updatedAt: d("2026-01-01"), ...extra })
const entry = (id: string, folderId: string | null, extra: Partial<NotebookEntry> = {}): NotebookEntry =>
  ({ id, docUrl: `automerge:${id}`, title: id, folderId, kind: "notebook", source: { kind: "none" }, createdAt: d("2026-01-01"), updatedAt: d("2026-01-02"), ...extra })

const lib: Library = {
  folders: { a: folder("a", null), b: folder("b", "a"), c: folder("c", null) },
  notebooks: { n1: entry("n1", "a", { readAt: d("2026-02-01") }), n2: entry("n2", "b"), n3: entry("n3", null) },
}
type D = Record<string, any>
const doc = () => A.from(libraryValue(lib) as D)
const at = d("2026-11-19T10:00:00Z")

test("bulk moves, read state and Read next, folders skipped where they don't apply", () => {
  let x = A.change(doc(), dd => {
    assert.equal(moveItems(dd, [{ kind: "notebook", id: "n3" }, { kind: "folder", id: "a" }, { kind: "folder", id: "c" }], "c", at), 2)
    setItemsFinished(dd, [{ kind: "notebook", id: "n1" }, { kind: "folder", id: "a" }], at)
    setItemsQueued(dd, [{ kind: "notebook", id: "n2" }], at)
  })
  const l = readLibrary(x)
  assert.equal(l.notebooks.n3.folderId, "c")
  assert.equal(l.folders.a.parentId, "c")
  assert.equal(l.folders.c.parentId, null, "a folder can't go into itself")
  assert.deepEqual(l.notebooks.n1.finishedAt, at)
  assert.deepEqual(l.notebooks.n2.queuedAt, at)
  x = A.change(x, dd => setItemsFinished(dd, [{ kind: "notebook", id: "n1" }], null))
  assert.equal(readLibrary(x).notebooks.n1.finishedAt, null)
})

test("the trash hides items from every list; restore brings them back, to the root when their folder is in the trash", () => {
  let x = A.change(doc(), dd => trashItems(dd, [{ kind: "folder", id: "a" }, { kind: "notebook", id: "n3" }], at))
  let l = readLibrary(x)
  assert.deepEqual(childFolders(l, null).map(f => f.id), ["c"])
  assert.deepEqual(entriesIn(l, null), [])
  assert.deepEqual(recentEntries(l), [], "n1 is in a trashed folder")
  x = A.change(x, dd => trashItems(dd, [{ kind: "folder", id: "b" }], at))
  l = readLibrary(x)
  const b = trashOf(l).find(t => t.kind === "folder" && t.folder.id === "b")!
  x = A.change(x, dd => restore(dd, l, b, at))
  l = readLibrary(x)
  assert.equal(l.folders.b.parentId, null, "its parent a is still in the trash")
  assert.equal(l.folders.b.trashedAt, null)
  assert.equal(countIn(l, "b"), 1)
  const a = trashOf(l).find(t => t.kind === "folder" && t.folder.id === "a")!
  x = A.change(x, dd => deleteForever(dd, a))
  l = readLibrary(x)
  assert.ok(!("a" in l.folders) && !("n1" in l.notebooks))
  assert.ok("n2" in l.notebooks, "b moved out before a was deleted")
})
