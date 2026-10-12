package com.ethran.notable.io.projection

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.FileStream
import com.artifex.mupdf.fitz.Matrix
import com.ethran.notable.sync.automerge.AmEpubLayout
import java.io.File

/**
 * An EPUB's pages exist only once laid out. Both the Boox background and the PDF projection lay it
 * out here, as the PWA does (web/app/src/lib/project.ts): `layout(w, h, em)`, then every page
 * written through MuPDF's PDF writer. The same layout gives the same pages, so annotation
 * coordinates are stable across devices.
 */
object FitzEpub {
    /** The EPUB at [file] laid out with [layout], as PDF bytes. @throws Exception from MuPDF */
    fun layOutAsPdf(file: File, layout: AmEpubLayout): ByteArray {
        val stream = FileStream(file, "r")
        try {
            val book = Document.openDocument(stream, "application/epub+zip")
            try {
                book.layout(layout.w.toFloat(), layout.h.toFloat(), layout.em.toFloat())
                val pages = book.countPages()
                if (pages < 1) throw IllegalArgumentException("the EPUB has no pages")
                val buf = Buffer()
                val writer = DocumentWriter(buf, "pdf", "")
                try {
                    for (i in 0 until pages) {
                        val p = book.loadPage(i)
                        try {
                            val dev = writer.beginPage(p.bounds)
                            p.run(dev, Matrix())
                            writer.endPage()
                        } finally {
                            p.destroy()
                        }
                    }
                    writer.close()
                } finally {
                    writer.destroy()
                }
                return buf.asByteArray().also { buf.destroy() }
            } finally {
                book.destroy()
            }
        } finally {
            stream.close()
        }
    }
}
