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
    suspend fun duplicatePage(pageId: String): String? {
        val data = pages.getWithDataById(pageId) ?: return null
        val stamp = Date(now())
        val copy = data.page.copy(id = UUID.randomUUID().toString(), scroll = 0, createdAt = stamp, updatedAt = stamp)
        val copiedStrokes = data.strokes.map { it.copy(id = UUID.randomUUID().toString(), pageId = copy.id, createdAt = stamp, updatedAt = stamp) }
        val copiedImages = data.images.map { it.copy(id = UUID.randomUUID().toString(), pageId = copy.id, createdAt = stamp, updatedAt = stamp) }
        val room: suspend () -> Unit = {
            pages.create(copy)
            if (copiedStrokes.isNotEmpty()) strokes.create(copiedStrokes)
            if (copiedImages.isNotEmpty()) images.create(copiedImages)
            copy.notebookId?.let { books.placePage(it, copy.id, pageId) }
        }
        val nb = copy.notebookId
        if (nb != null) mirror.create(LocalChange.PageAdded(nb, copy.id, pageId), room)
        else mirror.create(LocalChange.PageUpserted(copy), room)
        return copy.id
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
