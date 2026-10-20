package com.ethran.notable.sync.automerge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** Code doc edits on a device's docs (ADR-002): made on first use, whole entries, undefine. */
class CodeDocWriterTest {
    private fun def(name: String, source: String, at: Long, device: String = "android:test") = AmCodeDef(name, source, 1, Date(at), device)

    @Test
    fun the_first_definition_makes_the_code_doc_and_points_the_library_at_it() {
        val docs = MemoryLocalDocs()
        val w = CodeDocWriter(docs)
        assertNull(w.codeDocUrl())
        assertNull(w.read())
        w.define(def("command/a", "() => 1", 1))
        val url = assertNotNullAnd(w.codeDocUrl())
        assertEquals(url, docs.library().codeDocUrl)
        w.define(def("command/b", "() => 2", 2))
        assertEquals("one code doc", url, w.codeDocUrl())
        assertEquals(setOf("command/a", "command/b"), w.read()!!.defs.keys)
        assertEquals(1 + 1, docs.docs.size) // library + code doc
    }

    @Test
    fun undefining_deletes_the_entry_and_reports_a_missing_one() {
        val w = CodeDocWriter(MemoryLocalDocs())
        assertFalse(w.undefine("command/a"))
        w.define(def("command/a", "() => 1", 1))
        assertTrue(w.undefine("command/a"))
        assertFalse(w.undefine("command/a"))
        assertEquals(emptyMap<String, AmCodeDef>(), w.read()!!.defs)
    }

    @Test
    fun a_bad_name_writes_nothing() {
        val w = CodeDocWriter(MemoryLocalDocs())
        w.define(def("command/a", "() => 1", 1))
        runCatching { w.define(def("no-kind", "1", 2)) }
        assertEquals(setOf("command/a"), w.read()!!.defs.keys)
    }

    @Test
    fun two_devices_redefining_one_name_end_with_the_same_whole_entry() {
        val a = MemoryLocalDocs()
        CodeDocWriter(a).define(def("command/a", "() => 'base'", 1))
        val b = a.fork()
        CodeDocWriter(a).define(def("command/a", "() => 'from a'", 2, "android:a"))
        CodeDocWriter(b).define(def("command/a", "() => 'from b'", 3, "pwa"))
        a.syncWith(b)
        val onA = CodeDocWriter(a).read()!!.defs.getValue("command/a")
        val onB = CodeDocWriter(b).read()!!.defs.getValue("command/a")
        assertEquals(onA, onB)
        assertTrue(onA.source to onA.device in listOf("() => 'from a'" to "android:a", "() => 'from b'" to "pwa"))
    }

    @Test
    fun the_save_bytes_carry_the_history() {
        val w = CodeDocWriter(MemoryLocalDocs())
        assertNull(w.save())
        w.define(def("command/a", "() => 1", 1))
        w.define(def("command/a", "() => 2", 2))
        val loaded = org.automerge.Document.load(w.save()!!)
        assertEquals("() => 2", AutomergeSchema.readCode(loaded).defs.getValue("command/a").source)
    }

    private fun assertNotNullAnd(v: String?): String = v.also { assertNotNull(it) }!!
}
