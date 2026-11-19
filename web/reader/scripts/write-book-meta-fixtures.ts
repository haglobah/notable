/*
 * Writes web/schema/fixtures/book-meta/: sample EPUBs and PDFs for metadata extraction, the table
 * of contents and search (docs/plans/ebook-features.md). expected.json is written by hand, from
 * the spec; this script only makes the files. Both bookMeta.ts and the Kotlin extractor test
 * against them.
 *
 * Run: node scripts/write-book-meta-fixtures.ts (from web/reader). Regenerate only on purpose.
 */
import { mkdirSync, writeFileSync } from "node:fs"
import { crc32, deflateRawSync } from "node:zlib"
import * as mupdf from "mupdf"

const dir = new URL("../../schema/fixtures/book-meta/", import.meta.url)
mkdirSync(dir, { recursive: true })

/** A minimal zip: `mimetype` first and stored, as EPUB requires; the rest deflated. */
function zip(files: [string, string][]): Uint8Array {
  const local: Buffer[] = [], central: Buffer[] = []
  let offset = 0
  for (const [name, text] of files) {
    const data = Buffer.from(text, "utf8")
    const stored = name === "mimetype"
    const body = stored ? data : deflateRawSync(data)
    const nameBytes = Buffer.from(name, "utf8")
    const head = Buffer.alloc(30)
    head.writeUInt32LE(0x04034b50, 0); head.writeUInt16LE(20, 4); head.writeUInt16LE(0x0800, 6)
    head.writeUInt16LE(stored ? 0 : 8, 8); head.writeUInt32LE(0, 10)
    head.writeUInt32LE(crc32(data), 14); head.writeUInt32LE(body.length, 18); head.writeUInt32LE(data.length, 22)
    head.writeUInt16LE(nameBytes.length, 26); head.writeUInt16LE(0, 28)
    const cen = Buffer.alloc(46)
    cen.writeUInt32LE(0x02014b50, 0); cen.writeUInt16LE(20, 4); cen.writeUInt16LE(20, 6); cen.writeUInt16LE(0x0800, 8)
    cen.writeUInt16LE(stored ? 0 : 8, 10); cen.writeUInt32LE(0, 12)
    cen.writeUInt32LE(crc32(data), 16); cen.writeUInt32LE(body.length, 20); cen.writeUInt32LE(data.length, 24)
    cen.writeUInt16LE(nameBytes.length, 28); cen.writeUInt32LE(offset, 42)
    local.push(head, nameBytes, body)
    central.push(cen, nameBytes)
    offset += head.length + nameBytes.length + body.length
  }
  const cd = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10)
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16)
  return new Uint8Array(Buffer.concat([...local, cd, end]))
}

const CONTAINER = `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`

const chapter = (title: string, paragraphs: string[]) => `<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${title}</title></head>
<body><h1>${title}</h1>${paragraphs.map(p => `<p>${p}</p>`).join("")}</body></html>`

const filler = (n: number) => Array.from({ length: n }, (_, i) =>
  `Paragraph ${i + 1}. The ice moves slowly over the plain, and nobody hurries; the season is long and the cold is patient.`)

function epub(name: string, opf: string, chapters: [string, string][], ncx?: string) {
  writeFileSync(new URL(name, dir), zip([
    ["mimetype", "application/epub+zip"],
    ["META-INF/container.xml", CONTAINER],
    ["OEBPS/content.opf", opf],
    ...(ncx ? [["OEBPS/toc.ncx", ncx] as [string, string]] : []),
    ...chapters.map(([f, x]): [string, string] => [`OEBPS/${f}`, x]),
  ]))
}

