package com.ethran.notable.editor

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.io.BookText
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = ShipBook.getLogger("BookReading")

/** Kv key of the reading pace: [seconds per page, samples]. Per device. */
private const val PACE_KEY = "READING_PACE"
private val PACE_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(Float.serializer())

/** One line of the contents: [pageIndex] is the notebook page's index, [pageId] where a tap goes. */
data class ContentsLine(val title: String, val depth: Int, val pageIndex: Int, val pageId: String)

/** A page with search hits. */
data class SearchLine(val pageIndex: Int, val pageId: String, val snippet: String, val hits: Int)

/** The open reader dialog. [missing]: entries or hits whose document page has no notebook page. */
sealed interface BookPanel {
    data class Contents(
        val loading: Boolean = true,
        val lines: List<ContentsLine> = emptyList(),
        val missing: Int = 0,
        val error: String? = null,
        /** The open page's index and the page count, for "Page 12 of 300" and Go to page. */
        val current: Int = -1,
        val pageCount: Int = 0,
        /** Seconds per page as read so far; null until there is enough to go on. */
        val secondsPerPage: Float? = null,
    ) : BookPanel

    data class Search(
        val query: String = "",
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        /** Null until a search finished. */
        val results: List<SearchLine>? = null,
        val missing: Int = 0,
        val error: String? = null,
    ) : BookPanel
}

/**
 * The editor's reading features for a notebook made from a PDF or EPUB
 * (docs/plans/ebook-features.md): contents, search in the book, finished state, and `readAt` once
 * per opening. MuPDF and Room run on [Dispatchers.IO], never on the main or render thread.
 */
