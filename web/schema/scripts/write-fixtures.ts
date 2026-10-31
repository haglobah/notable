/*
 * Writes the TS side of the golden fixtures:
 *   fixtures/library-v1.json, notebook-v1.json        expected content (also read by Kotlin)
 *   fixtures/library-v1.automerge, notebook-v1.automerge  docs written by these builders
 *   fixtures/page-deleted.automerge          notebook-v1 with p-2 deleted
 *   fixtures/page-delete-vs-draw.automerge   p-2 deleted in one copy and drawn on (stroke
 *                                            "drawn-after-delete") in another, merged
 *   fixtures/code-v1.json, code-v1.automerge  the code doc (ADR-002): expected content and the doc
 *   fixtures/library-code-v1.automerge       library-v1 with codeDocUrl (code-v1.json's)
 *   fixtures/library-books-v1.json, .automerge  a library with book fields (meta, readAt,
 *                                            finishedAt); also the library of search-v1.json
 *   fixtures/search-v1.json                  that library and queries with their result ids,
 *                                            worked out by hand from docs/plans/ebook-features.md
 * Stroke points are the Kotlin encoder's bytes from fixtures/sb2-strokes.json.
 *
 * Run: npm run fixtures (from web/schema). Regenerate only for an intentional schema change.
 */
import { readFileSync, writeFileSync } from "node:fs"
import * as A from "@automerge/automerge"
import {
  libraryValue, notebookValue, libraryToJson, notebookToJson, strokeValue, deletePage, markPageLive,
  codeValue, codeToJson,
  type CodeImage, type Library, type Notebook,
} from "../src/index.ts"

const dir = new URL("../fixtures/", import.meta.url)
const sb2: { cases: { name: string; bytes: string }[] } = JSON.parse(readFileSync(new URL("sb2-strokes.json", dir), "utf8"))
const points = (name: string) => Uint8Array.from(Buffer.from(sb2.cases.find(c => c.name === name)!.bytes, "base64"))
const t = (s: string) => new Date(s)

const PDF = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
const EPUB = "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752"
const IMAGE = "fd61a03af4f77d870fc21e05e7e80678095c92d808cfb3b5c279ee04c74aca13"

const library: Library = {
  folders: {
    "f-uni": { id: "f-uni", title: "Uni", parentId: null, createdAt: t("2026-01-02T03:04:05.006Z"), updatedAt: t("2026-02-03T04:05:06.007Z") },
    "f-math": { id: "f-math", title: "Mathe – Übung 🙂", parentId: "f-uni", createdAt: t("2026-01-03T00:00:00.000Z"), updatedAt: t("2026-01-03T00:00:00.001Z") },
  },
  notebooks: {
    "nb-pdf": {
      id: "nb-pdf", docUrl: "automerge:2j9knpCseyhnK8izDmLpGP5WMdZQ", title: "Paper: CRDTs", folderId: "f-math",
      kind: "notebook", source: { kind: "pdf", blob: PDF },
      createdAt: t("2026-03-01T10:00:00.000Z"), updatedAt: t("2026-03-02T11:30:00.250Z"),
    },
    "nb-epub": {
      id: "nb-epub", docUrl: "automerge:3vKwmUKvqZ3Hxw5dJm8sRqzLcfWZ", title: "Baroque Cycle", folderId: null,
      kind: "notebook", source: { kind: "epub", blob: EPUB, layout: { w: 595, h: 842.5, em: 12 } },
      createdAt: t("2026-04-01T00:00:00.000Z"), updatedAt: t("2026-04-01T00:00:00.000Z"),
    },
    "nb-plain": {
      id: "nb-plain", docUrl: "automerge:4XfQq6dNkqY7r2pTmHf4Yk3bBdV1", title: "Journal", folderId: "f-uni",
      kind: "notebook", source: { kind: "none" },
      createdAt: t("2026-05-01T00:00:00.000Z"), updatedAt: t("2026-05-05T00:00:00.000Z"),
    },
    "qp-1": {
      id: "qp-1", docUrl: "automerge:5aBcDeFgHiJkLmNoPqRsTuVwXyZ1", title: "Quick page", folderId: "f-uni",
      kind: "quickPage", source: { kind: "none" },
      createdAt: t("2026-06-01T00:00:00.000Z"), updatedAt: t("2026-06-01T00:00:00.000Z"),
    },
  },
}