// EPUB 2 as calibre writes it: series in calibre meta, ISBN by scheme, escaped HTML description,
// a duplicate subject, an empty creator, a uuid identifier first. Three chapters and an NCX.
epub("epub2-calibre.epub", `<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>  The Left Hand
      of Darkness </dc:title>
    <dc:creator opf:role="aut" opf:file-as="Le Guin, Ursula K.">Ursula K. Le Guin</dc:creator>
    <dc:creator opf:role="aut">  </dc:creator>
    <dc:creator opf:role="trl">Some Translator</dc:creator>
    <dc:identifier id="uid">urn:uuid:0d6c4a52-6f43-4c3a-9a3e-6f0b6c1c2d11</dc:identifier>
    <dc:identifier opf:scheme="ISBN">978-0-441-47812-5</dc:identifier>
    <dc:description>&lt;div&gt;&lt;p&gt;A lone &lt;b&gt;human&lt;/b&gt; envoy&amp;nbsp;is sent to&lt;/p&gt;&lt;p&gt;the planet Gethen &amp;amp; its &amp;#8220;ice&amp;#x201D;.&lt;/p&gt;&lt;/div&gt;</dc:description>
    <dc:publisher>Ace Books</dc:publisher>
    <dc:language>en</dc:language>
    <dc:date>1969-03-01</dc:date>
    <dc:subject>Science Fiction</dc:subject>
    <dc:subject>science fiction</dc:subject>
    <dc:subject> Gender </dc:subject>
    <dc:subject></dc:subject>
    <meta name="calibre:series" content="Hainish Cycle"/>
    <meta name="calibre:series_index" content="4.0"/>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
    <item id="c3" href="c3.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine>
</package>`, [
  ["c1.xhtml", chapter("A Parade in Erhenrang", filler(40))],
  ["c2.xhtml", chapter("The Place Inside the Blizzard", [...filler(30), "Gethen is the name of the world."])],
  ["c3.xhtml", chapter("The Mad King", [...filler(20), "Nobody on Gethen hurries."])],
], `<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head/><docTitle><text>The Left Hand of Darkness</text></docTitle>
  <navMap>
    <navPoint id="n1" playOrder="1"><navLabel><text>A Parade in Erhenrang</text></navLabel><content src="c1.xhtml"/></navPoint>
    <navPoint id="n2" playOrder="2"><navLabel><text>Part Two</text></navLabel><content src="c2.xhtml"/>
      <navPoint id="n3" playOrder="3"><navLabel><text>The Place Inside the Blizzard</text></navLabel><content src="c2.xhtml"/></navPoint>
      <navPoint id="n4" playOrder="4"><navLabel><text>The Mad King</text></navLabel><content src="c3.xhtml"/></navPoint>
    </navPoint>
  </navMap>
</ncx>`)

// EPUB 3: series as a collection with a refined position, ISBN as a urn, no description.
epub("epub3-collection.epub", `<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:isbn:0-395-27653-x</dc:identifier>
    <dc:title id="t1">A Wizard of Earthsea</dc:title>
    <dc:creator id="a1">Ursula K. Le Guin</dc:creator>
    <meta refines="#a1" property="role" scheme="marc:relators">aut</meta>
    <dc:language>en-US</dc:language>
    <dc:date>1968</dc:date>
    <meta property="belongs-to-collection" id="c1">Earthsea</meta>
    <meta refines="#c1" property="collection-type">series</meta>
    <meta refines="#c1" property="group-position">1</meta>
    <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
  </metadata>
  <manifest><item id="c1x" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="c1x"/></spine>
</package>`, [["c1.xhtml", chapter("Warriors in the Mist", filler(5))]])

// Only a title: no meta is written, the title still replaces the file name.
epub("epub-title-only.epub", `<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Field Notes</dc:title>
    <dc:identifier id="uid">urn:uuid:11111111-2222-3333-4444-555555555555</dc:identifier>
    <meta name="calibre:series_index" content="first"/>
  </metadata>
  <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="c1"/></spine>
</package>`, [["c1.xhtml", chapter("Notes", filler(3))]])

/** A PDF of [pages] text pages with an outline entry per page, and the info entries given. */
function pdf(name: string, info: Record<string, string>, pages: string[]) {
  const doc = new mupdf.PDFDocument()
  const font = doc.addSimpleFont(new mupdf.Font("Times-Roman"))
  for (const text of pages) {
    const resources = doc.addObject({ Font: { F1: font } })
    const contents = `BT /F1 14 Tf 72 720 Td (${text}) Tj ET`
    doc.insertPage(-1, doc.addPage([0, 0, 595, 842], 0, resources, contents))
  }
  for (const [k, v] of Object.entries(info)) doc.setMetaData(`info:${k}`, v)
  if (pages.length > 1) {
    const it = doc.outlineIterator()
    pages.forEach((text, i) => { it.insert({ title: `Section ${i + 1}: ${text.slice(0, 20)}`, uri: doc.formatLinkURI({ type: "Fit", chapter: 0, page: i, x: 0, y: 0, width: 0, height: 0, zoom: 0 }), open: true }) })
  }
  writeFileSync(new URL(name, dir), doc.saveToBuffer("").asUint8Array())
  doc.destroy()
}

pdf("pdf-info.pdf", {
  Title: "Paper:  CRDTs ",
  Author: "Martin Kleppmann; Annette Bieniusa and Alastair R. Beresford & Marc Shapiro",
  Subject: "Conflict-free\n replicated   data types",
  Keywords: "crdt, distributed systems; CRDT, ",
}, ["Conflict-free replicated data types", "A local-first world", "Convergence of replicas"])

pdf("pdf-bare.pdf", {}, ["Nothing to see"])

console.log("wrote book-meta fixtures")
