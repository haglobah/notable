/*
 * Download PDF (ADR-001): the notebook as one ordinary PDF with its ink, made in the reader's worker
 * (project.ts there) and saved by the browser. Kept apart from project.ts, so the page's bundle
 * doesn't take in MuPDF.
 */
import type { NotebookEntry } from "@notable/schema"
import type { Reader } from "./client.ts"

/** Title → a file name every OS accepts. */
export function pdfFileName(title: string): string {
  let base = title.replace(/\.pdf$/i, "").replace(/[\u0000-\u001f\u007f/\\?%*:|"<>]+/g, " ").replace(/\s+/g, " ").trim()
  if (!base) base = "Untitled"
  return `${base.slice(0, 120).trim()}.pdf`
}

/** Makes the PDF of a notebook doc (Automerge save() bytes) and has the browser save it. */
export async function downloadPdf(reader: Reader, doc: Uint8Array, entry: NotebookEntry, progress?: (done: number, total: number) => void): Promise<void> {
  const pdf = await reader.projectPdf(doc, entry, progress)
  const url = URL.createObjectURL(new Blob([pdf as Uint8Array<ArrayBuffer>], { type: "application/pdf" }))
  const a = document.createElement("a")
  a.href = url
  a.download = pdfFileName(entry.title)
  document.body.append(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
