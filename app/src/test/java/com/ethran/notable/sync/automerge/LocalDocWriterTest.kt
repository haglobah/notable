package com.ethran.notable.sync.automerge

import com.ethran.notable.data.LocalChange
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageWithData
import org.automerge.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date

/** The docs of one device, in memory: [edit] gives exclusive access, as the repo does. */
internal class MemoryLocalDocs(
    val docs: LinkedHashMap<String, Document> = linkedMapOf(
        LIBRARY to AutomergeSchema.newLibraryDocument(AmLibrary(emptyMap(), emptyMap())),
    ),
) : LocalDocs {
    private var next = 1
    override fun libraryUrl() = LIBRARY
    override fun <T> edit(url: String, block: (Document) -> T): T = synchronized(this) {
        block(docs[url] ?: throw IllegalStateException("no doc $url"))
    }

    override fun createDoc(doc: Document): String = synchronized(this) {
        val url = "automerge:nb${next++}-${System.identityHashCode(this)}"
        docs[url] = Document.load(doc.save())
        url
    }

    fun library() = AutomergeSchema.readLibrary(docs.getValue(LIBRARY))
    fun notebook(id: String) = AutomergeSchema.readNotebook(docs.getValue(library().notebooks.getValue(id).docUrl))

    /** A second device with the same docs so far, as after a sync. */
    fun fork(): MemoryLocalDocs =
        MemoryLocalDocs(LinkedHashMap(docs.mapValues { it.value.fork() })).also { it.next = 1000 }

    /** Exchanges all changes both ways, as a sync through the server would. */
    fun syncWith(other: MemoryLocalDocs) {
        for ((url, doc) in docs) other.docs[url]?.let { doc.merge(it); it.merge(doc) } ?: run { other.docs[url] = doc.fork() }
        for ((url, doc) in other.docs) if (url !in docs) docs[url] = doc.fork()
    }

    companion object {
        const val LIBRARY = "automerge:library"
    }
}

/** Room for one device: the writer reads it, the projector writes it, the "repositories" write it. */
internal class FakeRoom(val sink: FakeSink = FakeSink()) : ProjectionSink by sink {
    /** Full page loads (with all strokes) the writer asked for. */
    var fullPageReads = 0

    /** The same rows as the writer reads them. */
    val library = object : LocalLibrary {
        override fun folders() = sink.folders.values.toList()
        override fun notebooks() = sink.notebooks.values.toList()
        override fun quickPages() = sink.pages.values.filter { it.notebookId == null }
        override fun pageRow(pageId: String) = sink.pages[pageId]
        override fun page(pageId: String): PageWithData? {
            fullPageReads++
            val p = sink.pages[pageId] ?: return null
            return PageWithData(p, sink.strokes.values.filter { it.pageId == pageId }, sink.images.values.filter { it.pageId == pageId })
        }
    }

    /** The repository's own Room write for [change]; not counted in [FakeSink.writes]. */
    fun roomWrite(change: LocalChange) {
        when (change) {
            is LocalChange.StrokesUpserted -> change.strokes.forEach { sink.strokes[it.id] = it }
            is LocalChange.StrokesDeleted -> change.ids.forEach { sink.strokes.remove(it) }
            is LocalChange.ImagesUpserted -> change.images.forEach { sink.images[it.id] = it }
            is LocalChange.ImagesDeleted -> change.ids.forEach { sink.images.remove(it) }
            is LocalChange.PageTouched -> sink.pages[change.pageId]?.let { sink.pages[it.id] = it.copy(updatedAt = Date(change.at)) }
            is LocalChange.PageUpserted -> sink.pages[change.page.id] = change.page
            is LocalChange.PageDeleted -> {
                sink.pages.remove(change.page.id)
                sink.strokes.values.removeIf { it.pageId == change.page.id }
            }
            is LocalChange.NotebookUpserted -> sink.notebooks[change.notebook.id] = change.notebook
            is LocalChange.NotebookDeleted -> sink.notebooks.remove(change.id)
            is LocalChange.OpenPageChanged -> sink.notebooks[change.notebookId]?.let { sink.notebooks[it.id] = it.copy(openPageId = change.pageId) }
            is LocalChange.FolderUpserted -> sink.folders[change.folder.id] = change.folder
            is LocalChange.FolderDeleted -> sink.folders.remove(change.id)
            is LocalChange.NotebookCreated -> Unit // the test put the rows first
            is LocalChange.NotebookRenamed -> notebook(change.id) { it.copy(title = change.title) }
            is LocalChange.NotebookMoved -> notebook(change.id) { it.copy(parentFolderId = change.folderId) }
            is LocalChange.NotebookBackgroundChanged -> notebook(change.id) {
                it.copy(defaultBackgroundType = change.type, defaultBackground = change.value)
            }
            is LocalChange.PageAdded -> notebook(change.notebookId) { it.copy(pageIds = placed(it.pageIds, change.pageId, change.afterPageId)) }
            is LocalChange.PageMoved -> notebook(change.notebookId) { it.copy(pageIds = placed(it.pageIds, change.pageId, change.afterPageId)) }
            is LocalChange.PageBackgroundChanged -> sink.pages[change.pageId]?.let {
                sink.pages[it.id] = it.copy(backgroundType = change.type, background = change.value, updatedAt = Date(change.at))
            }
            is LocalChange.FolderRenamed -> sink.folders[change.id]?.let { sink.folders[it.id] = it.copy(title = change.title, updatedAt = Date(change.at)) }
            is LocalChange.FolderMoved -> sink.folders[change.id]?.let { sink.folders[it.id] = it.copy(parentFolderId = change.parentId, updatedAt = Date(change.at)) }
        }
    }

