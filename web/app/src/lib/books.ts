/* How a book's metadata reads in the library and the reader. */
import type { Library, NotebookEntry } from "@notable/schema"

/** "Ursula K. Le Guin · Hainish Cycle 4"; null when the entry has neither. */
export function bylineOf(e: NotebookEntry): string | null {
  const m = e.meta
  if (!m) return null
  const authors = m.authors.length > 2 ? `${m.authors[0]} and others` : m.authors.join(" and ")
  const series = m.series ? (m.seriesIndex === null ? m.series : `${m.series} ${m.seriesIndex}`) : ""
  return [authors, series].filter(Boolean).join(" · ") || null
}

/** The quick-pages notebook first, the rest in the order given (docs/plans/ebook-features.md, round 5). */
export function quickFirst(lib: Library, entries: NotebookEntry[]): NotebookEntry[] {
  const at = entries.findIndex(e => e.id === lib.quickNotebookId)
  return at <= 0 ? entries : [entries[at], ...entries.slice(0, at), ...entries.slice(at + 1)]
}
