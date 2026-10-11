package com.ethran.notable.data

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke

/**
 * One local edit, as the repositories see it. The Automerge docs are the source of truth
 * (ADR-001), so every repository write is first recorded through [ContentMirror].
 */
sealed interface LocalChange {
    data class StrokesUpserted(val pageId: String, val strokes: List<Stroke>) : LocalChange
    data class StrokesDeleted(val pageId: String, val ids: List<String>) : LocalChange
    data class ImagesUpserted(val pageId: String, val images: List<Image>) : LocalChange
    data class ImagesDeleted(val pageId: String, val ids: List<String>) : LocalChange
    data class PageTouched(val pageId: String, val at: Long) : LocalChange
    /** A page row created or changed (background, folder). */
    data class PageUpserted(val page: Page) : LocalChange
    data class PageDeleted(val page: Page) : LocalChange
    /** A notebook row created or changed (title, folder, page order, open page, background). */
    data class NotebookUpserted(val notebook: Notebook) : LocalChange
    data class NotebookDeleted(val id: String) : LocalChange
    /** The page a notebook opens at. Not an edit: it doesn't change the notebook's "edited" date. */
    data class OpenPageChanged(val notebookId: String, val pageId: String?) : LocalChange
    data class FolderUpserted(val folder: Folder) : LocalChange
    data class FolderDeleted(val id: String) : LocalChange
}

/**
 * Records [LocalChange]s in the Automerge docs, the source of truth (ADR-001).
 *
 * - [write]: edits and deletions. Commits [change] to the doc and waits until it is on disk, then
 *   runs [room]. A crash in between loses nothing: the projector applies the doc to Room on the
 *   next start.
 * - [create]: new notebooks, pages and quick pages. Runs [room] first, because the doc is built
 *   from the new Room rows, then commits. A crash in between leaves a Room-only row; the
 *   migration on the next start (or the next edit of that notebook) adds it to the docs.
 *
 * A doc write that fails is reported (see the sync settings) and the Room write still happens, so
 * no local edit is lost. Never call these from the main thread.
 */
interface ContentMirror {
    suspend fun <T> write(change: LocalChange, room: suspend () -> T): T
    suspend fun <T> create(change: LocalChange, room: suspend () -> T): T

    /** Room only. For tests and for code that must not touch the docs. */
    object None : ContentMirror {
        override suspend fun <T> write(change: LocalChange, room: suspend () -> T): T = room()
        override suspend fun <T> create(change: LocalChange, room: suspend () -> T): T = room()
    }
}
