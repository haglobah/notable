/*
 * Library search, filters and sort (docs/plans/ebook-features.md, "Library search" and "Filters
 * and sort"). The Kotlin twin is LibrarySearch.kt; both run fixtures/search-v1.json, so a query
 * finds the same entries in the same order on every device.
 */
import type { Folder, Library, NotebookEntry } from "./model.ts"

type Field = "title" | "authors" | "series" | "subjects" | "folder" | "publisher" | "isbn" | "language" | "description" | "published"
type DateField = "added" | "edited" | "read" | "finished"
type NumberField = "rating" | "pages" | "progress"
type Exact = "kind" | "is" | "has"
export type TokenField = Field | DateField | NumberField | Exact

/** Weights for a plain term's best matching field, in tie-break order. `published` isn't scored. */
const WEIGHTS: [Field, number][] = [
  ["title", 8], ["authors", 6], ["series", 5], ["subjects", 3], ["folder", 2], ["publisher", 2], ["isbn", 2], ["language", 1], ["description", 1],
]
const WORD_START_BONUS = 2

/** Query field names; `tag` and `subject` are the same field. A Map, so `constructor:` isn't one. */
const FIELD_NAMES = new Map<string, TokenField>(Object.entries({
  title: "title", author: "authors", series: "series", tag: "subjects", subject: "subjects", folder: "folder",
  publisher: "publisher", isbn: "isbn", lang: "language", desc: "description", published: "published",
  added: "added", edited: "edited", read: "read", finished: "finished",
  rating: "rating", pages: "pages", progress: "progress",
  kind: "kind", is: "is", has: "has",
} as const))

/** `values`: one for a plain term; a field's unquoted commas give several, any of which may match. */
export type SearchToken = { negated: boolean; field: TokenField | null; values: string[] }

/** NFKD, combining marks dropped, lowercase: "Übung" and "ubung" are the same. */
export const normalize = (text: string) => text.normalize("NFKD").replace(/\p{M}/gu, "").toLowerCase()

/** Whitespace-separated tokens; a quoted span is one token, also as a field value. */
export function parseQuery(query: string): SearchToken[] {
  // Each raw token keeps where its unquoted commas are.
  const raw: { text: string; commas: number[] }[] = []
  let cur = "", commas: number[] = [], quoted = false, any = false
  const push = () => { if (cur || any) raw.push({ text: cur, commas }); cur = ""; commas = []; any = false }
  for (const ch of normalize(query)) {
    if (ch === '"') { quoted = !quoted; any = true; continue }
    if (!quoted && /\s/u.test(ch)) { push(); continue }
    if (!quoted && ch === ",") commas.push(cur.length)
    cur += ch
  }
  push()
  return raw.flatMap(({ text, commas }): SearchToken[] => {
    let t = text, offset = 0, negated = false
    if (t.startsWith("-") && t.length > 1) { negated = true; t = t.slice(1); offset = 1 }
    const colon = t.indexOf(":")
    const field = colon > 0 ? FIELD_NAMES.get(t.slice(0, colon)) : undefined
    if (!field) return t ? [{ negated, field: null, values: [t] }] : []
    const start = offset + colon + 1
    const cuts = commas.filter(c => c >= start)
    const values: string[] = []
    let from = start
    for (const c of [...cuts, text.length]) {
      const v = text.slice(from, c)
      if (v) values.push(v)
      from = c + 1
    }
    return values.length ? [{ negated, field, values }] : []
  })
}

type Indexed = {
  entry: NotebookEntry
  fields: Record<Field, string[]>
  dates: Record<DateField, string | null>
  kind: string
  state: "finished" | "reading" | "unread"
  /** On the Read next list (isNext). */
  next: boolean
  has: Set<string>
  /** progress is a percentage, 0 to 100. */
  numbers: Record<NumberField, number | null>
}

/**
 * How far into it, 0 to 1: finished is 1; else the open page's place (no open page yet: 0); null
 * when the page count isn't known.
 */
export function progressOf(e: NotebookEntry): number | null {
  if (e.finishedAt) return 1
  if (e.pageCount === undefined) return null
  if (e.readPage === undefined) return 0
  return e.pageCount <= 1 ? 1 : Math.min(1, e.readPage / (e.pageCount - 1))
}

const iso = (d: Date | null | undefined) => (d ? d.toISOString() : null)

