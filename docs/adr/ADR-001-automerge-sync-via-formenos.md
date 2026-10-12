# ADR-001: Replace WebDAV sync with Automerge sync through formenos

**Date:** 2026-10-10

## Context

Notable synced through WebDAV (its docs, `docs/webdav-sync-*.md`, were removed with it in Phase 5). Several factors make that weak:

- **Whole-page files, last writer wins.** Reconciliation compares `Notebook.updatedAt`. Concurrent
  edits to one page become a `CONFLICT` badge that the user must resolve by hand.
- **No atomic snapshot.** Pages and the manifest upload separately. An interrupted sync can expose
  half-written state, and missing media is tolerated by design.
- **WebDAV is a dumb store.** No device can reach the library without a Notable install. A phone
  cannot upload a PDF, and a browser cannot read a notebook.
- **Friction around PDFs.** Annotations live in Room, apart from the PDF. Getting an annotated PDF
  means an explicit export step, and a downloaded file is not guaranteed to be a plain,
  self-contained PDF.

Requirements from Beat:

1. Sync uses a CRDT. Merges happen automatically, with no conflict dialogs.
2. A highly available peer runs on `formenos` (noldor clan, `~/noldor`).
3. A PWA works on Android and iOS. It reads everything Notable can read, uploads files, and
   downloads them. It does not annotate.
4. Every download is a PDF that opens anywhere and already shows the annotations. There is no
   export step and no choice to make.
5. The app has its own login and accounts, independent of Kanidm, so others can self-host it.
6. The new sync replaces WebDAV sync.

Constraint: `formenos` is a 4 GB VPS that already runs Kanidm, Paperless, Immich, Audiobookshelf
and the metrics stack. Server work must stay small.

## Decision

### CRDT: Automerge

- Android uses `automerge-java`. The PWA uses `@automerge/automerge-repo`, stored in IndexedDB.
  `formenos` runs an automerge-repo sync server over WebSocket.
- Why not Yjs: there is no maintained Kotlin/JVM binding.
- Why not Loro: its JVM binding is less mature, and we don't need its movable-tree type.

### Documents

- One **library doc** holds the folder tree and the notebook index (id, title, folder, cover,
  source blob).
- One **notebook doc** per notebook holds the page list, per-page background, strokes and images.
- **Strokes are immutable map entries:**
  `strokes[strokeId] = { pen, color, size, bbox, points: bytes }`. Erasing deletes the key, and
  moving a stroke replaces its entry. Points stay in the existing compact binary encoding
  (`docs/database-structure.md`). The CRDT merges strokes as whole entries, never point by point.
  This keeps documents small.
- **Source files** (PDF, EPUB, images) are immutable, content-addressed blobs (`sha256`). They are
  uploaded over HTTPS to the sync server, which stores them on disk. Docs refer to them by hash and
  never contain the bytes.
- One schema version field per doc. The schema is defined once, with a shared golden fixture that
  both the Kotlin and TypeScript tests load.

### Source of truth

- Automerge docs are authoritative. Room becomes a local, derived index for fast editor queries.
  It can be rebuilt from the docs at any time.
- The editor's write path appends to the notebook doc. A projector then updates Room. There is
  never a second writable copy.

### PDF as a projection

- An annotated PDF is never stored. It is generated on demand from the source blob plus the
  notebook doc.
- The output keeps the original pages and adds strokes as real `/Ink` annotations, not flattened,
  so other readers show and can edit them. Notebooks without a PDF background render as vector
  PDF pages, as `ExportEngine` already does.
- The PDF embeds the Automerge snapshot and the notebook id as an attached file. Re-uploading that
  PDF reattaches it to the same notebook instead of creating a duplicate.
- The PWA has one action: **Download**, which always yields this PDF. No format picker.
- Generation runs on the client: MuPDF on Android, `mupdf.js` (WASM) in the PWA. The server only
  syncs and stores blobs.

### EPUB

- MuPDF opens EPUBs on both platforms. Each EPUB notebook stores its layout parameters (page width,
  height, font size) in the doc. Both platforms lay it out identically, so annotation coordinates
  stay valid.
- Changing the layout parameters would break existing annotations. They are fixed when the EPUB is
  imported.
- Downloading an EPUB notebook yields a PDF of that layout with the annotations.

### PWA

- Static SPA served by Caddy on `formenos`. A service worker caches the app and the docs and blobs
  opened recently, so reading works offline.
- On Android, the Web Share Target API adds the PWA to the share sheet. iOS has no share target,
  so uploads there go through the file picker.
- It renders PDF and EPUB with `mupdf.js` and draws strokes read-only. It never writes strokes.

### Login and accounts

- The sync server has its own accounts. Notable is meant to be useful to anyone who self-hosts it,
  so it does not depend on an external identity provider such as Kanidm.
- Accounts use username and password. Passwords are hashed with argon2id. Login returns a
  long-lived session token, which can be revoked on the server. The PWA keeps it in an `HttpOnly`
  cookie. Android keeps it in encrypted storage.
- Registration is closed by default. An admin creates accounts with a CLI command on the server, or
  an instance setting allows open sign-up.
- **Multi-user isolation is enforced on the server.** A plain automerge-repo sync server serves any
  doc to anyone who knows its id. The server therefore records an owner for every doc and blob.
  It refuses sync messages and blob requests for docs the session does not own. Each user's
  library doc is found through the account, not through a guessable id.
