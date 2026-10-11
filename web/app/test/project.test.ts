/*
 * Download-as-PDF projection. Checked with MuPDF (structure) and, independently, with qpdf and
 * poppler (validity, attachment, and where the ink lands in pixels).
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { execFileSync } from "node:child_process"
import { mkdtempSync, writeFileSync, readFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import * as A from "@automerge/automerge"
import * as mupdf from "mupdf"
import { readNotebook } from "@notable/schema"
import { project, pdfFileName, ProjectionError, EMBEDDED_DOC, EMBEDDED_META } from "../src/lib/project.ts"
import { makeEpub, makePdf } from "./files.ts"
import { BLACK, RED, entry, nativeBg, notebookDoc, page, pdfBg, stroke } from "./fixtures.ts"

const blobsOf = (m: Record<string, Uint8Array>) => async (h: string) => {
  const b = m[h]
  if (!b) throw new Error(`no blob ${h}`)
  return b
}
const open = (bytes: Uint8Array) => new mupdf.PDFDocument(bytes)
const near = (a: number, b: number, eps = 0.05) => Math.abs(a - b) < eps

// poppler's tools exit non-zero on --version, so ask the shell whether they exist.
const has = (cmd: string) => { try { execFileSync("sh", ["-c", `command -v ${cmd}`], { stdio: "ignore" }); return true } catch { return false } }
const tools = has("qpdf") && has("pdftoppm") && has("pdfdetach")
const toolsSkip = tools ? false : "qpdf/poppler not on PATH (run inside `nix shell nixpkgs#qpdf nixpkgs#poppler-utils`)"
const tmp = () => mkdtempSync(join(tmpdir(), "notable-project-"))

/** Gray value (0 black .. 255 white) at a point, from poppler's render at 72 dpi (1 px = 1 pt). */
function popplerPixel(pdf: Uint8Array, pageNo: number, x: number, y: number): number {
  const dir = tmp()
  writeFileSync(join(dir, "in.pdf"), pdf)
  execFileSync("pdftoppm", ["-r", "72", "-gray", "-f", String(pageNo), "-l", String(pageNo), "-singlefile", join(dir, "in.pdf"), join(dir, "out")])
  const file = readFileSync(join(dir, "out.pgm"))
  // Binary PGM: "P5" w h maxval, each followed by one whitespace byte, then rows of bytes.
  let pos = 0
  const fields: number[] = []
  while (fields.length < 3) {
    while (file[pos] === 0x0a || file[pos] === 0x20) pos++
    let tok = ""
    while (file[pos] !== 0x0a && file[pos] !== 0x20) tok += String.fromCharCode(file[pos++])
    if (tok !== "P5") fields.push(Number(tok))
  }
  const w = fields[0]
  return file[pos + 1 + Math.round(y) * w + Math.round(x)]
}

// A horizontal stroke across the middle of the canvas: 930..1395 px at y = 1240 px.
const MID = [[930, 1240], [1100, 1240], [1395, 1240]]

test("PDF notebook: original pages kept, strokes become /Ink at the Android-mapped position", async () => {
  const src = makePdf(2)
  const doc = notebookDoc([
    page("p0", pdfBg("src", 0), [stroke("s1", MID, { size: 9 })]),
    page("p1", pdfBg("src", 1), [stroke("s2", [[100, 100], [600, 100]], { pen: "MARKER", color: RED, size: 30 })]),
  ])
  const out = await project(doc, entry("Paper", { kind: "pdf", blob: "src" }), blobsOf({ src }))
  const pdf = open(out)
  assert.equal(pdf.countPages(), 2)
  assert.match(pdf.loadPage(0).toStructuredText("").asText(), /Page 1/, "original page content survives")

  const [ink] = pdf.loadPage(0).getAnnotations()
  assert.equal(ink.getType(), "Ink")
  const s = 595 / 1860
  const pts = ink.getInkList()[0]
  assert.ok(near(pts[0][0], 930 * s) && near(pts[0][1], 1240 * s), `first point ${pts[0]}`)
  assert.ok(near(pts[2][0], 1395 * s), `last point ${pts[2]}`)
  assert.deepEqual(ink.getColor(), [0, 0, 0])
  assert.ok(near(ink.getBorderWidth(), 9 * s, 0.01))

  const [marker] = pdf.loadPage(1).getAnnotations()
  assert.deepEqual(marker.getColor(), [1, 0, 0])
  assert.ok(near(marker.getOpacity(), 100 / 255, 0.01))
})

