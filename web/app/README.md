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
- **MuPDF** (10 MB WASM) loads lazily, only for adding, reading and downloading. For reading and
  downloading it runs in a worker (`src/lib/pdf.worker.ts`). The worker imports MuPDF on first use:
  mupdf.js initializes with top-level await, and a module worker that is still awaiting drops the
  messages sent to it.
- **Reader** (`src/components/ReaderView.tsx`): pages in `pageOrder`, each with two canvases. The
  background is a MuPDF page bitmap, a native template or an image. The ink layer holds images and
  strokes (SB2, pressure pens vary width). Pages within about a screen of the viewport draw; the
  rest set their canvases to 0×0. The view re-reads the doc on every change, so ink from the Boox
  appears live.
- **Where ink goes** (`src/lib/geometry.ts`): stroke coordinates are Android canvas px. A PDF or
  EPUB page is scaled to the canvas width (`canvasWidth` in the doc, default 1860 for the Note Air
  5C), so 1 px = page width / canvas width pt, from the page's top-left. Native pages are A4 wide
  (595 pt) and as tall as their content, at least one screen, as in Android's PDF export.
- **Download PDF** (`src/lib/project.ts`), the only download: it grafts original PDF pages, lays out
  EPUB pages with the stored layout, and draws native pages as vectors. Strokes become `/Ink`
  annotations with appearance streams; page images become image stamps. The notebook doc
  (`notable.automerge`) and `notable.json` are embedded. Pages whose background can't be resolved
  are drawn blank and listed under `warnings` in `notable.json`. A missing blob fails the download
  with a message naming it.
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
client, the library helpers, the page geometry, and the PDF projection. The projection tests check
the output with MuPDF and, when `qpdf` and poppler are on PATH, independently with them; without
those tools those two tests are skipped and say why
(`nix shell nixpkgs#qpdf nixpkgs#poppler-utils -c npm test`). UI behavior was checked in Chrome. See the plan for what was
verified.