function index(lib: Library, e: NotebookEntry): Indexed {
  const folders: string[] = []
  const seen = new Set<string>()
  for (let id = e.folderId; id && !seen.has(id) && lib.folders[id]; id = lib.folders[id].parentId) {
    seen.add(id)
    folders.push(lib.folders[id].title)
  }
  const m = e.meta
  const one = (v: string | null | undefined) => (v ? [normalize(v)] : [])
  const has = new Set<string>()
  if (m) {
    has.add("meta")
    if (m.authors.length) has.add("author")
    if (m.series) has.add("series")
    if (m.subjects.length) has.add("tag")
    if (m.publisher) has.add("publisher")
    if (m.language) has.add("lang")
    if (m.isbn) has.add("isbn")
    if (m.description) has.add("desc")
    if (m.published) has.add("published")
  }
  if (typeof e.rating === "number") has.add("rating")
  if (e.pageCount !== undefined) has.add("pages")
  const progress = progressOf(e)
  return {
    entry: e,
    fields: {
      title: one(e.title),
      authors: (m?.authors ?? []).map(normalize),
      series: one(m?.series),
      subjects: (m?.subjects ?? []).map(normalize),
      folder: folders.map(normalize),
      publisher: one(m?.publisher),
      isbn: one(m?.isbn),
      language: one(m?.language),
      description: one(m?.description),
      published: one(m?.published),
    },
    dates: { added: iso(e.createdAt), edited: iso(e.updatedAt), read: iso(e.readAt), finished: iso(e.finishedAt) },
    kind: e.kind === "quickPage" ? "quickpage" : e.source.kind === "none" ? "notebook" : e.source.kind,
    state: e.finishedAt ? "finished" : e.readAt ? "reading" : "unread",
    next: isNext(e),
    has,
    numbers: { rating: e.rating ?? null, pages: e.pageCount ?? null, progress: progress === null ? null : progress * 100 },
  }
}

const NUMBER = String.raw`\d+(?:\.\d+)?`
const COMPARE = new RegExp(`^(>=|<=|>|<)?(${NUMBER})$`)
const RANGE = new RegExp(`^(${NUMBER})-(${NUMBER})$`)

/** `8`, `>7`, `>=7`, `<200`, `<=200`, `7-9` (inclusive); anything else matches nothing. */
function compares(n: number | null, v: string): boolean {
  if (n === null) return false
  const r = RANGE.exec(v)
  if (r) return n >= Number(r[1]) && n <= Number(r[2])
  const c = COMPARE.exec(v)
  if (!c) return false
  const x = Number(c[2])
  switch (c[1]) {
    case ">": return n > x
    case ">=": return n >= x
    case "<": return n < x
    case "<=": return n <= x
    default: return n === x
  }
}

const isWordChar = (c: string) => /[\p{L}\p{N}]/u.test(c)

/** A match's weight in one field's values: 0 when absent, + bonus when one starts a word. */
function weigh(values: string[], term: string, weight: number): number {
  let best = 0
  for (const v of values) {
    for (let at = v.indexOf(term); at >= 0; at = v.indexOf(term, at + 1)) {
      const startsWord = at === 0 || !isWordChar(v[at - 1])
      best = Math.max(best, weight + (startsWord ? WORD_START_BONUS : 0))
      if (startsWord) break
    }
  }
  return best
}

/** The plain term's score: the weight of the best field it matches, or 0. */
function termScore(x: Indexed, term: string): number {
  for (const [f, w] of WEIGHTS) {
    const s = weigh(x.fields[f], term, w)
    if (s > 0) return s
  }
  return 0
}

function matchesOne(x: Indexed, field: TokenField | null, v: string): boolean {
  switch (field) {
    case null: return termScore(x, v) > 0
    case "kind": return x.kind === v
    case "is": return x.state === v || (v === "next" && x.next)
    case "has": return x.has.has(v)
    case "rating": case "pages": case "progress": return compares(x.numbers[field], v)
    case "added": case "edited": case "read": case "finished": {
      const d = x.dates[field]
      return d !== null && d.startsWith(v)
    }
    default: return x.fields[field].some(f => f.includes(v))
  }
}

const matches = (x: Indexed, t: SearchToken) => t.values.some(v => matchesOne(x, t.field, v))

export const SORT_KEYS = ["relevance", "title", "author", "series", "published", "added", "edited", "read", "finished", "length", "progress", "rating"] as const
export type SortKey = (typeof SORT_KEYS)[number]
/** Which way each key runs unless told otherwise: names A to Z, dates newest first, shortest first, best and furthest first. */
export const DEFAULT_DESCENDING: Record<SortKey, boolean> = {
  relevance: true, title: false, author: false, series: false, published: true, added: true, edited: true, read: true, finished: true,
  length: false, progress: true, rating: true,
}

export type LibraryQuery = {
  query: string
  sort?: SortKey
  /** Defaults to DEFAULT_DESCENDING[sort]; relevance ignores it. */
  descending?: boolean
  /** Absent: the whole library. null: the root folder. An id: that folder. */
  folderId?: string | null
  /** With folderId: also the entries of every folder below it (cycles stop). */
  subfolders?: boolean
}

