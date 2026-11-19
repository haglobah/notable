/* The library doc as a Solid signal, re-read on every change (the Boox syncs into it live). */
import { createSignal, onCleanup, type Accessor } from "solid-js"
import type { DocHandle } from "@automerge/automerge-repo"
import { isTrashed, readLibrary, type Folder, type Library, type NotebookEntry } from "@notable/schema"

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

/* What's in the trash (docs/plans/library-features.md) stays out of every list here. */

export const childFolders = (lib: Library, parentId: string | null): Folder[] =>
  Object.values(lib.folders).filter(f => f.parentId === parentId && !f.trashedAt).sort((a, b) => collator.compare(a.title, b.title))

/** Most recently changed first: what you were just reading or writing is at the top. */
export const entriesIn = (lib: Library, folderId: string | null): NotebookEntry[] =>
  Object.values(lib.notebooks).filter(n => n.folderId === folderId && !n.trashedAt).sort((a, b) => b.updatedAt.getTime() - a.updatedAt.getTime())

/**
 * The bar's "Recent" menu: entries a reader opened, most recently opened first (ties by title), at
 * most [limit]. Never-opened ones and notebooks known to have no pages are left out.
 */
export const recentEntries = (lib: Library, limit = 10): NotebookEntry[] =>
  Object.values(lib.notebooks)
    .filter(n => n.readAt && n.pageCount !== 0 && !isTrashed(lib, n))
    .sort((a, b) => b.readAt!.getTime() - a.readAt!.getTime() || collator.compare(a.title, b.title))
    .slice(0, limit)

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
  Object.values(lib.notebooks).filter(n => n.folderId === folderId && !n.trashedAt).length +
  Object.values(lib.folders).filter(f => f.parentId === folderId && !f.trashedAt).length

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

const relFmt = new Intl.RelativeTimeFormat(undefined, { numeric: "auto", style: "short" })

/** When an entry was opened, for the "Recent" menu: "just now", "5 min. ago", "3 days ago", then a date. */
export function openedAgo(d: Date, now = new Date()): string {
  const minutes = Math.round((now.getTime() - d.getTime()) / 60_000)
  if (minutes < 1) return "just now"
  if (minutes < 60) return relFmt.format(-minutes, "minute")
  const hours = Math.round(minutes / 60)
  if (hours < 24) return relFmt.format(-hours, "hour")
  const days = Math.round(hours / 24)
  if (days < 7) return relFmt.format(-days, "day")
  return d.getFullYear() === now.getFullYear() ? dayFmt.format(d) : yearFmt.format(d)
}
