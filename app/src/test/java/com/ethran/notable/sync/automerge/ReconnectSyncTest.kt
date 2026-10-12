package com.ethran.notable.sync.automerge

import org.automerge.AmValue
import org.automerge.Document
import org.automerge.NewValue
import org.automerge.ObjectId
import org.automerge.repo.AutomergeUrl
import org.automerge.repo.BackoffConfig
import org.automerge.repo.DialerConfig
import org.automerge.repo.DocHandle
import org.automerge.repo.Repo
import org.automerge.repo.RepoConfig
import org.automerge.repo.storage.FileSystemStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * A device that loses the server, edits a doc while offline, and comes back must still receive
 * the edits other devices made meanwhile and later. Found on the Note Air 5C: after an offline
 * local edit, the device sent its edit but received nothing for that doc until a restart.
 */
class ReconnectSyncTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val open = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() = open.reversed().forEach { runCatching { it.close() } }

    private val quickRetry = DialerConfig(
        BackoffConfig.builder().initialDelay(Duration.ofMillis(100)).maxDelay(Duration.ofMillis(500)).build()
    )

    private class Device(val repo: Repo, val docs: RepoLocalDocs, val net: FakeSyncServer.DeviceDialer)

    private fun device(server: FakeSyncServer): Device {
        val storage = DurableStorage(FileSystemStorage(tmp.newFolder().toPath()))
        val repo = Repo.load(RepoConfig.builder().storage(storage).build())
        open += repo
        val net = server.dialer()
        repo.dial(net, quickRetry)
        return Device(repo, RepoLocalDocs(repo, storage, "automerge:unused"), net)
    }

    private fun find(repo: Repo, url: String): DocHandle {
        repeat(60) {
            val h = repo.find(AutomergeUrl.parse(url)).get(10, TimeUnit.SECONDS)
            if (h.isPresent) return h.get()
            Thread.sleep(200)
        }
        throw AssertionError("doc $url not found")
    }

    private fun set(d: Document, key: String, value: String) =
        d.startTransaction().apply { set(ObjectId.ROOT, key, NewValue.str(value)); commit() }

    private fun read(d: Document, key: String) = (d.get(ObjectId.ROOT, key).orElse(null) as? AmValue.Str)?.value

    private fun awaitValue(what: String, key: String, expected: String, read: () -> String?) {
        val deadline = System.currentTimeMillis() + 20_000
        var last = read()
        while (last != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            last = read()
        }
        assertEquals("$what: $key", expected, last)
    }

    private fun scenario(silently: Boolean) {
        val server = FakeSyncServer(tmp.newFolder()).also { open += it }
        val a = device(server)
        val b = device(server)
        val url = a.docs.createDoc(Document().apply { set(this, "k", "v0") })
        val bHandle = find(b.repo, url)
        awaitValue("b", "k", "v0") { bHandle.withDocument { read(it, "k") }.get(5, TimeUnit.SECONDS) }

        // Wifi off on device a only: b stays connected.
        a.net.reachable = false
        a.net.cut(silently)
        a.docs.edit(url) { set(it, "local", "a1") }
        bHandle.withDocument { set(it, "remote", "b1") }.get(5, TimeUnit.SECONDS)
        Thread.sleep(500) // b's edit reaches the server
        if (silently) a.net.noticeClose()
        a.net.reachable = true

        val aRead = { key: String -> { a.docs.edit(url) { read(it, key) } } }
        val bRead = { key: String -> { bHandle.withDocument { read(it, key) }.get(5, TimeUnit.SECONDS) } }
        awaitValue("server got a's offline edit, b has it", "local", "a1", bRead("local"))
        awaitValue("a got b's edit made while a was offline", "remote", "b1", aRead("remote"))

        // And later edits keep arriving.
        bHandle.withDocument { set(it, "remote", "b2") }.get(5, TimeUnit.SECONDS)
        awaitValue("a got b's later edit", "remote", "b2", aRead("remote"))
    }

    @Test
    fun remote_edits_arrive_after_an_offline_local_edit() = scenario(silently = false)

    @Test
    fun remote_edits_arrive_after_an_offline_local_edit_on_a_half_open_socket() = scenario(silently = true)
}
