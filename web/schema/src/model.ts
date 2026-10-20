/*
 * Plain model of the shared Automerge documents (ADR-001). The Kotlin twin is
 * app/src/main/java/com/ethran/notable/sync/automerge/AmModel.kt. Map keys are ids, so every
 * `id` here comes from the key it is stored under.
 */

export const LIBRARY_SCHEMA = "notable/library"
export const NOTEBOOK_SCHEMA = "notable/notebook"
/** The live code image (ADR-002); versioned on its own, see CODE_VERSION. */
export const CODE_SCHEMA = "notable/code"
/** The newest version readers understand. */
export const SCHEMA_VERSION = 2
/** New docs: they hold nothing a version-1 reader would misread. */
export const BASE_VERSION = 1
/** A notebook doc holding a page marked `deleted` (version-1 readers would show it). */
export const DELETED_PAGES_VERSION = 2

/** Note Air 5C portrait width in px, for notebooks without `canvasWidth`. */
export const DEFAULT_CANVAS_WIDTH = 1860

export type Library = {
  folders: Record<string, Folder>
  notebooks: Record<string, NotebookEntry>
  /**
   * `automerge:` URL of the library's code doc (ADR-002). Optional: it is set when the first
   * definition is written, and libraries written before it existed lack it.
   */
  codeDocUrl?: string
}

export type Folder = { id: string; title: string; parentId: string | null; createdAt: Date; updatedAt: Date }

/** "quickPage": a single page living directly in a folder (Room: page.notebookId == null). */
export type EntryKind = "notebook" | "quickPage"

export type NotebookEntry = {
  id: string
  /** `automerge:` URL of the notebook doc. */
  docUrl: string
  title: string
  folderId: string | null
  kind: EntryKind
  source: Source
  createdAt: Date
  updatedAt: Date
}

/** Blobs are sha256 hex digests in the server's blob store. */
export type Source =
  | { kind: "none" }
  | { kind: "pdf"; blob: string }
  /** EPUB pages exist only for one fixed layout; changing it would move every annotation. */
  | { kind: "epub"; blob: string; layout: EpubLayout }

export type EpubLayout = { w: number; h: number; em: number }

export type Notebook = {
  notebookId: string
  /**
   * Width of the page in stroke coordinates: the Android canvas width (the device's shorter screen
   * side, 1860 px on the Note Air 5C) that a PDF page is scaled to. Optional because docs written
   * before it existed lack it; readers then assume DEFAULT_CANVAS_WIDTH.
   */
  canvasWidth?: number
  openPageId: string | null
  defaultBackground: Background
  /** Normalized on read: no dangling ids, no duplicates, every page present. */
  pageOrder: string[]
  pages: Record<string, Page>
}

/**
 * `type` is a BackgroundType key ("native", "image", "imagerepeating", "coverImage", "autoPdf",
 * "pdfN"). `native` holds the template name for "native"; `blob` the image or PDF otherwise.
 */
export type Background = { type: string; native: string | null; blob: string | null }

export type Page = {
  id: string
  background: Background
  strokes: Record<string, Stroke>
  images: Record<string, Image>
  createdAt: Date
  updatedAt: Date
}

/** `points` is Notable's SB2 encoding with pressure normalized to [0, 1]. */
export type Stroke = {
  id: string
  pen: string
  /** ARGB as a signed 32-bit integer, as on Android. */
  color: number
  size: number
  bbox: { left: number; top: number; right: number; bottom: number }
  points: Uint8Array
  createdAt: Date
  updatedAt: Date
}

export type Image = {
  id: string
  blob: string
  rect: { x: number; y: number; width: number; height: number }
  createdAt: Date
  updatedAt: Date
}

/* ------------------------------ code doc (ADR-002) ------------------------------ */

/** The newest code doc version readers understand. */
export const CODE_VERSION = 1

/** The code doc: one whole, immutable entry per definition name. */
export type CodeImage = { defs: Record<string, CodeDef> }

/**
 * One definition. `source` is one JavaScript expression (usually a function) and a scalar string,
 * never collaborative Text: two merged character edits of one function would not parse.
 * Redefining replaces the whole entry, so the last writer wins per name; undefining deletes it.
 */
export type CodeDef = {
  /** Namespaced, e.g. "command/clear-page" (see isDefName). */
  name: string
  source: string
  /** The host API version the source was written for; newer than the kernel's → failed. */
  hostApi: number
  updatedAt: Date
  /** Who wrote it, e.g. "android:Note Air 5C" or "pwa"; shown in `ls` and history. */
  device: string
}

/** `<kind>/<rest>`: a lowercase kind, then letters, digits and `._-/`. */
export const isDefName = (name: string) => /^[a-z][a-z0-9-]*\/[A-Za-z0-9._-][A-Za-z0-9._/-]*$/.test(name)