test("embedded notebook snapshot and metadata round-trip", async () => {
  const src = makePdf(1)
  const doc = notebookDoc([page("p0", pdfBg("src", 0), [stroke("s1", MID)])])
  const e = entry("Paper", { kind: "pdf", blob: "src" })
  const pdf = open(await project(doc, e, blobsOf({ src })))
  const files = pdf.getEmbeddedFiles()
  assert.deepEqual(Object.keys(files).sort(), [EMBEDDED_DOC, EMBEDDED_META].sort())
  const snapshot = pdf.getEmbeddedFileContents(files[EMBEDDED_DOC])!.asUint8Array()
  assert.deepEqual(readNotebook(A.load(snapshot)), readNotebook(doc))
  const meta = JSON.parse(new TextDecoder().decode(pdf.getEmbeddedFileContents(files[EMBEDDED_META])!.asUint8Array()))
  assert.equal(meta.notebookId, "nb-1")
  assert.equal(meta.entry.title, "Paper")
  assert.equal(meta.entry.docUrl, e.docUrl)
  assert.equal(pdf.getMetaData("info:Title"), "Paper")
})

test("canvasWidth from the doc changes the mapping (a Boox with a narrower screen)", async () => {
  const src = makePdf(1)
  const doc = notebookDoc([page("p0", pdfBg("src", 0), [stroke("narrow", [[702, 100], [1404, 100]])])], { canvasWidth: 1404 })
  const pdf = open(await project(doc, entry("Paper", { kind: "pdf", blob: "src" }), blobsOf({ src })))
  const pts = pdf.loadPage(0).getAnnotations()[0].getInkList()[0]
  assert.ok(near(pts[0][0], 297.5) && near(pts[1][0], 595), `got ${pts}`)
})

test("EPUB notebook: laid out with the stored layout, text kept, ink mapped onto its pages", async () => {
  const epub = makeEpub(2)
  const layout = { w: 446, h: 595, em: 12 }
  const book = mupdf.Document.openDocument(epub, "application/epub+zip")
  book.layout(layout.w, layout.h, layout.em)
  const n = book.countPages()
  const pages = Array.from({ length: n }, (_, i) => page(`p${i}`, pdfBg("ep", i), i === 1 ? [stroke("s", MID)] : []))
  const out = await project(notebookDoc(pages), entry("Book", { kind: "epub", blob: "ep", layout }), blobsOf({ ep: epub }))
  const pdf = open(out)
  assert.equal(pdf.countPages(), n)
  assert.deepEqual(pdf.loadPage(1).getBounds(), [0, 0, 446, 595])
  assert.match(pdf.loadPage(0).toStructuredText("").asText(), /Chapter 1/)
  const pts = pdf.loadPage(1).getAnnotations()[0].getInkList()[0]
  assert.ok(near(pts[0][0], 930 * 446 / 1860) && near(pts[0][1], 1240 * 446 / 1860), `got ${pts[0]}`)
  assert.equal(pdf.loadPage(0).getAnnotations().length, 0)
})

test("native notebook: A4-wide pages as tall as their content, template drawn as vectors", async () => {
  const doc = notebookDoc([
    page("p0", nativeBg("lined"), [stroke("s", MID)]),
    page("p1", nativeBg("blank"), [stroke("t", [[100, 100], [200, 3000]])]),
  ])
  const pdf = open(await project(doc, entry("Notes", { kind: "none" }), blobsOf({})))
  assert.equal(pdf.countPages(), 2)
  const s = 595 / 1860
  const b0 = pdf.loadPage(0).getBounds()
  assert.ok(near(b0[2], 595) && near(b0[3], 2480 * s, 0.5), `page 0 bounds ${b0}`)
  const b1 = pdf.loadPage(1).getBounds()
  assert.ok(near(b1[3], 3050 * s, 0.5), `page 1 grows with content: ${b1}`)
  assert.equal(pdf.loadPage(1).getAnnotations().length, 1)
})

