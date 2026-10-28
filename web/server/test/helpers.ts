import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import WebSocket from "ws"
import {
  Repo, NetworkAdapter, cbor,
  type Message, type PeerId, type PeerMetadata, type AutomergeUrl, type DocHandle,
} from "@automerge/automerge-repo"
import { startServer, type RunningServer } from "../src/server.ts"
import type { Config } from "../src/config.ts"

/**
 * A minimal automerge-repo WebSocket client that can send auth headers on the upgrade, as the
 * Android client will. The browser client authenticates with the session cookie instead.
 * Speaks the same join/peer handshake as @automerge/automerge-repo-network-websocket.
 */
export class HeaderClientAdapter extends NetworkAdapter {
  socket?: WebSocket
  #ready = false
  #resolveReady?: () => void
  #readyPromise = new Promise<void>(r => (this.#resolveReady = r))
  remotePeerId?: PeerId
  url: string
  headers: Record<string, string>

  constructor(url: string, headers: Record<string, string>) {
    super()
    this.url = url
    this.headers = headers
  }

  isReady() { return this.#ready }
  whenReady() { return this.#readyPromise }
  #markReady() { if (!this.#ready) { this.#ready = true; this.#resolveReady?.() } }

  connect(peerId: PeerId, peerMetadata?: PeerMetadata) {
    this.peerId = peerId
    this.peerMetadata = peerMetadata ?? {}
    const socket = new WebSocket(this.url, { headers: this.headers })
    this.socket = socket
    socket.binaryType = "arraybuffer"
    socket.on("open", () => {
      socket.send(cbor.encode({ type: "join", senderId: peerId, peerMetadata: this.peerMetadata, supportedProtocolVersions: ["1"] }))
    })
    socket.on("message", (data: ArrayBuffer) => {
      const msg = cbor.decode(new Uint8Array(data)) as Message & { peerMetadata?: PeerMetadata }
      if (msg.type === "peer") {
        this.remotePeerId = msg.senderId
        this.emit("peer-candidate", { peerId: msg.senderId, peerMetadata: msg.peerMetadata ?? {} })
        this.#markReady()
      } else if (msg.type === "error") {
        this.emit("close")
      } else {
        this.emit("message", msg)
      }
    })
    socket.on("close", () => {
      if (this.remotePeerId) this.emit("peer-disconnected", { peerId: this.remotePeerId })
      this.#markReady()
    })
    socket.on("error", () => this.#markReady())
    socket.on("unexpected-response", () => this.#markReady())
    setTimeout(() => this.#markReady(), 1000)
  }

  send(message: Message) {
    if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(cbor.encode(message))
  }

  disconnect() {
    this.socket?.close()
  }
}

export type Harness = {
  server: RunningServer
  base: string
  wsUrl: string
  stateDir: string
  close: () => Promise<void>
}

export async function startHarness(overrides: Partial<Config> = {}): Promise<Harness> {
  const stateDir = mkdtempSync(join(tmpdir(), "notable-server-test-"))
  const server = await startServer({
    stateDir,
    host: "127.0.0.1",
    port: 0,
    openSignup: false,
    publicOrigin: null,
    trustProxy: false,
    maxBlobBytes: 8 * 1024 * 1024,
    staticDir: null,
    sessionIdleDays: 180,
    htUrl: null,
    htLinks: new Map(),
    // Cheap hashing keeps tests fast; production uses the defaults in config.ts.
    argon2: { memory: 1024, passes: 1, parallelism: 1 },
    ...overrides,
  })
  const base = `http://127.0.0.1:${server.port}`
  return {
    server,
    base,
    wsUrl: `ws://127.0.0.1:${server.port}/sync`,
    stateDir,
    close: async () => {
      await server.close()
      rmSync(stateDir, { recursive: true, force: true })
    },
  }
}

export async function login(h: Harness, username: string, password: string) {
  const res = await fetch(`${h.base}/api/login`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ username, password }),
  })
  if (res.status !== 200) throw new Error(`login failed: ${res.status}`)
  const body = (await res.json()) as { token: string }
  const cookie = res.headers.get("set-cookie")!.split(";")[0]
  return { token: body.token, cookie }
}

export async function userWithToken(h: Harness, username: string, password = "correct horse battery") {
  await h.server.accounts.createUser(username, password)
  return (await login(h, username, password)).token
}

export function clientRepo(h: Harness, token: string, peerId?: string) {
  const adapter = new HeaderClientAdapter(h.wsUrl, { authorization: `Bearer ${token}` })
  const repo = new Repo({ network: [adapter], peerId: peerId as PeerId | undefined })
  return { repo, adapter }
}

export async function waitFor<T>(fn: () => T | Promise<T>, label: string, timeoutMs = 5000): Promise<NonNullable<T>> {
  const end = Date.now() + timeoutMs
  for (;;) {
    const v = await fn()
    if (v) return v as NonNullable<T>
    if (Date.now() > end) throw new Error(`timed out waiting for ${label}`)
    await new Promise(r => setTimeout(r, 25))
  }
}

/** Resolves to the handle, or to "unavailable" when the repo gives up. */
export async function tryFind<T>(repo: Repo, url: AutomergeUrl, timeoutMs = 3000): Promise<DocHandle<T> | "unavailable" | "timeout"> {
  const ac = new AbortController()
  const timer = setTimeout(() => ac.abort(), timeoutMs)
  try {
    return await repo.find<T>(url, { signal: ac.signal })
  } catch (e) {
    return ac.signal.aborted ? "timeout" : "unavailable"
  } finally {
    clearTimeout(timer)
  }
}

export function withTimeout<T>(p: Promise<T>, label: string, ms = 5000): Promise<T> {
  let timer: NodeJS.Timeout
  return Promise.race([
    p,
    new Promise<T>((_, reject) => { timer = setTimeout(() => reject(new Error(`timed out waiting for ${label}`)), ms) }),
  ]).finally(() => clearTimeout(timer))
}

export async function shutdown(...repos: Repo[]) {
  for (const r of repos) await r.shutdown()
}
