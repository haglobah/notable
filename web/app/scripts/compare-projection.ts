/*
 * Checks a PDF that Notable on Android made against the PWA's projection of the same notebook.
 *
 *   node scripts/compare-projection.ts <android.pdf> [blob files...]
 *
 * Reads the notebook doc and library entry embedded in the Android PDF, projects them here with
 * project() (the PWA's Download), and compares both PDFs: pages, bounds, drawn content,
 * annotations (count, order, ink points, colors, opacity, widths, rects), embedded files, the
 * embedded doc's heads, metadata. Blob files are the PDFs, EPUBs and images the notebook uses;
 * each is found by its sha256. Exits 1 on any difference.
 */
import { readFileSync } from "node:fs"
import { createHash } from "node:crypto"
import * as A from "@automerge/automerge"
import * as mupdf from "mupdf"
import { project, EMBEDDED_DOC, EMBEDDED_META } from "../src/lib/project.ts"
import { summarize, type Summary } from "../test/projectionParity.ts"

const [pdfPath, ...blobPaths] = process.argv.slice(2)
if (!pdfPath) {
  console.error("usage: compare-projection.ts <android.pdf> [blob files...]")
  process.exit(2)
}
const android = new Uint8Array(readFileSync(pdfPath))
const blobs: Record<string, Uint8Array> = {}
for (const p of blobPaths) {
  const b = new Uint8Array(readFileSync(p))
  blobs[createHash("sha256").update(b).digest("hex")] = b
}

const pdf = new mupdf.PDFDocument(android)
const files = pdf.getEmbeddedFiles()
if (!files[EMBEDDED_DOC] || !files[EMBEDDED_META]) throw new Error(`no ${EMBEDDED_DOC} / ${EMBEDDED_META} in ${pdfPath}`)
const doc = A.load(pdf.getEmbeddedFileContents(files[EMBEDDED_DOC])!.asUint8Array())
const meta = JSON.parse(new TextDecoder().decode(pdf.getEmbeddedFileContents(files[EMBEDDED_META])!.asUint8Array()))
pdf.destroy()
const e = meta.entry
const entry = { ...e, folderId: null, createdAt: new Date(e.createdAt), updatedAt: new Date(e.updatedAt) }

const node = await project(doc, entry, async h => {
  const b = blobs[h]
  if (!b) throw new Error(`blob ${h} not given on the command line`)
  return b
})
const want = summarize(node)
const got = summarize(android)

const problems: string[] = []
const near = (a: number, b: number, eps: number) => Math.abs(a - b) <= eps
const nearAll = (what: string, a: number[], b: number[], eps: number) => {
  if (a.length !== b.length || a.some((x, i) => !near(x, b[i], eps))) problems.push(`${what}: PWA ${JSON.stringify(a)}, Android ${JSON.stringify(b)}`)
}

function compare(w: Summary, g: Summary) {
  if (w.pages.length !== g.pages.length) problems.push(`pages: PWA ${w.pages.length}, Android ${g.pages.length}`)
  w.pages.forEach((wp, i) => {
    const gp = g.pages[i]
    if (!gp) return
    const at = `page ${i + 1}`
    nearAll(`${at} bounds`, wp.bounds, gp.bounds, 0.01)
    if (wp.contents !== gp.contents) problems.push(`${at}: content streams differ (${wp.contents.length} vs ${gp.contents.length} bytes)`)
    if (wp.annotations.length !== gp.annotations.length) problems.push(`${at} annotations: PWA ${wp.annotations.length}, Android ${gp.annotations.length}`)
    wp.annotations.forEach((wa, k) => {
      const ga = gp.annotations[k]
      if (!ga) return
      const ak = `${at} annotation ${k + 1}`
      if (wa.type !== ga.type) return problems.push(`${ak}: ${wa.type} vs ${ga.type}`)
      nearAll(`${ak} rect`, wa.rect, ga.rect, 0.5)
      if (wa.type === "Ink" && ga.type === "Ink") {
        nearAll(`${ak} ink`, wa.inkList.flat(2), ga.inkList.flat(2), 0.01)
        nearAll(`${ak} color`, wa.color, ga.color, 1e-4)
        nearAll(`${ak} opacity`, [wa.opacity], [ga.opacity], 1e-4)
        nearAll(`${ak} width`, [wa.borderWidth], [ga.borderWidth], 1e-3)
      }
    })
  })
  if (JSON.stringify(w.embedded) !== JSON.stringify(g.embedded)) problems.push(`embedded files: ${w.embedded} vs ${g.embedded}`)
  if (JSON.stringify([...w.docHeads].sort()) !== JSON.stringify([...g.docHeads].sort())) problems.push("embedded doc heads differ")
  if (JSON.stringify(w.meta) !== JSON.stringify(g.meta)) problems.push(`notable.json differs:\n  PWA     ${JSON.stringify(w.meta)}\n  Android ${JSON.stringify(g.meta)}`)
  if (w.title !== g.title) problems.push(`title: ${w.title} vs ${g.title}`)
}

compare(want, got)
const inks = got.pages.reduce((n, p) => n + p.annotations.filter(a => a.type === "Ink").length, 0)
console.log(`${pdfPath}: ${got.pages.length} pages, ${inks} ink annotations, entry "${e.title}" (${e.source.kind})`)
if (problems.length) {
  console.log(`DIFFERENT from the PWA projection (${problems.length}):`)
  for (const p of problems) console.log(`  - ${p}`)
  process.exit(1)
}
console.log("same as the PWA projection")
