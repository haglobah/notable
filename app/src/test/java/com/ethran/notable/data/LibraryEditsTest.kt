package com.ethran.notable.data

import androidx.lifecycle.LiveData
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.FolderDao
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.ImageDao
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.NotebookDao
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageDao
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageWithData
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokeDao
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.editor.utils.Pen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** Room in memory, behind the real repositories; every write checks it runs inside a mirror call. */
private class Store {
    val folders = linkedMapOf<String, Folder>()
    val notebooks = linkedMapOf<String, Notebook>()
    val pages = linkedMapOf<String, Page>()
    val strokes = linkedMapOf<String, Stroke>()
    val images = linkedMapOf<String, Image>()
    val log = mutableListOf<String>()
    /** True while a mirror call runs its Room step. */
    var inMirror = false
    val outside = mutableListOf<String>()

    fun write(what: String) {
        log += "room $what"
        if (!inMirror) outside += what
    }

    fun deletePageCascade(id: String) {
        pages.remove(id)
        strokes.values.removeIf { it.pageId == id }
        images.values.removeIf { it.pageId == id }
    }

    val notebookDao = object : NotebookDao {
        override fun getAllInFolder(folderId: String?): LiveData<List<Notebook>> = throw NotImplementedError()
        override fun getAll() = notebooks.values.toList()
        override suspend fun getAllWithBackgroundType(type: String) = notebooks.values.filter { it.defaultBackgroundType == type }
        override suspend fun backgroundsUnder(prefix: String) = notebooks.values.map { it.defaultBackground }.filter { it.startsWith(prefix) }.distinct()
        override suspend fun touchUpdatedAtForPage(pageId: String, updatedAt: Date) {
            val nb = notebooks.values.firstOrNull { pageId in it.pageIds } ?: return
            notebooks[nb.id] = nb.copy(updatedAt = updatedAt)
        }
        override fun getByIdLive(notebookId: String): LiveData<Notebook?> = throw NotImplementedError()
        override suspend fun getById(notebookId: String) = notebooks[notebookId]
        override suspend fun setOpenPageId(notebookId: String, pageId: String) {
            write("openPage"); notebooks[notebookId]?.let { notebooks[it.id] = it.copy(openPageId = pageId) }
        }
        override suspend fun setPageIds(id: String, pageIds: List<String>, updatedAt: Date) {
            write("pageIds"); notebooks[id]?.let { notebooks[id] = it.copy(pageIds = pageIds, updatedAt = updatedAt) }
        }
        private fun change(id: String, edit: (Notebook) -> Notebook): Int =
            notebooks[id]?.let { notebooks[id] = edit(it); 1 } ?: 0
        override suspend fun setTitle(id: String, title: String, updatedAt: Date): Int {
            write("title"); return change(id) { it.copy(title = title, updatedAt = updatedAt) }
        }
        override suspend fun setParentFolder(id: String, folderId: String?, updatedAt: Date): Int {
            write("notebookFolder"); return change(id) { it.copy(parentFolderId = folderId, updatedAt = updatedAt) }
        }
        override suspend fun setDefaultBackground(id: String, type: String, value: String, updatedAt: Date): Int {
            write("defaultBackground")
            return change(id) { it.copy(defaultBackgroundType = type, defaultBackground = value, updatedAt = updatedAt) }
        }
        override suspend fun setLinkedExternalUri(id: String, uri: String?): Int =
            change(id) { it.copy(linkedExternalUri = uri) }
        override suspend fun setReadAt(id: String, at: Date): Int { write("readAt"); return change(id) { it.copy(readAt = at) } }
        override suspend fun setFinishedAt(id: String, at: Date?): Int { write("finishedAt"); return change(id) { it.copy(finishedAt = at) } }
        override suspend fun setRating(id: String, rating: Int?): Int { write("rating"); return change(id) { it.copy(rating = rating) } }
        override suspend fun setPageCount(id: String, count: Int): Int { write("pageCount"); return change(id) { it.copy(pageCount = count) } }
        override suspend fun setQueuedAt(id: String, at: Date?): Int { write("queuedAt"); return change(id) { it.copy(queuedAt = at) } }
        override suspend fun setTrashedAt(id: String, at: Date?): Int { write("trashedAt"); return change(id) { it.copy(trashedAt = at) } }
        override suspend fun create(notebook: Notebook): Long { write("notebook+"); notebooks[notebook.id] = notebook; return 1 }
        override suspend fun update(notebook: Notebook) { write("notebook"); notebooks[notebook.id] = notebook }
        override suspend fun upsert(notebook: Notebook) { write("notebook"); notebooks[notebook.id] = notebook }
        override suspend fun delete(id: String) {
            write("notebook-"); notebooks.remove(id)
            pages.values.filter { it.notebookId == id }.map { it.id }.forEach(::deletePageCascade)
        }
    }

