import { staticHandler } from "./static.ts"
import { createServer, type IncomingMessage, type ServerResponse } from "node:http"
import type { Duplex } from "node:stream"
import { mkdirSync } from "node:fs"
import { timingSafeEqual } from "node:crypto"
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
import { authorizeUrl, exchangeCode, pkceChallenge, randomToken, userinfo, verifyIdToken, type OidcClient } from "./oidc.ts"
import { SignInFlows, type ConnectPurpose, type Purpose } from "./flows.ts"

export const COOKIE = "notable_session"
/** Binds a sign-in through the accounts provider to the browser that started it (flows.ts). */
const OIDC_COOKIE = "notable_oidc"
/** Binds a connect waiting for consent to the browser its consent page was shown in (flows.ts). */
const CONNECT_COOKIE = "notable_connect"
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

/**
 * Why a sign-in through the provider failed. The PWA gets only the code (`/?login_error=<code>`)
 * and shows its own text for it (app/src/components/Login.tsx), so a link can't put words on the
 * login page; an app connecting gets the text as its error_description, the log the details.
 */
export const SIGN_IN_ERRORS = {
  expired: "the sign-in expired or was started elsewhere; try again",
  login_required: "sign in to humane tools first",
  refused: "humane tools did not accept the sign-in",
  unavailable: "humane tools sign-in is unavailable",
  invalid_answer: "humane tools answered with something Notable could not check",
  email_unverified: "verify your humane tools email address first",
  no_account: "this humane tools account has no Notable account here; ask the admin to link it in NOTABLE_HT_LINKS",
} as const
export type SignInError = keyof typeof SIGN_IN_ERRORS
type SignInFailure = { error: SignInError; detail?: string }

const withParams = (url: string, params: Record<string, string>) => {
  const u = new URL(url)
  for (const [k, v] of Object.entries(params)) if (v) u.searchParams.set(k, v)
  return u.toString()
}

const escapeHtml = (s: string) =>
  s.replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!)

const sameSecret = (a: string, b: string) => a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b))

/**
 * A page of the server's own: no scripts, never in a frame (so no page can make a click on it for
 * someone), and sending its origin with its form post (the global no-referrer would send
 * `Origin: null`).
 */