    private fun notebook(id: String, edit: (Notebook) -> Notebook) {
        sink.notebooks[id]?.let { sink.notebooks[id] = edit(it) }
    }

    private fun placed(order: List<String>, id: String, after: String?): List<String> {
        val rest = order.filter { it != id }
        val at = if (after == null) 0 else rest.indexOf(after).let { if (it < 0) rest.size else it + 1 }
        return rest.take(at) + id + rest.drop(at)
    }
}

/** One device: Room, its docs, the writer and the projector, wired like NotableSyncClient. */
internal class Device(
    tmp: TemporaryFolder,
    val docs: MemoryLocalDocs = MemoryLocalDocs(),
    var clock: Long = 1_780_001_000_000, // after t1, the rows' updatedAt
    canvasWidth: Double = 1860.0,
    /** What the writer sees of [docs]; tests wrap it to record or fail commits. */
    writerDocs: (MemoryLocalDocs) -> LocalDocs = { it },
    /** Ids with a queued local write; the projector leaves them alone. */
    localPending: (String) -> Boolean = { false },
    /** The writer's blob store; [blobs] when null. */
    blobStore: BlobStore? = null,
) {
    val room = FakeRoom()
    private val dir = tmp.newFolder()
    val state = ProjectionStateStore(java.io.File(dir, "projection.json"))
    val blobs = FakeBlobStore()
    val writer = LocalDocWriter(writerDocs(docs), room.library, blobStore ?: blobs, MigrationStateStore(java.io.File(dir, "migration.json")), state, canvasWidth) { clock }
    val files = FakeFiles()
    val projector = AutomergeProjector(room, files, state, canvasWidth, localPending)

    /** What a repository does for an edit or deletion: doc first, then Room, then settle. */
    fun edit(change: LocalChange): WriteReceipt {
        val r = writer.write(change)
        room.roomWrite(change)
        writer.settle(r)
        return r
    }

    /** What a repository does for a creation: Room first, then the doc. */
    fun create(change: LocalChange): WriteReceipt {
        room.roomWrite(change)
        val r = writer.write(change)
        writer.settle(r)
        return r
    }

    /** Runs the projector over every doc, as the projection worker does after changes. */
    fun project(): List<com.ethran.notable.data.RemotePageEdit> {
        val lib = projector.projectLibrary(docs.docs.getValue(MemoryLocalDocs.LIBRARY), MemoryLocalDocs.LIBRARY)
        return lib.edits + docs.library().notebooks.values.flatMap { e ->
            docs.docs[e.docUrl]?.let { projector.projectNotebook(it, e.docUrl).edits }.orEmpty()
        }
    }
}

class LocalDocWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun page(id: String, nb: String?, folder: String? = null) =
        Page(id = id, notebookId = nb, parentFolderId = folder, createdAt = t0, updatedAt = t1)

    private fun notebook(id: String, vararg pages: String, title: String = "Lectures", folder: String? = null) =
        Notebook(id = id, title = title, openPageId = pages.firstOrNull(), pageIds = pages.toList(),
            parentFolderId = folder, createdAt = t0, updatedAt = t1)

    /** A device whose Room has notebook nb1 with page p1 (stroke s1), migrated and projected once. */
    private fun migratedDevice(strokes: Int = 1): Device = Device(tmp).apply {
        room.sink.notebooks["nb1"] = notebook("nb1", "p1")
        room.sink.pages["p1"] = page("p1", "nb1")
        repeat(strokes) { i -> room.sink.strokes["s${i + 1}"] = stroke("s${i + 1}", "p1") }
        val report = writer.migrateAll()
        assertEquals(listOf("nb1"), report.migrated)
        project()
        room.sink.writes = 0
    }

    @Test
    fun `a local stroke reaches the doc and the projector does not write it to Room again`() {
        val d = migratedDevice()
        d.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1"))))

        assertEquals(setOf("s1", "s2"), d.docs.notebook("nb1").pages.getValue("p1").strokes.keys)
        val edits = d.project()
        assertEquals("no echo into Room", 0, d.room.sink.writes)
        assertTrue("no echo into the editor: $edits", edits.isEmpty())
    }

    @Test
    fun `erasing many strokes is one commit`() {
        val d = migratedDevice(strokes = 1000)
        val ids = (1..1000).map { "s$it" }
        val receipt = d.edit(LocalChange.StrokesDeleted("p1", ids))

        val nbUrl = d.docs.library().notebooks.getValue("nb1").docUrl
        assertEquals(1, receipt.commits.count { it.url == nbUrl })
        assertTrue(d.docs.notebook("nb1").pages.getValue("p1").strokes.isEmpty())
        d.project()
        assertEquals(0, d.room.sink.writes)
    }

    @Test
    fun `a crash between the doc commit and the Room write is repaired by the projector`() {
        val d = migratedDevice()
        d.writer.write(LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1"))))
        // process killed here: no Room write, no settle

        val edits = d.project()
        assertNotNull("the stroke reaches Room on the next start", d.room.sink.strokes["s2"])
        assertEquals(setOf("s2"), edits.flatMap { e -> e.upsertStrokes.map { it.id } }.toSet())
    }

    @Test
    fun `a crash between the doc delete and the Room delete is repaired by the projector`() {
        val d = migratedDevice(strokes = 2)
        d.writer.write(LocalChange.StrokesDeleted("p1", listOf("s1")))

        d.project()
        assertNull(d.room.sink.strokes["s1"])
        assertNotNull(d.room.sink.strokes["s2"])
    }

    @Test
    fun `a remote change between the commit and the settle is still projected`() {
        val d = migratedDevice()
        val other = d.docs.fork()
        val change = LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))
        val receipt = d.writer.write(change)
        // another device's stroke arrives before the local Room write finished
        val url = other.library().notebooks.getValue("nb1").docUrl
        val od = other.docs.getValue(url)
        od.startTransaction().apply { AutomergeSchema.putStroke(this, AutomergeSchema.strokesOf(od, "p1"), amStroke("remote")); commit() }
        d.docs.syncWith(other)
        d.room.roomWrite(change)
        d.writer.settle(receipt)

        d.project()
        assertNotNull("remote stroke projected", d.room.sink.strokes["remote"])
        assertNotNull(d.room.sink.strokes["s2"])
    }

    @Test
    fun `a projection that ran before the settle keeps its heads`() {
        val d = migratedDevice()
        val change = LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))
        val receipt = d.writer.write(change)
        d.project() // the projector got there first and applied the commit itself
        d.room.roomWrite(change)
        d.writer.settle(receipt)
        d.room.sink.writes = 0

        d.project()
        assertEquals(0, d.room.sink.writes)
    }

    @Test
    fun `a notebook created here after the migration gets a doc and a library entry`() {
        val d = migratedDevice()
        d.create(LocalChange.PageUpserted(page("q1", "nb2"))) // page row first, as BookRepository.create does
        d.room.sink.notebooks["nb2"] = notebook("nb2", "q1", title = "New")
        d.create(LocalChange.NotebookUpserted(notebook("nb2", "q1", title = "New")))

        val entry = d.docs.library().notebooks.getValue("nb2")
        assertEquals("New", entry.title)
        assertEquals(listOf("q1"), d.docs.notebook("nb2").pageOrder)
        d.project()
        assertEquals(0, d.room.sink.writes)
    }

    @Test
    fun `a page added and moved updates pages and order`() {
        val d = migratedDevice()
        d.create(LocalChange.PageUpserted(page("p2", "nb1")))
        d.edit(LocalChange.NotebookUpserted(notebook("nb1", "p2", "p1")))

        val nb = d.docs.notebook("nb1")
        assertEquals(listOf("p2", "p1"), nb.pageOrder)
        assertEquals("p2", nb.openPageId)
    }

    @Test
    fun `a deleted page leaves pages and order`() {
        val d = migratedDevice()
        d.create(LocalChange.PageUpserted(page("p2", "nb1")))
        d.edit(LocalChange.NotebookUpserted(notebook("nb1", "p1", "p2")))
        d.edit(LocalChange.NotebookUpserted(notebook("nb1", "p1")))
        d.edit(LocalChange.PageDeleted(page("p2", "nb1")))

        val nb = d.docs.notebook("nb1")
        assertEquals(listOf("p1"), nb.pageOrder)
        assertFalse("p2" in nb.pages)
    }

    @Test
    fun `a local move and a remote rename of the same notebook both survive`() {
        val d = migratedDevice()
        val other = Device(tmp, d.docs.fork())
        other.room.sink.notebooks["nb1"] = notebook("nb1", "p1")
        other.room.sink.pages["p1"] = page("p1", "nb1")

        d.edit(LocalChange.FolderUpserted(Folder("f1", "Uni", null, t0, t1)))
        d.edit(LocalChange.NotebookUpserted(notebook("nb1", "p1", folder = "f1")))
        other.edit(LocalChange.NotebookUpserted(notebook("nb1", "p1", title = "Renamed")))
        d.docs.syncWith(other.docs)

        for (lib in listOf(d.docs.library(), other.docs.library())) {
            assertEquals("Renamed", lib.notebooks.getValue("nb1").title)
            assertEquals("f1", lib.notebooks.getValue("nb1").folderId)
        }
    }

    @Test
    fun `deleting a folder deletes what Room cascades with it`() {
        val d = migratedDevice()
        d.edit(LocalChange.FolderUpserted(Folder("f1", "Uni", null, t0, t1)))
        d.edit(LocalChange.FolderUpserted(Folder("f2", "Sem 1", "f1", t0, t1)))
        d.edit(LocalChange.NotebookUpserted(notebook("nb1", "p1", folder = "f2")))
        d.edit(LocalChange.FolderDeleted("f1"))

        val lib = d.docs.library()
        assertTrue(lib.folders.isEmpty())
        assertFalse("nb1" in lib.notebooks)
    }

    @Test
    fun `concurrent stroke add and erase on one page merge`() {
        val a = migratedDevice(strokes = 2)
        val b = Device(tmp, a.docs.fork())
        b.project() // b has synced once: its Room holds s1 and s2

        a.edit(LocalChange.StrokesDeleted("p1", listOf("s1")))
        b.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("b1", "p1"))))
        a.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("a1", "p1"))))
        a.docs.syncWith(b.docs)

        for (d in listOf(a, b)) {
            assertEquals(setOf("s2", "a1", "b1"), d.docs.notebook("nb1").pages.getValue("p1").strokes.keys)
            d.project()
            assertEquals(setOf("s2", "a1", "b1"), d.room.sink.strokes.keys)
        }
    }

    @Test
    fun `undo of an erase brings the same stroke back`() {
        val d = migratedDevice()
        d.edit(LocalChange.StrokesDeleted("p1", listOf("s1")))
        d.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("s1", "p1"))))

        assertEquals(setOf("s1"), d.docs.notebook("nb1").pages.getValue("p1").strokes.keys)
    }

    @Test
    fun `edits made offline merge when the devices meet again`() {
        val a = migratedDevice()
        val b = Device(tmp, a.docs.fork())
        b.room.sink.notebooks["nb1"] = notebook("nb1", "p1")
        b.room.sink.pages["p1"] = page("p1", "nb1")
        repeat(5) { i -> a.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("a$i", "p1")))) }
        repeat(5) { i -> b.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("b$i", "p1")))) }

        a.docs.syncWith(b.docs)
        b.project()
        assertEquals(11, b.room.sink.strokes.size)
    }

    @Test
    fun `a quick page gets its own doc and moves between folders`() {
        val d = migratedDevice()
        d.edit(LocalChange.FolderUpserted(Folder("f1", "Uni", null, t0, t1)))
        d.create(LocalChange.PageUpserted(page("qp", null)))
        assertEquals(AmEntryKind.QUICK_PAGE, d.docs.library().notebooks.getValue("qp").kind)

        d.edit(LocalChange.PageUpserted(page("qp", null, folder = "f1")))
        assertEquals("f1", d.docs.library().notebooks.getValue("qp").folderId)

        d.edit(LocalChange.PageDeleted(page("qp", null, folder = "f1")))
        assertFalse("qp" in d.docs.library().notebooks)
    }

    @Test
    fun `the library's edited date moves at most once a minute`() {
        val d = migratedDevice()
        val before = d.docs.library().notebooks.getValue("nb1").updatedAt
        d.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1"))))
        val first = d.docs.library().notebooks.getValue("nb1").updatedAt
        assertTrue(first.after(before))

        d.clock += 10_000
        d.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("s3", "p1"))))
        assertEquals(first, d.docs.library().notebooks.getValue("nb1").updatedAt)

        d.clock += 61_000
        d.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("s4", "p1"))))
        assertTrue(d.docs.library().notebooks.getValue("nb1").updatedAt.after(first))
    }

    @Test
    fun `opening another page is not an edit`() {
        val d = migratedDevice()
        d.create(LocalChange.PageUpserted(page("p2", "nb1")))
        d.edit(LocalChange.NotebookUpserted(notebook("nb1", "p1", "p2")))
        d.clock += 120_000
        val edited = d.docs.library().notebooks.getValue("nb1").updatedAt

        d.edit(LocalChange.OpenPageChanged("nb1", "p2"))
        assertEquals("p2", d.docs.notebook("nb1").openPageId)
        assertEquals(edited, d.docs.library().notebooks.getValue("nb1").updatedAt)
    }

    @Test
    fun `images are written with their file's hash`() {
        val d = migratedDevice()
        d.blobs.files["/img/cat.png"] = byteArrayOf(1, 2, 3)
        val img = Image("i1", 10, 20, 30, 40, "/img/cat.png", "p1", t0, t1)
        d.edit(LocalChange.ImagesUpserted("p1", listOf(img)))

        val stored = d.docs.notebook("nb1").pages.getValue("p1").images.getValue("i1")
        assertEquals(sha256Hex(byteArrayOf(1, 2, 3)), stored.blob)
        d.edit(LocalChange.ImagesDeleted("p1", listOf("i1")))
        assertTrue(d.docs.notebook("nb1").pages.getValue("p1").images.isEmpty())
    }

    @Test
    fun `rebuilding Room from the docs gives the same state`() {
        val d = migratedDevice(strokes = 3)
        d.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("s9", "p1"))))
        d.edit(LocalChange.StrokesDeleted("p1", listOf("s2")))
        d.create(LocalChange.PageUpserted(page("p2", "nb1")))
        d.edit(LocalChange.NotebookUpserted(notebook("nb1", "p2", "p1", title = "Renamed")))
        val expected = d.room.sink.snapshot()

        val fresh = Device(tmp, d.docs.fork())
        fresh.project()
        val rebuilt = fresh.room.sink
        assertEquals(expected[2].keys, rebuilt.pages.keys)
        assertEquals((expected[3] as Map<*, *>).keys, rebuilt.strokes.keys)
        assertEquals("Renamed", rebuilt.notebooks.getValue("nb1").title)
        assertEquals(listOf("p2", "p1"), rebuilt.notebooks.getValue("nb1").pageIds)
    }


    @Test
    fun `a pen-up reads the page row, not all of its strokes`() {
        val d = migratedDevice(strokes = 50)
        d.room.fullPageReads = 0
        d.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("s99", "p1"))))
        d.edit(LocalChange.StrokesDeleted("p1", listOf("s99")))
        assertEquals(0, d.room.fullPageReads)
    }

    @Test
    fun `strokes and images are stored in the doc's canvas units`() {
        // The doc was made on a 1404 px device; this device draws on 1860 px.
        val small = Device(tmp, canvasWidth = 1404.0).apply {
            room.sink.notebooks["nb1"] = notebook("nb1", "p1")
            room.sink.pages["p1"] = page("p1", "nb1")
            writer.migrateAll()
            project()
        }
        val big = Device(tmp, small.docs.fork())
        big.project()
        big.room.sink.writes = 0
        big.blobs.files["/img/a.png"] = byteArrayOf(9)
        val s = stroke("b1", "p1").copy(left = 186f, right = 372f, top = 93f, bottom = 186f, size = 18.6f)
        big.edit(LocalChange.StrokesUpserted("p1", listOf(s)))
        big.edit(LocalChange.ImagesUpserted("p1", listOf(Image("i1", 186, 372, 186, 930, "/img/a.png", "p1", t0, t1))))

        val stored = big.docs.notebook("nb1").pages.getValue("p1")
        val k = 1404.0 / 1860.0
        assertEquals(186 * k, stored.strokes.getValue("b1").left, 1e-3)
        assertEquals(18.6 * k, stored.strokes.getValue("b1").size, 1e-3)
        val img = stored.images.getValue("i1")
        assertEquals(listOf(140, 281, 702, 140), listOf(img.x, img.y, img.width, img.height))
        val pts = com.ethran.notable.data.db.decodeStrokePoints(stored.strokes.getValue("b1").points)
        assertEquals(5 * k, pts.first().x.toDouble(), 1e-2)

        big.project()
        assertEquals("no echo", 0, big.room.sink.writes)
        small.docs.syncWith(big.docs)
        small.files.available += sha256Hex(byteArrayOf(9))
        small.project()
        assertEquals(186 * k, small.room.sink.strokes.getValue("b1").left.toDouble(), 1e-3)
    }

    @Test
    fun `a stroke edit moves the page's edited date at most once a minute`() {
        val d = migratedDevice()
        d.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1"))))
        val first = d.docs.notebook("nb1").pages.getValue("p1").updatedAt
        assertEquals(Date(d.clock), first)

        d.clock += 10_000
        d.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("s3", "p1"))))
        assertEquals(first, d.docs.notebook("nb1").pages.getValue("p1").updatedAt)

        d.clock += 61_000
        d.edit(LocalChange.StrokesDeleted("p1", listOf("s3")))
        assertEquals(Date(d.clock), d.docs.notebook("nb1").pages.getValue("p1").updatedAt)
        d.project()
        assertEquals(0, d.room.sink.writes)
    }

    @Test
    fun `a stroke on a notebook not uploaded yet uploads it, and nothing comes back into Room`() {
        val d = migratedDevice()
        d.room.sink.notebooks["nb2"] = notebook("nb2", "q1", title = "Later")
        d.room.sink.pages["q1"] = page("q1", "nb2")
        d.room.sink.strokes["old"] = stroke("old", "q1")
        d.room.sink.writes = 0

        d.edit(LocalChange.StrokesUpserted("q1", listOf(stroke("new", "q1"))))

        assertEquals(setOf("old", "new"), d.docs.notebook("nb2").pages.getValue("q1").strokes.keys)
        d.project()
        assertEquals(0, d.room.sink.writes)
    }

    @Test
    fun `upload notebooks and an edit of the same notebook make one doc`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val created = java.util.concurrent.atomic.AtomicInteger()
        /** The first doc creation stops until released, so a second migration of nb1 can run meanwhile. */
        class SlowCreate(private val inner: LocalDocs) : LocalDocs by inner {
            override fun createDoc(doc: Document): String {
                if (created.incrementAndGet() == 1) {
                    entered.countDown()
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                return inner.createDoc(doc)
            }
        }
        val d = Device(tmp, writerDocs = { SlowCreate(it) }).apply {
            room.sink.notebooks["nb1"] = notebook("nb1", "p1")
            room.sink.pages["p1"] = page("p1", "nb1")
            room.sink.strokes["s1"] = stroke("s1", "p1")
        }
        val upload = Thread { d.writer.migrateAll() }.apply { start() }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val edit = Thread { d.writer.write(LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))) }.apply { start() }
        Thread.sleep(300) // the edit's on-demand migration of nb1 gets as far as it can
        release.countDown()
        upload.join(10_000)
        edit.join(10_000)

        assertEquals("one doc for nb1", 1, created.get())
        assertEquals(setOf("s1", "s2"), d.docs.notebook("nb1").pages.getValue("p1").strokes.keys)
    }

    /* ------------------------------ pages, notebooks, folders ------------------------------ */

    /** A device whose Room has notebook nb1 with pages p1..p[n], migrated and projected once. */
    private fun bookDevice(pages: Int = 4): Device = Device(tmp).apply {
        val ids = (1..pages).map { "p$it" }
        room.sink.notebooks["nb1"] = notebook("nb1", *ids.toTypedArray())
        ids.forEach { room.sink.pages[it] = page(it, "nb1") }
        assertEquals(listOf("nb1"), writer.migrateAll().migrated)
        project()
        room.sink.writes = 0
    }

    /** A second device that has synced [a]'s docs and projected them into its own Room. */
    private fun peer(a: Device): Device = Device(tmp, a.docs.fork()).apply {
        project()
        room.sink.writes = 0
    }

    @Test
    fun `reorders of different pages on two devices both survive, replayed from Room`() {
        val a = bookDevice()
        val b = peer(a)
        a.edit(LocalChange.NotebookUpserted(notebook("nb1", "p2", "p3", "p4", "p1")))
        b.edit(LocalChange.NotebookUpserted(notebook("nb1", "p3", "p1", "p2", "p4")))
        a.docs.syncWith(b.docs)

        for (d in listOf(a, b)) {
            assertEquals(listOf("p3", "p2", "p4", "p1"), d.docs.notebook("nb1").pageOrder)
            d.project()
            assertEquals(listOf("p3", "p2", "p4", "p1"), d.room.sink.notebooks.getValue("nb1").pageIds)
        }
    }

    @Test
    fun `a folder moved on one device and renamed on another keeps both, replayed from Room`() {
        val a = migratedDevice()
        a.edit(LocalChange.FolderUpserted(Folder("f1", "Uni", null, t0, t1)))
        a.edit(LocalChange.FolderUpserted(Folder("f2", "Sem 1", null, t0, t1)))
        val b = peer(a)
        a.edit(LocalChange.FolderUpserted(Folder("f2", "Sem 1", "f1", t0, t1)))
        b.edit(LocalChange.FolderUpserted(Folder("f2", "Semester 1", null, t0, t1)))
        a.docs.syncWith(b.docs)

        for (d in listOf(a, b)) {
            val f2 = d.docs.library().folders.getValue("f2")
            assertEquals("Semester 1", f2.title)
            assertEquals("f1", f2.parentId)
            d.project()
            assertEquals(Folder("f2", "Semester 1", "f1", t0, f2.updatedAt), d.room.sink.folders.getValue("f2"))
        }
    }

    @Test
    fun `deleting a page moves the library entry's edited date at once`() {
        val d = bookDevice()
        d.edit(LocalChange.StrokesUpserted("p1", listOf(stroke("s2", "p1")))) // bumps now
        d.clock += 5_000
        d.edit(LocalChange.NotebookUpserted(notebook("nb1", "p1", "p2", "p3")))
        d.edit(LocalChange.PageDeleted(page("p4", "nb1")))
        assertEquals(Date(d.clock), d.docs.library().notebooks.getValue("nb1").updatedAt)
    }

    @Test
    fun `a page reorder on two devices keeps both moves`() {
        val a = bookDevice()
        val b = peer(a)
        a.edit(LocalChange.PageMoved("nb1", "p1", afterPageId = "p4"))
        b.edit(LocalChange.PageMoved("nb1", "p3", afterPageId = null))
        a.docs.syncWith(b.docs)

        for (d in listOf(a, b)) {
            assertEquals(listOf("p3", "p2", "p4", "p1"), d.docs.notebook("nb1").pageOrder)
            d.project()
            assertEquals(listOf("p3", "p2", "p4", "p1"), d.room.sink.notebooks.getValue("nb1").pageIds)
        }
    }

    @Test
    fun `a page moved here lands after the page named, not at an index`() {
        val a = bookDevice()
        val b = peer(a)
        b.edit(LocalChange.PageMoved("nb1", "p4", afterPageId = null)) // b: p4 p1 p2 p3
        a.docs.syncWith(b.docs) // merged, not projected on a yet
        a.edit(LocalChange.PageMoved("nb1", "p1", afterPageId = "p2"))

        assertEquals(listOf("p4", "p2", "p1", "p3"), a.docs.notebook("nb1").pageOrder)
        a.project()
        assertEquals(listOf("p4", "p2", "p1", "p3"), a.room.sink.notebooks.getValue("nb1").pageIds)
    }

    @Test
    fun `a page deleted on one device and drawn on on another is gone on both, and the editor is told`() {
        val a = bookDevice()
        val b = peer(a)
        a.edit(LocalChange.PageDeleted(page("p2", "nb1")))
        b.edit(LocalChange.StrokesUpserted("p2", listOf(stroke("b1", "p2"))))
        b.edit(LocalChange.PageBackgroundChanged("p2", "nb1", "native", "lined", b.clock))
        a.docs.syncWith(b.docs)

        for (d in listOf(a, b)) {
            val nb = d.docs.notebook("nb1")
            assertFalse("p2" in nb.pages)
            assertEquals(listOf("p1", "p3", "p4"), nb.pageOrder)
        }
        val edits = b.project()
        assertTrue("the open editor learns the page is gone: $edits", edits.any { it.pageId == "p2" && it.pageDeleted })
        assertNull(b.room.sink.pages["p2"])
        assertNull(b.room.sink.strokes["b1"])
        assertEquals(listOf("p1", "p3", "p4"), b.room.sink.notebooks.getValue("nb1").pageIds)
    }

    @Test
    fun `a folder moved on one device and renamed on another keeps both`() {
        val a = migratedDevice()
        a.edit(LocalChange.FolderUpserted(Folder("f1", "Uni", null, t0, t1)))
        a.edit(LocalChange.FolderUpserted(Folder("f2", "Sem 1", null, t0, t1)))
        val b = peer(a)
        a.edit(LocalChange.FolderMoved("f2", "f1", a.clock))
        b.edit(LocalChange.FolderRenamed("f2", "Semester 1", b.clock))
        a.docs.syncWith(b.docs)

        for (d in listOf(a, b)) {
            d.project()
            val f2 = d.room.sink.folders.getValue("f2")
            assertEquals("Semester 1", f2.title)
            assertEquals("f1", f2.parentFolderId)
        }
    }

    @Test
    fun `a rename here does not undo a move made elsewhere that is merged but not projected`() {
        val a = migratedDevice()
        a.edit(LocalChange.FolderUpserted(Folder("f1", "Uni", null, t0, t1)))
        val b = peer(a)
        b.edit(LocalChange.NotebookMoved("nb1", "f1"))
        a.docs.syncWith(b.docs)
        a.edit(LocalChange.NotebookRenamed("nb1", "Analysis"))

        val e = a.docs.library().notebooks.getValue("nb1")
        assertEquals("Analysis", e.title)
        assertEquals("f1", e.folderId)
    }

    @Test
    fun `structural edits move the library entry's edited date at once`() {
        val d = bookDevice()
        d.room.sink.pages["p5"] = page("p5", "nb1")
        val steps = listOf<(Device) -> Unit>(
            { it.create(LocalChange.PageAdded("nb1", "p5", afterPageId = "p1")) },
            { it.edit(LocalChange.PageMoved("nb1", "p5", afterPageId = null)) },
            { it.edit(LocalChange.PageBackgroundChanged("p5", "nb1", "native", "dotted", it.clock)) },
            { it.edit(LocalChange.NotebookBackgroundChanged("nb1", "native", "lined")) },
            { it.edit(LocalChange.NotebookRenamed("nb1", "Analysis")) },
            { it.edit(LocalChange.PageDeleted(page("p5", "nb1"))) },
        )
        for (step in steps) {
            d.clock += 5_000
            step(d)
            assertEquals(Date(d.clock), d.docs.library().notebooks.getValue("nb1").updatedAt)
        }
        val nb = d.docs.notebook("nb1")
        assertEquals("lined", nb.defaultBackground.native)
        assertEquals("Analysis", d.docs.library().notebooks.getValue("nb1").title)
        d.project()
        assertEquals("no echo", 0, d.room.sink.writes)
    }

    @Test
    fun `a duplicated page lands after its original with its strokes, in the doc's units`() {
        val small = Device(tmp, canvasWidth = 1404.0).apply {
            room.sink.notebooks["nb1"] = notebook("nb1", "p1")
            room.sink.pages["p1"] = page("p1", "nb1")
            writer.migrateAll()
            project()
        }
        val big = peer(small) // draws on 1860 px; the doc is in 1404 px units
        big.room.sink.pages["p1copy"] = page("p1copy", "nb1")
        big.room.sink.strokes["c1"] = stroke("c1", "p1copy").copy(left = 186f)
        big.create(LocalChange.PageAdded("nb1", "p1copy", afterPageId = "p1"))

        val nb = big.docs.notebook("nb1")
        assertEquals(listOf("p1", "p1copy"), nb.pageOrder)
        assertEquals(186 * 1404.0 / 1860.0, nb.pages.getValue("p1copy").strokes.getValue("c1").left, 1e-3)
        big.project()
        assertEquals("no echo", 0, big.room.sink.writes)
    }

    @Test
    fun `an imported notebook reaches the docs with all its pages, strokes and images`() {
        val d = migratedDevice()
        d.blobs.files["/pdf/book.pdf"] = byteArrayOf(7, 7)
        d.blobs.files["/img/a.png"] = byteArrayOf(1)
        d.room.sink.notebooks["imp"] = notebook("imp", "i1", "i2", title = "Book")
            .copy(defaultBackgroundType = "autoPdf", defaultBackground = "/pdf/book.pdf")
        d.room.sink.pages["i1"] = page("i1", "imp").copy(backgroundType = "autoPdf", background = "/pdf/book.pdf")
        d.room.sink.pages["i2"] = page("i2", "imp").copy(backgroundType = "autoPdf", background = "/pdf/book.pdf")
        d.room.sink.strokes["x1"] = stroke("x1", "i2")
        d.room.sink.images["m1"] = Image("m1", 1, 2, 3, 4, "/img/a.png", "i1", t0, t1)
        d.room.sink.writes = 0
        d.create(LocalChange.NotebookCreated("imp"))

        val entry = d.docs.library().notebooks.getValue("imp")
        assertEquals("Book", entry.title)
        assertEquals(AmSource.Pdf(sha256Hex(byteArrayOf(7, 7))), entry.source)
        val nb = d.docs.notebook("imp")
        assertEquals(listOf("i1", "i2"), nb.pageOrder)
        assertEquals(setOf("x1"), nb.pages.getValue("i2").strokes.keys)
        assertEquals(setOf("m1"), nb.pages.getValue("i1").images.keys)
        d.project()
        assertEquals("no echo", 0, d.room.sink.writes)
    }

    @Test
    fun `a notebook deleted elsewhere tells the open editor its pages are gone`() {
        val a = bookDevice(pages = 2)
        val b = peer(a)
        a.edit(LocalChange.NotebookDeleted("nb1"))
        a.docs.syncWith(b.docs)

        val edits = b.project()
        assertEquals(setOf("p1", "p2"), edits.filter { it.pageDeleted }.map { it.pageId }.toSet())
        assertNull(b.room.sink.notebooks["nb1"])
    }
}