    val pageDao = object : PageDao {
        override suspend fun getByIds(ids: List<String>) = ids.mapNotNull { pages[it] }
        override suspend fun getById(pageId: String) = pages[pageId]
        override suspend fun getPageWithDataById(pageId: String) = pages[pageId]?.let { p ->
            PageWithData(p, strokes.values.filter { it.pageId == pageId }, images.values.filter { it.pageId == pageId })
        }
        override suspend fun updateScroll(pageId: String, scroll: Int) { pages[pageId]?.let { pages[pageId] = it.copy(scroll = scroll) } }
        override suspend fun touchUpdatedAt(pageId: String, updatedAt: Long) { write("pageTouched") }
        override suspend fun setBackground(pageId: String, type: String, value: String, updatedAt: Date) {
            write("pageBackground")
            pages[pageId]?.let { pages[pageId] = it.copy(backgroundType = type, background = value, updatedAt = updatedAt) }
        }
        override fun getSinglePagesInFolder(folderId: String?): LiveData<List<Page>> = throw NotImplementedError()
        override suspend fun getAllQuickPages() = pages.values.filter { it.notebookId == null }
        override suspend fun backgroundsUnder(prefix: String) = pages.values.map { it.background }.filter { it.startsWith(prefix) }.distinct()
        override suspend fun create(page: Page): Long { write("page+"); pages[page.id] = page; return 1 }
        override suspend fun update(page: Page) { write("page"); pages[page.id] = page }
        override suspend fun upsert(page: Page) { write("page"); pages[page.id] = page }
        override suspend fun delete(pageId: String) { write("page-"); deletePageCascade(pageId) }
    }

    val folderDao = object : FolderDao {
        override fun getChildrenFolders(folderId: String?): LiveData<List<Folder>> = throw NotImplementedError()
        override suspend fun get(folderId: String) = folders[folderId]
        override fun getLive(folderId: String): LiveData<Folder?> = throw NotImplementedError()
        override fun getAll() = folders.values.toList()
        override suspend fun create(folder: Folder): Long { write("folder+"); folders[folder.id] = folder; return 1 }
        override suspend fun update(folder: Folder) { write("folder"); folders[folder.id] = folder }
        override suspend fun upsert(folder: Folder) { write("folder"); folders[folder.id] = folder }
        override suspend fun setTitle(id: String, title: String, updatedAt: Date) {
            write("folderTitle"); folders[id]?.let { folders[id] = it.copy(title = title, updatedAt = updatedAt) }
        }
        override suspend fun setParent(id: String, parentId: String?, updatedAt: Date) {
            write("folderParent"); folders[id]?.let { folders[id] = it.copy(parentFolderId = parentId, updatedAt = updatedAt) }
        }
        override suspend fun setTrashedAt(id: String, at: Date?): Int {
            write("folderTrashedAt"); return folders[id]?.let { folders[id] = it.copy(trashedAt = at); 1 } ?: 0
        }
        override suspend fun delete(id: String) {
            write("folder-"); folders.remove(id)
            notebooks.values.filter { it.parentFolderId == id }.map { it.id }.forEach { notebooks.remove(it) }
        }
    }

    val strokeDao = object : StrokeDao {
        override suspend fun create(stroke: Stroke): Long { write("stroke+"); strokes[stroke.id] = stroke; return 1 }
        override suspend fun create(strokes: List<Stroke>) { write("strokes+"); strokes.forEach { this@Store.strokes[it.id] = it } }
        override suspend fun update(stroke: Stroke) { write("stroke"); strokes[stroke.id] = stroke }
        override suspend fun update(strokes: List<Stroke>) { write("strokes"); strokes.forEach { this@Store.strokes[it.id] = it } }
        override suspend fun upsert(strokes: List<Stroke>) { write("strokes"); strokes.forEach { this@Store.strokes[it.id] = it } }
        override suspend fun idsForPage(pageId: String) = strokes.values.filter { it.pageId == pageId }.map { it.id }
        override suspend fun deleteAll(ids: List<String>) { write("strokes-"); ids.forEach { strokes.remove(it) } }
        override suspend fun sumPointsLength(pageId: String) = 0L
        override suspend fun getById(strokeId: String) = strokes.getValue(strokeId)
    }

