// The PWA's live code image (ADR-002) on the real engine (quickjs-emscripten), with the shared
// prelude and the golden code doc. The outcomes must match Android's LiveImageTest.
import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { Repo } from "@automerge/automerge-repo"
import * as Am from "@automerge/automerge"
import { codeFromJson, libraryFromJson, libraryValue, readCode, type CodeDef } from "@notable/schema"
import { checked, history, replDevice, rollback, type Reply, type Request } from "@notable/repl"
import { JsRuntime, HOST_API, type ImageHost } from "../src/image/engine.ts"
import { LiveImage, type BuiltIn } from "../src/image/liveImage.ts"
import { CodeDoc, type CodeState } from "../src/image/codeDoc.ts"
import { libraryView } from "../src/image/host.ts"

const fixtures = new URL("../../schema/fixtures/", import.meta.url)
const prelude = readFileSync(new URL("../../schema/runtime/prelude.js", import.meta.url), "utf8")
const golden = codeFromJson(JSON.parse(readFileSync(new URL("code-v1.json", fixtures), "utf8"))).defs

function setup(limits = { memoryBytes: 16 << 20, stackBytes: 512 << 10, evalTimeoutMs: 500 }) {
  const snacks: string[] = []
  const logs: string[] = []
  const host: ImageHost = {
    log: l => logs.push(l),
    snack: t => snacks.push(t),
    libraryJson: () => JSON.stringify({ folders: {}, notebooks: { a: { id: "a", title: "A" }, b: { id: "b", title: "B" } } }),
    pageJson: () => JSON.stringify({ id: "p-1", notebookId: "a", index: 0, pageCount: 3 }),
  }
  let builtInRuns = 0
  const builtIns: BuiltIn[] = [{ id: "command/page-info", title: "Page info", run: () => { builtInRuns++ } }]
  const image = new LiveImage(new JsRuntime(limits), host, builtIns)
  return { image, snacks, logs, builtInRuns: () => builtInRuns }
}

const def = (name: string, source: string, hostApi = 1, at = 1): CodeDef => ({ name, source, hostApi, updatedAt: new Date(at), device: "test" })
const state = (img: LiveImage, name: string) => img.defs().find(d => d.def.name === name)!
const started = async (limits?: Parameters<typeof setup>[0]) => { const s = setup(limits); await s.image.start(prelude, null); return s }

test("the golden defs load or fail with name and line, as on Android", async () => {
  const { image, snacks } = await started()
  image.apply(golden)
  assert.equal(state(image, "command/hello").state, "loaded")
  assert.equal(state(image, "command/count-notebooks").state, "loaded")
  const broken = state(image, "command/broken")
  assert.equal(broken.state, "failed")
  assert.match(broken.error!, /^def:command\/broken:3: Error: broken on purpose$/)
  assert.match(state(image, "command/syntax").error!, /^def:command\/syntax/)
  assert.equal(state(image, "command/future").error, "needs host API 2; this device has 1 (update the app)")
  assert.deepEqual(image.commands().map(c => c.id), ["command/page-info", "command/count-notebooks", "command/hello"])
  assert.equal(image.commands()[1].title, "Count notebooks")
  assert.deepEqual(image.runCommand("command/count-notebooks"), { ok: true, value: "2" })
  assert.deepEqual(snacks, ["2 notebooks"])
})

test("calls bind late: a redefinition takes effect at the next call", async () => {
  const { image } = await started()
  image.apply({ "command/a": def("command/a", "() => 'one'") })
  assert.deepEqual(image.runCommand("command/a"), { ok: true, value: "one" })
  image.apply({ "command/a": def("command/a", "() => 'two'", 1, 2) })
  assert.deepEqual(image.runCommand("command/a"), { ok: true, value: "two" })
})

test("an unchanged definition is not evaluated again", async () => {
  const { image, logs } = await started()
  const counting = def("command/a", "(notable.log('evaluated'), () => 1)")
  image.apply({ "command/a": counting })
  image.apply({ "command/a": { ...counting }, "command/b": def("command/b", "() => 2") })
  assert.deepEqual(logs, ["evaluated"])
})

test("a failed redefinition drops the old value and the built-in takes over", async () => {
  const s = await started()
  s.image.apply({ "command/page-info": def("command/page-info", "() => 'from the image'") })
  assert.deepEqual(s.image.runCommand("command/page-info"), { ok: true, value: "from the image" })
  s.image.apply({ "command/page-info": def("command/page-info", "(() => { throw new Error('nope') })()", 1, 2) })
  assert.equal(state(s.image, "command/page-info").state, "failed")
  assert.equal(s.image.commands()[0].builtIn, true)
  assert.deepEqual(s.image.runCommand("command/page-info"), { ok: true, value: null })
  assert.equal(s.builtInRuns(), 1)
})

test("a throwing command falls back to its built-in and reports the error", async () => {
  const s = await started()
  s.image.apply({ "command/page-info": def("command/page-info", "() => {\n  null.x\n}") })
  const r = s.image.runCommand("command/page-info")
  assert.equal(r.ok, false)
  assert.match((r as { error: string }).error, /^def:command\/page-info:2: TypeError: .*\(ran the built-in instead\)$/)
  assert.equal(s.builtInRuns(), 1)
  assert.ok(state(s.image, "command/page-info").lastRunError)
  assert.match((s.image.runCommand("command/missing") as { error: string }).error, /no command command\/missing/)
})

