/*
 * Book metadata read at import (docs/plans/ebook-features.md, "Metadata extraction"): the OPF of
 * an EPUB, the info dictionary of a PDF. The Kotlin twin is io/BookMeta.kt; both are tested
 * against web/schema/fixtures/book-meta/.
 */
import type { BookMeta } from "@notable/schema"

export type Extracted = { title: string | null; meta: BookMeta | null }

const MAX_DESCRIPTION = 4000

/** Trimmed, whitespace runs (Unicode White_Space, so also U+00A0) collapsed; empty → null. */
const clean = (v: string | null | undefined): string | null => {
  const s = (v ?? "").replace(/\s+/gu, " ").trim()
  return s ? s : null
}

const cleanList = (vs: (string | null | undefined)[], dedupe = false): string[] => {
  const seen = new Set<string>()
  const out: string[] = []
  for (const v of vs) {
    const c = clean(v)
    if (!c) continue
    const key = c.toLowerCase()
    if (dedupe && seen.has(key)) continue
    seen.add(key)
    out.push(c)
  }
  return out
}

const NAMED: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " }

/** `&amp; &lt; &gt; &quot; &apos; &nbsp;` and numeric references; others are left as written. */
export function decodeEntities(s: string): string {
  return s.replace(/&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);/g, (all, ref: string) => {
    if (ref[0] === "#") {
      const code = ref[1] === "x" || ref[1] === "X" ? parseInt(ref.slice(2), 16) : parseInt(ref.slice(1), 10)
      return Number.isFinite(code) && code <= 0x10ffff ? String.fromCodePoint(code) : all
    }
    return NAMED[ref] ?? all
  })
}

/** Text that may hold HTML: tags become spaces, entities are decoded, whitespace collapsed. */
export function cleanDescription(text: string | null): string | null {
  if (text === null) return null
  const c = clean(decodeEntities(text.replace(/<[^>]*>/g, " ")))
  return c === null ? null : c.slice(0, MAX_DESCRIPTION)
}

const all = (...xs: (BookMeta[keyof BookMeta])[]) => xs.every(x => x === null || (Array.isArray(x) && x.length === 0))
const metaOrNull = (m: BookMeta): BookMeta | null =>
  all(m.authors, m.series, m.seriesIndex, m.description, m.publisher, m.language, m.isbn, m.published, m.subjects) ? null : m

/** 10 or 13 characters of digits (the last may be X) once prefixes, dashes and spaces are gone. */
function isbnOf(text: string, byScheme: boolean): string | null {
  const s = text.trim().replace(/^(urn:isbn:|isbn:?)/i, "").replace(/[-\s]/g, "").toUpperCase()
  if (/^(\d{9}[\dX]|\d{12}[\dX])$/.test(s)) return s
  return byScheme && s ? s : null
}

/* ------------------------------------ PDF ------------------------------------ */

/** [get] is MuPDF's Document.getMetaData. */
export function pdfMeta(get: (key: string) => string | undefined): Extracted {
  const info = (k: string) => clean(get(`info:${k}`))
  const author = info("Author")
  const keywords = info("Keywords")
  return {
    title: info("Title"),
    meta: metaOrNull({
      authors: cleanList(author ? author.split(/;|&| and /) : []),
      series: null,
      seriesIndex: null,
      description: cleanDescription(info("Subject")),
      publisher: null,
      language: null,
      isbn: null,
      published: null,
      subjects: cleanList(keywords ? keywords.split(/[,;]/) : [], true),
    }),
  }
}

/* ------------------------------------ EPUB ------------------------------------ */

type El = { name: string; attrs: Record<string, string>; children: (El | string)[] }

/** Local name: "dc:title" → "title". */
const local = (n: string) => n.slice(n.indexOf(":") + 1)

/**
 * A tolerant XML reader for OPF and container files: elements, attributes, text, CDATA. Names are
 * kept with their prefix; comments, processing instructions and doctypes are skipped.
 */
