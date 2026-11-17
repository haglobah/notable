package com.ethran.notable.ui

import com.ethran.notable.data.db.Notebook
import com.ethran.notable.ui.viewmodels.RecentNotebooks
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class RecentNotebooksTest {
    private fun nb(id: String, readAt: Long?, pages: List<String> = listOf("$id-p1", "$id-p2"), open: String? = null) =
        Notebook(id = id, title = id, pageIds = pages, openPageId = open, readAt = readAt?.let { Date(it) })

    @Test
    fun mostRecentlyOpenedFirst_neverOpenedLeftOut() {
        val books = listOf(nb("a", 100), nb("b", null), nb("c", 300), nb("d", 200))
        assertEquals(listOf("c", "d", "a"), RecentNotebooks.of(books).map { it.id })
    }

    @Test
    fun capsAtTheLimit() {
        val books = (1..15).map { nb("n$it", it.toLong()) }
        assertEquals(listOf("n15", "n14", "n13"), RecentNotebooks.of(books, limit = 3).map { it.id })
    }

    @Test
    fun leavesOutNotebooksWithoutPages() {
        assertEquals(emptyList<String>(), RecentNotebooks.of(listOf(nb("e", 1, pages = emptyList()))).map { it.id })
    }

    @Test
    fun opensWhereItWasLeft_elseTheFirstPage() {
        assertEquals("a-p2", RecentNotebooks.pageOf(nb("a", 1, open = "a-p2")))
        assertEquals("a-p1", RecentNotebooks.pageOf(nb("a", 1, open = null)))
        assertEquals("a-p1", RecentNotebooks.pageOf(nb("a", 1, open = "gone")))
    }
}
