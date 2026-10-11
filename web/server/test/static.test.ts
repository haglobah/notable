import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import net from "node:net"
import { startHarness, type Harness } from "./helpers.ts"

describe("serving the PWA", () => {
  let h: Harness
  let dir: string
  before(async () => {
    dir = mkdtempSync(join(tmpdir(), "notable-static-"))
    mkdirSync(join(dir, "assets"))
    writeFileSync(join(dir, "index.html"), "<!doctype html><title>Notable</title>")
    writeFileSync(join(dir, "sw.js"), "self.addEventListener('fetch', () => {})")
    writeFileSync(join(dir, "manifest.webmanifest"), "{}")
    writeFileSync(join(dir, "assets", "index-AbC123.js"), "console.log(1)")
    writeFileSync(join(dir, "assets", "mupdf-wasm-XyZ.wasm"), new Uint8Array([0, 97, 115, 109]))
    h = await startHarness({ staticDir: dir })
  })
  after(async () => {
    await h.close()
    rmSync(dir, { recursive: true, force: true })
  })

  const get = (path: string, init: RequestInit = {}) => fetch(`${h.base}${path}`, { redirect: "manual", ...init })

  test("the app shell is never cached, and pages get a strict CSP", async () => {
    const res = await get("/")
    assert.equal(res.status, 200)
    assert.match(res.headers.get("content-type")!, /^text\/html/)
    assert.equal(res.headers.get("cache-control"), "no-cache")
    const csp = res.headers.get("content-security-policy")!
    assert.match(csp, /default-src 'self'/)
    assert.match(csp, /'wasm-unsafe-eval'/, "MuPDF and Automerge are WASM")
    assert.doesNotMatch(csp, /'unsafe-eval'/)
    assert.equal(await res.text(), "<!doctype html><title>Notable</title>")
  })

  test("client routes fall back to index.html", async () => {
    for (const path of ["/n/1f0c", "/f/abc", "/login"]) {
      const res = await get(path)
      assert.equal(res.status, 200, path)
      assert.equal(await res.text(), "<!doctype html><title>Notable</title>")
    }
  })

  test("hashed assets are immutable and typed", async () => {
    const js = await get("/assets/index-AbC123.js")
    assert.equal(js.status, 200)
    assert.match(js.headers.get("content-type")!, /^text\/javascript/)
    assert.equal(js.headers.get("cache-control"), "public, max-age=31536000, immutable")
    const wasm = await get("/assets/mupdf-wasm-XyZ.wasm")
    assert.equal(wasm.headers.get("content-type"), "application/wasm")
  })

  test("the service worker and manifest are revalidated on every load", async () => {
    const sw = await get("/sw.js")
    assert.equal(sw.headers.get("cache-control"), "no-cache")
    assert.match(sw.headers.get("content-type")!, /^text\/javascript/)
    const manifest = await get("/manifest.webmanifest")
    assert.equal(manifest.headers.get("content-type"), "application/manifest+json")
  })

  test("missing files with an extension are 404, not the app", async () => {
    assert.equal((await get("/assets/gone-123.js")).status, 404)
    assert.equal((await get("/favicon.ico")).status, 404)
  })

  test("API paths never fall back to the app", async () => {
    const res = await get("/api/nope")
    assert.equal(res.status, 404)
    assert.match(res.headers.get("content-type")!, /json/)
  })

  test("paths cannot leave the static directory", async () => {
    for (const path of ["/..%2f..%2fetc%2fpasswd", "/%2e%2e/%2e%2e/etc/passwd", "/assets/..%2f..%2fnotable.db"]) {
      // fetch resolves "%2e%2e" segments itself, so some of these arrive as "/etc/passwd" and get
      // the app shell; what matters is that no file outside the directory is ever returned.
      const res = await get(path)
      const body = await res.text()
      assert.ok(res.status === 404 || body === "<!doctype html><title>Notable</title>", `${path} → ${res.status}`)
      assert.doesNotMatch(body, /root:|SQLite/)
    }
  })

  /** Sends the path byte for byte; fetch would normalize "..". */
  const raw = (path: string) =>
    new Promise<string>((resolve, reject) => {
      const s = net.connect(h.server.port, "127.0.0.1", () => s.write(`GET ${path} HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n`))
      let out = ""
      s.on("data", d => (out += d))
      s.on("end", () => resolve(out))
      s.on("error", reject)
    })

  test("unnormalized dot-dot paths are refused by the server itself", async () => {
    // Literal ".." is resolved by URL parsing before lookup, so it lands on the app shell.
    for (const path of ["/../../../../etc/passwd", "/assets/../../../../etc/passwd", "/%2e%2e/%2e%2e/%2e%2e/%2e%2e/etc/passwd"]) {
      const out = await raw(path)
      assert.doesNotMatch(out, /root:/, path)
      assert.ok(out.endsWith("<!doctype html><title>Notable</title>") || / 404 /.test(out.split("\r\n")[0]), path)
    }
    // Encoded slashes survive URL parsing and decode to ".." here; the directory guard refuses them.
    for (const path of ["/..%2f..%2f..%2f..%2fetc%2fpasswd", "/assets/..%2f..%2f..%2f..%2fetc%2fpasswd"]) {
      const out = await raw(path)
      assert.match(out.split("\r\n")[0], / 404 /, path)
      assert.doesNotMatch(out, /root:/, path)
    }
  })

  test("HEAD answers without a body", async () => {
    const res = await get("/", { method: "HEAD" })
    assert.equal(res.status, 200)
    assert.equal(await res.text(), "")
  })

  test("a share that arrives before the service worker is installed is sent back to the app", async () => {
    const res = await get("/share-target", { method: "POST", body: new FormData() })
    assert.equal(res.status, 303)
    assert.equal(res.headers.get("location"), "/?share=unavailable")
  })
})

test("without a static directory, the server serves only the API", async () => {
  const h = await startHarness()
  try {
    assert.equal((await fetch(`${h.base}/`)).status, 404)
  } finally {
    await h.close()
  }
})
