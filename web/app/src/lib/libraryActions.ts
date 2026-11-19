/*
 * Library management on the PWA (docs/plans/library-features.md): the same edits the Boox makes
 * (LibraryEdits.kt), each a single field through the shared schema's setters.
 */
import {
  deleteEntry, deleteFolder, isFolderTrashed, moveEntry, moveFolder, setEntryTrashed, setFinished, setFolderTrashed, setQueued,
  type Library, type TrashItem,
} from "@notable/schema"

/** A selected library item: a notebook entry or a folder. */
export type Item = { kind: "notebook"; id: string } | { kind: "folder"; id: string }
export const itemKey = (i: Item) => `${i.kind}:${i.id}`

type Doc = Record<string, any>

/** Moves the items into [folderId] (null: the root); a folder never goes into itself. Returns how many moved. */
export function moveItems(d: Doc, items: Item[], folderId: string | null, at: Date): number {
  let n = 0
  for (const i of items) {
    if (i.kind === "notebook") { moveEntry(d, i.id, folderId, at); n++ }
    else if (moveFolder(d, i.id, folderId, at)) n++
  }
  return n
}

/** Mark as read ([at]) or unread (null); folders are skipped. */
export function setItemsFinished(d: Doc, items: Item[], at: Date | null) {
  for (const i of items) if (i.kind === "notebook") setFinished(d, i.id, at)
}

/** Onto the Read next list ([at]) or off it (null); folders are skipped. */
export function setItemsQueued(d: Doc, items: Item[], at: Date | null) {
  for (const i of items) if (i.kind === "notebook") setQueued(d, i.id, at)
}

export function trashItems(d: Doc, items: Item[], at: Date) {
  for (const i of items) {
    if (i.kind === "notebook") setEntryTrashed(d, i.id, at)
    else setFolderTrashed(d, i.id, at)
  }
}

/** Folder [id] exists and neither it nor one above it is in the trash. */
const live = (lib: Library, id: string) => id in lib.folders && !isFolderTrashed(lib, id)

/** Out of the trash; to the root when its folder is itself in the trash or gone ([lib]: as read before). */
export function restore(d: Doc, lib: Library, item: TrashItem, at: Date) {
  if (item.kind === "notebook") {
    const f = item.entry.folderId
    if (f !== null && !live(lib, f)) moveEntry(d, item.entry.id, null, at)
    setEntryTrashed(d, item.entry.id, null)
  } else {
    const p = item.folder.parentId
    if (p !== null && !live(lib, p)) moveFolder(d, item.folder.id, null, at)
    setFolderTrashed(d, item.folder.id, null)
  }
}

/** Deletes a trash item for good. */
export function deleteForever(d: Doc, item: TrashItem) {
  if (item.kind === "notebook") deleteEntry(d, item.entry.id)
  else deleteFolder(d, item.folder.id)
}
