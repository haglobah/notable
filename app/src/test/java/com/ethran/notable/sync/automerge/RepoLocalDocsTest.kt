package com.ethran.notable.sync.automerge

import org.automerge.AmValue
import org.automerge.Document
import org.automerge.NewValue
import org.automerge.ObjectId
import org.automerge.repo.AutomergeUrl
import org.automerge.repo.Repo
import org.automerge.repo.RepoConfig
import org.automerge.repo.storage.FileSystemStorage
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Durability of local edits: when [RepoLocalDocs.edit] returns, the change is in the repo's files.
 * Copying the folder at that moment is what a process kill leaves behind.
 */
class RepoLocalDocsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun open(dir: File): Pair<Repo, DurableStorage> {
        val storage = DurableStorage(FileSystemStorage(dir.toPath()))
        return Repo.load(RepoConfig.builder().storage(storage).build()) to storage
    }

    /** What a fresh process finds on disk: a repo over a copy of [dir], read [key] from [url]. */
    private fun readAfterKill(dir: File, url: String, key: String): String? {
        val copy = copyWhileCompacting(dir)
        val repo = Repo.load(RepoConfig.builder().storage(FileSystemStorage(copy.toPath())).build())
        try {
            val h = repo.find(AutomergeUrl.parse(url)).get(10, TimeUnit.SECONDS)
            if (!h.isPresent) return null
            return h.get().withDocument { d -> (d.get(ObjectId.ROOT, key).orElse(null) as? AmValue.Str)?.value }
                .get(10, TimeUnit.SECONDS)
        } finally {
            repo.close()
        }
    }

    /**
     * The repo compacts in the background: it writes a snapshot, then deletes the incrementals it
     * covers. A copy taken while that happens can hold the old snapshot but none of the
     * incrementals, which no kill leaves behind. Files are content-addressed and never rewritten,
     * so a copy is consistent when the file list was the same before and after it; else it's retried.
     */
    private fun copyWhileCompacting(dir: File): File {
        fun listing() = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toSet()
        repeat(50) {
            val copy = tmp.newFolder()
            val before = listing()
            val vanished = try {
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val dest = File(copy, f.relativeTo(dir).path)
                    dest.parentFile.mkdirs()
                    java.nio.file.Files.copy(f.toPath(), dest.toPath())
                }
                false
            } catch (_: java.nio.file.NoSuchFileException) {
                true
            }
            if (!vanished && listing() == before) return copy
            Thread.sleep(20)
        }
        throw AssertionError("files kept changing while copying $dir")
    }

    @Test
    fun `an edit is on disk when edit returns`() {
        val dir = tmp.newFolder()
        val (repo, storage) = open(dir)
        repo.use {
            val docs = RepoLocalDocs(repo, storage, libraryUrl = "unused")
            val url = docs.createDoc(Document().apply { startTransaction().apply { set(ObjectId.ROOT, "k", NewValue.str("v0")); commit() } })
            assertEquals("v0", readAfterKill(dir, url, "k"))
            repeat(20) { i ->
                docs.edit(url) { d -> d.startTransaction().apply { set(ObjectId.ROOT, "k", NewValue.str("v${i + 1}")); commit() } }
                assertEquals("edit $i", "v${i + 1}", readAfterKill(dir, url, "k"))
            }
        }
    }

    @Test
    fun `a doc created from many changes is on disk when createDoc returns`() {
        val dir = tmp.newFolder()
        val (repo, storage) = open(dir)
        repo.use {
            val docs = RepoLocalDocs(repo, storage, libraryUrl = "unused")
            val doc = Document()
            repeat(50) { i -> doc.startTransaction().apply { set(ObjectId.ROOT, "k$i", NewValue.str("v$i")); commit() } }
            val url = docs.createDoc(doc)
            assertEquals("v0", readAfterKill(dir, url, "k0"))
            assertEquals("v49", readAfterKill(dir, url, "k49"))
        }
    }

    @Test
    fun `an edit waits for a slow storage write`() {
        val dir = tmp.newFolder()
        val inner = FileSystemStorage(dir.toPath())
        val slow = object : Storage by inner {
            override fun put(key: StorageKey, value: ByteArray): CompletableFuture<Void> =
                CompletableFuture.runAsync({ Thread.sleep(150) }).thenCompose { inner.put(key, value) }
        }
        val storage = DurableStorage(slow)
        Repo.load(RepoConfig.builder().storage(storage).build()).use { repo ->
            val docs = RepoLocalDocs(repo, storage, libraryUrl = "unused")
            val url = docs.createDoc(Document().apply { startTransaction().apply { set(ObjectId.ROOT, "k", NewValue.str("v0")); commit() } })
            assertEquals("v0", readAfterKill(dir, url, "k"))
            docs.edit(url) { d -> d.startTransaction().apply { set(ObjectId.ROOT, "k", NewValue.str("v1")); commit() } }
            assertEquals("v1", readAfterKill(dir, url, "k"))
        }
    }

    @Test
    fun `a failed storage write fails the edit`() {
        val inner = FileSystemStorage(tmp.newFolder().toPath())
        var failing = false
        val flaky = object : Storage by inner {
            override fun put(key: StorageKey, value: ByteArray): CompletableFuture<Void> =
                if (failing && key.parts.getOrNull(1) == "incremental") CompletableFuture.failedFuture(java.io.IOException("disk full"))
                else inner.put(key, value)
        }
        val storage = DurableStorage(flaky)
        Repo.load(RepoConfig.builder().storage(storage).build()).use { repo ->
            val docs = RepoLocalDocs(repo, storage, libraryUrl = "unused")
            val url = docs.createDoc(Document())
            failing = true
            val e = assertThrows(java.io.IOException::class.java) {
                docs.edit(url) { d -> d.startTransaction().apply { set(ObjectId.ROOT, "k", NewValue.str("v")); commit() } }
            }
            assertTrue(e.message, e.message!!.contains("disk full"))
        }
    }

    @Test
    fun `a read does not wait for storage`() {
        val (repo, storage) = open(tmp.newFolder())
        repo.use {
            val docs = RepoLocalDocs(repo, storage, libraryUrl = "unused")
            val url = docs.createDoc(Document())
            val t = System.nanoTime()
            repeat(50) { docs.edit(url) { it.heads.size } }
            assertTrue("reads waited", (System.nanoTime() - t) / 1e6 < 1000)
        }
    }
}
