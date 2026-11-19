package com.ethran.notable.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.ui.components.getFolderList
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.ethran.notable.ui.SnackConf
import javax.inject.Inject


data class PagesUiState(
    val bookId: String = "",
    val bookTitle: String = "",
    val pageIds: List<String> = emptyList(),
    val openPageId: String? = null,
    val folderList: List<Folder> = emptyList(),
    val isLoading: Boolean = true,
    // True once the observed notebook is gone (deleted under an open screen, or never existed).
    // The screen should stop showing pages and navigate back instead of dereferencing a null book.
    val bookMissing: Boolean = false,
    /** Selected pages (docs/plans/library-features.md, 35); empty: not selecting. */
    val selected: Set<String> = emptySet(),
    val selecting: Boolean = false,
    /** The notebook's default background, for "Add page" with the usual template. */
    val defaultBackgroundType: String = "native",
    val defaultBackground: String = "blank",
)

/** A notebook pages can be moved into, with where it is. */
data class MoveTarget(val id: String, val title: String, val path: String)

@HiltViewModel
class PagesViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val thumbnailBackfillQueue: ThumbnailBackfillQueue,
    private val snackDispatcher: com.ethran.notable.ui.SnackDispatcher,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val log = ShipBook.getLogger("PagesViewModel")

    private val _uiState = MutableStateFlow(PagesUiState())
    val uiState: StateFlow<PagesUiState> = _uiState.asStateFlow()

    fun loadBook(bookId: String) {
        viewModelScope.launch {
            appRepository.bookRepository.getByIdLive(bookId).asFlow().collect { book ->
                if (book == null) {
                    // Room re-emits on every write to the table; a null here means the row is gone.
                    // Don't dereference it (that was the NPE). Diagnose why it's null: log
                    // the id and whether the row currently exists in the DB at all.
                    val existsNow = appRepository.bookRepository.getById(bookId) != null
                    log.w("Observed notebook '$bookId' is null (existsNow=$existsNow) — treating as deleted")
                    _uiState.update { it.copy(bookId = bookId, isLoading = false, bookMissing = true) }
                    return@collect
                }
                val folderList = getFolderList(appRepository, book.parentFolderId)
                _uiState.update { it.copy(
                    bookId = bookId,
                    bookTitle = book.title,
                    pageIds = book.pageIds,
                    openPageId = book.openPageId,
                    folderList = folderList,
                    isLoading = false,
                    bookMissing = false,
                    // Pages deleted elsewhere drop out of the selection.
                    selected = it.selected.filter { id -> id in book.pageIds }.toSet(),
                    defaultBackgroundType = book.defaultBackgroundType,
                    defaultBackground = book.defaultBackground,
                ) }
            }
        }
    }

    fun reorderPage(bookId: String, pageId: String, toIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.edits.movePage(bookId, pageId, toIndex)
        }
    }

    fun deletePage(pageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.ethran.notable.data.deletePage(appRepository, pageId, context.filesDir)
        }
    }

    fun duplicatePage(pageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.duplicatePage(pageId)
        }
    }

    fun newPageInBook(bookId: String, index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.newPageInBook(bookId, index)
        }
    }

    /* ---------------- selecting pages (docs/plans/library-features.md, 35-39) ---------------- */

    fun setSelecting(on: Boolean) = _uiState.update { it.copy(selecting = on, selected = if (on) it.selected else emptySet()) }

    fun toggleSelected(pageId: String) = _uiState.update {
        it.copy(selecting = true, selected = if (pageId in it.selected) it.selected - pageId else it.selected + pageId)
    }

    fun selectAll() = _uiState.update { it.copy(selected = it.pageIds.toSet()) }

    /** The selection in the notebook's order. */
    private fun selectedInOrder(): List<String> = _uiState.value.let { s -> s.pageIds.filter { it in s.selected } }

    private fun onSelection(say: (Int) -> String, action: suspend (List<String>) -> Int) {
        val ids = selectedInOrder()
        if (ids.isEmpty()) return
        _uiState.update { it.copy(selected = emptySet(), selecting = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                say(action(ids))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e("page action failed: ${e.message}", e)
                "Failed: ${e.message}"
            }
            snackDispatcher.showOrUpdateSnack(SnackConf(text = text, duration = 3000))
        }
    }

    private fun pages(n: Int) = if (n == 1) "1 page" else "$n pages"

    fun deleteSelected() = onSelection({ "Deleted ${pages(it)}" }) { ids ->
        ids.forEach { com.ethran.notable.data.deletePage(appRepository, it, context.filesDir) }
        ids.size
    }

    /** Copies, each right after its original. */
    fun duplicateSelected() = onSelection({ "Duplicated ${pages(it)}" }) { ids ->
        ids.count { appRepository.edits.duplicatePage(it) != null }
    }

    fun moveSelectedTo(notebookId: String, title: String) = onSelection({ "Moved ${pages(it)} to $title" }) { ids ->
        appRepository.edits.movePagesTo(ids, notebookId)
    }

    /** One template for every selected page (37); [value] null keeps each page's own. */
    fun setSelectedBackground(type: String, value: String?) = onSelection({ "Changed the template of ${pages(it)}" }) { ids ->
        ids.count { appRepository.edits.setPageBackground(it, type, value) != null }
    }

    /** Selected pages moved, in order, to the start ([toStart]) or the end of the notebook. */
    fun moveSelectedWithin(bookId: String, toStart: Boolean) = onSelection({ "Moved ${pages(it)}" }) { ids ->
        val book = appRepository.bookRepository.getById(bookId) ?: return@onSelection 0
        if (toStart) ids.forEachIndexed { i, id -> appRepository.edits.movePage(bookId, id, i) }
        else ids.forEach { id -> appRepository.edits.movePage(bookId, id, book.pageIds.size - 1) }
        ids.size
    }

    /** A new page at [index] with its own template (39); [value] null: the type's default. */
    fun newPageWithBackground(bookId: String, index: Int, type: String, value: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (value == null) appRepository.newPageInBook(bookId, index)
                else appRepository.edits.addPageWithBackground(bookId, index, type, value)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(SnackConf(text = "Could not add the page: ${e.message}", duration = 4000))
            }
        }
    }

    /** Notebooks pages can go to: every other one, not in the trash, by title, with its folder path. */
    suspend fun moveTargets(): List<MoveTarget> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val folders = appRepository.folderRepository.getAll()
        val books = appRepository.bookRepository.getAll()
        val trashed = LibrarySearchIndex.trashedNotebookIds(folders, books)
        val byId = folders.associateBy { it.id }
        fun path(id: String?): String {
            val names = mutableListOf<String>()
            var at = id
            while (at != null && names.size <= byId.size) {
                val f = byId[at] ?: break
                names.add(0, f.title); at = f.parentFolderId
            }
            return names.joinToString(" / ")
        }
        books.filter { it.id != _uiState.value.bookId && it.id !in trashed }
            .sortedBy { it.title.lowercase() }
            .map { MoveTarget(it.id, it.title, path(it.parentFolderId)) }
    }

    fun generateThumbnailsForCurrentBook() {
        val pageIds = _uiState.value.pageIds
        if (pageIds.isEmpty()) return
        thumbnailBackfillQueue.enqueue(pageIds)
    }
}