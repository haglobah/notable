package com.ethran.notable.data

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class EditorWriteQueueTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = PendingLocalWrites()
    private val failures = Collections.synchronizedList(mutableListOf<String>())

    @After
    fun tearDown() = scope.cancel()

    private fun queue(mirror: ContentMirror = ContentMirror.None) =
        EditorWriteQueue(mirror, pending, scope) { op, e -> failures += "$op: ${e.message}" }

    private fun stroke(id: String, x: Float) = Stroke(
        id = id, size = 2f, pen = Pen.BALLPEN, top = 0f, bottom = 1f, left = x, right = x + 1f,
        points = emptyList(), pageId = "p1",
    )

    @Test
    fun `a page-cut move removes and re-adds the same ids in order, even when the first write is slow`() = runBlocking {
        val room = ConcurrentHashMap<String, Stroke>().apply { put("s1", stroke("s1", 0f)) }
        val q = queue()
        // PageView.cutAndMove: removeStrokes(ids), then addStrokes(moved) with the same ids.
        q.enqueue("remove", LocalChange.StrokesDeleted("p1", listOf("s1"))) { delay(200); room.remove("s1") }
        q.enqueue("add", LocalChange.StrokesUpserted("p1", listOf(stroke("s1", 50f)))) { room["s1"] = stroke("s1", 50f) }
        q.awaitIdle()

        assertEquals("the moved stroke survives", 50f, room["s1"]?.left)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `each write goes through the mirror, one at a time, in order`() = runBlocking {
        val log = Collections.synchronizedList(mutableListOf<String>())
        var running = 0
        val mirror = object : ContentMirror {
            override suspend fun <T> write(change: LocalChange, queuedAtNanos: Long?, room: suspend () -> T): T {
                assertEquals("one write at a time", 0, running++)
                log += "doc $change"
                delay(20)
                return room().also { running-- }
            }
            override suspend fun <T> create(change: LocalChange, room: suspend () -> T): T = room()
        }
        val q = queue(mirror)
        val changes = (1..20).map { LocalChange.StrokesDeleted("p1", listOf("s$it")) }
        changes.forEach { c -> q.enqueue("w", c) { log += "room $c" } }
        q.awaitIdle()

        assertEquals(changes.flatMap { listOf("doc $it", "room $it") }, log)
    }

    @Test
    fun `ids are pending from enqueue until their write is done`() = runBlocking {
        val q = queue()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        q.enqueue("add", LocalChange.StrokesUpserted("p1", listOf(stroke("s1", 0f)))) { gate.await() }
        q.enqueue("remove", LocalChange.ImagesDeleted("p1", listOf("i1"))) {}
        assertTrue(pending.contains("s1"))
        assertTrue(pending.contains("i1"))
        assertFalse(pending.contains("s2"))
        gate.complete(Unit)
        q.awaitIdle()
        assertFalse(pending.contains("s1"))
        assertFalse(pending.contains("i1"))
    }

    @Test
    fun `an id queued twice stays pending until both writes are done`() = runBlocking {
        val q = queue()
        val second = kotlinx.coroutines.CompletableDeferred<Unit>()
        q.enqueue("remove", LocalChange.StrokesDeleted("p1", listOf("s1"))) {}
        q.enqueue("add", LocalChange.StrokesUpserted("p1", listOf(stroke("s1", 0f)))) { second.await() }
        delay(100) // the first write is done by now
        assertTrue(pending.contains("s1"))
        second.complete(Unit)
        q.awaitIdle()
        assertFalse(pending.contains("s1"))
    }

    @Test
    fun `a failed write is reported, releases its ids, and later writes still run`() = runBlocking {
        val q = queue()
        var later = false
        q.enqueue("bad", LocalChange.StrokesDeleted("p1", listOf("s1"))) { throw IllegalStateException("disk I/O error") }
        q.enqueue("good", LocalChange.StrokesDeleted("p1", listOf("s2"))) { later = true }
        q.awaitIdle()

        assertEquals(listOf("bad: disk I/O error"), failures.toList())
        assertTrue(later)
        assertFalse(pending.contains("s1"))
    }

    @Test
    fun `the mirror gets the time the write was queued`() = runBlocking {
        var queuedAt: Long? = null
        val mirror = object : ContentMirror {
            override suspend fun <T> write(change: LocalChange, queuedAtNanos: Long?, room: suspend () -> T): T {
                queuedAt = queuedAtNanos
                return room()
            }
            override suspend fun <T> create(change: LocalChange, room: suspend () -> T): T = room()
        }
        val q = EditorWriteQueue(mirror, pending, scope, nanoTime = { 42L }) { _, _ -> }
        q.enqueue("w", LocalChange.StrokesDeleted("p1", listOf("s1"))) {}
        q.awaitIdle()
        assertEquals(42L, queuedAt)
    }
}
