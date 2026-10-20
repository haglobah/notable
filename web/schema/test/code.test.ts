// The code doc (ADR-002): whole immutable definitions, last writer wins per name, history from
// Automerge, and the optional codeDocUrl in the library.
import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync, existsSync } from "node:fs"
import * as A from "@automerge/automerge"
import {
  codeValue, readCode, putDef, deleteDef, codeHistory, codeFromJson, libraryFromJson, libraryValue, readLibrary,
  SchemaError, CODE_VERSION, isDefName,
  type CodeDef, type CodeImage,
} from "../src/index.ts"

const fixture = (name: string) => new URL(`../fixtures/${name}`, import.meta.url)
const json = (name: string) => JSON.parse(readFileSync(fixture(name), "utf8"))
const codeJson = json("code-v1.json")
const expected: CodeImage = codeFromJson(codeJson)
const expectedLibrary = libraryFromJson(json("library-v1.json"))
type D = Record<string, any>

const base = () => A.from(codeValue(expected) as D)
const def = (name: string, source: string, device: string, at: string, hostApi = 1): CodeDef =>
  ({ name, source, hostApi, device, updatedAt: new Date(at) })

test("code doc survives save/load", () => {
  assert.deepEqual(readCode(A.load(A.save(base()))), expected)
})

test("golden code doc written by TS reads back exactly", () => {
  assert.deepEqual(readCode(A.load(readFileSync(fixture("code-v1.automerge")))), expected)
})

test("code doc written by Kotlin reads back exactly", () => {
  const name = "code-from-kotlin.automerge"
  assert.ok(existsSync(fixture(name)), `missing ${name}; run the Kotlin GoldenFixtureTest with UPDATE_FIXTURES=1`)
  assert.deepEqual(readCode(A.load(readFileSync(fixture(name)))), expected)
})

for (const name of ["library-code-v1.automerge", "library-code-from-kotlin.automerge"]) {
  test(`library with codeDocUrl reads back: ${name}`, () => {
    assert.ok(existsSync(fixture(name)), `missing ${name}`)
    assert.deepEqual(readLibrary(A.load(readFileSync(fixture(name)))), { ...expectedLibrary, codeDocUrl: codeJson.codeDocUrl })
  })
}

test("libraries written before codeDocUrl still read, without it", () => {
  for (const name of ["library-v1.automerge", "library-from-kotlin.automerge"]) {
    const lib = readLibrary(A.load(readFileSync(fixture(name))))
    assert.equal("codeDocUrl" in lib, false)
    assert.deepEqual(lib, expectedLibrary)
  }
})

test("codeDocUrl doesn't change the library version", () => {
  const doc = A.from(libraryValue({ ...expectedLibrary, codeDocUrl: codeJson.codeDocUrl }) as D)
  assert.equal(doc.version, 1)
})

test("codeDocUrl must be a scalar string", () => {
  const doc = A.change(A.from(libraryValue(expectedLibrary) as D), d => { d.codeDocUrl = "plain JS string becomes Text" })
  assert.throws(() => readLibrary(doc), (e: unknown) => e instanceof SchemaError && /codeDocUrl/.test((e as Error).message))
})

test("source must be a scalar string, not Text", () => {
  const doc = A.change(base(), d => { d.defs["command/hello"].source = "() => 1" })
  assert.throws(() => readCode(doc), (e: unknown) => e instanceof SchemaError && e.message.includes("defs.command/hello.source"))
})

test("rejects a newer code doc version", () => {
  const doc = A.change(base(), d => { d.version = CODE_VERSION + 1 })
  assert.throws(() => readCode(doc), /newer/)
})

test("rejects a missing field and an invalid hostApi, naming the definition", () => {
  const missing = A.change(base(), d => { delete d.defs["command/hello"].device })
  assert.throws(() => readCode(missing), (e: unknown) => e instanceof SchemaError && e.message.includes("defs.command/hello.device"))
  const zero = A.change(base(), d => { d.defs["command/hello"].hostApi = 0 })
  assert.throws(() => readCode(zero), /hostApi/)
})

