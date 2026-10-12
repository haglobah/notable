# Handoff: Automerge sync

Read this first. Update the "State" and "Next" sections when you finish a piece of work.

## Project

Notable is an Android note app for Onyx Boox e-ink tablets (Kotlin, Compose, Room). WebDAV sync was
replaced by Automerge CRDT sync (WebDAV removed in Phase 5) through a server at https://notable.hagenlocher.me. There's
also a PWA for reading, uploading and downloading annotated PDFs. Decisions are in
`docs/adr/ADR-001-automerge-sync-via-formenos.md`. Phases, results and known gaps are in
`docs/plans/automerge-sync.md`.

## Where

- Worktree `/home/beat/projects/notable-automerge-sync`, branch `automerge-sync`.
- `/home/beat/projects/notable` is another session's checkout of `main`. Don't touch it.
- Don't touch `~/noldor*` (server deployment).
- Subagents don't commit or push; the orchestrator does.

## Code map

- Android sync code: `app/src/main/java/com/ethran/notable/sync/automerge/`.
  - `AutomergeSchema.kt`: doc readers and writers.
  - `Migration.kt`: Room → docs.
  - `Projector.kt` + `RoomProjection.kt`: docs → Room.
  - `LocalDocWriter.kt`: editor edits → docs.
  - `RepoLocalDocs.kt`: `DurableStorage` + `RepoLocalDocs`, edits that return once on disk.
  - `DocFirstMirror.kt`: the `ContentMirror` (doc, Room, settle, retry) and `LatencyStats`.
  - `DocOutbox.kt`: edits in Room that wait for the docs (`DocDebt`), on disk.
  - `QueuedBlobStore.kt`: files copied for sync, uploaded when connected.
  - `LogoutGuard.kt`: logout never leaves pending writes or files behind silently.
  - `DeviceLibrary.kt`: the device's one library (`files/notable-sync/library/`), whose it is,
    login decisions (attach, same, refused), adoption of old per-account folders.
  - `NotableSyncClient.kt`: Hilt singleton; opens the device library (with or without an
    account), login, logout, dialer, migration; the bound `ContentMirror`.
  - `data/EditorWriteQueue.kt`: the editor's serial write queue and `PendingLocalWrites`.
  - `data/LibraryEdits.kt`: every folder, notebook, page and import write (via
    `AppRepository.edits`), each through the `ContentMirror`.
  - `AndroidAdapters.kt`: Room, files, background-limit adapters.
- WebDAV leftovers: `data/LegacyWebDavCleanup.kt` (cancels old WorkManager work at start) and
  `MIGRATION_37_38` in `data/db/Migrations.kt` (drops its tables and stored settings).
- Debug hook: `editor/DebugEditHook.kt` (adb broadcasts drive `PageView`/`History`; ops `draw`,
  `erase`, `move`, `undo`, `redo`, `image`/`place`, `dump`, `delete-page`, `share-pdf --es target
  page|book`). Node peer:
  `web/server/scripts/device-peer.ts` (`dump`, `add-stroke`, `blob`, `delete-page`, `move-page`,
  `delete-notebook`, `rename-folder`).
- PDF projection and EPUB: `io/projection/` (`ProjectionPlan.kt` pure planner, `FitzPdfWriter.kt`,
  `FitzEpub.kt`, `EpubFiles.kt`, `PdfProjection.kt`), `io/EpubBackground.kt`. TS twin:
  `web/app/src/lib/project.ts`; parity fixture `web/schema/fixtures/projection-v1.json`
  (`npm run projection-fixture` in `web/app`); device PDF check
  `web/app/scripts/compare-projection.ts <android.pdf> [blob files]`.
- Editor: `docs/editor-data-flow.md`, `docs/editor-state-and-view.md`. The stroke and image write
  functions are in `data/PageDataManager.kt`.
- Web: `web/schema` (shared schema, TS), `web/server` (sync, blobs, accounts), `web/app` (PWA),
  `web/flake.nix` (package, NixOS module, VM test).

## Rules and decisions

- Automerge docs are authoritative; Room is a derived cache.
- Sync runs in the foreground only on Onyx.
- Strokes are immutable doc entries, with points as SB2 bytes.
- Ids, enums and titles are scalar strings. automerge-java 0.0.9: use the `int` overloads, because
  `NewValue.integer()` stores F64.
- Red/green TDD for behavior. Run only the tests you touch.
- Report failures explicitly. Never skip data silently.

## Build, test, device

- `nix develop path:.` gives JDK 21 and SDK 37.
- Tests: `./gradlew :app:testDebugUnitTest --tests '...'`. Web: `nix shell nixpkgs#nodejs_24`, then
  `npm test` in `web/`.
