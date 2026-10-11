package com.ethran.notable.sync.automerge

import com.ethran.notable.data.RemotePageEdit
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.encodeStrokePoints
import org.automerge.Document
import org.automerge.NewValue
import org.automerge.ObjectId
import org.automerge.AmValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** In-memory Room for the projector; counts writes so idempotency can be asserted. */
internal class FakeSink : ProjectionSink {
    val folders = linkedMapOf<String, Folder>()
    val notebooks = linkedMapOf<String, Notebook>()
    val pages = linkedMapOf<String, Page>()
    val strokes = linkedMapOf<String, Stroke>()
    val images = linkedMapOf<String, Image>()
    var writes = 0

    override fun <T> transaction(block: () -> T): T = block()
    override fun folder(id: String) = folders[id]
    override fun upsertFolder(f: Folder) { writes++; folders[f.id] = f }
    override fun folderHasChildren(id: String) =
        folders.values.any { it.parentFolderId == id } || notebooks.values.any { it.parentFolderId == id } ||
            pages.values.any { it.parentFolderId == id }
    override fun deleteFolder(id: String) { writes++; folders.remove(id) }
    override fun notebook(id: String) = notebooks[id]
    override fun upsertNotebook(n: Notebook) { writes++; notebooks[n.id] = n }
    override fun deleteNotebook(id: String) {
        writes++; notebooks.remove(id)
        pages.values.filter { it.notebookId == id }.map { it.id }.forEach(::cascadePage)
    }
    override fun page(id: String) = pages[id]
    override fun upsertPage(p: Page) {
        writes++
        check(p.notebookId == null || p.notebookId in notebooks) { "page ${p.id}: notebook ${p.notebookId} missing (FK)" }
        pages[p.id] = p
    }
    override fun deletePage(id: String) { writes++; cascadePage(id) }
    override fun strokeIds(pageId: String) = strokes.values.filter { it.pageId == pageId }.map { it.id }
    override fun upsertStrokes(s: List<Stroke>) {
        if (s.isEmpty()) return
        writes++
        s.forEach { check(it.pageId in pages) { "stroke ${it.id}: page ${it.pageId} missing (FK)" }; strokes[it.id] = it }
    }
    override fun deleteStrokes(ids: Collection<String>) { if (ids.isNotEmpty()) { writes++; ids.forEach { strokes.remove(it) } } }
    override fun imageIds(pageId: String) = images.values.filter { it.pageId == pageId }.map { it.id }
    override fun upsertImages(i: List<Image>) { if (i.isNotEmpty()) { writes++; i.forEach { images[it.id] = it } } }
    override fun deleteImages(ids: Collection<String>) { if (ids.isNotEmpty()) { writes++; ids.forEach { images.remove(it) } } }

    private fun cascadePage(id: String) {
        pages.remove(id)
        strokes.values.removeIf { it.pageId == id }
        images.values.removeIf { it.pageId == id }
    }

    /** Everything that matters for equality between two projections. */
    fun snapshot() = listOf(
        folders.toSortedMap(), notebooks.toSortedMap(), pages.toSortedMap(),
        strokes.mapValues { (_, s) -> s.copy(points = s.points) }.toSortedMap().mapValues { it.value.toString() + it.value.points },
        images.toSortedMap(),
    )
}

internal class FakeFiles(val available: MutableSet<String> = mutableSetOf()) : ProjectionFiles {
    val downloads = mutableListOf<String>()
    override fun backgroundPath(blob: String, type: String): String {
        if (blob !in available) throw MissingFileException("blob $blob")
        downloads += blob
        return "/sync-blobs/$blob"
    }
    override fun imageUri(blob: String): String {
        if (blob !in available) throw MissingFileException("blob $blob")
        downloads += blob
        return "file:///sync-blobs/$blob"
    }
    override fun holds(pathOrUri: String, blob: String) = pathOrUri.endsWith(blob)
}

