package com.ethran.notable.io

import com.ethran.notable.data.model.BookMeta
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Book metadata read at import (docs/plans/ebook-features.md, "Metadata extraction"): the OPF of
 * an EPUB, the info dictionary of a PDF. The TS twin is web/app/src/lib/bookMeta.ts; both are
 * tested against web/schema/fixtures/book-meta/. Pure Kotlin: the PDF side takes MuPDF's
 * `getMetaData` as a function ([FitzBookText.pdfMeta] passes it).
 */
object BookMetaReader {
    /** [title] null: use the file name. [meta] null: write no meta. */
    data class Extracted(val title: String?, val meta: BookMeta?)

    private const val MAX_DESCRIPTION = 4000

    /**
     * JS `\s` (what the TS twin's `/\s+/u` matches), spelled out: the JVM's `\s` is ASCII only, and
     * Android's ICU regex refuses the `(?U)` flag. Includes U+00A0.
     */
    internal const val SPACE = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val SPACES = Regex("[$SPACE]+")

    /** Trimmed, whitespace runs collapsed; empty → null. */
    fun clean(v: String?): String? = SPACES.replace(v ?: "", " ").trim().ifEmpty { null }

    private fun cleanList(vs: List<String?>, dedupe: Boolean = false): List<String> {
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        for (v in vs) {
            val c = clean(v) ?: continue
            val key = c.lowercase()
            if (dedupe && key in seen) continue
            seen += key
            out += c
        }
        return out
    }

    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0")

