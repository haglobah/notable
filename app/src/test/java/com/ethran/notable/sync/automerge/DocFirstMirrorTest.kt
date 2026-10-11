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

    private val outboxFile by lazy { java.io.File(tmp.newFolder(), "pending-doc-writes.json") }

    private fun outbox(pending: PendingLocalWrites = PendingLocalWrites()) = DocOutbox(outboxFile, pending)

    private fun mirror(
        d: Device?, latency: LatencyStats = LatencyStats(),
        outbox: DocOutbox? = if (d == null) null else outbox(),
        nanoTime: () -> Long = System::nanoTime,
    ) = DocFirstMirror(writer = { d?.writer }, outbox = { outbox }, report = { reported += it }, latency = latency, nanoTime = nanoTime)

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
        val m = mirror(d)
        m.write(change) { d.room.roomWrite(change) }

        assertNotNull("no local edit is lost", d.room.sink.strokes["s2"])
        assertEquals(1, reported.size)
        assertTrue(reported.single()!!.endsWith(": storing failed"))

        failDocs = false
        m.write(LocalChange.StrokesUpserted("p1", listOf(stroke("s3", "p1")))) {}
        assertNull("the next good write clears the error", reported.last())
    }

    @Test
    fun `a failed doc write is kept and reaches the doc on retry, without an echo`() = runBlocking {
        val d = device()
        val box = outbox()
        val m = mirror(d, outbox = box)
        failDocs = true
        val change = LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))
        m.write(change) { d.room.roomWrite(change) }
        assertEquals("pending", 1, box.size)
        assertEquals(1, m.retryPending())
        assertFalse(reported.last() == null)

        failDocs = false
        assertEquals(0, m.retryPending())
        assertTrue("the retry reached the doc", "s2" in d.docStrokes())
        assertEquals(0, box.size)
        assertNull(reported.last())
        d.project()
        assertEquals("settled: no echo into Room", 0, d.room.sink.writes)
    }

    @Test
    fun `a failed doc write survives a restart`() = runBlocking {
        val d = device()
        failDocs = true
        val change = LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))
        mirror(d, outbox = outbox()).write(change) { d.room.roomWrite(change) }
        failDocs = false

        // process restarted: a new outbox reads the same file
        val pending = PendingLocalWrites()
        val box = outbox(pending)
        assertEquals(1, box.size)
        assertTrue("a queued id stays pending after a restart", pending.contains("s2"))
        mirror(d, outbox = box).retryPending()
        assertTrue("s2" in d.docStrokes())
        assertFalse(pending.contains("s2"))
    }

    @Test
    fun `the retry writes what Room holds now`() = runBlocking {
        val d = device()
        val m = mirror(d)
        failDocs = true
        val add = LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))
        m.write(add) { d.room.roomWrite(add) }
        val erase = LocalChange.StrokesDeleted("p1", listOf("s2", "s1"))
        m.write(erase) { d.room.roomWrite(erase) }
        val again = LocalChange.StrokesUpserted("p1", listOf(stroke("s3", "p1")))
        m.write(again) { d.room.roomWrite(again) }
        failDocs = false

        m.retryPending()
        assertEquals(setOf("s3"), d.docStrokes().keys)
        assertEquals(d.room.sink.strokes.keys, d.docStrokes().keys)
    }

    @Test
    fun `failed page, notebook and folder writes are retried from Room`() = runBlocking {
        val d = device()
        val m = mirror(d)
        failDocs = true
        val renamed = d.room.sink.notebooks.getValue("nb1").copy(title = "Renamed")
        m.write(LocalChange.NotebookUpserted(renamed)) { d.room.roomWrite(LocalChange.NotebookUpserted(renamed)) }
        val folder = com.ethran.notable.data.db.Folder(id = "f1", title = "Uni")
        m.create(LocalChange.FolderUpserted(folder)) { d.room.roomWrite(LocalChange.FolderUpserted(folder)) }
        val p2 = Page(id = "p2", notebookId = "nb1", createdAt = t0, updatedAt = t1)
        m.create(LocalChange.PageUpserted(p2)) { d.room.roomWrite(LocalChange.PageUpserted(p2)) }
        failDocs = false

        assertEquals(0, m.retryPending())
        assertEquals("Renamed", d.docs.library().notebooks.getValue("nb1").title)
        assertTrue("f1" in d.docs.library().folders)
        assertTrue("p2" in d.docs.notebook("nb1").pages)
    }

    @Test
    fun `a creation does not clear an earlier failed edit of the same notebook`() = runBlocking {
        val d = device()
        val box = outbox()
        val m = mirror(d, outbox = box)
        failDocs = true
        val rename = LocalChange.NotebookRenamed("nb1", "Renamed")
        m.write(rename) { d.room.roomWrite(rename) }
        assertEquals(1, box.size)
        failDocs = false

        d.room.sink.pages["p2"] = Page(id = "p2", notebookId = "nb1", createdAt = t0, updatedAt = t1)
        val add = LocalChange.PageAdded("nb1", "p2", "p1")
        m.create(add) { d.room.roomWrite(add) }
        assertTrue("p2" in d.docs.notebook("nb1").pages)
        assertEquals("the rename still waits", 1, box.size)

        assertEquals(0, m.retryPending())
        assertEquals("Renamed", d.docs.library().notebooks.getValue("nb1").title)
    }

    @Test
    fun `a debt that keeps failing does not block the others`() = runBlocking {
        val d = device()
        val box = outbox()
        val m = mirror(d, outbox = box)
        failDocs = true
        val img = com.ethran.notable.data.db.Image("i1", 1, 2, 3, 4, "/gone.png", "p1", t0, t1)
        m.write(LocalChange.ImagesUpserted("p1", listOf(img))) { d.room.roomWrite(LocalChange.ImagesUpserted("p1", listOf(img))) }
        val change = LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))
        m.write(change) { d.room.roomWrite(change) }
        failDocs = false

        m.retryPending() // the image's file is gone: it stays pending
        m.retryPending()
        assertTrue("s2" in d.docStrokes())
        assertEquals(1, box.size)
    }

    @Test
    fun `before the library is known, edits wait in the outbox`() = runBlocking {
        val d = device()
        val box = outbox()
        var writer: LocalDocWriter? = null
        val m = DocFirstMirror(writer = { writer }, outbox = { box }, report = { reported += it }, latency = LatencyStats())
        val change = LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))
        m.write(change) { d.room.roomWrite(change) }
        assertEquals(1, box.size)

        writer = d.writer
        m.retryPending()
        assertTrue("s2" in d.docStrokes())
    }

    @Test
    fun `an edit that can't be recorded fails before Room is written`() = runBlocking {
        val d = device()
        val dir = tmp.newFolder()
        val box = DocOutbox(java.io.File(dir, "pending.json"), PendingLocalWrites())
        dir.setWritable(false)
        try {
            failDocs = true
            val change = LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))
            val failed = runCatching { mirror(d, outbox = box).write(change) { d.room.roomWrite(change) } }
            assertTrue(failed.isFailure)
            assertNull("Room must not hold an edit the doc will never get", d.room.sink.strokes["s2"])
        } finally {
            dir.setWritable(true)
        }
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
