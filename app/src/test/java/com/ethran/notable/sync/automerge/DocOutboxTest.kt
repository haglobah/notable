package com.ethran.notable.sync.automerge

import com.ethran.notable.data.LocalChange
import com.ethran.notable.data.PendingLocalWrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocOutboxTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `debts for the same rows merge, and a retry keeps ids recorded since`() {
        val file = File(tmp.newFolder(), "pending.json")
        val box = DocOutbox(file, PendingLocalWrites())
        box.record(LocalChange.StrokesDeleted("p1", listOf("a")))
        val snapshot = box.debts().single()
        box.record(LocalChange.StrokesUpserted("p1", listOf(stroke("b", "p1"))))
        box.record(LocalChange.PageTouched("p1", 1L))
        box.record(LocalChange.PageDeleted(com.ethran.notable.data.db.Page(id = "p1", notebookId = "nb1")))
        assertEquals(2, box.size)

        box.done(snapshot)
        assertEquals(
            listOf(DocDebt.Strokes("p1", setOf("b")), DocDebt.PageRow("p1", "nb1")),
            DocOutbox(file, PendingLocalWrites()).debts(),
        )
    }

    @Test
    fun `releasing an outbox frees its ids for the projector`() {
        val pending = PendingLocalWrites()
        val box = DocOutbox(File(tmp.newFolder(), "pending.json"), pending)
        box.record(LocalChange.ImagesDeleted("p1", listOf("i1")))
        assertTrue(pending.contains("i1"))

        box.release() // the session ended, e.g. at logout
        assertFalse(pending.contains("i1"))
    }
}
