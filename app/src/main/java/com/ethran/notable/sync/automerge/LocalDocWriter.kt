package com.ethran.notable.sync.automerge

import com.ethran.notable.data.LocalChange
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import org.automerge.ChangeHash
import org.automerge.Document
import org.automerge.ObjectId
import org.automerge.Transaction
import com.ethran.notable.data.db.decodeStrokePoints
import com.ethran.notable.data.db.encodeStrokePoints
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The docs this device edits. Repo-backed on the device, in memory in tests.
 *
 * [edit] runs [block] with exclusive access to the doc. [block] makes at most one commit, and
 * [edit] returns only once that commit is on disk, so a commit [edit] returned survives a crash.
 */
interface LocalDocs {
    fun libraryUrl(): String
    fun <T> edit(url: String, block: (Document) -> T): T
    /** Stores [doc] as a new doc; returns once all of it is on disk. */
    fun createDoc(doc: Document): String
}

/** Heads before and after a local commit; see [LocalDocWriter.settle]. */
data class WriteReceipt(val commits: List<Commit>) {
    data class Commit(val url: String, val before: Array<ChangeHash>, val after: Array<ChangeHash>)
}

/**
 * Turns [LocalChange]s into edits of the shared docs (ADR-001: the docs are the source of truth).
 *
 * - **One commit per doc per change.** Erasing 1000 strokes is one transaction.
 * - **Fields, not objects.** Notebook and page edits set single fields, so a concurrent edit of
 *   another field on another device merges instead of being overwritten.
 * - **Missing docs are created on demand.** A notebook made here after the first migration gets its
 *   doc and library entry the first time it is written, through [RoomToAutomergeMigration].
 * - **No echo.** After the caller wrote Room, [settle] advances the projector's heads past this
 *   device's own commit, so the projector doesn't apply it to Room a second time. If another
 *   change arrived in between, the heads stay put and the projector applies both (idempotent).
 *
 * Blocking. Runs on one background writer, never the main thread.
 */
