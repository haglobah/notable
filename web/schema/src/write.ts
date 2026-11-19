/*
 * Builders for Automerge-ready values. Use them for whole docs (`A.from(libraryValue(l))`) and
 * inside `A.change` for edits (`d.pages[id].strokes[s.id] = strokeValue(s)`).
 *
 * Ids, enums and titles go in as ImmutableString: a plain JS string would become collaborative
 * Text, which the Kotlin reader (and readLibrary/readNotebook) reject.
 *
 * Floats go in as plain numbers, so 3.0 is stored as an int; both readers accept ints for float
 * fields. Float64 wrappers are not used: in @automerge/automerge 3.5.0 they are stored as a
 * `{value}` map inside A.from() and inside nested lists and maps.
 */
import { ImmutableString, getConflicts } from "@automerge/automerge"
import {
  BASE_VERSION, CODE_SCHEMA, CODE_VERSION, DELETED_PAGES_VERSION, LIBRARY_SCHEMA, NOTEBOOK_SCHEMA, isDefName,
  type Background, type BookMeta, type CodeDef, type CodeImage, type Folder, type Image, type Library, type Notebook, type NotebookEntry,
  type Page, type Source, type Stroke,
} from "./model.ts"

const s = (v: string) => new ImmutableString(v)
const sOrNull = (v: string | null) => (v === null ? null : s(v))
const f = (v: number) => {
  if (!Number.isFinite(v)) throw new TypeError(`expected a finite number, got ${v}`)
  return v
}
const int = (v: number) => {
  if (!Number.isInteger(v)) throw new TypeError(`expected an integer, got ${v}`)
  return v
}

export const folderValue = (x: Folder) => ({
  title: s(x.title), parentId: sOrNull(x.parentId), createdAt: x.createdAt, updatedAt: x.updatedAt,
  ...(x.trashedAt === undefined ? {} : { trashedAt: x.trashedAt }),
})

const sourceValue = (x: Source) =>
  x.kind === "none" ? { kind: s("none"), blob: null }
    : x.kind === "pdf" ? { kind: s("pdf"), blob: s(x.blob) }
      : { kind: s("epub"), blob: s(x.blob), layout: { w: f(x.layout.w), h: f(x.layout.h), em: f(x.layout.em) } }

export const bookMetaValue = (x: BookMeta) => ({
  authors: x.authors.map(s),
  series: sOrNull(x.series),
  seriesIndex: x.seriesIndex === null ? null : f(x.seriesIndex),
  description: sOrNull(x.description),
  publisher: sOrNull(x.publisher),
  language: sOrNull(x.language),
  isbn: sOrNull(x.isbn),
  published: sOrNull(x.published),
  subjects: x.subjects.map(s),
})

export const notebookEntryValue = (x: NotebookEntry) => ({
  docUrl: s(x.docUrl), title: s(x.title), folderId: sOrNull(x.folderId), kind: s(x.kind),
  source: sourceValue(x.source), createdAt: x.createdAt, updatedAt: x.updatedAt,
  ...(x.meta === undefined ? {} : { meta: bookMetaValue(x.meta) }),
  ...(x.readAt === undefined ? {} : { readAt: x.readAt }),
  ...(x.finishedAt === undefined ? {} : { finishedAt: x.finishedAt }),
  ...(x.pageCount === undefined ? {} : { pageCount: int(x.pageCount) }),
  ...(x.readPage === undefined ? {} : { readPage: int(x.readPage) }),
  ...(x.rating === undefined ? {} : { rating: x.rating === null ? null : checkRating(x.rating) }),
  ...(x.queuedAt === undefined ? {} : { queuedAt: x.queuedAt }),
  ...(x.trashedAt === undefined ? {} : { trashedAt: x.trashedAt }),
})

const checkRating = (r: number) => {
  if (!Number.isInteger(r) || r < 1 || r > 10) throw new TypeError(`a rating is 1 to 10, got ${r}`)
  return r
}

/*
 * Reading state (docs/plans/ebook-features.md). Call inside `A.change` on the library doc; each
 * sets one field of the entry, so concurrent edits of other fields survive.
 */

/** A reader opened entry [id]. */
export function markRead(d: Record<string, any>, id: string, at: Date) {
  const e = d.notebooks[id]
  if (e === undefined) throw new Error(`no notebook ${id}`)
  e.readAt = at
}

/** Call inside `A.change` on a notebook doc: the page a reader shows, so every device reopens there. */
export function setOpenPage(d: Record<string, any>, pageId: string) {
  if (d.pages[pageId] === undefined) throw new Error(`no page ${pageId}`)
  d.openPageId = s(pageId)
}

