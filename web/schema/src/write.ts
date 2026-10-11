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
import { ImmutableString } from "@automerge/automerge"
import {
  LIBRARY_SCHEMA, NOTEBOOK_SCHEMA, SCHEMA_VERSION,
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
  version: SCHEMA_VERSION,
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
  version: SCHEMA_VERSION,
  notebookId: s(x.notebookId),
  ...(x.canvasWidth === undefined ? {} : { canvasWidth: f(x.canvasWidth) }),
  openPageId: sOrNull(x.openPageId),
  defaultBackground: backgroundValue(x.defaultBackground),
  pageOrder: x.pageOrder.map(s),
  pages: Object.fromEntries(Object.values(x.pages).map(v => [v.id, pageValue(v)])),
})
