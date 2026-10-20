/*
 * Writes the TS side of the golden fixtures:
 *   fixtures/library-v1.json, notebook-v1.json        expected content (also read by Kotlin)
 *   fixtures/library-v1.automerge, notebook-v1.automerge  docs written by these builders
 *   fixtures/page-deleted.automerge          notebook-v1 with p-2 deleted
 *   fixtures/page-delete-vs-draw.automerge   p-2 deleted in one copy and drawn on (stroke
 *                                            "drawn-after-delete") in another, merged
 *   fixtures/code-v1.json, code-v1.automerge  the code doc (ADR-002): expected content and the doc
 *   fixtures/library-code-v1.automerge       library-v1 with codeDocUrl (code-v1.json's)
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
console.log("wrote library-v1, notebook-v1, page-delete and code fixtures")
