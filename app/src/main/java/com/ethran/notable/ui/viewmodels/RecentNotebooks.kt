package com.ethran.notable.ui.viewmodels

import com.ethran.notable.data.db.Notebook

/** The library bar's "Recent" menu: the notebooks the editor opened last, from `readAt`. */
object RecentNotebooks {
    const val LIMIT = 10

    /**
     * Opened notebooks, most recently opened first, at most [limit]; ties by title. Notebooks
     * without pages can't be opened, so they are left out.
     */
    fun of(books: List<Notebook>, limit: Int = LIMIT): List<Notebook> = books
        .filter { it.readAt != null && it.pageIds.isNotEmpty() }
        .sortedWith(compareByDescending<Notebook> { it.readAt!!.time }.thenBy { it.title })
        .take(limit)

    /** The page to open [nb] at: where it was left, else its first page. */
    fun pageOf(nb: Notebook): String = nb.openPageId?.takeIf { it in nb.pageIds } ?: nb.pageIds.first()
}
