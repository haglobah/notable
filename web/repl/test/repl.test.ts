// notable-repl against a fake device over a real WebSocket: the protocol, every command, and
// history/rollback computed from the code doc's Automerge history.
import { test, after } from "node:test"
import assert from "node:assert/strict"
import { WebSocketServer } from "ws"
import * as A from "@automerge/automerge"
import { codeValue, readCode, putDef, deleteDef, type CodeDef } from "@notable/schema"
import { connect } from "../src/client.ts"
import { run, parseArgs } from "../src/cli.ts"
import { replDevice, type DefStatus, type ImageSide, type CodeDocSide } from "../src/device.ts"
import { ReplError } from "../src/commands.ts"

const TOKEN = "0123456789abcdef0123456789abcdef"

/** A device: an in-memory code doc, and an "image" that loads whatever doesn't contain `throw`. */
function fakeDevice() {
  let doc: A.Doc<any> | null = null
  let clock = Date.parse("2026-10-20T10:00:00Z")
  const statuses = new Map<string, DefStatus>()
  const apply = () => {
    statuses.clear()
    if (doc) for (const d of Object.values(readCode(doc).defs)) {
      statuses.set(d.name, d.source.includes("throw") ? { def: d, state: "failed", error: `def:${d.name}:1: boom` } : { def: d, state: "loaded" })
    }
  }
  const image: ImageSide = {
    hostApi: 1,
    eval: async code => code === "boom" ? { value: null, error: "repl: boom", logs: ["before"] } : { value: JSON.stringify(code.length), error: null, logs: [] },
    mode: () => "running",
    defs: () => [...statuses.values()],
    commands: () => [...statuses.values()].filter(s => s.state === "loaded").map(s => ({ id: s.def.name, title: s.def.name, builtIn: false })),
    whenApplied: async def => statuses.get(def.name) ?? null,
  }
  const docs: CodeDocSide = {
    define: async (def: CodeDef) => {
      doc ??= A.from(codeValue({ defs: {} }) as Record<string, unknown>)
      doc = A.change(doc, d => putDef(d, def))
      apply()
    },
    undefine: async name => {
      if (!doc) return false
      let existed = false
      doc = A.change(doc, d => { existed = deleteDef(d, name) })
      apply()
      return existed
    },
    save: async () => (doc ? A.save(doc) : null),
    state: () => (doc ? "ready" : "none"),
  }
  const device = replDevice({ token: TOKEN, device: "fake:test", image, docs, now: () => new Date(clock += 60_000) })
  return { device, current: () => (doc ? readCode(doc).defs : {}) }
}

const fake = fakeDevice()
const server = new WebSocketServer({ host: "127.0.0.1", port: 0 })
server.on("connection", ws => {
  const conn = { authorized: false }
  ws.on("message", async data => {
    const r = await fake.device.handle(conn, String(data))
    ws.send(r.text)
    if (r.close) ws.close(1008, "unauthorized")
  })
})
await new Promise(r => server.once("listening", r))
const url = `ws://127.0.0.1:${(server.address() as { port: number }).port}`
const conn = await connect(url, TOKEN)
after(() => { conn.close(); server.close() })
const sh = (line: string, source?: string) => run(conn.call, line.split(" "), undefined, async () => source ?? "")

test("a wrong token is refused", async () => {
  await assert.rejects(connect(url, "wrong"), (e: unknown) => e instanceof ReplError && /unauthorized/.test(e.message))
})

test("hello names the device and its host API", () => {
  assert.equal(conn.hello.device, "fake:test")
  assert.equal(conn.hello.hostApi, 1)
})

test("eval prints logs and the value, and writes nothing", async () => {
  assert.equal(await sh("eval 1 + 1"), "5")
  await assert.rejects(sh("eval boom"), (e: unknown) => e instanceof ReplError && e.message === "log: before\nrepl: boom")
  assert.deepEqual(fake.current(), {})
})

test("def, ls, show, history, rollback, undef", async () => {
  assert.equal(await sh("def command/hi", "() => 'v1'"), "command/hi: loaded")
  assert.equal(await sh("def command/hi", "() => 'v2'"), "command/hi: loaded")
  assert.match(await sh("def command/bad", "() => { throw 1 }"), /written, but failed on the device: def:command\/bad:1: boom/)

  const ls = await sh("ls")
  assert.match(ls, /^image: running; code doc: ready/)
  assert.match(ls, /failed\s+command\/bad/)
  assert.match(ls, /loaded\s+command\/hi\s+api 1/)

  assert.match(await sh("show command/hi"), /\n\n\(\) => 'v2'$/)

  const h = await sh("history command/hi")
  assert.match(h, /#1 .*fake:test.*\n    \(\) => 'v1'/)
  assert.match(h, /#2 .*\(current\)\n    \(\) => 'v2'/)

  assert.match(await sh("rollback command/hi"), /command\/hi: loaded \(version #1 from/)
  assert.equal(fake.current()["command/hi"].source, "() => 'v1'")
  // The rollback is a new entry: three versions now, the newest current.
  assert.match(await sh("history command/hi"), /#3 .*\(current\)\n    \(\) => 'v1'/)

  await sh("rollback command/hi 2")
  assert.equal(fake.current()["command/hi"].source, "() => 'v2'")
  await assert.rejects(sh("rollback command/hi 9"), /versions 1–4/)

  assert.equal(await sh("undef command/hi"), "command/hi: removed")
  assert.equal(await sh("undef command/hi"), "command/hi was not defined")
  assert.match(await sh("history command/hi"), /\(command\/hi is not defined now\)$/)
  // Undefined: rollback without n brings back the newest version.
  await sh("rollback command/hi")
  assert.equal(fake.current()["command/hi"].source, "() => 'v2'")
})

test("bad requests are errors, not crashes", async () => {
  await assert.rejects(sh("def nokind", "1"), /not a definition name/)
  await assert.rejects(sh("show command/nothing"), /no definition command\/nothing/)
  await assert.rejects(sh("history"), /missing name/)
  await assert.rejects(sh("frobnicate"), /unknown command 'frobnicate'/)
})

test("arguments: url, token from the environment, host api", () => {
  const a = parseArgs(["--host-api", "2", "def", "command/x", "f.js"], { NOTABLE_REPL_TOKEN: "t" })
  assert.deepEqual(a, { url: "ws://127.0.0.1:7357", token: "t", hostApi: 2, rest: ["def", "command/x", "f.js"] })
  assert.throws(() => parseArgs(["--host-api", "0"], {}), /positive integer/)
})