const notebook: Notebook = {
  notebookId: "nb-pdf",
  openPageId: "p-2",
  defaultBackground: { type: "autoPdf", native: null, blob: PDF },
  pageOrder: ["p-1", "p-2", "p-3"],
  pages: {
    "p-1": {
      id: "p-1", background: { type: "autoPdf", native: null, blob: PDF },
      strokes: {
        "s-raw": {
          id: "s-raw", pen: "BALLPEN", color: -16777216, size: 3.5,
          bbox: { left: 300, top: 1190, right: 322.75, bottom: 1200.5 },
          points: points("full-mask-raw"),
          createdAt: t("2026-03-01T10:01:00.000Z"), updatedAt: t("2026-03-01T10:01:00.000Z"),
        },
        "s-lz4": {
          id: "s-lz4", pen: "FOUNTAIN", color: -65536, size: 2,
          bbox: { left: 100, top: 2000, right: 249.5, bottom: 2074.75 },
          points: points("long-stroke-lz4"),
          createdAt: t("2026-03-01T10:02:00.000Z"), updatedAt: t("2026-03-01T10:03:00.000Z"),
        },
      },
      images: {},
      createdAt: t("2026-03-01T10:00:00.000Z"), updatedAt: t("2026-03-01T10:03:00.000Z"),
    },
    "p-2": {
      id: "p-2", background: { type: "native", native: "lined", blob: null },
      strokes: {
        "s-neg": {
          id: "s-neg", pen: "MARKER", color: 2130771712, size: 12.25,
          bbox: { left: -5.5, top: -0.25, right: -4, bottom: 0.5 },
          points: points("negative-coords-raw"),
          createdAt: t("2026-03-02T09:00:00.000Z"), updatedAt: t("2026-03-02T09:00:00.000Z"),
        },
      },
      images: {
        "img-1": {
          id: "img-1", blob: IMAGE, rect: { x: 40, y: -20, width: 640, height: 480 },
          createdAt: t("2026-03-02T09:05:00.000Z"), updatedAt: t("2026-03-02T09:06:00.000Z"),
        },
      },
      createdAt: t("2026-03-02T08:00:00.000Z"), updatedAt: t("2026-03-02T09:06:00.000Z"),
    },
    "p-3": {
      id: "p-3", background: { type: "pdf7", native: null, blob: PDF },
      strokes: {}, images: {},
      createdAt: t("2026-03-02T11:00:00.000Z"), updatedAt: t("2026-03-02T11:30:00.250Z"),
    },
  },
}

// Read by both runtimes' tests too: what loads, what fails and why must match on both clients.
const CODE_DOC_URL = "automerge:6cOdEdOcUrLpLaCeHoLdEr123456"
const code: CodeImage = {
  defs: {
    "command/hello": {
      name: "command/hello", source: `() => notable.snack("Hallo – 🙂 from the image")`, hostApi: 1,
      updatedAt: t("2026-10-20T08:00:00.000Z"), device: "android:Note Air 5C",
    },
    "command/count-notebooks": {
      name: "command/count-notebooks",
      source: [
        "({",
        "  title: \"Count notebooks\",",
        "  run: () => {",
        "    const n = Object.keys(notable.library().notebooks).length",
        "    notable.snack(n + \" notebooks\")",
        "    return n",
        "  },",
        "})",
      ].join("\n"),
      hostApi: 1, updatedAt: t("2026-10-20T08:01:00.000Z"), device: "pwa",
    },
    "command/broken": {
      name: "command/broken",
      source: ["(() => {", "  const ok = 1", "  throw new Error(\"broken on purpose\")", "})()"].join("\n"),
      hostApi: 1, updatedAt: t("2026-10-20T08:02:00.000Z"), device: "pwa",
    },
    "command/syntax": {
      name: "command/syntax", source: "() => {", hostApi: 1,
      updatedAt: t("2026-10-20T08:03:00.000Z"), device: "pwa",
    },
    "command/future": {
      name: "command/future", source: "() => notable.fromTheFuture()", hostApi: 2,
      updatedAt: t("2026-10-20T08:04:00.000Z"), device: "android:Note Air 5C",
    },
  },
}

