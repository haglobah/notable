package com.ethran.notable.sync.automerge

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.decodeStrokePoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pdf = "/storage/emulated/0/Documents/notabledb/pdfs/paper.pdf"
    private val pdfBytes = "%PDF-1.7 fake".toByteArray()
    private val photo = "/storage/emulated/0/Documents/notabledb/images/photo.png"

    private val local = FakeLocalLibrary()
    private val blobs = FakeBlobStore(mutableMapOf(pdf to pdfBytes, photo to byteArrayOf(1, 2, 3)))
    private val docs = FakeDocStore()

    private fun migration(batch: Int = 1000) = RoomToAutomergeMigration(
        local, blobs, docs, MigrationStateStore(tmp.root.resolve("migration.json")),
        canvasWidth = 1860.0, strokesPerTransaction = batch,
    )

    private fun seed() {
        local.folders += Folder("f-uni", "Uni", null, t0, t1)
        local.folders += Folder("f-sub", "Sub", "f-uni", t0, t1)
        // A plain notebook with two pages, one stroke each, one image.
        local.notebooks += Notebook(
            id = "nb-plain", title = "Lectures", openPageId = "p2", pageIds = listOf("p1", "p2"),
            parentFolderId = "f-sub", defaultBackground = "lined", defaultBackgroundType = "native",
            createdAt = t0, updatedAt = t1,
        )
        local.add(Page("p1", 0, "nb-plain", "lined", "native", null, t0, t1), listOf(stroke("s1", "p1")))
        local.add(
            Page("p2", 0, "nb-plain", "squared", "native", null, t0, t1), listOf(stroke("s2", "p2")),
            listOf(Image("i1", 3, 4, height = 50, width = 80, uri = photo, pageId = "p2", createdAt = t0, updatedAt = t1)),
        )
        // An imported PDF.
        local.notebooks += Notebook(
            id = "nb-pdf", title = "Paper", pageIds = listOf("q1", "q2"), parentFolderId = null,
            defaultBackground = pdf, defaultBackgroundType = "autoPdf", createdAt = t0, updatedAt = t1,
        )
        local.add(Page("q1", 0, "nb-pdf", pdf, "autoPdf", null, t0, t1), listOf(stroke("s3", "q1")))
        local.add(Page("q2", 0, "nb-pdf", pdf, "pdf1", null, t0, t1))
        // A quick page.
        local.add(Page("qp", 0, null, "blank", "native", "f-uni", t0, t1), listOf(stroke("s4", "qp")))
    }

    @Test
    fun migrates_folders_notebooks_and_quick_pages() {
        seed()
        val report = migration().run()

        assertEquals(setOf("nb-plain", "nb-pdf", "qp"), report.migrated.toSet())
        assertTrue(report.failed.isEmpty())
        val lib = docs.library()
        assertEquals(AmFolder("f-sub", "Sub", "f-uni", t0, t1), lib.folders["f-sub"])

        val plainEntry = lib.notebooks.getValue("nb-plain")
        assertEquals("Lectures", plainEntry.title)
        assertEquals("f-sub", plainEntry.folderId)
        assertEquals(AmEntryKind.NOTEBOOK, plainEntry.kind)
        assertEquals(AmSource.None, plainEntry.source)
        assertEquals(t0, plainEntry.createdAt)
        assertEquals(t1, plainEntry.updatedAt)

        val plain = docs.notebook(plainEntry.docUrl)
        assertEquals("nb-plain", plain.notebookId)
        assertEquals("p2", plain.openPageId)
        assertEquals(listOf("p1", "p2"), plain.pageOrder)
        assertEquals(1860.0, plain.canvasWidth)
        assertEquals(AmBackground("native", "lined", null), plain.defaultBackground)
        assertEquals(AmBackground("native", "squared", null), plain.pages.getValue("p2").background)
        val s2 = plain.pages.getValue("p2").strokes.getValue("s2")
        assertEquals("FOUNTAIN", s2.pen)
        assertEquals(0xFF112233.toInt(), s2.color)
        assertEquals(listOf(5.0, 10.0, 60.0, 40.0), listOf(s2.left, s2.top, s2.right, s2.bottom))
        // SB2 keeps x/y exactly here and quantizes pressure to 16 bits.
        val decoded = decodeStrokePoints(s2.points)
        val original = stroke("s2", "p2").points
        assertEquals(original.map { it.x to it.y }, decoded.map { it.x to it.y })
        original.zip(decoded).forEach { (a, b) -> assertEquals(a.pressure!!, b.pressure!!, 1e-4f) }
        val image = plain.pages.getValue("p2").images.getValue("i1")
        assertEquals(AmImage("i1", sha256Hex(byteArrayOf(1, 2, 3)), 3, 4, 80, 50, t0, t1), image)

        val pdfEntry = lib.notebooks.getValue("nb-pdf")
        assertEquals(AmSource.Pdf(sha256Hex(pdfBytes)), pdfEntry.source)
        val pdfDoc = docs.notebook(pdfEntry.docUrl)
        assertEquals(AmBackground("autoPdf", null, sha256Hex(pdfBytes)), pdfDoc.defaultBackground)
        assertEquals(AmBackground("pdf1", null, sha256Hex(pdfBytes)), pdfDoc.pages.getValue("q2").background)

        val quick = lib.notebooks.getValue("qp")
        assertEquals(AmEntryKind.QUICK_PAGE, quick.kind)
        assertEquals("f-uni", quick.folderId)
        val quickDoc = docs.notebook(quick.docUrl)
        assertEquals("qp", quickDoc.notebookId)
        assertEquals(listOf("qp"), quickDoc.pageOrder)
        assertTrue("s4" in quickDoc.pages.getValue("qp").strokes)
    }

    @Test
    fun pressure_is_normalized_before_encoding() {
        local.notebooks += Notebook(id = "nb", title = "Raw", pageIds = listOf("p"), createdAt = t0, updatedAt = t1)
        local.add(Page("p", 0, "nb", "blank", "native", null, t0, t1), listOf(stroke("s", "p", maxPressure = 4096, pressure = 2048f)))
        migration().run()
        val doc = docs.notebook(docs.library().notebooks.getValue("nb").docUrl)
        val points = decodeStrokePoints(doc.pages.getValue("p").strokes.getValue("s").points)
        assertEquals(0.5f, points.first().pressure!!, 0.001f)
    }

    @Test
    fun a_second_run_changes_nothing() {
        seed()
        migration().run()
        val docsBefore = docs.docs.keys.toList()
        val libBefore = docs.library()

        val again = migration().run()

        assertTrue(again.migrated.isEmpty())
        assertEquals(setOf("nb-plain", "nb-pdf", "qp"), again.alreadyDone.toSet())
        assertEquals(docsBefore, docs.docs.keys.toList())
        assertEquals(libBefore, docs.library())
    }

    @Test
    fun resumes_a_run_interrupted_after_the_doc_was_created() {
        seed()
        val state = MigrationStateStore(tmp.root.resolve("migration.json"))
        // Simulate a crash after createDoc + state record, before the library entry.
        val url = docs.createDoc(AutomergeSchema.newNotebookDocument(
            AmNotebook("nb-plain", null, AmBackground("native", "lined", null), emptyList(), emptyMap())
        ))
        state.record("nb-plain", url)

        migration().run()

        assertEquals(url, docs.library().notebooks.getValue("nb-plain").docUrl)
        // Only nb-pdf and qp got new docs; the interrupted one reused its doc.
        assertEquals(3, docs.docs.size)
    }

    @Test
    fun a_missing_file_fails_only_that_notebook_and_names_the_file() {
        seed()
        blobs.files.remove(pdf)

        val report = migration().run()

        assertEquals(setOf("nb-plain", "qp"), report.migrated.toSet())
        val failure = report.failed.single()
        assertEquals("nb-pdf", failure.id)
        assertEquals("Paper", failure.title)
        assertTrue(failure.reason, failure.reason.contains(pdf))
        assertNull(docs.library().notebooks["nb-pdf"])

        // Once the file is back, the next run picks the notebook up.
        blobs.files[pdf] = pdfBytes
        val retry = migration().run()
        assertEquals(listOf("nb-pdf"), retry.migrated)
        assertTrue(retry.failed.isEmpty())
    }

    @Test
    fun an_image_without_a_file_fails_its_notebook() {
        seed()
        local.pages["p2"] = local.pages.getValue("p2").copy(
            images = listOf(Image("i9", 0, 0, height = 1, width = 1, uri = null, pageId = "p2"))
        )
        val report = migration().run()
        val failure = report.failed.single()
        assertEquals("nb-plain", failure.id)
        assertTrue(failure.reason, failure.reason.contains("i9"))
    }

    @Test
    fun large_notebooks_are_written_in_batches_with_every_stroke() {
        val pageIds = (1..5).map { "p$it" }
        local.notebooks += Notebook(id = "big", title = "Big", pageIds = pageIds, createdAt = t0, updatedAt = t1)
        pageIds.forEach { pid ->
            local.add(Page(pid, 0, "big", "blank", "native", null, t0, t1), (1..3).map { stroke("$pid-s$it", pid) })
        }
        migration(batch = 2).run()
        val doc = docs.notebook(docs.library().notebooks.getValue("big").docUrl)
        assertEquals(15, doc.pages.values.sumOf { it.strokes.size })
        assertEquals(pageIds, doc.pageOrder)
    }

    @Test
    fun a_page_listed_but_missing_in_room_is_reported_not_invented() {
        local.notebooks += Notebook(id = "nb", title = "Gap", pageIds = listOf("p", "gone"), createdAt = t0, updatedAt = t1)
        local.add(Page("p", 0, "nb", "blank", "native", null, t0, t1))
        val report = migration().run()
        assertEquals(listOf("nb"), report.migrated)
        assertEquals(listOf("gone"), report.warnings.single { it.id == "nb" }.missingPages)
        val doc = docs.notebook(docs.library().notebooks.getValue("nb").docUrl)
        assertFalse("gone" in doc.pages)
    }

    @Test
    fun progress_counts_up_to_the_total() {
        seed()
        val seen = mutableListOf<MigrationProgress>()
        migration().run { seen += it }
        assertEquals(3, seen.last().total)
        assertEquals(3, seen.last().done)
    }
}
