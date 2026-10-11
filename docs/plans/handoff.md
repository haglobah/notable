# Handoff: Automerge sync

Read this first. Update the "State" and "Next" sections when you finish a piece of work.

## Project

Notable is an Android note app for Onyx Boox e-ink tablets (Kotlin, Compose, Room). WebDAV sync is
being replaced by Automerge CRDT sync through a server at https://notable.hagenlocher.me. There's
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
  - `NotableSyncClient.kt`: Hilt singleton, session, repo, dialer; the bound `ContentMirror`.
  - `data/EditorWriteQueue.kt`: the editor's serial write queue and `PendingLocalWrites`.
  - `AndroidAdapters.kt`: Room, files, background-limit adapters.
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

- Phases 0–3 are done and deployed.
- Phase 4a is in progress. Committed in `a177f2e2`: `LocalDocWriter` and the projector fixes.
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

Known gaps:

- A projection that read the doc between a page-cut's remove and re-add, and applies after the
  re-add settled, drops the strokes from the editor until the next projection brings them back.
- A remote change to a stroke whose local delete is queued is skipped in Room; the doc keeps the
  remote put (it wins over the delete), so Room lacks it until that stroke changes again. (For a
  delete in the outbox, the retry deletes it from the doc, so local wins there.)
- Logout deletes the account folder with its outbox and queued blobs: edits not yet in the docs
  stay in Room only. Logout should warn when `pendingWrites` or `pendingBlobs` is non-zero.
- An unreadable outbox file is moved aside and reported in the sync status; its edits are then
  Room-only. No reconcile from Room exists yet.
- A debt that can never succeed (e.g. an image whose file is gone) is retried forever; it shows as
  pending with its error but has no "discard" action.
- `blobs/uploaded` grows without bound; queued files are never re-hashed before upload.
- A page or notebook debt replays the whole row (e.g. a retried rename also bumps the library
  entry's edited date). Harmless, but not the original edit's exact fields.
- `RepoDocStore` and `UploadingBlobStore` are only used by `LiveServerMigrationTest` now.
- Signed out (no session), edits go to Room only by design; "Upload notebooks" copies only
  notebooks without a library entry, so edits to already uploaded notebooks made while signed out
  are not sent. (Logout deletes the local repo, so this only matters after logging in again.)

## Next (Phase 4a remaining)

1. **Debug hook and device check.**
   - A debug-only broadcast that drives `PageView` and `History`: draw, erase, move, undo/redo,
     images.
   - On device, check: server and PWA, a remote and a local edit merging, `am force-stop` right
     after an edit, latency (sync settings show p50 and max), offline launch with a cached
     library URL, an image inserted offline that uploads on reconnect, the pending counts.
   - A live test for offline edits that sync on reconnect (pattern: `LiveServerMigrationTest`).
   - Update the plan.
2. Logout warns about pending writes and files (see Known gaps).

After 4a: pages, notebooks, folders and imports through the docs; local-only mode without an
account (automatic migration on first start); Android share-as-PDF using the same projection;
EPUB import on Android. Then Phase 5, removing WebDAV.
