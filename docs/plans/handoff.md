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
  - `DocFirstMirror.kt`: the `ContentMirror` (doc, Room, settle) and `LatencyStats`.
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
- Done, not committed yet (steps 1–3 of the old Next list; unit tests only, not on device):
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

Known gaps from steps 1–3:

- A projection that read the doc between a page-cut's remove and re-add, and applies after the
  re-add settled, drops the strokes from the editor until the next projection brings them back.
- A remote change to a stroke whose local delete is queued is skipped in Room; the doc keeps the
  remote put (it wins over the delete), so Room lacks it until that stroke changes again.
- A failed doc write leaves the edit in Room only; nothing retries it.
- "Upload notebooks" (`runMigration`) still creates docs through `RepoDocStore`, without the
  durability wait, and can race the writer's on-demand migration of the same notebook (shared
  state store, no shared lock): a possible duplicate doc.
- The cached library URL is not checked against `/me` on connect.
- `UploadingBlobStore` fails offline, so offline image edits are Room-only (step 1 below).

## Next (Phase 4a remaining)

1. **Images offline.** Queue the blob store: copy `content://` files into the account folder, and
   upload when connected.
2. **Debug hook and device check.**
   - A debug-only broadcast that drives `PageView` and `History`: draw, erase, move, undo/redo,
     images.
   - On device, check: server and PWA, a remote and a local edit merging, `am force-stop` right
     after an edit, latency (sync settings show p50 and max), offline launch with a cached
     library URL.
   - A live test for offline edits that sync on reconnect (pattern: `LiveServerMigrationTest`).
   - Update the plan.

After 4a: pages, notebooks, folders and imports through the docs; local-only mode without an
account (automatic migration on first start); Android share-as-PDF using the same projection;
EPUB import on Android. Then Phase 5, removing WebDAV.
