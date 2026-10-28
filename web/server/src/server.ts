import { staticHandler } from "./static.ts"
import { createServer, type IncomingMessage, type ServerResponse } from "node:http"
import type { Duplex } from "node:stream"
import { mkdirSync } from "node:fs"
import { join } from "node:path"
import { WebSocketServer } from "ws"
import { Repo, stringifyAutomergeUrl, type PeerId, type DocumentId } from "@automerge/automerge-repo"
import { NodeFSStorageAdapter } from "@automerge/automerge-repo-storage-nodefs"
import { libraryValue } from "@notable/schema"
import type { Config } from "./config.ts"
import { Accounts, AccountError, type SessionUser, type User } from "./accounts.ts"
import { AuthenticatedServerAdapter } from "./syncAdapter.ts"
import { BlobStore, HASH, HashMismatch, TooLarge } from "./blobs.ts"
import { FailureLimiter } from "./rateLimit.ts"
import { openHt } from "./ht.ts"

export const COOKIE = "notable_session"
const SERVER_PEER_ID = "notable-server" as PeerId
const MAX_JSON_BYTES = 16 * 1024
const MAX_SYNC_MESSAGE = 256 * 1024 * 1024
const SESSION_SWEEP_MS = 30_000
const GC_INTERVAL_MS = 6 * 3600_000

export type RunningServer = {
  port: number
  accounts: Accounts
  repo: Repo
  close: () => Promise<void>
  collectGarbage: () => Promise<void>
}

type Auth = { user: SessionUser; via: "bearer" | "cookie" }

class HttpError extends Error {
  status: number
  headers: Record<string, string>
  constructor(status: number, message: string, headers: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.headers = headers
  }
}

