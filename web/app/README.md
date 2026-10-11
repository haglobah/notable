# @notable/app

The Notable PWA: log in, see the library as the Boox syncs it, and add PDFs and EPUBs from any
device ([ADR-001](../../docs/adr/ADR-001-automerge-sync-via-formenos.md), Phase 2). Solid + Vite.
The server in `../server` serves the build from the same origin.

## Run

```sh
npm run build            # → dist/, then:
NOTABLE_STATE_DIR=/tmp/notable NOTABLE_STATIC_DIR=$PWD/dist node ../server/src/main.ts
```

`npm run dev` runs Vite on its own port and proxies `/api` and `/sync` to a server on `:3030`.
The service worker registers only in production builds.

## How it works

- **Session:** the server's `HttpOnly` cookie. `/api/me` names the account and its library doc.
  The app remembers that (not the cookie) in `localStorage`, so the library opens offline.
- **Docs:** automerge-repo with IndexedDB storage, one database per account
  (`notable-<username>`), synced over `/sync`.
- **Adding a document** (`src/lib/upload.ts`): MuPDF opens it and counts its pages, refusing files it
  can't read. Then the app hashes the file in 4 MiB chunks, uploads it with progress, and only then
  writes the notebook doc and library entry. A failed upload leaves no entry behind. EPUBs are laid
  out at the Note Air 5C's physical page size (`EPUB_LAYOUT`, 446 × 595 pt, 12 pt).
- **MuPDF** (10 MB WASM) loads lazily, only for adding (and later reading and downloading).
- **Service worker** (`public/sw.js`): it precaches the app shell, using a list written in at build
  time. Navigations are network-first and fall back to the cached shell. `/assets/*` is
  cache-first. `/api/blobs/<sha256>` is cache-first because blobs never change. Nothing else under
  `/api` is cached.
- **Share target (Android):** the manifest's `share_target` POSTs to `/share-target`. The worker
  parks the files in the `share-inbox` cache and redirects to `/?share=received`, and the app adds
  them. iOS has no share target, so the file picker is the way in there.
- **Log out** revokes the session and deletes this device's IndexedDB database, blob cache and
  share inbox.

## Tests

`npm test` runs the upload pipeline against a real server on an ephemeral port with a second
client, plus the library helpers. UI behavior was checked in Chrome. See the plan for what was
verified.