- Device: Note Air 5C over adb (`nix shell nixpkgs#android-tools`).
  - Use only the `.dev` debug build (`com.ethran.notable.dev`, storage `Documents/notabledb-dev`).
  - Never touch `com.ethran.notable` or `com.ethran.notable.preview`, or their data.
  - Onyx may disable a fresh install: `pm enable com.ethran.notable.dev`.
  - Injected touch events don't draw on Onyx.
- Throwaway live accounts:
  - Create: `printf '%s\n' "$PW" | ssh -o BatchMode=yes root@49.12.12.164 'notable-admin user add NAME'`.
  - Delete afterwards: `echo NAME | ssh -o BatchMode=yes root@49.12.12.164 'notable-admin user del NAME'`.

## State (2026-10-11)

- Phases 0–3 are done and deployed. Phase 4 is done except "rebuild Room on demand" (optional).
  Phase 5 (remove WebDAV) is done and committed (`580887cc`). The UI refresh round 2 is merged.
- Phase 4a: Committed in `a177f2e2`: `LocalDocWriter` and the projector fixes.
- Committed (steps 1–3 of an older Next list; unit tests only, not on device):
  - **Durability.** `RepoLocalDocs.kt`: `DurableStorage` records finished puts; `RepoLocalDocs.edit`
    waits for the new heads' incremental keys (or a later snapshot), `createDoc` also waits until
    the doc has no write in flight. A failed storage write fails the edit. `RepoLocalDocsTest`
    passes; its "kill" copy retries until the file list is stable, because compaction deletes
    files during a copy.
  - **Editor wiring.** `data/EditorWriteQueue.kt`: one serial queue for the 6 stroke and image
    writes in `PageDataManager`; `PendingLocalWrites` holds their ids until done. The remove
    functions now take the page id (`PageView` passes it). `DocFirstMirror.kt` is the
    `ContentMirror`: doc commit (durable), Room, settle; Room only without a session; a doc
    failure is reported and Room is still written. `NotableSyncClient` implements `ContentMirror`
    by delegating to it, bound in `di/SyncModule.kt`. The projector skips stroke and image ids
    that are pending, and its Room writes run under the mirror's lock (`exclusive`), so they never
    land between a local Room write and its settle.
  - **Session.** One `ProjectionStateStore` and one `MigrationStateStore` per session.
    `LibraryUrlCache` (`files/notable-sync/<account>/library-url`): with a cached URL, the writer
    and the projection start at launch, offline. `ProjectionStatus.writeError` and
    `commitLatency` (p50, max over the last 256) show in the sync settings; the latency is also
    logged every 100 edits.
