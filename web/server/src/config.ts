import type { Argon2Params } from "./passwords.ts"

export type Config = {
  stateDir: string
  host: string
  port: number
  /** Allow POST /api/signup. Off by default: accounts come from the admin CLI. */
  openSignup: boolean
  /** e.g. "https://notable.example.org". Cookie-authenticated requests must come from here. */
  publicOrigin: string | null
  /** Take the client IP from X-Forwarded-For (set when behind a reverse proxy on the same host). */
  trustProxy: boolean
  maxBlobBytes: number
  /** The built PWA (web/app/dist), served from the same origin. Null serves only the API. */
  staticDir: string | null
  sessionIdleDays: number
  /** humane tools' auth server, e.g. "https://todos.humane.tools". Logins with an email go there (ht.ts). */
  htUrl: string | null
  /** Username -> humane tools email: the accounts HT may sign in as. Applied whole at every start. */
  htLinks: Map<string, string>
  /**
   * The humane.tools accounts provider (ht's ADR-005), e.g. "https://accounts.humane.tools", and
   * this server's secret as its `notable` client. Set, "Sign in with humane tools" redirects there
   * (oidc.ts); the accounts it signs in are the ones NOTABLE_HT_LINKS links. Needs publicOrigin.
   */
  accounts: { url: string; clientSecret: string } | null
  /**
   * Apps that may connect to an account through the provider (GET /api/connect), by name, with
   * the exact redirect URLs each may receive a code at: "flow" -> ["https://flow.humane.tools/…"].
   */
  connectClients: Map<string, string[]>
  argon2: Argon2Params
}

/** OWASP's argon2id baseline: 19 MiB, 2 passes, 1 lane. */
export const DEFAULT_ARGON2: Argon2Params = { memory: 19456, passes: 2, parallelism: 1 }

const bool = (v: string | undefined) => v === "1" || v === "true"

function int(env: NodeJS.ProcessEnv, name: string, fallback: number): number {
  const raw = env[name]
  if (raw === undefined || raw === "") return fallback
  const n = Number(raw)
  if (!Number.isInteger(n) || n <= 0) throw new Error(`${name} must be a positive integer, got "${raw}"`)
  return n
}

export function argon2FromEnv(env: NodeJS.ProcessEnv = process.env): Argon2Params {
  return {
    memory: int(env, "NOTABLE_ARGON2_MEMORY_KIB", DEFAULT_ARGON2.memory),
    passes: int(env, "NOTABLE_ARGON2_PASSES", DEFAULT_ARGON2.passes),
    parallelism: DEFAULT_ARGON2.parallelism,
  }
}

const localHost = (hostname: string) => ["localhost", "127.0.0.1", "[::1]"].includes(hostname) || hostname.endsWith(".localhost")

/** An https URL (http only on localhost) without credentials or a fragment. */
function secureUrl(value: string, name: string): URL {
  let url: URL
  try {
    url = new URL(value)
  } catch {
    throw new Error(`${name} must be a URL, got "${value}"`)
  }
  if (url.protocol !== "https:" && !(url.protocol === "http:" && localHost(url.hostname))) throw new Error(`${name} must use https (http only on localhost), got "${value}"`)
  if (url.hash || url.username || url.password) throw new Error(`${name} must not carry a fragment or credentials`)
  return url
}

/** "flow=https://a/cb,flow=https://b/cb,other=…": each app's redirect URLs. */
export function parseConnectClients(raw: string | undefined): Map<string, string[]> {
  const clients = new Map<string, string[]>()
  for (const entry of (raw ?? "").split(",").map(e => e.trim()).filter(Boolean)) {
    const at = entry.indexOf("=")
    const name = entry.slice(0, at).trim().toLowerCase()
    const url = entry.slice(at + 1).trim()
    if (at < 1 || !/^[a-z][a-z0-9-]*$/.test(name) || !url) throw new Error(`NOTABLE_CONNECT_CLIENTS: expected name=url, got "${entry}"`)
    secureUrl(url, `NOTABLE_CONNECT_CLIENTS ${name}`)
    clients.set(name, [...(clients.get(name) ?? []), url])
  }
  return clients
}

