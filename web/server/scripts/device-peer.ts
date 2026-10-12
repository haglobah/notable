/*
 * A second device for on-device checks of the Android client: reads an account's docs from the
 * live server, or adds a stroke as another device would.
 *
 *   NOTABLE_SERVER=https://notable.hagenlocher.me NOTABLE_USER=... NOTABLE_PASSWORD=... \
 *     node server/scripts/device-peer.ts dump
 *   ... node server/scripts/device-peer.ts add-stroke <notebookId> <pageId> <x> <y>
 *   ... node server/scripts/device-peer.ts blob <sha256>
 *   ... node server/scripts/device-peer.ts delete-page <notebookId> <pageId>
 *   ... node server/scripts/device-peer.ts move-page <notebookId> <pageId> <index>
 *   ... node server/scripts/device-peer.ts delete-notebook <notebookId>
 *   ... node server/scripts/device-peer.ts rename-folder <folderId> <title>
 *
 * `dump` prints one JSON object: the library entries and, per page, stroke ids with their bbox
 * and image ids with their blob. Coordinates are in doc units (canvasWidth wide).
 */
import { Repo, type AutomergeUrl, type DocHandle } from "@automerge/automerge-repo"
import { ImmutableString } from "@automerge/automerge"
import { readLibrary, readNotebook, strokeValue, deletePage, markPageLive } from "@notable/schema"
import { HeaderClientAdapter } from "../test/helpers.ts"

const env = (k: string) => process.env[k] ?? (() => { throw new Error(`set ${k}`) })()
const server = env("NOTABLE_SERVER").replace(/\/$/, "")

async function login() {
  const res = await fetch(`${server}/api/login`, {
    method: "POST",
    headers: { "content-type": "application/json", origin: server },
    body: JSON.stringify({ username: env("NOTABLE_USER"), password: env("NOTABLE_PASSWORD") }),
  })
  if (res.status !== 200) throw new Error(`login: ${res.status} ${await res.text()}`)
  const { token } = (await res.json()) as { token: string }
  const me = await fetch(`${server}/api/me`, { headers: { authorization: `Bearer ${token}` } })
  const { libraryDocUrl } = (await me.json()) as { libraryDocUrl: string }
  return { token, libraryDocUrl: libraryDocUrl as AutomergeUrl }
}

async function find<T>(repo: Repo, url: AutomergeUrl): Promise<DocHandle<T>> {
  for (let i = 0; i < 20; i++) {
    try {
      return await repo.find<T>(url, { signal: AbortSignal.timeout(10_000) })
    } catch {
      await new Promise(r => setTimeout(r, 500))
    }
  }
  throw new Error(`server never sent ${url}`)
}

/** SB2, version 2, x/y only, no compression (data/db/StrokePointConverter.kt). */
function encodePoints(pts: { x: number; y: number }[]): Uint8Array {
  const poly = (vs: number[]) => {
    let prev = 0
    let out = ""
    for (const v of vs) {
      const iv = Math.trunc(v * 100)
      let d = iv - prev
      prev = iv
      d = d < 0 ? ~(d << 1) : d << 1
      while (d >= 32) { out += String.fromCharCode(((d & 31) | 0x20) + 63); d >>= 5 }
      out += String.fromCharCode(d + 63)
    }
    return new TextEncoder().encode(out)
  }
  const xs = poly(pts.map(p => p.x)), ys = poly(pts.map(p => p.y))
  const buf = new Uint8Array(9 + 4 + xs.length + 4 + ys.length)
  const v = new DataView(buf.buffer)
  buf.set([0x53, 0x42, 2, 0], 0)
  v.setInt32(4, pts.length, true)
  buf[8] = 0
  v.setInt32(9, xs.length, true)
  buf.set(xs, 13)
  v.setInt32(13 + xs.length, ys.length, true)
  buf.set(ys, 17 + xs.length)
  return buf
}

/** Lets the repo send local changes before shutdown. */
const sent = () => new Promise(r => setTimeout(r, 3000))

