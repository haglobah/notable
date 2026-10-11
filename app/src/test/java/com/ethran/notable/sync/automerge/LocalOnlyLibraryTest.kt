package com.ethran.notable.sync.automerge

import com.ethran.notable.data.LocalChange
import com.ethran.notable.data.PendingLocalWrites
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import kotlinx.coroutines.runBlocking
import org.automerge.Document
import org.automerge.repo.AutomergeUrl
import org.automerge.repo.DialerConfig
import org.automerge.repo.DocHandle
import org.automerge.repo.Repo
import org.automerge.repo.RepoConfig
import org.automerge.repo.storage.FileSystemStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Local-only mode (ADR-001): without an account the device has its own library, and it is
 * authoritative. A later login attaches it to the account; a logout keeps it.
 *
 * Real repos on disk; [FakeSyncServer] stands in for the sync server. Room ([room]) outlives every
 * "process" ([start] to close), as the Room database does.
 */
class LocalOnlyLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val alice = LibraryOwner("https://notable.example/", "alice")
    private val bob = LibraryOwner("https://notable.example/", "bob")

    private val room = FakeRoom()
    private val root by lazy { tmp.newFolder("notable-sync") }
    private val lib by lazy { DeviceLibrary(root) }
    private val blobs = FakeBlobStore()
    private val open = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() = open.reversed().forEach { runCatching { it.close() } }

    /** One run of the app: the device library opened as NotableSyncClient opens it. */
    private inner class Proc(val opened: OpenedLibrary, docs: (LocalDocs) -> LocalDocs = { it }) : AutoCloseable {
        val repo: Repo get() = opened.repo
        val libraryUrl = opened.state.libraryUrl!!
        val docs = RepoLocalDocs(opened.repo, opened.storage, libraryUrl)
        val migrationState = MigrationStateStore(File(lib.dir, "migration.json"))
        val writer = LocalDocWriter(docs(this.docs), room.library, blobs, migrationState, ProjectionStateStore(File(lib.dir, "projection.json")), 1860.0)
        val outbox = DocOutbox(File(lib.dir, "pending-doc-writes.json"), PendingLocalWrites())
        val mirror = DocFirstMirror({ writer }, { outbox }, { r -> if (r != null) fail("doc write failed: $r") }, LatencyStats())

        fun library(): AmLibrary = docs.edit(libraryUrl) { AutomergeSchema.readLibrary(it) }
        fun notebook(id: String): AmNotebook = docs.edit(library().notebooks.getValue(id).docUrl) { AutomergeSchema.readNotebook(it) }

        override fun close() = opened.repo.close()
    }

    private fun start(account: LibraryOwner?, server: FakeSyncServer? = null, docs: (LocalDocs) -> LocalDocs = { it }): Proc {
        val p = Proc(lib.open(account), docs)
        open += p
        server?.let { p.repo.dial(it.dialer(), DialerConfig.defaults()) }
        return p
    }

    private fun server(): FakeSyncServer = FakeSyncServer(tmp.newFolder()).also { open += it }

    /** The account's library doc, made by the server when the account was created. */
    private fun FakeSyncServer.newAccountLibrary(): String =
        repo.create(AutomergeSchema.newLibraryDocument(AmLibrary(emptyMap(), emptyMap())).save()).get(10, TimeUnit.SECONDS).url.toString()

    private fun find(repo: Repo, url: String): DocHandle {
        repeat(60) {
            val h = repo.find(AutomergeUrl.parse(url)).get(10, TimeUnit.SECONDS)
            if (h.isPresent) return h.get()
            Thread.sleep(200)
        }
        throw AssertionError("doc $url not found")
    }

    /** What NotableSyncClient does once logged in: copy Room (a no-op for docs it has), merge, sync. */
    private fun attachAndSync(p: Proc): MigrationReport {
        find(p.repo, p.libraryUrl)
        val report = p.writer.migrateAll()
        p.writer.mergeLocalCopies { url -> find(p.repo, url) }
        // The projector follows every entry; a doc found while connected syncs with the server.
        p.library().notebooks.values.forEach { find(p.repo, it.docUrl) }
        return report
    }

    private fun awaitServerDocs(server: FakeSyncServer, expected: Set<String>) {
        val ids = expected.map { it.removePrefix("automerge:") }.toSet()
        val deadline = System.currentTimeMillis() + 20_000
        while (!server.docIds().containsAll(ids) && System.currentTimeMillis() < deadline) Thread.sleep(100)
        Thread.sleep(1000) // anything else on its way arrives too
        assertEquals("docs on the server", ids, server.docIds())
    }

    /** Reads the account as a second device would: a fresh repo dialed to the server. */
    private fun <T> fromServer(server: FakeSyncServer, read: (find: (String) -> Document) -> T): T {
        val repo = Repo.load(RepoConfig.builder().storage(FileSystemStorage(tmp.newFolder().toPath())).build())
        try {
            repo.dial(server.dialer(), DialerConfig.defaults())
            return read { url -> find(repo, url).withDocument { Document.load(it.save()) }.get(10, TimeUnit.SECONDS) }
        } finally {
            repo.close()
        }
    }

    private fun seedRoom() {
        room.sink.folders["f1"] = Folder("f1", "Uni", null, t0, t1)
        room.sink.notebooks["nb1"] = Notebook(id = "nb1", title = "Lectures", openPageId = "p1", pageIds = listOf("p1"),
            parentFolderId = "f1", createdAt = t0, updatedAt = t1)
        room.sink.pages["p1"] = Page(id = "p1", notebookId = "nb1", createdAt = t0, updatedAt = t1)
        room.sink.strokes["s1"] = stroke("s1", "p1")
        room.sink.notebooks["nb2"] = Notebook(id = "nb2", title = "Diary", openPageId = "p2", pageIds = listOf("p2"), createdAt = t0, updatedAt = t1)
        room.sink.pages["p2"] = Page(id = "p2", notebookId = "nb2", createdAt = t0, updatedAt = t1)
        room.sink.strokes["s2"] = stroke("s2", "p2")
        room.sink.pages["q1"] = Page(id = "q1", notebookId = null, createdAt = t0, updatedAt = t1)
        room.sink.strokes["sq"] = stroke("sq", "q1")
    }

    /** Doc folders in the device's repo (one per doc). */
    private fun repoDocs(): Set<String> = lib.repoDir.listFiles().orEmpty()
        .filter { it.isDirectory }.map { it.name }.toSet()

    @Test
    fun `without an account, Room is copied into a library of the device's own`() = runBlocking {
        seedRoom()
        val p = start(null)

        val report = p.writer.migrateAll()

        assertEquals(setOf("nb1", "nb2", "q1"), report.migrated.toSet())
        assertEquals(setOf("f1"), p.library().folders.keys)
        assertEquals(setOf("s1"), p.notebook("nb1").pages.getValue("p1").strokes.keys)
        assertNull("nobody owns it", lib.state()?.owner)

        // Every write path goes to the docs, with no account.
        val s3 = LocalChange.StrokesUpserted("p1", listOf(stroke("s3", "p1")))
        p.mirror.write(s3) { room.roomWrite(s3) }
        room.sink.notebooks["nb3"] = Notebook(id = "nb3", title = "New", openPageId = "p3", pageIds = listOf("p3"), createdAt = t0, updatedAt = t1)
        room.sink.pages["p3"] = Page(id = "p3", notebookId = "nb3", createdAt = t0, updatedAt = t1)
        p.mirror.create(LocalChange.NotebookCreated("nb3")) { }
        val renamed = LocalChange.NotebookRenamed("nb1", "Lectures 2")
        p.mirror.write(renamed) { room.roomWrite(renamed) }

        assertEquals(setOf("s1", "s3"), p.notebook("nb1").pages.getValue("p1").strokes.keys)
        assertEquals("Lectures 2", p.library().notebooks.getValue("nb1").title)
        assertEquals(listOf("p3"), p.notebook("nb3").pageOrder)
        assertEquals(0, p.outbox.size)
    }

    @Test
    fun `a copy cut short by a crash resumes on the next start, without a second doc`() {
        seedRoom()
        // The process dies after nb2's doc is made, before its library entry is written.
        val crashed = start(null) { inner ->
            object : LocalDocs by inner {
                var made = 0
                override fun createDoc(doc: Document) = inner.createDoc(doc).also { made++ }
                override fun <T> edit(url: String, block: (Document) -> T): T {
                    if (made == 2 && url == inner.libraryUrl()) throw IllegalStateException("killed")
                    return inner.edit(url, block)
                }
            }
        }
        val first = runCatching { crashed.writer.migrateAll() }
        assertTrue(first.isFailure)
        val docsAfterCrash = repoDocs()
        crashed.close()
        assertEquals("still due", true, lib.state()?.migrate)

        val p = start(null)
        val report = p.writer.migrateAll()

        assertEquals(crashed.libraryUrl, p.libraryUrl)
        assertEquals(setOf("nb2", "q1"), report.migrated.toSet())
        assertEquals(listOf("nb1"), report.alreadyDone)
        assertEquals("the doc made before the crash is reused", setOf("nb1", "nb2", "q1").map { p.library().notebooks.getValue(it).docUrl }.toSet(),
            p.migrationState.load().values.toSet())
        assertEquals("only the quick page's doc is new", 1, (repoDocs() - docsAfterCrash).size)
    }

    @Test
    fun `logging in attaches the local docs to the account, with no duplicates on the server`() = runBlocking {
        seedRoom()
        val server = server()
        val accountLibrary = server.newAccountLibrary()
        val local = start(null)
        local.writer.migrateAll()
        lib.migrationDone()
        val s3 = LocalChange.StrokesUpserted("p1", listOf(stroke("s3", "p1")))
        local.mirror.write(s3) { room.roomWrite(s3) }
        val localDocs = local.library().notebooks.mapValues { it.value.docUrl }
        local.close()

        assertEquals(LoginDecision.Attach, lib.decideLogin(alice, accountLibrary))
        lib.attach(alice, accountLibrary)
        assertEquals("copy due again", true, lib.state()?.migrate)
        val p = start(alice, server)
        val report = attachAndSync(p)

        assertEquals(setOf("nb1", "nb2", "q1"), report.migrated.toSet())
        assertEquals("the same docs, now listed in the account's library", localDocs, p.library().notebooks.mapValues { it.value.docUrl })
        awaitServerDocs(server, setOf(accountLibrary) + localDocs.values)
        val seen = fromServer(server) { find ->
            val l = AutomergeSchema.readLibrary(find(accountLibrary))
            l.folders.keys to AutomergeSchema.readNotebook(find(l.notebooks.getValue("nb1").docUrl)).pages.getValue("p1").strokes.keys
        }
        assertEquals(setOf("f1") to setOf("s1", "s3"), seen)

        // Running it again (a crash before the flag was cleared) changes nothing.
        assertEquals(setOf("nb1", "nb2", "q1"), attachAndSync(p).alreadyDone.toSet())
        awaitServerDocs(server, setOf(accountLibrary) + localDocs.values)
    }

    @Test
    fun `a notebook the account already has gets this device's pages and strokes, in the account's doc`() = runBlocking {
        seedRoom()
        val server = server()
        val accountLibrary = server.newAccountLibrary()
        // Another device put nb1 in the account first (same Room ids, e.g. through WebDAV): page p1 with stroke "other".
        val theirs = run {
            val other = MemoryLocalDocs()
            val room2 = FakeRoom().apply {
                sink.notebooks["nb1"] = room.sink.notebooks.getValue("nb1")
                sink.pages["p1"] = room.sink.pages.getValue("p1")
                sink.strokes["other"] = stroke("other", "p1")
            }
            LocalDocWriter(other, room2.library, FakeBlobStore(), MigrationStateStore(File(tmp.newFolder(), "m.json")),
                ProjectionStateStore(File(tmp.newFolder(), "p.json")), 1860.0).migrateAll()
            val nbDoc = other.docs.getValue(other.library().notebooks.getValue("nb1").docUrl)
            val url = server.repo.create(nbDoc.save()).get(10, TimeUnit.SECONDS).url.toString()
            val entry = other.library().notebooks.getValue("nb1").copy(docUrl = url)
            find(server.repo, accountLibrary).withDocument { d ->
                val (_, notebooks) = AutomergeSchema.libraryMapsOf(d)
                d.startTransaction().apply { AutomergeSchema.putNotebookEntry(this, notebooks, entry); commit() }
            }.get(10, TimeUnit.SECONDS)
            url
        }
        val local = start(null)
        local.writer.migrateAll()
        val p9 = Page(id = "p9", notebookId = "nb1", createdAt = t0, updatedAt = t1)
        room.sink.pages["p9"] = p9
        room.sink.strokes["s9"] = stroke("s9", "p9")
        local.mirror.write(LocalChange.PageAdded("nb1", "p9", "p1")) { room.roomWrite(LocalChange.PageAdded("nb1", "p9", "p1")) }
        val s3 = LocalChange.StrokesUpserted("p1", listOf(stroke("s3", "p1")))
        local.mirror.write(s3) { room.roomWrite(s3) }
        val ourNb1 = local.library().notebooks.getValue("nb1").docUrl
        val ourOthers = local.library().notebooks.filterKeys { it != "nb1" }.mapValues { it.value.docUrl }
        local.close()

        lib.attach(alice, accountLibrary)
        val p = start(alice, server)
        attachAndSync(p)

        assertEquals("the account's doc stays nb1's", theirs, p.library().notebooks.getValue("nb1").docUrl)
        val nb1 = p.notebook("nb1")
        assertEquals(setOf("other", "s1", "s3"), nb1.pages.getValue("p1").strokes.keys)
        assertEquals(listOf("p1", "p9"), nb1.pageOrder)
        assertEquals(setOf("s9"), nb1.pages.getValue("p9").strokes.keys)
        assertEquals("merged once", emptyList<LocalCopy>(), p.writer.localCopies())
        awaitServerDocs(server, setOf(accountLibrary, theirs) + ourOthers.values)
        assertTrue("this device's copy of nb1 stays on the device", ourNb1.removePrefix("automerge:") !in server.docIds())
    }

    @Test
    fun `logging out keeps the docs as the device's library, and another account is refused`() = runBlocking {
        seedRoom()
        val server = server()
        val accountLibrary = server.newAccountLibrary()
        lib.attach(alice, accountLibrary)
        start(alice, server).use { p ->
            attachAndSync(p)
            lib.migrationDone()
        }

        // Logged out: the same library, no account, no server.
        val p = start(null)
        assertEquals(accountLibrary, p.libraryUrl)
        assertEquals(alice, lib.state()?.owner)
        assertEquals(setOf("nb1", "nb2", "q1"), p.library().notebooks.keys)
        val s4 = LocalChange.StrokesUpserted("p1", listOf(stroke("s4", "p1")))
        p.mirror.write(s4) { room.roomWrite(s4) }
        assertEquals(setOf("s1", "s4"), p.notebook("nb1").pages.getValue("p1").strokes.keys)

        val d = lib.decideLogin(bob, "automerge:boblib")
        assertTrue(d is LoginDecision.Refused)
        assertEquals(alice, lib.state()?.owner)
        assertEquals(accountLibrary, lib.state()?.libraryUrl)
        p.close()

        // Alice again: the edit made while logged out goes up.
        assertEquals(LoginDecision.Same, lib.decideLogin(alice, accountLibrary))
        val again = start(alice, server)
        val nb1 = again.library().notebooks.getValue("nb1").docUrl
        find(again.repo, accountLibrary)
        find(again.repo, nb1)
        val deadline = System.currentTimeMillis() + 20_000
        var strokes = emptySet<String>()
        while (System.currentTimeMillis() < deadline && "s4" !in strokes) {
            strokes = find(server.repo, nb1).withDocument { AutomergeSchema.readNotebook(it).pages.getValue("p1").strokes.keys }.get(10, TimeUnit.SECONDS)
            Thread.sleep(100)
        }
        assertEquals(setOf("s1", "s4"), strokes)
    }
}
