package com.ethran.notable.io.projection

import com.ethran.notable.sync.automerge.AmEntryKind
import com.ethran.notable.sync.automerge.AmEpubLayout
import com.ethran.notable.sync.automerge.AmNotebookEntry
import com.ethran.notable.sync.automerge.AmSource
import com.ethran.notable.sync.automerge.AutomergeSchema
import com.ethran.notable.sync.automerge.FixturePaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.automerge.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.math.abs

/**
 * Android's PDF projection matches the PWA's Download (ADR-001 "PDF as a projection").
 *
 * `web/schema/fixtures/projection-v1.json` holds notebooks and what the TS `project()` made of
 * them, summarized with MuPDF (`web/app/test/projectionParity.ts`). The plan Kotlin hands to fitz
 * must give the same pages (bounds, drawn content), the same annotations (count, order, ink
 * points, colors, opacity, widths, stamp rects; ink rects within tolerance), the same metadata and
 * the same embedded notebook doc. fitz itself can't run on the JVM; the device check compares a
 * real Android PDF with `web/app/scripts/compare-projection.ts`.
 */
class ProjectionParityTest {
    private val fixture = Json.parseToJsonElement(File(FixturePaths.dir, "projection-v1.json").readText()).jsonObject
    private val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }

    private fun case(name: String) = cases.first { it.str("name") == name }

    @Test fun pdf_notebook_matches_the_pwa() = check(case("pdf"))
    @Test fun epub_notebook_matches_the_pwa() = check(case("epub"))
    @Test fun native_notebook_matches_the_pwa() = check(case("native"))

    @Test
    fun every_fixture_case_is_checked() {
        assertEquals(listOf("pdf", "epub", "native"), cases.map { it.str("name") })
    }

    private fun check(c: JsonObject) {
        val name = c.str("name")
        val docBytes = Base64.getDecoder().decode(c.str("notebook"))
        val doc = Document.load(docBytes)
        val nb = AutomergeSchema.readNotebook(doc)
        val entry = entry(c.getValue("entry").jsonObject)
        val plan = ProjectionPlanner.plan(nb, entry, FixtureSources(c))
        val expected = c.getValue("expected").jsonObject

        // Pages: bounds, and the drawn content of pages Notable draws itself.
        val pages = expected.getValue("pages").jsonArray.map { it.jsonObject }
        assertEquals("$name: page count", pages.size, plan.pages.size)
        for ((i, pair) in pages.zip(plan.pages).withIndex()) {
            val (want, got) = pair
            val at = "$name page ${i + 1}"
            assertRect("$at bounds", want.rect("bounds"), got.bounds, 0.001)
            if (got.content is PageContent.Drawn) {
                assertEquals("$at content stream", want.str("contents"), (got.content as PageContent.Drawn).ops)
            }
            val annots = want.getValue("annotations").jsonArray.map { it.jsonObject }
            val gotTypes = got.stamps.map { "Stamp" } + got.inks.map { "Ink" }
            assertEquals("$at annotation types", annots.map { it.str("type") }, gotTypes)
            for ((a, stamp) in annots.filter { it.str("type") == "Stamp" }.zip(got.stamps)) {
                assertRect("$at stamp rect", a.rect("rect"), stamp.rect, 0.01)
            }
            for ((k, pairInk) in annots.filter { it.str("type") == "Ink" }.zip(got.inks).withIndex()) {
                val (a, ink) = pairInk
                val ia = "$at ink ${k + 1}"
                val wantPts = a.getValue("inkList").jsonArray.single().jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.double } }
                assertEquals("$ia point count", wantPts.size, ink.points.size)
                for ((wp, gp) in wantPts.zip(ink.points)) {
                    assertNear("$ia x", wp[0], gp.x, 0.01)
                    assertNear("$ia y", wp[1], gp.y, 0.01)
                }
                val color = a.getValue("color").jsonArray.map { it.jsonPrimitive.double }
                assertEquals("$ia color components", 3, color.size)
                for (j in 0..2) assertNear("$ia color", color[j], ink.rgb[j], 1e-6)
                assertNear("$ia opacity", a.getValue("opacity").jsonPrimitive.double, ink.opacity ?: 1.0, 1e-6)
                assertNear("$ia width", a.getValue("borderWidth").jsonPrimitive.double, ink.borderWidth, 1e-4)
                // MuPDF sizes the rect from the appearance: the ink plus about its width and 6 pt.
                val r = a.rect("rect")
                val minX = ink.points.minOf { it.x }; val maxX = ink.points.maxOf { it.x }
                val minY = ink.points.minOf { it.y }; val maxY = ink.points.maxOf { it.y }
                val slack = ink.borderWidth + 7
                assertTrue("$ia rect $r holds the ink", r.x0 <= minX && r.y0 <= minY && r.x1 >= maxX && r.y1 >= maxY)
                assertTrue("$ia rect $r within tolerance", minX - r.x0 <= slack && minY - r.y0 <= slack && r.x1 - maxX <= slack && r.y1 - maxY <= slack)
            }
        }

        // Metadata, embedded files and the embedded doc.
        assertEquals("$name title", expected.str("title"), plan.title)
        assertEquals("$name embedded files", expected.getValue("embedded").jsonArray.map { it.jsonPrimitive.content },
            listOf(ProjectionPlanner.EMBEDDED_DOC, ProjectionPlanner.EMBEDDED_META).sorted())
        assertEquals("$name meta", expected.getValue("meta"), Json.parseToJsonElement(plan.metaJson).normalized())
        val embedded = Document.load(ProjectionPlanner.embeddedDoc(doc))
        val wantHeads = expected.getValue("docHeads").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals("$name embedded doc heads", wantHeads, embedded.heads.map { hex(it.bytes) }.toSet())
        assertEquals("$name embedded doc content", nb, AutomergeSchema.readNotebook(embedded))
    }

    /** Source facts the TS side measured with MuPDF. */
    private class FixtureSources(c: JsonObject) : ProjectionSources {
        private val documents = c.getValue("documents").jsonObject.mapValues { (_, v) ->
            v.jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.double }.let { PdfRect(it[0], it[1], it[2], it[3]) } }
        }
        private val images = c.getValue("images").jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.int }.let { it[0] to it[1] } }

        override fun documentPages(ref: DocumentRef) =
            documents[ref.key] ?: throw ProjectionException("${ref.key} not in the fixture")

        override fun imageSize(blob: String) = images[blob] ?: throw ProjectionException("image $blob not in the fixture")
    }

    private fun entry(o: JsonObject): AmNotebookEntry {
        val s = o.getValue("source").jsonObject
        val source = when (s.str("kind")) {
            "none" -> AmSource.None
            "pdf" -> AmSource.Pdf(s.str("blob"))
            "epub" -> s.getValue("layout").jsonObject.let { l ->
                AmSource.Epub(s.str("blob"), AmEpubLayout(l.d("w"), l.d("h"), l.d("em")))
            }
            else -> error("unknown source")
        }
        return AmNotebookEntry(
            id = o.str("id"), docUrl = o.str("docUrl"), title = o.str("title"),
            folderId = (o["folderId"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
            kind = AmEntryKind.fromKey(o.str("kind")), source = source,
            createdAt = Date.from(Instant.parse(o.str("createdAt"))),
            updatedAt = Date.from(Instant.parse(o.str("updatedAt"))),
        )
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.d(k: String) = getValue(k).jsonPrimitive.double
    private fun JsonObject.rect(k: String) = getValue(k).jsonArray.map { it.jsonPrimitive.double }.let { PdfRect(it[0], it[1], it[2], it[3]) }

    /** JSON numbers compare by value: TS writes 446, Kotlin may write 446.0. */
    private fun JsonElement.normalized(): JsonElement = when (this) {
        is JsonObject -> JsonObject(mapValues { it.value.normalized() })
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(map { it.normalized() })
        is JsonPrimitive -> if (!isString && doubleOrNull != null && double == Math.rint(double)) JsonPrimitive(double.toLong()) else this
        else -> this
    }

    private fun assertNear(what: String, want: Double, got: Double, eps: Double) =
        assertTrue("$what: want $want, got $got", abs(want - got) <= eps)

    private fun assertRect(what: String, want: PdfRect, got: PdfRect, eps: Double) {
        assertNear("$what x0", want.x0, got.x0, eps); assertNear("$what y0", want.y0, got.y0, eps)
        assertNear("$what x1", want.x1, got.x1, eps); assertNear("$what y1", want.y1, got.y1, eps)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
