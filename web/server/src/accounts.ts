/*
 * Accounts, sessions and ownership in SQLite. The server and the admin CLI open the same file
 * (WAL mode), so every check reads the database instead of a cache: a CLI revoke or delete takes
 * effect on the server's next request without a restart.
 */
import { DatabaseSync } from "node:sqlite"
import { createHash, randomBytes } from "node:crypto"
import { hashPassword, verifyPassword, type Argon2Params } from "./passwords.ts"

export type User = { id: number; username: string; libraryDocId: string | null }
export type SessionUser = User & { sessionHash: string }

export class AccountError extends Error {
  code: "invalid-username" | "weak-password" | "exists" | "no-such-user"
  constructor(code: AccountError["code"], message: string) {
    super(message)
    this.code = code
  }
}

const MIGRATIONS: string[] = [
  `
  CREATE TABLE users (
    id INTEGER PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    library_doc_id TEXT,
    created_at INTEGER NOT NULL
  );
  CREATE TABLE sessions (
    token_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    last_seen INTEGER NOT NULL,
    user_agent TEXT
  );
  CREATE INDEX sessions_user ON sessions(user_id);
  CREATE TABLE doc_owners (
    doc_id TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    claimed_at INTEGER NOT NULL
  );
  CREATE INDEX doc_owners_user ON doc_owners(user_id);
  -- Docs of deleted users. Never claimable again; storage is removed by garbage collection.
  CREATE TABLE doc_tombstones (
    doc_id TEXT PRIMARY KEY,
    deleted_at INTEGER NOT NULL,
    collected INTEGER NOT NULL DEFAULT 0
  );
  CREATE TABLE blob_owners (
    hash TEXT NOT NULL,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    added_at INTEGER NOT NULL,
    PRIMARY KEY (hash, user_id)
  );
  CREATE INDEX blob_owners_user ON blob_owners(user_id);
  `,
]

const USERNAME = /^[a-z0-9][a-z0-9_.-]{0,63}$/
export const MIN_PASSWORD = 10
const MAX_PASSWORD = 1024
const LAST_SEEN_RESOLUTION_MS = 60_000

export const normalizeUsername = (u: string) => u.trim().toLowerCase()
export const hashToken = (token: string) => createHash("sha256").update(token).digest("hex")

export class Accounts {
  readonly db: DatabaseSync
  readonly argon2: Argon2Params
  #dummyHash?: Promise<string>

  private constructor(db: DatabaseSync, argon2: Argon2Params) {
    this.db = db
    this.argon2 = argon2
  }

  static open(path: string, argon2: Argon2Params): Accounts {
    const db = new DatabaseSync(path)
    db.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;")
    const { user_version } = db.prepare("PRAGMA user_version").get() as { user_version: number }
    if (user_version > MIGRATIONS.length) {
      throw new Error(`database schema version ${user_version} is newer than this server (${MIGRATIONS.length})`)
    }
    for (let v = user_version; v < MIGRATIONS.length; v++) {
      db.exec("BEGIN")
      try {
        db.exec(MIGRATIONS[v])
        db.exec(`PRAGMA user_version = ${v + 1}`)
        db.exec("COMMIT")
      } catch (e) {
        db.exec("ROLLBACK")
        throw e
      }
    }
    return new Accounts(db, argon2)
  }

  close() { this.db.close() }

  // --- users -------------------------------------------------------------------------------

  static validateUsername(raw: string): string {
    const u = normalizeUsername(raw)
    if (!USERNAME.test(u)) {
      throw new AccountError("invalid-username", "username must be 1-64 characters: a-z, 0-9, '_', '.', '-', starting with a letter or digit")
    }
    return u
  }

  static validatePassword(pw: string) {
    if (typeof pw !== "string" || pw.length < MIN_PASSWORD || pw.length > MAX_PASSWORD) {
      throw new AccountError("weak-password", `password must be ${MIN_PASSWORD}-${MAX_PASSWORD} characters`)
    }
  }

  async createUser(rawUsername: string, password: string): Promise<User> {
    const username = Accounts.validateUsername(rawUsername)
    Accounts.validatePassword(password)
    const hash = await hashPassword(password, this.argon2)
    try {
      const r = this.db.prepare("INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)").run(username, hash, Date.now())
      return { id: Number(r.lastInsertRowid), username, libraryDocId: null }
    } catch (e) {
      if (String(e).includes("UNIQUE")) throw new AccountError("exists", `user ${username} already exists`)
      throw e
    }
  }

