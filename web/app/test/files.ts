/* Small real documents for tests, built in memory: a PDF from MuPDF and a stored-zip EPUB. */
import * as mupdf from "mupdf"

export function makePdf(pages: number): Uint8Array<ArrayBuffer> {
  const doc = new mupdf.PDFDocument()
  const font = doc.addSimpleFont(new mupdf.Font("Times-Roman"))
  for (let i = 1; i <= pages; i++) {
    const res = doc.addObject({ Font: { F1: font } })
    doc.insertPage(-1, doc.addPage([0, 0, 595, 842], 0, res, `BT /F1 24 Tf 72 720 Td (Page ${i}) Tj ET`))
  }
  return new Uint8Array(doc.saveToBuffer("compress").asUint8Array())
}

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
const crc32 = (b: Uint8Array) => {
  let c = 0xffffffff
  for (const x of b) c = CRC_TABLE[(c ^ x) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

/** Uncompressed zip; EPUB requires `mimetype` first and stored, which this satisfies. */
function zip(files: [string, string][]): Uint8Array<ArrayBuffer> {
  const enc = new TextEncoder()
  const local: Uint8Array[] = []
  const central: Uint8Array[] = []
  let offset = 0
  for (const [name, text] of files) {
    const n = enc.encode(name), data = enc.encode(text), crc = crc32(data)
    const h = new DataView(new ArrayBuffer(30))
    h.setUint32(0, 0x04034b50, true); h.setUint16(4, 20, true); h.setUint32(14, crc, true)
    h.setUint32(18, data.length, true); h.setUint32(22, data.length, true); h.setUint16(26, n.length, true)
    local.push(new Uint8Array(h.buffer), n, data)
    const c = new DataView(new ArrayBuffer(46))
    c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint32(16, crc, true)
    c.setUint32(20, data.length, true); c.setUint32(24, data.length, true); c.setUint16(28, n.length, true)
    c.setUint32(42, offset, true)
    central.push(new Uint8Array(c.buffer), n)
    offset += 30 + n.length + data.length
  }
  const cdSize = central.reduce((s, b) => s + b.length, 0)
  const end = new DataView(new ArrayBuffer(22))
  end.setUint32(0, 0x06054b50, true); end.setUint16(8, files.length, true); end.setUint16(10, files.length, true)
  end.setUint32(12, cdSize, true); end.setUint32(16, offset, true)
  const parts = [...local, ...central, new Uint8Array(end.buffer)]
  const out = new Uint8Array(parts.reduce((s, b) => s + b.length, 0))
  let o = 0
  for (const p of parts) { out.set(p, o); o += p.length }
  return out
}

export function makeEpub(chapters: number): Uint8Array<ArrayBuffer> {
  const chapter = (i: number) =>
    `<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${i}</title></head><body><h1>Chapter ${i}</h1>` +
    Array.from({ length: 30 }, (_, p) => `<p>Paragraph ${p}. ${"The quick brown fox jumps over the lazy dog. ".repeat(6)}</p>`).join("") +
    "</body></html>"
  const items = Array.from({ length: chapters }, (_, i) => i + 1)
  return zip([
    ["mimetype", "application/epub+zip"],
    ["META-INF/container.xml", `<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`],
    ...items.map(i => [`OEBPS/c${i}.xhtml`, chapter(i)] as [string, string]),
    ["OEBPS/content.opf", `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">test</dc:identifier><dc:title>Test</dc:title><dc:language>en</dc:language></metadata><manifest>${items.map(i => `<item id="c${i}" href="c${i}.xhtml" media-type="application/xhtml+xml"/>`).join("")}</manifest><spine>${items.map(i => `<itemref idref="c${i}"/>`).join("")}</spine></package>`],
  ])
}
