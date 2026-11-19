package com.ethran.notable.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.sync.automerge.LibrarySearch
import com.ethran.notable.io.ImportEngine
import com.ethran.notable.io.ImportOptions
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.editor.utils.PreviewSaveMode
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.utils.fold
import com.ethran.notable.utils.isLatestVersion
import com.ethran.notable.data.events.AppEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Something in the library that can be selected (docs/plans/library-features.md). */
sealed interface LibraryItem {
    val id: String
    data class Book(override val id: String) : LibraryItem
    data class Shelf(override val id: String) : LibraryItem
}

/** One row of the trash: what it is, its name, and when it was trashed. */
data class TrashRow(val item: LibrarySearch.TrashItem, val title: String, val trashedAt: java.util.Date)

/** The chosen sort; [isDescending] resolves the key's default. */
data class LibrarySort(val key: LibrarySearch.SortKey? = null, val descending: Boolean? = null) {
    val isDescending: Boolean get() = descending ?: key?.defaultDescending ?: false
}

/**
 * The library view's search side: [results] when there is a query, else [sortedBooks] when a sort
 * is chosen (null: the folder's usual order). [facets] fill the filter bar's menus. [recent]: the
 * notebooks opened last, anywhere in the library, for the bar's "Recent" menu.
 */
data class LibraryBrowse(
    val results: LibrarySearchResults? = null,
    val sortedBooks: List<Notebook>? = null,
    val facets: LibrarySearch.Facets? = null,
    val sort: LibrarySort = LibrarySort(),
    val everywhere: Boolean = false,
    val inFolder: Boolean = false,
    val recent: List<Notebook> = emptyList(),
    /** The Read next list, oldest queued first. */
    val readNext: List<Notebook> = emptyList(),
    val hideFinished: Boolean = false,
)

data class LibraryUiState(
    val folderId: String? = null,
    val isLatestVersion: Boolean = true,
    val isImporting: Boolean = false,
    val breadcrumbFolders: List<Folder> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val books: List<Notebook> = emptyList(),
    val singlePages: List<Page> = emptyList(),
)

