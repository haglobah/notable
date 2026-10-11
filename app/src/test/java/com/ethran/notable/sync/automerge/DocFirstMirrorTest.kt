package com.ethran.notable.sync.automerge

import com.ethran.notable.data.EditorWriteQueue
import com.ethran.notable.data.LocalChange
import com.ethran.notable.data.PendingLocalWrites
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.automerge.Document
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

/** Editor writes: doc commit, on disk, then Room, then settle; Room alone without a session. */
class DocFirstMirrorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = Collections.synchronizedList(mutableListOf<String>())
    private val reported = Collections.synchronizedList(mutableListOf<String?>())
    private var failDocs = false

    @After
    fun tearDown() = scope.cancel()

    /** Logs "durable" when an edit that committed returns, as [RepoLocalDocs] does once on disk. */
    private inner class RecordingDocs(private val inner: LocalDocs) : LocalDocs by inner {
        override fun <T> edit(url: String, block: (Document) -> T): T {
            if (failDocs) throw java.io.IOException("storing failed")
            var committed = false
            val r = inner.edit(url) { d ->
                val before = d.heads
                block(d).also { committed = !sameHeads(before, d.heads) }
            }
            if (committed) log += "durable"
            return r
        }
    }

    private fun device(pending: PendingLocalWrites = PendingLocalWrites()) = Device(
        tmp, writerDocs = { RecordingDocs(it) }, localPending = pending::contains,
    ).apply {
        room.sink.notebooks["nb1"] = Notebook(id = "nb1", title = "Lectures", openPageId = "p1", pageIds = listOf("p1"), createdAt = t0, updatedAt = t1)
        room.sink.pages["p1"] = Page(id = "p1", notebookId = "nb1", createdAt = t0, updatedAt = t1)
        room.sink.strokes["s1"] = stroke("s1", "p1")
        assertEquals(listOf("nb1"), writer.migrateAll().migrated)
        project()
        room.sink.writes = 0
        log.clear()
    }

    private fun mirror(d: Device?, latency: LatencyStats = LatencyStats(), nanoTime: () -> Long = System::nanoTime) =
        DocFirstMirror(writer = { d?.writer }, report = { reported += it }, latency = latency, nanoTime = nanoTime)

    private fun Device.docStrokes() = docs.notebook("nb1").pages.getValue("p1").strokes

    @Test
    fun `the doc commit is on disk before Room is written, and Room is settled after`() = runBlocking {
        val d = device()
        val change = LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))
        mirror(d).write(change) {
            log += "room"
            assertTrue("the doc has the stroke before Room", "s2" in d.docStrokes())
            d.room.roomWrite(change)
        }
        assertEquals(listOf("durable", "room"), log.distinct())
        assertEquals("durable", log.first())

        val edits = d.project()
        assertEquals("settled: no echo into Room", 0, d.room.sink.writes)
        assertTrue("no echo into the editor", edits.isEmpty())
        assertEquals(listOf<String?>(null), reported.distinct())
    }

    @Test
    fun `without a session only Room is written`() = runBlocking {
        val d = device()
        val change = LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))
        val before = d.docStrokes().keys
        val r = mirror(null).write(change) { d.room.roomWrite(change); "done" }

        assertEquals("done", r)
        assertNotNull(d.room.sink.strokes["s2"])
        assertEquals(before, d.docStrokes().keys)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `a failed doc write is reported and Room is still written`() = runBlocking {
        val d = device()
        failDocs = true
        val change = LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))
        mirror(d).write(change) { d.room.roomWrite(change) }

        assertNotNull("no local edit is lost", d.room.sink.strokes["s2"])
        assertEquals(listOf<String?>("An edit is saved on this device only and won't sync: storing failed"), reported.toList())

        failDocs = false
        mirror(d).write(LocalChange.StrokesUpserted("p1", listOf(stroke("s3", "p1")))) {}
        assertNull("the next good write clears the error", reported.last())
    }

    @Test
    fun `a page-cut move through the queue leaves the moved strokes in the doc and in Room`() = runBlocking {
        val d = device()
        val pending = PendingLocalWrites()
        val queue = EditorWriteQueue(mirror(d), pending, scope) { op, e -> throw AssertionError("$op failed", e) }
        val moved = stroke("s1", "p1").copy(left = 500f, right = 555f)
        val remove = LocalChange.StrokesDeleted("p1", listOf("s1"))
        val add = LocalChange.StrokesUpserted("p1", listOf(moved))
        queue.enqueue("remove", remove) { delay(100); d.room.roomWrite(remove) }
        queue.enqueue("add", add) { d.room.roomWrite(add) }
        queue.awaitIdle()

        assertEquals(500f, d.room.sink.strokes.getValue("s1").left)
        assertEquals(500.0, d.docStrokes().getValue("s1").left, 0.01)
        d.project()
        assertEquals(0, d.room.sink.writes)
    }

    @Test
    fun `pen-up to commit latency is recorded`() = runBlocking {
        val d = device()
        val latency = LatencyStats()
        var now = 0L
        val m = mirror(d, latency) { now }
        for (ms in listOf(4L, 10L, 6L)) {
            now = 1_000_000_000L
            m.write(LocalChange.StrokesUpserted("p1", listOf(stroke("s$ms", "p1"))), queuedAtNanos = now - ms * 1_000_000) { now += 50_000_000 }
        }
        assertEquals(LatencySummary(count = 3, p50Ms = 6, maxMs = 10), latency.summary())
    }

    @Test
    fun `a projection waits for a write's Room step and settle`() = runBlocking {
        val d = device()
        val m = mirror(d)
        val inRoom = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val change = LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))
        val write = launch(Dispatchers.IO) { m.write(change) { inRoom.complete(Unit); gate.await(); d.room.roomWrite(change) } }
        inRoom.await()
        val projected = async(Dispatchers.IO) { m.exclusive { log += "projected" } }
        delay(100)
        assertFalse("the projection ran inside a write", "projected" in log)
        gate.complete(Unit)
        write.join()
        projected.await()
        assertTrue("projected" in log)
    }

    @Test
    fun `the projector leaves ids with a queued local write alone`() {
        val pending = PendingLocalWrites()
        val d = device(pending)
        d.room.sink.strokes["s9"] = stroke("s9", "p1")
        val other = d.docs.fork()
        val url = other.library().notebooks.getValue("nb1").docUrl
        val od = other.docs.getValue(url)
        val strokes = AutomergeSchema.strokesOf(od, "p1")
        od.startTransaction().apply {
            delete(strokes, "s1") // erased elsewhere, while this device moves it
            AutomergeSchema.putStroke(this, strokes, amStroke("s9", x = 900f)) // changed elsewhere, while queued here
            AutomergeSchema.putStroke(this, strokes, amStroke("r1"))
            commit()
        }
        d.docs.syncWith(other)
        pending.add(listOf("s1", "s9"))

        val edits = d.project()
        assertNotNull("a queued id is not deleted", d.room.sink.strokes["s1"])
        assertEquals("a queued id is not overwritten", 5f, d.room.sink.strokes.getValue("s9").left)
        assertNotNull("other ids are applied", d.room.sink.strokes["r1"])
        val touched = edits.flatMap { e -> e.upsertStrokes.map { it.id } + e.deleteStrokes }
        assertEquals(listOf("r1"), touched)
    }
}
