import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { createHash, createHmac } from "node:crypto"
import { createServer, type Server } from "node:http"
import { startHarness, type Harness } from "./helpers.ts"
import { accountsFromEnv, parseConnectClients, parseHtLinks } from "../src/config.ts"
import { verifyIdToken } from "../src/oidc.ts"

const SECRET = "notable-client-secret-0123456789abcdef"
const b64 = (v: unknown) => Buffer.from(JSON.stringify(v)).toString("base64url")
const sign = (claims: Record<string, unknown>, secret = SECRET) => {
  const head = `${b64({ alg: "HS256", typ: "JWT" })}.${b64(claims)}`
  return `${head}.${createHmac("sha256", secret).update(head).digest("base64url")}`
}

/** The accounts provider (ht's Better Auth OIDC plugin), as far as Notable uses it. */
async function fakeProvider() {
  /** Who is signed in at the provider in "the browser"; null: nobody. */
  let signedIn: { sub: string; email: string; verified: boolean } | null = { sub: "ht-dora", email: "Dora@Example.org", verified: true }
  const codes = new Map<string, { nonce: string; challenge: string; redirect: string; sub: string; email: string; verified: boolean }>()
  const tokens = new Map<string, { sub: string; email: string; verified: boolean }>()
  let n = 0
  const server: Server = createServer((req, res) => {
    let body = ""
    req.on("data", c => (body += c))
    req.on("end", () => {
      const url = new URL(req.url!, "http://x")
      if (url.pathname === "/api/auth/oauth2/authorize") {
        const q = url.searchParams
        const redirect = q.get("redirect_uri")!
        const back = new URL(redirect)
        back.searchParams.set("state", q.get("state")!)
        if (!signedIn) back.searchParams.set("error", "login_required")
        else {
          const code = `code-${++n}`
          codes.set(code, { nonce: q.get("nonce")!, challenge: q.get("code_challenge")!, redirect, ...signedIn })
          back.searchParams.set("code", code)
        }
        return void res.writeHead(302, { location: back.toString() }).end()
      }
      if (req.method === "POST" && url.pathname === "/api/auth/oauth2/token") {
        const p = new URLSearchParams(body)
        const c = codes.get(p.get("code") ?? "")
        codes.delete(p.get("code") ?? "")
        const verifierOk = c && createHash("sha256").update(p.get("code_verifier") ?? "").digest("base64url") === c.challenge
        if (!c || !verifierOk || p.get("client_secret") !== SECRET || p.get("client_id") !== "notable" || p.get("redirect_uri") !== c.redirect)
          return void res.writeHead(400, { "content-type": "application/json" }).end('{"error":"invalid_grant"}')
        const access = `at-${++n}`
        tokens.set(access, c)
        const idToken = sign({ sub: c.sub, aud: "notable", nonce: c.nonce, exp: Math.floor(Date.now() / 1000) + 600 })
        return void res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({ access_token: access, refresh_token: "rt", id_token: idToken, expires_in: 3600 }))
      }
      if (url.pathname === "/api/auth/oauth2/userinfo") {
        const t = tokens.get((req.headers.authorization ?? "").replace(/^Bearer /, ""))
        if (!t) return void res.writeHead(401).end()
        return void res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({ sub: t.sub, email: t.email, email_verified: t.verified }))
      }
      res.writeHead(404).end()
    })
  })
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r))
  return {
    url: `http://127.0.0.1:${(server.address() as { port: number }).port}`,
    setSignedIn: (v: typeof signedIn) => (signedIn = v),
    close: () => new Promise<void>(r => server.close(() => r())),
  }
}

const freePort = async () => {
  const s = createServer()
  await new Promise<void>(r => s.listen(0, "127.0.0.1", r))
  const port = (s.address() as { port: number }).port
  await new Promise<void>(r => s.close(() => r()))
  return port
}

/** Follows redirects by hand, carrying cookies per host, as a browser would. */
function browser() {
  const jar = new Map<string, Map<string, string>>()
  const cookieFor = (u: URL) => [...(jar.get(u.host) ?? new Map())].map(([k, v]) => `${k}=${v}`).join("; ")
  const keep = (u: URL, res: Response) => {
    for (const c of res.headers.getSetCookie()) {
      const [pair] = c.split(";")
      const [k, ...v] = pair.split("=")
      const m = jar.get(u.host) ?? new Map<string, string>()
      if (/Max-Age=0/.test(c)) m.delete(k)
      else m.set(k, v.join("="))
      jar.set(u.host, m)
    }
  }
  /** Goes to `url`, following redirects until `stop` matches (or a non-redirect); returns the last URL. */
  const go = async (url: string, stop: (u: URL) => boolean = () => false) => {
    let u = new URL(url)
    for (let i = 0; i < 10; i++) {
      if (i > 0 && stop(u)) return { url: u, res: null }
      const res = await fetch(u, { redirect: "manual", headers: { cookie: cookieFor(u) } })
      keep(u, res)
      const loc = res.headers.get("location")
      if (res.status !== 302 || !loc) return { url: u, res }
      u = new URL(loc, u)
    }
    throw new Error("too many redirects")
  }
  return { go, cookie: (host: string, name: string) => jar.get(host)?.get(name) ?? null, cookieFor }
}

