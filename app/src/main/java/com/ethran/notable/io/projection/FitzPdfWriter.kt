package com.ethran.notable.io.projection

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.FileStream
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Date

/**
 * Carries out a [ProjectionPlan] with MuPDF (`fitz`), the way web/app/src/lib/project.ts does
 * with mupdf.js: PDF pages grafted, EPUBs laid out and written as PDF first, Notable's own pages
 * drawn from the plan's content stream, strokes as `/Ink` with appearance streams, images as
 * stamps, and the notebook doc plus `notable.json` embedded.
 *
 * Also the [ProjectionSources] the planner asks for page and image sizes. [blobs] returns a local
 * file holding a blob's bytes, or throws. One writer per projection; [close] frees MuPDF memory.
 */
class FitzPdfWriter(private val blobs: (String) -> File) : ProjectionSources, AutoCloseable {
    private val sources = HashMap<String, PDFDocument>()
    private val images = HashMap<String, Image>()
    private val streams = ArrayList<FileStream>()

    override fun documentPages(ref: DocumentRef): List<PdfRect> {
        val doc = source(ref)
        return (0 until doc.countPages()).map { i ->
            val page = doc.loadPage(i)
            try {
                page.bounds.let { PdfRect(it.x0.toDouble(), it.y0.toDouble(), it.x1.toDouble(), it.y1.toDouble()) }
            } finally {
                page.destroy()
            }
        }
    }

    override fun imageSize(blob: String): Pair<Int, Int> = image(blob).let { it.width to it.height }

    /** Writes [plan] to [out]; [doc] is the notebook doc's `save()` bytes. */
    fun write(plan: ProjectionPlan, doc: ByteArray, out: File, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val pdf = PDFDocument()
        try {
            for ((i, p) in plan.pages.withIndex()) {
                when (val c = p.content) {
                    is PageContent.Graft -> pdf.graftPage(-1, source(c.ref), c.index)
                    is PageContent.Drawn -> {
                        val xobjects = pdf.newDictionary()
                        c.backgroundImage?.let { xobjects.put("Bg", pdf.addImage(image(it))) }
                        val resources = pdf.newDictionary().apply { put("XObject", xobjects) }
                        val b = p.bounds
                        pdf.insertPage(-1, pdf.addPage(rect(b), 0, pdf.addObject(resources), c.ops))
                    }
                }
                val page = pdf.loadPage(i) as PDFPage
                try {
                    for (s in p.stamps) {
                        val a = page.createAnnotation(PDFAnnotation.TYPE_STAMP)
                        a.rect = rect(s.rect)
                        a.setStampImage(image(s.blob))
                        a.update()
                    }
                    for (ink in p.inks) {
                        val a = page.createAnnotation(PDFAnnotation.TYPE_INK)
                        a.setInkList(arrayOf(ink.points.map { Point(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()))
                        a.color = floatArrayOf(ink.rgb[0].toFloat(), ink.rgb[1].toFloat(), ink.rgb[2].toFloat())
                        ink.opacity?.let { a.opacity = it.toFloat() }
                        a.borderWidth = ink.borderWidth.toFloat()
                        a.update()
                    }
                } finally {
                    page.destroy()
                }
                onProgress(i + 1, plan.pages.size)
            }

            val now = Date()
            val files = sortedMapOf(
                ProjectionPlanner.EMBEDDED_DOC to pdf.addEmbeddedFile(
                    ProjectionPlanner.EMBEDDED_DOC, "application/octet-stream", ByteArrayInputStream(doc), now, now, true),
                ProjectionPlanner.EMBEDDED_META to pdf.addEmbeddedFile(
                    ProjectionPlanner.EMBEDDED_META, "application/json", ByteArrayInputStream(plan.metaJson.toByteArray()), now, now, true),
            )
            setEmbeddedFiles(pdf, files)
            pdf.setMetaData(PDFDocument.META_INFO_TITLE, plan.title)
            pdf.setMetaData(PDFDocument.META_INFO_PRODUCER, "Notable")
            out.absoluteFile.parentFile?.mkdirs()
            pdf.save(out.absolutePath, "compress,garbage")
        } finally {
            pdf.destroy()
        }
    }

    /** The `/EmbeddedFiles` name tree, written as mupdf.js `insertEmbeddedFile` does: one sorted `/Names` array. */
    private fun setEmbeddedFiles(pdf: PDFDocument, files: Map<String, PDFObject>) {
        val root = pdf.trailer.get("Root")
        var names = root.get("Names")
        if (!names.isDictionary) {
            root.put("Names", pdf.newDictionary())
            names = root.get("Names")
        }
        val array = pdf.newArray()
        for ((name, spec) in files) {
            array.push(pdf.newString(name))
            array.push(spec)
        }
        val tree = pdf.newDictionary().apply { put("Names", array) }
        names.put("EmbeddedFiles", tree)
    }

    private fun source(ref: DocumentRef): PDFDocument = sources.getOrPut(ref.key) {
        val file = blobs(ref.blob)
        try {
            when (ref.format) {
                DocFormat.PDF -> open(file, "application/pdf").asPDF()
                    ?: throw ProjectionException("PDF ${ref.blob} is not a PDF")
                DocFormat.EPUB -> epubAsPdf(file, ref)
            }
        } catch (e: ProjectionException) {
            throw e
        } catch (e: Exception) {
            throw ProjectionException("${ref.format.key.uppercase()} ${ref.blob} can't be opened: ${e.message}", e)
        }
    }

    private fun open(file: File, mime: String): Document =
        Document.openDocument(FileStream(file, "r").also { streams += it }, mime)

    /** Lays the EPUB out with its stored parameters and writes those pages as a PDF, as project.ts does. */
    private fun epubAsPdf(file: File, ref: DocumentRef): PDFDocument {
        val layout = ref.layout ?: throw ProjectionException("EPUB ${ref.blob} has no layout")
        return Document.openDocument(FitzEpub.layOutAsPdf(file, layout), "application/pdf").asPDF()
            ?: throw ProjectionException("EPUB ${ref.blob}: MuPDF wrote no PDF")
    }

    private fun image(blob: String): Image = images.getOrPut(blob) {
        try {
            Image(blobs(blob).absolutePath)
        } catch (e: ProjectionException) {
            throw e
        } catch (e: Exception) {
            throw ProjectionException("Image $blob can't be opened: ${e.message}", e)
        }
    }

    private fun rect(r: PdfRect) = Rect(r.x0.toFloat(), r.y0.toFloat(), r.x1.toFloat(), r.y1.toFloat())

    override fun close() {
        sources.values.forEach { runCatching { it.destroy() } }
        images.values.forEach { runCatching { it.destroy() } }
        streams.forEach { runCatching { it.close() } }
        sources.clear(); images.clear(); streams.clear()
    }
}
