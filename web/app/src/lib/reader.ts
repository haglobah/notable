/* The PWA's document reader (@notable/reader): its MuPDF worker and its blobs, under /api/blobs. */
import { createReader } from "@notable/reader"

export const reader = createReader({
  worker: () => new Worker(new URL("./pdf.worker.ts", import.meta.url), { type: "module" }),
  blobUrl: hash => `/api/blobs/${hash}`,
})
