# Plan: Automerge sync, PWA, and PDF projection

Implements [ADR-001](../adr/ADR-001-automerge-sync-via-formenos.md). Each phase ends in something
usable and verified. Later phases depend on earlier ones.

## Phase 0 — Spikes (de-risk before building)

- [x] `automerge-java` on the Boox: create a doc, put a 10k-stroke notebook in it, then save and load.
      Measure APK size, load time and memory. **Go/no-go for the whole plan → GO.**
      See "Phase 0 results" below.
- [x] samod (in `automerge-java`) syncs with the JS `automerge-repo` server over WebSocket → works
      both ways. See "Phase 0 results".
- [ ] Same notebook in the browser through `@automerge/automerge-repo`: check doc size and load time.
- [x] `mupdf.js`: render one PDF page and one EPUB page, add an `/Ink` annotation and an embedded
      file, and save. Checked with qpdf and poppler. See "Phase 0 results".
- [ ] Open the projected PDFs on the iPhone (Files / Safari) and in Firefox. Needs Beat's devices.

## Phase 1 — Schema and server

- [x] Write the schema for the library and notebook docs (TS types and Kotlin classes) with a golden
      fixture. Both test suites load the fixture. See `web/schema/README.md`.
      Found two library quirks; the readers work around both:
      @automerge/automerge 3.5.0 stores zero and negative integers as float64, and
      automerge-java 0.0.9 `NewValue.integer()` stores F64.
- [x] `web/server`: accounts (argon2id, sessions, admin CLI, login rate limit).
- [x] `web/server`: automerge-repo sync and blob PUT/GET by sha256, with a per-doc owner check.
      Isolation tests first. Two independent layers (an authenticated adapter and `shareConfig.access`),
      each enough on its own. Mutation-checked. The Java samod client (0.0.9) syncs through it
      with a Bearer token; another account's samod client gets "not found". See
      `web/server/README.md`.
- [x] `web/flake.nix` builds the server, an OCI image and a NixOS module (`services.notable-server`)
      with a VM test. The PWA output comes in Phase 2.
- [x] noldor `modules/notable.nix`: systemd unit, Caddy vhost, `clan.core.state` with a SQLite
      snapshot before backup. On branch `notable-sync` in `~/noldor-notable-sync`. The formenos system
      builds with `--override-input notable path:…/web`.
- [x] Push `automerge-sync`, lock the `notable` input in noldor, add the DNS record, and deploy to
      formenos (2026-10-11). Live at https://notable.hagenlocher.me, port 3040 behind Caddy. Smoke
      test passed with a throwaway account (deleted). Deployed from noldor branch `notable-sync`;
      that branch still needs merging into noldor `main` once the uncommitted work there lands.
- [ ] Beat creates an account: `ssh -t root@49.12.12.164 notable-admin user add beat`.

## Phase 2 — PWA (read, upload, download)

- [x] Login, library view, and offline cache. `web/app` (Solid + Vite), served by the server from
      the same origin (`NOTABLE_STATIC_DIR`, packaged in the flake, checked in the VM test).
- [x] Upload a PDF or EPUB: MuPDF counts pages, then hash, blob PUT with progress, then the notebook
      doc and library entry. Tested against a real server with a second client.
- [x] Reader: `mupdf.js` pages (in a worker) with the strokes drawn on top, updating live. Pages
      near the viewport draw; the rest free their canvases. Checked in headless Chromium at 390 px
      against a local server with a simulated Boox: ink lands at the expected pixels, and a stroke
      written by the "Boox" appears without a reload. No console errors.
- [x] Download: `project()` in `web/app/src/lib/project.ts`, run in the worker. Original PDF pages
      are grafted, EPUB pages laid out with the stored layout, native pages drawn as vectors.
      Strokes become `/Ink` annotations with appearance streams, and the notebook doc is embedded.
      Tested in Node with qpdf and poppler; the downloaded file was also checked with poppler.
- [ ] Open a downloaded PDF on the iPhone (Files/Safari) and in Firefox. Needs Beat's devices.
- [x] Install and use the PWA on iOS: Beat confirmed it works on the iPhone (2026-10-11).
- [ ] Install the PWA and share a PDF on a real Android phone. Needs Beat's device.
- [x] Manifest and service worker: installable, plus an Android share target. Checked in desktop
      Chrome: the worker is active, the shell precached, and the library loads offline from
      IndexedDB. Install and share on a real Android or iOS device are not checked yet.