- Nothing is reachable without a login.
- Possible later additions: passkeys, and sharing a notebook with another account.

### Code layout and deployment

- The TypeScript code lives in this repo under `web/`, with `web/app` (PWA) and `web/server`
  (sync + blob server). The schema and fixtures sit next to the Kotlin code they must match.
- `web/flake.nix` builds the server and the PWA bundle. noldor adds a `modules/notable.nix` that
  imports them as a flake input. It runs the server as a systemd service and adds a Caddy vhost.
  It registers `/var/lib/notable` in `clan.core.state`, so borgbackup covers docs, blobs and the
  account database.
- The server is packaged as a NixOS module and a container image, so others can self-host it.

### Sync runs in the foreground only (2026-10-11)

- Onyx freezes Notable about 3 s after it leaves the foreground, even when Android reports it as
  exempt. Changes then wait until the app is opened again and arrive within seconds.
- Beat chose foreground-only sync: no foreground service and no permanent notification. Drawing
  happens with the app open, and the PWA shows whatever has reached the server.
- A foreground service stays possible later if the delay turns out to matter.

### Local-only mode and accounts on one device (2026-10-11)

- **Every device has one library, with or without an account.** It lives in
  `files/notable-sync/library/` (repo, outbox, queued files, `library.json`). Without an account
  it is the device's own: its library doc is made on the first start, Room is copied into it
  automatically, and every write goes to its docs first. Nothing is sent anywhere. There is no
  Room-only write path any more; a write waits until the library is open, and fails before
  touching Room if it can't open.
- **The first login attaches it.** The library is recorded as that account's, and its library doc
  becomes the account's (from `/me`). The copy of Room runs again into the account's library: a
  notebook with a doc here keeps that doc, so the copy only adds its entry, and sync uploads the
  doc under the same URL (the server gives it to the first account that syncs it). No notebook
  gets a second doc. The device's old library doc is never looked up again, so it never reaches
  the server.
- **A notebook the account already has** (same Room id, e.g. synced through WebDAV, or uploaded
  before a logout deleted the repo) keeps the account's doc. This device's pages, strokes and
  images that the account's doc lacks are added to it; nothing is deleted on either side. The
  account's title, folder and page order win. The device's own copy stays on the device, read
  from disk without the repo so it isn't uploaded.
- **A logout keeps the library.** It stays the account's and becomes the device's library without
  a connection; edits keep going to its docs and go up at the next login to that account.
- **A login to a different account is refused** while the library belongs to another one, with a
  message naming the owner. Merging silently would put one account's notebooks into another's;
  the other account also couldn't sync docs the first one owns on the server. Refusing loses
  nothing: the library, Room and the login of the owner stay as they were. The same holds for an
  account deleted and recreated under the same name (its library doc differs). Moving a library
  to another account would need an explicit action; there is none yet.
- Folders of accounts logged in before this change (`files/notable-sync/<hash>/`) are adopted as
  the library of the logged-in account on the first start.

### WebDAV

- WebDAV sync is removed once the new sync covers folders, notebooks, pages, strokes, images and
  backgrounds. Done in Phase 5 (2026-10-11): Room migration 37 → 38 drops its tables and stored
  settings; leftover WorkManager work is cancelled at start.
- A one-time migration reads the local Room data and writes the Automerge docs. It is idempotent
  and keyed by the existing UUIDs, so each device can run it safely.

## Consequences

Easier:

- Concurrent edits from the Boox and other devices merge with no conflict UI. Most of
  `NotebookReconciliationService` and `SyncConflictStrategy` go away.
- Every device, including the iPhone, can reach the library through the PWA.
- Downloads are always ordinary PDFs with visible, editable annotations. Their meaning does not
  depend on Notable.
- The server stays small: an automerge-repo node and a blob directory.

Harder:

- **Persistence refactor.** Making Automerge authoritative changes the editor's write path. Every
  place that writes strokes, pages or folders to Room must go through the doc. This is the largest
  and riskiest part.
- **Native dependency.** `automerge-java` ships JNI libraries for each ABI. A spike must confirm it
  runs on the Onyx devices (arm64, Android 11+) before anything else is built.
- **Rendering parity.** The PWA must draw Notable's pens (ballpoint, fountain, marker, pencil…)
  well enough to read. It doesn't need pixel parity, but `/Ink` annotations in the PDF only
  approximate pressure.
- **Document growth.** Automerge keeps history. Heavily erased notebooks grow. We may need periodic
  compaction (`save()` without history, or a fresh doc), and that has to be designed for ids that
  other devices still hold.
- **Bundle size.** `mupdf.js` is several MB of WASM. The first PWA load is heavy, though it's cached
  afterwards.
- **We own account security.** That covers password hashing, rate limiting on login, session
  revocation and an admin CLI. It's more code than delegating to an identity provider, but it keeps
  Notable self-contained.
- **Per-doc access control** sits in the sync server. Isolation between users depends on it, so
  it needs focused tests.
- **Foreground-only sync on Onyx.** The Boox shows remote changes a few seconds after Notable is
  opened, not while it sits in the background.
- **Coupling with upstream.** This fork diverges from `Ethran/notable`'s WebDAV sync. Upstream
  merges touching `sync/` will conflict.
- **Out of scope for now:** importing annotations made in other PDF readers back into Notable,
  sharing with other people, and annotating in the PWA.
