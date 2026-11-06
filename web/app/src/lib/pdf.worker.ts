/// <reference lib="webworker" />
/* The PWA's MuPDF worker (@notable/reader): blobs from /api/blobs, through the service worker's cache. */
import { servePdfWorker } from "@notable/reader/worker"

servePdfWorker(self as unknown as DedicatedWorkerGlobalScope, hash => `/api/blobs/${hash}`)
