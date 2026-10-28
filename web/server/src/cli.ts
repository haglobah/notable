#!/usr/bin/env node
/*
 * Admin CLI. Works on the same SQLite file as a running server; changes apply to the server's
 * next request, and revoked sessions' open sync connections close within 30 seconds.
 */
import { join } from "node:path"
import { mkdirSync } from "node:fs"
import { createInterface } from "node:readline"
import { Writable } from "node:stream"
import { Accounts, AccountError } from "./accounts.ts"
import { argon2FromEnv } from "./config.ts"

const USAGE = `usage: notable-admin <command>

  user add <name>         create an account; reads the password from stdin
  user passwd <name>      set a new password (logs the user out everywhere)
  user del <name>         delete an account; asks you to type the name to confirm
  user list               list accounts
  user link <name> <email>  let this humane tools account sign in as <name>
  user unlink <name>      remove the link to humane tools
  sessions revoke <name>  log a user out everywhere

environment: NOTABLE_STATE_DIR (required)`

function fail(msg: string): never {
  process.stderr.write(`${msg}\n`)
  process.exit(1)
}

/** One line from stdin; without echo when stdin is a terminal. */
async function readLine(prompt: string, hidden: boolean): Promise<string> {
  const tty = process.stdin.isTTY
  let muted = false
  const output = new Writable({
    write(chunk, _enc, cb) {
      if (!muted) process.stderr.write(chunk)
      cb()
    },
  })
  const rl = createInterface({ input: process.stdin, output, terminal: tty })
  if (tty) process.stderr.write(prompt)
  muted = hidden
  const line = await new Promise<string>(resolve => {
    rl.once("line", resolve)
    rl.once("close", () => resolve(""))
  })
  rl.close()
  if (tty && hidden) process.stderr.write("\n")
  return line
}

async function newPassword(): Promise<string> {
  const pw = await readLine("Password: ", true)
  if (process.stdin.isTTY) {
    const again = await readLine("Repeat: ", true)
    if (again !== pw) fail("passwords do not match")
  }
  return pw
}

async function main(args: string[]) {
  const stateDir = process.env.NOTABLE_STATE_DIR
  if (!stateDir) fail(`NOTABLE_STATE_DIR is not set\n\n${USAGE}`)
  const [cmd, sub, name, email] = args
  const needName = () => name ?? fail(USAGE)

  mkdirSync(stateDir, { recursive: true })
  const acc = Accounts.open(join(stateDir, "notable.db"), argon2FromEnv())
  try {
    switch (`${cmd} ${sub}`) {
      case "user add": {
        const n = Accounts.validateUsername(needName())
        const u = await acc.createUser(n, await newPassword())
        console.log(`created ${u.username}`)
        break
      }
      case "user passwd": {
        needName()
        await acc.setPassword(name, await newPassword())
        console.log(`password changed for ${name}; all sessions revoked`)
        break
      }
      case "user del": {
        const n = needName()
        if (!acc.userByName(n)) fail(`no user ${n}`)
        const typed = await readLine(`Type "${n}" to delete the account and its synced data: `, false)
        if (typed.trim().toLowerCase() !== n.trim().toLowerCase()) fail("not confirmed; nothing deleted")
        const { docs, blobs } = acc.deleteUser(n)
        console.log(`deleted ${n}: ${docs} docs tombstoned, ${blobs} file references removed. ` +
          "The server removes the stored data at its next garbage collection (on start, and every 6 hours).")
        break
      }
      case "user list": {
        for (const u of acc.listUsers()) {
          console.log(`${u.username}\tcreated ${new Date(u.createdAt).toISOString()}\t${u.sessions} sessions\t${u.docs} docs\t${u.blobs} files` +
            `${u.htEmail ? `\thumane tools ${u.htEmail}` : ""}`)
        }
        break
      }
      case "user link": {
        needName()
        acc.linkHt(name, email ?? fail(USAGE))
        console.log(`${email} can now sign in as ${name}; its password stays valid`)
        break
      }
      case "user unlink": {
        needName()
        acc.linkHt(name, null)
        console.log(`${name} no longer signs in with humane tools; existing sessions stay until revoked`)
        break
      }
      case "sessions revoke": {
        const count = acc.revokeAllSessions(needName())
        console.log(`revoked ${count} sessions for ${name}`)
        break
      }
      default:
        fail(USAGE)
    }
  } catch (e) {
    if (e instanceof AccountError) fail(e.message)
    throw e
  } finally {
    acc.close()
  }
}

await main(process.argv.slice(2))
