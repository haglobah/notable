/*
 * The server side of automerge-repo's WebSocket protocol, with per-account isolation.
 *
 * This replaces @automerge/automerge-repo-network-websocket's WebSocketServerAdapter. That adapter
 * trusts the peer id a client announces, so a second client announcing an existing peer id takes
 * over the first one's socket and receives its traffic. Here every socket is authenticated at
 * upgrade time and the peer id is bound to that account.
 *
 * Why filtering here is complete: everything the Repo receives or sends over the network passes
 * through `receive` and `send` below. automerge-repo 2.5.6 has six message types:
 *   - sync, request, ephemeral, doc-unavailable: carry a documentId; allowed only when the
 *     account owns the doc (an unknown doc is claimed on its first `sync`, never on `request`).
 *   - remote-subscription-change, remote-heads-changed: storage-id gossip. The server does not
 *     enable remote-heads gossiping; both are dropped in both directions.
 * Unknown types are dropped. The Repo's shareConfig.access runs the same ownership check again
 * (defense in depth), and shareConfig.announce is false, so the server never pushes a doc to a
 * peer that has not asked for it.
 */
import WebSocket from "ws"
import {
  NetworkAdapter, cbor, isValidDocumentId,
  type Message, type PeerId, type PeerMetadata, type DocumentId,
} from "@automerge/automerge-repo"
import type { Accounts, SessionUser } from "./accounts.ts"

type Conn = { socket: WebSocket; user: SessionUser; peerId: PeerId | null; alive: boolean }

const PROTOCOL_V1 = "1"
const MAX_PEER_ID = 128

export class AuthenticatedServerAdapter extends NetworkAdapter {
  readonly accounts: Accounts
  #conns = new Set<Conn>()
  #byPeer = new Map<PeerId, Conn>()
  #keepAlive?: NodeJS.Timeout
  #ready = false
  #resolveReady?: () => void
  #readyPromise = new Promise<void>(r => (this.#resolveReady = r))
  log: (msg: string) => void

  constructor(accounts: Accounts, log: (msg: string) => void = () => {}) {
    super()
    this.accounts = accounts
    this.log = log
  }

  isReady() { return this.#ready }
  whenReady() { return this.#readyPromise }

  connect(peerId: PeerId, peerMetadata?: PeerMetadata) {
    this.peerId = peerId
    this.peerMetadata = peerMetadata ?? {}
    this.#ready = true
    this.#resolveReady?.()
    this.#keepAlive = setInterval(() => {
      for (const c of this.#conns) {
        if (!c.alive) { c.socket.terminate(); continue }
        c.alive = false
        c.socket.ping()
      }
    }, 15_000)
    this.#keepAlive.unref()
  }