// Private data class for clean Flow combining
private data class LibraryDatabaseState(
    val folders: List<Folder> = emptyList(),
    val books: List<Notebook> = emptyList(),
    val singlePages: List<Page> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    val appRepository: AppRepository,
    private val appEventBus: AppEventBus,
    val importEngine: ImportEngine,
    val exportEngine: ExportEngine,
    private val thumbnailBackfillQueue: ThumbnailBackfillQueue,
    val pageDataManager: PageDataManager,
    private val snackDispatcher: SnackDispatcher,
    @param:ApplicationContext private val context: Context // Kept strictly for ImportEngine
) : ViewModel() {

    private val bookRepository = appRepository.bookRepository
    private val folderRepository = appRepository.folderRepository
    private val pageRepository = appRepository.pageRepository

    private val _folderId = MutableStateFlow<String?>(null)
    private val _isImporting = MutableStateFlow(false)
    private val _newlyCreatedBookId = MutableStateFlow<String?>(null)
    val newlyCreatedBookId: StateFlow<String?> = _newlyCreatedBookId
    private val _isLatestVersion = MutableStateFlow(true)
    private val _breadcrumbFolders = MutableStateFlow<List<Folder>>(emptyList())

    // 1. Convert LiveData to Flow and switch automatically when folderId changes
    private val _foldersFlow =
        _folderId.flatMapLatest { id -> folderRepository.getAllInFolder(id).asFlow() }
    private val _booksFlow =
        _folderId.flatMapLatest { id -> bookRepository.getAllInFolder(id).asFlow() }
    private val _singlePagesFlow =
        _folderId.flatMapLatest { id -> pageRepository.getSinglePagesInFolder(id).asFlow() }

    // 2. Group the database flows semantically
    private val _dbDataFlow = combine(
        _foldersFlow, _booksFlow, _singlePagesFlow
    ) { folders, books, pages ->
        LibraryDatabaseState(folders, books, pages)
    }

    // Library search, filters and sort (docs/plans/ebook-features.md, "Filters and sort").
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    /** In a folder, a query searches it and its subfolders; this widens it to the whole library. */
    private val _everywhere = MutableStateFlow(false)
    /** Remembered per device (AppSettings), not in the docs. Null key: the folder's usual order. */
    private val _sort = MutableStateFlow(
        LibrarySort(
            GlobalAppSettings.current.librarySort?.let { LibrarySearch.SortKey.fromKey(it) },
            GlobalAppSettings.current.librarySortDescending,
        )
    )

    @Volatile private var lastBrowse = LibraryBrowse()

    /** Per device (AppSettings): finished books stay out of the library's lists. */
    private val _hideFinished = MutableStateFlow(GlobalAppSettings.current.libraryHideFinished)

    @OptIn(FlowPreview::class)
    val browse: StateFlow<LibraryBrowse> = combine(
        combine(_query.debounce(300), _hideFinished) { q, h -> q to h }, _folderId, _sort, _everywhere, _dbDataFlow,
    ) { (q, hide), folderId, sort, everywhere, _ -> BrowseInput(q, folderId, sort, everywhere, hide) }
        .mapLatest { input ->
            withContext(Dispatchers.IO) {
                // A failed read keeps the last result rather than taking the app down.
                try {
                    browseOf(input)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    io.shipbook.shipbooksdk.ShipBook.getLogger("LibraryViewModel").e("library browse failed: ${e.message}", e)
                    lastBrowse
                }
            }.also { lastBrowse = it }
        }
        // Until the first search is done, the bar shows the saved sort and Hide finished.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryBrowse(sort = _sort.value, hideFinished = _hideFinished.value))

    private data class BrowseInput(val query: String, val folderId: String?, val sort: LibrarySort, val everywhere: Boolean, val hideFinished: Boolean)

    private suspend fun browseOf(input: BrowseInput): LibraryBrowse {
        val folders = folderRepository.getAll()
        val books = bookRepository.getAll()
        val trashed = LibrarySearchIndex.trashedNotebookIds(folders, books)
        // An explicit `is:` filter asks for states by name, finished included: it wins over hiding.
        val hide = input.hideFinished && QueryFilters.selected(input.query, "is").isEmpty()
        fun List<Notebook>.shown() = if (hide) filter { it.finishedAt == null } else this
        val quick = pageRepository.getAllQuickPages()
        // At the root a query covers the library; in a folder, the folder and its subfolders.
        val searchScope = if (input.folderId == null || input.everywhere) LibrarySearch.Scope.All
        else LibrarySearch.Scope.In(input.folderId, subfolders = true)
        val key = input.sort.key
        val results = if (input.query.isBlank()) null else LibrarySearchIndex.query(
            LibrarySearch.Query(input.query, key ?: LibrarySearch.SortKey.RELEVANCE, input.sort.descending, searchScope),
            folders, books, quick,
        )
        // No query: the folder's own notebooks, in the chosen order (none chosen: as before).
        val sorted = if (results != null || key == null) null else LibrarySearchIndex.query(
            LibrarySearch.Query("", key, input.sort.descending, LibrarySearch.Scope.In(input.folderId)),
            folders, books, quick,
        ).notebooks
        return LibraryBrowse(
            results = results?.let { r -> r.copy(notebooks = r.notebooks.shown()) }, sortedBooks = sorted?.shown(),
            facets = LibrarySearchIndex.facets(searchScope, folders, books, quick),
            sort = input.sort, everywhere = input.everywhere, inFolder = input.folderId != null,
            recent = RecentNotebooks.of(books.filter { it.id !in trashed }),
            readNext = LibrarySearchIndex.readNext(folders, books).filter { it.pageIds.isNotEmpty() },
            hideFinished = input.hideFinished,
        )
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    /** A filter bar value: adds it to, or removes it from, field [field] of the query text. */
    fun toggleFilter(field: String, value: String) {
        _query.value = QueryFilters.toggle(_query.value, field, value)
    }

    fun setHideFinished(on: Boolean) {
        _hideFinished.value = on
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.kvProxy.setAppSettings(GlobalAppSettings.current.copy(libraryHideFinished = on))
        }
    }

    /* ---------------- selection and bulk actions (docs/plans/library-features.md) ---------------- */

    private val _selection = MutableStateFlow<Set<LibraryItem>>(emptySet())
    /** Empty: not selecting. A long press starts it; then taps add and remove. */
    val selection: StateFlow<Set<LibraryItem>> = _selection

    fun toggleSelected(item: LibraryItem) {
        _selection.value = _selection.value.let { if (item in it) it - item else it + item }
    }

    fun selectAll(items: Collection<LibraryItem>) {
        _selection.value = _selection.value + items
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /** Runs [action] on each selected item off the main thread, ends the selection, and says how it went. */
    private fun bulk(done: (Int) -> String, undo: (suspend (Set<LibraryItem>) -> Unit)? = null,
                     action: suspend (LibraryItem) -> Boolean) {
        val items = _selection.value
        if (items.isEmpty()) return
        _selection.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            var n = 0
            var failure: String? = null
            for (item in items) {
                try {
                    if (action(item)) n++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: e.javaClass.simpleName
                }
            }
            val text = done(n) + (failure?.let { " (failed: $it)" } ?: "")
            val id = java.util.UUID.randomUUID().toString()
            // Undo replaces the snack with its outcome, so it can't be tapped twice.
            val actions = if (undo != null && n > 0) listOf("Undo" to {
                viewModelScope.launch(Dispatchers.IO) {
                    val result = runCatching { undo(items) }.exceptionOrNull()
                    snackDispatcher.showOrUpdateSnack(SnackConf(id = id, duration = 3000,
                        text = result?.let { "Could not undo: ${it.message}" } ?: "Restored ${items(items.size)}"))
                }
                Unit
            }) else null
            snackDispatcher.showOrUpdateSnack(SnackConf(id = id, text = text, duration = if (actions != null) 10_000 else 3000, actions = actions))
        }
    }

    private fun items(n: Int) = if (n == 1) "1 item" else "$n items"
    private fun books(n: Int) = if (n == 1) "1 book" else "$n books"

    /** Moves the selection into [folderId] (null: the root). A folder can't go into itself. */
    fun moveSelected(folderId: String?) = bulk({ "Moved ${items(it)}" }) { item ->
        when (item) {
            is LibraryItem.Book -> { appRepository.edits.moveNotebook(item.id, folderId); true }
            is LibraryItem.Shelf -> appRepository.edits.moveFolder(item.id, folderId)
        }
    }

    /** "Mark as read" ([finished]) or "Mark as unread" for the selected notebooks; folders are skipped. */
    fun setSelectedFinished(finished: Boolean) =
        bulk({ if (finished) "Marked ${books(it)} as read" else "Marked ${books(it)} as unread" }) { item ->
            if (item !is LibraryItem.Book) return@bulk false
            appRepository.edits.setNotebookFinished(item.id, finished); true
        }

    /** Puts the selected notebooks on the Read next list, or takes them off. */
    fun setSelectedQueued(queued: Boolean) =
        bulk({ if (queued) "Added ${books(it)} to Read next" else "Took ${books(it)} off Read next" }) { item ->
            if (item !is LibraryItem.Book) return@bulk false
            appRepository.edits.setNotebookQueued(item.id, queued); true
        }

    /** To the trash, with an Undo. */
    fun trashSelected() = bulk(
        done = { "Moved ${items(it)} to the trash" },
        // Folders first: a book restored while its folder is still in the trash would go to the root.
        undo = { items -> items.sortedBy { if (it is LibraryItem.Shelf) 0 else 1 }.forEach { restore(it) } },
    ) { item ->
        when (item) {
            is LibraryItem.Book -> appRepository.edits.trashNotebook(item.id)
            is LibraryItem.Shelf -> appRepository.edits.trashFolder(item.id)
        }
        true
    }

    private suspend fun restore(item: LibraryItem) = when (item) {
        is LibraryItem.Book -> appRepository.edits.restoreNotebook(item.id)
        is LibraryItem.Shelf -> appRepository.edits.restoreFolder(item.id)
    }

    /* ------------------------------ the trash ------------------------------ */

    private val _trash = MutableStateFlow<List<TrashRow>>(emptyList())
    /** Filled by [loadTrash] when the trash opens. */
    val trash: StateFlow<List<TrashRow>> = _trash

    fun loadTrash() {
        viewModelScope.launch(Dispatchers.IO) { _trash.value = trashRows() }
    }

    private fun trashRows(): List<TrashRow> {
        val lib = LibrarySearchIndex.library(folderRepository.getAll(), bookRepository.getAll(), emptyList())
        return LibrarySearch.trashOf(lib).map { t ->
            val title = when (t) {
                is LibrarySearch.TrashItem.Folder -> t.folder.title
                is LibrarySearch.TrashItem.Notebook -> t.entry.title
            }
            TrashRow(t, title, t.trashedAt)
        }
    }

    private fun trashEdit(what: String, edit: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                edit()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(SnackConf(text = "Could not $what: ${e.message}", duration = 4000))
            }
            _trash.value = trashRows()
        }
    }

    fun restoreFromTrash(row: TrashRow) = trashEdit("restore ${row.title}") {
        when (val t = row.item) {
            is LibrarySearch.TrashItem.Folder -> appRepository.edits.restoreFolder(t.id)
            is LibrarySearch.TrashItem.Notebook -> appRepository.edits.restoreNotebook(t.id)
        }
    }

    fun deleteForever(row: TrashRow) = trashEdit("delete ${row.title}") { appRepository.edits.deleteTrashItem(row.item) }

    fun emptyTrash() = trashEdit("empty the trash") {
        trashRows().forEach { appRepository.edits.deleteTrashItem(it.item) }
    }

    fun setEverywhere(on: Boolean) {
        _everywhere.value = on
    }

    /** [key] null: the usual order. [descending] null: the key's default direction. */
    fun setSort(key: LibrarySearch.SortKey?, descending: Boolean?) {
        val next = LibrarySort(key, descending)
        _sort.value = next
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.kvProxy.setAppSettings(
                GlobalAppSettings.current.copy(librarySort = key?.key, librarySortDescending = descending)
            )
        }
    }

    // 3. Expose the final UI State
    val uiState: StateFlow<LibraryUiState> = combine(
        combine(_folderId, _hideFinished) { f, h -> f to h }, _isLatestVersion, _isImporting, _breadcrumbFolders, _dbDataFlow
    ) { (folderId, hideFinished), isLatestVersion, isImporting, breadcrumbs, dbData ->
        LibraryUiState(
            folderId = folderId,
            isLatestVersion = isLatestVersion,
            isImporting = isImporting,
            breadcrumbFolders = breadcrumbs,
            // The trash (docs/plans/library-features.md): what's in it stays out of the folder views.
            folders = dbData.folders.filter { it.trashedAt == null },
            books = dbData.books.filter { it.trashedAt == null && !(hideFinished && it.finishedAt != null) },
            singlePages = dbData.singlePages,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )


    init {
        // Run network/heavy checks in the background
        viewModelScope.launch(Dispatchers.IO) {
            _isLatestVersion.value = isLatestVersion(context, appEventBus, true)
        }
        // Entries written before pageCount existed, or behind the notebook (docs/plans/ebook-features.md).
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Old quick pages become pages of the quick notebook (round 5), before anything else.
                val moved = appRepository.edits.migrateQuickPages()
                if (moved > 0) {
                    io.shipbook.shipbooksdk.ShipBook.getLogger("LibraryViewModel").i("moved $moved quick pages into the quick notebook")
                    snackDispatcher.showOrUpdateSnack(SnackConf(text = "Quick pages moved into the notebook \"Quick pages\"", duration = 3000))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                io.shipbook.shipbooksdk.ShipBook.getLogger("LibraryViewModel").e("quick page migration failed: ${e.message}", e)
                snackDispatcher.showOrUpdateSnack(SnackConf(text = "Could not move the quick pages: ${e.message}", duration = 4000))
            }
            refreshQuickNotebook()
            try {
                // The trash empties itself after 30 days (docs/plans/library-features.md).
                val gone = appRepository.edits.emptyExpiredTrash()
                if (gone > 0) io.shipbook.shipbooksdk.ShipBook.getLogger("LibraryViewModel").i("deleted $gone trash items older than 30 days")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                io.shipbook.shipbooksdk.ShipBook.getLogger("LibraryViewModel").e("emptying the trash failed: ${e.message}", e)
            }
            try {
                val n = appRepository.edits.backfillPageCounts()
                if (n > 0) io.shipbook.shipbooksdk.ShipBook.getLogger("LibraryViewModel").i("synced the page count of $n notebooks")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                io.shipbook.shipbooksdk.ShipBook.getLogger("LibraryViewModel").e("page count backfill failed: ${e.message}", e)
            }
        }
    }

    fun onPreviewRequested(pageId: String) {
        thumbnailBackfillQueue.enqueue(listOf(pageId))
    }

    fun loadFolder(folderId: String?) {
        pageDataManager.cancelLoadingPages()
        _selection.value = emptySet()
        _folderId.value = folderId

        // Resolve breadcrumbs in background thread
        viewModelScope.launch(Dispatchers.IO) {
            _breadcrumbFolders.value = resolveBreadcrumbs(folderId)
        }
    }

    private suspend fun resolveBreadcrumbs(folderId: String?): List<Folder> {
        if (folderId == null) return emptyList()

        val list = mutableListOf<Folder>()
        var currentId: String? = folderId

        while (currentId != null) {
            val folder = folderRepository.get(currentId)
            if (folder != null) {
                list.add(folder)
                currentId = folder.parentFolderId
            } else {
                currentId = null
            }
        }
        return list.reversed()
    }

    /** The quick notebook's id, shown first in its folder (round 5). */
    private val _quickNotebookId = MutableStateFlow<String?>(null)
    val quickNotebookId: StateFlow<String?> = _quickNotebookId

    private suspend fun refreshQuickNotebook() {
        _quickNotebookId.value = runCatching { appRepository.edits.quickNotebook()?.id }.getOrNull()
    }

    /** "+ Quick page": a new last page of the quick notebook (made if needed), then [onOpen] on it. */
    fun openQuickPage(onOpen: (notebookId: String, pageId: String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val at = appRepository.edits.openQuickPage(GlobalAppSettings.current.defaultNativeTemplate)
                    ?: throw IllegalStateException("the quick notebook is gone")
                _quickNotebookId.value = at.notebookId
                withContext(Dispatchers.Main) { onOpen(at.notebookId, at.pageId) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(SnackConf(text = "Could not add a quick page: ${e.message}", duration = 4000))
            }
        }
    }

    fun createNewFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.edits.createFolder(_folderId.value)
        }
    }

    fun deleteEmptyBook(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.edits.deleteNotebook(bookId)
        }
    }

    fun onCreateNewNotebook() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            val notebook = Notebook(
                parentFolderId = _folderId.value,
                defaultBackground = settings.defaultNativeTemplate,
                defaultBackgroundType = BackgroundType.Native.key
            )
            appRepository.edits.createNotebook(notebook)
            _newlyCreatedBookId.value = notebook.id
        }
    }

    fun clearNewlyCreatedBookId() {
        _newlyCreatedBookId.value = null
    }

    fun onPdfFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            // PDFs are linked, EPUBs copied; both come through here.
            val snackText = "Importing document"

            _isImporting.value = true
            snackDispatcher.showOrUpdateSnack(SnackConf(text = snackText, duration = 2000))

            try {
                // Ideally, ImportEngine should be injected via Hilt rather than instantiated here
                val result = importEngine.import(
                    uri, ImportOptions(folderId = _folderId.value, linkToExternalFile = true)
                )
                
                result.fold(
                    onSuccess = { importedPageIds ->
                        if (importedPageIds.isNotEmpty()) {
                            thumbnailBackfillQueue.enqueue(importedPageIds, PreviewSaveMode.STRICT_BW)
                        }
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import successful"))
                    },
                    onError = { error ->
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${error.userMessage}"))
                    }
                )
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${e.message}"))
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun onXoppFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            snackDispatcher.showOrUpdateSnack(
                SnackConf(
                    text = "Importing from xopp file...",
                    duration = 2000
                )
            )

            try {
                val result = importEngine.import(uri, ImportOptions(folderId = _folderId.value))
                result.fold(
                    onSuccess = { _ -> 
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "XOPP Import Successful", duration = 3000))
                    },
                    onError = { error ->
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${error.userMessage}"))
                    }
                )
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${e.message}"))
            } finally {
                _isImporting.value = false
            }
        }
    }

}