/** NOTABLE_ACCOUNTS_URL and NOTABLE_OIDC_CLIENT_SECRET, both or neither. */
export function accountsFromEnv(env: NodeJS.ProcessEnv, publicOrigin: string | null): Config["accounts"] {
  const url = (env.NOTABLE_ACCOUNTS_URL ?? "").trim()
  const clientSecret = (env.NOTABLE_OIDC_CLIENT_SECRET ?? "").trim()
  if (!url && !clientSecret) return null
  if (!url) throw new Error("NOTABLE_OIDC_CLIENT_SECRET needs NOTABLE_ACCOUNTS_URL")
  const parsed = secureUrl(url, "NOTABLE_ACCOUNTS_URL")
  if (parsed.origin !== url.replace(/\/$/, "")) throw new Error("NOTABLE_ACCOUNTS_URL must be an origin, without a path")
  if (clientSecret.length < 32) throw new Error("NOTABLE_ACCOUNTS_URL needs NOTABLE_OIDC_CLIENT_SECRET, 32 characters or more")
  if (!publicOrigin) throw new Error("NOTABLE_ACCOUNTS_URL needs NOTABLE_PUBLIC_ORIGIN (the provider redirects back to it)")
  return { url: parsed.origin, clientSecret }
}

/** "name=email,name=email" as a map, lower-cased; one email per account and one account per email. */
export function parseHtLinks(raw: string | undefined): Map<string, string> {
  const links = new Map<string, string>()
  const emails = new Set<string>()
  for (const entry of (raw ?? "").split(",").map(e => e.trim()).filter(Boolean)) {
    const [name, email, ...rest] = entry.split("=").map(p => p.trim().toLowerCase())
    if (!name || !email || rest.length || !/^[^@\s]+@[^@\s]+$/.test(email)) throw new Error(`NOTABLE_HT_LINKS: expected name=email, got "${entry}"`)
    if (links.has(name) || emails.has(email)) throw new Error(`NOTABLE_HT_LINKS: "${entry}" repeats an account or an email`)
    links.set(name, email)
    emails.add(email)
  }
  return links
}

function accountsAndClients(env: NodeJS.ProcessEnv, publicOrigin: string | null) {
  const accounts = accountsFromEnv(env, publicOrigin)
  const connectClients = parseConnectClients(env.NOTABLE_CONNECT_CLIENTS)
  if (connectClients.size && !accounts) throw new Error("NOTABLE_CONNECT_CLIENTS needs NOTABLE_ACCOUNTS_URL")
  return { accounts, connectClients }
}

export function configFromEnv(env: NodeJS.ProcessEnv = process.env): Config {
  const stateDir = env.NOTABLE_STATE_DIR
  if (!stateDir) throw new Error("NOTABLE_STATE_DIR is required")
  const listen = env.NOTABLE_LISTEN ?? "127.0.0.1:3030"
  const sep = listen.lastIndexOf(":")
  const host = listen.slice(0, sep).replace(/^\[(.*)\]$/, "$1")
  const port = Number(listen.slice(sep + 1))
  if (sep < 0 || !host || !Number.isInteger(port)) throw new Error(`NOTABLE_LISTEN must be host:port, got "${listen}"`)
  const publicOrigin = env.NOTABLE_PUBLIC_ORIGIN ? new URL(env.NOTABLE_PUBLIC_ORIGIN).origin : null
  return {
    stateDir,
    host,
    port,
    openSignup: bool(env.NOTABLE_OPEN_SIGNUP),
    publicOrigin,
    trustProxy: bool(env.NOTABLE_TRUST_PROXY),
    maxBlobBytes: int(env, "NOTABLE_MAX_BLOB_MIB", 512) * 1024 * 1024,
    staticDir: env.NOTABLE_STATIC_DIR || null,
    sessionIdleDays: int(env, "NOTABLE_SESSION_IDLE_DAYS", 180),
    htUrl: env.NOTABLE_HT_URL ? new URL(env.NOTABLE_HT_URL).origin : null,
    htLinks: parseHtLinks(env.NOTABLE_HT_LINKS),
    ...accountsAndClients(env, publicOrigin),
    argon2: argon2FromEnv(env),
  }
}
