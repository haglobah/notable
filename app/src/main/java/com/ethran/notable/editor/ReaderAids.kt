package com.ethran.notable.editor

import android.graphics.RectF
import com.ethran.notable.io.SearchHit

/*
 * Reading aids for books (docs/plans/library-features.md, 29, 32, 34): search hits drawn on the
 * page, the way back after a jump, and how long is left. Pure, except [SearchHighlights].
 */

/**
 * Search hits to draw on pages, as rectangles in fractions of the page (0 to 1), by notebook page
 * id. Set when a search in the book finishes, cleared by the next search or "Clear highlights".
 * The page renderer reads it on every background draw (any thread).
 */
object SearchHighlights {
    @Volatile
    private var byPage: Map<String, List<RectF>> = emptyMap()

    fun on(pageId: String?): List<RectF> = pageId?.let { byPage[it] }.orEmpty()

    /**
     * Told the pages whose highlights came or went, so their cached bitmaps are dropped and the
     * next visit draws them fresh. The editor sets it while open.
     */
    @Volatile
    var onChanged: ((Set<String>) -> Unit)? = null

    fun set(next: Map<String, List<RectF>>) {
        val changed = byPage.keys + next.keys
        byPage = next
        if (changed.isNotEmpty()) onChanged?.invoke(changed)
    }

    fun clear() = set(emptyMap())

    val isEmpty: Boolean get() = byPage.isEmpty()

    /**
     * A hit's quads as page fractions: each quad's bounding box, from the page's top left corner
     * (which need not be 0, 0), over the page size. Empty without a size.
     */
    fun fractions(hit: SearchHit): List<RectF> {
        if (hit.width <= 0f || hit.height <= 0f) return emptyList()
        return hit.quads.filter { it.size >= 8 }.map { q ->
            val xs = floatArrayOf(q[0], q[2], q[4], q[6]).map { it - hit.x0 }
            val ys = floatArrayOf(q[1], q[3], q[5], q[7]).map { it - hit.y0 }
            RectF(xs.min() / hit.width, ys.min() / hit.height, xs.max() / hit.width, ys.max() / hit.height)
        }
    }
}

/** Where a jump (contents, search hit, go to page) came from, to offer the way back. */
data class JumpBack(val fromPageId: String, val fromIndex: Int, val toPageId: String)

/**
 * The reader's pace in seconds per page, from page turns: a turn to the next or previous page
 * after [MIN_DWELL_S] to [MAX_DWELL_S] seconds counts; jumps and quick flips don't. A moving
 * average, so it follows the reader without jumping on one slow page.
 */
object ReadingPace {
    const val MIN_DWELL_S = 4f
    const val MAX_DWELL_S = 600f
    /** Weight of a new sample. */
    const val ALPHA = 0.15f
    /** Samples before an estimate is shown. */
    const val MIN_SAMPLES = 5

    data class State(val secondsPerPage: Float? = null, val samples: Int = 0) {
        val usable: Boolean get() = secondsPerPage != null && samples >= MIN_SAMPLES
    }

    /** [dwellS] seconds on a page, then a turn of [step] pages (1 or -1 counts). */
    fun record(state: State, dwellS: Float, step: Int): State {
        if (step != 1 && step != -1) return state
        if (dwellS < MIN_DWELL_S || dwellS > MAX_DWELL_S) return state
        val avg = state.secondsPerPage?.let { it + ALPHA * (dwellS - it) } ?: dwellS
        return State(avg, state.samples + 1)
    }

    /** Pages after [current] (0-based) in a book of [count] pages, the current one included. */
    fun pagesLeft(current: Int, count: Int): Int = (count - current).coerceAtLeast(0)

    /**
     * Pages left in the chapter: from [current] up to the next contents entry's page ([starts]:
     * page indexes of the entries). Null when no entry comes after it.
     */
    fun chapterPagesLeft(current: Int, starts: List<Int>): Int? =
        starts.filter { it > current }.minOrNull()?.let { it - current }

    /** Whole minutes, rounded: what the reader is shown ("4 h 05 min", "12 min"). */
    fun minutes(seconds: Float): Int = Math.round(seconds / 60f)
}