async function main() {
  const [cmd, ...args] = process.argv.slice(2)
  const { token, libraryDocUrl } = await login()
  if (cmd === "blob") {
    const res = await fetch(`${server}/api/blobs/${args[0]}`, { headers: { authorization: `Bearer ${token}` } })
    const bytes = new Uint8Array(await res.arrayBuffer())
    const sha = Buffer.from(await crypto.subtle.digest("SHA-256", bytes)).toString("hex")
    console.log(JSON.stringify({ status: res.status, bytes: bytes.length, sha256: sha, matches: sha === args[0] }))
    return
  }
  const wsUrl = server.replace(/^http/, "ws") + "/sync"
  const repo = new Repo({ network: [new HeaderClientAdapter(wsUrl, { authorization: `Bearer ${token}` })] })
  try {
    const lib = readLibrary((await find(repo, libraryDocUrl)).doc())
    if (cmd === "dump") {
      const out: Record<string, unknown> = { library: libraryDocUrl, folders: Object.fromEntries(Object.values(lib.folders).map(f => [f.id, { title: f.title, parentId: f.parentId }])), notebooks: {} }
      for (const e of Object.values(lib.notebooks)) {
        const nb = readNotebook((await find(repo, e.docUrl as AutomergeUrl)).doc())
        ;(out.notebooks as Record<string, unknown>)[e.id] = {
          title: e.title, folderId: e.folderId, kind: e.kind, docUrl: e.docUrl, canvasWidth: nb.canvasWidth, defaultBackground: nb.defaultBackground, pageOrder: nb.pageOrder,
          pages: Object.fromEntries(Object.values(nb.pages).map(p => [p.id, { background: p.background,
            strokes: Object.values(p.strokes).map(s => ({ id: s.id, bbox: s.bbox })),
            images: Object.values(p.images).map(i => ({ id: i.id, blob: i.blob, rect: i.rect })),
          }])),
        }
      }
      console.log(JSON.stringify(out, null, 1))
    } else if (cmd === "delete-page" || cmd === "move-page") {
      const [notebookId, pageId, index] = args
      const entry = lib.notebooks[notebookId] ?? (() => { throw new Error(`no notebook ${notebookId}`) })()
      const handle = await find<any>(repo, entry.docUrl as AutomergeUrl)
      const at = handle.doc().pageOrder.findIndex((p: unknown) => String(p) === pageId)
      if (at < 0) throw new Error(`page ${pageId} not in ${notebookId}'s pageOrder`)
      handle.change((d: any) => {
        if (cmd === "delete-page") deletePage(d, pageId) // marked deleted, content kept (ADR-001)
        else {
          d.pageOrder.deleteAt(at)
          d.pageOrder.insertAt(Math.min(Number(index), d.pageOrder.length), new ImmutableString(pageId))
        }
      })
      await sent()
      console.log(JSON.stringify({ [cmd]: pageId, notebookId, pageOrder: readNotebook(handle.doc()).pageOrder }))
    } else if (cmd === "delete-notebook" || cmd === "rename-folder") {
      const handle = await find<any>(repo, libraryDocUrl)
      const [id, title] = args
      handle.change((d: any) => {
        if (cmd === "delete-notebook") {
          if (!d.notebooks[id]) throw new Error(`no notebook ${id}`)
          delete d.notebooks[id]
        } else {
          if (!d.folders[id]) throw new Error(`no folder ${id}`)
          d.folders[id].title = new ImmutableString(title)
          d.folders[id].updatedAt = new Date()
        }
      })
      await sent()
      console.log(JSON.stringify({ [cmd]: id, title }))
    } else if (cmd === "add-stroke") {
      const [notebookId, pageId, xs, ys] = args
      const x = Number(xs), y = Number(ys)
      const entry = lib.notebooks[notebookId] ?? (() => { throw new Error(`no notebook ${notebookId}`) })()
      const handle = await find<any>(repo, entry.docUrl as AutomergeUrl)
      const pts = Array.from({ length: 41 }, (_, i) => ({ x: x + i * 10, y: y + (i % 2 ? 40 : 0) }))
      const id = `remote-${Date.now().toString(36)}`
      const now = new Date()
      handle.change((d: any) => {
        d.pages[pageId].strokes[id] = strokeValue({
          id, pen: "BALLPEN", color: 0xff000000 | 0, size: 4,
          bbox: { left: x - 4, top: y - 4, right: x + 404, bottom: y + 44 },
          points: encodePoints(pts), createdAt: now, updatedAt: now,
        })
        markPageLive(d, pageId) // a stroke on a page deleted elsewhere brings it back
      })
      await sent()
      console.log(JSON.stringify({ added: id, notebookId, pageId }))
    } else {
      throw new Error(`unknown command ${cmd}; use dump, add-stroke, blob, delete-page, move-page, delete-notebook, rename-folder`)
    }
  } finally {
    await repo.shutdown()
  }
}

main().then(() => process.exit(0), e => { console.error(e); process.exit(1) })
