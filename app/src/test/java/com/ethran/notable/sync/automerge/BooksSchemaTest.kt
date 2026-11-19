package com.ethran.notable.sync.automerge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.NewValue
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * The book fields of library entries (docs/plans/ebook-features.md): `meta`, `readAt`,
 * `finishedAt`. Mirrors web/schema/test/books.test.ts; TS writes `library-books-v1.automerge`, this
 * test writes `library-books-from-kotlin.automerge` (with `UPDATE_FIXTURES=1`) for the TS test.
 */
class BooksSchemaTest {
    private fun fixture(name: String) = File(FixturePaths.dir, name)
    private val books = FixtureJson.library(fixture("library-books-v1.json"))
    private fun doc() = AutomergeSchema.newLibraryDocument(books)
    private fun reload(doc: Document) = Document.load(doc.save())

    @Test
    fun book_fields_survive_save_and_load() {
        assertEquals(books, AutomergeSchema.readLibrary(reload(doc())))
    }

    @Test
    fun reads_the_ts_written_books_library_exactly() {
        assertEquals(books, AutomergeSchema.readLibrary(Document.load(fixture("library-books-v1.automerge").readBytes())))
    }

    @Test
    fun kotlin_written_books_library_feeds_the_ts_test() {
        val name = "library-books-from-kotlin.automerge"
        if (FixturePaths.update) fixture(name).writeBytes(doc().save())
        assertEquals(books, AutomergeSchema.readLibrary(Document.load(fixture(name).readBytes())))
    }

    @Test
    fun entries_without_book_fields_read_without_them() {
        val e = AutomergeSchema.readLibrary(doc()).notebooks.getValue("p-anal")
        assertNull(e.meta); assertNull(e.readAt); assertNull(e.finishedAt); assertFalse(e.hasFinishedAt)
        val d = doc()
        val entry = AutomergeSchema.entryObjOrNull(d, "p-anal")!!
        listOf("meta", "readAt", "finishedAt").forEach { assertFalse(it, d.get(entry, it).isPresent) }
    }

    @Test
    fun finished_at_null_reads_as_null_not_absent() {
        val e = AutomergeSchema.readLibrary(doc()).notebooks.getValue("b-earth")
        assertNull(e.finishedAt)
        assertTrue(e.hasFinishedAt)
    }

    @Test
    fun meta_missing_a_key_is_refused_naming_the_path() {
        val d = doc()
        val meta = (d.get(AutomergeSchema.entryObjOrNull(d, "b-dune")!!, "meta").get() as AmValue.Map).id
        d.startTransaction().apply { delete(meta, "isbn"); commit() }
        val e = assertThrows(SchemaException::class.java) { AutomergeSchema.readLibrary(d) }
        assertEquals("notebooks.b-dune.meta.isbn: missing", e.message)
    }

    @Test
    fun an_author_stored_as_text_is_refused() {
        val d = doc()
        val meta = (d.get(AutomergeSchema.entryObjOrNull(d, "b-dune")!!, "meta").get() as AmValue.Map).id
        val authors = (d.get(meta, "authors").get() as AmValue.List).id
        d.startTransaction().apply {
            val text = insert(authors, 0, ObjectType.TEXT)
            spliceText(text, 0, 0, "plain text")
            commit()
        }
        val e = assertThrows(SchemaException::class.java) { AutomergeSchema.readLibrary(d) }
        assertEquals("notebooks.b-dune.meta.authors[0]: expected a scalar string, got collaborative Text", e.message)
    }

    @Test
    fun read_at_must_be_a_timestamp() {
        val d = doc()
        d.startTransaction().apply { set(AutomergeSchema.entryObjOrNull(d, "b-dune")!!, "readAt", NewValue.str("yesterday")); commit() }
        val e = assertThrows(SchemaException::class.java) { AutomergeSchema.readLibrary(d) }
        assertEquals("notebooks.b-dune.readAt: expected a timestamp", e.message)
    }

    @Test
    fun read_and_finished_set_one_field_and_merge_with_a_concurrent_rename() {
        val base = doc()
        val reader = base.fork()
        reader.startTransaction().apply {
            val e = AutomergeSchema.entryObjOrNull(reader, "b-dune")!!
            AutomergeSchema.setReadAt(this, e, Date.from(java.time.Instant.parse("2026-10-30T09:00:00Z")))
            AutomergeSchema.setFinishedAt(this, e, Date.from(java.time.Instant.parse("2026-10-30T10:00:00Z")))
            commit()
        }
        val renamer = base.fork()
        renamer.startTransaction().apply {
            AutomergeSchema.setEntryFields(this, AutomergeSchema.entryObjOrNull(renamer, "b-dune")!!, "Dune (1965)", null, false, null)
            commit()
        }
        val merged = reader.fork().apply { merge(renamer) }
        val e = AutomergeSchema.readLibrary(merged).notebooks.getValue("b-dune")
        assertEquals("Dune (1965)", e.title)
        assertEquals(Date.from(java.time.Instant.parse("2026-10-30T09:00:00Z")), e.readAt)
        assertEquals(Date.from(java.time.Instant.parse("2026-10-30T10:00:00Z")), e.finishedAt)
        assertEquals(books.notebooks.getValue("b-dune").meta, e.meta)
        merged.startTransaction().apply { AutomergeSchema.setFinishedAt(this, AutomergeSchema.entryObjOrNull(merged, "b-dune")!!, null); commit() }
        val unread = AutomergeSchema.readLibrary(merged).notebooks.getValue("b-dune")
        assertNull(unread.finishedAt)
        assertTrue(unread.hasFinishedAt)
    }

