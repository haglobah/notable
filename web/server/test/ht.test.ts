import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { createServer, type Server } from "node:http"
import { startHarness, type Harness } from "./helpers.ts"
import { Accounts } from "../src/accounts.ts"
import { parseHtLinks } from "../src/config.ts"

/** Todos' Better Auth, as far as sign-in and sign-out go. */
async function fakeHt() {
  const users = new Map([
    ["dora@example.org", { id: "ht-dora", password: "dora's ht password", verified: true }],
    ["emil@example.org", { id: "ht-emil", password: "emil's ht password", verified: true }],
    ["new@example.org", { id: "ht-new", password: "unverified password", verified: false }],
  ])
  const signOuts: { cookie?: string; origin?: string }[] = []
  let down = false
  const server: Server = createServer((req, res) => {
    let body = ""
    req.on("data", c => (body += c))
    req.on("end", () => {
      if (down) return void res.writeHead(502).end()
      if (req.method === "POST" && req.url === "/api/auth/sign-in/email") {
        const { email, password } = JSON.parse(body) as { email: string; password: string }
        const key = email.toLowerCase()
        const u = users.get(key)
        if (!u || u.password !== password) return void res.writeHead(401, { "content-type": "application/json" }).end('{"code":"INVALID_EMAIL_OR_PASSWORD"}')
        if (!u.verified) return void res.writeHead(403, { "content-type": "application/json" }).end('{"code":"EMAIL_NOT_VERIFIED"}')
        res.writeHead(200, {
          "content-type": "application/json",
          "set-cookie": [`humane-shared.session_token=tok-${u.id}.sig; Path=/; HttpOnly`, "humane-shared.session_data=x; Path=/"],
        })
        return void res.end(JSON.stringify({ redirect: false, token: `tok-${u.id}`, user: { id: u.id, email: key, name: key } }))
      }
      if (req.method === "POST" && req.url === "/api/auth/sign-out") {
        signOuts.push({ cookie: req.headers.cookie, origin: req.headers.origin })
        return void res.writeHead(200, { "content-type": "application/json" }).end('{"success":true}')
      }
      res.writeHead(404).end()
    })
  })
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r))
  const { port } = server.address() as { port: number }
  return {
    url: `http://127.0.0.1:${port}`,
    users,
    signOuts,
    setDown: (v: boolean) => (down = v),
    close: () => new Promise<void>(r => server.close(() => r())),
  }
}

