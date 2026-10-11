/*
 * Content-addressed file store: $STATE_DIR/blobs/<first two hex>/<sha256>. One copy on disk per
 * content; who may read it is recorded per account in blob_owners.
 */
import { createHash, randomBytes } from "node:crypto"
import { createReadStream, createWriteStream } from "node:fs"
import { mkdir, rename, rm, stat, readdir } from "node:fs/promises"
import { join } from "node:path"
import type { IncomingMessage } from "node:http"

export const HASH = /^[0-9a-f]{64}$/
const DRAIN_SLACK = 16 * 1024 * 1024

export class TooLarge extends Error {}
export class HashMismatch extends Error {}

export class BlobStore {
  readonly dir: string
  readonly tmpDir: string

  constructor(stateDir: string) {
    this.dir = join(stateDir, "blobs")
    this.tmpDir = join(stateDir, "tmp")
  }

  async init() {
    await mkdir(this.dir, { recursive: true })
    await rm(this.tmpDir, { recursive: true, force: true })
    await mkdir(this.tmpDir, { recursive: true })
  }

  path(hash: string) {
    if (!HASH.test(hash)) throw new Error("invalid blob hash")
    return join(this.dir, hash.slice(0, 2), hash)
  }

  async size(hash: string): Promise<number | null> {
    try {
      return (await stat(this.path(hash))).size
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === "ENOENT") return null
      throw e
    }
  }

  read(hash: string) {
    return createReadStream(this.path(hash))
  }

  /**
   * Streams the request body to a temp file while hashing it. Calls `beforeCommit` once the hash
   * is verified and before the file becomes visible, so ownership is recorded first (garbage
   * collection never sees an ownerless fresh blob).
   */
  async write(hash: string, body: IncomingMessage, maxBytes: number, beforeCommit: () => void): Promise<void> {
    const tmp = join(this.tmpDir, randomBytes(12).toString("hex"))
    const out = createWriteStream(tmp, { flags: "wx" })
    const hasher = createHash("sha256")
    let size = 0
    try {
      await new Promise<void>((resolve, reject) => {
        body.on("data", (chunk: Buffer) => {
          size += chunk.length
          if (size > maxBytes) {
            // Keep reading (and discarding) a little longer so the client sees the 413 instead of
            // a reset connection; give up on clients that keep sending far beyond the limit.
            if (size > maxBytes + DRAIN_SLACK) body.destroy()
            return
          }
          hasher.update(chunk)
          if (!out.write(chunk)) {
            body.pause()
            out.once("drain", () => body.resume())
          }
        })
        body.on("end", () => out.end(resolve))
        body.on("close", () => { if (!body.complete) reject(new Error("upload aborted")) })
        body.on("error", reject)
        out.on("error", reject)
      })
      if (size > maxBytes) throw new TooLarge()
      if (hasher.digest("hex") !== hash) throw new HashMismatch()
      beforeCommit()
      const final = this.path(hash)
      await mkdir(join(this.dir, hash.slice(0, 2)), { recursive: true })
      if ((await this.size(hash)) === null) await rename(tmp, final)
    } finally {
      out.destroy()
      await rm(tmp, { force: true })
    }
  }

  async *all(): AsyncGenerator<string> {
    for (const prefix of await readdir(this.dir)) {
      for (const name of await readdir(join(this.dir, prefix))) if (HASH.test(name)) yield name
    }
  }

  async remove(hash: string) {
    await rm(this.path(hash), { force: true })
  }
}
