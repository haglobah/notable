package com.ethran.notable.sync.automerge

import org.automerge.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Page version points (docs/plans/library-features.md, 40). */
class PageVersionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun heads(): Array<org.automerge.ChangeHash> = Document().apply {
        startTransaction().apply { set(org.automerge.ObjectId.ROOT, "x", Math.random()); commit() }
    }.heads

    @Test
    fun `due after the interval, newest first, capped, and kept across a restart`() {
        var clock = 1_000_000L
        val file = File(tmp.root, "v.json")
        val store = PageVersionStore(file) { clock }
        assertTrue(store.due("p"))
        val first = heads()
        store.record("automerge:a", "p", first)
        assertFalse(store.due("p"))
        store.record("automerge:a", "p", first)
        assertEquals("the same heads twice is one version", 1, store.list("p").size)
        repeat(PageVersionStore.KEEP + 3) { clock += PageVersionStore.INTERVAL_MS; store.record("automerge:a", "p", heads()) }
        val list = store.list("p")
        assertEquals(PageVersionStore.KEEP, list.size)
        assertTrue("newest first", list.zipWithNext().all { (a, b) -> a.at > b.at })
        val again = PageVersionStore(file) { clock }.list("p")
        assertEquals(list.map { it.at }, again.map { it.at })
        assertTrue(sameHeads(list[0].heads, again[0].heads))
    }
}
