package com.ethran.notable.sync.automerge

import com.ethran.notable.data.LocalChange
import com.ethran.notable.data.PendingLocalWrites
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.automerge.repo.AutomergeUrl
import org.automerge.repo.DialerConfig
import org.automerge.repo.DocHandle
import org.automerge.repo.Repo
import org.automerge.repo.RepoConfig
import org.automerge.repo.storage.FileSystemStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Offline edits reach the live server on reconnect: one device's repo on disk, used online
 * (migration), then offline (edits, a queued image, an edit made before the writer was ready),
 * then restarted online. A fresh repo then reads the result back from the server.
 *
 * Opt-in, like [LiveServerMigrationTest]: NOTABLE_E2E_SERVER, NOTABLE_E2E_USER, NOTABLE_E2E_PASSWORD
 * (a throwaway account).
 */
class LiveServerOfflineEditsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env = listOf("NOTABLE_E2E_SERVER", "NOTABLE_E2E_USER", "NOTABLE_E2E_PASSWORD")
        .associateWith { System.getenv(it) }
    private val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()

    private class Session(val repo: Repo, val storage: DurableStorage) : AutoCloseable {
        override fun close() = repo.close()
    }

    /** The device's repo on [dir]; dialed to the server only when [api] is given. */
    private fun open(dir: File, api: ServerApi? = null, token: String? = null): Session {
        val storage = DurableStorage(FileSystemStorage(dir.toPath()))
        val repo = Repo.load(RepoConfig.builder().storage(storage).build())
        if (api != null) {
            val open = CountDownLatch(1)
            repo.dial(OkHttpDialer(http, ServerApi.syncUrl(api.baseUrl), token!!, object : OkHttpDialer.Listener {
                override fun onOpen() = open.countDown()
                override fun onClosed(reason: String) = Unit
                override fun onAuthFailed() = throw AssertionError("auth failed")
            }), DialerConfig.defaults())
            assertTrue("websocket did not open", open.await(20, TimeUnit.SECONDS))
        }
        return Session(repo, storage)
    }

    private fun find(repo: Repo, url: String): DocHandle {
        repeat(40) {
            val h = repo.find(AutomergeUrl.parse(url)).get(30, TimeUnit.SECONDS)
            if (h.isPresent) return h.get()
            Thread.sleep(500)
        }
        throw AssertionError("doc $url not found")
    }

    @Test
    fun edits_made_offline_reach_the_server_on_reconnect() = runBlocking {
        assumeTrue("set ${env.keys} to run", env.values.all { !it.isNullOrBlank() })
        val api = ServerApi(http, ServerApi.parseServerUrl(env.getValue("NOTABLE_E2E_SERVER")!!))
        val token = api.login(env.getValue("NOTABLE_E2E_USER")!!, env.getValue("NOTABLE_E2E_PASSWORD")!!).token
        val lib = api.me(token).libraryDocUrl
        val run = System.currentTimeMillis().toString(36)
        val nb = "off-nb-$run"
        val p1 = "off-p1-$run"
        fun id(s: String) = "off-$s-$run"

        val dir = tmp.newFolder("device")
        val repoDir = File(dir, "repo").apply { mkdirs() }
        val room = FakeRoom()
        room.sink.notebooks[nb] = Notebook(id = nb, title = "Offline $run", openPageId = p1, pageIds = listOf(p1), createdAt = t0, updatedAt = t1)
        room.sink.pages[p1] = Page(id = p1, notebookId = nb, createdAt = t0, updatedAt = t1)
        room.sink.strokes[id("s1")] = stroke(id("s1"), p1)
        val blobs = QueuedBlobStore(File(dir, "blobs"), ::openLocalFile)
        fun writer(s: Session) = LocalDocWriter(
            RepoLocalDocs(s.repo, s.storage, lib), room.library, blobs,
            MigrationStateStore(File(dir, "migration.json")), ProjectionStateStore(File(dir, "projection.json")), 1860.0,
        )

        // 1. Online: the notebook gets its doc.
        open(repoDir, api, token).use { s ->
            find(s.repo, lib)
            assertEquals(listOf(nb), writer(s).migrateAll().migrated)
            Thread.sleep(3000)
        }

        // 2. Offline: edits commit to the docs on disk; the image waits in the queue.
        val png = tmp.newFile("offline.png").also {
            val img = BufferedImage(90, 60, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics(); g.color = java.awt.Color.ORANGE; g.fillRect(0, 0, 90, 60); g.dispose()
            ImageIO.write(img, "png", it)
        }
        val outbox = DocOutbox(File(dir, "pending-doc-writes.json"), PendingLocalWrites())
        val reported = mutableListOf<String?>()
        open(repoDir).use { s ->
            val w = writer(s)
            // Before the library is known (launch without a cached URL): kept in the outbox.
            val early = DocFirstMirror({ null }, { outbox }, { reported += it }, LatencyStats())
            val s3 = LocalChange.StrokesUpserted(p1, listOf(stroke(id("s3"), p1)))
            early.write(s3, null) { room.roomWrite(s3) }
            assertEquals(1, outbox.size)

            val mirror = DocFirstMirror({ w }, { outbox }, { reported += it }, LatencyStats())
            for (change in listOf(
                LocalChange.StrokesUpserted(p1, listOf(stroke(id("s2"), p1))),
                LocalChange.StrokesDeleted(p1, listOf(id("s1"))),
                LocalChange.ImagesUpserted(p1, listOf(Image(id("i1"), 40, 50, height = 60, width = 90, uri = png.path, pageId = p1))),
            )) mirror.write(change, System.nanoTime()) { room.roomWrite(change) }
            assertEquals(0, mirror.retryPending())
            assertEquals(1, blobs.pendingCount())
        }
        assertTrue("doc writes failed: $reported", reported.filterNotNull().isEmpty())

        // 3. Restart online: the repo sends what it has, the queue uploads.
        open(repoDir, api, token).use { s ->
            val nbUrl = find(s.repo, lib).withDocument { AutomergeSchema.readLibrary(it) }.get().notebooks.getValue(nb).docUrl
            find(s.repo, nbUrl) // as the projector follows each notebook doc
            assertEquals(1, blobs.drain({ sha, size, open -> api.putBlob(token, sha, size, open) }).uploaded)
            Thread.sleep(3000)
        }

        // 4. A fresh repo sees only what the server holds.
        open(tmp.newFolder("fresh"), api, token).use { s ->
            var strokes = emptySet<String>()
            var images = emptyList<AmImage>()
            repeat(40) {
                val entry = find(s.repo, lib).withDocument { AutomergeSchema.readLibrary(it) }.get().notebooks[nb]
                if (entry != null) {
                    val page = find(s.repo, entry.docUrl).withDocument { AutomergeSchema.readNotebook(it) }.get().pages[p1]
                    strokes = page?.strokes?.keys.orEmpty()
                    images = page?.images?.values?.toList().orEmpty()
                    if (strokes == setOf(id("s2"), id("s3")) && images.size == 1) return@repeat
                }
                Thread.sleep(500)
            }
            assertEquals(setOf(id("s2"), id("s3")), strokes)
            assertEquals(listOf(id("i1")), images.map { it.id })
            val sha = images.single().blob
            assertEquals(sha256Hex(png.readBytes()), sha)
            val back = tmp.root.resolve("back.png")
            api.getBlob(token, sha, back)
            assertEquals(sha, sha256Hex(back.readBytes()))
        }
    }
}
