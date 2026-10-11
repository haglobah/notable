/**
 * Decoder for Notable's stroke point encoding (SB, versions 1 and 2), mirroring
 * app/src/main/java/com/ethran/notable/data/db/StrokePointConverter.kt.
 *
 * Header (little-endian): 'S' 'B' version mask count:int32 compression:uint8
 * Body (optionally LZ4, then preceded by raw size:int32):
 *   xLen:int32 x:polyline  yLen:int32 y:polyline  [pressure uint16 (v2) | int16 (v1)]
 *   [tiltX int8] [tiltY int8] [dt uint16]
 */
import { lz4DecompressBlock } from "./lz4.ts"

export class Sb2Error extends Error {}

export type StrokePoint = {
  x: number
  y: number
  /** v2: normalized [0, 1]. v1: raw digitizer value. */
  pressure: number | null
  tiltX: number | null
  tiltY: number | null
  dt: number | null
}

export type DecodedStroke = { version: number; mask: number; compression: number; points: StrokePoint[] }

const HEADER = 9
const MAX_VERSION = 2
const PRESSURE = 1, TILT_X = 2, TILT_Y = 4, DT = 8
const XY_PRECISION = 2

export function decodeStrokePoints(bytes: Uint8Array): DecodedStroke {
  if (bytes.length < HEADER + 8) throw new Sb2Error("too small for an SB header")
  if (bytes[0] !== 0x53 || bytes[1] !== 0x42) throw new Sb2Error("bad magic, not an SB stroke")
  const version = bytes[2]
  if (version > MAX_VERSION) throw new Sb2Error(`unsupported SB version ${version}`)
  const mask = bytes[3]
  const head = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const count = head.getInt32(4, true)
  if (count < 0) throw new Sb2Error("negative point count")
  const compression = bytes[8]

  let body: Uint8Array
  if (compression === 0) {
    body = bytes.subarray(HEADER)
  } else if (compression === 1) {
    if (bytes.length <= HEADER + 4) throw new Sb2Error("truncated: missing raw size")
    const rawSize = head.getInt32(HEADER, true)
    if (rawSize <= 0) throw new Sb2Error(`invalid raw size ${rawSize}`)
    try {
      body = lz4DecompressBlock(bytes.subarray(HEADER + 4), rawSize)
    } catch (e) {
      throw new Sb2Error(`LZ4: ${(e as Error).message}`)
    }
  } else {
    throw new Sb2Error(`unknown compression flag ${compression}`)
  }

  const r = new Cursor(body)
  const xs = decodePolyline(r.bytes(r.int32()))
  const ys = decodePolyline(r.bytes(r.int32()))
  if (xs.length !== count || ys.length !== count) {
    throw new Sb2Error(`point count mismatch: xs=${xs.length} ys=${ys.length} count=${count}`)
  }
  const pressure = mask & PRESSURE
    ? Array.from({ length: count }, () => version >= 2 ? r.uint16() / 65535 : r.int16())
    : null
  const tiltX = mask & TILT_X ? Array.from({ length: count }, () => r.int8()) : null
  const tiltY = mask & TILT_Y ? Array.from({ length: count }, () => r.int8()) : null
  const dt = mask & DT ? Array.from({ length: count }, () => r.uint16()) : null
  if (compression === 0 && r.remaining() > 0) throw new Sb2Error("trailing bytes after stroke")

  const points = xs.map((x, i): StrokePoint => ({
    x, y: ys[i],
    pressure: pressure?.[i] ?? null,
    tiltX: tiltX?.[i] ?? null,
    tiltY: tiltY?.[i] ?? null,
    // 0xFFFF is reserved as a per-point "no dt" sentinel.
    dt: dt ? (dt[i] === 0xffff ? null : dt[i]) : null,
  }))
  return { version, mask, compression, points }
}

class Cursor {
  private pos = 0
  private buf: Uint8Array
  private view: DataView
  constructor(buf: Uint8Array) {
    this.buf = buf
    this.view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength)
  }
  private need(n: number) {
    if (this.pos + n > this.buf.length) throw new Sb2Error("truncated stroke body")
  }
  remaining() { return this.buf.length - this.pos }
  int32() { this.need(4); const v = this.view.getInt32(this.pos, true); this.pos += 4; return v }
  int16() { this.need(2); const v = this.view.getInt16(this.pos, true); this.pos += 2; return v }
  uint16() { this.need(2); const v = this.view.getUint16(this.pos, true); this.pos += 2; return v }
  int8() { this.need(1); const v = this.view.getInt8(this.pos); this.pos += 1; return v }
  bytes(n: number) {
    if (n < 0) throw new Sb2Error("negative section length")
    this.need(n); const v = this.buf.subarray(this.pos, this.pos + n); this.pos += n; return v
  }
}

/** Google polyline algorithm over one channel, as in EncodePolyline.kt. */
function decodePolyline(chars: Uint8Array): number[] {
  const factor = 10 ** XY_PRECISION
  const out: number[] = []
  let acc = 0
  let value = 0
  let shift = 0
  let open = false
  for (const c of chars) {
    const chunk = c - 63
    if (chunk < 0 || chunk > 63) throw new Sb2Error("invalid polyline character")
    value |= (chunk & 0x1f) << shift
    shift += 5
    open = true
    if ((chunk & 0x20) === 0) {
      const delta = value & 1 ? ~value >> 1 : value >> 1
      acc += delta / factor
      out.push(Math.round(acc * factor) / factor)
      value = 0
      shift = 0
      open = false
    }
  }
  if (open) throw new Sb2Error("truncated polyline")
  return out
}