export async function startServer(config: Config, log: (msg: string) => void = () => {}): Promise<RunningServer> {
  mkdirSync(config.stateDir, { recursive: true })
  const accounts = Accounts.open(join(config.stateDir, "notable.db"), config.argon2)
  const blobs = new BlobStore(config.stateDir)
  await blobs.init()

  const storage = new NodeFSStorageAdapter(join(config.stateDir, "docs"))
  const adapter = new AuthenticatedServerAdapter(accounts, log)
  const repo = new Repo({
    network: [adapter],
    storage,
    peerId: SERVER_PEER_ID,
    shareConfig: {
      // Only docs this peer synced before: a reconnect then resets their sync state (see syncAdapter.ts).
      announce: async (peerId, documentId) => documentId !== undefined && adapter.hasSynced(peerId, documentId),
      access: async (peerId, documentId) => documentId !== undefined && adapter.peerOwns(peerId, documentId),
    },
  })

  const serveStatic = config.staticDir ? staticHandler(config.staticDir) : null
  const loginByUser = new FailureLimiter(5, 15 * 60_000)
  const loginByIp = new FailureLimiter(30, 15 * 60_000)
  const libraryLocks = new Map<number, Promise<string>>()
  const ht = config.htUrl ? openHt(config.htUrl) : null
  for (const name of accounts.setHtLinks(config.htLinks)) log(`NOTABLE_HT_LINKS names ${name}, which has no account; create it with notable-admin user add`)
  const secureCookie = config.publicOrigin?.startsWith("https://") ?? false

  // --- helpers ---------------------------------------------------------------------------------

  const clientIp = (req: IncomingMessage) => {
    if (config.trustProxy) {
      const xff = req.headers["x-forwarded-for"]
      const last = (Array.isArray(xff) ? xff.join(",") : xff ?? "").split(",").map(s => s.trim()).filter(Boolean).pop()
      if (last) return last
    }
    return req.socket.remoteAddress ?? "unknown"
  }

  const originAllowed = (req: IncomingMessage) => {
    const origin = req.headers.origin
    if (!origin) return false
    if (config.publicOrigin) return origin === config.publicOrigin
    try {
      return new URL(origin).host === req.headers.host
    } catch {
      return false
    }
  }

  const cookieToken = (req: IncomingMessage): string | null => {
    for (const part of (req.headers.cookie ?? "").split(";")) {
      const [k, ...v] = part.trim().split("=")
      if (k === COOKIE) return v.join("=") || null
    }
    return null
  }

  const authenticate = (req: IncomingMessage): Auth | null => {
    const header = req.headers.authorization
    if (header?.startsWith("Bearer ")) {
      const user = accounts.sessionUser(header.slice(7).trim(), config.sessionIdleDays)
      return user ? { user, via: "bearer" } : null
    }
    const token = cookieToken(req)
    if (!token) return null
    const user = accounts.sessionUser(token, config.sessionIdleDays)
    return user ? { user, via: "cookie" } : null
  }

  /** Cookies are sent by the browser on its own; a request riding on one must come from our origin. */
  const requireAuth = (req: IncomingMessage, stateChanging: boolean): Auth => {
    const auth = authenticate(req)
    if (!auth) throw new HttpError(401, "not logged in")
    if (auth.via === "cookie" && stateChanging && !originAllowed(req)) throw new HttpError(403, "origin not allowed")
    return auth
  }

  const sessionCookie = (token: string) =>
    `${COOKIE}=${token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${config.sessionIdleDays * 86400}${secureCookie ? "; Secure" : ""}`
  const clearCookie = `${COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0${secureCookie ? "; Secure" : ""}`

  const readJson = async (req: IncomingMessage): Promise<Record<string, unknown>> => {
    let size = 0
    const chunks: Buffer[] = []
    for await (const chunk of req) {
      size += (chunk as Buffer).length
      if (size > MAX_JSON_BYTES) throw new HttpError(413, "request too large")
      chunks.push(chunk as Buffer)
    }
    try {
      const v = JSON.parse(Buffer.concat(chunks).toString("utf8"))
      if (typeof v !== "object" || v === null || Array.isArray(v)) throw new Error()
      return v
    } catch {
      throw new HttpError(400, "invalid JSON body")
    }
  }

  const credentials = (body: Record<string, unknown>) => {
    const { username, password } = body
    if (typeof username !== "string" || typeof password !== "string") throw new HttpError(400, "username and password are required")
    return { username, password }
  }

  const json = (res: ServerResponse, status: number, body: unknown, headers: Record<string, string> = {}) => {
    const data = JSON.stringify(body)
    res.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(data), "cache-control": "no-store", ...headers })
    res.end(data)
  }

  /** Creates the account's library doc on first use. Serialized per user, so it happens once. */
  const ensureLibrary = (user: User): Promise<string> => {
    if (user.libraryDocId) return Promise.resolve(user.libraryDocId)
    let p = libraryLocks.get(user.id)
    if (!p) {
      p = (async () => {
        const fresh = accounts.userByName(user.username)
        if (fresh?.libraryDocId) return fresh.libraryDocId
        const handle = repo.create(libraryValue({ folders: {}, notebooks: {} }))
        if (!accounts.claimDoc(handle.documentId, user.id)) throw new Error("could not claim a fresh library doc")
        await repo.flush([handle.documentId])
        accounts.setLibraryDoc(user.id, handle.documentId)
        return handle.documentId as string
      })().finally(() => libraryLocks.delete(user.id))
      libraryLocks.set(user.id, p)
    }
    return p
  }

  /** The account linked to these humane tools credentials, or null when HT refuses them. */
  const htUser = async (email: string, password: string): Promise<User | null> => {
    const r = await ht!.signIn(email.trim(), password)
    if (!r.ok) {
      if (r.status === 401) return null
      throw new HttpError(r.status, r.error, r.retryAfter ? { "retry-after": r.retryAfter } : {})
    }
    const user = accounts.userForHt(r.account.id, r.account.email)
    if (!user) throw new HttpError(403, `${r.account.email} has no Notable account here; ask the admin to link it in NOTABLE_HT_LINKS`)
    return user
  }

  const startSession = (res: ServerResponse, req: IncomingMessage, user: User, status: number) => {
    const token = accounts.createSession(user.id, req.headers["user-agent"] ?? null)
    json(res, status, { token, username: user.username }, { "set-cookie": sessionCookie(token) })
  }

  // --- routes ----------------------------------------------------------------------------------

  const routes: Record<string, (req: IncomingMessage, res: ServerResponse) => Promise<void>> = {
    "GET /api/health": async (_req, res) => json(res, 200, { ok: true }),

    // The PWA's service worker answers the Web Share Target POST. This only runs when it isn't
    // installed yet; send the person back to the app, which explains how to add the file.
    "POST /share-target": async (req, res) => {
      req.resume()
      res.writeHead(303, { location: "/?share=unavailable", "cache-control": "no-store" })
      res.end()
    },

    "POST /api/login": async (req, res) => {
      if (req.headers.origin && !originAllowed(req)) throw new HttpError(403, "origin not allowed")
      const { username, password } = credentials(await readJson(req))
      const ip = clientIp(req)
      const userKey = username.trim().toLowerCase()
      const wait = Math.max(loginByUser.retryAfter(userKey), loginByIp.retryAfter(ip))
      if (wait > 0) throw new HttpError(429, "too many failed logins, try again later", { "retry-after": String(wait) })
      // Usernames can't contain "@", so an email always means a humane tools account.
      const user = ht && username.includes("@") ? await htUser(username, password) : await accounts.authenticate(username, password)
      if (!user) {
        loginByUser.fail(userKey)
        loginByIp.fail(ip)
        throw new HttpError(401, "invalid username or password")
      }
      loginByUser.reset(userKey)
      startSession(res, req, user, 200)
    },

    "POST /api/signup": async (req, res) => {
      if (!config.openSignup) throw new HttpError(404, "not found")
      if (req.headers.origin && !originAllowed(req)) throw new HttpError(403, "origin not allowed")
      const ip = clientIp(req)
      const wait = loginByIp.retryAfter(ip)
      if (wait > 0) throw new HttpError(429, "too many attempts, try again later", { "retry-after": String(wait) })
      const { username, password } = credentials(await readJson(req))
      loginByIp.fail(ip) // signups count toward the per-IP budget
      try {
        const user = await accounts.createUser(username, password)
        await ensureLibrary(user)
        startSession(res, req, user, 201)
      } catch (e) {
        if (e instanceof AccountError) throw new HttpError(e.code === "exists" ? 409 : 400, e.message)
        throw e
      }
    },

    "POST /api/logout": async (req, res) => {
      const { user } = requireAuth(req, true)
      accounts.revokeSession(user.sessionHash)
      adapter.closeSessions(new Set([user.sessionHash]))
      res.writeHead(204, { "set-cookie": clearCookie, "cache-control": "no-store" })
      res.end()
    },

    "GET /api/me": async (req, res) => {
      const { user } = requireAuth(req, false)
      const libraryDocId = await ensureLibrary(user)
      json(res, 200, {
        username: user.username,
        libraryDocUrl: stringifyAutomergeUrl(libraryDocId as DocumentId),
        maxBlobBytes: config.maxBlobBytes,
      })
    },
  }

  const blobRoute = async (req: IncomingMessage, res: ServerResponse, hash: string) => {
    if (req.method === "PUT") {
      const { user } = requireAuth(req, true)
      const declared = Number(req.headers["content-length"])
      if (Number.isFinite(declared) && declared > config.maxBlobBytes) {
        req.resume()
        throw new HttpError(413, "file too large")
      }
      try {
        await blobs.write(hash, req, config.maxBlobBytes, () => accounts.addBlobOwner(hash, user.id))
      } catch (e) {
        if (e instanceof TooLarge) throw new HttpError(413, "file too large")
        if (e instanceof HashMismatch) throw new HttpError(400, "content does not match the sha256 in the URL")
        throw e
      }
      return json(res, 201, { hash })
    }
    if (req.method === "GET" || req.method === "HEAD") {
      const { user } = requireAuth(req, false)
      const size = accounts.ownsBlob(hash, user.id) ? await blobs.size(hash) : null
      if (size === null) throw new HttpError(404, "not found")
      res.writeHead(200, {
        "content-type": "application/octet-stream",
        "content-length": size,
        "cache-control": "private, max-age=31536000, immutable",
        etag: `"${hash}"`,
        // Uploaded bytes are never interpreted by the browser on our origin.
        "content-security-policy": "sandbox; default-src 'none'",
        "content-disposition": "attachment",
      })
      if (req.method === "HEAD") return void res.end()
      blobs.read(hash).on("error", () => res.destroy()).pipe(res)
      return
    }
    throw new HttpError(405, "method not allowed")
  }

  const handle = async (req: IncomingMessage, res: ServerResponse) => {
    res.setHeader("x-content-type-options", "nosniff")
    res.setHeader("referrer-policy", "no-referrer")
    try {
      const path = new URL(req.url ?? "/", "http://x").pathname
      const route = routes[`${req.method} ${path}`]
      if (route) return await route(req, res)
      const blob = /^\/api\/blobs\/([^/]+)$/.exec(path)
      if (blob && HASH.test(blob[1])) return await blobRoute(req, res, blob[1])
      if (serveStatic && (await serveStatic(req, res, path))) return
      throw new HttpError(404, "not found")
    } catch (e) {
      if (res.headersSent) return void res.destroy()
      if (e instanceof HttpError) return json(res, e.status, { error: e.message }, e.headers)
      log(`internal error: ${(e as Error).stack ?? e}`)
      json(res, 500, { error: "internal error" })
    }
  }

  // --- sync ------------------------------------------------------------------------------------

  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_SYNC_MESSAGE })
  const refuseUpgrade = (socket: Duplex, status: number, text: string) => {
    socket.end(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`)
  }

  const http = createServer((req, res) => void handle(req, res))
  http.on("upgrade", (req, socket, head) => {
    const path = new URL(req.url ?? "/", "http://x").pathname
    if (path !== "/sync") return refuseUpgrade(socket, 404, "Not Found")
    const auth = authenticate(req)
    if (!auth) return refuseUpgrade(socket, 401, "Unauthorized")
    if (auth.via === "cookie" && !originAllowed(req)) return refuseUpgrade(socket, 403, "Forbidden")
    wss.handleUpgrade(req, socket, head, ws => adapter.handleConnection(ws, auth.user))
  })

  // Sessions revoked elsewhere (CLI, another server request) close their open sockets.
  const sweep = setInterval(() => {
    const gone = accounts.missingSessions(adapter.liveSessionHashes())
    if (gone.size) adapter.closeSessions(gone)
  }, SESSION_SWEEP_MS)
  sweep.unref()

  // --- garbage collection ----------------------------------------------------------------------

  const collectGarbage = async () => {
    for (const docId of accounts.uncollectedTombstones()) {
      repo.delete(docId as DocumentId)
      await storage.removeRange([docId])
      accounts.markCollected(docId)
    }
    for await (const hash of blobs.all()) {
      if (!accounts.blobHasOwner(hash)) await blobs.remove(hash)
    }
  }
  await collectGarbage()
  const gcTimer = setInterval(() => void collectGarbage().catch(e => log(`gc failed: ${e}`)), GC_INTERVAL_MS)
  gcTimer.unref()

  await new Promise<void>(resolve => http.listen(config.port, config.host, resolve))
  const address = http.address()
  const port = typeof address === "object" && address ? address.port : config.port

  return {
    port,
    accounts,
    repo,
    collectGarbage,
    close: async () => {
      clearInterval(sweep)
      clearInterval(gcTimer)
      adapter.disconnect()
      wss.close()
      await new Promise<void>(resolve => {
        http.close(() => resolve())
        http.closeAllConnections()
      })
      await repo.shutdown()
      accounts.close()
    },
  }
}