test("writers refuse names without a kind and non-integer hostApi", () => {
  assert.throws(() => A.change(base(), d => putDef(d, def("hello", "1", "pwa", "2026-10-20T09:00:00Z"))), /definition name/)
  assert.throws(() => A.change(base(), d => putDef(d, def("command/x", "1", "pwa", "2026-10-20T09:00:00Z", 1.5))), /hostApi/)
  assert.ok(isDefName("command/clear-page") && isDefName("toolbar/layout") && !isDefName("Command/x") && !isDefName("command/"))
})

// Two devices redefine one name at once. The CRDT must keep one entry whole, never a mix of the
// two (one's source with the other's hostApi), and both copies must agree after the merge.
test("concurrent redefinitions: the last writer wins the whole entry", () => {
  const origin = base()
  const a = A.change(A.clone(origin), d => putDef(d, def("command/hello", "() => 'from a'", "android:a", "2026-10-20T09:00:00Z", 1)))
  const b = A.change(A.clone(origin), d => putDef(d, def("command/hello", "() => 'from b'", "pwa", "2026-10-20T09:00:01Z", 2)))
  const ab = readCode(A.merge(A.clone(a), b)).defs["command/hello"]
  const ba = readCode(A.merge(A.clone(b), a)).defs["command/hello"]
  assert.deepEqual(ab, ba)
  const winner = [readCode(a).defs["command/hello"], readCode(b).defs["command/hello"]].find(x => x.source === ab.source)
  assert.deepEqual(ab, winner)
})

test("redefining one name and undefining another concurrently keeps both edits", () => {
  const origin = base()
  const a = A.change(A.clone(origin), d => putDef(d, def("command/hello", "() => 2", "android:a", "2026-10-20T09:00:00Z")))
  const b = A.change(A.clone(origin), d => { assert.ok(deleteDef(d, "command/broken")) })
  const merged = readCode(A.merge(A.clone(a), b)).defs
  assert.equal(merged["command/hello"].source, "() => 2")
  assert.equal("command/broken" in merged, false)
})

test("history lists every version, including a concurrent loser, and marks the current one", () => {
  const origin = base()
  const v2 = A.change(A.clone(origin), d => putDef(d, def("command/hello", "() => 2", "android:a", "2026-10-20T09:00:00Z")))
  const v3 = A.change(A.clone(v2), d => putDef(d, def("command/hello", "() => 3", "android:a", "2026-10-20T09:05:00Z")))
  const rival = A.change(A.clone(v2), d => putDef(d, def("command/hello", "() => rival", "pwa", "2026-10-20T09:04:00Z")))
  const merged = A.merge(A.clone(v3), rival)
  const h = codeHistory(merged, "command/hello")
  assert.deepEqual(h.map(v => v.def.source), [expected.defs["command/hello"].source, "() => 2", "() => rival", "() => 3"])
  const current = readCode(merged).defs["command/hello"]
  assert.deepEqual(h.filter(v => v.current).map(v => v.def), [current])
})

test("rollback writes an old version again as a new entry, and history survives an undef", () => {
  let doc = A.change(base(), d => putDef(d, def("command/hello", "() => 2", "android:a", "2026-10-20T09:00:00Z")))
  doc = A.change(doc, d => { deleteDef(d, "command/hello") })
  assert.equal(readCode(doc).defs["command/hello"], undefined)
  const [first] = codeHistory(doc, "command/hello")
  doc = A.change(doc, d => putDef(d, { ...first.def, updatedAt: new Date("2026-10-20T10:00:00Z"), device: "repl" }))
  const now = readCode(doc).defs["command/hello"]
  assert.equal(now.source, expected.defs["command/hello"].source)
  assert.equal(now.device, "repl")
  assert.equal(codeHistory(doc, "command/hello").at(-1)!.current, true)
})
