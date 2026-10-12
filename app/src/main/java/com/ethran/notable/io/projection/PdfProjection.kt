package com.ethran.notable.io.projection

import android.content.Context
import com.ethran.notable.sync.automerge.AutomergeSchema
import com.ethran.notable.sync.automerge.NotableSyncClient
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.automerge.Document
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notable's PDF: the notebook doc projected exactly as the PWA's Download does it (ADR-001 "PDF
 * as a projection"). Reads the device library's docs, not Room, so every PDF from either side of
 * the same notebook state is the same.
 */
@Singleton
class PdfProjection @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sync: NotableSyncClient,
) {
    private val log = ShipBook.getLogger("PdfProjection")

    /** What one projection wrote. [warnings] are also in the PDF's `notable.json`. */
    data class Result(val pages: Int, val warnings: List<String>, val pendingWrites: Int)

    /**
     * Projects library entry [entryId] (a notebook, or a quick page's id) into [out]. [onlyPages]
     * limits the pages (a single-page export); the embedded doc is still the whole notebook.
     * @throws ProjectionException, or IllegalStateException when the library can't be read.
     */
    suspend fun write(entryId: String, out: File, onlyPages: Set<String>? = null): Result = withContext(Dispatchers.IO) {
        val snapshot = sync.notebookSnapshot(entryId)
        val doc = Document.load(snapshot.doc)
        val nb = try {
            AutomergeSchema.readNotebook(doc)
        } finally {
            doc.free()
        }
        val cache = File(context.cacheDir, "projection-blobs")
        FitzPdfWriter { sha ->
            try {
                sync.blobFile(sha, entryId, cache)
            } catch (e: Exception) {
                throw ProjectionException("file $sha could not be loaded: ${e.message}", e)
            }
        }.use { writer ->
            val plan = ProjectionPlanner.plan(nb, snapshot.entry, writer, onlyPages)
            if (plan.pages.isEmpty()) throw ProjectionException("nothing to export: the notebook doc has none of these pages")
            val tmp = File(out.absoluteFile.parentFile, out.name + ".part")
            writer.write(plan, snapshot.doc, tmp)
            if (!tmp.renameTo(out)) {
                tmp.delete()
                throw ProjectionException("could not write ${out.path}")
            }
            plan.warnings.forEach { log.w("${snapshot.entry.title}: $it") }
            Result(plan.pages.size, plan.warnings, snapshot.pendingWrites)
        }
    }
}
