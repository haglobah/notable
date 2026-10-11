/*
 * Strict readers. Anything that does not match the schema throws SchemaError naming the dotted
 * path; nothing is repaired silently. The only normalization is pageOrder, whose inconsistent
 * states are legal results of concurrent edits.
 */
import { isImmutableString } from "@automerge/automerge"
import {
  LIBRARY_SCHEMA, NOTEBOOK_SCHEMA, SCHEMA_VERSION,
  type Background, type EntryKind, type Library, type Notebook, type Page, type Source,
} from "./model.ts"

export class SchemaError extends Error {}

type Obj = Record<string, unknown>
const at = (p: string, k: string) => (p ? `${p}.${k}` : k)

function field(o: Obj, k: string, p: string): unknown {
  if (!(k in o) || o[k] === undefined) throw new SchemaError(`${at(p, k)}: missing`)
  return o[k]
}

function scalar(v: unknown, path: string): string {
  if (isImmutableString(v)) return v.toString()
  if (typeof v === "string") throw new SchemaError(`${path}: expected a scalar string, got collaborative Text`)
  throw new SchemaError(`${path}: expected a scalar string, got ${describe(v)}`)
}

const describe = (v: unknown) => (v === null ? "null" : Array.isArray(v) ? "list" : v instanceof Uint8Array ? "bytes" : typeof v)

const str = (o: Obj, k: string, p: string) => scalar(field(o, k, p), at(p, k))
const strOrNull = (o: Obj, k: string, p: string) => {
  const v = field(o, k, p)
  return v === null ? null : scalar(v, at(p, k))
}

function num(v: unknown, path: string): number {
  if (typeof v !== "number") throw new SchemaError(`${path}: expected a number, got ${describe(v)}`)
  return v
}

function int(v: unknown, path: string): number {
  if (!Number.isInteger(v)) throw new SchemaError(`${path}: expected an integer, got ${describe(v)} ${String(v)}`)
  return v as number
}

function map(o: Obj, k: string, p: string): Obj {
  const v = field(o, k, p)
  if (v === null || typeof v !== "object" || Array.isArray(v) || v instanceof Uint8Array || v instanceof Date || isImmutableString(v)) {
    throw new SchemaError(`${at(p, k)}: expected a map`)
  }
  return v as Obj
}

function list(o: Obj, k: string, p: string): unknown[] {
  const v = field(o, k, p)
  if (!Array.isArray(v)) throw new SchemaError(`${at(p, k)}: expected a list`)
  return v
}

function date(o: Obj, k: string, p: string): Date {
  const v = field(o, k, p)
  if (!(v instanceof Date)) throw new SchemaError(`${at(p, k)}: expected a timestamp`)
  return v
}

function bytes(o: Obj, k: string, p: string): Uint8Array {
  const v = field(o, k, p)
  if (!(v instanceof Uint8Array)) throw new SchemaError(`${at(p, k)}: expected bytes`)
  return Uint8Array.from(v)
}

function header(doc: Obj, expected: string) {
  const schema = str(doc, "schema", "")
  if (schema !== expected) throw new SchemaError(`schema: expected '${expected}', got '${schema}'`)
  const version = int(field(doc, "version", ""), "version")
  if (version > SCHEMA_VERSION) {
    throw new SchemaError(`version: doc is version ${version}, newer than supported ${SCHEMA_VERSION}; update the app`)
  }
  if (version < 1) throw new SchemaError(`version: invalid ${version}`)
}

export function readLibrary(doc: unknown): Library {
  const d = doc as Obj
  header(d, LIBRARY_SCHEMA)
  const folders = map(d, "folders", "")
  const notebooks = map(d, "notebooks", "")
  return {
    folders: Object.fromEntries(Object.keys(folders).map(id => {
      const p = `folders.${id}`
      const o = map(folders, id, "folders")
      return [id, { id, title: str(o, "title", p), parentId: strOrNull(o, "parentId", p), createdAt: date(o, "createdAt", p), updatedAt: date(o, "updatedAt", p) }]
    })),
    notebooks: Object.fromEntries(Object.keys(notebooks).map(id => {
      const p = `notebooks.${id}`
      const o = map(notebooks, id, "notebooks")
      const kind = str(o, "kind", p)
      if (kind !== "notebook" && kind !== "quickPage") throw new SchemaError(`${p}.kind: unknown kind '${kind}'`)
      return [id, {
        id,
        docUrl: str(o, "docUrl", p),
        title: str(o, "title", p),
        folderId: strOrNull(o, "folderId", p),
        kind: kind as EntryKind,
        source: readSource(map(o, "source", p), `${p}.source`),
        createdAt: date(o, "createdAt", p),
        updatedAt: date(o, "updatedAt", p),
      }]
    })),
  }
}

