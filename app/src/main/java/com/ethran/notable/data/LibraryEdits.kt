package com.ethran.notable.data

import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.data.db.newPage
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.sync.automerge.LibrarySearch
import com.ethran.notable.ui.viewmodels.LibrarySearchIndex
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every change to folders, notebooks and pages that the user makes (ADR-001: the Automerge docs
 * are the source of truth). Each one goes through [ContentMirror], on the same lock, outbox and
 * echo suppression as the editor's strokes:
 *
 * - **Edits and deletions** commit to the doc first, durably, then write Room.
 * - **Creations** (notebooks, pages, imports) write Room first, because the doc is built from the
 *   new rows; the change is recorded for a retry before that, so a crash leaves no Room-only row.
 * - **One field per edit.** A rename sets the title, a move the folder, a reorder places one page
 *   after another. The Room step re-reads the row under the mirror's lock, so neither a stale
 *   screen nor a projection that landed meanwhile is overwritten.
 * - **Deleted pages are announced** on [RemoteEdits], so an editor showing one leaves it.
 *
 * Suspends on Room and on the doc's disk write: never call from the main thread.
 */
@Singleton
class LibraryEdits @Inject constructor(
    private val books: BookRepository,
    private val pages: PageRepository,
    private val folders: FolderRepository,
    private val strokes: StrokeRepository,
    private val images: ImageRepository,
    private val mirror: ContentMirror,
) {
    companion object {
        /** The quick notebook's title until renamed. */
        const val QUICK_NOTEBOOK_TITLE = "Quick pages"
    }

    internal var now: () -> Long = System::currentTimeMillis
    internal var publish: (List<RemotePageEdit>) -> Boolean = RemoteEdits::publish

    /* ------------------------------ folders ------------------------------ */

    suspend fun createFolder(parentId: String?): Folder {
        val folder = Folder(parentFolderId = parentId)
        mirror.write(LocalChange.FolderUpserted(folder)) { folders.create(folder) }
        return folder
    }

    suspend fun renameFolder(id: String, title: String) {
        val at = now()
        mirror.write(LocalChange.FolderRenamed(id, title, at)) { folders.setTitle(id, title, Date(at)) }
    }

    /** False, and nothing written, when [parentId] is [id] itself or inside it. */
    suspend fun moveFolder(id: String, parentId: String?): Boolean {
        var cursor = parentId
        while (cursor != null) {
            if (cursor == id) return false
            cursor = folders.getParent(cursor)
        }
        val at = now()
        mirror.write(LocalChange.FolderMoved(id, parentId, at)) { folders.setParent(id, parentId, Date(at)) }
        return true
    }

    /** With everything in it: Room cascades, and the docs delete the same entries. */
    suspend fun deleteFolder(id: String) {
        mirror.write(LocalChange.FolderDeleted(id)) { folders.delete(id) }
    }

    /* ------------------------------ notebooks ------------------------------ */

    /** A new notebook with one empty page. */
    suspend fun createNotebook(notebook: Notebook) {
        mirror.create(LocalChange.NotebookCreated(notebook.id)) { books.create(notebook) }
    }

    suspend fun renameNotebook(id: String, title: String) {
        mirror.write(LocalChange.NotebookRenamed(id, title)) { books.setTitle(id, title) }
    }

    suspend fun moveNotebook(id: String, folderId: String?) {
        mirror.write(LocalChange.NotebookMoved(id, folderId)) { books.setParentFolder(id, folderId) }
    }

    suspend fun setNotebookBackground(id: String, type: String, value: String) {
        mirror.write(LocalChange.NotebookBackgroundChanged(id, type, value)) { books.setDefaultBackground(id, type, value) }
    }

    /** The file this device exports the notebook to. Device-local: Room only, not in the docs. */
    suspend fun setNotebookLinkedUri(id: String, uri: String?) = books.setLinkedExternalUri(id, uri)

    /** The editor opened notebook [id] (docs/plans/ebook-features.md: once per opening). */
    suspend fun markNotebookRead(id: String) {
        val at = now()
        mirror.write(LocalChange.NotebookRead(id, at)) { books.setReadAt(id, Date(at)) }
    }

    /** "Mark as finished" ([finished]) or "Mark as unread". */
    suspend fun setNotebookFinished(id: String, finished: Boolean) {
        val at = if (finished) now() else null
        mirror.write(LocalChange.NotebookFinished(id, at)) { books.setFinishedAt(id, at?.let(::Date)) }
    }

    /** 1 to 10, or null for "Clear". */
    suspend fun rateNotebook(id: String, rating: Int?) {
        require(rating == null || rating in 1..10) { "rating must be 1 to 10, got $rating" }
        mirror.write(LocalChange.NotebookRated(id, rating)) { books.setRating(id, rating) }
    }

    /* -------------------- Read next and the trash (docs/plans/library-features.md) -------------------- */

    /** Puts notebook [id] on the Read next list ([queued]) or takes it off. */
    suspend fun setNotebookQueued(id: String, queued: Boolean) {
        val at = if (queued) now() else null
        mirror.write(LocalChange.NotebookQueued(id, at)) { books.setQueuedAt(id, at?.let(::Date)) }
    }

    suspend fun trashNotebook(id: String) {
        val at = now()
        mirror.write(LocalChange.NotebookTrashed(id, at)) { books.setTrashedAt(id, Date(at)) }
    }

    /** Folder [id] and everything in it go to the trash together. */
    suspend fun trashFolder(id: String) {
        val at = now()
        mirror.write(LocalChange.FolderTrashed(id, at)) { folders.setTrashedAt(id, Date(at)) }
    }

    /** Out of the trash; to the root when its folder is itself in the trash or gone. */
    suspend fun restoreNotebook(id: String) {
        val nb = books.getById(id) ?: return
        val folder = nb.parentFolderId
        if (folder != null && !folderLive(folder)) moveNotebook(id, null)
        mirror.write(LocalChange.NotebookTrashed(id, null)) { books.setTrashedAt(id, null) }
    }

    /** Out of the trash with everything in it; to the root when its parent is in the trash or gone. */
    suspend fun restoreFolder(id: String) {
        val parent = folders.getParent(id)
        if (parent != null && !folderLive(parent)) moveFolder(id, null)
        mirror.write(LocalChange.FolderTrashed(id, null)) { folders.setTrashedAt(id, null) }
    }

    /** Folder [id] exists and neither it nor one above it is in the trash. */
    private fun folderLive(id: String): Boolean {
        val all = folders.getAll().associateBy { it.id }
        val seen = HashSet<String>()
        var at: String? = id
        while (at != null && seen.add(at)) {
            val f = all[at] ?: return false
            if (f.trashedAt != null) return false
            at = f.parentFolderId
        }
        return true
    }

    /** Deletes for good what has been in the trash [LibrarySearch.TRASH_DAYS] days or more; returns how many items. */
    suspend fun emptyExpiredTrash(): Int {
        val lib = LibrarySearchIndex.library(folders.getAll(), books.getAll(), emptyList())
        val due = LibrarySearch.expiredTrash(lib, Date(now()))
        due.forEach { deleteTrashItem(it) }
        return due.size
    }

    /** Deletes a trash item for good. */
    suspend fun deleteTrashItem(item: LibrarySearch.TrashItem) = when (item) {
        is LibrarySearch.TrashItem.Folder -> deleteFolder(item.id)
        is LibrarySearch.TrashItem.Notebook -> deleteNotebook(item.id)
    }

    /** Brings the entry's page count and open page index up to the notebook doc's. */
    suspend fun syncReading(id: String) {
        mirror.write(LocalChange.ReadingSynced(id)) {
            books.getById(id)?.let { books.setPageCount(id, it.pageIds.size) }
        }
    }

    /**
     * Backfill (docs/plans/ebook-features.md, round 3): every notebook whose entry's page count, as
     * Room last saw it, is missing or differs from its pages. Returns how many were synced.
     */
    suspend fun backfillPageCounts(): Int {
        val due = books.getAll().filter { it.pageCount != it.pageIds.size }
        due.forEach { syncReading(it.id) }
        return due.size
    }

    suspend fun deleteNotebook(id: String) {
        val pageIds = books.getById(id)?.pageIds.orEmpty()
        mirror.write(LocalChange.NotebookDeleted(id)) { books.delete(id) }
        publish(pageIds.map { RemotePageEdit(it, pageDeleted = true) })
    }

    /** Writes an imported notebook's rows through [Import], then creates its doc from them. */
    suspend fun <T> importNotebook(notebook: Notebook, fill: suspend Import.() -> T): T =
        mirror.create(LocalChange.NotebookCreated(notebook.id)) {
            books.createEmpty(notebook)
            Import(notebook.id).fill()
        }

    /**
     * A copy of notebook [notebookId], titled "… (copy)" in the same folder, with copies of its
     * pages, strokes and images under new ids. Its reading state and external link are not
     * copied. Returns the copy's id; null when the notebook is gone.
     */
    suspend fun duplicateNotebook(notebookId: String): String? {
        val source = books.getById(notebookId) ?: return null
        val stamp = Date(now())
        val copy = Notebook(
            title = "${source.title} (copy)",
            parentFolderId = source.parentFolderId,
            defaultBackground = source.defaultBackground,
            defaultBackgroundType = source.defaultBackgroundType,
            bookMeta = source.bookMeta,
            createdAt = stamp,
            updatedAt = stamp,
        )
        var firstPageId: String? = null
        importNotebook(copy) {
            for (sourcePageId in source.pageIds) {
                val data = pages.getWithDataById(sourcePageId) ?: continue
                val pageId = UUID.randomUUID().toString()
                if (firstPageId == null) firstPageId = pageId
                page(
                    data.page.copy(id = pageId, scroll = 0, createdAt = stamp, updatedAt = stamp),
                    data.strokes.map { it.copy(id = UUID.randomUUID().toString(), pageId = pageId, createdAt = stamp, updatedAt = stamp) },
                    data.images.map { it.copy(id = UUID.randomUUID().toString(), pageId = pageId, createdAt = stamp, updatedAt = stamp) },
                )
            }
        }
        firstPageId?.let { setOpenPage(copy.id, it) }
        return copy.id
    }

    /** Room writes of one import; valid only inside [importNotebook]'s block. */
    inner class Import internal constructor(private val notebookId: String) {
        suspend fun page(page: Page, strokes: List<Stroke> = emptyList(), images: List<Image> = emptyList()) {
            pages.create(page.copy(notebookId = notebookId))
            strokes(strokes)
            images(images)
            books.addPage(notebookId, page.id)
        }

        suspend fun strokes(list: List<Stroke>) {
            if (list.isNotEmpty()) this@LibraryEdits.strokes.create(list)
        }

        suspend fun images(list: List<Image>) {
            if (list.isNotEmpty()) this@LibraryEdits.images.create(list)
        }
    }

    /* ------------------------------ pages ------------------------------ */

    /** A new page at [index] of the notebook's order; null when the notebook is gone. */
    suspend fun addPage(notebookId: String, index: Int): String? {
        val book = books.getById(notebookId) ?: return null
        val after = book.pageIds.getOrNull(index.coerceIn(0, book.pageIds.size) - 1)
        val page = book.newPage()
        mirror.create(LocalChange.PageAdded(notebookId, page.id, after)) {
            pages.create(page)
            books.placePage(notebookId, page.id, after)
        }
        return page.id
    }

    /** Moves [pageId] to [toIndex]; the doc places it after the page that is then before it. */
    suspend fun movePage(notebookId: String, pageId: String, toIndex: Int) {
        val book = books.getById(notebookId) ?: return
        if (pageId !in book.pageIds) return
        val order = book.pageIds.filter { it != pageId }.toMutableList()
        val at = toIndex.coerceIn(0, order.size)
        order.add(at, pageId)
        if (order == book.pageIds) return
        val after = order.getOrNull(at - 1)
        mirror.write(LocalChange.PageMoved(notebookId, pageId, after)) { books.placePage(notebookId, pageId, after) }
    }

    /** A copy right after the original, with copies of its strokes and images; null when it's gone. */
    suspend fun duplicatePage(pageId: String): String? = copyPage(pageId, into = null)

    /**
     * Moves pages into notebook [notebookId], at its end in the order given
     * (docs/plans/library-features.md, 36): each is copied there with new ids, then deleted where
     * it was. The copy reaches the target's doc before the original is deleted, so a crash between
     * leaves the page twice, never lost. Pages already in [notebookId] stay. Returns how many moved.
     */
    suspend fun movePagesTo(pageIds: List<String>, notebookId: String): Int {
        if (books.getById(notebookId) == null) throw IllegalStateException("Notebook no longer exists")
        var moved = 0
        for (id in pageIds) {
            val page = pages.getById(id) ?: continue
            if (page.notebookId == notebookId) continue
            copyPage(id, into = notebookId) ?: continue
            deletePage(id, page.notebookId)
            moved++
        }
        return moved
    }

    /**
     * A new page at [index] of the notebook's order with background [type]/[value] instead of the
     * notebook's (docs/plans/library-features.md, 39); null when the notebook is gone.
     */
    suspend fun addPageWithBackground(notebookId: String, index: Int, type: String, value: String): String? {
        val id = addPage(notebookId, index) ?: return null
        setPageBackground(id, type, value)
        return id
    }

    /**
     * A copy of [pageId] with copies of its strokes and images: right after it ([into] null), or at
     * the end of notebook [into]. Null when the page (or that notebook) is gone.
     */
    private suspend fun copyPage(pageId: String, into: String?): String? {
        val data = pages.getWithDataById(pageId) ?: return null
        val stamp = Date(now())
        val target = into?.let { books.getById(it) ?: return null }
        val copy = data.page.copy(id = UUID.randomUUID().toString(), scroll = 0, createdAt = stamp, updatedAt = stamp,
            notebookId = target?.id ?: data.page.notebookId, parentFolderId = if (target != null) null else data.page.parentFolderId)
            // An AutoPdf page shows the PDF page at its own index; elsewhere it keeps the one it showed.
            .let { c ->
                val from = data.page.notebookId?.let { books.getById(it) }
                val index = from?.pageIds?.indexOf(pageId) ?: -1
                if (target != null && c.backgroundType == BackgroundType.AutoPdf.key && index >= 0)
                    c.copy(backgroundType = BackgroundType.Pdf(index).key) else c
            }
        val copiedStrokes = data.strokes.map { it.copy(id = UUID.randomUUID().toString(), pageId = copy.id, createdAt = stamp, updatedAt = stamp) }
        val copiedImages = data.images.map { it.copy(id = UUID.randomUUID().toString(), pageId = copy.id, createdAt = stamp, updatedAt = stamp) }
        // Right after the original, or last in the target notebook.
        val after = if (target != null) target.pageIds.lastOrNull() else pageId
        val room: suspend () -> Unit = {
            pages.create(copy)
            if (copiedStrokes.isNotEmpty()) strokes.create(copiedStrokes)
            if (copiedImages.isNotEmpty()) images.create(copiedImages)
            copy.notebookId?.let { books.placePage(it, copy.id, after) }
        }
        val nb = copy.notebookId
        if (nb != null) mirror.create(LocalChange.PageAdded(nb, copy.id, after), room)
        else mirror.create(LocalChange.PageUpserted(copy), room)
        return copy.id
    }

    /* ------------------------------ quick pages (round 5) ------------------------------ */

    /** A notebook page to open: [notebookId] and [pageId]. */
    data class OpenAt(val notebookId: String, val pageId: String)

    /**
     * "+ Quick page" (docs/plans/ebook-features.md, round 5): a new page at the end of the quick
     * notebook, with its default background. Without a live quick notebook, one titled "Quick
     * pages" is made in the root and becomes it (one library write); its first page is the new one.
     */
    suspend fun openQuickPage(nativeTemplate: String): OpenAt? {
        val existing = quickNotebook()
        if (existing != null) {
            val pageId = addPage(existing.id, existing.pageIds.size) ?: return null
            return OpenAt(existing.id, pageId)
        }
        val nb = Notebook(title = QUICK_NOTEBOOK_TITLE, parentFolderId = null,
            defaultBackground = nativeTemplate, defaultBackgroundType = BackgroundType.Native.key)
        createNotebook(nb)
        mirror.write(LocalChange.QuickNotebookSet(nb.id)) {}
        val first = books.getById(nb.id)?.pageIds?.firstOrNull() ?: return null
        return OpenAt(nb.id, first)
    }

    /**
     * The quick notebook when the library names one Room has and it isn't in the trash (a trashed
     * one is left there; "+ Quick page" then starts a new one).
     */
    suspend fun quickNotebook(): Notebook? = mirror.quickNotebookId()?.let { books.getById(it) }
        ?.takeIf { it.trashedAt == null && (it.parentFolderId == null || folderLive(it.parentFolderId)) }

    /**
     * Old quick pages (one entry each) move into the quick notebook, oldest first, each appended
     * at the end; the notebook is made when there's none ("Quick pages", in the root). Returns how
     * many moved. Room's quick-page row becomes the notebook's page (strokes and images stay, by
     * page id); the doc copies first and deletes the old entry after ([LocalChange.QuickPageMoved]).
     */
    suspend fun migrateQuickPages(): Int {
        val old = pages.getAllQuickPages().sortedWith(compareBy<Page> { it.createdAt }.thenBy { it.id })
        if (old.isEmpty()) return 0
        val target = quickNotebook() ?: Notebook(title = QUICK_NOTEBOOK_TITLE, parentFolderId = null,
            defaultBackground = old.first().background, defaultBackgroundType = old.first().backgroundType).also { nb ->
            mirror.create(LocalChange.NotebookCreated(nb.id)) { books.createEmpty(nb) }
            mirror.write(LocalChange.QuickNotebookSet(nb.id)) {}
        }
        for (page in old) {
            mirror.write(LocalChange.QuickPageMoved(page.id, target.id)) {
                val row = pages.getById(page.id) ?: return@write
                if (row.notebookId == null) pages.update(row.copy(notebookId = target.id, parentFolderId = null))
                val nb = books.getById(target.id) ?: return@write
                if (page.id !in nb.pageIds) books.addPage(target.id, page.id)
            }
        }
        return old.size
    }

    suspend fun createQuickPage(folderId: String?, nativeTemplate: String): String {
        val page = Page(notebookId = null, background = nativeTemplate, backgroundType = BackgroundType.Native.key, parentFolderId = folderId)
        mirror.create(LocalChange.PageUpserted(page)) { pages.create(page) }
        return page.id
    }

    /**
     * Deletes a page, a quick page included. [notebookId] names the notebook when Room lacks the
     * page row but the notebook still lists it.
     */
    suspend fun deletePage(pageId: String, notebookId: String? = null) {
        val page = pages.getById(pageId) ?: notebookId?.let { Page(id = pageId, notebookId = it) } ?: return
        mirror.write(LocalChange.PageDeleted(page)) {
            page.notebookId?.let { books.removePage(it, pageId) }
            pages.delete(pageId)
        }
        publish(listOf(RemotePageEdit(pageId, pageDeleted = true)))
    }

    /**
     * Page [pageId]'s handwriting becomes [strokes] (an earlier version, docs/plans/library-features.md,
     * 40): strokes it has now and [strokes] lacks are deleted, the rest written. Images stay. An
     * editor showing the page takes the change like one from another device.
     */
    suspend fun restorePageStrokes(pageId: String, strokes: List<Stroke>) {
        val now = this.strokes.idsForPage(pageId)
        val keep = strokes.map { it.copy(pageId = pageId) }
        val gone = now - keep.map { it.id }.toSet()
        if (gone.isNotEmpty()) mirror.write(LocalChange.StrokesDeleted(pageId, gone)) { this.strokes.deleteAll(gone) }
        if (keep.isNotEmpty()) mirror.write(LocalChange.StrokesUpserted(pageId, keep)) { this.strokes.upsert(keep) }
        // An edit: the page's "edited" date moves, and with it its library preview goes stale.
        val at = now()
        mirror.write(LocalChange.PageTouched(pageId, at)) { pages.touchUpdatedAt(pageId, at) }
        publish(listOf(RemotePageEdit(pageId, upsertStrokes = keep, deleteStrokes = gone.toSet())))
    }

    /** [value] null keeps the current one (a type change only). Returns the page as written. */
    suspend fun setPageBackground(pageId: String, type: String, value: String?): Page? {
        val page = pages.getById(pageId) ?: return null
        val v = value ?: page.background
        val at = now()
        mirror.write(LocalChange.PageBackgroundChanged(pageId, page.notebookId, type, v, at)) {
            pages.setBackground(pageId, type, v, Date(at))
        }
        return page.copy(backgroundType = type, background = v, updatedAt = Date(at))
    }

    /** The page a notebook opens at. Not an edit: it doesn't change the "edited" date. */
    suspend fun setOpenPage(notebookId: String, pageId: String) {
        mirror.write(LocalChange.OpenPageChanged(notebookId, pageId)) { books.setOpenPageId(notebookId, pageId) }
    }
}

/**
 * The page an editor shows after its page was deleted: the one now at the deleted page's index,
 * or the last. Null when the notebook is gone or empty; the editor then leaves.
 */
fun pageAfterDeletion(order: List<String>?, formerIndex: Int): String? =
    order?.takeIf { it.isNotEmpty() }?.let { it[formerIndex.coerceIn(0, it.size - 1)] }