    val imageDao = object : ImageDao {
        override suspend fun create(image: Image): Long { write("image+"); images[image.id] = image; return 1 }
        override suspend fun create(images: List<Image>) { write("images+"); images.forEach { this@Store.images[it.id] = it } }
        override suspend fun update(image: Image) { write("image"); images[image.id] = image }
        override suspend fun update(images: List<Image>) { write("images"); images.forEach { this@Store.images[it.id] = it } }
        override suspend fun upsert(images: List<Image>) { write("images"); images.forEach { this@Store.images[it.id] = it } }
        override suspend fun idsForPage(pageId: String) = images.values.filter { it.pageId == pageId }.map { it.id }
        override suspend fun urisUnder(prefix: String) = images.values.mapNotNull { it.uri }.filter { it.startsWith(prefix) }.distinct()
        override suspend fun deleteAll(ids: List<String>) { write("images-"); ids.forEach { images.remove(it) } }
        override suspend fun getById(imageId: String) = images.getValue(imageId)
    }
}

/** Logs the doc step of each change; [beforeRoom] runs inside the lock, before the Room step. */
private class RecordingMirror(private val store: Store) : ContentMirror {
    val changes = mutableListOf<Pair<String, LocalChange>>()
    var beforeRoom: () -> Unit = {}
    /** What the docs' library names as the quick notebook; [LocalChange.QuickNotebookSet] sets it. */
    var quick: String? = null

    override suspend fun quickNotebookId(): String? = quick

    override suspend fun <T> write(change: LocalChange, queuedAtNanos: Long?, room: suspend () -> T): T {
        changes += "write" to change
        store.log += "doc ${change::class.simpleName}"
        if (change is LocalChange.QuickNotebookSet) quick = change.id
        return roomStep(room)
    }

    override suspend fun <T> create(change: LocalChange, room: suspend () -> T): T {
        changes += "create" to change
        val r = roomStep(room)
        store.log += "doc ${change::class.simpleName}"
        return r
    }

    private suspend fun <T> roomStep(room: suspend () -> T): T {
        beforeRoom()
        beforeRoom = {}
        store.inMirror = true
        try {
            return room()
        } finally {
            store.inMirror = false
        }
    }
}

class LibraryEditsTest {
    private val t0 = Date(1_780_000_000_000)
    private val store = Store()
    private val mirror = RecordingMirror(store)
    private val published = mutableListOf<RemotePageEdit>()
    private val edits = LibraryEdits(
        BookRepository(store.notebookDao, store.pageDao), PageRepository(store.pageDao), FolderRepository(store.folderDao),
        StrokeRepository(store.strokeDao), ImageRepository(store.imageDao), mirror,
    ).also {
        it.now = { 1_780_000_500_000 }
        it.publish = { e -> published += e; true }
    }

    private fun stroke(id: String, pageId: String) = Stroke(
        id = id, size = 3f, pen = Pen.BALLPEN, color = 0, top = 1f, bottom = 2f, left = 1f, right = 2f,
        points = listOf(StrokePoint(1f, 1f, 0.5f)), pageId = pageId, createdAt = t0, updatedAt = t0,
    )

    /** Notebook nb1 in folder f1 with pages p1..p[n]. */
    private fun book(n: Int = 3) {
        store.folders["f1"] = Folder("f1", "Uni", null, t0, t0)
        val ids = (1..n).map { "p$it" }
        store.notebooks["nb1"] = Notebook("nb1", "Lectures", "p1", ids, "f1", createdAt = t0, updatedAt = t0)
        ids.forEach { store.pages[it] = Page(it, notebookId = "nb1", createdAt = t0, updatedAt = t0) }
        store.strokes["s1"] = stroke("s1", "p1")
    }

    private fun lastChange() = mirror.changes.last()