describe("signing in through the humane.tools accounts provider", () => {
  let provider: Awaited<ReturnType<typeof fakeProvider>>
  let h: Harness
  const APP_CB = "http://127.0.0.1:9/flow/callback"
  before(async () => {
    provider = await fakeProvider()
    const port = await freePort()
    h = await startHarness({
      port,
      publicOrigin: `http://127.0.0.1:${port}`,
      accounts: { url: provider.url, clientSecret: SECRET },
      connectClients: parseConnectClients(`flow=${APP_CB}`),
    })
    await h.server.accounts.createUser("dora", "dora's notable password")
    h.server.accounts.setHtLinks(parseHtLinks("dora=dora@example.org"))
  })
  after(async () => {
    await h.close()
    await provider.close()
  })

  test("the login form learns that humane tools sign-in is on", async () => {
    const r = await (await fetch(`${h.base}/api/login-methods`)).json()
    assert.deepEqual(r, { password: true, humaneTools: true, connect: ["flow"] })
  })

  test("a browser signed in to humane tools gets a Notable session for the linked account", async () => {
    provider.setSignedIn({ sub: "ht-dora", email: "Dora@Example.org", verified: true })
    const b = browser()
    const { url } = await b.go(`${h.base}/api/oidc/start`, u => u.pathname === "/")
    assert.equal(url.pathname, "/")
    assert.equal(url.searchParams.get("login_error"), null)
    const me = await fetch(`${h.base}/api/me`, { headers: { cookie: b.cookieFor(new URL(h.base)) } })
    assert.equal(me.status, 200)
    assert.equal((await me.json()).username, "dora")
  })

  test("an account no link names gets no session, and says why", async () => {
    provider.setSignedIn({ sub: "ht-emil", email: "emil@example.org", verified: true })
    const b = browser()
    const { url } = await b.go(`${h.base}/api/oidc/start`, u => u.pathname === "/")
    assert.match(url.searchParams.get("login_error") ?? "", /no Notable account/)
    assert.equal(b.cookie(new URL(h.base).host, "notable_session"), null)
  })

  test("a callback the browser did not start is refused", async () => {
    provider.setSignedIn({ sub: "ht-dora", email: "dora@example.org", verified: true })
    const started = await fetch(`${h.base}/api/oidc/start`, { redirect: "manual" })
    const authorize = new URL(started.headers.get("location")!)
    // Another browser (no state cookie) lands on the callback with the first one's code.
    const atProvider = await fetch(authorize, { redirect: "manual" })
    const callback = new URL(atProvider.headers.get("location")!)
    const r = await fetch(callback, { redirect: "manual" })
    assert.match(new URL(r.headers.get("location")!, h.base).searchParams.get("login_error") ?? "", /expired or was started elsewhere/)
    assert.equal(r.headers.getSetCookie().some(c => c.startsWith("notable_session=")), false)
  })

  test("not signed in at humane tools, the sign-in says so", async () => {
    provider.setSignedIn(null)
    const { url } = await browser().go(`${h.base}/api/oidc/start`, u => u.pathname === "/")
    assert.match(url.searchParams.get("login_error") ?? "", /sign in to humane tools/)
  })

  describe("an app connecting (Flow)", () => {
    const verifier = "v".repeat(50)
    const challenge = createHash("sha256").update(verifier).digest("base64url")
    const connect = (extra: Record<string, string> = {}) =>
      `${h.base}/api/connect?${new URLSearchParams({ client: "flow", redirect_uri: APP_CB, state: "app-state", code_challenge: challenge, code_challenge_method: "S256", ...extra })}`
    const swap = (body: Record<string, string>) =>
      fetch(`${h.base}/api/connect/token`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) })

    test("gets a code at its redirect URL and swaps it once for a session token", async () => {
      provider.setSignedIn({ sub: "ht-dora", email: "dora@example.org", verified: true })
      const { url } = await browser().go(connect(), u => u.href.startsWith(APP_CB))
      assert.equal(url.searchParams.get("state"), "app-state")
      const code = url.searchParams.get("code")!
      assert.ok(code)
      const r = await swap({ code, code_verifier: verifier, redirect_uri: APP_CB, client: "flow" })
      assert.equal(r.status, 200)
      const { token, username } = await r.json()
      assert.equal(username, "dora")
      const me = await fetch(`${h.base}/api/me`, { headers: { authorization: `Bearer ${token}` } })
      assert.equal(me.status, 200)
      const again = await swap({ code, code_verifier: verifier, redirect_uri: APP_CB, client: "flow" })
      assert.equal(again.status, 400)
    })

    test("a code is worthless without the verifier", async () => {
      const { url } = await browser().go(connect(), u => u.href.startsWith(APP_CB))
      const r = await swap({ code: url.searchParams.get("code")!, code_verifier: "w".repeat(50), redirect_uri: APP_CB, client: "flow" })
      assert.equal(r.status, 400)
    })

    test("an unregistered app or redirect URL gets nothing, not even a redirect", async () => {
      for (const extra of [{ client: "evil" }, { redirect_uri: "http://127.0.0.1:9/elsewhere" }] as Record<string, string>[]) {
        const r = await fetch(connect(extra), { redirect: "manual" })
        assert.equal(r.status, 400)
        assert.equal(r.headers.get("location"), null)
      }
    })

    test("a refusal goes back to the app with its state", async () => {
      provider.setSignedIn({ sub: "ht-emil", email: "emil@example.org", verified: true })
      const { url } = await browser().go(connect(), u => u.href.startsWith(APP_CB))
      assert.equal(url.searchParams.get("error"), "access_denied")
      assert.equal(url.searchParams.get("state"), "app-state")
      assert.equal(url.searchParams.get("code"), null)
    })
  })
})

