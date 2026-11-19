/*
 * The table of contents and in-book search of an open MuPDF document (docs/plans/ebook-features.md,
 * "Table of contents and in-book search"). Runs in the worker; pages are document pages. The
 * Kotlin twin is io/BookText.kt.
 */
import type * as Mupdf from "mupdf"

type Doc = InstanceType<typeof Mupdf.Document>
type Outline = NonNullable<ReturnType<Doc["loadOutline"]>>

export type OutlineEntry = { title: string; page: number; depth: number }
/** [quads] are in document points: [x0,y0, x1,y1, x2,y2, x3,y3] each (ul, ur, ll, lr). */
export type SearchHit = { page: number; quads: number[][]; snippet: string }

const SNIPPET_SIDE = 40

/** Depth-first; items without a page are dropped and their children moved up a level. */
export function outlineOf(doc: Doc): OutlineEntry[] {
  const out: OutlineEntry[] = []
  const walk = (items: Outline, depth: number) => {
    for (const it of items) {
      let page = it.page ?? -1
      if (page < 0 && it.uri) {
        try { page = doc.resolveLink(it.uri) } catch { page = -1 }
      }
      const title = (it.title ?? "").replace(/\s+/gu, " ").trim()
      const kept = page >= 0 && title !== ""
      if (kept) out.push({ title, page, depth })
      if (it.down) walk(it.down, kept ? depth + 1 : depth)
    }
  }
  walk(doc.loadOutline() ?? [], 0)
  return out
}

/** Up to 40 characters each side of the first case-insensitive occurrence, with … where cut. */
export function snippet(text: string, query: string): string {
  const t = text.replace(/\s+/gu, " ").trim()
  const at = t.toLowerCase().indexOf(query.toLowerCase())
  if (at < 0) return t.slice(0, SNIPPET_SIDE * 2) + (t.length > SNIPPET_SIDE * 2 ? "…" : "")
  const from = Math.max(0, at - SNIPPET_SIDE), to = Math.min(t.length, at + query.length + SNIPPET_SIDE)
  return (from > 0 ? "…" : "") + t.slice(from, to) + (to < t.length ? "…" : "")
}

/** Every page with a hit, in page order; [onPage] reports progress. */
export function searchDoc(doc: Doc, query: string, onPage?: (done: number, total: number) => void): SearchHit[] {
  const q = query.trim()
  if (!q) return []
  const hits: SearchHit[] = []
  const total = doc.countPages()
  for (let i = 0; i < total; i++) {
    const page = doc.loadPage(i)
    try {
      const st = page.toStructuredText("preserve-whitespace")
      try {
        const found = st.search(q, "ignore-case,ignore-diacritics")
        if (found.length) hits.push({ page: i, quads: found.flat().map(qd => [...qd]), snippet: snippet(st.asText(), q) })
      } finally {
        st.destroy()
      }
    } finally {
      page.destroy()
    }
    onPage?.(i + 1, total)
  }
  return hits
}
