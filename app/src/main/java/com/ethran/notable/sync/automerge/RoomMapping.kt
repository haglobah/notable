package com.ethran.notable.sync.automerge

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.PageWithData
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.encodeStrokePoints
import com.ethran.notable.data.db.withNormalizedPressure
import com.ethran.notable.data.model.BackgroundType

/** A file a notebook refers to is gone, or the reference has no file at all. */
class MissingFileException(val what: String) : Exception("missing file: $what")

/**
 * Room rows → shared-schema model (web/schema/README.md). [blob] turns a local path or content
 * URI into the sha256 of the uploaded file and throws [MissingFileException] when it can't.
 */
internal class RoomMapping(private val blob: (String) -> String) {

    fun folder(f: Folder) = AmFolder(f.id, f.title, f.parentFolderId, f.createdAt, f.updatedAt)

    fun background(type: String, value: String): AmBackground =
        if (type == BackgroundType.Native.key) AmBackground(type, value, null)
        else AmBackground(type, null, blob(value))

    /** Docs hold normalized pressure only; legacy rows carry the digitizer's raw scale. */
    fun stroke(s: Stroke): AmStroke {
        val n = s.withNormalizedPressure()
        return AmStroke(
            id = n.id, pen = n.pen.penName, color = n.color, size = n.size.toDouble(),
            left = n.left.toDouble(), top = n.top.toDouble(), right = n.right.toDouble(), bottom = n.bottom.toDouble(),
            points = encodeStrokePoints(n.points),
            createdAt = n.createdAt, updatedAt = n.updatedAt,
        )
    }

    fun image(i: Image): AmImage {
        val uri = i.uri ?: throw MissingFileException("image ${i.id} has no file")
        return AmImage(i.id, blob(uri), i.x, i.y, i.width, i.height, i.createdAt, i.updatedAt)
    }

    fun page(p: PageWithData) = AmPage(
        id = p.page.id,
        background = background(p.page.backgroundType, p.page.background),
        strokes = p.strokes.associate { it.id to stroke(it) },
        images = p.images.associate { it.id to image(it) },
        createdAt = p.page.createdAt,
        updatedAt = p.page.updatedAt,
    )

    /** The file a notebook was made from: its default PDF background, else the first PDF page. */
    fun source(nb: Notebook, pages: List<PageWithData>): AmSource {
        if (isPdf(nb.defaultBackgroundType)) return AmSource.Pdf(blob(nb.defaultBackground))
        val pdfPage = pages.firstOrNull { isPdf(it.page.backgroundType) } ?: return AmSource.None
        return AmSource.Pdf(blob(pdfPage.page.background))
    }

    private fun isPdf(type: String) = when (BackgroundType.fromKey(type)) {
        BackgroundType.AutoPdf, is BackgroundType.Pdf -> true
        else -> false
    }
}