const loginAs = (h: Harness, username: string, password: string) =>
  fetch(`${h.base}/api/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username, password }) })

describe("humane tools sign-in", () => {
  let ht: Awaited<ReturnType<typeof fakeHt>>
  let h: Harness
  before(async () => {
    ht = await fakeHt()
    h = await startHarness({ htUrl: ht.url })
    await h.server.accounts.createUser("dora", "dora's notable password")
    h.server.accounts.setHtLinks(parseHtLinks("dora=Dora@Example.org"))
  })
  after(async () => {
    await h.close()
    await ht.close()
  })

  test("a linked account signs in with its HT email and password", async () => {
    const res = await loginAs(h, "dora@example.org", "dora's ht password")
    assert.equal(res.status, 200)
    const { token, username } = (await res.json()) as { token: string; username: string }
    assert.equal(username, "dora", "clients see the Notable account, as after a password login")
    const me = await fetch(`${h.base}/api/me`, { headers: { authorization: `Bearer ${token}` } })
    assert.equal(((await me.json()) as { username: string }).username, "dora")
  })

  test("the HT session is signed out again, with HT's own origin", async () => {
    await loginAs(h, "dora@example.org", "dora's ht password")
    const last = ht.signOuts.at(-1)!
    assert.equal(last.cookie, "humane-shared.session_token=tok-ht-dora.sig; humane-shared.session_data=x")
    assert.equal(last.origin, ht.url)
  })

  test("the Notable password still works", async () => {
    assert.equal((await loginAs(h, "dora", "dora's notable password")).status, 200)
  })

  test("after the first sign-in, HT's user id decides, not the email", async () => {
    ht.users.set("dora.new@example.org", ht.users.get("dora@example.org")!)
    ht.users.delete("dora@example.org")
    const res = await loginAs(h, "dora.new@example.org", "dora's ht password")
    assert.equal(res.status, 200)
    assert.equal(((await res.json()) as { username: string }).username, "dora")
  })

  test("a wrong HT password fails like a wrong Notable password", async () => {
    const res = await loginAs(h, "dora.new@example.org", "wrong")
    assert.equal(res.status, 401)
    assert.deepEqual(await res.json(), { error: "invalid username or password" })
  })

  test("an HT account without a linked Notable account is refused", async () => {
    const res = await loginAs(h, "emil@example.org", "emil's ht password")
    assert.equal(res.status, 403)
    assert.match(((await res.json()) as { error: string }).error, /emil@example.org has no Notable account/)
  })

  test("an unverified HT email is refused", async () => {
    assert.equal((await loginAs(h, "new@example.org", "unverified password")).status, 403)
  })

  test("HT being down fails email logins only", async () => {
    ht.setDown(true)
    try {
      assert.equal((await loginAs(h, "dora.new@example.org", "dora's ht password")).status, 503)
      assert.equal((await loginAs(h, "dora", "dora's notable password")).status, 200)
    } finally {
      ht.setDown(false)
    }
  })

  test("failed HT logins count toward the rate limit", async () => {
    for (let i = 0; i < 5; i++) await loginAs(h, "nobody@example.org", "wrong")
    const res = await loginAs(h, "nobody@example.org", "wrong")
    assert.equal(res.status, 429)
    assert.ok(Number(res.headers.get("retry-after")) > 0)
  })
})

describe("without humane tools", () => {
  test("an email is just an unknown username", async () => {
    const h = await startHarness()
    try {
      assert.equal((await loginAs(h, "dora@example.org", "anything")).status, 401)
    } finally {
      await h.close()
    }
  })
})

describe("NOTABLE_HT_LINKS", () => {
  const argon2 = { memory: 1024, passes: 1, parallelism: 1 }
  const linked = (acc: Accounts) =>
    Object.fromEntries(acc.listUsers().filter(u => u.htEmail).map(u => [u.username, u.htEmail]))

  test("parses name=email pairs and refuses anything ambiguous", () => {
    assert.deepEqual([...parseHtLinks(" Dora = Dora@Example.org , emil=e@x.org ")], [["dora", "dora@example.org"], ["emil", "e@x.org"]])
    assert.equal(parseHtLinks(undefined).size, 0)
    for (const bad of ["dora", "dora=not-an-email", "dora=a@x.org,dora=b@x.org", "dora=a@x.org,emil=a@x.org", "dora=a@x.org=b"]) {
      assert.throws(() => parseHtLinks(bad), /NOTABLE_HT_LINKS/, bad)
    }
  })

  test("is the whole set: listed accounts are linked, all others unlinked, unknown names reported", async () => {
    const acc = Accounts.open(":memory:", argon2)
    for (const name of ["dora", "emil", "fritz"]) await acc.createUser(name, "a long enough password")
    assert.deepEqual(acc.setHtLinks(parseHtLinks("dora=d@x.org,fritz=f@x.org")), [])
    assert.deepEqual(linked(acc), { dora: "d@x.org", fritz: "f@x.org" })
    assert.deepEqual(acc.setHtLinks(parseHtLinks("dora=f@x.org,emil=d@x.org,ghost=g@x.org")), ["ghost"], "emails can move between accounts")
    assert.deepEqual(linked(acc), { dora: "f@x.org", emil: "d@x.org" })
    acc.close()
  })

  test("an unchanged link keeps HT's user id across restarts; a changed one forgets it", async () => {
    const acc = Accounts.open(":memory:", argon2)
    await acc.createUser("dora", "a long enough password")
    acc.setHtLinks(parseHtLinks("dora=d@x.org"))
    assert.equal(acc.userForHt("ht-dora", "d@x.org")?.username, "dora")
    acc.setHtLinks(parseHtLinks("dora=d@x.org"))
    assert.equal(acc.userForHt("ht-dora", "renamed@x.org")?.username, "dora", "matched by id")
    acc.setHtLinks(parseHtLinks("dora=other@x.org"))
    assert.equal(acc.userForHt("ht-dora", "d@x.org"), null)
    acc.close()
  })

  test("the server applies the links when it starts", async () => {
    const h = await startHarness()
    await h.server.accounts.createUser("dora", "a long enough password")
    const stateDir = h.stateDir
    await h.server.close()
    const again = await startHarness({ stateDir, htLinks: parseHtLinks("dora=d@x.org") })
    try {
      assert.deepEqual(linked(again.server.accounts), { dora: "d@x.org" })
    } finally {
      await again.close()
    }
  })
})