    @Test
    fun the_search_fixture_library_is_the_books_library() {
        val search = Json.parseToJsonElement(fixture("search-v1.json").readText()).jsonObject
        assertEquals(books, FixtureJson.library(search.getValue("library").jsonObject))
    }

    @Test
    fun the_book_meta_expected_metas_parse_as_fixture_json() {
        // Guards FixtureJson.bookMeta against the shape BookMetaTest reads.
        val files = Json.parseToJsonElement(fixture("book-meta/expected.json").readText()).jsonObject.getValue("files").jsonObject
        val calibre = FixtureJson.bookMeta(files.getValue("epub2-calibre.epub").jsonObject.getValue("meta").jsonObject)
        assertEquals(4.0, calibre.seriesIndex)
        assertEquals(listOf("Science Fiction", "Gender"), calibre.subjects)
        assertTrue(files.getValue("epub2-calibre.epub").jsonObject.getValue("outline").jsonArray.isNotEmpty())
        assertEquals("gethen", files.getValue("epub2-calibre.epub").jsonObject.getValue("search").jsonObject.getValue("query").jsonPrimitive.content)
    }

    @Test
    fun series_index_is_a_float_and_root_untouched() {
        val d = doc()
        val meta = (d.get(AutomergeSchema.entryObjOrNull(d, "b-fr")!!, "meta").get() as AmValue.Map).id
        assertTrue(d.get(meta, "seriesIndex").get() is AmValue.F64)
        assertTrue(d.get(ObjectId.ROOT, "version").get() is AmValue.Int)
    }

    /* Length, progress and rating (round 3). */

    private fun withEntry(id: String, edit: org.automerge.Transaction.(ObjectId) -> Unit): Document = doc().also { d ->
        val e = AutomergeSchema.entryObjOrNull(d, id)!!
        d.startTransaction().apply { edit(e); commit() }
    }

    @Test
    fun a_negative_page_count_and_a_rating_out_of_range_are_refused() {
        val neg = assertThrows(SchemaException::class.java) {
            AutomergeSchema.readLibrary(withEntry("b-dune") { set(it, "pageCount", -1) })
        }
        assertEquals("notebooks.b-dune.pageCount: expected 0 or more, got -1", neg.message)
        val high = assertThrows(SchemaException::class.java) {
            AutomergeSchema.readLibrary(withEntry("b-dune") { set(it, "rating", 11) })
        }
        assertEquals("notebooks.b-dune.rating: expected 1 to 10, got 11", high.message)
        val text = assertThrows(SchemaException::class.java) {
            AutomergeSchema.readLibrary(withEntry("b-dune") { set(it, "readPage", NewValue.str("3")) })
        }
        assertTrue(text.message!!, text.message!!.startsWith("notebooks.b-dune.readPage: expected an integer"))
    }

    @Test
    fun a_cleared_rating_reads_as_null_but_present() {
        val e = AutomergeSchema.readLibrary(withEntry("b-dune") { AutomergeSchema.setRating(this, it, null) }).notebooks.getValue("b-dune")
        assertNull(e.rating)
        assertTrue(e.hasRating)
        val rated = AutomergeSchema.readLibrary(withEntry("b-dune") { AutomergeSchema.setRating(this, it, 7) }).notebooks.getValue("b-dune")
        assertEquals(7, rated.rating)
    }

    @Test
    fun set_reading_writes_only_what_changed() {
        val d = doc()
        val e = AutomergeSchema.entryObjOrNull(d, "b-dune")!!
        fun write(count: Int?, page: Int?): Boolean {
            val tx = d.startTransaction()
            val wrote = AutomergeSchema.setReading(d, tx, e, count, page)
            tx.commit()
            return wrote
        }
        assertTrue(write(10, 3))
        val heads = d.heads.map { it.bytes.toList() }
        assertFalse(write(10, 3))
        assertEquals("an unchanged value makes no op", heads, d.heads.map { it.bytes.toList() })
        assertTrue(write(null, 4))
        val back = AutomergeSchema.readLibrary(d).notebooks.getValue("b-dune")
        assertEquals(10, back.pageCount)
        assertEquals(4, back.readPage)
        assertTrue(d.get(e, "pageCount").get() is AmValue.Int)
    }
}