At the end of this phase, Beat can upload from a phone and read in any browser.

## Phase 3 — Android sync (strokes still written by Room)

- [x] Debug builds install as `com.ethran.notable.dev` ("Notable dev") next to the release app.
      **The Room database lives in shared storage (`Documents/notabledb`)**, so debug builds also
      use their own folders via `BuildConfig.STORAGE_DIR_SUFFIX`: `notabledb-dev`,
      `notable-dev/Linked`, and exports under `notable-dev`. Without that, a debug build would open
      the real app's database. The FileProvider authority is `${applicationId}.provider`.
      `src/debug/google-services.json` reuses the release Firebase app for the `.dev` id, with
      analytics turned off in debug.
- [x] Login in Notable: a Notable Sync section at the top of Settings → Sync. The token is
      encrypted with the app's Keystore AES-GCM key (`CryptoHelper`) in a private file. Logout
      revokes the session on the server and deletes the account's local repo. An unreadable
      saved login shows as such; it is not treated as logged out.
- [x] Repo client (`NotableSyncClient`, a Hilt singleton started from `NotableApp`): automerge-java
      Repo with `FileSystemStorage` under `files/notable-sync/<account>/`, and an OkHttp WebSocket
      dialer with `Authorization: Bearer`. samod's default backoff handles reconnecting. A 401
      on the upgrade stops dialing and shows "log in again"; it does not loop.
- [x] Projector: library and notebook docs → Room (part B), `sync/automerge/Projector.kt`.
      - **Incremental.** Each doc's last projected heads are stored in `projection.json`. A change
        is applied from `doc.diff(lastHeads, heads)`: only touched pages are read, and only changed
        rows are written (`@Upsert`, which never cascades). A doc without heads (first sync, debug
        rebuild) is projected in full.
      - **Deletions are explicit.** A stroke, image, page, notebook or folder is deleted only when
        the diff deletes its key. A full projection only upserts. A folder deleted elsewhere that
        still has content here is kept, with a warning.
      - **No echo.** The projector writes Room only. The migration skips notebooks that already
        have a library entry, so projected rows never go back up.
      - **Blobs** download on demand to `notabledb[-dev]/sync-blobs/<sha256>[.pdf]` (hash checked
        before the rename). A row already pointing at a file with the same bytes keeps its path.
        A missing blob fails only its notebook, without advancing its heads; it retries after 30 s.
      - **Strokes are scaled** by `localCanvasWidth / doc.canvasWidth`. Unknown pens become
        BALLPEN with a visible warning.
      - **Quick pages** become Room pages without a notebook. Pages only Room knows keep their
        place at the end of `pageIds`.
      - One worker per session. Change listeners only enqueue, since automerge-java forbids
        blocking in them. Every (re)connect re-queues all docs.
      - Settings → Sync shows "Changes from other devices" (status, errors, warnings), plus a
        debug "Re-apply everything from the server".
- [x] Editor live update. `RemoteEdits` carries each applied page edit. `PageDataManager` merges
      it into cached pages (explicit deletes only) and drops their stale screen bitmaps. The
      editor redraws when the open page changes. The client deletes saved previews of remotely
      edited pages, because preview freshness compares file time with `page.updatedAt`, and synced
      rows carry the other device's timestamps.
      - Fixed a pre-existing editor race: `PageView.loadPage` emitted the post-load redraw before
        the canvas observers registered, so it was lost. Local pages hid it behind a saved preview;
        synced pages have none and opened blank. It now draws directly when nobody listens yet.
- [x] Verify part B on the Boox (2026-10-11), with a throwaway account (deleted) and a Node script
      as the other device:
      - A new notebook appeared in the library.
      - A stroke added to the page open in the editor appeared without reopening.
      - A remote erase disappeared live.
      - A page reorder reached `Notebook.pageIds`.
      - The debug rebuild left Room byte-identical.
      - The real app's database checksum was unchanged.
      - **Background:** with Notable in the background, no remote change arrived within 60 s.
        Onyx logs a kernel `freeze` about 3 s after the app leaves the foreground, even with
        `isFrozen=false` and the exempt standby bucket. Changes arrive within seconds of returning
        to the foreground. In the foreground, idle, they arrive live.
