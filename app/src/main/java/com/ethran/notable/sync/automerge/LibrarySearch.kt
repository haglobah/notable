package com.ethran.notable.sync.automerge

import java.text.Normalizer
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Library search, filters and sort (docs/plans/ebook-features.md, "Library search" and "Filters
 * and sort"). The TS twin is web/schema/src/search.ts; both run web/schema/fixtures/search-v1.json,
 * so a query finds the same entries in the same order on every device. Substrings, word starts
 * and text order are on UTF-16 strings, as in JS.
 */
object LibrarySearch {
    enum class Field {
        TITLE, AUTHORS, SERIES, SUBJECTS, FOLDER, PUBLISHER, ISBN, LANGUAGE, DESCRIPTION, PUBLISHED,
        ADDED, EDITED, READ, FINISHED,
        RATING, PAGES, PROGRESS,
        KIND, IS, HAS,
    }

    private val TEXT_FIELDS = listOf(
        Field.TITLE, Field.AUTHORS, Field.SERIES, Field.SUBJECTS, Field.FOLDER, Field.PUBLISHER, Field.ISBN,
        Field.LANGUAGE, Field.DESCRIPTION, Field.PUBLISHED,
    )

    /** [values]: one for a plain term; a field's unquoted commas give several, any of which may match. */
    data class Token(val negated: Boolean, val field: Field?, val values: List<String>)

    /** Weights for a plain term's best matching field, in tie-break order. `published` isn't scored. */
    private val WEIGHTS = listOf(
        Field.TITLE to 8, Field.AUTHORS to 6, Field.SERIES to 5, Field.SUBJECTS to 3, Field.FOLDER to 2,
        Field.PUBLISHER to 2, Field.ISBN to 2, Field.LANGUAGE to 1, Field.DESCRIPTION to 1,
    )
    private const val WORD_START_BONUS = 2

    /** Query field names; `tag` and `subject` are the same field. */
    private val FIELD_NAMES = mapOf(
        "title" to Field.TITLE, "author" to Field.AUTHORS, "series" to Field.SERIES, "tag" to Field.SUBJECTS,
        "subject" to Field.SUBJECTS, "folder" to Field.FOLDER, "publisher" to Field.PUBLISHER, "isbn" to Field.ISBN,
        "lang" to Field.LANGUAGE, "desc" to Field.DESCRIPTION, "published" to Field.PUBLISHED,
        "added" to Field.ADDED, "edited" to Field.EDITED, "read" to Field.READ, "finished" to Field.FINISHED,
        "rating" to Field.RATING, "pages" to Field.PAGES, "progress" to Field.PROGRESS,
        "kind" to Field.KIND, "is" to Field.IS, "has" to Field.HAS,
    )

    private val MARKS = Regex("\\p{M}+")

    /** NFKD, combining marks dropped, lowercase: "Übung" and "ubung" are the same. */
    fun normalize(text: String): String = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "").lowercase()

    /** JS `\s`: what the TS tokenizer splits on. */
    private fun isSpace(c: Char) = c in "\t\n\u000B\u000C\r       　﻿" || c in ' '..' '

    /** JS `[\p{L}\p{N}]` on one UTF-16 code unit (a lone surrogate is neither). */
    private fun isWordChar(c: Char) = when (Character.getType(c).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
        Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    /** Whitespace-separated tokens; a quoted span is one token, also as a field value. */
    fun parse(query: String): List<Token> {
        // Each raw token keeps where its unquoted commas are.
        val raw = mutableListOf<Pair<String, List<Int>>>()
        val cur = StringBuilder()
        var commas = mutableListOf<Int>()
        var quoted = false
        var any = false
        fun push() {
            if (cur.isNotEmpty() || any) raw += cur.toString() to commas
            cur.clear(); commas = mutableListOf(); any = false
        }
        for (ch in normalize(query)) {
            if (ch == '"') { quoted = !quoted; any = true; continue }
            if (!quoted && isSpace(ch)) { push(); continue }
            if (!quoted && ch == ',') commas += cur.length
            cur.append(ch)
        }
        push()
        return raw.mapNotNull { (text, commaAt) ->
            var t = text
            var offset = 0
            var negated = false
            if (t.startsWith("-") && t.length > 1) { negated = true; t = t.substring(1); offset = 1 }
            val colon = t.indexOf(':')
            val field = if (colon > 0) FIELD_NAMES[t.substring(0, colon)] else null
            if (field == null) return@mapNotNull if (t.isNotEmpty()) Token(negated, null, listOf(t)) else null
            val start = offset + colon + 1
            val values = mutableListOf<String>()
            var from = start
            for (c in commaAt.filter { it >= start } + text.length) {
                val v = text.substring(from, c)
                if (v.isNotEmpty()) values += v
                from = c + 1
            }
            if (values.isEmpty()) null else Token(negated, field, values)
        }
    }

    private class Indexed(
        val entry: AmNotebookEntry,
        val fields: Map<Field, List<String>>,
        val dates: Map<Field, String?>,
        /** Progress is a percentage, 0 to 100. */
        val numbers: Map<Field, Double?>,
        val kind: String,
        val state: String,
        val has: Set<String>,
    )

    /** JS `Date.toISOString()`: UTC with milliseconds. */
    private val ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private fun iso(d: Date?) = d?.let { ISO.format(it.toInstant()) }

    fun kindOf(e: AmNotebookEntry) = when {
        e.kind == AmEntryKind.QUICK_PAGE -> "quickpage"
        else -> when (e.source) {
            AmSource.None -> "notebook"
            is AmSource.Pdf -> "pdf"
            is AmSource.Epub -> "epub"
        }
    }

    /**
     * How far into it, 0 to 1: finished is 1; else the open page's place (no open page yet: 0);
     * null when the page count isn't known. Same as TS `progressOf`.
     */
    fun progressOf(e: AmNotebookEntry): Double? {
        if (e.finishedAt != null) return 1.0
        val count = e.pageCount ?: return null
        val page = e.readPage ?: return 0.0
        return if (count <= 1) 1.0 else minOf(1.0, page.toDouble() / (count - 1))
    }

    fun stateOf(e: AmNotebookEntry) = when {
        e.finishedAt != null -> "finished"
        e.readAt != null -> "reading"
        else -> "unread"
    }

    private fun index(lib: AmLibrary, e: AmNotebookEntry): Indexed {
        val folders = mutableListOf<String>()
        val seen = HashSet<String>()
        var id = e.folderId
        while (!id.isNullOrEmpty() && id !in seen) {
            val f = lib.folders[id] ?: break
            seen += id
            folders += f.title
            id = f.parentId
        }
        val m = e.meta
        fun one(v: String?) = if (v.isNullOrEmpty()) emptyList() else listOf(normalize(v))
        val has = HashSet<String>()
        if (m != null) {
            has += "meta"
            if (m.authors.isNotEmpty()) has += "author"
            if (!m.series.isNullOrEmpty()) has += "series"
            if (m.subjects.isNotEmpty()) has += "tag"
            if (!m.publisher.isNullOrEmpty()) has += "publisher"
            if (!m.language.isNullOrEmpty()) has += "lang"
            if (!m.isbn.isNullOrEmpty()) has += "isbn"
            if (!m.description.isNullOrEmpty()) has += "desc"
            if (!m.published.isNullOrEmpty()) has += "published"
        }
        if (e.rating != null) has += "rating"
        if (e.pageCount != null) has += "pages"
        val progress = progressOf(e)
        return Indexed(
            entry = e,
            fields = mapOf(
                Field.TITLE to one(e.title),
                Field.AUTHORS to m?.authors.orEmpty().map(::normalize),
                Field.SERIES to one(m?.series),
                Field.SUBJECTS to m?.subjects.orEmpty().map(::normalize),
                Field.FOLDER to folders.map(::normalize),
                Field.PUBLISHER to one(m?.publisher),
                Field.ISBN to one(m?.isbn),
                Field.LANGUAGE to one(m?.language),
                Field.DESCRIPTION to one(m?.description),
                Field.PUBLISHED to one(m?.published),
            ),
            dates = mapOf(
                Field.ADDED to iso(e.createdAt), Field.EDITED to iso(e.updatedAt),
                Field.READ to iso(e.readAt), Field.FINISHED to iso(e.finishedAt),
            ),
            numbers = mapOf(
                Field.RATING to e.rating?.toDouble(), Field.PAGES to e.pageCount?.toDouble(),
                Field.PROGRESS to progress?.let { it * 100 },
            ),
            kind = kindOf(e),
            state = stateOf(e),
            has = has,
        )
    }

    /** A match's weight in one field's values: 0 when absent, + bonus when one starts a word. */
    private fun weigh(values: List<String>, term: String, weight: Int): Int {
        var best = 0
        for (v in values) {
            var at = v.indexOf(term)
            while (at >= 0) {
                val startsWord = at == 0 || !isWordChar(v[at - 1])
                best = maxOf(best, weight + if (startsWord) WORD_START_BONUS else 0)
                if (startsWord) break
                at = v.indexOf(term, at + 1)
            }
        }
        return best
    }

    /** The plain term's score: the weight of the first field in [WEIGHTS] it matches, or 0. */
    private fun termScore(x: Indexed, term: String): Int {
        for ((f, w) in WEIGHTS) {
            val s = weigh(x.fields.getValue(f), term, w)
            if (s > 0) return s
        }
        return 0
    }

    // ASCII digits: Android's ICU `\\d` would take any Unicode digit, JS `\\d` doesn't.
    private const val NUMBER = "[0-9]+(?:\\.[0-9]+)?"
    private val COMPARE = Regex("^(>=|<=|>|<)?($NUMBER)$")
    private val RANGE = Regex("^($NUMBER)-($NUMBER)$")

    /** `8`, `>7`, `>=7`, `<200`, `<=200`, `7-9` (inclusive); anything else matches nothing. */
    private fun compares(n: Double?, v: String): Boolean {
        if (n == null) return false
        RANGE.find(v)?.let { return n >= it.groupValues[1].toDouble() && n <= it.groupValues[2].toDouble() }
        val c = COMPARE.find(v) ?: return false
        val x = c.groupValues[2].toDouble()
        return when (c.groupValues[1]) {
            ">" -> n > x
            ">=" -> n >= x
            "<" -> n < x
            "<=" -> n <= x
            else -> n == x
        }
    }

    private fun matchesOne(x: Indexed, field: Field?, v: String): Boolean = when (field) {
        null -> termScore(x, v) > 0
        Field.KIND -> x.kind == v
        Field.IS -> x.state == v
        Field.HAS -> v in x.has
        Field.ADDED, Field.EDITED, Field.READ, Field.FINISHED -> x.dates[field]?.startsWith(v) ?: false
        Field.RATING, Field.PAGES, Field.PROGRESS -> compares(x.numbers[field], v)
        else -> x.fields.getValue(field).any { it.contains(v) }
    }

    private fun matches(x: Indexed, t: Token) = t.values.any { matchesOne(x, t.field, it) }

    /** [defaultDescending]: which way each key runs unless told otherwise: names A to Z, dates newest first. */
    enum class SortKey(val key: String, val defaultDescending: Boolean) {
        RELEVANCE("relevance", true), TITLE("title", false), AUTHOR("author", false), SERIES("series", false),
        PUBLISHED("published", true), ADDED("added", true), EDITED("edited", true), READ("read", true),
        FINISHED("finished", true), LENGTH("length", false), PROGRESS("progress", true), RATING("rating", true);

        companion object {
            fun fromKey(key: String) = entries.firstOrNull { it.key == key }
        }
    }

    /** Which entries a query looks at. */
    sealed interface Scope {
        /** The whole library. */
        data object All : Scope
        /**
         * Entries in folder [id] (null: the root); with [subfolders] also in every folder below it
         * (the root with subfolders is everything that has a known folder or none).
         */
        data class In(val id: String?, val subfolders: Boolean = false) : Scope
    }

    data class Query(
        val query: String,
        val sort: SortKey = SortKey.RELEVANCE,
        /** Null: [SortKey.defaultDescending]. Relevance ignores it. */
        val descending: Boolean? = null,
        val scope: Scope = Scope.All,
    )

    private fun isDigit(c: Char) = c in '0'..'9'

    /** Runs of ASCII digits and runs of anything else. */
    private fun runs(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val d = isDigit(s[i])
            var j = i + 1
            while (j < s.length && isDigit(s[j]) == d) j++
            out += s.substring(i, j)
            i = j
        }
        return out
    }

    /** "Book 2" before "Book 10": runs of ASCII digits compare by value, the rest by UTF-16 code unit. */
    fun naturalCompare(a: String, b: String): Int {
        val xs = runs(a)
        val ys = runs(b)
        for (i in 0 until minOf(xs.size, ys.size)) {
            val x = xs[i]
            val y = ys[i]
            if (isDigit(x[0]) && isDigit(y[0])) {
                val tx = x.trimStart('0')
                val ty = y.trimStart('0')
                if (tx.length != ty.length) return tx.length - ty.length
                if (tx != ty) return if (tx < ty) -1 else 1
                if (x.length != y.length) return x.length - y.length
            } else if (x != y) return if (x < y) -1 else 1
        }
        return xs.size - ys.size
    }

    private val byId = Comparator<AmNotebookEntry> { a, b -> a.id.compareTo(b.id) }
    private val byTitle = Comparator<AmNotebookEntry> { a, b ->
        naturalCompare(normalize(a.title), normalize(b.title)).takeIf { it != 0 } ?: byId.compare(a, b)
    }

    /** A sort key's value; null sorts last in either direction. */
    private sealed interface Key {
        data class Text(val s: String) : Key
        data class Time(val t: Long) : Key
        data class Num(val n: Double) : Key
        data class Series(val name: String, val index: Double) : Key
    }

    private fun keyOf(x: Indexed, sort: SortKey): Key? {
        val e = x.entry
        return when (sort) {
            SortKey.RELEVANCE -> null
            SortKey.TITLE -> Key.Text(normalize(e.title))
            SortKey.AUTHOR -> x.fields.getValue(Field.AUTHORS).firstOrNull()?.let(Key::Text)
            SortKey.SERIES -> e.meta?.series?.takeIf { it.isNotEmpty() }
                ?.let { Key.Series(normalize(it), e.meta.seriesIndex ?: Double.POSITIVE_INFINITY) }
            SortKey.PUBLISHED -> x.fields.getValue(Field.PUBLISHED).firstOrNull()?.let(Key::Text)
            SortKey.ADDED -> Key.Time(e.createdAt.time)
            SortKey.EDITED -> Key.Time(e.updatedAt.time)
            SortKey.READ -> e.readAt?.let { Key.Time(it.time) }
            SortKey.FINISHED -> e.finishedAt?.let { Key.Time(it.time) }
            SortKey.LENGTH -> e.pageCount?.let { Key.Num(it.toDouble()) }
            SortKey.PROGRESS -> progressOf(e)?.let(Key::Num)
            SortKey.RATING -> e.rating?.let { Key.Num(it.toDouble()) }
        }
    }

    private fun compareKeys(a: Key, b: Key): Int = when {
        a is Key.Time && b is Key.Time -> a.t.compareTo(b.t)
        a is Key.Num && b is Key.Num -> a.n.compareTo(b.n)
        a is Key.Text && b is Key.Text -> naturalCompare(a.s, b.s)
        a is Key.Series && b is Key.Series -> naturalCompare(a.name, b.name).takeIf { it != 0 } ?: a.index.compareTo(b.index)
        else -> 0
    }

    /** The folder ids [scope] stands for: its folder alone, or with every folder below it. */
    private fun folderIds(lib: AmLibrary, scope: Scope.In): Set<String?> {
        val ids = hashSetOf(scope.id)
        if (!scope.subfolders) return ids
        var grew = true
        while (grew) { // a cycle adds nothing new, so this ends
            grew = false
            for (f in lib.folders.values) if (f.id !in ids && f.parentId in ids) { ids += f.id; grew = true }
        }
        return ids
    }

    /**
     * Entries in scope matching every token, in the order asked for. An empty query matches every
     * entry in scope (browsing with a sort).
     */
    fun query(lib: AmLibrary, q: Query): List<AmNotebookEntry> {
        val tokens = parse(q.query)
        val scope = (q.scope as? Scope.In)?.let { folderIds(lib, it) }
        val rows = lib.notebooks.values.mapNotNull { e ->
            if (scope != null && e.folderId !in scope) return@mapNotNull null
            val x = index(lib, e)
            if (!tokens.all { matches(x, it) != it.negated }) return@mapNotNull null
            x to tokens.sumOf { if (!it.negated && it.field == null) termScore(x, it.values[0]) else 0 }
        }
        if (q.sort == SortKey.RELEVANCE) return rows.sortedWith(
            compareByDescending<Pair<Indexed, Int>> { it.second }
                .thenByDescending { it.first.entry.readAt?.time ?: Long.MIN_VALUE }
                .thenByDescending { it.first.entry.updatedAt.time }
                .thenBy { it.first.entry.id },
        ).map { it.first.entry }
        val sign = if (q.descending ?: q.sort.defaultDescending) -1 else 1
        return rows.map { it.first.entry to keyOf(it.first, q.sort) }.sortedWith { (ea, ka), (eb, kb) ->
            if (ka == null || kb == null) {
                if (ka == null && kb == null) byTitle.compare(ea, eb) else if (ka == null) 1 else -1
            } else (sign * compareKeys(ka, kb)).takeIf { it != 0 } ?: byTitle.compare(ea, eb)
        }.map { it.first }
    }

    /** Entries matching every token, best first. An empty query finds nothing. */
    fun search(lib: AmLibrary, query: String): List<AmNotebookEntry> =
        if (parse(query).isEmpty()) emptyList() else query(lib, Query(query))

    data class Facet(val value: String, val count: Int)

    data class Facets(
        val authors: List<Facet>, val series: List<Facet>, val tags: List<Facet>,
        val languages: List<Facet>, val kinds: List<Facet>, val states: List<Facet>,
    )

    /**
     * What the entries can be filtered by, with how many have each value: the menus of a filter
     * bar. Values keep the spelling of their first occurrence; most common first, then by name.
     */
    fun facetsOf(entries: Collection<AmNotebookEntry>): Facets {
        fun tally(pick: (AmNotebookEntry) -> List<String?>): List<Facet> {
            val m = LinkedHashMap<String, Facet>()
            for (e in entries) {
                val seen = HashSet<String>()
                for (raw in pick(e)) {
                    if (raw.isNullOrEmpty()) continue
                    val k = normalize(raw)
                    if (!seen.add(k)) continue
                    val f = m[k]
                    m[k] = if (f == null) Facet(raw, 1) else f.copy(count = f.count + 1)
                }
            }
            return m.values.sortedWith { a, b ->
                (b.count - a.count).takeIf { it != 0 } ?: naturalCompare(normalize(a.value), normalize(b.value))
            }
        }
        return Facets(
            authors = tally { it.meta?.authors.orEmpty() },
            series = tally { listOf(it.meta?.series) },
            tags = tally { it.meta?.subjects.orEmpty() },
            languages = tally { listOf(it.meta?.language) },
            kinds = tally { listOf(kindOf(it)) },
            states = tally { listOf(stateOf(it)) },
        )
    }
}
