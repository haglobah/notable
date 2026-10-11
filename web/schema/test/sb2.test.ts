import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { decodeStrokePoints, Sb2Error } from "../src/sb2.ts"

// Written by the Kotlin encoder: app/src/test/java/com/ethran/notable/sync/automerge/Sb2FixtureTest.kt
type Case = {
  name: string
  compression: number
  bytes: string
  points: { x: number; y: number; pressure: number | null; tiltX: number | null; tiltY: number | null; dt: number | null }[]
}
const fixture: { cases: Case[] } = JSON.parse(readFileSync(new URL("../fixtures/sb2-strokes.json", import.meta.url), "utf8"))

test("fixture covers raw and LZ4 bodies", () => {
  const flags = new Set(fixture.cases.map(c => c.compression))
  assert.deepEqual([...flags].sort(), [0, 1])
})

for (const c of fixture.cases) {
  test(`decodes Kotlin SB2 bytes: ${c.name}`, () => {
    const decoded = decodeStrokePoints(Buffer.from(c.bytes, "base64"))
    assert.equal(decoded.version, 2)
    assert.equal(decoded.compression, c.compression)
    assert.equal(decoded.points.length, c.points.length)
    decoded.points.forEach((p, i) => {
      const e = c.points[i]
      assert.ok(Math.abs(p.x - e.x) < 1e-3, `x[${i}] ${p.x} != ${e.x}`)
      assert.ok(Math.abs(p.y - e.y) < 1e-3, `y[${i}] ${p.y} != ${e.y}`)
      if (e.pressure === null) assert.equal(p.pressure, null)
      else assert.ok(Math.abs(p.pressure! - e.pressure) <= 1 / 65535, `pressure[${i}]`)
      assert.equal(p.tiltX, e.tiltX)
      assert.equal(p.tiltY, e.tiltY)
      assert.equal(p.dt, e.dt)
    })
  })
}

test("rejects bad magic", () => {
  assert.throws(() => decodeStrokePoints(new Uint8Array(20)), Sb2Error)
})

test("rejects truncated input", () => {
  const bytes = Buffer.from(fixture.cases[0].bytes, "base64")
  assert.throws(() => decodeStrokePoints(bytes.subarray(0, bytes.length - 3)), Sb2Error)
})

test("rejects a newer format version", () => {
  const bytes = Uint8Array.from(Buffer.from(fixture.cases[0].bytes, "base64"))
  bytes[2] = 3
  assert.throws(() => decodeStrokePoints(bytes), Sb2Error)
})
