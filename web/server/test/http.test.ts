import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { createHash, randomBytes } from "node:crypto"
import WebSocket from "ws"
import { startHarness, login, userWithToken, type Harness } from "./helpers.ts"

const sha = (b: Uint8Array) => createHash("sha256").update(b).digest("hex")
const bearer = (t: string) => ({ authorization: `Bearer ${t}` })

describe("accounts and sessions", () => {
  let h: Harness
  before(async () => { h = await startHarness({ publicOrigin: "https://notes.example" }) })
  after(async () => h.close())

  test("health needs no session", async () => {
    const res = await fetch(`${h.base}/api/health`)
    assert.equal(res.status, 200)
  })

  test("login sets an HttpOnly, SameSite=Strict, Secure cookie and returns a token", async () => {
    await h.server.accounts.createUser("dora", "a long enough password")
    const res = await fetch(`${h.base}/api/login`, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ username: "Dora", password: "a long enough password" }),
    })
    assert.equal(res.status, 200)
    const cookie = res.headers.get("set-cookie")!
    assert.match(cookie, /^notable_session=[A-Za-z0-9_-]{43};/)
    for (const attr of ["HttpOnly", "SameSite=Strict", "Secure", "Path=/"]) assert.ok(cookie.includes(attr), attr)
    const { token } = await res.json() as { token: string }
    assert.equal(cookie.split(";")[0], `notable_session=${token}`)
    assert.equal(h.server.accounts.sessionCountFor("dora"), 1)
  })

  test("only a hash of the session token is stored", async () => {
    const { token } = await login(h, "dora", "a long enough password")
    const rows = h.server.accounts.db.prepare("SELECT token_hash FROM sessions").all() as { token_hash: string }[]
    assert.ok(rows.every(r => r.token_hash !== token))
    assert.ok(rows.some(r => r.token_hash === createHash("sha256").update(token).digest("hex")))
  })

  test("wrong password and unknown user fail the same way", async () => {
    for (const body of [{ username: "dora", password: "wrong" }, { username: "nobody", password: "wrong" }]) {
      const res = await fetch(`${h.base}/api/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) })
      assert.equal(res.status, 401)
      assert.deepEqual(await res.json(), { error: "invalid username or password" })
    }
  })

  test("me works with bearer and with cookie (same origin)", async () => {
    const { token, cookie } = await login(h, "dora", "a long enough password")
    const r1 = await fetch(`${h.base}/api/me`, { headers: bearer(token) })
    assert.equal(r1.status, 200)
    const body = (await r1.json()) as { username: string; maxBlobBytes: number }
    assert.equal(body.username, "dora")
    assert.equal(body.maxBlobBytes, 8 * 1024 * 1024, "clients check the size limit before uploading")
    const r2 = await fetch(`${h.base}/api/me`, { headers: { cookie } })
    assert.equal(r2.status, 200)
    assert.equal((await fetch(`${h.base}/api/me`)).status, 401)
  })

  test("cookie-authenticated state changes require the public origin", async () => {
    const { cookie } = await login(h, "dora", "a long enough password")
    const foreign = await fetch(`${h.base}/api/logout`, { method: "POST", headers: { cookie, origin: "https://evil.example" } })
    assert.equal(foreign.status, 403)
    const missing = await fetch(`${h.base}/api/logout`, { method: "POST", headers: { cookie } })
    assert.equal(missing.status, 403)
    const ok = await fetch(`${h.base}/api/logout`, { method: "POST", headers: { cookie, origin: "https://notes.example" } })
    assert.equal(ok.status, 204)
    assert.equal((await fetch(`${h.base}/api/me`, { headers: { cookie } })).status, 401)
  })

  test("cookie-authenticated WebSocket upgrade requires the public origin", async () => {
    const { cookie } = await login(h, "dora", "a long enough password")
    const status = (origin: string) => new Promise<number>(resolve => {
      const ws = new WebSocket(h.wsUrl, { headers: { cookie, origin } })
      ws.on("unexpected-response", (_q, res) => resolve(res.statusCode ?? 0))
      ws.on("open", () => { ws.close(); resolve(101) })
      ws.on("error", () => {})
    })
    assert.equal(await status("https://evil.example"), 403)
    assert.equal(await status("https://notes.example"), 101)
  })

  test("logout revokes the bearer token", async () => {
    const { token } = await login(h, "dora", "a long enough password")
    assert.equal((await fetch(`${h.base}/api/logout`, { method: "POST", headers: bearer(token) })).status, 204)
    assert.equal((await fetch(`${h.base}/api/me`, { headers: bearer(token) })).status, 401)
  })

  test("signup is closed by default", async () => {
    const res = await fetch(`${h.base}/api/signup`, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ username: "eve", password: "a long enough password" }),
    })
    assert.equal(res.status, 404)
  })
})

describe("signup when open", () => {
  let h: Harness
  before(async () => { h = await startHarness({ openSignup: true }) })
  after(async () => h.close())

  test("creates the account and a session; duplicate names are refused", async () => {
    const body = JSON.stringify({ username: "frank", password: "a long enough password" })
    const res = await fetch(`${h.base}/api/signup`, { method: "POST", headers: { "content-type": "application/json" }, body })
    assert.equal(res.status, 201)
    const { token } = await res.json() as { token: string }
    assert.equal((await fetch(`${h.base}/api/me`, { headers: bearer(token) })).status, 200)
    const dup = await fetch(`${h.base}/api/signup`, { method: "POST", headers: { "content-type": "application/json" }, body })
    assert.equal(dup.status, 409)
  })

  test("weak input is refused", async () => {
    for (const body of [{ username: "x y", password: "a long enough password" }, { username: "gina", password: "short" }]) {
      const res = await fetch(`${h.base}/api/signup`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) })
      assert.equal(res.status, 400)
    }
  })
})

describe("login rate limit", () => {
  let h: Harness
  before(async () => { h = await startHarness() })
  after(async () => h.close())

  test("repeated failures for one username are throttled, even with the right password", async () => {
    await h.server.accounts.createUser("hank", "a long enough password")
    const attempt = (password: string) => fetch(`${h.base}/api/login`, {
      method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username: "hank", password }),
    })
    for (let i = 0; i < 5; i++) assert.equal((await attempt("wrong")).status, 401)
    const blocked = await attempt("a long enough password")
    assert.equal(blocked.status, 429)
    assert.ok(Number(blocked.headers.get("retry-after")) > 0)
  })
})

describe("blobs", () => {
  let h: Harness
  let tokenA: string
  let tokenB: string
  before(async () => {
    h = await startHarness({ maxBlobBytes: 1024 * 1024 })
    tokenA = await userWithToken(h, "ann")
    tokenB = await userWithToken(h, "ben")
  })
  after(async () => h.close())

  const put = (token: string, hash: string, body: Buffer) =>
    fetch(`${h.base}/api/blobs/${hash}`, { method: "PUT", headers: { ...bearer(token), "content-type": "application/octet-stream" }, body: new Uint8Array(body) })

  test("upload, download, and HEAD by sha256", async () => {
    const data = randomBytes(200_000)
    const hash = sha(data)
    assert.equal((await put(tokenA, hash, data)).status, 201)
    const get = await fetch(`${h.base}/api/blobs/${hash}`, { headers: bearer(tokenA) })
    assert.equal(get.status, 200)
    assert.equal(get.headers.get("content-length"), String(data.length))
    assert.match(get.headers.get("cache-control")!, /immutable/)
    assert.deepEqual(new Uint8Array(await get.arrayBuffer()), new Uint8Array(data))
    const head = await fetch(`${h.base}/api/blobs/${hash}`, { method: "HEAD", headers: bearer(tokenA) })
    assert.equal(head.status, 200)
    assert.equal(head.headers.get("content-length"), String(data.length))
  })

  test("a hash mismatch is rejected and nothing is stored", async () => {
    const data = randomBytes(1000)
    const wrong = sha(randomBytes(10))
    assert.equal((await put(tokenA, wrong, data)).status, 400)
    assert.equal((await fetch(`${h.base}/api/blobs/${wrong}`, { headers: bearer(tokenA) })).status, 404)
  })

  test("another user's blob is indistinguishable from a missing one", async () => {
    const data = randomBytes(500)
    const hash = sha(data)
    await put(tokenA, hash, data)
    const theirs = await fetch(`${h.base}/api/blobs/${hash}`, { headers: bearer(tokenB) })
    const missing = await fetch(`${h.base}/api/blobs/${sha(randomBytes(5))}`, { headers: bearer(tokenB) })
    assert.equal(theirs.status, 404)
    assert.equal(missing.status, 404)
  })

  test("uploading identical content gives the second user access without a second copy", async () => {
    const data = randomBytes(700)
    const hash = sha(data)
    await put(tokenA, hash, data)
    assert.equal((await put(tokenB, hash, data)).status, 201)
    assert.equal((await fetch(`${h.base}/api/blobs/${hash}`, { headers: bearer(tokenB) })).status, 200)
  })

  test("over the size limit is refused", async () => {
    const data = randomBytes(1024 * 1024 + 1)
    assert.equal((await put(tokenA, sha(data), data)).status, 413)
  })

  test("blobs need a session and a valid hash", async () => {
    assert.equal((await fetch(`${h.base}/api/blobs/${"a".repeat(64)}`)).status, 401)
    assert.equal((await fetch(`${h.base}/api/blobs/../../etc/passwd`, { headers: bearer(tokenA) })).status, 404)
    assert.equal((await fetch(`${h.base}/api/blobs/ABC`, { headers: bearer(tokenA) })).status, 404)
  })
})
