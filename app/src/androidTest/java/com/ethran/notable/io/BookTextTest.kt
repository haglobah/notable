package com.ethran.notable.io

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.data.model.BookMeta
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * MuPDF against web/schema/fixtures/book-meta/expected.json (packaged as androidTest assets): page
 * counts at the Boox EPUB layout, the flattened outline, search hits per page, and PDF metadata.
 * The TS test of web/reader/src/bookText.ts checks the same file.
 */
@RunWith(AndroidJUnit4::class)
class BookTextTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val expected = JSONObject(assets.open("expected.json").bufferedReader().readText()).getJSONObject("files")

    /** The asset as a file: MuPDF and the import read paths. Named as in the fixture (no layout: Boox). */
    private fun file(name: String): File = File(context.cacheDir, "book-meta/$name").apply {
        parentFile!!.mkdirs()
        assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
    }

    private fun names() = expected.keys().asSequence().toList()

    @Test
    fun page_counts_and_outlines_match() {
        for (name in names()) {
            val want = expected.getJSONObject(name)
            BookText.withBook(file(name).path) { doc ->
                assertEquals("$name pages", want.getInt("pages"), doc.countPages())
                val outline = want.getJSONArray("outline").let { a ->
                    (0 until a.length()).map { a.getJSONObject(it).let { o -> OutlineEntry(o.getString("title"), o.getInt("page"), o.getInt("depth")) } }
                }
                assertEquals("$name outline", outline, BookText.outlineOf(doc))
            }
        }
    }

    @Test
    fun search_hits_the_expected_pages_with_snippets_and_quads() {
        for (name in names()) {
            val search = expected.getJSONObject(name).optJSONObject("search") ?: continue
            val query = search.getString("query")
            val pages = search.getJSONArray("pages").let { a -> (0 until a.length()).map { a.getInt(it) } }
            val progress = mutableListOf<Int>()
            val hits = BookText.withBook(file(name).path) { BookText.searchDoc(it, query.uppercase(), onPage = { done, _ -> progress += done }) }
            assertEquals("$name hits", pages, hits.map { it.page })
            assertTrue(hits.all { h -> h.quads.isNotEmpty() && h.quads.all { it.size == 8 } })
            assertTrue("$name snippets: ${hits.map { it.snippet }}", hits.all { it.snippet.lowercase().contains(query) })
            assertEquals(progress.size, progress.last())
            // Ignore-diacritics: an accented query finds the plain text.
            val accented = query.replaceFirst('e', 'é')
            val again = BookText.withBook(file(name).path) { BookText.searchDoc(it, accented) }
            assertEquals("$name hits for $accented", pages, again.map { it.page })
        }
    }

    @Test
    fun pdf_metadata_matches() {
        for (name in names().filter { it.endsWith(".pdf") }) {
            val want = expected.getJSONObject(name)
            val got = BookText.pdfMeta(file(name).path)
            assertEquals("$name title", if (want.isNull("title")) null else want.getString("title"), got.title)
            assertEquals("$name meta", if (want.isNull("meta")) null else meta(want.getJSONObject("meta")), got.meta)
        }
    }

    @Test
    fun epub_metadata_reads_on_the_device_too() {
        for (name in names().filter { it.endsWith(".epub") }) {
            val want = expected.getJSONObject(name)
            val got = BookMetaReader.epubMeta(file(name))
            assertEquals("$name meta", if (want.isNull("meta")) null else meta(want.getJSONObject("meta")), got.meta)
        }
    }

    private fun meta(o: JSONObject): BookMeta {
        fun s(k: String) = if (o.isNull(k)) null else o.getString(k)
        fun list(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
        return BookMeta(
            authors = list(o.getJSONArray("authors")), series = s("series"),
            seriesIndex = if (o.isNull("seriesIndex")) null else o.getDouble("seriesIndex"),
            description = s("description"), publisher = s("publisher"), language = s("language"),
            isbn = s("isbn"), published = s("published"), subjects = list(o.getJSONArray("subjects")),
        )
    }
}
