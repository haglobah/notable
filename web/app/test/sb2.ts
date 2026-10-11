/* SB2 encoder for test fixtures (uncompressed), the inverse of web/schema/src/sb2.ts. */

function polyline(values: number[]): number[] {
  const out: number[] = []
  let prev = 0
  for (const v of values) {
    const cur = Math.round(v * 100)
    let d = cur - prev
    prev = cur
    d = d < 0 ? ~(d << 1) : d << 1
    while (d >= 0x20) {
      out.push((0x20 | (d & 0x1f)) + 63)
      d >>>= 5
    }
    out.push(d + 63)
  }
  return out
}

/** Points as [x, y] or [x, y, pressure 0..1]. Pressure is included when every point has it. */
export function encodeSb2(points: number[][]): Uint8Array {
  const withPressure = points.every(p => p.length > 2)
  const xs = polyline(points.map(p => p[0]))
  const ys = polyline(points.map(p => p[1]))
  const size = 9 + 4 + xs.length + 4 + ys.length + (withPressure ? points.length * 2 : 0)
  const buf = new Uint8Array(size)
  const v = new DataView(buf.buffer)
  buf[0] = 0x53; buf[1] = 0x42; buf[2] = 2; buf[3] = withPressure ? 1 : 0
  v.setInt32(4, points.length, true)
  buf[8] = 0
  let o = 9
  v.setInt32(o, xs.length, true); o += 4; buf.set(xs, o); o += xs.length
  v.setInt32(o, ys.length, true); o += 4; buf.set(ys, o); o += ys.length
  if (withPressure) for (const p of points) { v.setUint16(o, Math.round(p[2] * 65535), true); o += 2 }
  return buf
}