- [x] One-time migration: Room → Automerge docs, keyed by existing UUIDs, idempotent and resumable
      (`migration.json` maps notebook → doc; the library entry marks a finished notebook).
      Folders, notebooks and quick pages are copied. PDF and image files are uploaded as blobs from
      paths or content URIs. Strokes are written in batches of 500 per transaction. A notebook
      with a missing file fails on its own, with the file named, and stays local. Pages listed in
      a notebook but missing in Room are reported. Run from Settings → Sync → Upload notebooks.
- [x] Write `canvasWidth` (`min(SCREEN_WIDTH, SCREEN_HEIGHT)`) into every notebook doc. The Kotlin
      model, writer and reader have it now.
- [x] Onyx auto-freeze: the sync section reads `ApplicationExitInfo`. A recent
      `REASON_PACKAGE_STATE_CHANGE` on an Onyx device means it was frozen, and the section says so
      with steps. It also warns about background restriction and battery optimization.
- [x] Verify on the Boox (2026-10-11, Note Air 5C). The `.dev` build uses `notabledb-dev`, and
      the real app's database checksum and install time were unchanged. Through the UI: created a
      notebook and a quick page, and copy-imported a 2-page PDF. Injected stylus events don't
      draw on Onyx (the firmware pen pipeline reads the digitizer), so strokes were seeded into the
      `.dev` Room database with the real `encodeStrokePoints`, while the app was stopped. Then:
      logged in, uploaded 3 notebooks with 0 failures, and a second run found all 3 already there.
      A JS client read `canvasWidth` 1860 from the device, exact stroke coordinates, and the PDF
      blob, byte-identical to the imported file. The live PWA drew the strokes at the same spots as
      the Boox's own editor. Logout cleared the token and the local repo.
      - Fixed on device: on a fresh install `files/notable-sync/` doesn't exist, and saving the
        account failed with ENOENT. Both stores now create their folder (tests first).
      - Onyx auto-freeze: a fresh install is disabled about 1 s after install (`enabled=3`), even
        before first launch, so there is no exit record and the warning can't show; `pm enable`
        fixes it. A real background freeze didn't happen within 60 s in the background or 3 min
        with the screen off (USB connected). The same mechanism, `pm disable-user` on the running
        app, gives exit reason 15 "STATE CHANGE". After that the section shows the Onyx warning,
        and the client reconnects on its own with the stored token.
      - The WebDAV "Experimental Feature" consent dialog covers the whole Sync tab, including the
        Notable Sync section, and comes back after every reinstall.

Known gaps in part A:

- Pages that belong to a notebook in Room but are missing from `Notebook.pageIds` are not
  migrated. The app keeps the two in step, so this should not happen.
- A crash between `repo.create` and writing `migration.json` leaves an orphan doc on the server.
  The rerun creates a new one. It's harmless, but uses space.
- Two installs on one device both handle `notable://` links, so Android asks which app to use.
- A debug build logged into the same WebDAV server as the release app shares its remote folder.
  WebDAV paths have no suffix.

Known gaps in part B:

- **Strokes drawn on the Boox after the upload are not synced.** They stay in Room only. The
  projector never deletes them, and a full rebuild keeps them. Notebooks created after the upload
  go up with another "Upload notebooks"; edits to already uploaded notebooks wait for Phase 4.
- No Room transaction around one doc's projection: Room transactions are suspend-scoped, and the
  blocking sink would deadlock in one. Writes are idempotent and heads advance last, so a crash
  re-applies the same diff.
- A page that is loading when an edit commits can miss it, until it is reopened.
- Deleting the page that is open in the editor leaves the editor showing it until it is closed.
  Phase 4b: the editor now moves to the neighbouring page or the library (unit tests only, not on
  device).
- Changes arrive only while Notable is in the foreground on Onyx (see above). Decided
  2026-10-11: foreground-only sync, no foreground service (ADR-001).
- Notebook doc changes bump no `updatedAt` in the doc, so "edited" dates in the library don't move.
  Phase 4: stroke edits bump it once a minute, structural edits at once.
- The WebDAV consent dialog still covers the Notable Sync section; it goes with WebDAV in Phase 5.
- automerge-repo in Node logs `TimeoutNegativeWarning` on most client runs; not investigated.

