package com.ethran.notable.details

import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.data.db.*
import com.ethran.notable.testing.TestDatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

/** Real Room updates: a details sheet must not replace pages changed since it opened. */
class NotebookMetadataTests {
    private val db = TestDatabaseFactory.createInMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    private val repository = BookRepository(db.notebookDao(), db.pageDao())
    @After fun close() = db.close()

    @Test fun metadataEditsPreserveLatestPageStateAndStampModification() = runBlocking {
        val original = Notebook(id = "book", title = "Original", pageIds = listOf("old-page"),
            linkedExternalUri = "file:///linked", defaultBackground = "grid", updatedAt = Date(1))
        db.notebookDao().create(original)
        db.folderDao().create(Folder(id = "folder"))
        // A page operation happened after the dialog read its initial notebook.
        db.notebookDao().setPageIds("book", listOf("old-page", "new-page"), Date(2))
        db.notebookDao().setOpenPageId("book", "new-page")
        repository.rename("book", "Renamed")
        val renamed = repository.getById("book")!!
        assertEquals("Renamed", renamed.title)
        assertEquals(listOf("old-page", "new-page"), renamed.pageIds)
        assertEquals("new-page", renamed.openPageId)
        assertEquals("file:///linked", renamed.linkedExternalUri)
        assertTrue(renamed.updatedAt.time > 2)
        repository.setBackground("book", "image", null)
        repository.move("book", "folder")
        repository.setLinkedUri("book", null)
        val changed = repository.getById("book")!!
        assertEquals("Renamed", changed.title)
        assertEquals("grid", changed.defaultBackground) // null selection preserves existing template
        assertEquals("image", changed.defaultBackgroundType)
        assertEquals("folder", changed.parentFolderId)
        assertNull(changed.linkedExternalUri)
        assertEquals(renamed.pageIds, changed.pageIds)
        assertEquals(renamed.openPageId, changed.openPageId)
        assertEquals(original.createdAt, changed.createdAt)
    }

    @Test fun missingNotebookFailsVisiblyInsteadOfReportingSaveSuccess() = runBlocking {
        try {
            repository.rename("missing", "Name")
            fail("A missing notebook cannot be saved")
        } catch (expected: IllegalStateException) {
            assertEquals("Notebook no longer exists", expected.message)
        }
    }
}