- Tests: `sync.automerge.*`, `data.*` and `editor.*` unit tests pass.
- Done, not committed (steps 1–2 of the old Next list; unit tests only, not on device):
  - **Outbox.** `DocOutbox.kt`: a failed doc write, or one made before the library URL is known,
    is recorded in `<account>/pending-doc-writes.json` (synced to disk) *before* the Room write. If
    the record itself fails, the write throws and Room is not touched. Entries are `DocDebt`s
    (rows touched: strokes/images by page, page, notebook, open page, folder) that merge by key.
    `LocalDocWriter.replay` makes the doc match what Room holds now for those rows, so order
    doesn't matter. `DocFirstMirror.retryPending` runs under the mirror lock; a pass stops at the
    first failure and moves that debt to the end. Stroke and image ids in the outbox are in
    `PendingLocalWrites` (also after a restart), so the projector leaves them alone; `release()` on
    disconnect. `create` records first too, so a crash after a creation's Room write leaves no
    Room-only row. Sync settings show `pendingWrites`.
  - **One migration lock.** "Upload notebooks" runs `session.writer.migrateAll` (durable through
    `RepoLocalDocs`, projection heads set). `RoomToAutomergeMigration` checks and migrates each
    notebook under one lock shared with `ensureEntry` (the writer's on-demand migration). Test:
    `LocalDocWriterTest` "upload notebooks and an edit of the same notebook make one doc" (red
    with 2 docs before the lock). `runMigration` also refuses when the cached library URL differs
    from `/me`.
  - **Images offline.** `QueuedBlobStore.kt`: `upload` copies the file (path or `content://`) into
    `<account>/blobs/<sha256>` (synced to disk), returns the hash at once; the writer uses it, so
    image edits commit offline. `drain` uploads when connected; uploaded hashes are listed in
    `blobs/uploaded` and not queued again; a 413 moves the file to `blobs/rejected/` with a
    warning. The projector's blob download takes a queued file first. One background job per
    session (`NotableSyncClient.startBackground`) retries the outbox and drains blobs every 15 s
    and when woken (file queued, writer ready, connected). Sync settings show `pendingBlobs`,
    `blobError`.
  - Tests: `DocOutboxTest`, `QueuedBlobStoreTest`, new cases in `DocFirstMirrorTest` and
    `LocalDocWriterTest`. All `sync.automerge.*` pass (125, the live test skipped).

- Done, not committed (2026-10-11, the old Next list; unit and live JVM tests, not on device):
  - **Logout guard.** `LogoutGuard.kt` + `NotableSyncClient.logout(choice)` + the sync section.
    With pending writes or files, "Log out" stays logged in and shows the counts, with "Sync
    first, then log out" (waits ≤ 2 min for zero pending while connected, then 3 s) and "Log out
    anyway". "Anyway" keeps the whole account folder (repo, outbox, blobs) and reports the
    counts; the next login to the same account sends them. Test: `LogoutGuardTest`.
  - **Live test.** `LiveServerOfflineEditsTest` (env `NOTABLE_E2E_SERVER`, `_USER`, `_PASSWORD`):
    offline edits, an outbox entry and a queued image reach the server on reconnect. Passed
    against the live server; the throwaway account is deleted.
  - **Debug hook.** `editor/DebugEditHook.kt`, registered from `EditorView` in debug builds:
    `adb shell am broadcast -a com.ethran.notable.dev.DEBUG_EDIT --es op draw --ei x 200 --ei y 300
    --ei w 400 --ei h 200 --ei n 3`. Ops: `draw`, `erase`, `move` (`dx`, `dy`), `undo`, `redo`,
    `image --es path <file>` then `place`, `dump`. Results go to logcat, tag `DebugEdit`.
  - **Node peer.** `web/server/scripts/device-peer.ts`: `dump`, `add-stroke <nb> <page> <x> <y>`,
    `blob <sha>`; env `NOTABLE_SERVER`, `NOTABLE_USER`, `NOTABLE_PASSWORD`. Run from `web/` with
    `nix shell nixpkgs#nodejs_24 -c node server/scripts/device-peer.ts dump`.
  - Tests: `sync.automerge.*` and `editor.*` pass (175, the 2 live tests skipped without env);
    `assembleDebug` builds.
- Device check: done on 2026-10-11 (see the last State entry).

- Done, not committed (2026-10-11, Phase 4b; unit tests and `assembleDebug`, not on device):
  - **One entry point.** `data/LibraryEdits.kt` (`AppRepository.edits`) holds every library and
    page write: folders (create, rename, move, delete), notebooks (create, rename, move,
    background, linked file, delete), pages (add, move, duplicate, background, delete, open
    page), quick pages, and imports (`importNotebook` + `Import.page/strokes/images`, used by
    `ImportEngine` for PDF and XOPP). Each Room write runs inside a `ContentMirror` call: edits and
    deletions go to the doc first, creations write Room first (recorded in the outbox before). The
    repositories' write methods are now Room-only primitives for `LibraryEdits` and WebDAV.
  - **Write sites moved:** `LibraryViewModel` (folder, notebook create/delete), `FolderConfig`
    (rename, delete), `NotebookConfig` (background, rename, move, linked file, delete),
    `PagesViewModel` and `PageMenu` (reorder, add, duplicate, delete), `dbUtils.deletePage`,
    `EditorViewModel` (page background, open page, "remove bad page", next page past the end),
    `AppRepository` (add page, duplicate, quick page), `ImportEngine` (PDF, XOPP with images).
  - **Field-level changes.** New `LocalChange`s: `NotebookCreated`, `NotebookRenamed`,
    `NotebookMoved`, `NotebookBackgroundChanged`, `PageAdded`/`PageMoved` (after a page id),
    `PageBackgroundChanged`, `FolderRenamed`, `FolderMoved`. The doc applies each to its current
    state; the Room step uses field `UPDATE`s or re-reads `pageIds` under the mirror lock. So a
    stale screen or an unprojected remote change is not undone.
  - **Merges.** `pageOrder` is edited in place (LCS diff, `AutomergeSchema.editPageOrder`), so
    reorders of different pages on two devices both survive. Folders are edited per field, so a
    move and a concurrent rename both survive. A delete beats a concurrent edit of the same page.
    Structural edits bump the library entry's `updatedAt` at once (strokes: once a minute).
  - **Deleted open page.** `LibraryEdits` publishes `RemotePageEdit(pageDeleted)` for local
    deletes; the projector does for remote page, notebook and quick-page deletes
    (`LibraryProjection.edits`). `PageDataManager` drops the cache entry; the editor
    (`EditorViewModel.onOpenPageDeleted`) shows the page now at that index, or the library.
  - **Fixes on the way:** a page added from Room (`putFullPage`) is scaled to the doc's canvas
    units; a creation no longer clears an earlier failed edit of the same row from the outbox.
  - Tests: `LibraryEditsTest` (15), new cases in `LocalDocWriterTest` and `DocFirstMirrorTest`.
    `sync.automerge.*`, `data.*`, `editor.*`: 275 pass, 2 live tests skipped, 0 fail. `assembleDebug`
    and `compileDebugAndroidTestKotlin` build.

- Done, not committed (2026-10-11, Phase 4 "local-only mode"; unit tests and `assembleDebug`,
  not on device, not against the live server):
  - **One device library** (`DeviceLibrary.kt`, ADR-001 "Local-only mode"): `files/notable-sync/
    library/` with `library.json` (`libraryUrl`, `owner`, `migrate`). Without an account the first
    start makes a library doc of its own and copies Room into it (`migrate` stays set until a run
    copies everything, so a crash resumes; progress and errors show as a snackbar and in
    Settings → Sync). An old `<hash>/` folder of the logged-in account is adopted.
  - **No Room-only writes.** `DocFirstMirror` waits for the first open (`awaitLibrary`) and throws
    before Room when no library is open. Writes during login/logout wait for the switch
    (`mirror.exclusive`) and use the new session.
  - **Login** (`NotableSyncClient.login`): `DeviceLibrary.decideLogin` → `Attach` (device's own
    library: owner and the account's library URL saved, copy due), `Same`, or `Refused`
    (another owner, or the same name with another library doc; the new session is revoked and
    `LibraryOwnedElsewhere` shown). Once connected the copy runs into the account's library:
    docs made here are reused (no second doc), then `LocalDocWriter.mergeLocalCopies` adds this
    device's pages, strokes and images to notebooks the account already had (reading the local
    copy with `LocalDocs.peek`, from disk, so it isn't uploaded).
  - **Logout** keeps the library (owner stays) and reopens it without an account.
    `LogoutGuard.forgetAccountFiles` is gone.
  - Tests: `DeviceLibraryTest` (9), `LocalOnlyLibraryTest` (5: first start, crash resume, attach
    with the server's doc count checked, a notebook the account had, logout + refused login),
    `FakeSyncServer` (an in-process automerge-repo peer over in-memory transports), new cases in
    `DocFirstMirrorTest`, `RepoLocalDocsTest`. `sync.automerge.*` and `data.*`: 249 pass, 2 live
    tests skipped. `assembleDebug` builds.

- Done, not committed (2026-10-11): **device check of 4a, 4b and local-only mode** on the Note
  Air 5C, `.dev` build, live server, two throwaway accounts (deleted; the `.dev` app is logged
  out). Every check passed; no fix was needed. Checked with `DEBUG_EDIT`, `device-peer.ts dump`,
  Room copies (`adb pull` of `notabledb-dev`) and the PWA in headless Chromium.
  - Local-only: installed over a logged-out `.dev` app with Room data; the first start copied 3
    notebooks and the quick page ("Notebooks copied: 4"); a stroke drawn with no account was in
    the doc. Login attached the library: the server got the same 4 doc URLs as `migration.json`
    (no duplicates; account deletion later reported 8 docs = library + every notebook made in the
    check). Logout kept the library ("This device keeps the notebooks of …"); an edit made then
    arrived at the next login. A second account was refused, `library.json` unchanged, its
    library stayed empty.
  - 4a: draw, erase, move, undo, redo, image each matched in `dump` (ids and bboxes) and the PWA.
    `add-stroke` during 6 local draws: all 11 strokes on both sides. Draw then `am force-stop`
    after 50 and 100 ms: strokes in Room and on the server after relaunch. Offline launch: draw
    and image committed, no pending writes, "Files waiting to upload: 1"; wifi back: strokes and
    blob on the server (`blob` sha matches). Logout with the file pending: "Not logged out: 0
    edits and 1 files …" with both choices. Latency: p50 68 ms, max 122 ms (32 edits).
  - 4b: folder create, rename, delete (its notebook went too, on both sides); notebook create,
    rename, move to a folder, background, delete (local and remote); pages add, duplicate (also
    with 46 strokes and 2 images), drag-move, delete, page background; remote `move-page` and
    `rename-folder` projected to Room. PDF import (blob sha = file sha, renders in the PWA) and
    XOPP import (46 strokes, 2 images, same blobs). Open page deleted remotely and locally (the
    `delete-page` hook op, the function quick nav's page menu calls): the editor moved to the
    page now at that index.
  - Not checked: folder move (no UI); quick nav's own button (its 3-finger swipe can't be
    injected: SELinux denies `sendevent`); adoption of an old per-account folder (the `.dev` app
    had none); the first-start snackbar (copy finished before the first screenshot).
  - Screenshots: `/tmp/ndc/*.png` (not kept in the repo).

- Done, not committed (2026-10-11, the rest of Phase 4; unit tests, web tests and device check):
  - **PDF = the PWA's projection.** `ProjectionPlanner` (pure port of `project.ts`/`geometry.ts`:
    backgrounds, content streams to the byte, ink points, colors, opacity, widths, stamps,
    `notable.json`) + `FitzPdfWriter` (fitz: graft, EPUB layout, annotations with `/AP`, embedded
    files as mupdf.js writes them). `PdfProjection` reads the notebook doc from the device library
    (`NotableSyncClient.notebookSnapshot`) and resolves blobs (`NotableSyncClient.blobFile`:
    `sync-blobs`, upload queue, Room's files of that notebook, server). `ExportEngine` PDF export
    (page and book) and the new menu items "Share page as PDF"/"Share notebook as PDF"
    (`ExportEngine.sharePdf`, `cache/shared/`, FileProvider) use it. A single page exports that
    page only, with the whole doc embedded. PNG/JPEG/XOPP unchanged.
  - **EPUB import.** `ImportEngine.handleImportEpub`: copy as `backgrounds/pdfs/<title>@446x595x12.epub`
    (`EpubFiles`: layout in the file name), lay out with fitz (`FitzEpub`), one `pdfN` page per
    page. `RoomMapping.source` makes it an `AmSource.Epub` with that layout. Rendering and page
    counts go through `EpubBackground.laidOutPdf` (hidden `.<name>.pdf` next to the EPUB). The
    projector stores a PWA-uploaded EPUB as `sync-blobs/<sha>@<layout>.epub` and replaces an older
    `<sha>.pdf` row. The library picker accepts `application/epub+zip`. Import titles now come
    from the provider's display name.
  - Tests: `ProjectionParityTest` (3 cases vs the TS fixture), `EpubFilesTest`, new cases in
    `MigrationTest` and `ProjectorTest`. `io.*`, `sync.automerge.*`, `editor.*`: 211 pass, 2 live
    skipped. Web: `web/app` 35 pass (new `projection-fixture.test.ts`), typecheck clean.
    `assembleDebug` builds.
  - Device (Note Air 5C, `.dev`, logged out): imported a 3-chapter EPUB (15 pages, renders),
    drew via the hook, shared the notebook as PDF, pulled it: `qpdf --check` clean, `pdfdetach`
    lists both files, poppler draws the strokes over the same words as the editor, and
    `compare-projection.ts` reports it equal to Node's projection of its embedded doc. The same
    for a native notebook with images (48 inks) and a PDF notebook; "Export page to PDF" from the
    menu wrote a valid 1-page PDF with both attachments. Files: `/tmp/ndc/android-*.pdf`.

- Done, not committed (2026-10-11, **Phase 5: WebDAV removed**; unit suite, `assembleDebug`,
  instrumented migration test and device check):
  - Deleted 56 files: everything in `sync/` but `sync/automerge/` (incl. `serializers/`), their
    unit and android tests, `NotebookSyncState.kt`, `PageSyncState.kt`, `SyncWorkUiBridge.kt`,
    `ConflictResolutionDialog.kt`, `SyncSettingsTab.kt` (with the consent dialog),
    `ApplySyncSettingsUpdateTest.kt`, `docs/webdav-sync-{technical,user}.md`. New:
    `ui/views/EInkSettingsElements.kt` (`EInkSection`, `EInkTextField`, `eInkButtonColors`, used by
    `NotableSyncSection`), `data/LegacyWebDavCleanup.kt`, `app/schemas/.../38.json`.
  - Edited: `Db.kt` (version 38, two entities and DAOs gone), `Migrations.kt` (`MIGRATION_37_38`:
    drop `notebook_sync_state`, `page_sync_state`; delete `Kv` rows `SYNC_SETTINGS`,
    `SYNC_SERVER_CAPABILITIES`), `Kv.kt` (KvProxy without sync settings or `CryptoHelper`),
    `AppRepository` (no `commitNotebookSync`, `replaceDownloadedPage`, sync-state repos, `db`),
    `NotableApp` (calls the cleanup), `MainActivity` (no WebDAV scheduling or snack bridge),
    `EditorViewModel` (no sync-on-close, check-on-open, `appScope`), `LibraryViewModel`,
    `HomeView`, `NotebookCard` (no badges, conflict dialog), `NotebookConfig` (no WebDAV
    deletion), `Settings.kt`/`SettingsViewModel` (Sync tab = `NotableSyncSection` only), DAO and
    repository methods only WebDAV used, 96 `sync_*` strings (en, pl), `build.gradle` comments,
    the editor androidTests, `MigrationTest`, `instructions.md`, ADR-001.
  - Tests: `MigrationTest` (instrumented, 3 pass on the Note Air 5C via `am instrument`; red first:
    `MIGRATION_37_38` didn't exist). Unit suite: 352, 0 failures, 2 live skipped (one earlier full
    run had a `RepoLocalDocsTest` failure, see Known gaps). `assembleDebug`,
    `compileDebugAndroidTestKotlin` build.
  - Device: seeded the old `.dev` build with WebDAV settings, sync-state rows and a scheduled
    periodic `SyncWorker`, installed the new build over it: no crash, the work and its system job
    cancelled, Room 38 without the tables or `Kv` rows, 4 notebooks / 22 pages / 63 strokes kept,
    library shows them, Sync tab shows only Notable Sync. Screenshots
    `/tmp/ndc/phase5-{home,synctab}.png`. The test APK was uninstalled again.

- Done, committed (2026-10-11): **merged `ui-refresh-round-2`** (`f70b92a2`, plan in
  `docs/plans/ui-refresh-round-2.md`) into `automerge-sync`, keeping this branch's decisions:
  - No WebDAV: `NotebookConfigDialog` lost its `SyncScheduler` parameter and deletion queueing;
    cards have no sync badges; string `details_delete_sync_failed` dropped (en, pl).
  - The redesigned details dialog writes only through `appRepository.edits` (rename on dismissal,
    background, move, linked file, delete), on `Dispatchers.IO`, inside their `perform` (save
    before dismissal, slow save keeps it open, failure keeps the draft, delete/move finish first).
    A background change without a value keeps the current one (their `COALESCE`, now in the dialog).
  - Their atomic DAO queries (`rename`, `setBackground`, `setLinkedUri`, `move`) were dropped in
    favor of `LibraryEdits`' field-level ones; those now return the row count and
    `BookRepository` throws "Notebook no longer exists" on 0 (red/green: `LibraryEditsTest`
    "a notebook edit fails visibly…"). `setLinkedExternalUri` still doesn't stamp `updatedAt`.
  - PDFs always linked (`onPdfFile(uri)`); the picker routes EPUBs to the same callback and
    accepts `application/epub+zip` again (red/green: `PdfImportTests.selectedEpubGoesToTheDocumentImporter`).
    Import snack text is now "Importing document".
  - Their androidTests adapted: `NotebookDetailsTests` mocks `edits.renameNotebook`;
    `NotebookMetadataTests` tests the Room primitives `LibraryEdits` uses.
  - Verified: unit suite 353, 0 failures, 2 live skipped; `assembleDebug`,
    `compileDebugAndroidTestKotlin` build; 13 focused androidTests (`library.*`, `details.*`) pass
    on the Note Air 5C via `am instrument` against `.dev` (test APK uninstalled afterwards; Onyx
    disables a fresh `.dev.test`, `pm enable` right before the run). Not checked visually on device.

- Done, committed (2026-10-11): **delete against draw: the page comes back**, and the
  **"Paginate PDF" setting removed**. Unit tests, web tests, `assembleDebug`; not on device.
  - Mechanism (ADR-001 "Delete against draw", `web/schema/README.md`): a page delete sets
    `pages[id].deleted = true` and takes the page out of `pageOrder`; strokes and images stay.
    Every stroke or image write sets `deleted = false` as a new op (delete the key, then set it;
    Automerge skips a put of the same value, which would let a delete that saw the old op win).
    Readers treat a page as deleted only when every concurrent value is true (`getAll` /
    `getConflicts`). A concurrent revival lands at the end of the order. Erase, background change
    and move don't revive. A doc with a deleted page is raised to `version: 2`; readers accept 2,
    new docs stay 1. **Redeploy the PWA before a device deletes a page**, or the old PWA refuses
    that notebook ("update the app").
  - Kotlin: `AutomergeSchema` (`isPageDeleted`, `livePageObjOrNull`, `deletePage`,
    `markPageLive`, readers skip deleted pages, `SUPPORTED_VERSION`), `LocalDocWriter` (delete marks;
    stroke/image upserts revive; touch, background, move use live pages only; `putFullPage`
    revives a marked page in place), `Projector` (`deleted` in a diff → re-read and delete from
    Room if it reads deleted; a page Room lacks is projected in full, `fresh`). TS: `model.ts`,
    `read.ts`, `write.ts` (`isPageDeleted`, `deletePage`, `markPageLive`); `device-peer.ts`
    `delete-page`/`add-stroke` use them.
  - Tests: `web/schema/test/page-delete.test.ts` (8), new `GoldenFixtureTest` cases (TS↔Kotlin
    fixtures `page-deleted*.automerge`, `page-delete-vs-draw*.automerge`), `LocalDocWriterTest`
    (delete vs stroke comes back on both with Room restored; image revives; plain delete stays
    deleted and tells the open editor; background/move don't revive). The old test "…is gone on
    both" was inverted.
  - Paginate PDF: `AppSettings.paginatePdf`, its toggle in `GeneralSettings`, `paginate_pdf`
    (en, pl) removed; `docs/export-formats.md` updated. The stored key needs no migration: the
    settings JSON is read with `ignoreUnknownKeys` and the next save drops it.
  - Verified: unit suite 361, 1 failure (the known flaky `RepoLocalDocsTest` "peek…", passed 3×
    alone right after), 2 live skipped; `assembleDebug` builds. Web: schema 34, app 33 (+2 skipped,
    need qpdf/poppler), server 45 pass; `tsc` clean in schema, app, server.
  - Device: the new `.dev` build is installed and **its app data was cleared** (`pm clear`, then
    storage appop allowed, `pm enable`); the library owned by the deleted account is gone, Room in
    `Documents/notabledb-dev` stays. The device check (hook `delete-page` while `device-peer.ts
    add-stroke`) was not run: creating a throwaway account and launching the app were refused by
    the agent's permission classifier.

- Done, committed (2026-10-11): **"Preview PDF pagination" removed** (Beat's decision: no PDF is
  paginated any more). `AppSettings.visualizePdfPagination`, its toggle, `preview_pdf_pagination`
  (en, pl), `drawPaginationLine` and its paint are gone. PNG/JPEG export height is now always the
  content bottom + `ProjectionGeometry.CONTENT_MARGIN` (50), at least one screen, like the PDF
  projection (`exportContentSize` in `io/PageContentRenderer.kt`; the width still grows with
  content past the screen's right edge, the projection's doesn't). Red/green:
  `ExportContentSizeTest` (with the toggle on, 3000 instead of 3050). Unit suite 364, 0 failures,
  2 live skipped; `assembleDebug` builds. The stored key needs no migration (`ignoreUnknownKeys`).

- Done, committed (2026-10-11): **device check of delete against draw** on the Note Air 5C,
  `.dev` build of `2162dbef`, live server (the live PWA already accepts schema version 2),
  throwaway account `devdela0540c` (deleted; `.dev` logged out). Notebook "Remote Notebook"
  (`remote-nb`, pages `rp-c`, `rp-a`, `rp-b`).
  - Concurrent (deterministic): wifi off, hook `delete-page` on the open page, `device-peer.ts
    add-stroke` on it, wifi on. Twice (`rp-a`, `rp-b`). Server: the page is back at the end of
    the order with all its strokes plus the remote one. Device: the editor left the page at once
    ("open page … was deleted; showing …"); after reconnect it stayed gone (see Known gaps:
    inbound sync stalls); after an app restart Room had both pages with every stroke, and the
    editor showed `rp-a` with the remote stroke (`dump`: `remote-…`, `rs-2`, `rs-4`, `rs-7`).
  - Online, near-simultaneous (hook delete, peer's change ~2 s later): back on both without a
    restart; Room and server equal (5, 5, 3 strokes).
  - PWA (headless Chromium): 3 pages, the revived ones with their strokes.
  - Plain delete (`rp-b`, nothing concurrent): gone on the server, in Room, in the library ("2
    pages") and in the PWA, also after an app restart. The PWA's header said "3 pages" once while
    the list showed 2 (a profile with the old doc cached); the next load said 2.
  - Also seen: the General settings no longer show "Preview PDF pagination".
  - Screenshots: `/tmp/ndc/del-*.png`, `/tmp/ndc/pwa-del-*.png` (not kept in the repo).

Known gaps:

- The logout notice ("Not logged out: N edits and M files …") stays on screen after the counts
  reach zero, until the settings screen is reopened.
- After the attaching login, "Notebooks copied: 4, already there 0" still showed the first
  start's numbers; the docs were reused, so either the rerun didn't update the status or it
  didn't run. Not investigated.
- Imported notebooks were titled from the content URI ("document 1000000068"). Fixed for new
  imports (display name); existing notebooks keep their titles.
- The `.dev` app on the Note Air 5C now keeps a library owned by the deleted account
  `devdela0540c`; any other login there is refused until its data is cleared or it is reinstalled.
- **Inbound sync stalls after an offline edit (found 2026-10-11 on device).** With the app
  running: wifi off, a local edit (a draw or a page delete), a remote edit of the same notebook,
  wifi on. The device sends its edit (the server has it), but never receives the remote one, nor
  later remote edits of that notebook; "Re-apply everything" doesn't help, since the device's doc
  lacks them. A restart of the app fixes it. A plain wifi off/on without a local edit is fine
  (remote stroke projected in < 30 s). Reproduced 3 times. Not investigated; the Android repo's
  sync state with the server after a reconnect with diverged heads is the first place to look.

- A projection that read the doc between a page-cut's remove and re-add, and applies after the
  re-add settled, drops the strokes from the editor until the next projection brings them back.
- A remote change to a stroke whose local delete is queued is skipped in Room; the doc keeps the
  remote put (it wins over the delete), so Room lacks it until that stroke changes again. (For a
  delete in the outbox, the retry deletes it from the doc, so local wins there.)
- "Sync first" can't see whether the server has the repo's last commits (automerge-java 0.0.9
  keeps peer sync state private); it waits for zero pending while connected, then 3 s. The
  library stays after logout, so an unsent commit goes up at the next login to that account.
- A library belongs to the first account that logs in, for good: another account (or the same
  name recreated on the server) is refused, and there is no action yet to move the library to
  another account or start a fresh one. Reinstalling clears it (Room in shared storage stays,
  and becomes the next library's content).
- Old per-account folders other than the logged-in account's (`files/notable-sync/<hash>/`, kept
  by "log out anyway" before this change) are left as they are: not adopted, not deleted.
- Merging into a notebook the account already had: deletions, renames, moves and page order made
  in the local copy don't carry over (the account's win); only missing pages, strokes and images
  are added. Its local doc stays in the repo.
- The copy of Room runs only when due (first start, first login, adoption) or from the "Copy
  notebooks" button. While a run has failures (a missing file), it reruns at every start or
  reconnect, with a snackbar each time.
- A library that can't open makes every write fail ("Not saved"), shown in Settings → Sync; there
  is no recovery action.
- A library adopted without its URL and then logged out has no writer: writes wait in the outbox
  until the next login. Without an account the projector can't fetch a blob missing on the
  device; that notebook's projection fails and retries.
- `NotableSyncClient`'s start/login/logout/open glue has no unit test; the flows are tested on
  `DeviceLibrary`, `LocalDocWriter` and repos directly.
- An unreadable outbox file is moved aside and reported in the sync status; its edits are then
  Room-only. No reconcile from Room exists yet.
- A debt that can never succeed (e.g. an image whose file is gone) is retried forever; it shows as
  pending with its error but has no "discard" action.
- `blobs/uploaded` grows without bound; queued files are never re-hashed before upload.
- A page or notebook debt replays the whole row (e.g. a retried rename also bumps the library
  entry's edited date). Harmless, but not the original edit's exact fields.
- `RepoDocStore` and `UploadingBlobStore` are only used by `LiveServerMigrationTest` now.
- **Phase 5 leftovers.** `work-runtime` stays only for `LegacyWebDavCleanup`; drop both once no
  device can upgrade from a WebDAV build. Room version 38 is this fork's; upstream `Ethran/notable`
  may use 38 differently, so a merge from upstream needs its schema renumbered. Leftover WebDAV
  `.part`/`.bak` files next to images and files on WebDAV servers are not cleaned up.
- `RepoLocalDocsTest` "peek reads a doc as stored…" failed once in a full unit run
  (`FileSystemStorage.loadRange` → `UncheckedIOException`), passed 5 times alone and in the next
  full run. Likely compaction deleting files during `peek`; not investigated.
- A page deleted on one device and drawn on at the same time on another comes back at the end of
  the page order, not at its old place (the delete took it out of `pageOrder`). Erasing on it
  doesn't bring it back.
- A page move whose page was deleted elsewhere is dropped. A page placed after a page the doc no
  longer has goes to the end.
- An import runs its whole Room write under the mirror lock, so projections and editor writes
  wait until it ends (long for big PDFs).
- No intent for moving a quick page between folders (no UI for it here); `PageUpserted` handles it
  in the writer. `moveFolder` has no UI yet.
- Deleting a folder here doesn't announce its pages (the editor can't be open then); a remote
  folder delete does, through its notebook entries.
- The editor leaving a deleted page may still write a preview file for it.
- Strings unused before the UI merge (both branches): `home_new_folder`, `app_latest_version`,
  `work_in_progress`, `export_page_to_error`, `exporting_the_page_to`, `exporting_the_book_to`,
  `cleared_all_strokes`, `toolbar_settings_edit_pen`, `pen_name_charcoal_classic`.
- The details dialog's Export closes the dialog at once (unchanged from their branch).

- `RepoLocalDocsTest` "peek…" failed a second time in a full run (2026-10-11, same
  `loadRange` error); still passes alone.
- A PDF reflects the docs: edits still in the outbox are missing; the export message says how many
  wait. A notebook not yet in the library docs can't be exported (error message).
- An EPUB uploaded from the PWA opening on the Boox is unit-tested only (projector naming); the
  device's library belongs to a deleted account, so no login was possible.
- The laid-out PDF of an EPUB (`.<name>.pdf`) is never deleted with its notebook. Large EPUBs are
  laid out on first render (blocking that page) and held in memory once during export.
- `blobFile` hashes every Room file of the notebook on a miss (memoized per process).
- Single-page PDFs number warnings by notebook page, and have no TS counterpart (the PWA exports
  whole notebooks only).

## Next

What's left overall:

1. Inbound sync stalls after an offline edit until the app restarts (see Known gaps). Reproduce
   in a JVM test against `FakeSyncServer` (disconnect, local and remote edit, reconnect), then fix.
2. Look at the merged library and details screens on the Boox (`.dev`).

Optional, left from Phase 4: rebuild Room from the docs on demand; a live JVM test of attaching
(like `LiveServerOfflineEditsTest`); check a PWA-uploaded EPUB on the Boox. Server hardening and
doc compaction are in the plan's "Open items".