## Phase 4 — Make Automerge authoritative

- [x] Route every editor write (strokes, images, pages, folders, notebooks) through the doc. Room
      only receives projected changes. Checked on device (2026-10-11).
  - [x] **4a: strokes and images** (`LocalDocWriter`, `DocFirstMirror`, `EditorWriteQueue`):
        doc commit on disk, then Room, then settle. Failed or early writes wait in `DocOutbox`;
        images are copied into `QueuedBlobStore` and upload when connected. With a cached
        library URL this works offline from launch. Sync settings show pending writes, pending
        files, the last write error and pen-up → commit latency (p50, max).
  - [x] **Logout guard** (`LogoutGuard.kt`). With pending writes or files, "Log out" stays
        logged in and offers "Sync first, then log out" (waits up to 2 min for zero pending
        while connected, plus 3 s for the repo to send) or "Log out anyway". "Anyway" keeps the
        whole account folder (repo, outbox, queued files) and says how many edits and files did
        not reach the server; the next login to the same account sends them. Room keeps the
        edits either way. An outbox set aside as unreadable also keeps the folder.
        `LogoutGuardTest`.
  - [x] **Live JVM test** `LiveServerOfflineEditsTest` (opt-in, like `LiveServerMigrationTest`):
        a repo on disk migrates online, then edits offline (stroke add and delete, an image, a
        stroke recorded in the outbox before the writer existed), restarts online and drains
        the file queue. A fresh repo reads exactly those strokes, the image and its blob from
        the server. Passed against the live server on 2026-10-11 (11 s), account deleted.
  - [x] **Debug hook** (`editor/DebugEditHook.kt`, debug builds only, registered while the
        editor is open): `adb shell am broadcast -a com.ethran.notable.dev.DEBUG_EDIT --es op
        <draw|erase|move|undo|redo|image|place|dump> ...` drives the real `PageView` and `History`
        paths (`handleDraw`, `handleErase`, selection move as a tap outside, `History.undo/redo`,
        the image picker path). Each op logs one `DebugEdit` line. Node peer for checks:
        `web/server/scripts/device-peer.ts` (`dump`, `add-stroke`, `blob`).
  - [x] **Device check of 4a** on the Note Air 5C (2026-10-11, `.dev` build, live server): draw,
        erase, move, undo, redo and image reach the server and the PWA; a remote stroke during
        local draws keeps both; a force-stop 50 ms after a draw loses nothing; offline edits
        commit (0 pending writes, 1 file waiting) and upload when wifi returns; the logout guard
        shows the pending file. Pen-up → commit latency p50 68 ms, max 122 ms (32 edits).
