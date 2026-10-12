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
  BASE_VERSION, DELETED_PAGES_VERSION, LIBRARY_SCHEMA, NOTEBOOK_SCHEMA,
  type Background, type Folder, type Image, type Library, type Notebook, type NotebookEntry, type Page, type Source, type Stroke,
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
})

const sourceValue = (x: Source) =>
  x.kind === "none" ? { kind: s("none"), blob: null }
    : x.kind === "pdf" ? { kind: s("pdf"), blob: s(x.blob) }
      : { kind: s("epub"), blob: s(x.blob), layout: { w: f(x.layout.w), h: f(x.layout.h), em: f(x.layout.em) } }

export const notebookEntryValue = (x: NotebookEntry) => ({
  docUrl: s(x.docUrl), title: s(x.title), folderId: sOrNull(x.folderId), kind: s(x.kind),
  source: sourceValue(x.source), createdAt: x.createdAt, updatedAt: x.updatedAt,
})

export const libraryValue = (x: Library) => ({
  schema: s(LIBRARY_SCHEMA),
  version: BASE_VERSION,
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
