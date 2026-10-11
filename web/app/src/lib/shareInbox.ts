/* Files shared to Notable from Android's share sheet, parked by the service worker (public/sw.js). */

export type Shared = { file: File; remove: () => Promise<void> }

export async function takeSharedFiles(): Promise<Shared[]> {
  if (!("caches" in self)) return []
  const inbox = await caches.open("share-inbox")
  const out: Shared[] = []
  for (const request of await inbox.keys()) {
    const res = await inbox.match(request)
    if (!res) continue
    const name = decodeURIComponent(res.headers.get("x-file-name") ?? "shared file")
    const file = new File([await res.blob()], name, { type: res.headers.get("content-type") ?? "" })
    out.push({ file, remove: async () => { await inbox.delete(request) } })
  }
  return out
}
