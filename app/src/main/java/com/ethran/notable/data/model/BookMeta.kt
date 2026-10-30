package com.ethran.notable.data.model

import kotlinx.serialization.Serializable

/**
 * A book's metadata, read from the file at import (docs/plans/ebook-features.md). Written whole,
 * once, into the library entry's `meta`; Room keeps a JSON copy on the notebook row. The TS twin
 * is `BookMeta` in web/schema/src/model.ts.
 */
@Serializable
data class BookMeta(
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Double? = null,
    /** Plain text: tags stripped, whitespace collapsed, at most 4000 characters. */
    val description: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    /** Digits and `X` only. */
    val isbn: String? = null,
    /** As the file gives it: "2019", "2019-05-02", … */
    val published: String? = null,
    val subjects: List<String> = emptyList(),
) {
    /** True when the file gave nothing: then no `meta` is written at all. */
    val isEmpty: Boolean
        get() = authors.isEmpty() && series == null && seriesIndex == null && description == null &&
            publisher == null && language == null && isbn == null && published == null && subjects.isEmpty()
}
