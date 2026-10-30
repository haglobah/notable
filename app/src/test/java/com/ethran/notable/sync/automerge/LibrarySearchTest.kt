package com.ethran.notable.sync.automerge

import com.ethran.notable.sync.automerge.LibrarySearch.Facet
import com.ethran.notable.sync.automerge.LibrarySearch.Field
import com.ethran.notable.sync.automerge.LibrarySearch.Token
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Every case of web/schema/fixtures/search-v1.json (`cases`, `browse`, `natural`), as the TS test runs them. */
class LibrarySearchTest {
    private val fixture = Json.parseToJsonElement(File(FixturePaths.dir, "search-v1.json").readText()).jsonObject
    private val library = FixtureJson.library(fixture.getValue("library").jsonObject)

    private fun ids(o: JsonObject) = o.getValue("ids").jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun every_fixture_case_finds_the_same_ids_in_the_same_order() {
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        val failures = cases.mapNotNull { c ->
            val query = c.getValue("query").jsonPrimitive.content
            val got = LibrarySearch.search(library, query).map { it.id }
            if (got == ids(c)) null else "${JsonPrimitive(query)}: want ${ids(c)}, got $got"
        }
        assertEquals("", failures.joinToString("\n"))
        assertEquals(31, cases.size)
    }

    /** A browse case as a query: `folderId` absent is the whole library, JSON null the root. */
    private fun queryOf(c: JsonObject) = LibrarySearch.Query(
        query = c.getValue("query").jsonPrimitive.content,
        sort = c["sort"]?.let { LibrarySearch.SortKey.fromKey(it.jsonPrimitive.content) ?: error("unknown sort $it") }
            ?: LibrarySearch.SortKey.RELEVANCE,
        descending = c["descending"]?.jsonPrimitive?.boolean,
        scope = if ("folderId" !in c) LibrarySearch.Scope.All else LibrarySearch.Scope.In(
            c.getValue("folderId").let { if (it is JsonNull) null else it.jsonPrimitive.content },
            c["subfolders"]?.jsonPrimitive?.boolean ?: false,
        ),
    )

    @Test
    fun every_browse_case_finds_the_same_ids_in_the_same_order() {
        val cases = fixture.getValue("browse").jsonArray.map { it.jsonObject }
        val failures = cases.mapNotNull { c ->
            val got = LibrarySearch.query(library, queryOf(c)).map { it.id }
            if (got == ids(c)) null else "$c: got $got"
        }
        assertEquals("", failures.joinToString("\n"))
        assertEquals(47, cases.size)
    }

    @Test
    fun natural_order_matches_the_fixture() {
        val failures = fixture.getValue("natural").jsonArray.mapNotNull { e ->
            val (a, b, sign) = e.jsonArray.let { Triple(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content, it[2].jsonPrimitive.int) }
            val got = Integer.signum(LibrarySearch.naturalCompare(a, b))
            if (got == sign) null else "$a vs $b: want $sign, got $got"
        }
        assertEquals("", failures.joinToString("\n"))
    }

    @Test
    fun query_tokens_quotes_negation_fields_unknown_fields_any_of_values() {
        assertEquals(
            listOf(
                Token(true, null, listOf("le guin")),
                Token(false, Field.AUTHORS, listOf("k. le")),
                Token(false, null, listOf("foo:bar")),
                Token(false, null, listOf("-")),
                Token(false, null, listOf("x")),
                Token(false, Field.KIND, listOf("pdf", "epub")),
                Token(false, Field.SUBJECTS, listOf("a,b", "c")),
            ),
            LibrarySearch.parse("-\"Le Guin\" author:\"K. Le\" foo:bar - x kind:pdf,,epub, tag:\"a,b\",c"),
        )
    }

    @Test
    fun facets_count_each_value_once_per_entry_most_common_first() {
        val f = LibrarySearch.facetsOf(library.notebooks.values)
        assertEquals(listOf(Facet("Ursula K. Le Guin", 2), Facet("Albert Camus", 1)), f.authors.take(2))
        assertEquals(Facet("Science Fiction", 2), f.tags[0])
        assertEquals(listOf(Facet("epub", 4), Facet("pdf", 2), Facet("notebook", 1), Facet("quickpage", 1)), f.kinds)
        assertEquals(listOf(Facet("unread", 4), Facet("reading", 3), Facet("finished", 1)), f.states)
    }

    @Test
    fun normalize_drops_marks_and_case() {
        assertEquals("ubungsblatt l'etranger", LibrarySearch.normalize("Übungsblatt L'Étranger"))
    }
}
