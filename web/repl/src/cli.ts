#!/usr/bin/env node
/*
 * notable-repl: talks to the live code image on a device (ADR-002). See README.md for adb forward.
 */
import { readFileSync } from "node:fs"
import { createInterface } from "node:readline"
import { connect } from "./client.ts"
import * as cmd from "./commands.ts"
import { ReplError, type Call } from "./commands.ts"
import { DEFAULT_PORT } from "./protocol.ts"

const USAGE = `usage: notable-repl [--url URL] [--token TOKEN] [command]

  (no command)                    interactive: each line is evaluated; :help lists the commands
  eval <code>                     run code in the live runtime; writes nothing to the doc
  def <name> [file] [--host-api N]
                                  commit a definition (source from file, or stdin), synced to every device
  undef <name>                    remove a definition
  ls                              definitions with their state on the device, and the menu commands
  show <name>                     a definition's source and state
  history <name>                  every version of a definition, from the code doc's history
  rollback <name> [n]             write version n again (default: the one before the current)

  --url     default ws://127.0.0.1:${DEFAULT_PORT} (after: adb forward tcp:${DEFAULT_PORT} tcp:${DEFAULT_PORT})
  --token   default $NOTABLE_REPL_TOKEN; shown on the device in Settings → Sync → Live code`

const INTERACTIVE_HELP = `:ls  :show <name>  :history <name>  :rollback <name> [n]  :undef <name>
:def <name> [hostApi]   then the source, ended by a line with a single "."
:q   quit                anything else is evaluated`

export type Args = { url: string; token: string | undefined; hostApi?: number; rest: string[] }

export function parseArgs(argv: string[], env: Record<string, string | undefined>): Args {
  const out: Args = { url: `ws://127.0.0.1:${DEFAULT_PORT}`, token: env.NOTABLE_REPL_TOKEN, rest: [] }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    const value = () => {
      const v = argv[++i]
      if (v === undefined) throw new ReplError(`${a} needs a value`)
      return v
    }
    if (a === "--url") out.url = value()
    else if (a === "--token") out.token = value()
    else if (a === "--host-api") {
      const n = Number(value())
      if (!Number.isInteger(n) || n < 1) throw new ReplError("--host-api must be a positive integer")
      out.hostApi = n
    } else if (a === "-h" || a === "--help") throw new ReplError(USAGE)
    else out.rest.push(a)
  }
  return out
}

const need = (v: string | undefined, what: string) => {
  if (v === undefined || v === "") throw new ReplError(`missing ${what}\n\n${USAGE}`)
  return v
}

async function readStdin(): Promise<string> {
  const chunks: Buffer[] = []
  for await (const c of process.stdin) chunks.push(c as Buffer)
  return Buffer.concat(chunks).toString("utf8")
}

/** One command; returns what to print. */
export async function run(call: Call, rest: string[], hostApi: number | undefined, readSource: (file?: string) => Promise<string>): Promise<string> {
  const [op, a, b] = rest
  switch (op) {
    case "eval": return cmd.evalCode(call, need(rest.slice(1).join(" "), "code"))
    case "def": return cmd.def(call, need(a, "name"), (await readSource(b)).trimEnd(), hostApi)
    case "undef": return cmd.undef(call, need(a, "name"))
    case "ls": return cmd.ls(call)
    case "show": return cmd.show(call, need(a, "name"))
    case "history": return cmd.history(call, need(a, "name"))
    case "rollback": {
      const n = b === undefined ? undefined : Number(b)
      if (n !== undefined && !(Number.isInteger(n) && n > 0)) throw new ReplError("rollback: n must be a version number from history")
      return cmd.rollback(call, need(a, "name"), n)
    }
    default: throw new ReplError(`unknown command '${op}'\n\n${USAGE}`)
  }
}

async function interactive(call: Call, device: string) {
  const rl = createInterface({ input: process.stdin, output: process.stdout, prompt: "notable> " })
  process.stdout.write(`connected to ${device}. ${INTERACTIVE_HELP.split("\n")[0]}\n`)
  let collecting: { name: string; hostApi?: number; lines: string[] } | null = null
  // Piped input closes the interface before the last lines are handled; prompt only while open.
  let open = true
  rl.on("close", () => { open = false })
  const prompt = () => { if (open) rl.prompt() }
  prompt()
  for await (const line of rl) {
    try {
      if (collecting) {
        if (line.trim() === ".") {
          const c = collecting
          collecting = null
          console.log(await cmd.def(call, c.name, c.lines.join("\n"), c.hostApi))
        } else collecting.lines.push(line)
        if (collecting) continue
      } else if (line.startsWith(":")) {
        const [op, ...args] = line.slice(1).trim().split(/\s+/)
        if (op === "q" || op === "quit") break
        if (op === "help") console.log(INTERACTIVE_HELP)
        else if (op === "def") {
          const hostApi = args[1] === undefined ? undefined : Number(args[1])
          collecting = { name: need(args[0], "name"), hostApi, lines: [] }
          continue
        } else console.log(await run(call, [op, ...args], undefined, async () => { throw new ReplError("use :def <name>, then the source") }))
      } else if (line.trim() !== "") console.log(await cmd.evalCode(call, line))
    } catch (e) {
      if (!(e instanceof ReplError)) throw e
      console.log(`error: ${e.message}`)
    }
    prompt()
  }
  rl.close()
}

async function main() {
  const args = parseArgs(process.argv.slice(2), process.env)
  const token = need(args.token, "token (--token or NOTABLE_REPL_TOKEN)")
  const conn = await connect(args.url, token)
  try {
    if (args.rest.length === 0) await interactive(conn.call, conn.hello.device)
    else console.log(await run(conn.call, args.rest, args.hostApi, async file => (file === undefined || file === "-" ? readStdin() : readFileSync(file, "utf8"))))
  } finally {
    conn.close()
  }
}

if (import.meta.main) {
  main().catch(e => {
    process.stderr.write(`${e instanceof ReplError ? e.message : (e as Error).stack}\n`)
    process.exit(1)
  })
}