    @Test
    fun `every library and page edit writes Room only inside a mirror call`() = runBlocking {
        book()
        val ops: List<suspend () -> Unit> = listOf(
            { edits.createFolder("f1") },
            { edits.renameFolder("f1", "Uni 2") },
            { edits.createNotebook(Notebook(id = "nb2", parentFolderId = "f1")) },
            { edits.renameNotebook("nb1", "Analysis") },
            { edits.moveNotebook("nb1", null) },
            { edits.setNotebookBackground("nb1", "native", "lined") },
            { edits.addPage("nb1", 1) },
            { edits.movePage("nb1", "p3", 0) },
            { edits.duplicatePage("p1") },
            { edits.setPageBackground("p2", "native", "dotted") },
            { edits.setOpenPage("nb1", "p2") },
            { edits.markNotebookRead("nb1") },
            { edits.setNotebookFinished("nb1", true) },
            { edits.createQuickPage("f1", "blank") },
            { edits.deletePage("p2") },
            { edits.importNotebook(Notebook(id = "imp", title = "Book")) { page(Page(notebookId = "imp")) } },
            { edits.deleteNotebook("nb2") },
            { edits.deleteFolder("f1") },
        )
        for (op in ops) op()
        assertEquals("Room writes outside the mirror", emptyList<String>(), store.outside)
        assertEquals(ops.size, mirror.changes.size)
    }

    @Test
    fun `a notebook edit fails visibly when Room no longer has the notebook`() = runBlocking {
        val ops: List<suspend () -> Unit> = listOf(
            { edits.renameNotebook("gone", "Name") },
            { edits.moveNotebook("gone", null) },
            { edits.setNotebookBackground("gone", "native", "lined") },
            { edits.setNotebookLinkedUri("gone", null) },
            { edits.markNotebookRead("gone") },
            { edits.setNotebookFinished("gone", true) },
        )
        for (op in ops) {
            val error = runCatching { op() }.exceptionOrNull()
            assertEquals("Notebook no longer exists", (error as? IllegalStateException)?.message)
        }
    }

    @Test
    fun `edits and deletions reach the doc before Room, creations after`() = runBlocking {
        book()
        edits.renameNotebook("nb1", "Analysis")
        assertEquals(listOf("doc NotebookRenamed", "room title"), store.log)
        store.log.clear()
        edits.addPage("nb1", 3)
        assertEquals(listOf("room page+", "room pageIds", "doc PageAdded"), store.log)
    }

    @Test
    fun `reading state goes to the doc first and leaves the edited date`() = runBlocking {
        book()
        edits.markNotebookRead("nb1")
        assertEquals("write" to LocalChange.NotebookRead("nb1", 1_780_000_500_000), lastChange())
        edits.setNotebookFinished("nb1", true)
        assertEquals("write" to LocalChange.NotebookFinished("nb1", 1_780_000_500_000), lastChange())
        assertEquals(listOf("doc NotebookRead", "room readAt", "doc NotebookFinished", "room finishedAt"), store.log)
        val nb = store.notebooks.getValue("nb1")
        assertEquals(Date(1_780_000_500_000), nb.readAt)
        assertEquals(Date(1_780_000_500_000), nb.finishedAt)
        assertEquals(t0, nb.updatedAt)
        edits.setNotebookFinished("nb1", false)
        assertEquals("write" to LocalChange.NotebookFinished("nb1", null), lastChange())
        assertNull(store.notebooks.getValue("nb1").finishedAt)
    }

    @Test
    fun `read next and the trash go to the doc first and leave the edited date`() = runBlocking {
        book()
        val at = Date(1_780_000_500_000)
        edits.setNotebookQueued("nb1", true)
        assertEquals("write" to LocalChange.NotebookQueued("nb1", at.time), lastChange())
        assertEquals(at, store.notebooks.getValue("nb1").queuedAt)
        edits.setNotebookQueued("nb1", false)
        assertEquals("write" to LocalChange.NotebookQueued("nb1", null), lastChange())
        edits.trashNotebook("nb1")
        assertEquals("write" to LocalChange.NotebookTrashed("nb1", at.time), lastChange())
        edits.trashFolder("f1")
        assertEquals("write" to LocalChange.FolderTrashed("f1", at.time), lastChange())
        assertEquals(at, store.folders.getValue("f1").trashedAt)
        assertEquals(t0, store.notebooks.getValue("nb1").updatedAt)
        assertEquals(t0, store.folders.getValue("f1").updatedAt)
        assertEquals("Room writes outside the mirror", emptyList<String>(), store.outside)
    }