/*
 * The trash and Read next (docs/plans/library-features.md).
 */

/** Folder [id] or one above it is in the trash. A missing folder isn't; cycles stop. */
export function isFolderTrashed(lib: Library, id: string | null): boolean {
  const seen = new Set<string>()
  for (let f = id; f && !seen.has(f) && lib.folders[f]; f = lib.folders[f].parentId) {
    seen.add(f)
    if (lib.folders[f].trashedAt) return true
  }
  return false
}

/** Entry [e] is in the trash: trashed itself, or inside a trashed folder. */
export const isTrashed = (lib: Library, e: NotebookEntry) => !!e.trashedAt || isFolderTrashed(lib, e.folderId)

/** Queued, not finished, and not opened since it was queued. (Trash is the caller's: see readNext.) */
export const isNext = (e: NotebookEntry) =>
  !!e.queuedAt && !e.finishedAt && !(e.readAt && e.readAt.getTime() >= e.queuedAt.getTime())

/** The Read next list: oldest queued first, then by id; nothing in the trash. */
export function readNext(lib: Library): NotebookEntry[] {
  return Object.values(lib.notebooks)
    .filter(e => isNext(e) && !isTrashed(lib, e))
    .sort((a, b) => a.queuedAt!.getTime() - b.queuedAt!.getTime() || byId(a, b))
}

export type TrashItem = { kind: "folder"; folder: Folder } | { kind: "notebook"; entry: NotebookEntry }

/** What was trashed directly (not what's inside a trashed folder), most recently trashed first, then by id. */
export function trashOf(lib: Library): TrashItem[] {
  const items: { at: number; id: string; item: TrashItem }[] = [
    ...Object.values(lib.folders).filter(f => f.trashedAt).map(f => ({ at: f.trashedAt!.getTime(), id: f.id, item: { kind: "folder", folder: f } as TrashItem })),
    ...Object.values(lib.notebooks).filter(e => e.trashedAt).map(e => ({ at: e.trashedAt!.getTime(), id: e.id, item: { kind: "notebook", entry: e } as TrashItem })),
  ]
  return items.sort((a, b) => b.at - a.at || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)).map(x => x.item)
}

export const TRASH_DAYS = 30

/** Trash items trashed at least TRASH_DAYS before [now]: due to be deleted for good. */
export const expiredTrash = (lib: Library, now: Date) =>
  trashOf(lib).filter(t => now.getTime() - (t.kind === "folder" ? t.folder.trashedAt! : t.entry.trashedAt!).getTime() >= TRASH_DAYS * 86_400_000)

/** The folder ids [folderId] stands for: it alone, or with every folder below it. */
function scopeOf(lib: Library, folderId: string | null, subfolders: boolean): Set<string | null> {
  const ids = new Set<string | null>([folderId])
  if (!subfolders) return ids
  let grew = true
  while (grew) {
    grew = false
    for (const f of Object.values(lib.folders)) {
      if (!ids.has(f.id) && ids.has(f.parentId)) { ids.add(f.id); grew = true }
    }
  }
  return ids
}

/** "Book 2" before "Book 10": runs of ASCII digits compare by value, the rest by UTF-16 code unit. */
export function naturalCompare(a: string, b: string): number {
  const run = /\d+|\D+/g
  const as = a.match(run) ?? [], bs = b.match(run) ?? []
  for (let i = 0; i < Math.min(as.length, bs.length); i++) {
    const x = as[i], y = bs[i]
    const dx = x.charCodeAt(0) >= 48 && x.charCodeAt(0) <= 57, dy = y.charCodeAt(0) >= 48 && y.charCodeAt(0) <= 57
    if (dx && dy) {
      const tx = x.replace(/^0+/, ""), ty = y.replace(/^0+/, "")
      if (tx.length !== ty.length) return tx.length - ty.length
      if (tx !== ty) return tx < ty ? -1 : 1
      if (x.length !== y.length) return x.length - y.length
    } else if (x !== y) return x < y ? -1 : 1
  }
  return as.length - bs.length
}

const byId = (a: NotebookEntry, b: NotebookEntry) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)
const byTitle = (a: NotebookEntry, b: NotebookEntry) => naturalCompare(normalize(a.title), normalize(b.title)) || byId(a, b)
const time = (d: Date | null | undefined) => (d ? d.getTime() : null)