    /** `&amp; &lt; &gt; &quot; &apos; &nbsp;` and numeric references; others are left as written. */
    fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val ref = m.groupValues[1]
        if (ref[0] == '#') {
            val code = if (ref[1] == 'x' || ref[1] == 'X') ref.substring(2).toIntOrNull(16) else ref.substring(1).toIntOrNull()
            if (code != null && code in 0..0x10FFFF) StringBuilder().appendCodePoint(code).toString() else m.value
        } else NAMED[ref] ?: m.value
    }

    private val TAG = Regex("<[^>]*>")

    /** Text that may hold HTML: tags become spaces, entities are decoded, whitespace collapsed. */
    fun cleanDescription(text: String?): String? {
        if (text == null) return null
        return clean(decodeEntities(TAG.replace(text, " ")))?.take(MAX_DESCRIPTION)
    }

    private fun metaOrNull(m: BookMeta): BookMeta? = m.takeUnless { it.isEmpty }

    private val ISBN_PREFIX = Regex("^(urn:isbn:|isbn:?)", RegexOption.IGNORE_CASE)
    private val DASHES_SPACES = Regex("[-$SPACE]")
    private val ISBN = Regex("^(\\d{9}[\\dX]|\\d{12}[\\dX])$")

    /** 10 or 13 characters of digits (the last may be X) once prefixes, dashes and spaces are gone. */
    private fun isbnOf(text: String, byScheme: Boolean): String? {
        val s = DASHES_SPACES.replace(ISBN_PREFIX.replace(text.trim(), ""), "").uppercase()
        if (ISBN.matches(s)) return s
        return if (byScheme && s.isNotEmpty()) s else null
    }

    /* ------------------------------------ PDF ------------------------------------ */

    private val AUTHOR_SPLIT = Regex(";|&| and ")
    private val KEYWORD_SPLIT = Regex("[,;]")

    /** [get] is MuPDF's `Document.getMetaData`. */
    fun pdfMeta(get: (String) -> String?): Extracted {
        fun info(k: String) = clean(get("info:$k"))
        val author = info("Author")
        val keywords = info("Keywords")
        return Extracted(
            title = info("Title"),
            meta = metaOrNull(BookMeta(
                authors = cleanList(author?.split(AUTHOR_SPLIT).orEmpty()),
                description = cleanDescription(info("Subject")),
                subjects = cleanList(keywords?.split(KEYWORD_SPLIT).orEmpty(), dedupe = true),
            )),
        )
    }

    /* ------------------------------------ EPUB ------------------------------------ */

    class El(val name: String) {
        val attrs = LinkedHashMap<String, String>()
        /** [El] or [String]. */
        val children = mutableListOf<Any>()
    }

    /** Local name: "dc:title" → "title". */
    private fun local(n: String) = n.substring(n.indexOf(':') + 1)

    private val START_TAG = Regex("^([^$SPACE/>]+)([\\s\\S]*?)/?$")
    private val ATTR = Regex("([^$SPACE=]+)[$SPACE]*=[$SPACE]*(\"([^\"]*)\"|'([^']*)')")

    /**
     * A tolerant XML reader for OPF and container files: elements, attributes, text, CDATA. Names
     * are kept with their prefix; comments, processing instructions and doctypes are skipped.
     */
    fun parseXml(xml: String): El {
        val root = El("#root")
        val stack = mutableListOf(root)
        var i = 0
        fun text(t: String) { if (t.isNotEmpty()) stack.last().children += decodeEntities(t) }
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) { text(xml.substring(i)); break }
            text(xml.substring(i, lt))
            if (xml.startsWith("<!--", lt)) { i = end(xml, "-->", lt); continue }
            if (xml.startsWith("<![CDATA[", lt)) {
                val close = xml.indexOf("]]>", lt)
                stack.last().children += xml.substring(lt + 9, if (close < 0) xml.length else close)
                i = if (close < 0) xml.length else close + 3
                continue
            }
            if (xml.startsWith("<?", lt)) { i = end(xml, "?>", lt); continue }
            if (xml.startsWith("<!", lt)) { i = end(xml, ">", lt); continue }
            val gt = tagEnd(xml, lt)
            val body = xml.substring(lt + 1, gt)
            i = gt + 1
            if (body.startsWith("/")) {
                val name = body.substring(1).trim()
                // Close up to the matching element; a stray end tag is ignored.
                val at = stack.indexOfLast { it.name == name }
                if (at > 0) while (stack.size > at) stack.removeAt(stack.size - 1)
                continue
            }
            val selfClosing = body.endsWith("/")
            val m = START_TAG.find(body) ?: continue
            val el = El(m.groupValues[1])
            for (a in ATTR.findAll(m.groupValues[2])) {
                val value = a.groups[3]?.value ?: a.groups[4]?.value ?: ""
                el.attrs[a.groupValues[1]] = decodeEntities(value)
            }
            stack.last().children += el
            if (!selfClosing) stack += el
        }
        return root
    }

    private fun end(xml: String, marker: String, from: Int): Int {
        val at = xml.indexOf(marker, from)
        return if (at < 0) xml.length else at + marker.length
    }

    /** The `>` closing the tag at [lt], skipping quoted attribute values. */
    private fun tagEnd(xml: String, lt: Int): Int {
        var q: Char? = null
        for (i in lt + 1 until xml.length) {
            val c = xml[i]
            if (q != null) { if (c == q) q = null }
            else if (c == '"' || c == '\'') q = c
            else if (c == '>') return i
        }
        return xml.length
    }

    private fun elements(e: El) = e.children.filterIsInstance<El>()
    private fun descendants(e: El): List<El> = elements(e).flatMap { listOf(it) + descendants(it) }
    private fun textOf(e: El): String = e.children.joinToString("") { if (it is String) it else textOf(it as El) }
    /** An attribute by local name: "opf:scheme" and "scheme" are both "scheme". */
    private fun attr(e: El, name: String) = e.attrs.entries.firstOrNull { local(it.key) == name }?.value

    private val JS_DECIMAL = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
    private val JS_RADIX = Regex("^0([xXoObB])([0-9a-fA-F]+)$")

    /** JS `Number(v)` for the forms a series index takes; null when not a finite number. */
    private fun numberOrNull(v: String?): Double? {
        val c = clean(v) ?: return null
        JS_DECIMAL.find(c)?.let { return c.toDouble().takeIf { it.isFinite() } }
        JS_RADIX.find(c)?.let { m ->
            val radix = when (m.groupValues[1].lowercase()) { "x" -> 16; "o" -> 8; else -> 2 }
            return m.groupValues[2].toBigIntegerOrNull(radix)?.toDouble()?.takeIf { it.isFinite() }
        }
        return null
    }

    /** The title and metadata of the OPF document [opf]. */
    fun opfMeta(opf: String): Extracted {
        val metadata = descendants(parseXml(opf)).firstOrNull { local(it.name) == "metadata" }
            ?: return Extracted(null, null)
        val items = descendants(metadata)
        // Dublin Core elements by local name, whatever prefix the file binds to them.
        fun dc(name: String) = items.filter { local(it.name) == name }
        fun first(name: String) = clean(dc(name).map(::textOf).firstOrNull { clean(it) != null })
        val metas = items.filter { local(it.name) == "meta" }
        fun named(name: String) = metas.firstOrNull { attr(it, "name") == name }?.attrs?.get("content")

        var series = clean(named("calibre:series"))
        var seriesIndex = numberOrNull(named("calibre:series_index"))
        if (series == null) {
            val collection = metas.firstOrNull { attr(it, "property") == "belongs-to-collection" && clean(textOf(it)) != null }
            if (collection != null) {
                series = clean(textOf(collection))
                val id = collection.attrs["id"]
                val position = if (!id.isNullOrEmpty()) metas.firstOrNull {
                    it.attrs["refines"] == "#$id" && attr(it, "property") == "group-position"
                } else null
                if (position != null && seriesIndex == null) seriesIndex = numberOrNull(textOf(position))
            }
        }

        var isbn: String? = null
        for (e in dc("identifier")) {
            isbn = isbnOf(textOf(e), (attr(e, "scheme") ?: "").uppercase() == "ISBN")
            if (isbn != null) break
        }

        val description = dc("description").map(::textOf).firstOrNull { clean(it) != null }
        return Extracted(
            title = first("title"),
            meta = metaOrNull(BookMeta(
                authors = cleanList(dc("creator").map(::textOf)),
                series = series,
                seriesIndex = seriesIndex,
                description = cleanDescription(description),
                publisher = first("publisher"),
                language = first("language"),
                isbn = isbn,
                published = first("date"),
                subjects = cleanList(dc("subject").map(::textOf), dedupe = true),
            )),
        )
    }

    /** The title and metadata of the EPUB [file]. @throws Exception when it isn't a zip or has no OPF */
    fun epubMeta(file: File): Extracted = ZipFile(file).use { zip ->
        fun text(name: String): String {
            val entry = zip.getEntry(name) ?: throw java.io.IOException("$name is missing")
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF") // as TextDecoder does
        }
        val container = text("META-INF/container.xml")
        val rootfile = descendants(parseXml(container)).firstOrNull { local(it.name) == "rootfile" }?.attrs?.get("full-path")
        if (rootfile.isNullOrEmpty()) throw java.io.IOException("META-INF/container.xml names no rootfile")
        opfMeta(text(decodeUriComponent(rootfile)))
    }

    /** JS `decodeURIComponent`: %XX as UTF-8, `+` kept. @throws IllegalArgumentException on a bad escape */
    private fun decodeUriComponent(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder()
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                require(i + 3 <= s.length) { "bad escape in $s" }
                bytes.write(s.substring(i + 1, i + 3).toIntOrNull(16) ?: throw IllegalArgumentException("bad escape in $s"))
                i += 3
                continue
            }
            if (bytes.size() > 0) { out.append(bytes.toByteArray().toString(Charsets.UTF_8)); bytes.reset() }
            out.append(s[i]); i++
        }
        if (bytes.size() > 0) out.append(bytes.toByteArray().toString(Charsets.UTF_8))
        return out.toString()
    }
}
