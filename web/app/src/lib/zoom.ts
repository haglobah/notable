/** Zoom levels of the reader's pages, relative to fitting the column's width. */
export const MIN_ZOOM = 1
/** Past this the page canvases hit MAX_CANVAS_PIXELS and stop getting sharper, at a phone's memory's cost. */
export const MAX_ZOOM = 3

export const clampZoom = (z: number) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, z))

type Point = { x: number; y: number }

/**
 * The translation that, with `scale(k)` from the content's top-left, puts content point `p`
 * (in unscaled content coordinates, measured from `origin`, the content's client top-left) under client point `m`.
 */
export function pinchTranslate(origin: Point, p: Point, k: number, m: Point): Point {
  return { x: m.x - origin.x - p.x * k, y: m.y - origin.y - p.y * k }
}

/**
 * How far to scroll so content laid out at `k` times its old size, now with its top-left at client `origin`,
 * has old content point `p` under client point `m`.
 */
export function scrollCorrection(origin: Point, p: Point, k: number, m: Point): Point {
  return { x: origin.x + p.x * k - m.x, y: origin.y + p.y * k - m.y }
}

/** A gesture's live preview is a transform; its end commits the zoom so the layout (and canvases) follow. */
export type PinchZoom = { zoomTo: (z: number, at: Point) => void; detach: () => void }

/**
 * Pinch-zooms `content` inside the horizontal scroller `scroller` (the window scrolls vertically):
 * two-finger touch, trackpad pinch (ctrl+wheel; Safari's gesture events) and ctrl+mouse wheel.
 */
export function attachPinchZoom(scroller: HTMLElement, content: HTMLElement, zoom: () => number, setZoom: (z: number) => void): PinchZoom {
  let g: { z0: number; origin: Point; p: Point; k: number; m: Point } | null = null

  const begin = (m: Point) => {
    const r = content.getBoundingClientRect()
    g = { z0: zoom(), origin: { x: r.left, y: r.top }, p: { x: m.x - r.left, y: m.y - r.top }, k: 1, m }
    content.style.transformOrigin = "0 0"
    content.style.willChange = "transform"
  }
  const update = (k: number, m: Point) => {
    if (!g) return
    g.k = clampZoom(g.z0 * k) / g.z0
    g.m = m
    const t = pinchTranslate(g.origin, g.p, g.k, m)
    content.style.transform = `translate(${t.x}px, ${t.y}px) scale(${g.k})`
  }
  const end = () => {
    if (!g) return
    const { z0, p, k, m } = g
    g = null
    content.style.transform = ""
    content.style.willChange = ""
    if (k === 1) return
    setZoom(z0 * k)
    // Solid applied the new width synchronously; reading the rect lays it out.
    const r = content.getBoundingClientRect()
    const d = scrollCorrection({ x: r.left, y: r.top }, p, k, m)
    scroller.scrollLeft += d.x
    window.scrollBy(0, d.y)
  }

  // Touch: two fingers zoom and pan together; one finger scrolls natively.
  let d0 = 0
  let touching = false
  const two = (t: TouchList) => ({
    d: Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY),
    m: { x: (t[0].clientX + t[1].clientX) / 2, y: (t[0].clientY + t[1].clientY) / 2 },
  })
  const onTouchStart = (e: TouchEvent) => {
    touching = true
    if (e.touches.length !== 2) return
    const { d, m } = two(e.touches)
    d0 = d
    begin(m)
  }
  const onTouchMove = (e: TouchEvent) => {
    if (!g || e.touches.length !== 2) return
    e.preventDefault()
    const { d, m } = two(e.touches)
    update(d / d0, m)
  }
  const onTouchEnd = (e: TouchEvent) => {
    touching = e.touches.length > 0
    if (e.touches.length < 2) end()
  }

  // Trackpad pinch arrives as ctrl+wheel in Chromium and Firefox; it has no end event.
  let wk = 1
  let wheelEnd: ReturnType<typeof setTimeout> | undefined
  const onWheel = (e: WheelEvent) => {
    if (!e.ctrlKey) return
    e.preventDefault()
    const m = { x: e.clientX, y: e.clientY }
    if (!g) { begin(m); wk = 1 }
    const dy = e.deltaMode === WheelEvent.DOM_DELTA_LINE ? e.deltaY * 16 : e.deltaY
    wk *= Math.exp(-dy * 0.01)
    update(wk, m)
    clearTimeout(wheelEnd)
    wheelEnd = setTimeout(end, 150)
  }

  // Safari: trackpad pinch on macOS; on iOS the touch handlers above do the work, so only stop the page zoom.
  type GestureEvent = UIEvent & { scale: number; clientX: number; clientY: number }
  const onGestureStart = (e: Event) => {
    e.preventDefault()
    if (!touching) begin({ x: (e as GestureEvent).clientX, y: (e as GestureEvent).clientY })
  }
  const onGestureChange = (e: Event) => {
    e.preventDefault()
    if (!touching) update((e as GestureEvent).scale, { x: (e as GestureEvent).clientX, y: (e as GestureEvent).clientY })
  }
  const onGestureEnd = (e: Event) => {
    e.preventDefault()
    if (!touching) end()
  }

  const active = { passive: false }
  scroller.addEventListener("touchstart", onTouchStart, { passive: true })
  scroller.addEventListener("touchmove", onTouchMove, active)
  scroller.addEventListener("touchend", onTouchEnd)
  scroller.addEventListener("touchcancel", onTouchEnd)
  scroller.addEventListener("wheel", onWheel, active)
  scroller.addEventListener("gesturestart", onGestureStart, active)
  scroller.addEventListener("gesturechange", onGestureChange, active)
  scroller.addEventListener("gestureend", onGestureEnd, active)

  return {
    zoomTo: (z, at) => {
      if (g) return
      begin(at)
      update(clampZoom(z) / zoom(), at)
      end()
    },
    detach: () => {
      clearTimeout(wheelEnd)
      scroller.removeEventListener("touchstart", onTouchStart)
      scroller.removeEventListener("touchmove", onTouchMove)
      scroller.removeEventListener("touchend", onTouchEnd)
      scroller.removeEventListener("touchcancel", onTouchEnd)
      scroller.removeEventListener("wheel", onWheel)
      scroller.removeEventListener("gesturestart", onGestureStart)
      scroller.removeEventListener("gesturechange", onGestureChange)
      scroller.removeEventListener("gestureend", onGestureEnd)
    },
  }
}