    @Test
    fun `a notebook restored from a trashed folder goes to the root, the folder keeps its place`() = runBlocking {
        book()
        store.folders["f2"] = Folder("f2", "Inner", "f1", t0, t0)
        edits.trashFolder("f1")
        edits.trashNotebook("nb1")
        edits.trashFolder("f2")
        edits.restoreNotebook("nb1")
        assertNull(store.notebooks.getValue("nb1").trashedAt)
        assertNull("its folder is in the trash", store.notebooks.getValue("nb1").parentFolderId)
        edits.restoreFolder("f2")
        assertNull(store.folders.getValue("f2").parentFolderId)
        edits.restoreFolder("f1")
        assertNull(store.folders.getValue("f1").trashedAt)
        assertNull(store.folders.getValue("f1").parentFolderId)
    }

    @Test
    fun `restoring into a live folder keeps the folder`() = runBlocking {
        book()
        edits.trashNotebook("nb1")
        edits.restoreNotebook("nb1")
        assertEquals("f1", store.notebooks.getValue("nb1").parentFolderId)
        assertEquals("write" to LocalChange.NotebookTrashed("nb1", null), lastChange())
    }

    @Test
    fun `the trash empties what has been in it for 30 days`() = runBlocking {
        book()
        val day = 86_400_000L
        store.folders["old"] = Folder("old", "Old", null, t0, t0, trashedAt = Date(1_780_000_500_000 - 31 * day))
        store.notebooks["nbOld"] = Notebook("nbOld", "Old", parentFolderId = null, trashedAt = Date(1_780_000_500_000 - 30 * day))
        store.notebooks["nbNew"] = Notebook("nbNew", "New", parentFolderId = null, trashedAt = Date(1_780_000_500_000 - 29 * day))
        assertEquals(2, edits.emptyExpiredTrash())
        assertFalse("old" in store.folders)
        assertFalse("nbOld" in store.notebooks)
        assertTrue("nbNew" in store.notebooks)
        assertTrue("nb1" in store.notebooks)
    }

    @Test
    fun `pages move to another notebook as copies with new ids, the originals deleted after`() = runBlocking {
        book(3)
        store.notebooks["nb2"] = Notebook("nb2", "Other", "q1", listOf("q1"), null, createdAt = t0, updatedAt = t0)
        store.pages["q1"] = Page("q1", notebookId = "nb2", createdAt = t0, updatedAt = t0)
        store.pages["p1"] = store.pages.getValue("p1").copy(backgroundType = "autoPdf", background = "book.pdf")
        mirror.changes.clear()
        assertEquals(2, edits.movePagesTo(listOf("p1", "p3"), "nb2"))
        assertEquals(listOf("p2"), store.notebooks.getValue("nb1").pageIds)
        val target = store.notebooks.getValue("nb2").pageIds
        assertEquals(3, target.size)
        assertEquals("q1", target[0])
        val (m1, m3) = target[1] to target[2]
        assertTrue(m1 != "p1" && m3 != "p3")
        assertFalse("p1" in store.pages || "p3" in store.pages)
        assertEquals("nb2", store.pages.getValue(m1).notebookId)
        assertEquals("the PDF page it showed, pinned", "pdf0", store.pages.getValue(m1).backgroundType)
        assertEquals("its stroke came along", 1, store.strokes.values.count { it.pageId == m1 })
        // Copy first, then delete, per page.
        val kinds = mirror.changes.map { it.second::class.simpleName }
        assertEquals(listOf("PageAdded", "PageDeleted", "PageAdded", "PageDeleted"), kinds)
        assertEquals("a page already there stays", 0, edits.movePagesTo(listOf("q1"), "nb2"))
    }

    @Test
    fun `restoring a version replaces the handwriting through the doc and marks the page edited`() = runBlocking {
        book(1)
        store.strokes["s2"] = stroke("s2", "p1")
        edits.restorePageStrokes("p1", listOf(stroke("old", "p1")))
        assertEquals(setOf("old"), store.strokes.values.filter { it.pageId == "p1" }.map { it.id }.toSet())
        val kinds = mirror.changes.map { it.second::class.simpleName }
        assertEquals(listOf("StrokesDeleted", "StrokesUpserted", "PageTouched"), kinds)
        assertEquals("Room writes outside the mirror", emptyList<String>(), store.outside)
        assertEquals(setOf("s1", "s2"), published.single().deleteStrokes)
    }