const entryOf = (d: Record<string, any>, id: string) => {
  const e = d.notebooks[id]
  if (e === undefined) throw new Error(`no notebook ${id}`)
  return e
}

/** [rating] 1 to 10 for entry [id]; null clears it. */
export function setRating(d: Record<string, any>, id: string, rating: number | null) {
  entryOf(d, id).rating = rating === null ? null : checkRating(rating)
}

/**
 * The entry's page count and open page index, from its notebook doc. Writes only what changed:
 * Automerge records a put of an unchanged value too, and page turns would grow the library.
 */
export function setReading(d: Record<string, any>, id: string, r: { pageCount?: number; readPage?: number }) {
  const e = entryOf(d, id)
  if (r.pageCount !== undefined && e.pageCount !== int(r.pageCount)) e.pageCount = r.pageCount
  if (r.readPage !== undefined && e.readPage !== int(r.readPage)) e.readPage = r.readPage
}

/** [at] marks entry [id] finished; null marks it unread again. */
export function setFinished(d: Record<string, any>, id: string, at: Date | null) {
  const e = d.notebooks[id]
  if (e === undefined) throw new Error(`no notebook ${id}`)
  e.finishedAt = at
}

/*
 * Read next and the trash (docs/plans/library-features.md). Call inside `A.change` on the library
 * doc; each sets one field.
 */

/** [at] puts entry [id] on the Read next list; null takes it off. */
export function setQueued(d: Record<string, any>, id: string, at: Date | null) {
  entryOf(d, id).queuedAt = at
}

/** [at] moves entry [id] to the trash; null restores it (see restoreEntry for the parent). */
export function setEntryTrashed(d: Record<string, any>, id: string, at: Date | null) {
  entryOf(d, id).trashedAt = at
}

/** [at] moves folder [id], with everything in it, to the trash; null restores it. */
export function setFolderTrashed(d: Record<string, any>, id: string, at: Date | null) {
  const f = d.folders[id]
  if (f === undefined) throw new Error(`no folder ${id}`)
  f.trashedAt = at
}

/** Moves entry [id] into folder [folderId] (null: the root). A structural edit: updatedAt moves. */
export function moveEntry(d: Record<string, any>, id: string, folderId: string | null, at: Date) {
  const e = entryOf(d, id)
  e.folderId = sOrNull(folderId)
  e.updatedAt = at
}

/**
 * Moves folder [id] under [parentId] (null: the root). False, and nothing written, when
 * [parentId] is [id] itself or inside it.
 */
export function moveFolder(d: Record<string, any>, id: string, parentId: string | null, at: Date): boolean {
  const f = d.folders[id]
  if (f === undefined) throw new Error(`no folder ${id}`)
  const seen = new Set<string>()
  for (let p = parentId; p !== null && !seen.has(p); p = d.folders[p]?.parentId == null ? null : String(d.folders[p].parentId)) {
    if (p === id) return false
    seen.add(p)
  }
  f.parentId = sOrNull(parentId)
  f.updatedAt = at
  return true
}

/** Deletes entry [id] for good (its notebook doc stays, unreferenced). */
export function deleteEntry(d: Record<string, any>, id: string) {
  if (d.notebooks[id] !== undefined) delete d.notebooks[id]
}

/** Deletes folder [id] for good, with every folder below it and every entry in them, as the Boox does. */
export function deleteFolder(d: Record<string, any>, id: string) {
  const gone = new Set<string>([id])
  for (let grew = true; grew;) {
    grew = false
    for (const [fid, f] of Object.entries<any>(d.folders)) {
      if (!gone.has(fid) && f.parentId != null && gone.has(String(f.parentId))) { gone.add(fid); grew = true }
    }
  }
  for (const fid of gone) if (d.folders[fid] !== undefined) delete d.folders[fid]
  for (const [eid, e] of Object.entries<any>(d.notebooks)) if (e.folderId != null && gone.has(String(e.folderId))) delete d.notebooks[eid]
}

export const libraryValue = (x: Library) => ({
  schema: s(LIBRARY_SCHEMA),
  version: BASE_VERSION,
  ...(x.codeDocUrl === undefined ? {} : { codeDocUrl: s(x.codeDocUrl) }),
  ...(x.quickNotebookId === undefined ? {} : { quickNotebookId: s(x.quickNotebookId) }),
  folders: Object.fromEntries(Object.values(x.folders).map(v => [v.id, folderValue(v)])),
  notebooks: Object.fromEntries(Object.values(x.notebooks).map(v => [v.id, notebookEntryValue(v)])),
})

export const backgroundValue = (x: Background) => ({ type: s(x.type), native: sOrNull(x.native), blob: sOrNull(x.blob) })

