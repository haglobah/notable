/*
 * @notable/reader: reading Notable's documents in a browser, without a UI framework. Where things
 * sit on a page (geometry.ts), drawing them (draw.ts, paint.ts), MuPDF in a worker (client.ts on
 * the page, worker.ts in the worker) and Download-as-PDF (project.ts, `@notable/reader/project`).
 * The PWA (web/app) reads with it, and so does Flow (~/projects/hotlustre's app).
 */
export * from "./geometry.ts"
export * from "./draw.ts"
export * from "./cover.ts"
export * from "./paint.ts"
export * from "./pdfProtocol.ts"
export { createReader, type Reader, type ReaderOptions } from "./client.ts"
export type { BlobUrl } from "./worker.ts"
