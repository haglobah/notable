package com.ethran.notable.io

import androidx.annotation.WorkerThread
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.FileStream
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.StructuredText
import com.ethran.notable.io.projection.EpubFiles
import java.io.File

/** One table-of-contents line; [page] is a document page, [depth] 0 is the top level. */
data class OutlineEntry(val title: String, val page: Int, val depth: Int)

/**
 * A page with hits. [quads] are in document points, 8 numbers each (ul, ur, ll, lr as x, y), as in
 * the TS `SearchHit`.
 */
data class SearchHit(
    val page: Int, val quads: List<FloatArray>, val snippet: String,
    /** The page's size and top left corner in points, to place [quads] on its rendering; size 0 when unknown. */
    val width: Float = 0f, val height: Float = 0f, val x0: Float = 0f, val y0: Float = 0f,
)

/**
 * The table of contents and in-book search (docs/plans/ebook-features.md, "Table of contents and
 * in-book search"). The TS twin is web/reader/src/bookText.ts. Pages are document pages.
 */
object BookText {
    private const val SNIPPET_SIDE = 40
    private val SPACES = Regex("[${BookMetaReader.SPACE}]+")

    /** Up to 40 characters each side of the first case-insensitive occurrence, with … where cut. */
    fun snippet(text: String, query: String): String {
        val t = SPACES.replace(text, " ").trim()
        val at = t.lowercase().indexOf(query.lowercase())
        if (at < 0) return t.take(SNIPPET_SIDE * 2) + if (t.length > SNIPPET_SIDE * 2) "…" else ""
        val from = maxOf(0, at - SNIPPET_SIDE)
        val to = minOf(t.length, at + query.length + SNIPPET_SIDE)
        return (if (from > 0) "…" else "") + t.substring(from, to) + if (to < t.length) "…" else ""
    }

    /** Depth-first; items without a page are dropped and their children moved up a level. */
    fun outlineOf(doc: Document): List<OutlineEntry> {
        val out = mutableListOf<OutlineEntry>()
        fun walk(items: Array<Outline>, depth: Int) {
            for (it in items) {
                // Java outline items carry only a URI (also for PDFs); MuPDF resolves it to a page.
                val page = runCatching { doc.pageNumberFromLocation(doc.resolveLink(it)) }.getOrDefault(-1)
                val title = SPACES.replace(it.title ?: "", " ").trim()
                val kept = page >= 0 && title.isNotEmpty()
                if (kept) out += OutlineEntry(title, page, depth)
                it.down?.let { down -> walk(down, if (kept) depth + 1 else depth) }
            }
        }
        walk(doc.loadOutline() ?: emptyArray(), 0)
        return out
    }

    /**
     * Every page with a hit, in page order. [onPage] reports progress (pages done, total);
     * [cancelled] is checked between pages.
     */
    fun searchDoc(
        doc: Document, query: String,
        onPage: (Int, Int) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false },
    ): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        val total = doc.countPages()
        for (i in 0 until total) {
            if (cancelled()) break
            val page = doc.loadPage(i)
            try {
                // Page.search, not StructuredText.search: the latter's JNI in fitz 1.28.0 passes an
                // invalid reference (CheckJNI aborts the app). Page.search takes the same flags.
                val found = page.search(q, StructuredText.SEARCH_IGNORE_CASE or StructuredText.SEARCH_IGNORE_DIACRITICS)
                if (!found.isNullOrEmpty()) {
                    val st = page.toStructuredText("preserve-whitespace")
                    val text = try { st.asText() } finally { st.destroy() }
                    val bounds = page.bounds
                    hits += SearchHit(
                        width = bounds.x1 - bounds.x0, height = bounds.y1 - bounds.y0, x0 = bounds.x0, y0 = bounds.y0,
                        page = i,
                        quads = found.flatMap { it.asList() }.map {
                            floatArrayOf(it.ul_x, it.ul_y, it.ur_x, it.ur_y, it.ll_x, it.ll_y, it.lr_x, it.lr_y)
                        },
                        snippet = snippet(text, q),
                    )
                }
            } finally {
                page.destroy()
            }
            onPage(i + 1, total)
        }
        return hits
    }

    /**
     * [block] on the book at [path] as its pages are numbered in the notebook: a PDF as it is, an
     * EPUB laid out with the layout its name carries ([EpubFiles]), like its page backgrounds.
     * The document is destroyed afterwards. @throws Exception from MuPDF
     */
    @WorkerThread
    fun <T> withBook(path: String, block: (Document) -> T): T {
        if (!EpubFiles.isEpub(path)) {
            val doc = Document.openDocument(path)
            try {
                return block(doc)
            } finally {
                doc.destroy()
            }
        }
        val stream = FileStream(File(path), "r")
        try {
            val doc = Document.openDocument(stream, "application/epub+zip")
            try {
                val layout = EpubFiles.layoutOf(path) ?: EpubFiles.BOOX_LAYOUT
                doc.layout(layout.w.toFloat(), layout.h.toFloat(), layout.em.toFloat())
                return block(doc)
            } finally {
                doc.destroy()
            }
        } finally {
            stream.close()
        }
    }

    /** The title and info-dictionary metadata of the PDF at [path]. @throws Exception from MuPDF */
    @WorkerThread
    fun pdfMeta(path: String): BookMetaReader.Extracted =
        withBook(path) { doc -> BookMetaReader.pdfMeta { key -> doc.getMetaData(key) } }
}