const htmlPage = (res: ServerResponse, status: number, title: string, body: string, cookies: string[] = []) => {
  const page = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>
  body { font: 17px/1.5 system-ui, sans-serif; margin: 0; padding: 12vh 1.5rem; color: #1d1b18; background: #faf8f4; }
  main { max-width: 32rem; margin: 0 auto; }
  h1 { font-size: 1.4rem; line-height: 1.3; margin: 0 0 1rem; }
  code { font-size: 0.9em; overflow-wrap: anywhere; }
  .hint { color: #6b655c; font-size: 0.92rem; }
  form { display: flex; gap: 0.75rem; margin-top: 1.5rem; }
  button { font: inherit; padding: 0.5rem 1.25rem; border-radius: 6px; border: 1px solid #1d1b18; background: transparent; color: inherit; cursor: pointer; }
  button[value=connect] { background: #1d1b18; color: #faf8f4; }
</style>
</head>
<body><main>
${body}
</main></body>
</html>
`
  res.writeHead(status, {
    "content-type": "text/html; charset=utf-8",
    "content-length": Buffer.byteLength(page),
    "cache-control": "no-store",
    "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'",
    "x-frame-options": "DENY",
    "referrer-policy": "same-origin",
    ...(cookies.length ? { "set-cookie": cookies } : {}),
  })
  res.end(page)
}

/** Asks the person signed in whether `purpose`'s app may have their library. */
const consentPage = (res: ServerResponse, ask: string, user: User, purpose: ConnectPurpose, cookies: string[]) => {
  const app = escapeHtml(purpose.client.charAt(0).toUpperCase() + purpose.client.slice(1))
  const target = new URL(purpose.redirectUri)
  const local = target.protocol === "http:"
  htmlPage(res, 200, `Connect ${app} to Notable`, `<h1>Connect ${app} to your Notable library?</h1>
<p>${app} will be able to read and change the documents and notebooks of the Notable account <strong>${escapeHtml(user.username)}</strong>.</p>
<p>The access goes to <code>${escapeHtml(target.origin)}</code>${local ? ", a server on this computer" : ""}.</p>
<p class="hint">If you didn't just ask ${app} to connect to Notable, cancel.</p>
<form method="post" action="/api/connect/consent">
  <input type="hidden" name="ask" value="${escapeHtml(ask)}">
  <button type="submit" name="decision" value="connect">Connect</button>
  <button type="submit" name="decision" value="cancel">Cancel</button>
</form>`, cookies)
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
  // Signing in through the humane.tools accounts provider (oidc.ts), and apps connecting through it.
  const oidc: OidcClient | null = config.accounts && config.publicOrigin
    ? { accountsUrl: config.accounts.url, clientId: "notable", clientSecret: config.accounts.clientSecret, redirectUri: `${config.publicOrigin}/api/oidc/callback` }
    : null
  const flows = new SignInFlows()

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

  const query = (req: IncomingMessage) => new URL(req.url ?? "/", "http://x").searchParams

  const redirect = (res: ServerResponse, location: string, cookies: string[] = [], status = 302) => {
    res.writeHead(status, { location, "cache-control": "no-store", ...(cookies.length ? { "set-cookie": cookies } : {}) })
    res.end()
  }

  /** Lax: the provider's redirect back is a cross-site top-level navigation. */
  const oidcCookie = (state: string, maxAge: number) =>
    `${OIDC_COOKIE}=${state}; Path=/api/oidc; HttpOnly; SameSite=Lax; Max-Age=${maxAge}${secureCookie ? "; Secure" : ""}`
  const oidcState = (req: IncomingMessage) => {
    for (const part of (req.headers.cookie ?? "").split(";")) {
      const [k, ...v] = part.trim().split("=")
      if (k === OIDC_COOKIE) return v.join("=") || null
    }
    return null
  }

  /** Strict: only the consent page's own form post carries it. */
  const connectCookie = (value: string, maxAge: number) =>
    `${CONNECT_COOKIE}=${value}; Path=/api/connect; HttpOnly; SameSite=Strict; Max-Age=${maxAge}${secureCookie ? "; Secure" : ""}`
  const connectBrowser = (req: IncomingMessage) => {
    for (const part of (req.headers.cookie ?? "").split(";")) {
      const [k, ...v] = part.trim().split("=")
      if (k === CONNECT_COOKIE) return v.join("=") || null
    }
    return null
  }

  /** Sends the browser to the provider; the callback finishes `purpose`. */
  const toProvider = (res: ServerResponse, purpose: Purpose) => {
    const state = randomToken(), nonce = randomToken(), verifier = randomToken(48)
    flows.begin(state, purpose, nonce, verifier)
    redirect(res, authorizeUrl(oidc!, { state, nonce, challenge: pkceChallenge(verifier) }), [oidcCookie(state, 600)])
  }

  /** The account the provider's answer names, or why there is none. */
  const providerUser = async (code: string, verifier: string, nonce: string): Promise<User | SignInFailure> => {
    const tokens = await exchangeCode(oidc!, code, verifier)
    if (tokens.type !== "ok") return { error: tokens.type === "rejected" ? "refused" : "unavailable", detail: `token: ${tokens.error}` }
    const id = await verifyIdToken(tokens.value.idToken, { clientId: oidc!.clientId, clientSecret: oidc!.clientSecret, nonce })
    if (id.type !== "ok") return { error: "invalid_answer", detail: `invalid ID token (${id.reason})` }
    const info = await userinfo(oidc!, tokens.value.accessToken)
    if (info.type !== "ok") return { error: "unavailable", detail: `userinfo: ${info.error}` }
    if (info.value.sub !== id.sub) return { error: "invalid_answer", detail: "ID token and userinfo name two different accounts" }
    // The provider sends email_verified with the email scope; without it, only an account already
    // linked to this provider id signs in, never one found by email.
    const { sub, email, emailVerified } = info.value
    if (emailVerified === false) return { error: "email_unverified", detail: email }
    return accounts.userForHt(sub, email, emailVerified === true)
      ?? (emailVerified === true ? { error: "no_account", detail: email } : { error: "email_unverified", detail: `${email}: no email_verified from the provider` })
  }

  const readBody = async (req: IncomingMessage): Promise<string> => {
    let size = 0
    const chunks: Buffer[] = []
    for await (const chunk of req) {
      size += (chunk as Buffer).length
      if (size > MAX_JSON_BYTES) throw new HttpError(413, "request too large")
      chunks.push(chunk as Buffer)
    }
    return Buffer.concat(chunks).toString("utf8")
  }

  const readJson = async (req: IncomingMessage): Promise<Record<string, unknown>> => {
    const body = await readBody(req)
    try {
      const v = JSON.parse(body)
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
    const user = accounts.userForHt(r.account.id, r.account.email, r.account.emailVerified)
    if (!user && !r.account.emailVerified) throw new HttpError(403, "verify your humane tools email address first")
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

    /** What the login form offers (the PWA asks before showing it). */
    "GET /api/login-methods": async (_req, res) => json(res, 200, { password: true, humaneTools: !!oidc, connect: [...config.connectClients.keys()] }),

    // "Sign in with humane tools": the provider signs the browser in (silently when it already is
    // there), the callback gives it an ordinary session.
    "GET /api/oidc/start": async (_req, res) => {
      if (!oidc) throw new HttpError(404, "not found")
      toProvider(res, { kind: "login" })
    },

    "GET /api/oidc/callback": async (req, res) => {
      if (!oidc) throw new HttpError(404, "not found")
      const q = query(req)
      const state = q.get("state") ?? ""
      const clear = oidcCookie("", 0)
      // Only the browser that started the sign-in may finish it.
      const pending = state && oidcState(req) === state ? flows.pending.take(state) : null
      if (!pending) return redirect(res, "/?login_error=expired", [clear])
      const { purpose } = pending
      const fail = ({ error, detail }: SignInFailure) => {
        if (detail) log(`sign-in through humane tools failed (${error}): ${detail}`)
        return purpose.kind === "login"
          ? redirect(res, `/?login_error=${error}`, [clear])
          : redirect(res, withParams(purpose.redirectUri, { error: "access_denied", error_description: SIGN_IN_ERRORS[error], state: purpose.state }), [clear])
      }
      const code = q.get("code")
      if (!code)
        return fail(q.get("error") === "login_required" ? { error: "login_required" } : { error: "refused", detail: q.get("error_description") ?? q.get("error") ?? "no code" })
      const user = await providerUser(code, pending.verifier, pending.nonce)
      if (!("id" in user)) return fail(user)
      if (purpose.kind === "login") {
        const token = accounts.createSession(user.id, req.headers["user-agent"] ?? null)
        return redirect(res, "/", [clear, sessionCookie(token)])
      }
      // An app gets a code only once the person says so here: any page can start a connect, and
      // the provider signs a browser in silently.
      const ask = randomToken(), browser = randomToken()
      flows.ask(ask, user, purpose, browser)
      consentPage(res, ask, user, purpose, [clear, connectCookie(browser, 600)])
    },

    // An app (Flow) connects to the account of whoever signs in to humane tools in this browser,
    // once they approve it on the consent page: OAuth's code flow with PKCE, the app being a
    // client registered in NOTABLE_CONNECT_CLIENTS by its exact redirect URLs. Its server swaps
    // the code for an ordinary session token.
    "GET /api/connect": async (req, res) => {
      if (!oidc) throw new HttpError(404, "not found")
      const q = query(req)
      const client = q.get("client") ?? ""
      const redirectUri = q.get("redirect_uri") ?? ""
      const allowed = config.connectClients.get(client)
      // Nothing is sent to an unregistered URL, not even an error.
      if (!allowed || !allowed.includes(redirectUri)) throw new HttpError(400, "unknown app or redirect URL")
      const state = q.get("state") ?? ""
      const challenge = q.get("code_challenge") ?? ""
      if (!state || q.get("code_challenge_method") !== "S256" || !/^[A-Za-z0-9_-]{43}$/.test(challenge))
        return redirect(res, withParams(redirectUri, { error: "invalid_request", error_description: "state and an S256 code_challenge are needed", state }))
      toProvider(res, { kind: "connect", client, redirectUri, state, challenge })
    },

    // The consent page's form: only from our origin, with the one-time token the page carried,
    // in the browser it was shown in (the cookie).
    "POST /api/connect/consent": async (req, res) => {
      if (!oidc) throw new HttpError(404, "not found")
      if (!originAllowed(req)) throw new HttpError(403, "origin not allowed")
      const form = new URLSearchParams(await readBody(req))
      const asked = flows.asks.take(form.get("ask") ?? "")
      const browser = connectBrowser(req)
      const clear = connectCookie("", 0)
      if (!asked || !browser || !sameSecret(browser, asked.browser))
        return htmlPage(res, 400, "Connect expired", "<h1>This connect expired</h1>\n<p>It was started in another browser or too long ago. Start it again from the app.</p>", [clear])
      const { user, purpose } = asked
      if (form.get("decision") !== "connect")
        return redirect(res, withParams(purpose.redirectUri, { error: "access_denied", error_description: "the connect was cancelled", state: purpose.state }), [clear], 303)
      const code = randomToken()
      flows.issueCode(code, user, purpose)
      redirect(res, withParams(purpose.redirectUri, { code, state: purpose.state }), [clear], 303)
    },

    "POST /api/connect/token": async (req, res) => {
      if (!oidc) throw new HttpError(404, "not found")
      const body = await readJson(req)
      const { code, code_verifier, redirect_uri, client } = body
      if (typeof code !== "string" || typeof code_verifier !== "string" || typeof redirect_uri !== "string" || typeof client !== "string")
        throw new HttpError(400, "code, code_verifier, redirect_uri and client are needed")
      const issued = flows.codes.take(code)
      if (!issued || issued.client !== client || issued.redirectUri !== redirect_uri || pkceChallenge(code_verifier) !== issued.challenge)
        throw new HttpError(400, "invalid or expired code")
      const token = accounts.createSession(issued.user.id, `${client} (connected)`)
      json(res, 200, { token, username: issued.user.username })
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