- [x] **4b: pages, notebooks and folders through the docs** (create, rename, move, delete,
      reorder, duplicate, background, open page, quick pages), and **imports** (PDF, XOPP with
      their images). `data/LibraryEdits.kt` is the one entry point (via `AppRepository.edits`);
      every Room write there runs inside a `ContentMirror` call, so on the same lock, outbox and
      echo suppression as strokes. Edits carry one field (`NotebookRenamed`, `PageMoved` after a
      page id, `FolderMoved`, ...), so a stale screen or an unprojected remote change is never
      undone. `pageOrder` is edited in place (LCS), so reorders of different pages on two
      devices both survive; folders are edited per field. Structural edits bump the library
      entry's `updatedAt` at once. A delete beats a concurrent edit of the same page; the editor
      showing a deleted page (here or remote) moves to its neighbour or the library.
      `LibraryEditsTest`, new cases in `LocalDocWriterTest`, `DocFirstMirrorTest`. Unit tests
      only; not on device.
  - [x] **Device check of 4b** (2026-10-11): folder create, rename, delete; notebook create,
        rename, move, background, delete (local and remote); page add, duplicate, move, delete,
        background; PDF and XOPP import; remote page move and folder rename; the open page deleted
        here or remotely moves the editor on. Not checked: folder move (no UI), quick nav's own
        button (its gesture can't be injected; the debug hook calls the same delete).
- [x] **Local-only mode without an account** (`DeviceLibrary.kt`, ADR-001 "Local-only mode").
      One device library in `files/notable-sync/library/`, with or without an account. The first
      start makes its library doc and copies Room into it automatically (progress and errors in
      a snackbar and in Settings → Sync; due until a run copies everything, so a crash resumes).
      Every write goes to the docs; the Room-only fallback is gone (a write waits for the
      library to open, and fails before Room if it can't). The first login attaches the library
      to the account: the copy runs again into the account's library, reusing every doc, and
      sync uploads them. Notebooks the account had already get this device's pages, strokes
      and images added (`LocalDocWriter.mergeLocalCopies`), nothing deleted. Logout keeps the
      library. A login to another account is refused. Old per-account folders are adopted.
      `DeviceLibraryTest`, `LocalOnlyLibraryTest` (real repos and an in-process sync peer,
      `FakeSyncServer`, which counts the server's docs), new cases in `DocFirstMirrorTest` and
      `RepoLocalDocsTest`. Checked on the Note Air 5C against the live server (2026-10-11): first
      start copies Room with no account, edits work logged out, login attaches with no duplicate
      docs, logout keeps the library, a later login sends the edits made meanwhile, another
      account is refused. Not checked on device: adoption of an old per-account folder.
- [ ] Rebuild Room from the docs on demand (a debug action and a test).
- [x] **Android PDF = the PWA's projection** (`io/projection/`). `ProjectionPlanner` (pure Kotlin,
      a port of `project.ts`/`geometry.ts`) plans pages, content streams, `/Ink` and stamp
      annotations and `notable.json`; `FitzPdfWriter` carries the plan out with MuPDF `fitz`
      (grafted PDF pages, EPUB laid out with its stored layout, embedded `notable.automerge`).
      `PdfProjection` reads the device library's docs (`NotableSyncClient.notebookSnapshot`) and
      finds blobs locally (projection downloads, upload queue, Room's files) or on the server.
      Every PDF export (`ExportEngine`, page and notebook) and the new "Share page/notebook as
      PDF" use it; PNG, JPEG and XOPP are unchanged. Parity: `ProjectionParityTest` (JVM) against
      `web/schema/fixtures/projection-v1.json`, which the TS `project()` writes
      (`projection-fixture.test.ts` keeps it current). Device check 2026-10-11: shared PDFs of an
      EPUB, a native and a PDF notebook equal the PWA's projection of their embedded doc
      (`web/app/scripts/compare-projection.ts`), pass `qpdf --check`, list both attachments in
      `pdfdetach`, and poppler draws the ink where it was drawn.
- [x] **EPUB import on Android.** Copied as `<title>@446x595x12.epub` (layout in the name,
      `EpubFiles`; 446 × 595 pt, 12 pt as the PWA), laid out once with fitz
      (`FitzEpub`, `EpubBackground` keeps the laid-out PDF next to it), one `pdfN` page per page;
      the doc gets an EPUB source with that layout. The projector names a PWA-uploaded EPUB's blob
      `<sha>@<layout>.epub`, so it renders on the Boox. Checked on device: a 3-chapter EPUB
      imported as 15 pages, rendered, and its shared PDF matches Node's layout page for page. Not
      checked on device: an EPUB uploaded from the PWA (unit test only).

## Phase 5 — Remove WebDAV

- [ ] Delete `sync/` WebDAV code, its settings UI, tests, and docs.
- [ ] Drop the `page_sync_state` table with a Room migration.

## Phase 0 results

### automerge-java 0.0.9 on Onyx Note Air 5C (2026-10-11)

Setup: Android 15, arm64, 5.3 GB RAM. Synthetic notebook: 100 pages × 100 strokes. Each stroke holds
about 100 points at 18 bytes per point, with no LZ4, so 19.3 MiB of point data. That's heavier than
typical. Throwaway app, separate app id, uninstalled afterwards.

| Measure | Result |
|---|---|
| Bulk insert 10k strokes, one transaction (migration case) | 7.0 s |
| `save()` full doc | 1.7 s, 16.6 MiB (0.86 × raw points) |
| Cold `load()` | 0.49 s |
| Read one page's strokes | 5 ms |
| Read all points | 0.57 s |
| One stroke per transaction (editor case) | 2.8 ms avg, 16 ms max, 2.2 KB change |
| Erase 1000 strokes, then `save()` | size unchanged: erased strokes stay in history |
| Repo + `FileSystemStorage`: create / reopen + find | 0.84 s / 0.98 s, 20 MiB on disk |
| Peak native heap | 39 MB |
| APK cost | +6.4 MB (arm64 `.so` only) |

Consequences for the build:

- Commit on a background thread. 16 ms max is too slow for the pen-up path on the UI thread.
- Never call a full `save()` per stroke. Let the Repo's storage write incrementally.
- Exclude `native/**` java resources (48 MB of desktop JNI libs) in `packaging`, and keep
  `abiFilters` to the ABIs we ship.
- History growth is confirmed. Compaction (an open item below) is needed for heavily erased
  notebooks.
- **Onyx auto-freeze:** Onyx disables user apps that it freezes (`pm` state `enabled=3`). It
  disabled the spike one second after install. Background sync needs Notable exempted. Detect a
  frozen state and tell the user in the sync settings; don't fail silently.

### samod ↔ JS automerge-repo interop (2026-10-11)

Setup: a Node relay (`automerge-repo` 2.6.0-alpha.3 with `WebSocketServerAdapter`, `sharePolicy`
false), a JS client peer, and a JVM client using `automerge-java` 0.0.9 with a `Dialer` over
`java.net.http.WebSocket`.

- The JS client creates a doc. Java finds it by `automerge:` URL through the relay and reads a string
  and a byte array. Java writes a field, and the JS client receives it. Both directions work.
- A `Dialer` only has to move whole binary frames. samod does the handshake itself. On Android this
  is about 40 lines of OkHttp.
- **Type trap for the schema:** a JS string assigned in `create({...})` becomes a collaborative
  `Text` object. A Java `tx.set(obj, key, String)` writes a scalar string, which JS reads as
  `ImmutableString`. The schema must use scalar strings for ids, enums and titles on both sides.
  JS must write them with `new ImmutableString(...)`. The golden fixture has to catch any drift.
- npm's `latest` tag for `automerge-repo` is an alpha (2.6.0-alpha.3). Pin exact versions.

### mupdf.js projection (2026-10-11)

Setup: `mupdf` 1.28.1 (npm, AGPL-3.0, the same MuPDF version as Android's `fitz` 1.28.0) in Node.
The inputs were a generated 2-page PDF and a generated 2-chapter EPUB 3. No personal files were used.

- **PDF projection works.** Two `/Ink` annotations plus an embedded `notable.automerge` file in
  7.5 ms. MuPDF writes an `/AP` appearance stream for each annotation, so readers that don't draw
  ink themselves still show the strokes.
- **EPUB → PDF works.** `layout(595, 842, 12)` gives 6 pages. Writing them through
  `DocumentWriter("pdf")` took 11.5 ms and kept real, selectable text. Laying out twice with the
  same parameters gives identical pages, so annotation coordinates are stable.
- **Independent checks:** `qpdf --check` finds no errors. Poppler's `pdfdetach` lists the
  attachment. Poppler's renderer draws both strokes on the PDF and on the EPUB-derived PDF.
- MuPDF warns `unknown epub version: 3.0` but lays out fine.
- **PWA cost:** `mupdf-wasm.wasm` is 10.4 MB raw, 3.6 MB brotli. Load it lazily (only in the reader
  and on download) and let the service worker cache it.
- Not yet checked: iOS Files/Safari and Firefox's PDF viewer. Android's `fitz` API for
  `setInkList` and `addEmbeddedFile` should match, but confirm it when building the Android
  projection in Phase 4.

## Open items

Server security, from the Phase 1 review:

- **Claim window.** A doc belongs to whoever syncs it first. Anyone who learns a doc id before its
  owner's first sync could claim it. Ids are random, so this needs a leak. A fix is to have the
  client register the doc over HTTP before its first sync.
- **No per-account quotas.** Doc count, doc size and blob bytes are unlimited. Needed before open
  sign-up is enabled anywhere.
- **Long-lived tokens.** Sessions expire after 180 idle days, with no scoping and no rotation
  beyond logout or revoke. Consider rotation on use for the Android token.
- **In-memory login rate limit.** It resets on restart. Behind Caddy it needs
  `NOTABLE_TRUST_PROXY=1`, or every login shares one IP.
- **Unexplained warning.** A Node `TimeoutNegativeWarning` appeared once during the manual
  interop run, not in the tests. Not investigated.

- How to compact docs whose history has grown (see ADR-001 consequences).
- Pen rendering in the PWA: decide how closely it must match the Boox.