export function parseXml(xml: string): El {
  const root: El = { name: "#root", attrs: {}, children: [] }
  const stack = [root]
  let i = 0
  const text = (t: string) => { if (t) stack[stack.length - 1].children.push(decodeEntities(t)) }
  while (i < xml.length) {
    const lt = xml.indexOf("<", i)
    if (lt < 0) { text(xml.slice(i)); break }
    text(xml.slice(i, lt))
    if (xml.startsWith("<!--", lt)) { i = end(xml, "-->", lt); continue }
    if (xml.startsWith("<![CDATA[", lt)) {
      const close = xml.indexOf("]]>", lt)
      stack[stack.length - 1].children.push(xml.slice(lt + 9, close < 0 ? xml.length : close))
      i = close < 0 ? xml.length : close + 3
      continue
    }
    if (xml.startsWith("<?", lt)) { i = end(xml, "?>", lt); continue }
    if (xml.startsWith("<!", lt)) { i = end(xml, ">", lt); continue }
    const gt = tagEnd(xml, lt)
    const body = xml.slice(lt + 1, gt)
    i = gt + 1
    if (body.startsWith("/")) {
      const name = body.slice(1).trim()
      // Close up to the matching element; a stray end tag is ignored.
      const at = stack.map(e => e.name).lastIndexOf(name)
      if (at > 0) stack.length = at
      continue
    }
    const selfClosing = body.endsWith("/")
    const m = /^([^\s/>]+)([\s\S]*?)\/?$/.exec(body)
    if (!m) continue
    const el: El = { name: m[1], attrs: {}, children: [] }
    for (const a of m[2].matchAll(/([^\s=]+)\s*=\s*("([^"]*)"|'([^']*)')/g)) el.attrs[a[1]] = decodeEntities(a[3] ?? a[4] ?? "")
    stack[stack.length - 1].children.push(el)
    if (!selfClosing) stack.push(el)
  }
  return root
}

const end = (xml: string, marker: string, from: number) => {
  const at = xml.indexOf(marker, from)
  return at < 0 ? xml.length : at + marker.length
}

/** The `>` closing the tag at [lt], skipping quoted attribute values. */
function tagEnd(xml: string, lt: number): number {
  let q: string | null = null
  for (let i = lt + 1; i < xml.length; i++) {
    const c = xml[i]
    if (q) { if (c === q) q = null } else if (c === '"' || c === "'") q = c
    else if (c === ">") return i
  }
  return xml.length
}

const elements = (e: El): El[] => e.children.filter((c): c is El => typeof c !== "string")
const descendants = (e: El): El[] => elements(e).flatMap(c => [c, ...descendants(c)])
const textOf = (e: El): string => e.children.map(c => (typeof c === "string" ? c : textOf(c))).join("")
/** An attribute by local name: "opf:scheme" and "scheme" are both "scheme". */
const attr = (e: El, name: string) => Object.entries(e.attrs).find(([k]) => local(k) === name)?.[1]

const numberOrNull = (v: string | null | undefined) => {
  const c = clean(v)
  if (c === null) return null
  const n = Number(c)
  return Number.isFinite(n) ? n : null
}

/** The title and metadata of the OPF document [opf]. */
export function opfMeta(opf: string): Extracted {
  const metadata = descendants(parseXml(opf)).find(e => local(e.name) === "metadata")
  if (!metadata) return { title: null, meta: null }
  const items = descendants(metadata)
  // Dublin Core elements by local name, whatever prefix the file binds to them.
  const dc = (name: string) => items.filter(e => local(e.name) === name)
  const first = (name: string) => clean(dc(name).map(textOf).find(t => clean(t) !== null))
  const metas = items.filter(e => local(e.name) === "meta")
  const named = (name: string) => metas.find(e => attr(e, "name") === name)?.attrs.content

  let series = clean(named("calibre:series"))
  let seriesIndex = numberOrNull(named("calibre:series_index"))
  if (series === null) {
    const collection = metas.find(e => attr(e, "property") === "belongs-to-collection" && clean(textOf(e)) !== null)
    if (collection) {
      series = clean(textOf(collection))
      const id = collection.attrs.id
      const position = id ? metas.find(e => e.attrs.refines === `#${id}` && attr(e, "property") === "group-position") : undefined
      if (position && seriesIndex === null) seriesIndex = numberOrNull(textOf(position))
    }
  }

  let isbn: string | null = null
  for (const e of dc("identifier")) {
    isbn = isbnOf(textOf(e), (attr(e, "scheme") ?? "").toUpperCase() === "ISBN")
    if (isbn) break
  }

  const description = dc("description").map(textOf).find(t => clean(t) !== null) ?? null
  return {
    title: first("title"),
    meta: metaOrNull({
      authors: cleanList(dc("creator").map(textOf)),
      series,
      seriesIndex,
      description: cleanDescription(description),
      publisher: first("publisher"),
      language: first("language"),
      isbn,
      published: first("date"),
      subjects: cleanList(dc("subject").map(textOf), true),
    }),
  }
}

/** The title and metadata of the EPUB [bytes]. @throws when it isn't a zip or has no OPF */
export async function epubMeta(bytes: Uint8Array): Promise<Extracted> {
  const zip = readZip(bytes)
  const container = await zip.text("META-INF/container.xml")
  const rootfile = descendants(parseXml(container)).find(e => local(e.name) === "rootfile")?.attrs["full-path"]
  if (!rootfile) throw new Error("META-INF/container.xml names no rootfile")
  return opfMeta(await zip.text(decodeURIComponent(rootfile)))
}

/* ------------------------------------ zip ------------------------------------ */

type Entry = { method: number; size: number; offset: number }

/** Reads the central directory; files are inflated on request. ZIP64 is not supported. */
function readZip(bytes: Uint8Array) {
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let eocd = -1
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 0xffff); i--) {
    if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break }
  }
  if (eocd < 0) throw new Error("not a zip file")
  const count = dv.getUint16(eocd + 10, true)
  let at = dv.getUint32(eocd + 16, true)
  const entries = new Map<string, Entry>()
  const utf8 = new TextDecoder()
  for (let n = 0; n < count; n++) {
    if (at + 46 > bytes.length || dv.getUint32(at, true) !== 0x02014b50) throw new Error("damaged zip directory")
    const nameLen = dv.getUint16(at + 28, true), extraLen = dv.getUint16(at + 30, true), commentLen = dv.getUint16(at + 32, true)
    const name = utf8.decode(bytes.subarray(at + 46, at + 46 + nameLen))
    entries.set(name, { method: dv.getUint16(at + 10, true), size: dv.getUint32(at + 20, true), offset: dv.getUint32(at + 42, true) })
    at += 46 + nameLen + extraLen + commentLen
  }
  return {
    async text(name: string): Promise<string> {
      const e = entries.get(name)
      if (!e) throw new Error(`${name} is missing`)
      const start = e.offset + 30 + dv.getUint16(e.offset + 26, true) + dv.getUint16(e.offset + 28, true)
      const data = bytes.subarray(start, start + e.size)
      if (e.method === 0) return utf8.decode(data)
      if (e.method !== 8) throw new Error(`${name} uses zip method ${e.method}`)
      const out = new Blob([data as Uint8Array<ArrayBuffer>]).stream().pipeThrough(new DecompressionStream("deflate-raw"))
      return utf8.decode(await new Response(out).arrayBuffer())
    },
  }
}
