package com.ethran.notable.sync.automerge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.automerge.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Date

/**
 * Read next and the trash (docs/plans/library-features.md): `queuedAt`, `trashedAt`. Mirrors
 * web/schema/test/trash.test.ts; both hold to `trash-v1.json`. TS writes
 * `library-trash-v1.automerge`; this test writes `library-trash-from-kotlin.automerge` (with
 * `UPDATE_FIXTURES=1`) for the TS test.
 */
class TrashSchemaTest {
    private fun fixture(name: String) = File(FixturePaths.dir, name)
    private val lib = FixtureJson.library(fixture("library-trash-v1.json"))
    private val expect = Json.parseToJsonElement(fixture("trash-v1.json").readText()).jsonObject
    private fun ids(key: String) = expect.getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun pairs(key: String) = expect.getValue(key).jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.content } }
    private fun pairsOf(items: List<LibrarySearch.TrashItem>) = items.map {
        listOf(if (it is LibrarySearch.TrashItem.Folder) "folder" else "notebook", it.id)
    }
    private fun doc() = AutomergeSchema.newLibraryDocument(lib)

    @Test
    fun fields_survive_save_and_load_null_apart_from_absent() {
        val back = AutomergeSchema.readLibrary(Document.load(doc().save()))
        assertEquals(lib, back)
        val off = back.notebooks.getValue("n-off")
        assertNull(off.queuedAt); assertTrue(off.hasQueuedAt)
        assertFalse(back.notebooks.getValue("n-old").hasQueuedAt)
        assertTrue(back.folders.getValue("f-keep").hasTrashedAt)
        assertFalse(back.folders.getValue("f-sub").hasTrashedAt)
    }

    @Test
    fun reads_the_ts_written_library_exactly() {
        assertEquals(lib, AutomergeSchema.readLibrary(Document.load(fixture("library-trash-v1.automerge").readBytes())))
    }

    @Test
    fun kotlin_written_library_feeds_the_ts_test() {
        val name = "library-trash-from-kotlin.automerge"
        if (FixturePaths.update) fixture(name).writeBytes(doc().save())
        assertEquals(lib, AutomergeSchema.readLibrary(Document.load(fixture(name).readBytes())))
    }

    @Test
    fun read_next_list() {
        assertEquals(ids("readNext"), LibrarySearch.readNext(lib).map { it.id })
    }

    @Test
    fun trash_expired_trash_and_what_is_in_the_trash() {
        assertEquals(pairs("trash"), pairsOf(LibrarySearch.trashOf(lib)))
        val now = Date.from(Instant.parse(expect.getValue("now").jsonPrimitive.content))
        assertEquals(pairs("expired"), pairsOf(LibrarySearch.expiredTrash(lib, now)))
        assertEquals(ids("trashed").sorted(), lib.notebooks.values.filter { LibrarySearch.isTrashed(lib, it) }.map { it.id }.sorted())
    }

    @Test
    fun search_leaves_out_the_trash() {
        for (c in expect.getValue("cases").jsonArray) {
            val o = c.jsonObject
            val q = o.getValue("query").jsonPrimitive.content
            assertEquals(q, o.getValue("ids").jsonArray.map { it.jsonPrimitive.content }, LibrarySearch.search(lib, q).map { it.id })
        }
    }

    @Test
    fun setters_write_one_field_each() {
        val d = doc()
        val at = Date.from(Instant.parse("2026-11-19T12:00:00.000Z"))
        d.startTransaction().apply {
            AutomergeSchema.setQueuedAt(this, AutomergeSchema.entryObjOrNull(d, "n-off")!!, at)
            AutomergeSchema.setTrashedAt(this, AutomergeSchema.entryObjOrNull(d, "n-dune")!!, at)
            commit()
        }
        val l = AutomergeSchema.readLibrary(d)
        assertEquals(at, l.notebooks.getValue("n-off").queuedAt)
        assertEquals(at, l.notebooks.getValue("n-dune").trashedAt)
        d.startTransaction().apply {
            AutomergeSchema.setTrashedAt(this, AutomergeSchema.entryObjOrNull(d, "n-dune")!!, null)
            commit()
        }
        val back = AutomergeSchema.readLibrary(d).notebooks.getValue("n-dune")
        assertNull(back.trashedAt); assertTrue(back.hasTrashedAt)
    }
}