/** Strokes are immutable entries: an edit replaces the whole entry, an erase deletes the key. */
export const strokeValue = (x: Stroke) => ({
  pen: s(x.pen),
  color: int(x.color),
  size: f(x.size),
  bbox: [f(x.bbox.left), f(x.bbox.top), f(x.bbox.right), f(x.bbox.bottom)],
  points: x.points,
  createdAt: x.createdAt,
  updatedAt: x.updatedAt,
})

export const imageValue = (x: Image) => ({
  blob: s(x.blob),
  rect: [int(x.rect.x), int(x.rect.y), int(x.rect.width), int(x.rect.height)],
  createdAt: x.createdAt,
  updatedAt: x.updatedAt,
})

export const pageValue = (x: Page) => ({
  background: backgroundValue(x.background),
  strokes: Object.fromEntries(Object.values(x.strokes).map(v => [v.id, strokeValue(v)])),
  images: Object.fromEntries(Object.values(x.images).map(v => [v.id, imageValue(v)])),
  createdAt: x.createdAt,
  updatedAt: x.updatedAt,
})

export const notebookValue = (x: Notebook) => ({
  schema: s(NOTEBOOK_SCHEMA),
  version: BASE_VERSION,
  notebookId: s(x.notebookId),
  ...(x.canvasWidth === undefined ? {} : { canvasWidth: f(x.canvasWidth) }),
  openPageId: sOrNull(x.openPageId),
  defaultBackground: backgroundValue(x.defaultBackground),
  pageOrder: x.pageOrder.map(s),
  pages: Object.fromEntries(Object.values(x.pages).map(v => [v.id, pageValue(v)])),
})

/*
 * Page deletion (ADR-001, "Delete against draw"). Call these inside `A.change` on a notebook doc.
 * A delete keeps the page's content and marks it `deleted: true`; every stroke or image write
 * sets `deleted: false`. A write the delete didn't see conflicts with it, and readers keep a page
 * unless every concurrent value is true, so drawing on a page deleted elsewhere brings it back.
 */

/** True when page map [page] counts as deleted: every concurrent value of `deleted` is true. */
export function isPageDeleted(page: Record<string, unknown>): boolean {
  if (!("deleted" in page) || page.deleted === undefined) return false
  const conflicts = getConflicts(page, "deleted")
  const values = conflicts ? Object.values(conflicts) : [page.deleted]
  return values.length > 0 && values.every(v => v === true)
}

/** Deletes page [pageId]: marks it deleted, takes it out of pageOrder, keeps its content. */
export function deletePage(d: Record<string, any>, pageId: string) {
  const page = d.pages[pageId]
  if (page === undefined) throw new Error(`no page ${pageId}`)
  page.deleted = true
  for (let i = d.pageOrder.length - 1; i >= 0; i--) if (String(d.pageOrder[i]) === pageId) d.pageOrder.deleteAt(i)
  if (d.version < DELETED_PAGES_VERSION) d.version = DELETED_PAGES_VERSION
}

/**
 * Call with every stroke or image write on page [pageId]. A page deleted earlier (in this doc's
 * history) comes back at the end of pageOrder.
 */
export function markPageLive(d: Record<string, any>, pageId: string) {
  const page = d.pages[pageId]
  if (page === undefined) throw new Error(`no page ${pageId}`)
  if (isPageDeleted(page) && !d.pageOrder.some((id: unknown) => String(id) === pageId)) d.pageOrder.push(s(pageId))
  // A fresh op every time: Automerge skips a put of the value already there, and that old op may be
  // one a concurrent delete has seen, so this write would not conflict with it.
  if ("deleted" in page) delete page.deleted
  page.deleted = false
}

/*
 * The code doc (ADR-002). A definition is a whole, immutable entry: redefining puts a new map
 * under the name (`d.defs[name] = codeDefValue(def)`), never edits fields of the old one, so two
 * concurrent redefinitions resolve to one of them whole. Undefining deletes the key.
 */

export const codeDefValue = (x: CodeDef) => {
  if (!isDefName(x.name)) throw new TypeError(`not a definition name: '${x.name}' (expected kind/name, e.g. command/clear-page)`)
  if (!Number.isInteger(x.hostApi) || x.hostApi < 1) throw new TypeError(`hostApi must be a positive integer, got ${x.hostApi}`)
  return { source: s(x.source), hostApi: int(x.hostApi), updatedAt: x.updatedAt, device: s(x.device) }
}

export const codeValue = (x: CodeImage) => ({
  schema: s(CODE_SCHEMA),
  version: CODE_VERSION,
  defs: Object.fromEntries(Object.values(x.defs).map(v => [v.name, codeDefValue(v)])),
})
