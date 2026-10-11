package com.ethran.notable.sync.automerge

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * End to end against a real server: login, WebSocket sync with the Bearer dialer, blob upload, and
 * the migration. A second, fresh repo then reads everything back from the server.
 *
 * Opt-in, because it needs a server and a throwaway account:
 * NOTABLE_E2E_SERVER, NOTABLE_E2E_USER, NOTABLE_E2E_PASSWORD, NOTABLE_E2E_PDF (any small PDF).
 * Prints the library URL so the PWA can be checked against the same account.
 */
class LiveServerMigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env = listOf("NOTABLE_E2E_SERVER", "NOTABLE_E2E_USER", "NOTABLE_E2E_PASSWORD", "NOTABLE_E2E_PDF")
        .associateWith { System.getenv(it) }
    private val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()

    private fun connectedRepo(dir: File, api: ServerApi, token: String): Repo {
        val repo = Repo.load(RepoConfig.builder().storage(FileSystemStorage(dir.toPath())).build())
        val open = CountDownLatch(1)
        repo.dial(OkHttpDialer(http, ServerApi.syncUrl(api.baseUrl), token, object : OkHttpDialer.Listener {
            override fun onOpen() = open.countDown()
            override fun onClosed(reason: String) = Unit
            override fun onAuthFailed() = throw AssertionError("auth failed")
        }), DialerConfig.defaults())
        assertTrue("websocket did not open", open.await(20, TimeUnit.SECONDS))
        return repo
    }

    private fun find(repo: Repo, url: String): DocHandle {
        repeat(40) {
            val h = repo.find(AutomergeUrl.parse(url)).get(30, TimeUnit.SECONDS)
            if (h.isPresent) return h.get()
            Thread.sleep(500)
        }
        throw AssertionError("server never sent $url")
    }

    /** A circle of radius r around (cx, cy), in canvas pixels, as Notable would store it. */
    private fun circle(id: String, pageId: String, cx: Float, cy: Float, r: Float, pen: Pen, color: Int): Stroke {
        val pts = (0..72).map { i ->
            val a = i * Math.PI / 36
            StrokePoint((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat(), 0.5f + 0.4f * sin(a * 3).toFloat())
        }
        return Stroke(
            id = id, size = 6f, pen = pen, color = color, maxPressure = 1,
            top = cy - r, bottom = cy + r, left = cx - r, right = cx + r, points = pts, pageId = pageId,
        )
    }

    @Test
    fun migrates_through_the_live_server_and_a_fresh_repo_reads_it_back() {
        assumeTrue("set ${env.keys} to run", env.values.all { !it.isNullOrBlank() })
        val api = ServerApi(http, ServerApi.parseServerUrl(env.getValue("NOTABLE_E2E_SERVER")!!))
        val token = api.login(env.getValue("NOTABLE_E2E_USER")!!, env.getValue("NOTABLE_E2E_PASSWORD")!!).token
        val me = api.me(token)
        println("E2E libraryDocUrl=${me.libraryDocUrl}")

        val png = tmp.newFile("photo.png").also {
            val img = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics(); g.color = java.awt.Color.WHITE; g.fillRect(0, 0, 120, 80)
            g.color = java.awt.Color.BLUE; g.fillOval(20, 10, 80, 60); g.dispose()
            ImageIO.write(img, "png", it)
        }
        val pdf = env.getValue("NOTABLE_E2E_PDF")!!
        val local = FakeLocalLibrary()
        local.folders += Folder("e2e-folder", "E2E folder", null)
        local.notebooks += Notebook(
            id = "e2e-notes", title = "E2E notes", openPageId = "e2e-p1", pageIds = listOf("e2e-p1", "e2e-p2"),
            parentFolderId = "e2e-folder", defaultBackground = "lined", defaultBackgroundType = "native",
        )
        local.add(Page("e2e-p1", 0, "e2e-notes", "lined", "native"), listOf(circle("e2e-s1", "e2e-p1", 600f, 700f, 200f, Pen.BALLPEN, 0xFF000000.toInt())))
        local.add(
            Page("e2e-p2", 0, "e2e-notes", "squared", "native"),
            listOf(circle("e2e-s2", "e2e-p2", 900f, 1200f, 300f, Pen.FOUNTAIN, 0xFF0000FF.toInt())),
            listOf(Image("e2e-i1", 100, 100, height = 400, width = 600, uri = png.path, pageId = "e2e-p2")),
        )
        local.notebooks += Notebook(
            id = "e2e-pdf", title = "E2E annotated PDF", pageIds = listOf("e2e-q1", "e2e-q2"),
            defaultBackground = pdf, defaultBackgroundType = "autoPdf",
        )
        local.add(Page("e2e-q1", 0, "e2e-pdf", pdf, "autoPdf"), listOf(circle("e2e-s3", "e2e-q1", 930f, 600f, 250f, Pen.MARKER, 0xFFFF0000.toInt())))
        local.add(Page("e2e-q2", 0, "e2e-pdf", pdf, "autoPdf"), listOf(circle("e2e-s4", "e2e-q2", 500f, 900f, 150f, Pen.BALLPEN, 0xFF000000.toInt())))
        local.add(Page("e2e-qp", 0, null, "blank", "native", "e2e-folder"), listOf(circle("e2e-s5", "e2e-qp", 930f, 1000f, 400f, Pen.BALLPEN, 0xFF000000.toInt())))

        val stateFile = tmp.root.resolve("migration.json")
        val repoA = connectedRepo(tmp.newFolder("repoA"), api, token)
        val report = repoA.use { repo ->
            val docs = RepoDocStore(repo, find(repo, me.libraryDocUrl))
            val first = RoomToAutomergeMigration(local, UploadingBlobStore(api, token, ::openLocalFile), docs,
                MigrationStateStore(stateFile), canvasWidth = 1860.0).run()
            val second = RoomToAutomergeMigration(local, UploadingBlobStore(api, token, ::openLocalFile), docs,
                MigrationStateStore(stateFile), canvasWidth = 1860.0).run()
            assertTrue("second run re-migrated ${second.migrated}", second.migrated.isEmpty())
            Thread.sleep(3000) // let the repo flush its last changes to the server
            first
        }
        assertEquals(setOf("e2e-notes", "e2e-pdf", "e2e-qp"), report.migrated.toSet())
        assertTrue(report.failed.toString(), report.failed.isEmpty())

        // A fresh repo with empty storage sees only what the server holds.
        connectedRepo(tmp.newFolder("repoB"), api, token).use { repo ->
            var lib = find(repo, me.libraryDocUrl).withDocument { AutomergeSchema.readLibrary(it) }.get()
            repeat(20) {
                if (lib.notebooks.keys.containsAll(report.migrated)) return@repeat
                Thread.sleep(500)
                lib = find(repo, me.libraryDocUrl).withDocument { AutomergeSchema.readLibrary(it) }.get()
            }
            assertTrue(lib.notebooks.keys.toString(), lib.notebooks.keys.containsAll(report.migrated))
            assertEquals("E2E folder", lib.folders.getValue("e2e-folder").title)
            val strokes = report.migrated.associateWith { id ->
                val nb = find(repo, lib.notebooks.getValue(id).docUrl).withDocument { AutomergeSchema.readNotebook(it) }.get()
                assertEquals(1860.0, nb.canvasWidth)
                println("E2E $id docUrl=${lib.notebooks.getValue(id).docUrl} pages=${nb.pageOrder}")
                nb.pages.values.sumOf { it.strokes.size }
            }
            assertEquals(mapOf("e2e-notes" to 2, "e2e-pdf" to 2, "e2e-qp" to 1), strokes)
            assertTrue(lib.notebooks.getValue("e2e-pdf").source is AmSource.Pdf)
        }
        // Blobs reached the server under the hash the docs name.
        val blob = (UploadingBlobStore(api, token, ::openLocalFile)).upload(pdf)
        assertEquals(sha256Hex(File(pdf).readBytes()), blob)
    }
}
