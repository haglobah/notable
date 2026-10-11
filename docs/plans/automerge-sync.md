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
- [ ] `web/flake.nix` builds the server and the PWA.
- [ ] noldor `modules/notable.nix`: systemd unit, Caddy vhost, `clan.core.state`. Deploy to formenos.

## Phase 2 — PWA (read, upload, download)

- [ ] Login, library view, and offline cache.
- [ ] Upload a PDF or EPUB: write the blob, then create the notebook doc.
- [ ] Reader: `mupdf.js` pages with the strokes drawn on top.
- [ ] Download: generate the PDF projection on the client.
- [ ] Manifest and service worker: installable on iOS and Android, plus an Android share target.

At the end of this phase, Beat can upload from a phone and read in any browser.

## Phase 3 — Android sync (strokes still written by Room)

- [ ] Login screen in Notable, with the token in encrypted storage.
- [ ] Repo client: connect to formenos, store docs locally, pull blobs.
- [ ] Projector: notebook doc → Room, so remote changes show up in the editor.
- [ ] One-time migration: Room → Automerge docs, keyed by existing UUIDs, idempotent.

## Phase 4 — Make Automerge authoritative

- [ ] Route every editor write (strokes, images, pages, folders, notebooks) through the doc. Room
      only receives projected changes.
- [ ] Rebuild Room from the docs on demand (a debug action and a test).
- [ ] Android "share as PDF" uses the same projection as the PWA.
- [ ] EPUB import on Android with fixed layout parameters.

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
