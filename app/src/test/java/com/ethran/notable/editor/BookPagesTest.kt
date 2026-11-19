package com.ethran.notable.editor

import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookPagesTest {
    private fun page(id: String, type: String, bg: String) = Page(id = id, notebookId = "nb", backgroundType = type, background = bg)

    @Test
    fun the_first_page_showing_a_document_page_is_its_notebook_page() {
        val pages = listOf(
            page("a", "pdf0", "/b.epub"), page("n", "native", "blank"), page("b", "pdf2", "/b.epub"),
            page("c", "pdf2", "/b.epub"), page("x", "pdf1", "/other.pdf"), page("d", "pdf1", "/b.epub"),
        )
        val map = BookPages.notebookPages(pages.map { it.id }, pages.associateBy { it.id }, "/b.epub")
        assertEquals(mapOf(0 to "a", 2 to "b", 1 to "d"), map)
    }

    @Test
    fun an_auto_pdf_page_shows_the_page_at_its_index() {
        val pages = listOf(page("a", "autoPdf", "/p.pdf"), page("n", "native", "blank"), page("b", "autoPdf", "/p.pdf"))
        assertEquals(mapOf(0 to "a", 2 to "b"), BookPages.notebookPages(listOf("a", "n", "b"), pages.associateBy { it.id }, "/p.pdf"))
    }

    @Test
    fun the_source_is_the_default_document_background_or_the_first_document_page() {
        val pages = listOf(page("n", "native", "blank"), page("a", "pdf3", "/p.pdf"))
        assertEquals("/d.pdf", BookPages.sourceOf(Notebook(defaultBackgroundType = "autoPdf", defaultBackground = "/d.pdf"), pages))
        assertEquals("/p.pdf", BookPages.sourceOf(Notebook(), pages))
        assertNull(BookPages.sourceOf(Notebook(), pages.take(1)))
    }
}
