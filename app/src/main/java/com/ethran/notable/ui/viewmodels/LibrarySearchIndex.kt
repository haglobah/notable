package com.ethran.notable.ui.viewmodels

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.projection.EpubFiles
import com.ethran.notable.sync.automerge.AmEntryKind
import com.ethran.notable.sync.automerge.AmFolder
import com.ethran.notable.sync.automerge.AmLibrary
import com.ethran.notable.sync.automerge.AmNotebookEntry
import com.ethran.notable.sync.automerge.AmSource
import com.ethran.notable.sync.automerge.LibrarySearch
import com.ethran.notable.sync.automerge.RoomToAutomergeMigration

/** What a library search found, best first, with each result's folder path ("Uni / Math"). */
data class LibrarySearchResults(
    val query: String,
    val notebooks: List<Notebook>,
    val quickPages: List<Page>,
    val folderPaths: Map<String, String>,
)

/**
 * Runs [LibrarySearch] (the same rules and order as the PWA) over Room's rows: Room mirrors the
 * library doc, so the rows are mapped to the doc's entries first. Doc URLs and blobs aren't
 * needed for searching and stay empty.
 */
object LibrarySearchIndex {
    fun library(folders: List<Folder>, notebooks: List<Notebook>, quickPages: List<Page>) = AmLibrary(
        folders = folders.associate { it.id to AmFolder(it.id, it.title, it.parentFolderId, it.createdAt, it.updatedAt, trashedAt = it.trashedAt) },
        notebooks = notebooks.associate {
            it.id to AmNotebookEntry(
                id = it.id, docUrl = "", title = it.title, folderId = it.parentFolderId, kind = AmEntryKind.NOTEBOOK,
                source = sourceKind(it.defaultBackgroundType, it.defaultBackground),
                createdAt = it.createdAt, updatedAt = it.updatedAt,
                meta = it.bookMeta, readAt = it.readAt, finishedAt = it.finishedAt,
                rating = it.rating, queuedAt = it.queuedAt, trashedAt = it.trashedAt,
                // Room's pages mirror the doc's pageOrder; the entry's copy may lag behind.
                pageCount = it.pageIds.size,
                readPage = it.openPageId?.let { open -> it.pageIds.indexOf(open) }?.takeIf { i -> i >= 0 },
            )
        } + quickPages.associate {
            it.id to AmNotebookEntry(
                id = it.id, docUrl = "", title = RoomToAutomergeMigration.QUICK_PAGE_TITLE, folderId = it.parentFolderId,
                kind = AmEntryKind.QUICK_PAGE, source = sourceKind(it.backgroundType, it.background),
                createdAt = it.createdAt, updatedAt = it.updatedAt, pageCount = 1, readPage = 0,
            )
        },
    )

    /** The source kind only: imports give a notebook its book as the default background. */
    private fun sourceKind(type: String, value: String): AmSource = when (BackgroundType.fromKey(type)) {
        BackgroundType.AutoPdf, is BackgroundType.Pdf ->
            if (EpubFiles.isEpub(value)) AmSource.Epub("", EpubFiles.layoutOf(value) ?: EpubFiles.BOOX_LAYOUT) else AmSource.Pdf("")
        else -> AmSource.None
    }

    /** [LibrarySearch.readNext] as Room rows (docs/plans/library-features.md). */
    fun readNext(folders: List<Folder>, notebooks: List<Notebook>): List<Notebook> {
        val byId = notebooks.associateBy { it.id }
        return LibrarySearch.readNext(library(folders, notebooks, emptyList())).mapNotNull { byId[it.id] }
    }

    /** Ids of notebooks in the trash, directly or through a folder. */
    fun trashedNotebookIds(folders: List<Folder>, notebooks: List<Notebook>): Set<String> {
        val lib = library(folders, notebooks, emptyList())
        return lib.notebooks.values.filter { LibrarySearch.isTrashed(lib, it) }.map { it.id }.toSet()
    }

    /** [LibrarySearch.search]: nothing for a query without tokens. */
    fun search(query: String, folders: List<Folder>, notebooks: List<Notebook>, quickPages: List<Page>): LibrarySearchResults =
        results(query, LibrarySearch.search(library(folders, notebooks, quickPages), query), folders, notebooks, quickPages)

    /** [LibrarySearch.query]: filters, scope and sort; an empty query is everything in scope. */
    fun query(q: LibrarySearch.Query, folders: List<Folder>, notebooks: List<Notebook>, quickPages: List<Page>): LibrarySearchResults =
        results(q.query, LibrarySearch.query(library(folders, notebooks, quickPages), q), folders, notebooks, quickPages)

    /** What the entries in [scope] can be filtered by, for the filter bar's menus. */
    fun facets(scope: LibrarySearch.Scope, folders: List<Folder>, notebooks: List<Notebook>, quickPages: List<Page>): LibrarySearch.Facets {
        val lib = library(folders, notebooks, quickPages)
        return LibrarySearch.facetsOf(LibrarySearch.query(lib, LibrarySearch.Query("", scope = scope)))
    }

    private fun results(
        query: String, found: List<AmNotebookEntry>,
        folders: List<Folder>, notebooks: List<Notebook>, quickPages: List<Page>,
    ): LibrarySearchResults {
        val books = notebooks.associateBy { it.id }
        val pages = quickPages.associateBy { it.id }
        val byId = folders.associateBy { it.id }
        fun path(id: String?): String {
            val names = mutableListOf<String>()
            var at = id
            while (at != null && names.size <= byId.size) {
                val f = byId[at] ?: break
                names.add(0, f.title)
                at = f.parentFolderId
            }
            return names.joinToString(" / ")
        }
        val hits = found.mapNotNull { e -> books[e.id] }
        val quick = found.mapNotNull { e -> pages[e.id] }
        return LibrarySearchResults(
            query = query, notebooks = hits, quickPages = quick,
            folderPaths = (hits.map { it.id to path(it.parentFolderId) } + quick.map { it.id to path(it.parentFolderId) }).toMap(),
        )
    }
}
