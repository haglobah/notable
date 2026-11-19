package com.ethran.notable.editor

import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.BackgroundType

/**
 * Which notebook page shows which page of the book the notebook was made from
 * (docs/plans/ebook-features.md, "Source page → notebook page").
 */
object BookPages {
    private fun isDocument(type: String) = when (BackgroundType.fromKey(type)) {
        BackgroundType.AutoPdf, is BackgroundType.Pdf -> true
        else -> false
    }

    /**
     * The PDF or EPUB the notebook was made from: its default background when that is a document,
     * else the first document page's (as the doc's `source` is chosen); null for other notebooks.
     */
    fun sourceOf(nb: Notebook, pages: List<Page>): String? =
        if (isDocument(nb.defaultBackgroundType)) nb.defaultBackground
        else pages.firstOrNull { isDocument(it.backgroundType) }?.background

    /**
     * Document page → the first page in [order] whose background is that page of [source]. A
     * `pdfN` page shows page N; an `autoPdf` page shows the page at its own index.
     */
    fun notebookPages(order: List<String>, pages: Map<String, Page>, source: String): Map<Int, String> {
        val out = HashMap<Int, String>()
        order.forEachIndexed { i, id ->
            val p = pages[id] ?: return@forEachIndexed
            if (p.background != source) return@forEachIndexed
            val docPage = when (val t = BackgroundType.fromKey(p.backgroundType)) {
                is BackgroundType.Pdf -> t.page
                BackgroundType.AutoPdf -> i
                else -> return@forEachIndexed
            }
            out.putIfAbsent(docPage, id)
        }
        return out
    }
}
