package com.ethran.notable.sync.automerge

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageWithData
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import org.automerge.Document
import java.util.Date

/** In-memory stand-ins for Room, the blob server and the Automerge repo. */
internal class FakeLocalLibrary(
    val folders: MutableList<Folder> = mutableListOf(),
    val notebooks: MutableList<Notebook> = mutableListOf(),
    val pages: MutableMap<String, PageWithData> = linkedMapOf(),
) : LocalLibrary {
    override fun folders() = folders.toList()
    override fun notebooks() = notebooks.toList()
    override fun quickPages() = pages.values.map { it.page }.filter { it.notebookId == null }
    override fun page(pageId: String) = pages[pageId]

    fun add(page: Page, strokes: List<Stroke> = emptyList(), images: List<Image> = emptyList()) {
        pages[page.id] = PageWithData(page, strokes, images)
    }
}

internal class FakeBlobStore(val files: MutableMap<String, ByteArray> = mutableMapOf()) : BlobStore {
    val uploads = mutableListOf<String>()
    override fun upload(pathOrUri: String): String {
        val bytes = files[pathOrUri] ?: throw MissingFileException(pathOrUri)
        uploads += pathOrUri
        return sha256Hex(bytes)
    }
}

internal class FakeDocStore : DocStore {
    var library: Document = AutomergeSchema.newLibraryDocument(AmLibrary(emptyMap(), emptyMap()))
    val docs = linkedMapOf<String, Document>()
    private var next = 1

    override fun library(): AmLibrary = AutomergeSchema.readLibrary(library)
    override fun createDoc(doc: Document): String {
        val url = "automerge:test${next++}"
        docs[url] = Document.load(doc.save())
        return url
    }

    override fun editLibrary(edit: (Document) -> Unit) = edit(library)
    fun notebook(url: String) = AutomergeSchema.readNotebook(docs.getValue(url))
}

internal val t0 = Date(1_780_000_000_000)
internal val t1 = Date(1_780_000_100_000)

internal fun stroke(id: String, pageId: String, maxPressure: Int = 1, pressure: Float = 0.5f) = Stroke(
    id = id, size = 3f, pen = Pen.FOUNTAIN, color = 0xFF112233.toInt(), maxPressure = maxPressure,
    top = 10f, bottom = 40f, left = 5f, right = 60f,
    points = listOf(StrokePoint(5f, 10f, pressure), StrokePoint(30f, 25f, pressure), StrokePoint(60f, 40f, pressure)),
    pageId = pageId, createdAt = t0, updatedAt = t1,
)
