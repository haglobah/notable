import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { createHash, randomBytes } from "node:crypto"
import { existsSync } from "node:fs"
import { spawnSync } from "node:child_process"
import { join } from "node:path"
import { parseAutomergeUrl } from "@automerge/automerge-repo"
import { startHarness, login, userWithToken, clientRepo, waitFor, shutdown, tryFind, type Harness } from "./helpers.ts"
import { hashPassword, verifyPassword } from "../src/passwords.ts"
import { Accounts } from "../src/accounts.ts"

describe("passwords", () => {
  test("argon2id PHC string round-trips and rejects a wrong password", async () => {
    const params = { memory: 1024, passes: 1, parallelism: 1 }
    const phc = await hashPassword("hunter2hunter2", params)
    assert.match(phc, /^\$argon2id\$v=19\$m=1024,t=1,p=1\$[A-Za-z0-9+/]+\$[A-Za-z0-9+/]+$/)
    assert.equal(await verifyPassword(phc, "hunter2hunter2"), true)
    assert.equal(await verifyPassword(phc, "hunter3hunter3"), false)
  })
})

describe("schema migrations", () => {
  test("a fresh database ends at the latest user_version", () => {
    const acc = Accounts.open(":memory:", { memory: 1024, passes: 1, parallelism: 1 })
    const { user_version } = acc.db.prepare("PRAGMA user_version").get() as { user_version: number }
    assert.ok(user_version >= 1)
    acc.close()
  })
})

describe("admin CLI", () => {
  let h: Harness
  before(async () => { h = await startHarness() })
  after(async () => h.close())

  const cli = (args: string[], input = "") =>
    spawnSync(process.execPath, [join(import.meta.dirname, "../src/cli.ts"), ...args], {
      input, encoding: "utf8",
      env: { ...process.env, NOTABLE_STATE_DIR: h.stateDir, NOTABLE_ARGON2_MEMORY_KIB: "1024", NOTABLE_ARGON2_PASSES: "1" },
    })

  test("user add, list, passwd", async () => {
    const add = cli(["user", "add", "ivy"], "first password!\n")
    assert.equal(add.status, 0, add.stderr)
    assert.match(cli(["user", "list"]).stdout, /^ivy\b/m)
    await login(h, "ivy", "first password!")

    const pw = cli(["user", "passwd", "ivy"], "second password!\n")
    assert.equal(pw.status, 0, pw.stderr)
    await assert.rejects(login(h, "ivy", "first password!"))
    await login(h, "ivy", "second password!")
  })

  test("passwd revokes existing sessions", async () => {
    cli(["user", "add", "jack"], "jack password 1\n")
    const { token } = await login(h, "jack", "jack password 1")
    cli(["user", "passwd", "jack"], "jack password 2\n")
    assert.equal((await fetch(`${h.base}/api/me`, { headers: { authorization: `Bearer ${token}` } })).status, 401)
  })

  test("sessions revoke logs a user out everywhere", async () => {
    const token = await userWithToken(h, "kim")
    const r = cli(["sessions", "revoke", "kim"])
    assert.equal(r.status, 0, r.stderr)
    assert.equal((await fetch(`${h.base}/api/me`, { headers: { authorization: `Bearer ${token}` } })).status, 401)
  })

  test("user del removes access; the data is tombstoned and never claimable again", async () => {
    const token = await userWithToken(h, "leo")
    const c = clientRepo(h, token)
    const handle = c.repo.create({ text: "leo's" })
    const docId = parseAutomergeUrl(handle.url).documentId
    await waitFor(() => h.server.accounts.docOwner(docId), "claim")
    const blob = randomBytes(64)
    const hash = createHash("sha256").update(blob).digest("hex")
    await fetch(`${h.base}/api/blobs/${hash}`, { method: "PUT", headers: { authorization: `Bearer ${token}` }, body: blob })
    await shutdown(c.repo)

    const del = cli(["user", "del", "leo"], "leo\n")
    assert.equal(del.status, 0, del.stderr)
    assert.equal((await fetch(`${h.base}/api/me`, { headers: { authorization: `Bearer ${token}` } })).status, 401)
    assert.equal(h.server.accounts.docOwner(docId), null)
    assert.equal(h.server.accounts.isTombstoned(docId), true)

    // Someone else who learned the URL cannot claim it and get the server's copy.
    const otherToken = await userWithToken(h, "mia")
    const other = clientRepo(h, otherToken)
    assert.equal(await tryFind(other.repo, handle.url), "unavailable")
    await shutdown(other.repo)

    // The server deletes orphaned docs and blobs on its next garbage-collection pass.
    await h.server.collectGarbage()
    assert.equal(existsSync(join(h.stateDir, "blobs", hash.slice(0, 2), hash)), false)
  })

  test("unknown subcommand fails with usage", () => {
    const r = cli(["frobnicate"])
    assert.notEqual(r.status, 0)
    assert.match(r.stderr, /usage/i)
  })
})