class BookReading(
    private val appRepository: AppRepository,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    private val _panel = MutableStateFlow<BookPanel?>(null)
    val panel: StateFlow<BookPanel?> = _panel.asStateFlow()

    private var notebookId: String? = null
    private var readMarkedFor: String? = null
    private var searchJob: Job? = null

    /* ---------------- back after a jump, reading pace (docs/plans/library-features.md) ---------------- */

    private val _jumpBack = MutableStateFlow<JumpBack?>(null)
    /** Set after a jump from the contents, a search hit or Go to page; shown while on the page it landed on. */
    val jumpBack: StateFlow<JumpBack?> = _jumpBack.asStateFlow()

    /** A jump from [fromPageId] to [toPageId] is about to happen. */
    suspend fun jumped(fromPageId: String, toPageId: String) {
        if (fromPageId.isEmpty() || fromPageId == toPageId) return
        val index = orderOf().indexOf(fromPageId)
        _jumpBack.value = JumpBack(fromPageId, index, toPageId)
        lastShown = null // a jump isn't a page read
    }

    /** "Back": the page to return to, and the offer goes. */
    fun takeJumpBack(): String? = _jumpBack.value?.fromPageId.also { _jumpBack.value = null }

    fun dismissJumpBack() {
        _jumpBack.value = null
    }

    private var pace = ReadingPace.State()
    private var paceLoaded = false
    private var lastShown: Pair<String, Long>? = null

    private suspend fun loadPace() {
        if (paceLoaded) return
        paceLoaded = true
        runCatching {
            appRepository.kvProxy.get(PACE_KEY, PACE_SERIALIZER)
        }.getOrNull()?.takeIf { it.size == 2 }?.let { pace = ReadingPace.State(it[0], it[1].toInt()) }
    }

    /**
     * The editor shows [pageId] now (at [now] ms). A turn to the next or previous page after a
     * plausible time on the last one counts toward the pace; leaving the page a jump landed on ends
     * the offer to go back.
     */
    suspend fun pageShown(pageId: String, now: Long = System.currentTimeMillis()) {
        // Only on the page the jump landed on; back where it came from, there's nothing to go back to.
        _jumpBack.value?.let { if (pageId != it.toPageId) _jumpBack.value = null }
        val last = lastShown
        lastShown = pageId to now
        if (last == null || notebookId == null) return
        val order = orderOf()
        val step = order.indexOf(pageId) - order.indexOf(last.first)
        loadPace()
        val next = ReadingPace.record(pace, (now - last.second) / 1000f, step)
        if (next != pace) {
            pace = next
            runCatching {
                appRepository.kvProxy.setKv(PACE_KEY, listOf(next.secondsPerPage ?: 0f, next.samples.toFloat()),
                    PACE_SERIALIZER)
            }
        }
    }

    /** The page id at 1-based [number] of the open notebook; null when out of range. */
    suspend fun pageAt(number: Int): String? = orderOf().getOrNull(number - 1)

    /** The book file of [nb], or null when it isn't made from one. Reads Room: call off the main thread. */
    suspend fun sourceOf(nb: Notebook): String? = BookPages.sourceOf(nb, pagesOf(nb))

    /** Sets readAt the first time this editor shows notebook [id]. */
    suspend fun opened(id: String) {
        notebookId = id
        if (readMarkedFor == id) return
        readMarkedFor = id
        try {
            appRepository.edits.markNotebookRead(id)
            appRepository.edits.syncReading(id) // backfill pageCount/readPage of an older entry
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e("Could not record that $id was opened: ${e.message}", e)
        }
    }

    /** "Mark as finished" or "Mark as unread"; returns the new state, or null when it failed. */
    suspend fun setFinished(finished: Boolean): Boolean? {
        val id = notebookId ?: return null
        return try {
            appRepository.edits.setNotebookFinished(id, finished)
            finished
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e("Could not mark $id ${if (finished) "finished" else "unread"}: ${e.message}", e)
            onError("Not saved: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    /** 1 to 10, or null for Clear; false when it failed (shown). */
    suspend fun setRating(rating: Int?): Boolean {
        val id = notebookId ?: return false
        return try {
            appRepository.edits.rateNotebook(id, rating)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e("Could not rate $id: ${e.message}", e)
            onError("Not saved: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    fun close() {
        searchJob?.cancel()
        _panel.value = null
    }

    /** [currentPageId]: the page the editor shows, for where the reader is and the time left. */
    fun openContents(currentPageId: String? = null) {
        _panel.value = BookPanel.Contents()
        scope.launch(Dispatchers.IO) {
            loadPace()
            val next = try {
                val (source, map) = book() ?: throw IllegalStateException("This notebook has no book file")
                val outline = BookText.withBook(source) { BookText.outlineOf(it) }
                val order = orderOf()
                val lines = outline.mapNotNull { e ->
                    map[e.page]?.let { id -> ContentsLine(e.title, e.depth, order.indexOf(id), id) }
                }
                BookPanel.Contents(loading = false, lines = lines, missing = outline.size - lines.size,
                    current = currentPageId?.let { order.indexOf(it) } ?: -1, pageCount = order.size,
                    secondsPerPage = pace.takeIf { it.usable }?.secondsPerPage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e("Contents failed: ${e.message}", e)
                BookPanel.Contents(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
            _panel.update { if (it is BookPanel.Contents) next else it }
        }
    }

    fun openSearch() {
        if (_panel.value !is BookPanel.Search) _panel.value = BookPanel.Search()
    }

    fun search(query: String) {
        searchJob?.cancel()
        SearchHighlights.clear()
        if (query.isBlank()) { _panel.value = BookPanel.Search(query); return }
        _panel.value = BookPanel.Search(query, running = true)
        searchJob = scope.launch(Dispatchers.IO) {
            val next = try {
                val (source, map) = book() ?: throw IllegalStateException("This notebook has no book file")
                val ctx = coroutineContext
                val hits = BookText.withBook(source) { doc ->
                    BookText.searchDoc(doc, query,
                        onPage = { done, total -> _panel.update { (it as? BookPanel.Search)?.copy(done = done, total = total) ?: it } },
                        cancelled = { !ctx.isActive })
                }
                if (!ctx.isActive) return@launch
                val order = orderOf()
                val lines = hits.mapNotNull { h ->
                    map[h.page]?.let { id -> SearchLine(order.indexOf(id), id, h.snippet, h.quads.size) }
                }
                // Drawn on the pages until the next search or "Clear highlights" (29).
                SearchHighlights.set(hits.mapNotNull { h -> map[h.page]?.let { id -> id to SearchHighlights.fractions(h) } }
                    .filter { it.second.isNotEmpty() }.toMap())
                val progress = _panel.value as? BookPanel.Search
                BookPanel.Search(query, done = progress?.done ?: 0, total = progress?.total ?: 0, results = lines, missing = hits.size - lines.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e("Search failed: ${e.message}", e)
                BookPanel.Search(query, error = e.message ?: e.javaClass.simpleName)
            }
            _panel.update { if (it is BookPanel.Search && it.query == query) next else it }
        }
    }

    /* ------------------------------ Room ------------------------------ */

    private suspend fun notebook(): Notebook? = notebookId?.let { appRepository.bookRepository.getById(it) }

    private suspend fun orderOf(): List<String> = notebook()?.pageIds.orEmpty()

    private suspend fun pagesOf(nb: Notebook): List<Page> = withContext(Dispatchers.IO) {
        nb.pageIds.chunked(500).flatMap { appRepository.pageRepository.getByIds(it) }
    }

    /** The book file and document page → notebook page, as Room has them now. */
    private suspend fun book(): Pair<String, Map<Int, String>>? {
        val nb = notebook() ?: return null
        val pages = pagesOf(nb)
        val source = BookPages.sourceOf(nb, pages) ?: return null
        return source to BookPages.notebookPages(nb.pageIds, pages.associateBy { it.id }, source)
    }
}
