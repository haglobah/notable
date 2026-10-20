package com.ethran.notable.sync.automerge

import org.automerge.AmValue
import org.automerge.Document
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Date

/**
 * The code doc (ADR-002) against the shared golden fixtures: TS writes `code-v1.automerge` and
 * `library-code-v1.automerge`, Kotlin writes the `*-from-kotlin` twins (with `UPDATE_FIXTURES=1`)
 * that the TS tests read back.
 */
class CodeDocSchemaTest {
    private fun fixture(name: String) = File(FixturePaths.dir, name)
    private val expected = FixtureJson.code(fixture("code-v1.json"))
    private val codeDocUrl = FixtureJson.codeDocUrl(fixture("code-v1.json"))
    private val expectedLibrary = FixtureJson.library(fixture("library-v1.json"))

    private fun load(name: String) = Document.load(fixture(name).readBytes())
    private fun reload(doc: Document) = Document.load(doc.save())
    private fun def(name: String, source: String, device: String, at: String, hostApi: Int = 1) =
        AmCodeDef(name, source, hostApi, Date.from(Instant.parse(at)), device)

    private fun put(doc: Document, d: AmCodeDef) = doc.startTransaction().run {
        AutomergeSchema.putCodeDef(this, AutomergeSchema.codeDefsOf(doc), d)
        commit()
    }

    @Test
    fun reads_the_ts_written_code_doc() {
        assertEquals(expected, AutomergeSchema.readCode(load("code-v1.automerge")))
    }

    @Test
    fun kotlin_written_docs_round_trip_and_feed_the_ts_tests() {
        val code = AutomergeSchema.newCodeDocument(expected)
        val library = AutomergeSchema.newLibraryDocument(expectedLibrary.copy(codeDocUrl = codeDocUrl))
        assertEquals(expected, AutomergeSchema.readCode(reload(code)))
        if (FixturePaths.update) {
            fixture("code-from-kotlin.automerge").writeBytes(code.save())
            fixture("library-code-from-kotlin.automerge").writeBytes(library.save())
        }
        assertEquals(expected, AutomergeSchema.readCode(load("code-from-kotlin.automerge")))
        assertEquals(expectedLibrary.copy(codeDocUrl = codeDocUrl), AutomergeSchema.readLibrary(load("library-code-from-kotlin.automerge")))
    }

    @Test
    fun reads_the_ts_written_library_with_a_code_doc() {
        assertEquals(expectedLibrary.copy(codeDocUrl = codeDocUrl), AutomergeSchema.readLibrary(load("library-code-v1.automerge")))
    }

    @Test
    fun libraries_written_before_code_docs_still_read() {
        for (name in listOf("library-v1.automerge", "library-from-kotlin.automerge")) {
            val lib = AutomergeSchema.readLibrary(load(name))
            assertNull(lib.codeDocUrl)
            assertEquals(expectedLibrary, lib)
        }
    }

    @Test
    fun setting_the_code_doc_url_keeps_the_library_version() {
        val doc = AutomergeSchema.newLibraryDocument(expectedLibrary)
        doc.startTransaction().apply { AutomergeSchema.setCodeDocUrl(this, codeDocUrl); commit() }
        assertEquals(codeDocUrl, AutomergeSchema.readLibrary(reload(doc)).codeDocUrl)
        assertEquals(1L, (doc.get(ObjectId.ROOT, "version").get() as AmValue.Int).value)
    }

    @Test
    fun host_api_is_stored_as_an_int() {
        val doc = AutomergeSchema.newCodeDocument(expected)
        val defs = AutomergeSchema.codeDefsOf(doc)
        val hello = (doc.get(defs, "command/hello").get() as AmValue.Map).id
        assertTrue(doc.get(hello, "hostApi").get() is AmValue.Int)
    }

    @Test
    fun rejects_text_source_and_a_newer_version() {
        val doc = AutomergeSchema.newCodeDocument(expected)
        val hello = (doc.get(AutomergeSchema.codeDefsOf(doc), "command/hello").get() as AmValue.Map).id
        doc.startTransaction().apply {
            spliceText(set(hello, "source", ObjectType.TEXT), 0, 0, "() => 1")
            commit()
        }
        val e = assertThrows(SchemaException::class.java) { AutomergeSchema.readCode(doc) }
        assertTrue(e.message!!, e.message!!.contains("defs.command/hello.source"))

        val newer = AutomergeSchema.newCodeDocument(expected)
        newer.startTransaction().apply { set(ObjectId.ROOT, "version", AutomergeSchema.CODE_VERSION + 1); commit() }
        assertTrue(assertThrows(SchemaException::class.java) { AutomergeSchema.readCode(newer) }.message!!.contains("newer"))
    }

    @Test
    fun writers_refuse_names_without_a_kind() {
        val doc = AutomergeSchema.newCodeDocument(expected)
        assertThrows(IllegalArgumentException::class.java) { put(doc, def("hello", "1", "a", "2026-10-20T09:00:00Z")) }
        assertTrue(AmCodeDef.isDefName("command/clear-page") && !AmCodeDef.isDefName("Command/x") && !AmCodeDef.isDefName("command/"))
    }

    // Two devices redefine one name at once: one entry wins whole, never a mix of both.
    @Test
    fun concurrent_redefinitions_keep_one_whole_entry() {
        val origin = AutomergeSchema.newCodeDocument(expected)
        val a = origin.fork().also { put(it, def("command/hello", "() => 'from a'", "android:a", "2026-10-20T09:00:00Z", 1)) }
        val b = origin.fork().also { put(it, def("command/hello", "() => 'from b'", "pwa", "2026-10-20T09:00:01Z", 2)) }
        val ab = AutomergeSchema.readCode(a.fork().apply { merge(b) }).defs.getValue("command/hello")
        val ba = AutomergeSchema.readCode(b.fork().apply { merge(a) }).defs.getValue("command/hello")
        assertEquals(ab, ba)
        val versions = listOf(AutomergeSchema.readCode(a), AutomergeSchema.readCode(b)).map { it.defs.getValue("command/hello") }
        assertTrue("merged entry $ab is not one of $versions", ab in versions)
    }

    @Test
    fun ts_and_kotlin_merge_concurrent_redefinitions_alike() {
        // A TS-written doc and a Kotlin edit of it merge into an entry both readers agree on.
        val ts = load("code-v1.automerge")
        val kt = ts.fork().also { put(it, def("command/hello", "() => 'kotlin'", "android:a", "2026-10-20T09:00:00Z")) }
        val merged = ts.fork().apply { merge(kt) }
        assertEquals("() => 'kotlin'", AutomergeSchema.readCode(reload(merged)).defs.getValue("command/hello").source)
    }
}
