/*
 * argon2id via Node's built-in crypto.argon2, stored as a PHC string so the parameters travel
 * with each hash and can be raised later without invalidating existing passwords.
 */
import { argon2, randomBytes, timingSafeEqual } from "node:crypto"

export type Argon2Params = { memory: number; passes: number; parallelism: number }

const TAG_LENGTH = 32

function derive(password: string, salt: Buffer, p: Argon2Params, tagLength = TAG_LENGTH): Promise<Buffer> {
  return new Promise((resolve, reject) =>
    argon2("argon2id", {
      message: Buffer.from(password, "utf8"), nonce: salt,
      memory: p.memory, passes: p.passes, parallelism: p.parallelism, tagLength,
    }, (err, key) => (err ? reject(err) : resolve(key))),
  )
}

const b64 = (b: Buffer) => b.toString("base64").replace(/=+$/, "")

export async function hashPassword(password: string, p: Argon2Params): Promise<string> {
  const salt = randomBytes(16)
  const key = await derive(password, salt, p)
  return `$argon2id$v=19$m=${p.memory},t=${p.passes},p=${p.parallelism}$${b64(salt)}$${b64(key)}`
}

const PHC = /^\$argon2id\$v=19\$m=(\d+),t=(\d+),p=(\d+)\$([A-Za-z0-9+/]+)\$([A-Za-z0-9+/]+)$/

export async function verifyPassword(phc: string, password: string): Promise<boolean> {
  const m = PHC.exec(phc)
  if (!m) throw new Error("stored password hash is not an argon2id PHC string")
  const params = { memory: Number(m[1]), passes: Number(m[2]), parallelism: Number(m[3]) }
  const expected = Buffer.from(m[5], "base64")
  const actual = await derive(password, Buffer.from(m[4], "base64"), params, expected.length)
  return timingSafeEqual(actual, expected)
}