  disconnect() {
    clearInterval(this.#keepAlive)
    for (const c of [...this.#conns]) c.socket.terminate()
  }

  /** Called after the HTTP upgrade has been authenticated. */
  handleConnection(socket: WebSocket, user: SessionUser) {
    const conn: Conn = { socket, user, peerId: null, alive: true }
    this.#conns.add(conn)
    socket.on("pong", () => (conn.alive = true))
    socket.on("message", (data, isBinary) => {
      if (!isBinary) { socket.close(1003, "binary frames only"); return }
      this.#receive(conn, data as Buffer)
    })
    socket.on("close", () => this.#drop(conn))
    socket.on("error", () => socket.terminate())
  }

  /** The account currently bound to a peer id, if that peer is connected. */
  userOfPeer(peerId: PeerId): SessionUser | null {
    return this.#byPeer.get(peerId)?.user ?? null
  }

  peerOwns(peerId: PeerId, documentId: DocumentId): boolean {
    const user = this.userOfPeer(peerId)
    return user !== null && this.accounts.ownsDoc(documentId, user.id)
  }

  closeSessions(sessionHashes: Set<string>) {
    for (const c of this.#conns) {
      if (sessionHashes.has(c.user.sessionHash)) c.socket.close(4401, "session ended")
    }
  }

  liveSessionHashes(): string[] {
    return [...new Set([...this.#conns].map(c => c.user.sessionHash))]
  }

  #drop(conn: Conn) {
    if (!this.#conns.delete(conn)) return
    if (conn.peerId && this.#byPeer.get(conn.peerId) === conn) {
      this.#byPeer.delete(conn.peerId)
      this.emit("peer-disconnected", { peerId: conn.peerId })
    }
  }

  #sendRaw(conn: Conn, message: object) {
    if (conn.socket.readyState === WebSocket.OPEN) conn.socket.send(cbor.encode(message))
  }

  #receive(conn: Conn, bytes: Buffer) {
    let msg: Message & { peerMetadata?: PeerMetadata; supportedProtocolVersions?: string[] }
    try {
      msg = cbor.decode(new Uint8Array(bytes))
    } catch {
      conn.socket.close(1007, "invalid message")
      return
    }
    if (typeof msg !== "object" || msg === null || typeof msg.type !== "string") {
      conn.socket.close(1007, "invalid message")
      return
    }
    if (msg.type === "join") return this.#join(conn, msg)

    // Everything after the handshake must come from the peer id this socket joined as.
    if (conn.peerId === null || msg.senderId !== conn.peerId || msg.targetId !== this.peerId) return

    const documentId = (msg as { documentId?: unknown }).documentId
    const hasDoc = typeof documentId === "string" && isValidDocumentId(documentId)
    switch (msg.type) {
      case "sync":
        if (!hasDoc) return
        if (this.accounts.claimDoc(documentId, conn.user.id)) return void this.emit("message", msg)
        return this.#unavailable(conn, documentId)
      case "request":
        if (!hasDoc) return
        if (this.accounts.ownsDoc(documentId, conn.user.id)) return void this.emit("message", msg)
        return this.#unavailable(conn, documentId)
      case "ephemeral":
      case "doc-unavailable":
        if (hasDoc && this.accounts.ownsDoc(documentId, conn.user.id)) this.emit("message", msg)
        return
      default:
        // remote-subscription-change, remote-heads-changed, and anything unknown.
        return
    }
  }

  #join(conn: Conn, msg: { senderId: PeerId; peerMetadata?: PeerMetadata; supportedProtocolVersions?: string[] }) {
    const peerId = msg.senderId
    const reject = (message: string) => {
      this.#sendRaw(conn, { type: "error", senderId: this.peerId, targetId: peerId, message })
      conn.socket.close(4400, message)
    }
    if (typeof peerId !== "string" || peerId.length === 0 || peerId.length > MAX_PEER_ID) return reject("invalid peer id")
    if (conn.peerId !== null && conn.peerId !== peerId) return reject("one peer id per connection")
    const versions = msg.supportedProtocolVersions
    if (versions !== undefined && !versions.includes(PROTOCOL_V1)) return reject("unsupported protocol version")

    const existing = this.#byPeer.get(peerId)
    if (existing && existing !== conn) {
      // Another account holds this peer id: refuse. The holder keeps its connection.
      if (existing.user.id !== conn.user.id) {
        this.log(`refused peer id held by another account (${conn.user.username})`)
        return reject("peer id in use")
      }
      // Same account reconnecting (e.g. a stale socket): replace it, as the stock adapter does.
      this.#byPeer.delete(peerId)
      this.emit("peer-disconnected", { peerId })
      existing.socket.close(4409, "replaced by a new connection")
    }
    conn.peerId = peerId
    this.#byPeer.set(peerId, conn)
    this.emit("peer-candidate", { peerId, peerMetadata: msg.peerMetadata ?? {} })
    this.#sendRaw(conn, {
      type: "peer", senderId: this.peerId, targetId: peerId,
      peerMetadata: this.peerMetadata, selectedProtocolVersion: PROTOCOL_V1,
    })
  }

  #unavailable(conn: Conn, documentId: DocumentId) {
    this.#sendRaw(conn, { type: "doc-unavailable", senderId: this.peerId, targetId: conn.peerId, documentId })
  }

  send(message: Message) {
    const conn = this.#byPeer.get(message.targetId)
    if (!conn) return
    const documentId = (message as { documentId?: unknown }).documentId
    if (typeof documentId !== "string") return // gossip without a document: never sent
    // doc-unavailable reveals nothing; everything else requires ownership.
    if (message.type !== "doc-unavailable" && !this.accounts.ownsDoc(documentId, conn.user.id)) {
      this.log(`blocked outbound ${message.type} for a doc ${conn.user.username} does not own`)
      return
    }
    if ("data" in message && message.data?.byteLength === 0) return
    this.#sendRaw(conn, message)
  }
}
