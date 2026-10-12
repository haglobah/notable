package com.ethran.notable.sync.automerge

import com.ethran.notable.data.db.decodeStrokePoints
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.NewValue
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared-schema contract between Android and the PWA (ADR-001). TS writes the `*-v1.automerge`
 * golden docs; this test proves Kotlin reads every field of them. Kotlin writes the
 * `*-from-kotlin.automerge` docs (with `UPDATE_FIXTURES=1`), which the TS tests read back.
 */
class GoldenFixtureTest {
    private fun fixture(name: String) = File(FixturePaths.dir, name)
    private val expectedLibrary = FixtureJson.library(fixture("library-v1.json"))
    private val expectedNotebook = FixtureJson.notebook(fixture("notebook-v1.json"))

    private fun load(name: String) = Document.load(fixture(name).readBytes())
    private fun reload(doc: Document) = Document.load(doc.save())

    @Test
    fun reads_the_ts_written_library() {
        assertEquals(expectedLibrary, AutomergeSchema.readLibrary(load("library-v1.automerge")))
    }

    @Test
    fun reads_the_ts_written_notebook() {
        assertEquals(expectedNotebook, AutomergeSchema.readNotebook(load("notebook-v1.automerge")))
    }

    @Test
    fun golden_strokes_decode_with_the_app_decoder() {
        val strokes = expectedNotebook.pages.values.flatMap { it.strokes.values }
        assertTrue(strokes.isNotEmpty())
        strokes.forEach { assertTrue(decodeStrokePoints(it.points).isNotEmpty()) }
    }

    @Test
    fun kotlin_written_docs_round_trip_and_feed_the_ts_tests() {
        val library = AutomergeSchema.newLibraryDocument(expectedLibrary)
        val notebook = AutomergeSchema.newNotebookDocument(expectedNotebook)
        assertEquals(expectedLibrary, AutomergeSchema.readLibrary(reload(library)))
        assertEquals(expectedNotebook, AutomergeSchema.readNotebook(reload(notebook)))
        if (FixturePaths.update) {
            fixture("library-from-kotlin.automerge").writeBytes(library.save())
            fixture("notebook-from-kotlin.automerge").writeBytes(notebook.save())
        }
        // The committed Kotlin-written fixtures must still read back (catches stale files).
        assertEquals(expectedLibrary, AutomergeSchema.readLibrary(load("library-from-kotlin.automerge")))
        assertEquals(expectedNotebook, AutomergeSchema.readNotebook(load("notebook-from-kotlin.automerge")))
    }

    @Test
    fun a_stroke_added_later_reads_back() {
        val doc = AutomergeSchema.newNotebookDocument(expectedNotebook)
        val pageId = expectedNotebook.pageOrder.first()
        val stroke = expectedNotebook.pages.getValue(pageId).strokes.values.first().copy(id = "added")
        doc.startTransaction().apply {
            AutomergeSchema.putStroke(this, AutomergeSchema.strokesOf(doc, pageId), stroke)
            commit()
        }
        assertEquals(stroke, AutomergeSchema.readNotebook(reload(doc)).pages.getValue(pageId).strokes["added"])
    }

    @Test
    fun rejects_text_where_a_scalar_string_is_required() {
        val doc = AutomergeSchema.newLibraryDocument(expectedLibrary)
        val folderId = expectedLibrary.folders.keys.first()
        val folders = (doc.get(ObjectId.ROOT, "folders").get() as AmValue.Map).id
        val folder = (doc.get(folders, folderId).get() as AmValue.Map).id
        doc.startTransaction().apply {
            val text = set(folder, "title", ObjectType.TEXT)
            spliceText(text, 0, 0, "collaborative text")
            commit()
        }
        val e = assertThrows(SchemaException::class.java) { AutomergeSchema.readLibrary(doc) }
        assertTrue(e.message!!, e.message!!.contains("folders.$folderId.title"))
    }

    // automerge-java 0.0.9: NewValue.integer() calls tx.set(obj, key, long), which has no overload
    // and widens to double, so the value lands as F64. The writer must use the int overloads.
    @Test
    fun integers_are_stored_as_automerge_ints() {
        val doc = AutomergeSchema.newNotebookDocument(expectedNotebook)
        assertTrue(doc.get(ObjectId.ROOT, "version").get() is AmValue.Int)
        val pageId = expectedNotebook.pageOrder.first()
        val strokes = AutomergeSchema.strokesOf(doc, pageId)
        val stroke = (doc.get(strokes, expectedNotebook.pages.getValue(pageId).strokes.keys.first()).get() as AmValue.Map).id
        assertTrue(doc.get(stroke, "color").get() is AmValue.Int)
    }

    @Test
    fun rejects_the_wrong_schema() {
        val doc = AutomergeSchema.newNotebookDocument(expectedNotebook)
        assertThrows(SchemaException::class.java) { AutomergeSchema.readLibrary(doc) }
    }

    @Test
    fun rejects_a_newer_version_explicitly() {
        val doc = AutomergeSchema.newLibraryDocument(expectedLibrary)
        doc.startTransaction().apply { set(ObjectId.ROOT, "version", AutomergeSchema.SUPPORTED_VERSION.toInt() + 1); commit() }
        val e = assertThrows(SchemaException::class.java) { AutomergeSchema.readLibrary(doc) }
        assertTrue(e.message!!, e.message!!.contains("newer"))
    }