/** A sort key's value; null sorts last in either direction. */
function keyOf(x: Indexed, sort: Exclude<SortKey, "relevance">): string | number | [string, number] | null {
  const e = x.entry
  switch (sort) {
    case "title": return normalize(e.title)
    case "author": return x.fields.authors[0] ?? null
    case "series": return e.meta?.series ? [normalize(e.meta.series), e.meta.seriesIndex ?? Infinity] : null
    case "published": return x.fields.published[0] ?? null
    case "added": return time(e.createdAt)
    case "edited": return time(e.updatedAt)
    case "read": return time(e.readAt)
    case "finished": return time(e.finishedAt)
    case "length": return e.pageCount ?? null
    case "progress": return progressOf(e)
    case "rating": return typeof e.rating === "number" ? e.rating : null
  }
}

function compareKeys(a: string | number | [string, number], b: typeof a): number {
  if (typeof a === "number" && typeof b === "number") return a === b ? 0 : a < b ? -1 : 1
  if (typeof a === "string" && typeof b === "string") return naturalCompare(a, b)
  const [sa, ia] = a as [string, number], [sb, ib] = b as [string, number]
  return naturalCompare(sa, sb) || (ia === ib ? 0 : ia < ib ? -1 : 1)
}

/**
 * Entries in scope matching every token, in the order asked for. An empty query matches every
 * entry in scope (browsing with a sort).
 */
export function queryLibrary(lib: Library, q: LibraryQuery): NotebookEntry[] {
  const tokens = parseQuery(q.query)
  const sort = q.sort ?? "relevance"
  const scope = q.folderId === undefined ? null : scopeOf(lib, q.folderId, q.subfolders ?? false)
  const rows: { x: Indexed; score: number }[] = []
  for (const e of Object.values(lib.notebooks)) {
    if (scope && !scope.has(e.folderId)) continue
    if (isTrashed(lib, e)) continue
    const x = index(lib, e)
    if (!tokens.every(t => matches(x, t) !== t.negated)) continue
    const score = tokens.reduce((sum, t) => sum + (!t.negated && t.field === null ? termScore(x, t.values[0]) : 0), 0)
    rows.push({ x, score })
  }
  if (sort === "relevance") {
    const t = (d: Date | undefined) => d?.getTime() ?? -Infinity
    return rows.sort((a, b) =>
      b.score - a.score ||
      t(b.x.entry.readAt) - t(a.x.entry.readAt) ||
      b.x.entry.updatedAt.getTime() - a.x.entry.updatedAt.getTime() ||
      byId(a.x.entry, b.x.entry),
    ).map(r => r.x.entry)
  }
  const sign = (q.descending ?? DEFAULT_DESCENDING[sort]) ? -1 : 1
  const keyed = rows.map(r => ({ e: r.x.entry, k: keyOf(r.x, sort) }))
  return keyed.sort((a, b) => {
    if (a.k === null || b.k === null) return a.k === b.k ? byTitle(a.e, b.e) : a.k === null ? 1 : -1
    return sign * compareKeys(a.k, b.k) || byTitle(a.e, b.e)
  }).map(r => r.e)
}

/** Entries matching every token, best first. An empty query finds nothing. */
export function searchLibrary(lib: Library, query: string): NotebookEntry[] {
  return parseQuery(query).length ? queryLibrary(lib, { query }) : []
}

export type Facet = { value: string; count: number }
export type Facets = { authors: Facet[]; series: Facet[]; tags: Facet[]; languages: Facet[]; kinds: Facet[]; states: Facet[] }

/**
 * What the entries can be filtered by, with how many have each value: the menus of a filter bar.
 * Values keep the spelling of their first occurrence; most common first, then by name.
 */
export function facetsOf(entries: NotebookEntry[]): Facets {
  const tally = (pick: (e: NotebookEntry) => (string | null | undefined)[]) => {
    const m = new Map<string, Facet>()
    for (const e of entries) {
      const seen = new Set<string>()
      for (const raw of pick(e)) {
        if (!raw) continue
        const k = normalize(raw)
        if (seen.has(k)) continue
        seen.add(k)
        const f = m.get(k)
        if (f) f.count++
        else m.set(k, { value: raw, count: 1 })
      }
    }
    return [...m.values()].sort((a, b) => b.count - a.count || naturalCompare(normalize(a.value), normalize(b.value)))
  }
  return {
    authors: tally(e => e.meta?.authors ?? []),
    series: tally(e => [e.meta?.series]),
    tags: tally(e => e.meta?.subjects ?? []),
    languages: tally(e => [e.meta?.language]),
    kinds: tally(e => [e.kind === "quickPage" ? "quickpage" : e.source.kind === "none" ? "notebook" : e.source.kind]),
    states: tally(e => [e.finishedAt ? "finished" : e.readAt ? "reading" : "unread"]),
  }
}
