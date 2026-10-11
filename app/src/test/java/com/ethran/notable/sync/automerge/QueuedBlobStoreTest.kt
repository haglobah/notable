package com.ethran.notable.sync.automerge

import com.ethran.notable.data.LocalChange
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Blobs are copied into the account folder at once and uploaded when the server can be reached. */
class QueuedBlobStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val files = mutableMapOf<String, ByteArray>()
    private val opened = mutableListOf<String>()
    private val open: (String) -> InputStream = { path ->
        opened += path
        (files[path] ?: throw MissingFileException(path)).inputStream()
    }
    private val dir by lazy { tmp.newFolder("blobs") }
    private fun store() = QueuedBlobStore(dir, open)

    /** The server's blob store; [down] makes every PUT fail as without a network. */
    private val server = mutableMapOf<String, ByteArray>()
    private var down = false
    private val put: (String, Long, () -> InputStream) -> Unit = { sha, size, body ->
        if (down) throw ServerError.Network(IOException("offline"))
        val bytes = body().use { it.readBytes() }
        assertEquals(size, bytes.size.toLong())
        assertEquals(sha, sha256Hex(bytes))
        server[sha] = bytes
    }

    @Test
    fun `a file is copied into the queue and its hash returned without the server`() {
        files["content://media/1"] = byteArrayOf(1, 2, 3)
        val sha = store().upload("content://media/1")

        assertEquals(sha256Hex(byteArrayOf(1, 2, 3)), sha)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir, sha).readBytes())
        assertEquals(1, store().pendingCount())
    }

    @Test
    fun `a missing file still fails`() {
        try {
            store().upload("content://media/gone")
            fail("expected MissingFileException")
        } catch (_: MissingFileException) {
        }
        assertEquals(0, store().pendingCount())
    }

    @Test
    fun `queued blobs upload when the server is back, also after a restart`() {
        files["/a.png"] = byteArrayOf(7)
        val sha = store().upload("/a.png")
        down = true
        try {
            store().drain(put)
            fail("expected the network error")
        } catch (_: ServerError.Network) {
        }
        assertEquals("kept for the next try", 1, store().pendingCount())

        down = false
        val result = store().drain(put) // a new instance: the queue is on disk
        assertEquals(1, result.uploaded)
        assertArrayEquals(byteArrayOf(7), server[sha])
        assertEquals(0, store().pendingCount())
    }

    @Test
    fun `an uploaded blob is not queued again`() {
        files["/a.png"] = byteArrayOf(7)
        store().upload("/a.png")
        store().drain(put)
        server.clear()

        store().upload("/a.png") // after a restart: no memory of the path
        assertEquals(0, store().pendingCount())
    }

    @Test
    fun `a blob the server refuses is set aside and reported, not retried`() {
        files["/big.pdf"] = byteArrayOf(1)
        files["/a.png"] = byteArrayOf(2)
        val big = store().upload("/big.pdf")
        store().upload("/a.png")
        val result = store().drain({ sha, size, body -> if (sha == big) throw ServerError.TooLarge() else put(sha, size, body) })

        assertEquals(1, result.uploaded)
        assertEquals(listOf(big), result.rejected.map { it.sha })
        assertEquals(0, store().pendingCount())
        assertEquals(0, store().drain(put).uploaded)
    }

    @Test
    fun `a queued blob serves the projection before it is uploaded`() {
        files["/a.png"] = byteArrayOf(4, 5)
        val sha = store().upload("/a.png")
        val dest = File(tmp.newFolder("sync-blobs"), sha)

        assertTrue(store().copyQueued(sha, dest))
        assertArrayEquals(byteArrayOf(4, 5), dest.readBytes())
        assertFalse(store().copyQueued("0".repeat(64), File(dest.parentFile, "x")))
    }

    @Test
    fun `the same unchanged file is read once per session`() {
        files["content://media/1"] = byteArrayOf(1)
        val s = store()
        s.upload("content://media/1")
        s.upload("content://media/1")
        assertEquals(1, opened.size)
    }

    @Test
    fun `an image edit commits to the doc offline`() {
        files["content://media/cat"] = byteArrayOf(9, 9)
        val d = Device(tmp, blobStore = store()).apply {
            room.sink.notebooks["nb1"] = Notebook(id = "nb1", title = "Lectures", openPageId = "p1", pageIds = listOf("p1"), createdAt = t0, updatedAt = t1)
            room.sink.pages["p1"] = Page(id = "p1", notebookId = "nb1", createdAt = t0, updatedAt = t1)
            writer.migrateAll()
            project()
        }
        val img = Image("i1", 10, 20, 30, 40, "content://media/cat", "p1", t0, t1)
        d.edit(LocalChange.ImagesUpserted("p1", listOf(img)))

        val sha = sha256Hex(byteArrayOf(9, 9))
        assertEquals(sha, d.docs.notebook("nb1").pages.getValue("p1").images.getValue("i1").blob)
        assertEquals(1, store().pendingCount())
        store().drain(put)
        assertArrayEquals(byteArrayOf(9, 9), server[sha])
    }
}