const write = (name: string, data: string | Uint8Array) => writeFileSync(new URL(name, dir), data)
write("library-v1.json", JSON.stringify(libraryToJson(library), null, 2) + "\n")
write("notebook-v1.json", JSON.stringify(notebookToJson(notebook), null, 2) + "\n")
write("library-v1.automerge", A.save(A.from(libraryValue(library) as Record<string, unknown>)))
write("notebook-v1.automerge", A.save(A.from(notebookValue(notebook) as Record<string, unknown>)))

write("code-v1.json", JSON.stringify({ codeDocUrl: CODE_DOC_URL, ...codeToJson(code) }, null, 2) + "\n")
write("code-v1.automerge", A.save(A.from(codeValue(code) as Record<string, unknown>)))
write("library-code-v1.automerge", A.save(A.from(libraryValue({ ...library, codeDocUrl: CODE_DOC_URL }) as Record<string, unknown>)))

// Delete against draw (ADR-001): the drawing brings the page back.
type D = Record<string, any>
const origin = A.from(notebookValue(notebook) as D)
const drawn = strokeValue({ ...notebook.pages["p-2"].strokes["s-neg"], id: "drawn-after-delete" })
const deleter = A.change(A.clone(origin), d => deletePage(d, "p-2"))
const drawer = A.change(A.clone(origin), d => { d.pages["p-2"].strokes["drawn-after-delete"] = drawn; markPageLive(d, "p-2") })
write("page-deleted.automerge", A.save(deleter))
write("page-delete-vs-draw.automerge", A.save(A.merge(A.clone(deleter), drawer)))
// Books (docs/plans/ebook-features.md). The search cases' ids were worked out by hand from the
// spec, so a change in either implementation that disagrees with the spec fails both test suites.
const noMeta = { authors: [], series: null, seriesIndex: null, description: null, publisher: null, language: null, isbn: null, published: null, subjects: [] }
const book = (id: string, title: string, folderId: string | null, source: Library["notebooks"][string]["source"], updatedAt: string, more: Partial<Library["notebooks"][string]> = {}) => ({
  id, docUrl: `automerge:${id.replace(/[^a-z]/g, "")}PlaceholderDocUrl`, title, folderId, kind: "notebook" as const, source,
  createdAt: t("2026-01-01T00:00:00.000Z"), updatedAt: t(updatedAt), ...more,
})
const EPUB_SOURCE = { kind: "epub" as const, blob: EPUB, layout: { w: 446, h: 595, em: 12 } }
const books: Library = {
  // The journal doubles as the quick-pages notebook (round 5).
  quickNotebookId: "n-journal",
  folders: {
    "f-uni": library.folders["f-uni"],
    "f-math": library.folders["f-math"],
    "f-fic": { id: "f-fic", title: "Fiction", parentId: null, createdAt: t("2026-01-01T00:00:00.000Z"), updatedAt: t("2026-01-01T00:00:00.000Z") },
  },
  notebooks: Object.fromEntries([
    book("b-lhod", "The Left Hand of Darkness", "f-fic", EPUB_SOURCE, "2026-10-05T00:00:00.000Z", {
      meta: { ...noMeta, authors: ["Ursula K. Le Guin"], series: "Hainish Cycle", seriesIndex: 4, description: "A lone human ambassador is sent to the planet Gethen.",
        publisher: "Ace", language: "en", isbn: "9780441478125", published: "1969", subjects: ["Science Fiction", "Gender"] },
      readAt: t("2026-10-01T00:00:00.000Z"), finishedAt: t("2026-10-05T00:00:00.000Z"),
      pageCount: 320, readPage: 319, rating: 9,
    }),
    book("b-earth", "A Wizard of Earthsea", "f-fic", EPUB_SOURCE, "2026-10-20T00:00:00.000Z", {
      meta: { ...noMeta, authors: ["Ursula K. Le Guin"], series: "Earthsea", seriesIndex: 1, publisher: "Parnassus", language: "en", subjects: ["Fantasy"] },
      readAt: t("2026-10-20T00:00:00.000Z"), finishedAt: null,
      pageCount: 180, readPage: 45, rating: 8,
    }),
    book("b-dune", "Dune", null, EPUB_SOURCE, "2026-09-01T00:00:00.000Z", {
      meta: { ...noMeta, authors: ["Frank Herbert"], series: "Dune", seriesIndex: 1, description: "Set on the desert world Arrakis.", language: "en", subjects: ["Science Fiction"] },
      pageCount: 612,
    }),
    book("b-fr", "L'Étranger", null, EPUB_SOURCE, "2026-08-01T00:00:00.000Z", {
      meta: { ...noMeta, authors: ["Albert Camus"], language: "fr", subjects: ["Roman"], seriesIndex: 0.5 },
      // Length unknown (a doc from before pageCount); a rating given and cleared again.
      rating: null,
    }),
    book("p-crdt", "Paper: CRDTs", "f-math", { kind: "pdf", blob: PDF }, "2026-10-10T00:00:00.000Z", {
      meta: { ...noMeta, authors: ["Martin Kleppmann", "Annette Bieniusa"], description: "Conflict-free replicated data types", subjects: ["distributed systems", "crdt"] },
      readAt: t("2026-10-10T00:00:00.000Z"),
      pageCount: 14, readPage: 3, rating: 7,
    }),
    book("p-anal", "Analysis I Übungsblatt", "f-math", { kind: "pdf", blob: PDF }, "2026-10-15T00:00:00.000Z", { pageCount: 2 }),
    book("n-journal", "Journal", "f-uni", { kind: "none" }, "2026-10-25T00:00:00.000Z", { readAt: t("2026-10-25T00:00:00.000Z"), pageCount: 40, readPage: 39 }),
    { ...book("q-1", "Quick page", "f-uni", { kind: "none" }, "2026-10-26T00:00:00.000Z", { pageCount: 1 }), kind: "quickPage" as const },
  ].map(e => [e.id, e])),
}
const searchCases: { query: string; ids: string[] }[] = [
  { query: "le guin", ids: ["b-lhod", "b-earth"] },
  { query: `author:"le guin"`, ids: ["b-earth", "b-lhod"] },
  { query: "AUTHOR:camus", ids: ["b-fr"] },
  { query: "ubung", ids: ["p-anal", "p-crdt"] },
  { query: "fiction", ids: ["b-lhod", "b-dune", "b-earth"] },
  { query: "science fiction -is:finished", ids: ["b-dune"] },
  { query: "kind:pdf", ids: ["p-crdt", "p-anal"] },
  { query: "kind:notebook", ids: ["n-journal"] },
  { query: "kind:quickpage", ids: ["q-1"] },
  { query: "kind:book", ids: [] },
  { query: "is:reading", ids: ["n-journal", "b-earth", "p-crdt"] },
  { query: "is:unread", ids: ["q-1", "p-anal", "b-dune", "b-fr"] },
  { query: "is:finished", ids: ["b-lhod"] },
  { query: "etranger", ids: ["b-fr"] },
  { query: "sea", ids: ["b-earth"] },
  { query: "series:dune", ids: ["b-dune"] },
  { query: "lang:fr", ids: ["b-fr"] },
  { query: "tag:crdt", ids: ["p-crdt"] },
  { query: "subject:fantasy", ids: ["b-earth"] },
  { query: "isbn:978044", ids: ["b-lhod"] },
  { query: "desc:replicated", ids: ["p-crdt"] },
  { query: "publisher:ace", ids: ["b-lhod"] },
  { query: "folder:uni", ids: ["n-journal", "p-crdt", "q-1", "p-anal"] },
  { query: `"left hand"`, ids: ["b-lhod"] },
  { query: "kleppmann -guin", ids: ["p-crdt"] },
  { query: "frobnicate:x", ids: [] },
  // Not fields, whatever a JS object's prototype holds.
  { query: "constructor:x", ids: [] },
  { query: "tostring:dune", ids: [] },
  { query: "author:", ids: [] },
  { query: "", ids: [] },
  { query: "   ", ids: [] },
]
write("library-books-v1.json", JSON.stringify(libraryToJson(books), null, 2) + "\n")
write("library-books-v1.automerge", A.save(A.from(libraryValue(books) as Record<string, unknown>)))
// Filters and sort (queryLibrary). folderId absent: everything; null: the root folder only.
const byTitle = ["b-earth", "p-anal", "b-dune", "n-journal", "b-fr", "p-crdt", "q-1", "b-lhod"]
const browseCases: { query: string; sort?: string; descending?: boolean; folderId?: string | null; subfolders?: boolean; ids: string[] }[] = [
  { query: "", sort: "title", ids: byTitle },
  { query: "", sort: "title", descending: true, ids: [...byTitle].reverse() },
  { query: "", sort: "author", ids: ["b-fr", "b-dune", "p-crdt", "b-earth", "b-lhod", "p-anal", "n-journal", "q-1"] },
  { query: "", sort: "author", descending: true, ids: ["b-earth", "b-lhod", "p-crdt", "b-dune", "b-fr", "p-anal", "n-journal", "q-1"] },
  { query: "", sort: "series", ids: ["b-dune", "b-earth", "b-lhod", "p-anal", "n-journal", "b-fr", "p-crdt", "q-1"] },
  { query: "", sort: "read", ids: ["n-journal", "b-earth", "p-crdt", "b-lhod", "p-anal", "b-dune", "b-fr", "q-1"] },
  { query: "", sort: "edited", descending: false, ids: ["b-fr", "b-dune", "b-lhod", "p-crdt", "p-anal", "b-earth", "n-journal", "q-1"] },
  { query: "", sort: "added", ids: byTitle },
  { query: "", sort: "finished", ids: ["b-lhod", "b-earth", "p-anal", "b-dune", "n-journal", "b-fr", "p-crdt", "q-1"] },
  { query: "", sort: "published", ids: ["b-lhod", "b-earth", "p-anal", "b-dune", "n-journal", "b-fr", "p-crdt", "q-1"] },
  { query: "", sort: "title", folderId: null, ids: ["b-dune", "b-fr"] },
  { query: "", sort: "title", folderId: "f-uni", ids: ["n-journal", "q-1"] },
  { query: "", sort: "title", folderId: "f-uni", subfolders: true, ids: ["p-anal", "n-journal", "p-crdt", "q-1"] },
  // Every folder is below the root.
  { query: "", sort: "title", folderId: null, subfolders: true, ids: byTitle },
  { query: "le guin", folderId: "f-fic", ids: ["b-lhod", "b-earth"] },
  { query: "kind:pdf,epub", sort: "title", ids: ["b-earth", "p-anal", "b-dune", "b-fr", "p-crdt", "b-lhod"] },
  { query: "-kind:pdf,epub", sort: "title", ids: ["n-journal", "q-1"] },
  { query: "has:series", sort: "series", ids: ["b-dune", "b-earth", "b-lhod"] },
  { query: "-has:meta", sort: "title", ids: ["p-anal", "n-journal", "q-1"] },
  { query: "has:bogus", sort: "title", ids: [] },
  { query: "read:2026-10-2", sort: "read", ids: ["n-journal", "b-earth"] },
  { query: "finished:2026", sort: "title", ids: ["b-lhod"] },
  { query: "edited:2026-10", sort: "edited", ids: ["q-1", "n-journal", "b-earth", "p-anal", "p-crdt", "b-lhod"] },
  { query: "published:196", sort: "title", ids: ["b-lhod"] },
  { query: "is:reading,finished", sort: "title", ids: ["b-earth", "n-journal", "p-crdt", "b-lhod"] },
  // A quoted comma is part of the value, not a separator.
  { query: `author:"le guin, ursula",camus`, ids: ["b-fr"] },
  // No values left: no token, so everything, by relevance (read, then edited).
  { query: "kind:,", ids: ["n-journal", "b-earth", "p-crdt", "b-lhod", "q-1", "p-anal", "b-dune", "b-fr"] },
  // Length, progress, rating. b-fr has no page count, so no length and no progress.
  { query: "", sort: "length", ids: ["q-1", "p-anal", "p-crdt", "n-journal", "b-earth", "b-lhod", "b-dune", "b-fr"] },
  { query: "", sort: "length", descending: true, ids: ["b-dune", "b-lhod", "b-earth", "n-journal", "p-crdt", "p-anal", "q-1", "b-fr"] },
  { query: "", sort: "rating", ids: ["b-lhod", "b-earth", "p-crdt", "p-anal", "b-dune", "n-journal", "b-fr", "q-1"] },
  { query: "", sort: "rating", descending: false, ids: ["p-crdt", "b-earth", "b-lhod", "p-anal", "b-dune", "n-journal", "b-fr", "q-1"] },
  { query: "", sort: "progress", ids: ["n-journal", "b-lhod", "b-earth", "p-crdt", "p-anal", "b-dune", "q-1", "b-fr"] },
  { query: "", sort: "progress", descending: false, ids: ["p-anal", "b-dune", "q-1", "p-crdt", "b-earth", "n-journal", "b-lhod", "b-fr"] },
  { query: "rating:>=8", sort: "rating", ids: ["b-lhod", "b-earth"] },
  { query: "rating:7,9", sort: "title", ids: ["p-crdt", "b-lhod"] },
  { query: "rating:7-8", sort: "title", ids: ["b-earth", "p-crdt"] },
  { query: "has:rating", sort: "title", ids: ["b-earth", "p-crdt", "b-lhod"] },
  { query: "-has:rating", sort: "title", ids: ["p-anal", "b-dune", "n-journal", "b-fr", "q-1"] },
  { query: "pages:<20", sort: "length", ids: ["q-1", "p-anal", "p-crdt"] },
  { query: "pages:>=180", sort: "length", ids: ["b-earth", "b-lhod", "b-dune"] },
  { query: "-has:pages", ids: ["b-fr"] },
  { query: "progress:>0", sort: "progress", ids: ["n-journal", "b-lhod", "b-earth", "p-crdt"] },
  { query: "is:reading progress:<50", sort: "title", ids: ["b-earth", "p-crdt"] },
  { query: "progress:100", sort: "title", ids: ["n-journal", "b-lhod"] },
  { query: "progress:25.1-25.2", ids: ["b-earth"] },
  { query: "rating:abc", ids: [] },
  { query: "rating:>x", ids: [] },
]
// [a, b, sign of naturalCompare(a, b)]
const natural: [string, string, number][] = [
  ["book 2", "book 10", -1], ["book 10", "book 2", 1], ["a1", "a01", -1], ["a01", "a01", 0],
  ["vol. 9b", "vol. 9a", 1], ["2", "a", -1], ["abc", "ab", 1], ["", "a", -1], ["x00012", "x12", 1],
]
write("search-v1.json", JSON.stringify({ library: libraryToJson(books), cases: searchCases, browse: browseCases, natural }, null, 2) + "\n")

console.log("wrote library-v1, notebook-v1, page-delete, code, books and search fixtures")
