import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import * as mupdf from "mupdf"
import { cleanDescription, decodeEntities, epubMeta, opfMeta, pdfMeta } from "../src/lib/bookMeta.ts"
import { outlineOf, searchDoc, snippet } from "../src/lib/bookText.ts"
import { EPUB_LAYOUT } from "../src/lib/pages.ts"

const dir = new URL("../../schema/fixtures/book-meta/", import.meta.url)
type Expected = {
  title: string | null; meta: unknown; pages: number
  outline: { title: string; page: number; depth: number }[]
  search?: { query: string; pages: number[] }
}
const expected: Record<string, Expected> = JSON.parse(readFileSync(new URL("expected.json", dir), "utf8")).files

const open = (name: string) => {
  const bytes = readFileSync(new URL(name, dir))
  const epub = name.endsWith(".epub")
  const doc = mupdf.Document.openDocument(bytes, epub ? "application/epub+zip" : "application/pdf")
  if (epub) doc.layout(EPUB_LAYOUT.w, EPUB_LAYOUT.h, EPUB_LAYOUT.em)
  return { bytes: new Uint8Array(bytes), doc, epub }
}

for (const [name, want] of Object.entries(expected)) {
  test(`metadata of ${name}`, async () => {
    const { bytes, doc, epub } = open(name)
    try {
      const got = epub ? await epubMeta(bytes) : pdfMeta(k => doc.getMetaData(k))
      assert.deepEqual(got, { title: want.title, meta: want.meta })
    } finally {
      doc.destroy()
    }
  })

  test(`pages and outline of ${name}`, () => {
    const { doc } = open(name)
    try {
      assert.equal(doc.countPages(), want.pages)
      assert.deepEqual(outlineOf(doc), want.outline)
    } finally {
      doc.destroy()
    }
  })

  if (want.search) {
    const s = want.search
    test(`search ${JSON.stringify(s.query)} in ${name}`, () => {
      const { doc } = open(name)
      try {
        const hits = searchDoc(doc, s.query)
        assert.deepEqual(hits.map(h => h.page), s.pages)
        for (const h of hits) {
          assert.ok(h.quads.length > 0 && h.quads.every(q => q.length === 8))
          assert.match(h.snippet.toLowerCase(), new RegExp(s.query))
        }
      } finally {
        doc.destroy()
      }
    })
  }
}

test("a blank query searches nothing", () => {
  const { doc } = open("pdf-info.pdf")
  try { assert.deepEqual(searchDoc(doc, "  "), []) } finally { doc.destroy() }
})

test("snippet cuts with an ellipsis on each side", () => {
  const text = `${"a".repeat(60)} needle ${"b".repeat(60)}`
  const s = snippet(text, "NEEDLE")
  assert.equal(s, `…${"a".repeat(39)} needle ${"b".repeat(39)}…`)
  assert.equal(snippet("short needle text", "needle"), "short needle text")
})

test("entities: named, numeric, unknown left alone", () => {
  assert.equal(decodeEntities("&amp;&lt;&#65;&#x42;&eacute;&nbsp;"), "&<AB&eacute; ")
})

test("description: tags become spaces, cut at 4000", () => {
  assert.equal(cleanDescription("<p>one</p><p>two</p>"), "one two")
  assert.equal(cleanDescription("<br/>"), null)
  assert.equal(cleanDescription("x".repeat(5000))!.length, 4000)
})

test("an OPF without metadata gives nothing", () => {
  assert.deepEqual(opfMeta("<package/>"), { title: null, meta: null })
})

test("an EPUB that isn't a zip is refused", async () => {
  await assert.rejects(epubMeta(new Uint8Array([1, 2, 3])), /not a zip file/)
})
