/*
 * Reading a book (docs/plans/ebook-features.md): which notebook page shows a document page, how
 * far into the notebook the open page is. Pure, shared by the reader, the covers and the tests.
 */
import type { Notebook, NotebookEntry } from "@notable/schema"
import { docKeyId, docKeyOf, resolveBackground, type DocKey } from "@notable/reader"

/** The document the entry was made from, as the worker opens it; null for plain notebooks. */
export function sourceKey(entry: NotebookEntry): DocKey | null {
  const s = entry.source
  return s.kind === "pdf" ? { format: "pdf", blob: s.blob } : s.kind === "epub" ? { format: "epub", blob: s.blob, layout: s.layout } : null
}

/** Document page index → the first notebook page (in pageOrder) that shows it. */
export function pagesOfDocument(nb: Notebook, entry: NotebookEntry, key: DocKey): Map<number, string> {
  const id = docKeyId(key)
  const out = new Map<number, string>()
  for (const pageId of nb.pageOrder) {
    const bg = resolveBackground(nb, entry, pageId)
    const k = docKeyOf(bg)
    if (bg.kind === "document" && k && docKeyId(k) === id && !out.has(bg.index)) out.set(bg.index, pageId)
  }
  return out
}

/**
 * Items pointing at document pages, mapped to notebook pages. Those whose page the notebook no
 * longer has (deleted on the Boox) are counted in [missing], not dropped silently.
 */
export function mapToPages<T extends { page: number }>(items: T[], pages: Map<number, string>): { mapped: (T & { pageId: string })[]; missing: number } {
  const mapped: (T & { pageId: string })[] = []
  let missing = 0
  for (const it of items) {
    const pageId = pages.get(it.page)
    if (pageId) mapped.push({ ...it, pageId })
    else missing++
  }
  return { mapped, missing }
}

export type Progress = { index: number; count: number; fraction: number }

/** Where the open page is; null when the notebook has no pages or no open page in its order. */
export function progressOf(nb: Notebook): Progress | null {
  const count = nb.pageOrder.length
  const index = nb.openPageId ? nb.pageOrder.indexOf(nb.openPageId) : -1
  if (count === 0 || index < 0) return null
  return { index, count, fraction: count === 1 ? 1 : index / (count - 1) }
}

/** "Page 12 of 300", plus the percentage once there are enough pages for it to mean something. */
export function progressText(p: Progress): string {
  const page = `Page ${p.index + 1} of ${p.count}`
  return p.count >= 10 ? `${page}, ${Math.round(p.fraction * 100)}%` : page
}

/** The part of a snippet to emphasize: the first case-insensitive occurrence of [query]. */
export function splitSnippet(snippet: string, query: string): [before: string, match: string, after: string] {
  const q = query.trim()
  const at = q ? snippet.toLowerCase().indexOf(q.toLowerCase()) : -1
  if (at < 0) return [snippet, "", ""]
  return [snippet.slice(0, at), snippet.slice(at, at + q.length), snippet.slice(at + q.length)]
}
