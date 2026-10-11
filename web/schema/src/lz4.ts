/**
 * LZ4 block-format decompression (no frame header), as written by lz4-java's
 * `LZ4Factory.highCompressor()` in the Android app. The caller supplies the raw size.
 */
export class Lz4Error extends Error {}

export function lz4DecompressBlock(src: Uint8Array, rawSize: number): Uint8Array {
  const out = new Uint8Array(rawSize)
  let ip = 0
  let op = 0
  while (ip < src.length) {
    const token = src[ip++]
    let litLen = token >>> 4
    if (litLen === 15) {
      let b: number
      do {
        if (ip >= src.length) throw new Lz4Error("truncated literal length")
        b = src[ip++]
        litLen += b
      } while (b === 255)
    }
    if (ip + litLen > src.length) throw new Lz4Error("truncated literals")
    if (op + litLen > rawSize) throw new Lz4Error("output overflow in literals")
    out.set(src.subarray(ip, ip + litLen), op)
    ip += litLen
    op += litLen
    if (ip === src.length) break // the last sequence has literals only
    if (ip + 2 > src.length) throw new Lz4Error("truncated match offset")
    const offset = src[ip] | (src[ip + 1] << 8)
    ip += 2
    if (offset === 0 || offset > op) throw new Lz4Error(`bad match offset ${offset}`)
    let matchLen = (token & 15) + 4
    if ((token & 15) === 15) {
      let b: number
      do {
        if (ip >= src.length) throw new Lz4Error("truncated match length")
        b = src[ip++]
        matchLen += b
      } while (b === 255)
    }
    if (op + matchLen > rawSize) throw new Lz4Error("output overflow in match")
    // Byte by byte: matches may overlap their own output.
    for (let i = 0; i < matchLen; i++, op++) out[op] = out[op - offset]
  }
  if (op !== rawSize) throw new Lz4Error(`decompressed ${op} bytes, expected ${rawSize}`)
  return out
}
