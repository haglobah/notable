package com.ethran.notable.io

import com.ethran.notable.data.model.BookMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Metadata extraction against web/schema/fixtures/book-meta/expected.json, as the TS test does.
 * EPUBs are read here in full; PDFs need MuPDF, so the instrumented `BookTextTest` reads the PDF
 * files, and this test feeds [BookMetaReader.pdfMeta] the info strings the fixture PDFs hold.
 */
class BookMetaTest {
    private val dir = File("../web/schema/fixtures/book-meta")
    private val files = Json.parseToJsonElement(File(dir, "expected.json").readText()).jsonObject.getValue("files").jsonObject

    private fun expected(name: String): BookMetaReader.Extracted {
        val f = files.getValue(name).jsonObject
        val title = f.getValue("title").let { if (it is JsonNull) null else it.jsonPrimitive.content }
        val meta = f.getValue("meta").let { if (it is JsonNull) null else meta(it.jsonObject) }
        return BookMetaReader.Extracted(title, meta)
    }

    private fun meta(o: JsonObject): BookMeta {
        fun s(k: String) = o.getValue(k).let { if (it is JsonNull) null else it.jsonPrimitive.content }
        return BookMeta(
            authors = o.getValue("authors").jsonArray.map { it.jsonPrimitive.content },
            series = s("series"),
            seriesIndex = o.getValue("seriesIndex").let { if (it is JsonNull) null else it.jsonPrimitive.double },
            description = s("description"), publisher = s("publisher"), language = s("language"),
            isbn = s("isbn"), published = s("published"),
            subjects = o.getValue("subjects").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun every_fixture_epub_gives_the_expected_title_and_meta() {
        val epubs = files.keys.filter { it.endsWith(".epub") }
        assertEquals(3, epubs.size)
        for (name in epubs) assertEquals(name, expected(name), BookMetaReader.epubMeta(File(dir, name)))
    }

    @Test
    fun pdf_info_strings_give_the_expected_title_and_meta() {
        // The info dictionary of pdf-info.pdf, as MuPDF returns it.
        val info = mapOf(
            "info:Title" to "Paper:  CRDTs ",
            "info:Author" to "Martin Kleppmann; Annette Bieniusa and Alastair R. Beresford & Marc Shapiro",
            "info:Subject" to "Conflict-free\n replicated   data types",
            "info:Keywords" to "crdt, distributed systems; CRDT, ",
        )
        assertEquals(expected("pdf-info.pdf"), BookMetaReader.pdfMeta { info[it] })
        assertEquals(expected("pdf-bare.pdf"), BookMetaReader.pdfMeta { if (it == "info:Producer") "MuPDF 1.28.1" else null })
    }

    @Test
    fun description_cleaning_strips_tags_decodes_entities_and_cuts() {
        assertEquals("a & b < c “d” é ' &unknown; x", BookMetaReader.cleanDescription("<p>a &amp; b &lt; c &#x201C;d&#8221;</p> &#233; &apos; &unknown; x"))
        assertEquals(4000, BookMetaReader.cleanDescription("x".repeat(5000))!!.length)
        assertNull(BookMetaReader.cleanDescription(" <br/> "))
    }

    @Test
    fun opf_isbn_series_and_subjects_follow_the_rules() {
        val opf = """
            <package><metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
              <dc:title>  T  </dc:title>
              <dc:identifier>urn:uuid:123</dc:identifier>
              <dc:identifier opf:scheme="isbn">0-306-40615-x</dc:identifier>
              <dc:subject>Fantasy</dc:subject><dc:subject>fantasy</dc:subject><dc:subject> </dc:subject>
              <meta property="belongs-to-collection" id="c1">Earthsea</meta>
              <meta refines="#c1" property="group-position">0x2</meta>
            </metadata></package>
        """.trimIndent()
        val r = BookMetaReader.opfMeta(opf)
        assertEquals("T", r.title)
        assertEquals("030640615X", r.meta!!.isbn)
        assertEquals(listOf("Fantasy"), r.meta!!.subjects)
        assertEquals("Earthsea", r.meta!!.series)
        assertEquals(2.0, r.meta!!.seriesIndex)
    }

    @Test
    fun a_file_that_is_not_an_epub_fails_loudly() {
        val bad = File.createTempFile("not-an-epub", ".epub").apply { writeText("hello"); deleteOnExit() }
        val e = runCatching { BookMetaReader.epubMeta(bad) }.exceptionOrNull()
        assertEquals(true, e != null)
    }

    @Test
    fun snippets_cut_with_ellipses() {
        val text = "a".repeat(50) + " Gethen " + "b".repeat(50)
        assertEquals("…" + "a".repeat(39) + " Gethen " + "b".repeat(39) + "…", BookText.snippet(text, "gethen"))
        assertEquals("short  text".replace("  ", " "), BookText.snippet(" short \n text ", "zzz"))
        assertEquals("x".repeat(80) + "…", BookText.snippet("x".repeat(81), "é"))
    }
}
