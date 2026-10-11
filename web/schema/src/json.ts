/*
 * The expected-content JSON stored next to each golden fixture: dates as ISO-8601, bytes as
 * base64, everything else as in the model. Kotlin reads the same files (FixtureJson.kt).
 */
import type { Library, Notebook, Page, Source } from "./model.ts"

type J = any

const d = (v: string) => new Date(v)
const b64 = (v: string) => Uint8Array.from(Buffer.from(v, "base64"))

export function libraryFromJson(j: J): Library {
  return {
    folders: Object.fromEntries(Object.entries<J>(j.folders).map(([id, f]) =>
      [id, { id, title: f.title, parentId: f.parentId, createdAt: d(f.createdAt), updatedAt: d(f.updatedAt) }])),
    notebooks: Object.fromEntries(Object.entries<J>(j.notebooks).map(([id, n]) =>
      [id, { id, docUrl: n.docUrl, title: n.title, folderId: n.folderId, kind: n.kind, source: n.source as Source, createdAt: d(n.createdAt), updatedAt: d(n.updatedAt) }])),
  }
}

export function notebookFromJson(j: J): Notebook {
  return {
    notebookId: j.notebookId,
    ...(j.canvasWidth === undefined ? {} : { canvasWidth: j.canvasWidth }),
    openPageId: j.openPageId,
    defaultBackground: j.defaultBackground,
    pageOrder: j.pageOrder,
    pages: Object.fromEntries(Object.entries<J>(j.pages).map(([id, p]): [string, Page] => [id, {
      id,
      background: p.background,
      strokes: Object.fromEntries(Object.entries<J>(p.strokes).map(([sid, s]) => [sid, {
        id: sid, pen: s.pen, color: s.color, size: s.size,
        bbox: { left: s.bbox[0], top: s.bbox[1], right: s.bbox[2], bottom: s.bbox[3] },
        points: b64(s.points), createdAt: d(s.createdAt), updatedAt: d(s.updatedAt),
      }])),
      images: Object.fromEntries(Object.entries<J>(p.images).map(([iid, i]) => [iid, {
        id: iid, blob: i.blob, rect: { x: i.rect[0], y: i.rect[1], width: i.rect[2], height: i.rect[3] },
        createdAt: d(i.createdAt), updatedAt: d(i.updatedAt),
      }])),
      createdAt: d(p.createdAt),
      updatedAt: d(p.updatedAt),
    }])),
  }
}

export function libraryToJson(l: Library): J {
  return {
    folders: Object.fromEntries(Object.values(l.folders).map(f =>
      [f.id, { title: f.title, parentId: f.parentId, createdAt: f.createdAt.toISOString(), updatedAt: f.updatedAt.toISOString() }])),
    notebooks: Object.fromEntries(Object.values(l.notebooks).map(n =>
      [n.id, { docUrl: n.docUrl, title: n.title, folderId: n.folderId, kind: n.kind, source: n.source, createdAt: n.createdAt.toISOString(), updatedAt: n.updatedAt.toISOString() }])),
  }
}

export function notebookToJson(n: Notebook): J {
  return {
    notebookId: n.notebookId,
    ...(n.canvasWidth === undefined ? {} : { canvasWidth: n.canvasWidth }),
    openPageId: n.openPageId,
    defaultBackground: n.defaultBackground,
    pageOrder: n.pageOrder,
    pages: Object.fromEntries(Object.values(n.pages).map(p => [p.id, {
      background: p.background,
      strokes: Object.fromEntries(Object.values(p.strokes).map(s => [s.id, {
        pen: s.pen, color: s.color, size: s.size, bbox: [s.bbox.left, s.bbox.top, s.bbox.right, s.bbox.bottom],
        points: Buffer.from(s.points).toString("base64"), createdAt: s.createdAt.toISOString(), updatedAt: s.updatedAt.toISOString(),
      }])),
      images: Object.fromEntries(Object.values(p.images).map(i => [i.id, {
        blob: i.blob, rect: [i.rect.x, i.rect.y, i.rect.width, i.rect.height],
        createdAt: i.createdAt.toISOString(), updatedAt: i.updatedAt.toISOString(),
      }])),
      createdAt: p.createdAt.toISOString(),
      updatedAt: p.updatedAt.toISOString(),
    }])),
  }
}