function readSource(o: Obj, p: string): Source {
  const kind = str(o, "kind", p)
  if (kind === "none") return { kind }
  if (kind === "pdf") return { kind, blob: str(o, "blob", p) }
  if (kind === "epub") {
    const l = map(o, "layout", p)
    const lp = `${p}.layout`
    return { kind, blob: str(o, "blob", p), layout: { w: num(field(l, "w", lp), `${lp}.w`), h: num(field(l, "h", lp), `${lp}.h`), em: num(field(l, "em", lp), `${lp}.em`) } }
  }
  throw new SchemaError(`${p}.kind: unknown source kind '${kind}'`)
}

function readBackground(o: Obj, p: string): Background {
  return { type: str(o, "type", p), native: strOrNull(o, "native", p), blob: strOrNull(o, "blob", p) }
}

function readPage(o: Obj, id: string): Page {
  const p = `pages.${id}`
  const strokes = map(o, "strokes", p)
  const images = map(o, "images", p)
  return {
    id,
    background: readBackground(map(o, "background", p), `${p}.background`),
    strokes: Object.fromEntries(Object.keys(strokes).map(sid => {
      const sp = `${p}.strokes.${sid}`
      const s = map(strokes, sid, `${p}.strokes`)
      const bbox = list(s, "bbox", sp).map((v, i) => num(v, `${sp}.bbox[${i}]`))
      if (bbox.length !== 4) throw new SchemaError(`${sp}.bbox: expected 4 numbers, got ${bbox.length}`)
      return [sid, {
        id: sid,
        pen: str(s, "pen", sp),
        color: int(field(s, "color", sp), `${sp}.color`),
        size: num(field(s, "size", sp), `${sp}.size`),
        bbox: { left: bbox[0], top: bbox[1], right: bbox[2], bottom: bbox[3] },
        points: bytes(s, "points", sp),
        createdAt: date(s, "createdAt", sp),
        updatedAt: date(s, "updatedAt", sp),
      }]
    })),
    images: Object.fromEntries(Object.keys(images).map(iid => {
      const ip = `${p}.images.${iid}`
      const i = map(images, iid, `${p}.images`)
      const r = list(i, "rect", ip).map((v, k) => int(v, `${ip}.rect[${k}]`))
      if (r.length !== 4) throw new SchemaError(`${ip}.rect: expected 4 integers, got ${r.length}`)
      return [iid, {
        id: iid,
        blob: str(i, "blob", ip),
        rect: { x: r[0], y: r[1], width: r[2], height: r[3] },
        createdAt: date(i, "createdAt", ip),
        updatedAt: date(i, "updatedAt", ip),
      }]
    })),
    createdAt: date(o, "createdAt", p),
    updatedAt: date(o, "updatedAt", p),
  }
}

export function readNotebook(doc: unknown): Notebook {
  const d = doc as Obj
  header(d, NOTEBOOK_SCHEMA)
  const pagesObj = map(d, "pages", "")
  const pages = Object.fromEntries(Object.keys(pagesObj).map(id => [id, readPage(map(pagesObj, id, "pages"), id)]))
  const rawOrder = list(d, "pageOrder", "").map((v, i) => scalar(v, `pageOrder[${i}]`))
  return {
    notebookId: str(d, "notebookId", ""),
    openPageId: strOrNull(d, "openPageId", ""),
    defaultBackground: readBackground(map(d, "defaultBackground", ""), "defaultBackground"),
    pageOrder: normalizeOrder(rawOrder, pages),
    pages,
  }
}

/**
 * Concurrent "delete page" and "move page" merge into a pageOrder that may name deleted pages,
 * repeat a page, or miss one. Keep the first occurrence of each existing page and append the
 * missing ones, oldest first. Must match AutomergeSchema.normalizeOrder in Kotlin.
 */
function normalizeOrder(raw: string[], pages: Record<string, Page>): string[] {
  const ordered = new Set(raw.filter(id => id in pages))
  Object.values(pages)
    .filter(pg => !ordered.has(pg.id))
    .sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime() || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
    .forEach(pg => ordered.add(pg.id))
  return [...ordered]
}
