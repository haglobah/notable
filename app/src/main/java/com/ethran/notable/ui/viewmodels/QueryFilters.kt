package com.ethran.notable.ui.viewmodels

import com.ethran.notable.sync.automerge.LibrarySearch

/**
 * Filters are query tokens (docs/plans/ebook-features.md, "Filters and sort"): the filter bar adds
 * and removes values in the query text, as the PWA does, so what is filtered stays visible and
 * editable. Several values of one field become one any-of token: `kind:pdf,epub`,
 * `author:"le guin",camus`.
 */
object QueryFilters {
    /** The query text split as the tokenizer does (whitespace outside quotes), raw. */
    private fun rawTokens(query: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        for (ch in query) {
            if (ch == '"') quoted = !quoted
            if (!quoted && ch.isWhitespace()) {
                if (cur.isNotEmpty()) out += cur.toString()
                cur.clear()
                continue
            }
            cur.append(ch)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun fieldOf(name: String) = LibrarySearch.parse("$name:x").singleOrNull()?.field
        ?: throw IllegalArgumentException("not a query field: $name")

    /** A raw token's values when it is a plain (not negated) [field] token, else null. */
    private fun valuesIf(raw: String, field: LibrarySearch.Field): List<String>? =
        LibrarySearch.parse(raw).singleOrNull()?.takeIf { !it.negated && it.field == field }?.values

    /** The normalized values the query filters [name] (`kind`, `is`, `author`, …) by, in order. */
    fun selected(query: String, name: String): List<String> {
        val field = fieldOf(name)
        return rawTokens(query).flatMap { valuesIf(it, field).orEmpty() }.distinct()
    }

    fun isSelected(query: String, name: String, value: String) = LibrarySearch.normalize(value) in selected(query, name)

    /**
     * [query] with [value] added to or removed from field [name]. The field's tokens become one,
     * at the end; everything else stays as typed.
     */
    fun toggle(query: String, name: String, value: String): String {
        val field = fieldOf(name)
        val v = LibrarySearch.normalize(value).replace("\"", "").trim()
        val values = selected(query, name).toMutableList()
        if (!values.remove(v) && v.isNotEmpty()) values += v
        val kept = rawTokens(query).filter { valuesIf(it, field) == null }
        val token = if (values.isEmpty()) null else "$name:" + values.joinToString(",") { quote(it) }
        return (kept + listOfNotNull(token)).joinToString(" ")
    }

    /** Quoted when the value holds a space, comma or colon; `"` can't be written and is dropped. */
    private fun quote(v: String): String {
        val s = v.replace("\"", "")
        return if (s.any { it.isWhitespace() || it == ',' || it == ':' }) "\"$s\"" else s
    }
}