/** "Another device": writes the shared docs the way the PWA or a second Boox would. */
internal class RemoteDevice {
    val library: Document = AutomergeSchema.newLibraryDocument(AmLibrary(emptyMap(), emptyMap()))
    val notebooks = linkedMapOf<String, Document>()

    fun folder(id: String, title: String, parent: String? = null) = editLibrary { tx, folders, _ ->
        AutomergeSchema.putFolder(tx, folders, AmFolder(id, title, parent, t0, t1))
    }

    fun notebook(
        id: String, title: String, pages: List<AmPage>, folder: String? = null, canvasWidth: Double? = 1860.0,
        kind: AmEntryKind = AmEntryKind.NOTEBOOK, source: AmSource = AmSource.None,
    ): String {
        val url = "automerge:$id"
        notebooks[url] = AutomergeSchema.newNotebookDocument(
            AmNotebook(id, pages.firstOrNull()?.id, AmBackground("native", "blank", null), pages.map { it.id },
                pages.associateBy { it.id }, canvasWidth)
        )
        entry(AmNotebookEntry(id, url, title, folder, kind, source, t0, t1))
        return url
    }

    fun entry(e: AmNotebookEntry) = editLibrary { tx, _, notebooks -> AutomergeSchema.putNotebookEntry(tx, notebooks, e) }

    fun editLibrary(edit: (org.automerge.Transaction, ObjectId, ObjectId) -> Unit) {
        val (folders, notebooks) = AutomergeSchema.libraryMapsOf(library)
        library.startTransaction().apply { edit(this, folders, notebooks); commit() }
    }

    fun deleteEntry(id: String) = editLibrary { tx, _, notebooks -> tx.delete(notebooks, id) }

    fun edit(url: String, block: (Document, org.automerge.Transaction) -> Unit) {
        val doc = notebooks.getValue(url)
        doc.startTransaction().apply { block(doc, this); commit() }
    }

    fun addStroke(url: String, pageId: String, s: AmStroke) {
        val doc = notebooks.getValue(url)
        val strokes = AutomergeSchema.strokesOf(doc, pageId)
        doc.startTransaction().apply { AutomergeSchema.putStroke(this, strokes, s); commit() }
    }

    fun deleteStroke(url: String, pageId: String, strokeId: String) {
        val doc = notebooks.getValue(url)
        val strokes = AutomergeSchema.strokesOf(doc, pageId)
        doc.startTransaction().apply { delete(strokes, strokeId); commit() }
    }

    fun setPageOrder(url: String, order: List<String>) = edit(url) { _, tx ->
        val list = tx.set(ObjectId.ROOT, "pageOrder", org.automerge.ObjectType.LIST)
        order.forEachIndexed { i, id -> tx.insert(list, i.toLong(), NewValue.str(id)) }
    }

    fun addPage(url: String, page: AmPage) {
        val doc = notebooks.getValue(url)
        val pagesObj = AutomergeSchema.pagesOf(doc)
        doc.startTransaction().apply { AutomergeSchema.putPage(this, pagesObj, page); commit() }
    }

    fun deletePage(url: String, pageId: String) {
        val doc = notebooks.getValue(url)
        val pagesObj = AutomergeSchema.pagesOf(doc)
        doc.startTransaction().apply { delete(pagesObj, pageId); commit() }
    }
}

internal fun amStroke(id: String, x: Float = 10f, y: Float = 20f, pen: String = "BALLPEN") = AmStroke(
    id = id, pen = pen, color = 0xFF000000.toInt(), size = 3.0,
    left = x.toDouble(), top = y.toDouble(), right = (x + 50).toDouble(), bottom = (y + 30).toDouble(),
    points = encodeStrokePoints(listOf(StrokePoint(x, y, 0.5f), StrokePoint(x + 50, y + 30, 0.7f))),
    createdAt = t0, updatedAt = t1,
)

internal fun amPage(id: String, vararg strokes: AmStroke, createdAt: java.util.Date = t0) = AmPage(
    id, AmBackground("native", "lined", null), strokes.associateBy { it.id }, emptyMap(), createdAt, t1,
)

class ProjectorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val remote = RemoteDevice()
    private val sink = FakeSink()
    private val files = FakeFiles(mutableSetOf("pdfblob", "imgblob"))
    private val stateFile get() = tmp.root.resolve("projection.json")

    private fun projector(s: FakeSink = sink, width: Double = 1860.0) =
        AutomergeProjector(s, files, ProjectionStateStore(stateFile), width)

    /** Projects the library, then every notebook it lists, as the client does. */
    private fun sync(p: AutomergeProjector = projector()): List<RemotePageEdit> {
        val lib = p.projectLibrary(remote.library, "automerge:library")
        val edits = mutableListOf<RemotePageEdit>()
        lib.follow.forEach { e -> remote.notebooks[e.docUrl]?.let { edits += p.projectNotebook(it, e.docUrl).edits } }
        return edits
    }

    @Test
    fun a_new_notebook_appears_with_pages_in_order_and_strokes() {
        remote.folder("f1", "Uni")
        remote.notebook("nb", "Lectures", listOf(amPage("p1", amStroke("s1")), amPage("p2", amStroke("s2"), amStroke("s3"))), folder = "f1")

        sync()

        assertEquals("Uni", sink.folders["f1"]?.title)
        val nb = sink.notebooks.getValue("nb")
        assertEquals("Lectures", nb.title)
        assertEquals("f1", nb.parentFolderId)
        assertEquals(listOf("p1", "p2"), nb.pageIds)
        assertEquals("nb", sink.pages.getValue("p2").notebookId)
        assertEquals(setOf("s1", "s2", "s3"), sink.strokes.keys)
        val s1 = sink.strokes.getValue("s1")
        assertEquals("p1", s1.pageId)
        assertEquals(1, s1.maxPressure)
        assertEquals(10f, s1.points[0].x, 0.01f)
        assertEquals(0.7f, s1.points[1].pressure!!, 0.01f)
    }

    @Test
    fun remote_stroke_add_and_erase_reach_room_and_are_reported() {
        val url = remote.notebook("nb", "N", listOf(amPage("p1", amStroke("s1"), amStroke("s2"))))
        sync()

        remote.addStroke(url, "p1", amStroke("s9", 300f, 400f))
        remote.deleteStroke(url, "p1", "s1")
        val edits = sync()

        assertEquals(setOf("s2", "s9"), sink.strokes.keys)
        val edit = edits.single { it.pageId == "p1" }
        assertEquals(listOf("s9"), edit.upsertStrokes.map { it.id })
        assertEquals(setOf("s1"), edit.deleteStrokes)
    }

    @Test
    fun strokes_only_in_room_survive_incremental_updates_and_rebuilds() {
        val url = remote.notebook("nb", "N", listOf(amPage("p1", amStroke("s1"))))
        sync()
        // Drawn on this Boox after the upload: Room only, not in the doc (Phase 3 doesn't sync it).
        sink.strokes["local"] = sink.strokes.getValue("s1").copy(id = "local")

        remote.addStroke(url, "p1", amStroke("s2"))
        sync()
        assertTrue("local" in sink.strokes)

        projector().forgetAll()
        sync()
        assertTrue("local" in sink.strokes)
        assertEquals(setOf("s1", "s2", "local"), sink.strokes.keys)
    }

    @Test
    fun page_order_follows_the_normalized_doc_order_and_keeps_local_only_pages() {
        val url = remote.notebook("nb", "N", listOf(amPage("p1"), amPage("p2"), amPage("p3")))
        sync()
        // A page created on this Boox after the upload.
        sink.upsertPage(Page("local-page", 0, "nb", "blank", "native", null, t0, t1))
        sink.notebooks["nb"] = sink.notebooks.getValue("nb").let { it.copy(pageIds = it.pageIds + "local-page") }

        // Reordered elsewhere, with a dangling id and a duplicate that normalization must drop.
        remote.setPageOrder(url, listOf("p3", "gone", "p1", "p3"))
        sync()

        assertEquals(listOf("p3", "p1", "p2", "local-page"), sink.notebooks.getValue("nb").pageIds)
    }

    @Test
    fun new_and_deleted_pages() {
        val url = remote.notebook("nb", "N", listOf(amPage("p1", amStroke("s1")), amPage("p2", amStroke("s2"))))
        sync()

        remote.addPage(url, amPage("p3", amStroke("s3"), createdAt = t1))
        remote.deletePage(url, "p2")
        val edits = sync()

        assertEquals(listOf("p1", "p3"), sink.notebooks.getValue("nb").pageIds)
        assertNull(sink.pages["p2"])
        assertNull(sink.strokes["s2"])
        assertEquals("p3", sink.strokes.getValue("s3").pageId)
        assertTrue(edits.single { it.pageId == "p2" }.pageDeleted)
    }

    @Test
    fun folder_and_notebook_moves() {
        remote.folder("a", "A")
        remote.folder("b", "B")
        remote.notebook("nb", "N", listOf(amPage("p1")), folder = "a")
        sync()

        remote.folder("b", "B", parent = "a")
        remote.entry(AmNotebookEntry("nb", "automerge:nb", "N renamed", "b", AmEntryKind.NOTEBOOK, AmSource.None, t0, t1))
        sync()

        assertEquals("a", sink.folders.getValue("b").parentFolderId)
        assertEquals("b", sink.notebooks.getValue("nb").parentFolderId)
        assertEquals("N renamed", sink.notebooks.getValue("nb").title)
    }

    @Test
    fun reapplying_the_same_state_writes_nothing() {
        remote.folder("a", "A")
        val url = remote.notebook("nb", "N", listOf(amPage("p1", amStroke("s1"))), folder = "a")
        sync()
        remote.addStroke(url, "p1", amStroke("s2"))
        sync()

        sink.writes = 0
        val edits = sync()
        assertEquals(0, sink.writes)
        assertTrue(edits.isEmpty())

        // A fresh projector with the persisted heads is just as quiet.
        sink.writes = 0
        sync(projector())
        assertEquals(0, sink.writes)
    }

    @Test
    fun a_full_rebuild_equals_the_incremental_result() {
        remote.folder("a", "A")
        val url = remote.notebook("nb", "N", listOf(amPage("p1", amStroke("s1")), amPage("p2")), folder = "a")
        sync()
        remote.addStroke(url, "p2", amStroke("s2"))
        remote.deleteStroke(url, "p1", "s1")
        remote.addPage(url, amPage("p3", amStroke("s3"), createdAt = t1))
        remote.setPageOrder(url, listOf("p3", "p2", "p1"))
        remote.folder("b", "B", parent = "a")
        sync()

        val fresh = FakeSink()
        tmp.root.resolve("projection.json").delete()
        sync(projector(fresh))

        assertEquals(sink.snapshot(), fresh.snapshot())
    }

    @Test
    fun a_removed_library_entry_deletes_the_notebook() {
        remote.notebook("nb", "N", listOf(amPage("p1", amStroke("s1"))))
        remote.notebook("keep", "K", listOf(amPage("k1")))
        sync()

        remote.deleteEntry("nb")
        sync()

        assertNull(sink.notebooks["nb"])
        assertNull(sink.pages["p1"])
        assertNull(sink.strokes["s1"])
        assertNotNull(sink.notebooks["keep"])
    }

    @Test
    fun a_quick_page_becomes_a_page_without_notebook() {
        remote.folder("a", "A")
        remote.notebook("qp", "Quick page", listOf(amPage("qp", amStroke("s1"))), folder = "a", kind = AmEntryKind.QUICK_PAGE)
        sync()

        val page = sink.pages.getValue("qp")
        assertNull(page.notebookId)
        assertEquals("a", page.parentFolderId)
        assertTrue(sink.notebooks.isEmpty())
        assertEquals("qp", sink.strokes.getValue("s1").pageId)
    }

    @Test
    fun strokes_are_scaled_from_the_drawing_device_canvas_width() {
        remote.notebook("nb", "N", listOf(amPage("p1", amStroke("s1", 10f, 20f))), canvasWidth = 930.0)
        sync()

        val s = sink.strokes.getValue("s1")
        assertEquals(20f, s.points[0].x, 0.01f)
        assertEquals(40f, s.points[0].y, 0.01f)
        assertEquals(20f, s.left, 0.01f)
        assertEquals(6f, s.size, 0.01f)
    }

    @Test
    fun blobs_become_local_files_and_a_missing_one_fails_only_its_notebook_until_it_arrives() {
        val pdfPage = AmPage("p1", AmBackground("autoPdf", null, "missingpdf"), emptyMap(),
            mapOf("i1" to AmImage("i1", "imgblob", 1, 2, 30, 40, t0, t1)), t0, t1)
        remote.notebook("pdf", "Paper", listOf(pdfPage), source = AmSource.Pdf("missingpdf"))
        remote.notebook("ok", "Fine", listOf(amPage("k1")))

        val p = projector()
        val lib = p.projectLibrary(remote.library, "automerge:library")
        val failed = runCatching { p.projectNotebook(remote.notebooks.getValue("automerge:pdf"), "automerge:pdf") }
        assertTrue(failed.exceptionOrNull() is MissingFileException)
        p.projectNotebook(remote.notebooks.getValue("automerge:ok"), "automerge:ok")
        assertNull(sink.notebooks["pdf"])
        assertNotNull(sink.notebooks["ok"])
        assertEquals(2, lib.follow.size)

        files.available += "missingpdf"
        p.projectNotebook(remote.notebooks.getValue("automerge:pdf"), "automerge:pdf")
        assertEquals("/sync-blobs/missingpdf", sink.pages.getValue("p1").background)
        assertEquals("autoPdf", sink.pages.getValue("p1").backgroundType)
        assertEquals("file:///sync-blobs/imgblob", sink.images.getValue("i1").uri)
        assertEquals(30, sink.images.getValue("i1").width)
    }

    @Test
    fun an_existing_background_with_the_same_content_is_kept() {
        val page = AmPage("p1", AmBackground("autoPdf", null, "pdfblob"), emptyMap(), emptyMap(), t0, t1)
        remote.notebook("nb", "N", listOf(page))
        // Migrated from this Boox: Room points at the original file, which holds the same bytes.
        sink.upsertNotebook(Notebook(id = "nb", title = "N", pageIds = listOf("p1"), createdAt = t0, updatedAt = t1))
        sink.upsertPage(Page("p1", 0, "nb", "/storage/Documents/paper-pdfblob", "autoPdf", null, t0, t1))

        sync()

        assertEquals("/storage/Documents/paper-pdfblob", sink.pages.getValue("p1").background)
        assertFalse("pdfblob" in files.downloads)
    }

    @Test
    fun unknown_pens_fall_back_with_a_warning() {
        val url = remote.notebook("nb", "N", listOf(amPage("p1")))
        val p = projector()
        p.projectLibrary(remote.library, "automerge:library")
        p.projectNotebook(remote.notebooks.getValue(url), url)
        remote.addStroke(url, "p1", amStroke("s1", pen = "HOLOGRAM"))

        val result = p.projectNotebook(remote.notebooks.getValue(url), url)

        assertEquals("BALLPEN", sink.strokes.getValue("s1").pen.penName)
        assertTrue(result.warnings.single().contains("HOLOGRAM"))
    }

    @Test
    fun stored_heads_round_trip_through_automerge_diff() {
        val url = remote.notebook("nb", "N", listOf(amPage("p1", amStroke("s1"))))
        sync()
        remote.addStroke(url, "p1", amStroke("s2"))

        // New process: heads come from disk, so only the new stroke is applied.
        val edits = sync(projector())

        assertEquals(listOf("s2"), edits.single().upsertStrokes.map { it.id })
        val doc = remote.notebooks.getValue(url)
        assertTrue(AutomergeSchema.readNotebook(doc).pages.getValue("p1").strokes.containsKey("s2"))
        assertTrue(doc.get(ObjectId.ROOT, "pages").get() is AmValue.Map)
    }
}
