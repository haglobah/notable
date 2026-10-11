# @notable/schema

The shared Automerge document schema for Notable sync ([ADR-001](../../docs/adr/ADR-001-automerge-sync-via-formenos.md)).
The Android twin is `app/src/main/java/com/ethran/notable/sync/automerge/`. Both sides test
against the golden fixtures in `fixtures/`.

## Documents

Map keys are ids. Ids are not repeated inside entries.

**Library doc** (one per account):

```
{ schema: "notable/library", version: 1,
  folders:   { <folderId>: { title, parentId|null, createdAt, updatedAt } },
  notebooks: { <notebookId>: { docUrl, title, folderId|null, kind: "notebook"|"quickPage",
                               source: { kind: "none"|"pdf"|"epub", blob|null, layout?: {w,h,em} },
                               createdAt, updatedAt } } }
```

**Notebook doc** (one per notebook, and one per quick page):

```
{ schema: "notable/notebook", version: 1, notebookId, openPageId|null,
  defaultBackground: { type, native|null, blob|null },
  pageOrder: [pageId],
  pages: { <pageId>: { background, createdAt, updatedAt,
                       strokes: { <strokeId>: { pen, color, size, bbox: [l,t,r,b], points: bytes,
                                                createdAt, updatedAt } },
                       images:  { <imageId>: { blob, rect: [x,y,w,h], createdAt, updatedAt } } } } }
```

- `blob` is the sha256 hex digest of a file in the server's blob store.
- `background.type` is an Android `BackgroundType` key: `native`, `image`, `imagerepeating`,
  `coverImage`, `autoPdf`, or `pdfN`.
- A PDF or EPUB added from the PWA gets one page per document page, with background `pdfN`
  pointing at the source blob, like Android's PDF import. EPUB pages use `pdfN` too: MuPDF opens
  either format, and an EPUB is laid out with its library entry's `source.layout` first.
- `canvasWidth` (optional number) is the page width in stroke coordinates: the Android canvas
  width, which is the device's shorter screen side (1860 px on the Note Air 5C). A PDF or EPUB page
  is scaled to it, so a stroke at `x` px sits at `x × pageWidthPt / canvasWidth` pt. Docs without it
  are read as `DEFAULT_CANVAS_WIDTH` (1860). Android must write it (Phase 3).
- `points` is Notable's SB2 stroke encoding, with pressure normalized to [0, 1]. See
  `src/sb2.ts` and `StrokePointConverter.kt`.
- Strokes are immutable. Editing a stroke replaces its entry, and erasing deletes its key.
- `pageOrder` can be inconsistent after concurrent edits: it may name a deleted page, repeat a
  page, or miss one. Readers normalize it: they keep the first occurrence of each existing page,
  then append the missing pages, oldest first.
- Writers edit `pageOrder` in place (delete and insert items), never replace the list. Then two
  devices that move different pages at once both keep their move. Android does this
  (`AutomergeSchema.editPageOrder`, `placeInPageOrder`). Folder and entry edits likewise set single
  fields of an existing map, so a move and a concurrent rename both survive.

## Rules

- **Scalar strings only.** Ids, enums and titles are scalar strings. In JS, a plain string written
  into a doc becomes collaborative `Text`, so the builders in `src/write.ts` wrap every string in
  `ImmutableString`. Both readers reject `Text`, giving the path.
- **Numbers.** JS writes plain numbers. @automerge/automerge 3.5.0 stores positive integers as
  `int`, but zero and negative integers as `float64`. It also stores `Float64`/`Int` wrappers as
  maps inside `A.from()` and in nested lists and maps. So readers accept an int for a float field
  and a whole-number float for an int field. Kotlin writes ints through the `Int` overloads, because
  `NewValue.integer()` in automerge-java 0.0.9 stores an `F64`.
- **Strict reading.** A missing field, a wrong type or an unknown enum throws `SchemaError` /
  `SchemaException`, naming the dotted path. Nothing is repaired silently.
- **Versioning.** Adding an optional field keeps `version`. Any change an older reader would
  misread bumps `version`. Readers refuse a doc newer than they support and say to update the app.

## Not stored, on purpose

- `Page.scroll`: per-device view state.
- `Notebook.linkedExternalUri`: a device-local path. `source.blob` replaces it.
- `Stroke.maxPressure`: docs always hold normalized pressure. The migration normalizes legacy
  rows with `withNormalizedPressure()` first.
- `pageId`, `notebookId` and `parentFolderId` on rows: these are implied by where an entry is
  stored. A quick page's folder is its library entry's `folderId`.
- Notebook `title` and timestamps are kept only in the library entry, so listing the library does
  not open every notebook doc.

## Fixtures

| File | Written by |
|---|---|
| `sb2-strokes.json` | Kotlin `Sb2FixtureTest` (real app encoder, raw and LZ4) |
| `library-v1.*`, `notebook-v1.*` | `npm run fixtures` (`scripts/write-fixtures.ts`) |
| `*-from-kotlin.automerge` | Kotlin `GoldenFixtureTest` |

Regenerate only for an intentional change. Kotlin fixtures: `UPDATE_FIXTURES=1 ./gradlew
:app:testDebugUnitTest --tests 'com.ethran.notable.sync.automerge.*'`. Then run `npm run fixtures`
and the Kotlin tests again. The `docUrl` values in the fixtures are placeholders, not real
documents.

## Commands

```
npm test            # node:test, from web/schema
npm run typecheck   # tsc --noEmit
```
