# Library, reader and page features

Goal (2026-11-19): everyday library management, reading and page features, chosen by Beat from a
list of 49 (numbers below are from that list). Boox first; the PWA gets what the shared library
needs to stay consistent (trash, read next, mark read, hide finished).
Branch `library-features`, worktree `/home/beat/projects/notable-library-features` (the main
checkout holds another session's uncommitted editor work). Read this first; update "State".

1. Multi-select library items (long-press starts it); folders can be selected too (7).
2. Bulk move to a folder. 3. Bulk mark read / unread. 4. Bulk delete (to the trash).
9. Mark read / unread from the library, without opening the book.
12. Read next: a list of books to read next (not general shelves).
15. Trash with restore; emptied after 30 days.
29. Search hits drawn on the page (Boox).
31. Go to page number. 32. Back after a jump (contents, search hit, link, go to page).
33. Crop margins / fit width for PDFs. 34. Reading time left.
35. Multi-select pages in the pages view: delete, duplicate, move.
36. Move pages to another notebook.
37. Template per page. 39. Insert a page with a chosen template between pages.
40. Page versions: earlier states of a page from the notebook doc's history, restorable.
47. Hide finished books: a persistent per-device toggle.

## Schema (shared, `web/schema`, Kotlin twin `sync/automerge/AmModel.kt`)

New optional keys; the library stays `version: 1` (older readers ignore them, so an old app shows
trashed items as normal ones).

```
notebooks: { <id>: { …, queuedAt?: timestamp|null,   // "Read next" since; null = taken off
                        trashedAt?: timestamp|null } } // in the trash since; null = restored
folders:   { <id>: { …, trashedAt?: timestamp|null } }
```

- **Trash.** An entry is in the trash when its `trashedAt` is set or any folder above it has
  `trashedAt` set. The trash lists what was trashed directly (entries and folders); what's inside a
  trashed folder goes and comes back with it. Library views, search, facets, Recent, Read next and
  Continue reading leave out everything in the trash. Restore clears `trashedAt`; an entry or
  folder whose parent folder is itself in the trash (or gone) is restored to the root (parent set to
  null). "Delete forever" and the 30-day emptying use the existing deletes. The emptying runs when
  the library opens, for items trashed more than 30 days before; any device may do it.
- **Read next.** `queuedAt` set marks a book to read next. The Read next list holds entries with
  `queuedAt` set, not finished, not in the trash, and not opened since being queued (`readAt` absent
  or older than `queuedAt`), oldest queued first (then id). Opening a book or finishing it takes it
  off the list without a write. `is:next` in search matches the same entries.
- Shared helpers in `search.ts` (Kotlin `LibrarySearch.kt`): `isTrashed`, `readNext`. Search
  (`queryLibrary`) never returns entries in the trash. Fixture cases in `search-v1.json`.

## Device-local

- Hide finished (47): `AppSettings.libraryHideFinished` (Boox), `localStorage` (PWA). Hides
  finished books in folder views, search results and sorted views; the Recent menu keeps them.

## State

- 2026-11-19: plan written.
- 2026-11-19, schema + data (not committed): TS `queuedAt`/`trashedAt` read/write/JSON, `setQueued`,
  `setEntryTrashed`, `setFolderTrashed`, `moveEntry`, `moveFolder`, `deleteEntry`, `deleteFolder`;
  `search.ts` `isTrashed`, `isFolderTrashed`, `isNext`, `readNext`, `trashOf`, `expiredTrash`,
  `is:next`; queryLibrary skips the trash. Fixtures `library-trash-v1.{json,automerge}`,
  `trash-v1.json` (by hand; `scripts/write-trash-fixtures.ts`), `library-trash-from-kotlin.automerge`.
  Kotlin twin (AmModel, AutomergeSchema, LibrarySearch), Room 41 (AutoMigration, androidTest
  `migrate40To41_…` written, not run), `LocalChange.NotebookQueued/NotebookTrashed/FolderTrashed`,
  `DocDebt.FolderTrash` (+ ReadingState replays queue/trash), projector + migration carry the
  fields, `LibraryEdits` queue/trash/restore/emptyExpiredTrash. Tests: schema 156, Android unit
  502 (4 skipped), all pass.
- 2026-11-19, Boox library UI (compiles, not yet checked on the device): long press selects
  (cards and folders), `LibrarySelectionBar` (Move…, Mark as read/unread, Add to/Remove from Read
  next, Details for one item, Move to trash with Undo), `TrashDialog` (trash icon by the gear),
  Read next menu and `is:next` segment in the bar, Hide finished toggle (AppSettings), details
  dialogs trash instead of delete and have Read / Read next switches, a trashed quick notebook is
  replaced. Back ends a selection.
- 2026-11-19, PWA (typechecks, tests pass, not yet checked in a browser): Select mode with the same
  actions and a Move picker, `/trash` (restore, delete with in-place confirm, empty, expired
  deleted on open), Read next menu and segment, Hide finished (localStorage), trash left out of
  every list.
- 2026-11-19, reader (compiles, `ReadingPaceTest`; not checked on the device): search hits drawn
  on their pages (`SearchHighlights`, `drawBg(highlights)`, cleared by the next search or "Clear
  highlights"), Go to page and "Page n of m · about … left · … in this chapter" over the contents,
  "Back to page n" chip after a jump (`JumpBackChip`), reading pace in Kv `READING_PACE`.
  33 (crop / fit width) is not built: the main checkout's uncommitted editor work adds fixed views
  (fit width, fit page, custom) that cover it.
- 2026-11-19, pages (compiles, tests): Select in the pages view (long press too) with Move to
  notebook…, Move to start/end, Duplicate, Template…, Versions (one page), Delete; Add page asks
  for the notebook's template or another. Moving copies each page to the target with new ids, then
  deletes the original (same ids in two docs would race in the projector); an AutoPdf page is pinned
  to the PDF page it showed. Page versions: `PageVersionStore` (per device,
  `page-versions.json`) records the notebook doc's heads before a page's first edit after 10
  minutes; `strokesAt` reads them via `fork(heads)`; restore records the current state first and
  replaces the handwriting only (images stay).
- Checks: schema 156, app 98 (2 skipped), server 60, Android unit 509 (4 skipped) pass, except
  `LocalDocWriterTest` "two devices moving the same quick page…", which fails about 1 run in 3 on
  HEAD too (random actor ids decide a concurrent map put). `assembleDebug` builds; lint's errors are
  all older (MissingTranslation in other files, a BOM in LibrarySearch.kt).

- 2026-11-19, checked on the Note Air 5C (dev build, `com.ethran.notable.dev`; its data backed up
  first): Room 40→41 migration test passes (`am instrument`, not `connectedAndroidTest`, which
  uninstalls the app). Library: selecting cards and folders, Move with the folder picker, Mark as
  read/unread, Read next (menu, filter, card mark), trash with Undo and from the details dialog,
  restore, Empty trash, Hide finished across a restart. Reader: Go to page, Back chip, time left,
  search hits on the page. Pages: Select, Template…, Duplicate, Move to notebook (filtered
  picker), Delete with confirm, add a page with a chosen template, Versions and Restore.
  Found and fixed there: a crash in the versions dialog (no Context for the date), a crash
  emptying the trash (notebook reads now in a transaction), highlights baked into page previews,
  Undo snack too short (10 s now, says "Restored"), bulk labels and the bulk move title.
  A review pass also fixed: Undo restoring a book before its folder, forks of old versions not
  freed, highlights on cached pages after a new search, the Back chip on the page it pointed to,
  replaying a read mark undoing a trash from elsewhere (own debts now), quick pages trashable on
  the PWA, the PWA read count, the PWA trash cleanup before the library loads, an un-undoable
  restore without a session, `page-versions.json` growing without bound.
- Committed as 10 commits on `library-features` (2026-11-19).

## Next

- PWA check in a browser (the Chrome extension wasn't connected; tests and typecheck pass).
- On the dev data: previews of AIPosNegFactor pages 7, 11 and 12 still show the test search's
  highlights (made before the fix); they redraw when those pages change.
- Merge with the main checkout's editor work: `EditorView.kt`, `EditorViewModel.kt`, `PageView.kt`
  (one line in drawBgToCanvas), `AppSettings.kt`, `strings.xml` will need hand merges.
- `LocalDocWriterTest` "two devices moving the same quick page…" fails about 1 run in 3 on HEAD
  too (random actor ids decide a concurrent map put); not touched here.