    @Test
    fun `a page can be added with its own template`() = runBlocking {
        book(2)
        val id = edits.addPageWithBackground("nb1", 1, "native", "lined")!!
        assertEquals(listOf("p1", id, "p2"), store.notebooks.getValue("nb1").pageIds)
        assertEquals("lined", store.pages.getValue(id).background)
    }

    @Test
    fun `ratings and page count backfills go to the doc first`() = runBlocking {
        book()
        edits.rateNotebook("nb1", 9)
        assertEquals("write" to LocalChange.NotebookRated("nb1", 9), lastChange())
        assertEquals(9, store.notebooks.getValue("nb1").rating)
        edits.rateNotebook("nb1", null)
        assertNull(store.notebooks.getValue("nb1").rating)
        assertEquals(1, edits.backfillPageCounts())
        assertEquals("write" to LocalChange.ReadingSynced("nb1"), lastChange())
        assertEquals(3, store.notebooks.getValue("nb1").pageCount)
        assertEquals("nothing due any more", 0, edits.backfillPageCounts())
        assertEquals(t0, store.notebooks.getValue("nb1").updatedAt)
        assertTrue(runCatching { edits.rateNotebook("nb1", 11) }.isFailure)
    }

    @Test
    fun `quick page makes the quick notebook once, then appends a page to its end and opens it`() = runBlocking {
        val first = edits.openQuickPage("blank")!!
        val nb = store.notebooks.getValue(first.notebookId)
        assertEquals(LibraryEdits.QUICK_NOTEBOOK_TITLE, nb.title)
        assertNull(nb.parentFolderId)
        assertEquals(listOf(first.pageId), nb.pageIds)
        assertEquals("write" to LocalChange.QuickNotebookSet(nb.id), lastChange())
        val second = edits.openQuickPage("blank")!!
        assertEquals(first.notebookId, second.notebookId)
        assertEquals(listOf(first.pageId, second.pageId), store.notebooks.getValue(nb.id).pageIds)
        val third = edits.openQuickPage("blank")!!
        assertEquals(listOf(first.pageId, second.pageId, third.pageId), store.notebooks.getValue(nb.id).pageIds)
        assertEquals(1, store.notebooks.size)
    }

    @Test
    fun `old quick pages move into the quick notebook oldest first, keeping their strokes`() = runBlocking {
        book()
        mirror.quick = "nb1"
        store.pages["qa"] = Page("qa", notebookId = null, parentFolderId = "f1", createdAt = Date(t0.time + 2), updatedAt = t0)
        store.pages["qb"] = Page("qb", notebookId = null, createdAt = Date(t0.time + 1), updatedAt = t0)
        store.strokes["qs"] = stroke("qs", "qa")
        assertEquals(2, edits.migrateQuickPages())
        assertEquals(listOf("p1", "p2", "p3", "qb", "qa"), store.notebooks.getValue("nb1").pageIds)
        assertEquals("nb1", store.pages.getValue("qa").notebookId)
        assertNull(store.pages.getValue("qa").parentFolderId)
        assertEquals("qa", store.strokes.getValue("qs").pageId)
        assertEquals(listOf("write" to LocalChange.QuickPageMoved("qb", "nb1"), "write" to LocalChange.QuickPageMoved("qa", "nb1")),
            mirror.changes.takeLast(2))
        assertEquals("nothing left to move", 0, edits.migrateQuickPages())
    }

    @Test
    fun `without a quick notebook the migration makes one`() = runBlocking {
        store.pages["qa"] = Page("qa", notebookId = null, createdAt = t0, updatedAt = t0)
        assertEquals(1, edits.migrateQuickPages())
        val nb = store.notebooks.values.single()
        assertEquals(LibraryEdits.QUICK_NOTEBOOK_TITLE, nb.title)
        assertEquals(listOf("qa"), nb.pageIds)
        assertEquals(nb.id, mirror.quick)
    }

    @Test
    fun `a page moved to an index is placed after the page now before it`() = runBlocking {
        book(4)
        edits.movePage("nb1", "p1", 2) // p2 p3 p1 p4
        assertEquals("write" to LocalChange.PageMoved("nb1", "p1", "p3"), lastChange())
        assertEquals(listOf("p2", "p3", "p1", "p4"), store.notebooks.getValue("nb1").pageIds)
        edits.movePage("nb1", "p4", 0)
        assertEquals("write" to LocalChange.PageMoved("nb1", "p4", null), lastChange())
        assertEquals(listOf("p4", "p2", "p3", "p1"), store.notebooks.getValue("nb1").pageIds)
    }

