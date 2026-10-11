/*
 * Login state and the per-account Automerge repo. The session itself is an HttpOnly cookie; this
 * module only remembers who is logged in (username, library URL, size limit) so the library opens
 * offline from IndexedDB.
 */
import { Repo, type AutomergeUrl } from "@automerge/automerge-repo"
import { WebSocketClientAdapter } from "@automerge/automerge-repo-network-websocket"
import { IndexedDBStorageAdapter } from "@automerge/automerge-repo-storage-indexeddb"

export type Me = { username: string; libraryDocUrl: AutomergeUrl; maxBlobBytes: number }

const ME_KEY = "notable.me"

const remember = (me: Me) => { try { localStorage.setItem(ME_KEY, JSON.stringify(me)) } catch { /* private mode */ } }
const remembered = (): Me | null => {
  try {
    const v = JSON.parse(localStorage.getItem(ME_KEY) ?? "null") as Me | null
    return v && typeof v.username === "string" && typeof v.libraryDocUrl === "string" ? v : null
  } catch {
    return null
  }
}
const forget = () => { try { localStorage.removeItem(ME_KEY) } catch { /* private mode */ } }

export type Whoami = { state: "in"; me: Me; offline: boolean } | { state: "out" }

/** Asks the server; when it can't be reached, falls back to the last known account. */
export async function whoami(): Promise<Whoami> {
  let res: Response
  try {
    res = await fetch("/api/me", { credentials: "same-origin" })
  } catch {
    const me = remembered()
    return me ? { state: "in", me, offline: true } : { state: "out" }
  }
  if (res.status === 401) {
    forget()
    return { state: "out" }
  }
  if (!res.ok) {
    const me = remembered()
    if (me) return { state: "in", me, offline: true }
    throw new Error(`The server answered ${res.status}. Try again in a moment.`)
  }
  const me = (await res.json()) as Me
  remember(me)
  return { state: "in", me, offline: false }
}

export class LoginError extends Error {}

export async function login(username: string, password: string): Promise<void> {
  let res: Response
  try {
    res = await fetch("/api/login", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ username, password }),
      credentials: "same-origin",
    })
  } catch {
    throw new LoginError("The server can't be reached. Check your connection, then try again.")
  }
  if (res.ok) return
  if (res.status === 401) throw new LoginError("Wrong username or password.")
  if (res.status === 429) {
    const minutes = Math.max(1, Math.ceil(Number(res.headers.get("retry-after") ?? "60") / 60))
    throw new LoginError(`Too many failed attempts. Try again in ${minutes} minute${minutes === 1 ? "" : "s"}.`)
  }
  throw new LoginError(`Logging in failed (${res.status}). Try again in a moment.`)
}

const databaseFor = (username: string) => `notable-${username}`

export function openRepo(me: Me): Repo {
  const scheme = location.protocol === "https:" ? "wss:" : "ws:"
  return new Repo({
    network: [new WebSocketClientAdapter(`${scheme}//${location.host}/sync`)],
    storage: new IndexedDBStorageAdapter(databaseFor(me.username)),
  })
}

/** Ends the session and removes this device's copy of the account: docs, cached files, inbox. */
export async function logout(me: Me | null, repo: Repo | null): Promise<void> {
  try {
    await fetch("/api/logout", { method: "POST", credentials: "same-origin" })
  } catch { /* offline: the local copy still goes */ }
  await repo?.shutdown().catch(() => {})
  forget()
  if (me) await new Promise<void>(resolve => {
    const req = indexedDB.deleteDatabase(databaseFor(me.username))
    req.onsuccess = req.onerror = req.onblocked = () => resolve()
  })
  if ("caches" in self) {
    await caches.delete("blobs")
    await caches.delete("share-inbox")
  }
}
