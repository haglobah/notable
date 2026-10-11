package com.ethran.notable.sync.automerge

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.FolderDao
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.ImageDao
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.NotebookDao
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageDao
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokeDao
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Room as the projector's sink. Blocking; runs on the projection worker's IO thread.
 *
 * [transaction] does not open a Room transaction: Room's transactions are suspend-scoped, and
 * blocking DAO calls inside one would deadlock its single transaction thread. Every write is an
 * idempotent upsert or an explicit delete, and the projector advances a doc's heads only after
 * all of them returned, so a crash mid-way is repaired by re-applying the same diff.
 */
internal class RoomProjectionSink(
    private val folders: FolderDao,
    private val notebooks: NotebookDao,
    private val pages: PageDao,
    private val strokes: StrokeDao,
    private val images: ImageDao,
) : ProjectionSink {
    override fun <T> transaction(block: () -> T): T = block()
    override fun folder(id: String) = runBlocking { folders.get(id) }
    override fun upsertFolder(f: Folder) = runBlocking { folders.upsert(f) }
    override fun folderHasChildren(id: String) = runBlocking {
        folders.getAll().any { it.parentFolderId == id } ||
            notebooks.getAll().any { it.parentFolderId == id } ||
            pages.getAllQuickPages().any { it.parentFolderId == id }
    }
    override fun deleteFolder(id: String) = runBlocking { folders.delete(id) }
    override fun notebook(id: String) = runBlocking { notebooks.getById(id) }
    override fun upsertNotebook(n: Notebook) = runBlocking { notebooks.upsert(n) }
    override fun deleteNotebook(id: String) = runBlocking { notebooks.delete(id) }
    override fun page(id: String) = runBlocking { pages.getById(id) }
    override fun upsertPage(p: Page) = runBlocking { pages.upsert(p) }
    override fun deletePage(id: String) = runBlocking { pages.delete(id) }
    override fun strokeIds(pageId: String) = runBlocking { strokes.idsForPage(pageId) }
    override fun upsertStrokes(s: List<Stroke>) = runBlocking { if (s.isNotEmpty()) strokes.upsert(s) }
    override fun deleteStrokes(ids: Collection<String>) = runBlocking {
        ids.toList().chunked(900).forEach { strokes.deleteAll(it) }
    }
    override fun imageIds(pageId: String) = runBlocking { images.idsForPage(pageId) }
    override fun upsertImages(i: List<Image>) = runBlocking { if (i.isNotEmpty()) images.upsert(i) }
    override fun deleteImages(ids: Collection<String>) = runBlocking {
        ids.toList().chunked(900).forEach { images.deleteAll(it) }
    }
}

/**
 * Blobs from the server as local files, in [dir] (inside the app's shared storage folder, next to
 * the other backgrounds). Named by hash, so a file there is complete and correct by construction
 * ([ServerApi.getBlob] renames only after checking the hash).
 */
internal class DownloadingProjectionFiles(
    private val download: (sha256: String, dest: File) -> Unit,
    private val dir: File,
    private val open: (String) -> InputStream,
) : ProjectionFiles {
    private val hashes = HashMap<String, String?>()

    override fun backgroundPath(blob: String, type: String): String {
        val ext = if (type == "autoPdf" || type.startsWith("pdf")) ".pdf" else ""
        return fetch(blob, ext).absolutePath
    }

    override fun imageUri(blob: String): String = "file://" + fetch(blob, "").absolutePath

    override fun holds(pathOrUri: String, blob: String): Boolean {
        val local = pathOrUri.removePrefix("file://")
        if (File(local).parentFile == dir && File(local).name.startsWith(blob)) return File(local).isFile
        val hash = synchronized(hashes) {
            hashes.getOrPut(pathOrUri) { runCatching { open(pathOrUri).use(::sha256Of) }.getOrNull() }
        }
        return hash == blob
    }

    private fun fetch(blob: String, ext: String): File {
        require(blob.matches(Regex("[0-9a-f]{64}"))) { "not a sha256 blob id: $blob" }
        val f = File(dir, blob + ext)
        if (!f.isFile) download(blob, f)
        return f
    }

    private fun sha256Of(input: InputStream): String {
        val d = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            d.update(buf, 0, n)
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