    @Test
    fun `a move keeps a page the projector added after the screen read the order`() = runBlocking {
        book(3)
        // The screen showed p1 p2 p3; a page from another device lands before the move's Room step.
        mirror.beforeRoom = {
            store.pages["remote"] = Page("remote", notebookId = "nb1", createdAt = t0, updatedAt = t0)
            store.notebooks["nb1"] = store.notebooks.getValue("nb1").let { it.copy(pageIds = it.pageIds + "remote") }
        }
        edits.movePage("nb1", "p1", 1)
        assertEquals(listOf("p2", "p1", "p3", "remote"), store.notebooks.getValue("nb1").pageIds)
    }

    @Test
    fun `a rename or move changes only its own field`() = runBlocking {
        book(2)
        mirror.beforeRoom = { store.notebooks["nb1"] = store.notebooks.getValue("nb1").copy(pageIds = listOf("p2", "p1")) }
        edits.renameNotebook("nb1", "Analysis")
        edits.moveNotebook("nb1", null)
        val nb = store.notebooks.getValue("nb1")
        assertEquals(listOf("p2", "p1"), nb.pageIds)
        assertEquals("Analysis", nb.title)
        assertNull(nb.parentFolderId)
        assertEquals(listOf(
            "write" to LocalChange.NotebookRenamed("nb1", "Analysis"),
            "write" to LocalChange.NotebookMoved("nb1", null),
        ), mirror.changes)
    }

    @Test
    fun `a new page lands after the page at the index before it`() = runBlocking {
        book(2)
        val id = edits.addPage("nb1", 1)!!
        assertEquals("create" to LocalChange.PageAdded("nb1", id, "p1"), lastChange())
        assertEquals(listOf("p1", id, "p2"), store.notebooks.getValue("nb1").pageIds)
        assertNotNull(store.pages[id])
        val first = edits.addPage("nb1", 0)!!
        assertEquals("create" to LocalChange.PageAdded("nb1", first, null), lastChange())
    }

    @Test
    fun `a duplicated page follows its original with copies of its strokes`() = runBlocking {
        book(2)
        val copy = edits.duplicatePage("p1")!!
        assertEquals("create" to LocalChange.PageAdded("nb1", copy, "p1"), lastChange())
        assertEquals(listOf("p1", copy, "p2"), store.notebooks.getValue("nb1").pageIds)
        assertEquals(1, store.strokes.values.count { it.pageId == copy })
    }

    @Test
    fun `a duplicated quick page is a new quick page`() = runBlocking {
        store.pages["q1"] = Page("q1", notebookId = null, parentFolderId = null, createdAt = t0, updatedAt = t0)
        val copy = edits.duplicatePage("q1")!!
        val (kind, change) = lastChange()
        assertEquals("create", kind)
        assertEquals(copy, (change as LocalChange.PageUpserted).page.id)
    }

    @Test
    fun `deleting a page removes it from its notebook and tells an open editor`() = runBlocking {
        book(3)
        edits.deletePage("p2")
        val (kind, change) = lastChange()
        assertEquals("write", kind)
        assertEquals("p2", (change as LocalChange.PageDeleted).page.id)
        assertEquals(listOf("p1", "p3"), store.notebooks.getValue("nb1").pageIds)
        assertNull(store.pages["p2"])
        assertEquals(listOf(RemotePageEdit("p2", pageDeleted = true)), published)
    }

    @Test
    fun `a page id the notebook lists but Room lacks is removed from the list`() = runBlocking {
        book(2)
        store.notebooks["nb1"] = store.notebooks.getValue("nb1").copy(pageIds = listOf("p1", "ghost", "p2"))
        edits.deletePage("ghost", notebookId = "nb1")
        val (kind, change) = lastChange()
        assertEquals("write", kind)
        assertEquals(Page(id = "ghost", notebookId = "nb1").let { it.id to it.notebookId },
            (change as LocalChange.PageDeleted).page.let { it.id to it.notebookId })
        assertEquals(listOf("p1", "p2"), store.notebooks.getValue("nb1").pageIds)
    }