test("a definition for a newer host API is skipped and its old value dropped", async () => {
  const { image } = await started()
  image.apply({ "command/a": def("command/a", "() => 1") })
  image.apply({ "command/a": def("command/a", "() => notable.v2Only()", HOST_API + 1, 2) })
  assert.match(state(image, "command/a").error!, /^needs host API 2/)
  assert.equal(image.commands().some(c => c.id === "command/a"), false)
})

test("busy loops are stopped and the runtime keeps working", async () => {
  const { image } = await started()
  image.apply({
    "command/spin": def("command/spin", "() => { while (true) {} }"),
    "command/spin-at-load": def("command/spin-at-load", "(() => { while (true) {} })()"),
    "command/ok": def("command/ok", "() => 'fine'"),
  })
  assert.match(state(image, "command/spin-at-load").error!, /stopped after 500 ms/)
  assert.match((image.runCommand("command/spin") as { error: string }).error, /stopped after 500 ms/)
  assert.deepEqual(image.runCommand("command/ok"), { ok: true, value: "fine" })
})

test("the memory limit stops a runaway allocation", async () => {
  const { image } = await started()
  image.apply({
    "command/hog": def("command/hog", "() => { const a = []; for (;;) a.push(new Array(100000).fill(1)) }"),
    "command/ok": def("command/ok", "() => 'fine'"),
  })
  assert.equal(image.runCommand("command/hog").ok, false)
  assert.deepEqual(image.runCommand("command/ok"), { ok: true, value: "fine" })
})

test("definitions see notable and nothing of the page", async () => {
  const { image } = await started()
  const r = await image.eval("[typeof fetch, typeof document, typeof window, typeof require, typeof XMLHttpRequest, typeof notable.snack, notable.hostApi].join()")
  assert.equal(r.value, "undefined,undefined,undefined,undefined,undefined,function,1")
  assert.equal((await image.eval("typeof __host")).value, "undefined")
  assert.equal((await image.eval("notable.page()")).value, `{"id":"p-1","notebookId":"a","index":0,"pageCount":3}`)
})

test("registered commands live as long as the definition that registered them", async () => {
  const { image } = await started()
  image.apply({ "lib/tools": def("lib/tools", "(notable.registerCommand('command/from-lib', 'From lib', () => 'lib ran'), 0)") })
  assert.equal(image.commands().find(c => c.id === "command/from-lib")?.title, "From lib")
  assert.deepEqual(image.runCommand("command/from-lib"), { ok: true, value: "lib ran" })
  image.apply({})
  assert.equal(image.commands().some(c => c.id === "command/from-lib"), false)
  assert.match((await image.eval("notable.registerCommand('x', 'x', () => 1)")).error!, /while a definition is evaluated/)
})

test("eval returns the value and what it logged", async () => {
  const { image } = await started()
  assert.deepEqual(await image.eval("notable.log('a', {b: 1}); var kept = 41; kept + 1"), { value: "42", error: null, logs: ['a {"b":1}'] })
  assert.equal((await image.eval("kept")).value, "41")
  assert.ok((await image.eval("(")).error)
})

test("safe mode keeps definitions out and built-ins working", async () => {
  const s = setup()
  await s.image.start(prelude, "switched on")
  s.image.apply(golden)
  assert.match(s.image.mode(), /^safe mode/)
  assert.ok(s.image.defs().every(d => d.state === "failed"))
  assert.deepEqual(s.image.commands().map(c => c.id), ["command/page-info"])
  assert.deepEqual(s.image.runCommand("command/page-info"), { ok: true, value: null })
})

test("notable.library() matches Android's JSON", () => {
  const lib = libraryFromJson(JSON.parse(readFileSync(new URL("library-v1.json", fixtures), "utf8")))
  assert.equal(libraryView(lib), readFileSync(new URL("library-view-v1.json", fixtures), "utf8").trim())
  assert.equal(libraryView(null), `{"folders":{},"notebooks":{}}`)
})

// The REPL panel: def through the in-page device writes the code doc, which comes back to the image.
test("the in-page REPL writes the code doc, made on first use, and rolls back", async () => {
  const repo = new Repo({ network: [] })
  const library = repo.create(libraryValue({ folders: {}, notebooks: {} }) as Record<string, unknown>)
  const { image } = await started()
  const states: CodeState[] = []
  const code = new CodeDoc(repo, library, st => { states.push(st); if (st.s === "ready") image.apply(st.image.defs) })
  code.start()
  let clock = 1000
  const device = replDevice({ token: null, device: "pwa", image, docs: code, now: () => new Date(clock++) })
  const conn = { authorized: false }
  let id = 1
  const call = checked(async (req: Request) => JSON.parse((await device.handle(conn, JSON.stringify({ id: id++, ...req }))).text) as Reply)

  assert.deepEqual(await call({ op: "def", name: "command/hi", source: "() => 'v1'" }).then(r => r.state), "loaded")
  const url = (library.doc() as { codeDocUrl?: unknown }).codeDocUrl
  assert.ok(Am.isImmutableString(url), "codeDocUrl is a scalar string")
  await call({ op: "def", name: "command/hi", source: "() => 'v2'" })
  assert.deepEqual(image.runCommand("command/hi"), { ok: true, value: "v2" })
  assert.match(await history(call, "command/hi"), /#2 .*\(current\)/)
  await rollback(call, "command/hi")
  assert.deepEqual(image.runCommand("command/hi"), { ok: true, value: "v1" })
  assert.equal(readCode(Am.load((await code.save())!)).defs["command/hi"].source, "() => 'v1'")
  code.stop()
  await repo.shutdown()
})
