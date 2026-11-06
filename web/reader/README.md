# @notable/reader

Reading Notable's documents in a browser, without a UI framework: what the PWA (`web/app`) reads
with, and what Flow (the owner's notes app, `~/projects/hotlustre`) reads them with. A page looks
the same in both because both draw it with this code.

- `geometry.ts`: where things sit on a page (Android canvas px → page), page backgrounds, pens.
- `draw.ts`, `paint.ts`: templates, images and strokes on a canvas; a page's height and pixels,
  its background and ink (`paintBackground`, `paintInk`), a library cover.
- `client.ts` + `worker.ts`: MuPDF (page sizes, page bitmaps, the projection) in a worker.
  `createReader({worker, blobUrl})` on the page; `servePdfWorker(self, blobUrl)` in the app's own
  worker entry. Each app says how to start its worker and where its blobs are (the PWA:
  `/api/blobs/<sha256>`).
- `download.ts` (page) and `project.ts` (worker): Download PDF, the notebook as an ordinary PDF
  with `/Ink` annotations (ADR-001 "PDF as a projection").

## Outside this repo

Bun and npm can't install a subdirectory of a git repo, so the repo root's `package.json`
(`@notable/web`) exposes `./schema`, `./reader`, `./reader/worker` and `./reader/project`. An app
installs the repo at a commit (`bun add @notable/web@github:haglobah/notable#<sha>`) and resolves
`@notable/schema`, which these modules import by workspace name, to
`node_modules/@notable/web/web/schema/src/index.ts` (a bundler alias and a tsconfig `paths`
entry). Changing an export here means updating the pin there.

## Commands

```
npm test             # node:test: geometry, covers, the projection (qpdf/poppler checks with them on PATH)
npm run typecheck
npm run projection-fixture   # rewrites web/schema/fixtures/projection-v1.json; only for an intentional change
```
