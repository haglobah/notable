/*
 * Isolation between accounts is the property the whole server rests on. Every test here uses
 * real Repo clients over real WebSockets against a server on an ephemeral port.
 */
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import WebSocket from "ws"
import * as A from "@automerge/automerge"
import { cbor, parseAutomergeUrl, generateAutomergeUrl, type AutomergeUrl } from "@automerge/automerge-repo"
import { readLibrary } from "@notable/schema"
import { startHarness, userWithToken, clientRepo, tryFind, waitFor, shutdown, withTimeout, type Harness } from "./helpers.ts"

type Note = { text?: string; byB?: string; n?: number }

describe("sync isolation", () => {
  let h: Harness
  let tokenA: string
  let tokenB: string

  before(async () => {
    h = await startHarness()
    tokenA = await userWithToken(h, "alice")
    tokenB = await userWithToken(h, "bob")
  })
  after(async () => h.close())

  test("WebSocket upgrade without a session is rejected with 401", async () => {
    for (const headers of [{}, { authorization: "Bearer not-a-real-token" }, { cookie: "notable_session=nope" }]) {
      const status = await new Promise<number>(resolve => {
        const ws = new WebSocket(h.wsUrl, { headers })
        ws.on("unexpected-response", (_req, res) => resolve(res.statusCode ?? 0))
        ws.on("open", () => { ws.close(); resolve(101) })
        ws.on("error", () => {})
      })
      assert.equal(status, 401)
    }
  })

  test("two devices of one user sync, and a new doc is claimed by its first writer", async () => {
    const d1 = clientRepo(h, tokenA)
    const d2 = clientRepo(h, tokenA)
    const handle = d1.repo.create<Note>({ text: "hello" })
    const docId = parseAutomergeUrl(handle.url).documentId
    await waitFor(() => h.server.accounts.docOwner(docId), "server to claim the doc")
    assert.equal(h.server.accounts.docOwner(docId)?.username, "alice")

    const seen = await tryFind<Note>(d2.repo, handle.url)
    assert.notEqual(typeof seen, "string", "second device must find the doc")
    const h2 = seen as Exclude<typeof seen, string>
    assert.equal(h2.doc().text, "hello")

    h2.change(d => { d.n = 2 })
    await waitFor(() => handle.doc().n === 2, "change to reach the first device")
    await shutdown(d1.repo, d2.repo)
  })

  test("another user cannot fetch a doc by its URL", async () => {
    const a = clientRepo(h, tokenA)
    const handle = a.repo.create<Note>({ text: "secret" })
    const docId = parseAutomergeUrl(handle.url).documentId
    await waitFor(() => h.server.accounts.docOwner(docId), "claim")

    const b = clientRepo(h, tokenB)
    assert.equal(await tryFind(b.repo, handle.url), "unavailable")
    await shutdown(a.repo, b.repo)
  })

  test("asking for an unknown doc neither leaks nor claims it", async () => {
    const b = clientRepo(h, tokenB)
    const url = generateAutomergeUrl()
    assert.equal(await tryFind(b.repo, url), "unavailable")
    assert.equal(h.server.accounts.docOwner(parseAutomergeUrl(url).documentId), null)
    await shutdown(b.repo)
  })

  test("another user cannot push changes into a doc they do not own", async () => {
    const a = clientRepo(h, tokenA)
    const handle = a.repo.create<Note>({ text: "mine" })
    const { documentId } = parseAutomergeUrl(handle.url)
    await waitFor(() => h.server.accounts.docOwner(documentId), "claim")

    // Bob speaks the protocol by hand and sends a sync message for Alice's document id.
    let forged = A.change(A.init<Note>(), d => { d.byB = "injected" })
    const [, syncMsg] = A.generateSyncMessage(forged, A.initSyncState())
    const replies: string[] = []
    const ws = new WebSocket(h.wsUrl, { headers: { authorization: `Bearer ${tokenB}` } })
    await withTimeout(new Promise<void>((resolve, reject) => {
      ws.on("error", reject)
      ws.on("open", () => ws.send(cbor.encode({ type: "join", senderId: "bob-raw", peerMetadata: {}, supportedProtocolVersions: ["1"] })))
      ws.on("message", (data: Buffer) => {
        const msg = cbor.decode(new Uint8Array(data)) as { type: string; documentId?: string; senderId: string }
        replies.push(msg.type)
        if (msg.type === "peer") {
          ws.send(cbor.encode({ type: "sync", senderId: "bob-raw", targetId: msg.senderId, documentId, data: syncMsg }))
        }
        if (msg.type === "doc-unavailable") {
          assert.equal(msg.documentId, documentId)
          resolve()
        }
      })
    }), "doc-unavailable for a forged sync")
    ws.close()
    assert.ok(!replies.includes("sync"), "server must not answer with sync data")

    // A fresh device of Alice loads the server's copy: Bob's field must be absent.
    const a2 = clientRepo(h, tokenA)
    const seen = await tryFind<Note>(a2.repo, handle.url)
    assert.notEqual(typeof seen, "string")
    await new Promise(r => setTimeout(r, 300))
    assert.equal((seen as Exclude<typeof seen, string>).doc().byB, undefined)
    await shutdown(a.repo, a2.repo)
  })

  test("a peer id held by another user's live connection cannot be taken over", async () => {
    const a = clientRepo(h, tokenA, "shared-peer-id")
    const handle = a.repo.create<Note>({ text: "keep" })
    const { documentId } = parseAutomergeUrl(handle.url)
    await waitFor(() => h.server.accounts.docOwner(documentId), "claim")

    const reply = await withTimeout(new Promise<string>(resolve => {
      const ws = new WebSocket(h.wsUrl, { headers: { authorization: `Bearer ${tokenB}` } })
      ws.on("open", () => ws.send(cbor.encode({ type: "join", senderId: "shared-peer-id", peerMetadata: {}, supportedProtocolVersions: ["1"] })))
      ws.on("message", (data: Buffer) => resolve((cbor.decode(new Uint8Array(data)) as { type: string }).type))
      ws.on("close", () => resolve("closed"))
    }), "reply to a join with a taken peer id")
    assert.ok(reply === "error" || reply === "closed", `got ${reply}`)

    // Alice's connection still works.
    handle.change(d => { d.n = 42 })
    const a2 = clientRepo(h, tokenA)
    const seen = await tryFind<Note>(a2.repo, handle.url)
    await waitFor(() => (seen as Exclude<typeof seen, string>).doc().n === 42, "alice still syncs")
    await shutdown(a.repo, a2.repo)
  })

  test("reusing another user's peer id after they left gives no access to their docs", async () => {
    const a = clientRepo(h, tokenA, "recycled-peer")
    const handle = a.repo.create<Note>({ text: "private" })
    await waitFor(() => h.server.accounts.docOwner(parseAutomergeUrl(handle.url).documentId), "claim")
    await shutdown(a.repo)
    await new Promise(r => setTimeout(r, 100))

    const b = clientRepo(h, tokenB, "recycled-peer")
    assert.equal(await tryFind(b.repo, handle.url), "unavailable")
    await shutdown(b.repo)
  })

  test("the library doc is reachable by its owner only", async () => {
    const me = await (await fetch(`${h.base}/api/me`, { headers: { authorization: `Bearer ${tokenA}` } })).json() as { username: string; libraryDocUrl: AutomergeUrl }
    assert.equal(me.username, "alice")

    const a = clientRepo(h, tokenA)
    const lib = await tryFind<unknown>(a.repo, me.libraryDocUrl)
    assert.notEqual(typeof lib, "string")
    const library = readLibrary((lib as Exclude<typeof lib, string>).doc())
    assert.deepEqual(library.notebooks, {})

    const b = clientRepo(h, tokenB)
    assert.equal(await tryFind(b.repo, me.libraryDocUrl), "unavailable")
    await shutdown(a.repo, b.repo)
  })

  test("logout closes the session's open sync connections", async () => {
    const token = await userWithToken(h, "carol")
    const c = clientRepo(h, token)
    await c.adapter.whenReady()
    await waitFor(() => c.adapter.socket?.readyState === WebSocket.OPEN, "open socket")
    const res = await fetch(`${h.base}/api/logout`, { method: "POST", headers: { authorization: `Bearer ${token}` } })
    assert.equal(res.status, 204)
    await waitFor(() => c.adapter.socket?.readyState === WebSocket.CLOSED, "socket closed after logout")
    await shutdown(c.repo)
  })
})
