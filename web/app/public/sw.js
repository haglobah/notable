/*
 * Notable's service worker. The precache list and version are written in at build time
 * (vite.config.ts). /api is never cached, except blobs: they are addressed by their sha256, so a
 * cached copy can never be stale. Logging out deletes the blob cache.
 */
const PRECACHE = self.__PRECACHE__
const VERSION = self.__VERSION__
const SHELL = `shell-${VERSION}`
const ASSETS = "assets"
const BLOBS = "blobs"
const INBOX = "share-inbox"
const BLOB_PATH = /^\/api\/blobs\/[0-9a-f]{64}$/

self.addEventListener("install", event => {
  event.waitUntil(caches.open(SHELL).then(c => c.addAll(PRECACHE)).then(() => self.skipWaiting()))
})

self.addEventListener("activate", event => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) if (key.startsWith("shell-") && key !== SHELL) await caches.delete(key)
    await self.clients.claim()
  })())
})

async function cacheFirst(request, cacheName) {
  const hit = await caches.match(request)
  if (hit) return hit
  const res = await fetch(request)
  if (res.ok) (await caches.open(cacheName)).put(request, res.clone())
  return res
}

async function networkFirstShell(request) {
  try {
    return await fetch(request)
  } catch {
    return (await caches.match("/", { cacheName: SHELL })) ?? Response.error()
  }
}

/** Android's share sheet POSTs the files here; keep them until the app picks them up. */
async function receiveShare(request) {
  const form = await request.formData()
  const inbox = await caches.open(INBOX)
  let i = 0
  for (const file of form.getAll("file")) {
    if (!(file instanceof File)) continue
    await inbox.put(`/share-inbox/${Date.now()}-${i++}`, new Response(file, {
      headers: { "content-type": file.type || "application/octet-stream", "x-file-name": encodeURIComponent(file.name) },
    }))
  }
  return Response.redirect(i > 0 ? "/?share=received" : "/?share=empty", 303)
}

self.addEventListener("fetch", event => {
  const { request } = event
  const url = new URL(request.url)
  if (url.origin !== self.location.origin) return
  if (request.method === "POST" && url.pathname === "/share-target") return event.respondWith(receiveShare(request))
  if (request.method !== "GET") return
  if (BLOB_PATH.test(url.pathname)) return event.respondWith(cacheFirst(request, BLOBS))
  if (url.pathname.startsWith("/api/") || url.pathname === "/sync") return
  if (request.mode === "navigate") return event.respondWith(networkFirstShell(request))
  if (url.pathname.startsWith("/assets/") || PRECACHE.includes(url.pathname)) return event.respondWith(cacheFirst(request, ASSETS))
})
