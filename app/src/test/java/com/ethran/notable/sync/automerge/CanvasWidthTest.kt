package com.ethran.notable.sync.automerge

import org.automerge.AmValue
import org.automerge.Document
import org.automerge.NewValue
import org.automerge.ObjectId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `canvasWidth` maps stroke pixels onto PDF points on other devices (web/schema/README.md). */
class CanvasWidthTest {
    private val notebook = AmNotebook(
        notebookId = "nb", openPageId = null,
        defaultBackground = AmBackground("native", "blank", null),
        pageOrder = emptyList(), pages = emptyMap(),
    )

    @Test
    fun written_as_a_float_and_read_back() {
        val doc = AutomergeSchema.newNotebookDocument(notebook.copy(canvasWidth = 1404.0))
        val reloaded = Document.load(doc.save())
        assertTrue(reloaded.get(ObjectId.ROOT, "canvasWidth").get() is AmValue.F64)
        assertEquals(1404.0, AutomergeSchema.readNotebook(reloaded).canvasWidth)
    }

    @Test
    fun absent_reads_as_null() {
        val doc = AutomergeSchema.newNotebookDocument(notebook)
        assertNull(AutomergeSchema.readNotebook(Document.load(doc.save())).canvasWidth)
    }

    @Test
    fun non_positive_is_rejected() {
        val doc = AutomergeSchema.newNotebookDocument(notebook)
        doc.startTransaction().apply { set(ObjectId.ROOT, "canvasWidth", NewValue.f64(0.0)); commit() }
        val e = assertThrows(SchemaException::class.java) { AutomergeSchema.readNotebook(doc) }
        assertTrue(e.message!!.contains("canvasWidth"))
    }
}
