/* How a book's metadata reads in the library and the reader. */
import { searchLibrary, type Library, type NotebookEntry } from "@notable/schema"

/** "Ursula K. Le Guin · Hainish Cycle 4"; null when the entry has neither. */
export function bylineOf(e: NotebookEntry): string | null {
  const m = e.meta
  if (!m) return null
  const authors = m.authors.length > 2 ? `${m.authors[0]} and others` : m.authors.join(" and ")
  const series = m.series ? (m.seriesIndex === null ? m.series : `${m.series} ${m.seriesIndex}`) : ""
  return [authors, series].filter(Boolean).join(" · ") || null
}

/** Opened and not finished, most recently read first. */
export const continueReading = (lib: Library, max = 6): NotebookEntry[] => searchLibrary(lib, "is:reading").slice(0, max)