class LocalDocWriter(
    private val docs: LocalDocs,
    private val local: LocalLibrary,
    blobs: BlobStore,
    migrationState: MigrationStateStore,
    private val projection: ProjectionStateStore,
    private val canvasWidth: Double,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mapping = RoomMapping { blobs.upload(it) }
    private val migration = RoomToAutomergeMigration(local, blobs, LibraryDocStore(), migrationState, canvasWidth)
    /** notebookId → doc URL. Entries never move to another doc. */
    private val docUrls = ConcurrentHashMap<String, String>()
    /** Library entry id → when its updatedAt was last bumped here; see [dueForBump]. */
    private val entryBumped = ConcurrentHashMap<String, Long>()

    fun write(change: LocalChange): WriteReceipt = when (change) {
        is LocalChange.StrokesUpserted -> if (change.strokes.isEmpty()) NONE else onPage(change.pageId) { doc, tx, _ ->
            val strokes = AutomergeSchema.strokesOf(doc, change.pageId)
            val k = toDocUnits(doc)
            change.strokes.forEach { AutomergeSchema.putStroke(tx, strokes, mapping.stroke(it).scaled(k)) }
        }
        is LocalChange.StrokesDeleted -> if (change.ids.isEmpty()) NONE else onPage(change.pageId, create = false) { doc, tx, _ ->
            val strokes = AutomergeSchema.strokesOf(doc, change.pageId)
            change.ids.filter { doc.get(strokes, it).isPresent }.forEach { tx.delete(strokes, it) }
        }
        is LocalChange.ImagesUpserted -> if (change.images.isEmpty()) NONE else {
            val mapped = change.images.map { mapping.image(it) } // hash files before touching the doc
            onPage(change.pageId) { doc, tx, _ ->
                val images = AutomergeSchema.imagesOf(doc, change.pageId)
                val k = toDocUnits(doc)
                mapped.forEach { AutomergeSchema.putImage(tx, images, it.scaled(k)) }
            }
        }
        is LocalChange.ImagesDeleted -> if (change.ids.isEmpty()) NONE else onPage(change.pageId, create = false) { doc, tx, _ ->
            val images = AutomergeSchema.imagesOf(doc, change.pageId)
            change.ids.filter { doc.get(images, it).isPresent }.forEach { tx.delete(images, it) }
        }
        is LocalChange.PageTouched -> touchPage(change.pageId, Date(change.at))
        is LocalChange.PageUpserted -> upsertPage(change.page)
        is LocalChange.PageDeleted -> deletePage(change.page)
        is LocalChange.NotebookUpserted -> upsertNotebook(change.notebook)
        is LocalChange.NotebookDeleted -> deleteEntry(change.id)
        is LocalChange.OpenPageChanged -> docUrlOf(change.notebookId)?.let { url ->
            commit(url) { doc, tx ->
                val shell = AutomergeSchema.readNotebookShell(doc)
                val open = change.pageId?.takeIf { it in shell.pages }
                if (open == shell.openPageId) return@commit false
                AutomergeSchema.setOpenPageId(tx, open)
                true
            }
        } ?: NONE
        is LocalChange.FolderUpserted -> editLibrary { doc, tx ->
            val (folders, _) = AutomergeSchema.libraryMapsOf(doc)
            AutomergeSchema.putFolder(tx, folders, mapping.folder(change.folder))
        }
        is LocalChange.FolderDeleted -> deleteFolder(change.id)
    }

    /** Copies everything in Room that has no doc yet; see [RoomToAutomergeMigration.run]. */
    fun migrateAll(onProgress: (MigrationProgress) -> Unit = {}): MigrationReport = migration.run(onProgress)

    /** Call after the Room write of [receipt]'s change returned. */
    fun settle(receipt: WriteReceipt) {
        for (c in receipt.commits) projection.compareAndSetHeads(c.url, c.before, c.after)
    }

    /* ------------------------------ pages ------------------------------ */

    /**
     * Runs [edit] on [pageId]'s doc with the page present. With [create], a page or notebook the
     * docs don't have yet is migrated first; without it (deletions), there's nothing to change.
     */
    private fun onPage(
        pageId: String, create: Boolean = true,
        edit: (Document, Transaction, ObjectId) -> Unit,
    ): WriteReceipt {
        // The row only: a pen-up must not load every stroke of the page from Room.
        val page = local.pageRow(pageId) ?: return NONE // already gone from Room: nothing to mirror
        val (url, migrated) = docFor(page, create)
        if (url == null) return migrated
        return migrated + commit(url) { doc, tx ->
            val obj = AutomergeSchema.pageObjOrNull(doc, pageId) ?: if (!create) return@commit false else {
                putFullPage(doc, tx, pageId)
                AutomergeSchema.pageObjOrNull(doc, pageId) ?: return@commit false
            }
            edit(doc, tx, obj)
            // The page's "edited" date, in the same change; once a minute is enough to sort by.
            val at = now()
            if (at - AutomergeSchema.pageUpdatedAt(doc, obj).time >= BUMP_INTERVAL_MS) AutomergeSchema.setDate(tx, obj, "updatedAt", Date(at))
            true
        } + touchOwningEntry(page)
    }

    private fun touchPage(pageId: String, at: Date): WriteReceipt {
        val page = local.pageRow(pageId) ?: return NONE
        val (url, migrated) = docFor(page, create = true)
        if (url == null) return migrated
        val r = migrated + commit(url) { doc, tx ->
            val obj = AutomergeSchema.pageObjOrNull(doc, pageId) ?: return@commit false
            // The editor touches the page after every stroke; once a minute is enough to sort by.
            val current = AutomergeSchema.readNotebookShell(doc).pages[pageId]?.updatedAt
            if (current != null && at.time - current.time < BUMP_INTERVAL_MS) return@commit false
            AutomergeSchema.setDate(tx, obj, "updatedAt", at)
            true
        }
        return r + touchOwningEntry(page)
    }

    private fun upsertPage(page: Page): WriteReceipt {
        val (url, migrated) = docFor(page, create = true)
        if (url == null) return migrated
        val bg = mapping.background(page.backgroundType, page.background) // hash outside the doc
        val r = migrated + commit(url) { doc, tx ->
            val obj = AutomergeSchema.pageObjOrNull(doc, page.id)
            if (obj == null) {
                putFullPage(doc, tx, page.id)
            } else {
                val current = AutomergeSchema.readNotebookShell(doc).pages[page.id]
                if (current?.background != bg) AutomergeSchema.setBackground(tx, obj, "background", bg)
                AutomergeSchema.setDate(tx, obj, "updatedAt", page.updatedAt)
            }
            true
        }
        // A quick page's folder lives on its library entry.
        val entry = if (page.notebookId == null) editLibrary { doc, tx ->
            val e = AutomergeSchema.entryObjOrNull(doc, page.id) ?: return@editLibrary
            val cur = AutomergeSchema.readLibrary(doc).notebooks[page.id]
            if (cur?.folderId != page.parentFolderId)
                AutomergeSchema.setEntryFields(tx, e, null, page.parentFolderId, true, Date(now()))
        } else NONE
        return r + entry
    }

    private fun deletePage(page: Page): WriteReceipt {
        if (page.notebookId == null) return deleteEntry(page.id)
        val url = docUrlOf(page.notebookId) ?: return NONE
        return commit(url) { doc, tx ->
            val pages = AutomergeSchema.pagesOf(doc)
            if (!doc.get(pages, page.id).isPresent) return@commit false
            tx.delete(pages, page.id)
            val order = AutomergeSchema.readNotebookShell(doc).pageOrder
            if (page.id in order) AutomergeSchema.setPageOrder(tx, order - page.id)
            true
        }
    }

    /** Adds [pageId] with all its Room content: strokes, images, background. */
    private fun putFullPage(doc: Document, tx: Transaction, pageId: String) {
        val data = local.page(pageId) ?: return
        AutomergeSchema.putPage(tx, AutomergeSchema.pagesOf(doc), mapping.page(data))
    }

    /* ------------------------------ notebooks and the library ------------------------------ */

    private fun upsertNotebook(nb: Notebook): WriteReceipt {
        val known = docUrlOf(nb.id)
        val url = known ?: return migrated(nb)
        val bg = mapping.background(nb.defaultBackgroundType, nb.defaultBackground) // hash outside the doc
        val doc = commit(url) { doc, tx ->
            val shell = AutomergeSchema.readNotebookShell(doc)
            // Pages Room lists but the doc lacks were made here; add them before ordering.
            val pages = AutomergeSchema.pagesOf(doc)
            for (id in nb.pageIds.distinct()) if (!doc.get(pages, id).isPresent) putFullPage(doc, tx, id)
            val order = nb.pageIds.distinct().filter { doc.get(pages, it).isPresent }
            if (order != shell.pageOrder) AutomergeSchema.setPageOrder(tx, order)
            val open = nb.openPageId?.takeIf { it in order }
            if (open != shell.openPageId) AutomergeSchema.setOpenPageId(tx, open)
            if (bg != shell.defaultBackground) AutomergeSchema.setBackground(tx, ObjectId.ROOT, "defaultBackground", bg)
            true
        }
        val entry = editLibrary { lib, tx ->
            val e = AutomergeSchema.entryObjOrNull(lib, nb.id) ?: return@editLibrary
            val cur = AutomergeSchema.readLibrary(lib).notebooks.getValue(nb.id)
            val renamed = cur.title != nb.title
            val moved = cur.folderId != nb.parentFolderId
            val bump = renamed || moved || dueForBump(nb.id)
            if (!renamed && !moved && !bump) return@editLibrary
            AutomergeSchema.setEntryFields(
                tx, e, nb.title.takeIf { renamed }, nb.parentFolderId, moved,
                if (bump) Date(now()) else null,
            )
            if (bump) entryBumped[nb.id] = now()
        }
        return doc + entry
    }

    /** Room deletes a folder's subfolders, notebooks and quick pages with it (FK cascade); so do the docs. */
    private fun deleteFolder(id: String): WriteReceipt = editLibrary { doc, tx ->
        val lib = AutomergeSchema.readLibrary(doc)
        val gone = HashSet<String>().apply { add(id) }
        do {
            val before = gone.size
            lib.folders.values.filter { it.parentId in gone }.forEach { gone += it.id }
        } while (gone.size != before)
        val (folders, notebooks) = AutomergeSchema.libraryMapsOf(doc)
        gone.filter { it in lib.folders }.forEach { tx.delete(folders, it) }
        lib.notebooks.values.filter { it.folderId in gone }.forEach {
            tx.delete(notebooks, it.id)
            docUrls.remove(it.id)
        }
    }

    private fun deleteEntry(id: String): WriteReceipt {
        docUrls.remove(id)
        return editLibrary { doc, tx ->
            val (_, notebooks) = AutomergeSchema.libraryMapsOf(doc)
            if (doc.get(notebooks, id).isPresent) tx.delete(notebooks, id)
        }
    }

    /** Bumps the library entry's updatedAt for an edit on [page], at most once a minute. */
    private fun touchOwningEntry(page: Page): WriteReceipt {
        val id = page.notebookId ?: page.id
        if (!dueForBump(id)) return NONE
        return editLibrary { doc, tx ->
            val e = AutomergeSchema.entryObjOrNull(doc, id) ?: return@editLibrary
            AutomergeSchema.setEntryFields(tx, e, null, null, false, Date(now()))
            entryBumped[id] = now()
        }
    }

    /**
     * Every edit's "edited" date matters in the library, but a library change per stroke would
     * grow the library doc with every pen-up. Once a minute per notebook is enough to sort by.
     */
    private fun dueForBump(id: String) = now() - (entryBumped[id] ?: 0L) >= BUMP_INTERVAL_MS

    /* ------------------------------ plumbing ------------------------------ */

    /**
     * The doc holding [page]; with [create], migrates its notebook or quick page if needed. The
     * receipt holds the migration's library commit: Room already has that notebook, so the
     * projector must not apply it back.
     */
    private fun docFor(page: Page, create: Boolean): Pair<String?, WriteReceipt> {
        val owner = page.notebookId ?: page.id
        docUrlOf(owner)?.let { return it to NONE }
        if (!create) return null to NONE
        return ensureEntry(owner)
    }

    private fun ensureEntry(owner: String): Pair<String?, WriteReceipt> {
        val lib = docs.libraryUrl()
        val before = docs.edit(lib) { it.heads }
        val url = migration.ensureEntry(owner) ?: return null to NONE
        docUrls[owner] = url
        val after = docs.edit(lib) { it.heads }
        val receipt = if (sameHeads(before, after)) NONE else WriteReceipt(listOf(WriteReceipt.Commit(lib, before, after)))
        return url to receipt
    }

    /** Factor from this device's canvas to the doc's stroke units (projector: the inverse). */
    private fun toDocUnits(doc: Document): Double =
        (AutomergeSchema.canvasWidthOf(doc) ?: AutomergeProjector.DEFAULT_CANVAS_WIDTH) / canvasWidth

    private fun docUrlOf(id: String): String? =
        docUrls[id] ?: docs.edit(docs.libraryUrl()) { AutomergeSchema.readLibrary(it).notebooks[id]?.docUrl }
            ?.also { docUrls[id] = it }

    /**
     * A notebook without a doc yet (its first migration failed or hasn't run): migrate it from
     * Room, then apply this update on top, since Room still holds the old row at this point.
     */
    private fun migrated(nb: Notebook): WriteReceipt {
        val (url, receipt) = ensureEntry(nb.id)
        if (url == null) return receipt
        return receipt + upsertNotebook(nb)
    }

    private fun editLibrary(edit: (Document, Transaction) -> Unit): WriteReceipt =
        commit(docs.libraryUrl()) { doc, tx -> edit(doc, tx); true }

    /** One transaction on [url]; [edit] returns false to roll back (nothing to change). */
    private fun commit(url: String, edit: (Document, Transaction) -> Boolean): WriteReceipt =
        docs.edit(url) { doc ->
            val before = doc.heads
            val tx = doc.startTransaction()
            val keep = try {
                edit(doc, tx)
            } catch (e: Throwable) {
                tx.rollback()
                throw e
            }
            if (!keep) {
                tx.rollback(); NONE
            } else if (tx.commit().isPresent) {
                WriteReceipt(listOf(WriteReceipt.Commit(url, before, doc.heads)))
            } else NONE
        }

    private operator fun WriteReceipt.plus(other: WriteReceipt) = WriteReceipt(commits + other.commits)

    /** The migration's view of the library, through [docs]. */
    private inner class LibraryDocStore : DocStore {
        override fun library() = docs.edit(docs.libraryUrl()) { AutomergeSchema.readLibrary(it) }
        /** Room already matches a doc made from it, so the projector starts at its heads. */
        override fun createDoc(doc: Document) = docs.createDoc(doc).also { projection.setHeadsIfAbsent(it, doc.heads) }
        override fun editLibrary(edit: (Document) -> Unit) = docs.edit(docs.libraryUrl(), edit)
    }

    companion object {
        val NONE = WriteReceipt(emptyList())
        const val BUMP_INTERVAL_MS = 60_000L
    }
}

/** This stroke with coordinates and size multiplied by [k]; the points are re-encoded. */
internal fun AmStroke.scaled(k: Double): AmStroke {
    if (k == 1.0) return this
    val f = k.toFloat()
    val pts = decodeStrokePoints(points).map { it.copy(x = it.x * f, y = it.y * f) }
    return copy(size = size * k, left = left * k, top = top * k, right = right * k, bottom = bottom * k, points = encodeStrokePoints(pts))
}

internal fun AmImage.scaled(k: Double): AmImage {
    if (k == 1.0) return this
    return copy(x = (x * k).roundToInt(), y = (y * k).roundToInt(), width = (width * k).roundToInt(), height = (height * k).roundToInt())
}