    @Test
    fun `deleting a notebook tells an open editor about each of its pages`() = runBlocking {
        book(2)
        edits.deleteNotebook("nb1")
        assertEquals("write" to LocalChange.NotebookDeleted("nb1"), lastChange())
        assertNull(store.notebooks["nb1"])
        assertEquals(setOf("p1", "p2"), published.filter { it.pageDeleted }.map { it.pageId }.toSet())
    }

    @Test
    fun `a new notebook and an import are created in Room first, then in the doc`() = runBlocking {
        val nb = Notebook(id = "nb2", title = "New")
        edits.createNotebook(nb)
        assertEquals("create" to LocalChange.NotebookCreated("nb2"), lastChange())
        assertEquals(1, store.notebooks.getValue("nb2").pageIds.size)
        assertEquals("doc NotebookCreated", store.log.last())

        store.log.clear()
        val pageIds = edits.importNotebook(Notebook(id = "imp", title = "Book")) {
            (1..3).map { i ->
                val p = Page(id = "i$i", notebookId = "imp")
                page(p)
                strokes(listOf(stroke("x$i", p.id)))
                p.id
            }
        }
        assertEquals("create" to LocalChange.NotebookCreated("imp"), lastChange())
        assertEquals(pageIds, store.notebooks.getValue("imp").pageIds)
        assertEquals(3, store.strokes.values.count { it.pageId.startsWith("i") })
        assertEquals("doc NotebookCreated", store.log.last())
    }

    @Test
    fun `folders are created, renamed, moved and deleted through the doc`() = runBlocking {
        val f1 = edits.createFolder(null)
        val f2 = edits.createFolder(null)
        assertEquals("write", lastChange().first)
        assertTrue(lastChange().second is LocalChange.FolderUpserted)
        edits.renameFolder(f2.id, "Sem 1")
        assertEquals("write" to LocalChange.FolderRenamed(f2.id, "Sem 1", 1_780_000_500_000), lastChange())
        assertTrue(edits.moveFolder(f2.id, f1.id))
        assertEquals("write" to LocalChange.FolderMoved(f2.id, f1.id, 1_780_000_500_000), lastChange())
        assertEquals(Folder(f2.id, "Sem 1", f1.id, f2.createdAt, Date(1_780_000_500_000)), store.folders.getValue(f2.id))

        val count = mirror.changes.size
        assertFalse("a folder can't move into its own subfolder", edits.moveFolder(f1.id, f2.id))
        assertEquals(count, mirror.changes.size)

        edits.deleteFolder(f1.id)
        assertEquals("write" to LocalChange.FolderDeleted(f1.id), lastChange())
    }

    @Test
    fun `backgrounds, the open page and quick pages go through the doc`() = runBlocking {
        book(2)
        edits.setPageBackground("p2", "native", "dotted")
        assertEquals("write" to LocalChange.PageBackgroundChanged("p2", "nb1", "native", "dotted", 1_780_000_500_000), lastChange())
        assertEquals("dotted", store.pages.getValue("p2").background)
        edits.setPageBackground("p2", "image", null) // keeps the current value
        assertEquals("write" to LocalChange.PageBackgroundChanged("p2", "nb1", "image", "dotted", 1_780_000_500_000), lastChange())

        edits.setNotebookBackground("nb1", "native", "lined")
        assertEquals("write" to LocalChange.NotebookBackgroundChanged("nb1", "native", "lined"), lastChange())

        edits.setOpenPage("nb1", "p2")
        assertEquals("write" to LocalChange.OpenPageChanged("nb1", "p2"), lastChange())
        assertEquals("p2", store.notebooks.getValue("nb1").openPageId)

        val q = edits.createQuickPage("f1", "blank")!!
        val (kind, change) = lastChange()
        assertEquals("create", kind)
        assertEquals(q, (change as LocalChange.PageUpserted).page.id)
        assertEquals("f1", store.pages.getValue(q).parentFolderId)
    }

    @Test
    fun `after the open page is deleted the editor shows its neighbour, or leaves`() {
        assertEquals("p3", pageAfterDeletion(listOf("p1", "p3"), formerIndex = 1))
        assertEquals("p1", pageAfterDeletion(listOf("p1"), formerIndex = 1))
        assertEquals("p1", pageAfterDeletion(listOf("p1", "p2"), formerIndex = 0))
        assertNull(pageAfterDeletion(emptyList(), formerIndex = 0))
        assertNull("notebook gone", pageAfterDeletion(null, formerIndex = 0))
    }
}
