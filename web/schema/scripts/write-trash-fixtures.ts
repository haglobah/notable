/*
 * Writes the Read next and trash fixtures (docs/plans/library-features.md):
 *   fixtures/library-trash-v1.json, .automerge  a library with queuedAt and trashedAt (expected
 *                                              content, and the doc these builders write)
 *   fixtures/trash-v1.json                     that library's Read next list, trash, expired trash
 *                                              and searches, worked out by hand from the plan
 * Kotlin reads all three (TrashSchemaTest) and writes library-trash-from-kotlin.automerge.
 *
 * Run: node scripts/write-trash-fixtures.ts (from web/schema). Kept apart from write-fixtures.ts so
 * adding these didn't rewrite the older binary fixtures.
 */
import { writeFileSync } from "node:fs"
import * as A from "@automerge/automerge"
import { libraryValue, libraryToJson, type Library, type NotebookEntry } from "../src/index.ts"

const dir = new URL("../fixtures/", import.meta.url)
const write = (name: string, data: string | Uint8Array) => writeFileSync(new URL(name, dir), data)
const t = (s: string) => new Date(s)
const at = (day: string) => t(`2026-${day}T00:00:00.000Z`)

const entry = (id: string, title: string, folderId: string | null, updated: string, extra: Partial<NotebookEntry> = {}): NotebookEntry => ({
  id, docUrl: `automerge:${id}PlaceholderDocUrl`, title, folderId, kind: "notebook", source: { kind: "none" },
  createdAt: at("01-01"), updatedAt: at(updated), ...extra,
})

const library: Library = {
  folders: {
    // In the trash since 09-01, so expired by 11-19; f-sub is in the trash through it.
    "f-old": { id: "f-old", title: "Old", parentId: null, createdAt: at("01-01"), updatedAt: at("01-01"), trashedAt: at("09-01") },
    "f-sub": { id: "f-sub", title: "Sub", parentId: "f-old", createdAt: at("01-01"), updatedAt: at("01-01") },
    // Restored: trashedAt null.
    "f-keep": { id: "f-keep", title: "Keep", parentId: null, createdAt: at("01-01"), updatedAt: at("01-01"), trashedAt: null },
  },
  notebooks: {
    "n-dune": entry("n-dune", "Dune", null, "02-01", { queuedAt: at("10-01") }),
    "n-fr": entry("n-fr", "L'Étranger", null, "01-15", { queuedAt: at("09-15") }),
    // Opened after it was queued: off the list.
    "n-opened": entry("n-opened", "Earthsea", null, "01-02", { queuedAt: at("10-01"), readAt: at("10-20") }),
    // Read before it was queued: on the list.
    "n-reread": entry("n-reread", "Reread", null, "01-03", { queuedAt: at("10-05"), readAt: at("10-01") }),
    "n-done": entry("n-done", "Lathe", null, "01-04", { queuedAt: at("10-01"), finishedAt: at("10-30") }),
    "n-off": entry("n-off", "Off", null, "01-05", { queuedAt: null }),
    // Trashed 9 days before 11-19: not expired. Queued, but the trash wins.
    "n-gone": entry("n-gone", "Dune Messiah", null, "01-06", { queuedAt: at("08-01"), trashedAt: at("11-10") }),
    // In the trash through f-old, but not listed in it.
    "n-sub": entry("n-sub", "Dune notes", "f-sub", "01-07", { queuedAt: at("08-02") }),
    "n-back": entry("n-back", "Dune again", "f-keep", "03-01", { trashedAt: null }),
    "n-old": entry("n-old", "Dune old", null, "01-08", { trashedAt: at("10-01") }),
  },
}

write("library-trash-v1.json", JSON.stringify(libraryToJson(library), null, 2) + "\n")
write("library-trash-v1.automerge", A.save(A.from(libraryValue(library) as Record<string, unknown>)))
write("trash-v1.json", JSON.stringify({
  now: "2026-11-19T00:00:00.000Z",
  readNext: ["n-fr", "n-dune", "n-reread"],
  // { kind, id }, most recently trashed first.
  trash: [["notebook", "n-gone"], ["notebook", "n-old"], ["folder", "f-old"]],
  expired: [["notebook", "n-old"], ["folder", "f-old"]],
  trashed: ["n-gone", "n-sub", "n-old"],
  cases: [
    // Title hits score alike; then newest edit first. Nothing in the trash.
    { query: "dune", ids: ["n-back", "n-dune"] },
    // No score; n-reread has a readAt, then newest edit first.
    { query: "is:next", ids: ["n-reread", "n-dune", "n-fr"] },
    { query: "-is:next dune", ids: ["n-back"] },
    { query: "folder:old", ids: [] },
  ],
}, null, 2) + "\n")
console.log("wrote library-trash-v1 and trash-v1")