test("strokes with one point and without pressure still project", async () => {
  const doc = notebookDoc([page("p0", nativeBg("blank"), [stroke("dot", [[500, 500]]), stroke("p", [[10, 10, 0.2], [20, 20, 0.9]], { pen: "FOUNTAIN" })])])
  const pdf = open(await project(doc, entry("Dots", { kind: "none" }), blobsOf({})))
  assert.equal(pdf.loadPage(0).getAnnotations().length, 2)
})

test("a missing blob fails loudly, naming it", async () => {
  const doc = notebookDoc([page("p0", pdfBg("gone", 0))])
  await assert.rejects(project(doc, entry("x", { kind: "pdf", blob: "gone" }), blobsOf({})),
    (e: unknown) => e instanceof ProjectionError && /gone/.test((e as Error).message))
})

test("progress reaches total", async () => {
  const src = makePdf(3)
  const doc = notebookDoc([0, 1, 2].map(i => page(`p${i}`, pdfBg("src", i), [stroke(`s${i}`, MID)])))
  const seen: [number, number][] = []
  await project(doc, entry("x", { kind: "pdf", blob: "src" }), blobsOf({ src }), (done, total) => seen.push([done, total]))
  assert.deepEqual(seen.at(-1), [3, 3])
})

test("file names are safe and end in .pdf", () => {
  assert.equal(pdfFileName("Paper: a/b\\c?"), "Paper a b c.pdf")
  assert.equal(pdfFileName("  "), "Untitled.pdf")
  assert.equal(pdfFileName("x".repeat(300)).length, 124)
  assert.equal(pdfFileName("notes.pdf"), "notes.pdf")
})

test("qpdf and poppler accept the output, see the attachment, and draw ink where Android drew it", { skip: toolsSkip }, async () => {
  const src = makePdf(1)
  const doc = notebookDoc([page("p0", pdfBg("src", 0), [stroke("s1", MID, { size: 12 })])])
  const out = await project(doc, entry("Paper", { kind: "pdf", blob: "src" }), blobsOf({ src }))
  const dir = tmp()
  writeFileSync(join(dir, "o.pdf"), out)
  execFileSync("qpdf", ["--check", join(dir, "o.pdf")])
  assert.match(execFileSync("pdfdetach", ["-list", join(dir, "o.pdf")]).toString(), new RegExp(EMBEDDED_DOC.replace(".", "\\.")))
  const s = 595 / 1860
  // On the stroke: dark. Same x, 20 pt above: white paper.
  assert.ok(popplerPixel(out, 1, 1100 * s, 1240 * s) < 80, "ink is drawn at the mapped position")
  assert.ok(popplerPixel(out, 1, 1100 * s, 1240 * s - 20) > 200, "paper above the stroke is blank")
})

test("qpdf accepts EPUB and native projections too", { skip: toolsSkip }, async () => {
  const layout = { w: 446, h: 595, em: 12 }
  const epub = makeEpub(1)
  const outs = [
    await project(notebookDoc([page("p0", pdfBg("ep", 0), [stroke("s", MID)])]), entry("B", { kind: "epub", blob: "ep", layout }), blobsOf({ ep: epub })),
    await project(notebookDoc([page("p0", nativeBg("hexed"), [stroke("s", MID)]), page("p1", nativeBg("dotted")), page("p2", nativeBg("squared"))]), entry("N", { kind: "none" }), blobsOf({})),
  ]
  for (const o of outs) {
    const f = join(tmp(), "o.pdf")
    writeFileSync(f, o)
    execFileSync("qpdf", ["--check", f])
  }
  // The lined template's first line is at 80 px: gray, not white and not ink-black.
  const lined = await project(notebookDoc([page("p0", nativeBg("lined"))]), entry("L", { kind: "none" }), blobsOf({}))
  // A 1 px line on an 1860 px canvas is a 0.32 pt hairline: look at the rows around it.
  const y = 80 * 595 / 1860
  const g = Math.min(...[-1, 0, 1].map(d => popplerPixel(lined, 1, 300, y + d)))
  assert.ok(g > 60 && g < 230, `line pixel gray ${g}`)
})
