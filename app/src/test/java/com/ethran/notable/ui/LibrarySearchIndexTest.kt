package com.ethran.notable.ui

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.BookMeta
import com.ethran.notable.ui.viewmodels.LibrarySearchIndex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

/** Room rows searched with the shared rules: every folder, kinds from the book file, paths shown. */
class LibrarySearchIndexTest {
    private val t = Date(1_780_000_000_000)
    private val folders = listOf(Folder("f1", "Uni", null, t, t), Folder("f2", "Math", "f1", t, t))
    private val notebooks = listOf(
        Notebook("e", "Earthsea", parentFolderId = "f2", defaultBackgroundType = "autoPdf",
            defaultBackground = "/b/Earthsea@446x595x12.epub", createdAt = t, updatedAt = t,
            bookMeta = BookMeta(authors = listOf("Ursula K. Le Guin")), readAt = Date(t.time + 1)),
        Notebook("p", "Paper", parentFolderId = null, defaultBackgroundType = "autoPdf", defaultBackground = "/b/p.pdf",
            createdAt = t, updatedAt = t, finishedAt = t),
        Notebook("n", "Journal", parentFolderId = "f1", createdAt = t, updatedAt = t),
    )
    private val quick = listOf(Page(id = "q", notebookId = null, parentFolderId = "f1", createdAt = t, updatedAt = t))

    private fun ids(q: String) = LibrarySearchIndex.search(q, folders, notebooks, quick).let { r -> r.notebooks.map { it.id } + r.quickPages.map { it.id } }

    @Test
    fun room_rows_are_searched_with_the_shared_rules() {
        assertEquals(listOf("e"), ids("guin"))
        assertEquals(listOf("e"), ids("kind:epub"))
        assertEquals(listOf("p"), ids("kind:pdf is:finished"))
        assertEquals(listOf("n"), ids("kind:notebook"))
        assertEquals(listOf("q"), ids("kind:quickpage"))
        assertEquals(listOf("e"), ids("folder:math"))
        assertEquals(listOf("e", "n", "q"), ids("folder:uni"))
        assertEquals(emptyList<String>(), ids(""))
    }

    @Test
    fun results_carry_their_folder_path() {
        val r = LibrarySearchIndex.search("folder:uni", folders, notebooks, quick)
        assertEquals("Uni / Math", r.folderPaths["e"])
        assertEquals("Uni", r.folderPaths["q"])
    }
}
