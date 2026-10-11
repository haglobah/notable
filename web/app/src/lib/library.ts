/* The library doc as a Solid signal, re-read on every change (the Boox syncs into it live). */
import { createSignal, onCleanup, type Accessor } from "solid-js"
import type { DocHandle } from "@automerge/automerge-repo"
import { readLibrary, type Folder, type Library, type NotebookEntry } from "@notable/schema"

export type LibraryState = { ok: true; library: Library } | { ok: false; error: string }

const read = (handle: DocHandle<unknown>): LibraryState => {
  try {
    return { ok: true, library: readLibrary(handle.doc()) }
  } catch (e) {
    return { ok: false, error: (e as Error).message }
  }
}

export function useLibrary(handle: DocHandle<unknown>): Accessor<LibraryState> {
  const [state, setState] = createSignal(read(handle), { equals: false })
  const onChange = () => setState(read(handle))
  handle.on("change", onChange)
  onCleanup(() => handle.off("change", onChange))
  return state
}

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: "base" })

export const childFolders = (lib: Library, parentId: string | null): Folder[] =>
  Object.values(lib.folders).filter(f => f.parentId === parentId).sort((a, b) => collator.compare(a.title, b.title))

/** Most recently changed first: what you were just reading or writing is at the top. */
export const entriesIn = (lib: Library, folderId: string | null): NotebookEntry[] =>
  Object.values(lib.notebooks).filter(n => n.folderId === folderId).sort((a, b) => b.updatedAt.getTime() - a.updatedAt.getTime())

/** Root first. Stops at a missing or cyclic parent rather than looping. */
export function folderPath(lib: Library, folderId: string | null): Folder[] {
  const path: Folder[] = []
  const seen = new Set<string>()
  let id = folderId
  while (id && !seen.has(id) && lib.folders[id]) {
    seen.add(id)
    path.unshift(lib.folders[id])
    id = lib.folders[id].parentId
  }
  return path
}

export const countIn = (lib: Library, folderId: string) =>
  Object.values(lib.notebooks).filter(n => n.folderId === folderId).length +
  Object.values(lib.folders).filter(f => f.parentId === folderId).length

export type KindLabel = "PDF" | "EPUB" | "Notebook" | "Quick page"
export const kindOf = (e: NotebookEntry): KindLabel =>
  e.kind === "quickPage" ? "Quick page" : e.source.kind === "pdf" ? "PDF" : e.source.kind === "epub" ? "EPUB" : "Notebook"

const dayFmt = new Intl.DateTimeFormat(undefined, { day: "numeric", month: "long" })
const yearFmt = new Intl.DateTimeFormat(undefined, { day: "numeric", month: "long", year: "numeric" })
const timeFmt = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" })

export function edited(d: Date, now = new Date()): string {
  const sameDay = d.toDateString() === now.toDateString()
  if (sameDay) return `today at ${timeFmt.format(d)}`
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (d.toDateString() === yesterday.toDateString()) return "yesterday"
  return d.getFullYear() === now.getFullYear() ? dayFmt.format(d) : yearFmt.format(d)
}