    // Concurrent "delete page" + "move page" are legal merges that leave pageOrder inconsistent.
    @Test
    fun normalizes_page_order_after_concurrent_edits() {
        val doc = AutomergeSchema.newNotebookDocument(expectedNotebook)
        val order = (doc.get(ObjectId.ROOT, "pageOrder").get() as AmValue.List).id
        val first = expectedNotebook.pageOrder.first()
        doc.startTransaction().apply {
            delete(order, 0)                                       // first page now missing from order
            insert(order, 0, NewValue.str("ghost"))                // dangling id
            insert(order, 1, NewValue.str(expectedNotebook.pageOrder[1])) // duplicate
            commit()
        }
        assertEquals(expectedNotebook.pageOrder.drop(1) + first, AutomergeSchema.readNotebook(doc).pageOrder)
    }

    /* Delete against draw (ADR-001): drawing on a page deleted elsewhere brings it back. */

    private val deletedPage = "p-2"
    private val drawn get() = expectedNotebook.pages.getValue(deletedPage).strokes.values.first().copy(id = "drawn-after-delete")

    private fun deleteIn(doc: Document) = doc.startTransaction().run { AutomergeSchema.deletePage(doc, this, deletedPage); commit() }
    private fun drawIn(doc: Document) = doc.startTransaction().run {
        AutomergeSchema.putStroke(this, AutomergeSchema.strokesOf(doc, deletedPage), drawn)
        AutomergeSchema.markPageLive(doc, this, deletedPage)
        commit()
    }

    private fun assertBackWithStroke(nb: AmNotebook) {
        assertEquals(expectedNotebook.pages.getValue(deletedPage).strokes.keys + drawn.id, nb.pages.getValue(deletedPage).strokes.keys)
        assertEquals(listOf("p-1", "p-3", deletedPage), nb.pageOrder)
    }

    private fun assertGone(nb: AmNotebook) {
        assertFalse(deletedPage in nb.pages)
        assertEquals(listOf("p-1", "p-3"), nb.pageOrder)
    }

    @Test
    fun a_page_deleted_in_one_doc_and_drawn_on_in_another_comes_back_and_feeds_the_ts_tests() {
        val origin = AutomergeSchema.newNotebookDocument(expectedNotebook)
        val deleter = origin.fork().also(::deleteIn)
        val drawer = origin.fork().also(::drawIn)
        val merged = deleter.fork().apply { merge(drawer) }
        assertBackWithStroke(AutomergeSchema.readNotebook(reload(merged)))
        assertBackWithStroke(AutomergeSchema.readNotebook(drawer.fork().apply { merge(deleter) }))
        assertGone(AutomergeSchema.readNotebook(reload(deleter)))
        assertEquals(2L, (deleter.get(ObjectId.ROOT, "version").get() as AmValue.Int).value)
        if (FixturePaths.update) {
            fixture("page-delete-vs-draw-from-kotlin.automerge").writeBytes(merged.save())
            fixture("page-deleted-from-kotlin.automerge").writeBytes(deleter.save())
        }
        assertBackWithStroke(AutomergeSchema.readNotebook(load("page-delete-vs-draw-from-kotlin.automerge")))
        assertGone(AutomergeSchema.readNotebook(load("page-deleted-from-kotlin.automerge")))
    }

    @Test
    fun ts_written_docs_delete_against_draw_keeps_the_page_and_a_plain_delete_removes_it() {
        assertBackWithStroke(AutomergeSchema.readNotebook(load("page-delete-vs-draw.automerge")))
        assertGone(AutomergeSchema.readNotebook(load("page-deleted.automerge")))
    }

    @Test
    fun a_delete_made_after_seeing_a_stroke_wins_and_a_stroke_after_the_delete_revives() {
        val doc = AutomergeSchema.newNotebookDocument(expectedNotebook)
        drawIn(doc)
        deleteIn(doc)
        assertGone(AutomergeSchema.readNotebook(doc))
        drawIn(doc)
        assertBackWithStroke(AutomergeSchema.readNotebook(doc))
        assertEquals(3, AutomergeSchema.rawPageOrder(doc).second.size)
    }

    @Test
    fun a_second_stroke_after_a_synced_first_one_still_beats_a_concurrent_delete() {
        val synced = AutomergeSchema.newNotebookDocument(expectedNotebook).also(::drawIn) // deleted: false, seen by both
        val deleter = synced.fork().also(::deleteIn)
        val drawer = synced.fork()
        drawer.startTransaction().run {
            AutomergeSchema.putStroke(this, AutomergeSchema.strokesOf(drawer, deletedPage), drawn.copy(id = "again"))
            AutomergeSchema.markPageLive(drawer, this, deletedPage)
            commit()
        }
        val nb = AutomergeSchema.readNotebook(deleter.fork().apply { merge(drawer) })
        assertTrue("again" in nb.pages.getValue(deletedPage).strokes)
    }

    @Test
    fun deleted_must_be_a_boolean() {
        val doc = AutomergeSchema.newNotebookDocument(expectedNotebook)
        val page = AutomergeSchema.pageObjOrNull(doc, deletedPage)!!
        doc.startTransaction().apply { set(page, "deleted", NewValue.str("yes")); commit() }
        val e = assertThrows(SchemaException::class.java) { AutomergeSchema.readNotebook(doc) }
        assertTrue(e.message!!, e.message!!.contains("pages.$deletedPage.deleted"))
    }
}