describe("the accounts provider's configuration", () => {
  test("both or neither, a long secret, and a public origin", () => {
    assert.equal(accountsFromEnv({}, null), null)
    assert.deepEqual(accountsFromEnv({ NOTABLE_ACCOUNTS_URL: "https://accounts.example.org", NOTABLE_OIDC_CLIENT_SECRET: SECRET }, "https://n.example.org"), { url: "https://accounts.example.org", clientSecret: SECRET })
    assert.throws(() => accountsFromEnv({ NOTABLE_OIDC_CLIENT_SECRET: SECRET }, "https://n.example.org"))
    assert.throws(() => accountsFromEnv({ NOTABLE_ACCOUNTS_URL: "https://accounts.example.org", NOTABLE_OIDC_CLIENT_SECRET: "short" }, "https://n.example.org"))
    assert.throws(() => accountsFromEnv({ NOTABLE_ACCOUNTS_URL: "http://accounts.example.org", NOTABLE_OIDC_CLIENT_SECRET: SECRET }, "https://n.example.org"))
    assert.throws(() => accountsFromEnv({ NOTABLE_ACCOUNTS_URL: "https://accounts.example.org/x", NOTABLE_OIDC_CLIENT_SECRET: SECRET }, "https://n.example.org"))
    assert.throws(() => accountsFromEnv({ NOTABLE_ACCOUNTS_URL: "https://accounts.example.org", NOTABLE_OIDC_CLIENT_SECRET: SECRET }, null))
  })

  test("connect clients: name=url, several URLs per app, https only", () => {
    assert.deepEqual([...parseConnectClients("flow=https://a.example/cb, flow=https://b.example/cb,other=http://localhost:3000/cb")],
      [["flow", ["https://a.example/cb", "https://b.example/cb"]], ["other", ["http://localhost:3000/cb"]]])
    assert.throws(() => parseConnectClients("flow=http://flow.example/cb"))
    assert.throws(() => parseConnectClients("https://flow.example/cb"))
  })

  test("ID tokens: signature, audience, expiry and nonce", async () => {
    const claims = { sub: "s", aud: "notable", nonce: "n", exp: Math.floor(Date.now() / 1000) + 60 }
    const ok = { clientId: "notable", clientSecret: SECRET, nonce: "n" }
    assert.deepEqual(await verifyIdToken(sign(claims), ok), { type: "ok", sub: "s" })
    assert.equal((await verifyIdToken(sign(claims, "x".repeat(40)), ok)).type, "invalid")
    assert.equal((await verifyIdToken(sign({ ...claims, aud: "mail" }), ok)).type, "invalid")
    assert.equal((await verifyIdToken(sign({ ...claims, exp: 1 }), ok)).type, "invalid")
    assert.equal((await verifyIdToken(sign({ ...claims, nonce: "m" }), ok)).type, "invalid")
  })
})
