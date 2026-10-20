import { test } from "node:test"
import assert from "node:assert/strict"
import { MAX_ZOOM, MIN_ZOOM, clampZoom, pinchTranslate, scrollCorrection } from "../src/lib/zoom.ts"

test("zoom stays between fitting the width and the sharpness limit", () => {
  assert.equal(clampZoom(0.2), MIN_ZOOM)
  assert.equal(clampZoom(1.7), 1.7)
  assert.equal(clampZoom(40), MAX_ZOOM)
})

test("the pinch preview keeps the pinched content point under the fingers", () => {
  const origin = { x: 16, y: -300 }, p = { x: 100, y: 900 }, k = 2, m = { x: 150, y: 500 }
  const t = pinchTranslate(origin, p, k, m)
  // scale(k) from the top-left, then translate: where p lands on screen.
  assert.deepEqual({ x: origin.x + t.x + p.x * k, y: origin.y + t.y + p.y * k }, m)
})

test("after the zoom is laid out, scrolling by the correction puts the point back under the fingers", () => {
  const origin = { x: 16, y: -300 }, p = { x: 100, y: 900 }, k = 1.5, m = { x: 150, y: 500 }
  const d = scrollCorrection(origin, p, k, m)
  assert.deepEqual({ x: origin.x - d.x + p.x * k, y: origin.y - d.y + p.y * k }, m)
})