  userByName(rawUsername: string): User | null {
    const row = this.db.prepare("SELECT id, username, library_doc_id FROM users WHERE username = ?").get(normalizeUsername(rawUsername)) as
      { id: number; username: string; library_doc_id: string | null } | undefined
    return row ? { id: row.id, username: row.username, libraryDocId: row.library_doc_id } : null
  }

  #mustUser(rawUsername: string): User {
    const u = this.userByName(rawUsername)
    if (!u) throw new AccountError("no-such-user", `no user ${normalizeUsername(rawUsername)}`)
    return u
  }

  listUsers(): { username: string; createdAt: number; sessions: number; docs: number; blobs: number }[] {
    return this.db.prepare(`
      SELECT u.username, u.created_at AS createdAt,
        (SELECT count(*) FROM sessions s WHERE s.user_id = u.id) AS sessions,
        (SELECT count(*) FROM doc_owners d WHERE d.user_id = u.id) AS docs,
        (SELECT count(*) FROM blob_owners b WHERE b.user_id = u.id) AS blobs
      FROM users u ORDER BY u.username`).all() as never
  }

  /** Changing a password logs the user out everywhere. */
  async setPassword(rawUsername: string, password: string) {
    const u = this.#mustUser(rawUsername)
    Accounts.validatePassword(password)
    const hash = await hashPassword(password, this.argon2)
    this.db.exec("BEGIN")
    try {
      this.db.prepare("UPDATE users SET password_hash = ? WHERE id = ?").run(hash, u.id)
      this.db.prepare("DELETE FROM sessions WHERE user_id = ?").run(u.id)
      this.db.exec("COMMIT")
    } catch (e) {
      this.db.exec("ROLLBACK")
      throw e
    }
  }

  /**
   * Deletes the account, its sessions and its ownership records. Its docs become tombstones (never
   * claimable again, so nobody who learned a URL can take over the server's copy); garbage
   * collection removes their storage and any blob nobody owns anymore. Devices keep local copies.
   */
  deleteUser(rawUsername: string): { docs: number; blobs: number } {
    const u = this.#mustUser(rawUsername)
    const now = Date.now()
    this.db.exec("BEGIN")
    try {
      const docs = this.db.prepare(
        "INSERT OR IGNORE INTO doc_tombstones (doc_id, deleted_at) SELECT doc_id, ? FROM doc_owners WHERE user_id = ?",
      ).run(now, u.id).changes
      const blobs = (this.db.prepare("SELECT count(*) AS n FROM blob_owners WHERE user_id = ?").get(u.id) as { n: number }).n
      this.db.prepare("DELETE FROM users WHERE id = ?").run(u.id)
      this.db.exec("COMMIT")
      return { docs: Number(docs), blobs }
    } catch (e) {
      this.db.exec("ROLLBACK")
      throw e
    }
  }

  setLibraryDoc(userId: number, docId: string) {
    this.db.prepare("UPDATE users SET library_doc_id = ? WHERE id = ?").run(docId, userId)
  }

  /**
   * Checks a password. Unknown users still pay for one argon2 run against a dummy hash, so
   * response time does not reveal which usernames exist.
   */
  async authenticate(rawUsername: string, password: string): Promise<User | null> {
    const row = this.db.prepare("SELECT id, username, password_hash, library_doc_id FROM users WHERE username = ?").get(normalizeUsername(rawUsername)) as
      { id: number; username: string; password_hash: string; library_doc_id: string | null } | undefined
    if (!row) {
      this.#dummyHash ??= hashPassword(randomBytes(16).toString("hex"), this.argon2)
      await verifyPassword(await this.#dummyHash, password)
      return null
    }
    if (!(await verifyPassword(row.password_hash, password))) return null
    return { id: row.id, username: row.username, libraryDocId: row.library_doc_id }
  }

  // --- sessions ----------------------------------------------------------------------------

  /** Returns the bearer token. Only its SHA-256 is stored. */
  createSession(userId: number, userAgent: string | null): string {
    const token = randomBytes(32).toString("base64url")
    const now = Date.now()
    this.db.prepare("INSERT INTO sessions (token_hash, user_id, created_at, last_seen, user_agent) VALUES (?, ?, ?, ?, ?)")
      .run(hashToken(token), userId, now, now, userAgent?.slice(0, 256) ?? null)
    return token
  }

  sessionUser(token: string, idleDays: number): SessionUser | null {
    const tokenHash = hashToken(token)
    return this.sessionByHash(tokenHash, idleDays)
  }

  sessionByHash(tokenHash: string, idleDays: number): SessionUser | null {
    const row = this.db.prepare(`
      SELECT s.last_seen, u.id, u.username, u.library_doc_id FROM sessions s JOIN users u ON u.id = s.user_id
      WHERE s.token_hash = ?`).get(tokenHash) as { last_seen: number; id: number; username: string; library_doc_id: string | null } | undefined
    if (!row) return null
    const now = Date.now()
    if (now - row.last_seen > idleDays * 86_400_000) {
      this.revokeSession(tokenHash)
      return null
    }
    if (now - row.last_seen > LAST_SEEN_RESOLUTION_MS) {
      this.db.prepare("UPDATE sessions SET last_seen = ? WHERE token_hash = ?").run(now, tokenHash)
    }
    return { id: row.id, username: row.username, libraryDocId: row.library_doc_id, sessionHash: tokenHash }
  }

  revokeSession(tokenHash: string) {
    this.db.prepare("DELETE FROM sessions WHERE token_hash = ?").run(tokenHash)
  }

  revokeAllSessions(rawUsername: string): number {
    const u = this.#mustUser(rawUsername)
    return Number(this.db.prepare("DELETE FROM sessions WHERE user_id = ?").run(u.id).changes)
  }

  sessionCountFor(rawUsername: string): number {
    const u = this.#mustUser(rawUsername)
    return (this.db.prepare("SELECT count(*) AS n FROM sessions WHERE user_id = ?").get(u.id) as { n: number }).n
  }

  /** Of the given session hashes, the ones that no longer exist. */
  missingSessions(hashes: string[]): Set<string> {
    const stmt = this.db.prepare("SELECT 1 FROM sessions WHERE token_hash = ?")
    return new Set(hashes.filter(h => stmt.get(h) === undefined))
  }

  // --- documents ---------------------------------------------------------------------------

  docOwner(docId: string): { userId: number; username: string } | null {
    const row = this.db.prepare("SELECT u.id, u.username FROM doc_owners d JOIN users u ON u.id = d.user_id WHERE d.doc_id = ?").get(docId) as
      { id: number; username: string } | undefined
    return row ? { userId: row.id, username: row.username } : null
  }

  isTombstoned(docId: string): boolean {
    return this.db.prepare("SELECT 1 FROM doc_tombstones WHERE doc_id = ?").get(docId) !== undefined
  }

  /**
   * Claims an unowned, never-deleted doc for `userId`. Returns whether `userId` owns it afterwards.
   * The INSERT is atomic, so two users racing for the same new id cannot both win.
   */
  claimDoc(docId: string, userId: number): boolean {
    if (this.isTombstoned(docId)) return false
    this.db.prepare("INSERT OR IGNORE INTO doc_owners (doc_id, user_id, claimed_at) VALUES (?, ?, ?)").run(docId, userId, Date.now())
    return this.docOwner(docId)?.userId === userId
  }

  ownsDoc(docId: string, userId: number): boolean {
    return this.db.prepare("SELECT 1 FROM doc_owners WHERE doc_id = ? AND user_id = ?").get(docId, userId) !== undefined
  }

  uncollectedTombstones(): string[] {
    return (this.db.prepare("SELECT doc_id FROM doc_tombstones WHERE collected = 0").all() as { doc_id: string }[]).map(r => r.doc_id)
  }

  markCollected(docId: string) {
    this.db.prepare("UPDATE doc_tombstones SET collected = 1 WHERE doc_id = ?").run(docId)
  }

  // --- blobs -------------------------------------------------------------------------------

  addBlobOwner(hash: string, userId: number) {
    this.db.prepare("INSERT OR IGNORE INTO blob_owners (hash, user_id, added_at) VALUES (?, ?, ?)").run(hash, userId, Date.now())
  }

  ownsBlob(hash: string, userId: number): boolean {
    return this.db.prepare("SELECT 1 FROM blob_owners WHERE hash = ? AND user_id = ?").get(hash, userId) !== undefined
  }

  blobHasOwner(hash: string): boolean {
    return this.db.prepare("SELECT 1 FROM blob_owners WHERE hash = ?").get(hash) !== undefined
  }
}
