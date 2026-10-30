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

/** The chosen sort; [isDescending] resolves the key's default. */
data class LibrarySort(val key: LibrarySearch.SortKey? = null, val descending: Boolean? = null) {
    val isDescending: Boolean get() = descending ?: key?.defaultDescending ?: false
}

/**
 * The library view's search side: [results] when there is a query, else [sortedBooks] when a sort
 * is chosen (null: the folder's usual order). [facets] fill the filter bar's menus.
 */
data class LibraryBrowse(
    val results: LibrarySearchResults? = null,
    val sortedBooks: List<Notebook>? = null,
    val facets: LibrarySearch.Facets? = null,
    val sort: LibrarySort = LibrarySort(),
    val everywhere: Boolean = false,
    val inFolder: Boolean = false,
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

    @OptIn(FlowPreview::class)
    val browse: StateFlow<LibraryBrowse> = combine(
        _query.debounce(300), _folderId, _sort, _everywhere, _dbDataFlow,
    ) { q, folderId, sort, everywhere, _ -> BrowseInput(q, folderId, sort, everywhere) }
        .mapLatest { input -> withContext(Dispatchers.IO) { browseOf(input) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryBrowse())

    private data class BrowseInput(val query: String, val folderId: String?, val sort: LibrarySort, val everywhere: Boolean)

    private suspend fun browseOf(input: BrowseInput): LibraryBrowse {
        val folders = folderRepository.getAll()
        val books = bookRepository.getAll()
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
            results = results, sortedBooks = sorted,
            facets = LibrarySearchIndex.facets(searchScope, folders, books, quick),
            sort = input.sort, everywhere = input.everywhere, inFolder = input.folderId != null,
        )
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    /** A filter bar value: adds it to, or removes it from, field [field] of the query text. */
    fun toggleFilter(field: String, value: String) {
        _query.value = QueryFilters.toggle(_query.value, field, value)
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
        _folderId, _isLatestVersion, _isImporting, _breadcrumbFolders, _dbDataFlow
    ) { folderId, isLatestVersion, isImporting, breadcrumbs, dbData ->
        LibraryUiState(
            folderId = folderId,
            isLatestVersion = isLatestVersion,
            isImporting = isImporting,
            breadcrumbFolders = breadcrumbs,
            folders = dbData.folders,
            books = dbData.books,
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
