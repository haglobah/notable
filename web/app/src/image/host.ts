/*
 * Host API v1 data in the PWA: `notable.library()` and `notable.page()`. The JSON must match
 * Android's (ImageService.libraryView), byte for byte: both are checked against
 * web/schema/fixtures/library-view-v1.json.
 */
import type { Library } from "@notable/schema"

const byId = <T extends { id: string }>(xs: T[]) => [...xs].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))

export function libraryView(lib: Library | null): string {
  return JSON.stringify({
    folders: Object.fromEntries(byId(Object.values(lib?.folders ?? {})).map(f => [f.id, { id: f.id, title: f.title, parentId: f.parentId }])),
    notebooks: Object.fromEntries(byId(Object.values(lib?.notebooks ?? {})).map(n => [n.id, {
      id: n.id, title: n.title, folderId: n.folderId, kind: n.kind, source: n.source.kind, updatedAt: n.updatedAt.toISOString(),
    }])),
  })
}

/** The page open in the reader. [index] is 0-based. */
export type OpenPage = { id: string; notebookId: string | null; index: number; pageCount: number }

export const pageView = (p: OpenPage | null) =>
  p === null ? "null" : JSON.stringify({ id: p.id, notebookId: p.notebookId, index: p.index, pageCount: p.pageCount })
