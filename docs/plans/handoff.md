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
  - `LocalDocWriter.kt`: editor edits → docs. Not wired yet.
  - `NotableSyncClient.kt`: Hilt singleton, session, repo, dialer.
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
- Phase 4a is in progress. Committed in `a177f2e2`: `LocalDocWriter`, which isn't wired into the
  editor yet, and the projector fixes.
- Untracked and not compiling yet: `RepoLocalDocsTest.kt`, the durability tests for the classes
  in Next, step 1.

## Next (Phase 4a remaining)

1. **Durability.** In automerge-java 0.0.9, `DocHandle.withDocument` returns before the storage write.
   - Storage keys are `<docId>/incremental/<changeHash>`.
   - Build `DurableStorage`, a `FileSystemStorage` wrapper that records finished puts.
   - Build `RepoLocalDocs`:
     - `edit` waits for the new head's key;
     - `createDoc` waits for its heads' keys, then until no write for that doc is in flight.
   - Make `RepoLocalDocsTest` pass.
2. **Editor wiring.**
   - The 6 write functions in `PageDataManager` (save, update and remove for strokes and images)
     start unordered IO coroutines. That's an existing race: a page-cut move does remove, then add,
     of the same ids.
   - Replace them with one serial queue: doc commit, wait for durability, Room write, settle.
   - With no session, write Room only.
   - Bind `ContentMirror` to `NotableSyncClient` in Hilt.
   - Ignore remote upserts and deletes of ids that have a queued local write.
3. **Session.**
   - One `ProjectionStateStore`, shared by the writer and the projector.
   - Cache `libraryUrl`, and project at launch even when offline.
   - Show writer errors in the sync status.
   - Record pen-up → commit latency (p50 and max).
4. **Images offline.** Queue the blob store: copy `content://` files into the account folder, and
   upload when connected.
5. **Debug hook and device check.**
   - A debug-only broadcast that drives `PageView` and `History`: draw, erase, move, undo/redo,
     images.
   - On device, check: server and PWA, a remote and a local edit merging, `am force-stop` right
     after an edit, latency.
   - A live test for offline edits that sync on reconnect (pattern: `LiveServerMigrationTest`).
   - Update the plan.

After 4a: pages, notebooks, folders and imports through the docs; local-only mode without an
account (automatic migration on first start); Android share-as-PDF using the same projection;
EPUB import on Android. Then Phase 5, removing WebDAV.
