package com.ethran.notable.sync.automerge

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The device check's wifi steps against a real server: device a loses its network ([FlakyNetwork]),
 * edits a doc offline, device b edits it too, a's network comes back. a must receive b's edit and
 * later ones, without a restart.
 *
 * Opt-in, like [LiveServerOfflineEditsTest]: NOTABLE_E2E_SERVER, NOTABLE_E2E_USER,
 * NOTABLE_E2E_PASSWORD (a throwaway account). Best against a server started locally.
 */
class LiveServerReconnectTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env = listOf("NOTABLE_E2E_SERVER", "NOTABLE_E2E_USER", "NOTABLE_E2E_PASSWORD")
        .associateWith { System.getenv(it) }
    private val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    private val open = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() = open.reversed().forEach { runCatching { it.close() } }

    private val quickRetry = DialerConfig(
        BackoffConfig.builder().initialDelay(Duration.ofMillis(200)).maxDelay(Duration.ofSeconds(1)).build()
    )

    private class Device(val repo: Repo, val docs: RepoLocalDocs)

    private fun device(syncUrl: String, token: String, log: String): Device {
        val storage = DurableStorage(FileSystemStorage(tmp.newFolder().toPath()))
        val repo = Repo.load(RepoConfig.builder().storage(storage).build())
        open += repo
        repo.dial(OkHttpDialer(http, syncUrl, token, object : OkHttpDialer.Listener {
            override fun onOpen() = println("$log: open")
            override fun onClosed(reason: String) = println("$log: closed: $reason")
            override fun onAuthFailed() = println("$log: auth failed")
        }), quickRetry)
        return Device(repo, RepoLocalDocs(repo, storage, "automerge:unused"))
    }

    private fun find(repo: Repo, url: String): DocHandle {
        repeat(40) {
            val h = repo.find(AutomergeUrl.parse(url)).get(30, TimeUnit.SECONDS)
            if (h.isPresent) return h.get()
            Thread.sleep(500)
        }
        throw AssertionError("doc $url not found")
    }

    private fun set(d: Document, key: String, value: String) =
        d.startTransaction().apply { set(ObjectId.ROOT, key, NewValue.str(value)); commit() }

    private fun read(d: Document, key: String) = (d.get(ObjectId.ROOT, key).orElse(null) as? AmValue.Str)?.value

    private fun awaitValue(what: String, key: String, expected: String, timeoutMs: Long = 30_000, read: () -> String?) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = read()
        while (last != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            last = read()
        }
        assertEquals("$what: $key", expected, last)
    }

    private fun scenario(localEdit: Boolean) = runBlocking {
        assumeTrue("set ${env.keys} to run", env.values.all { !it.isNullOrBlank() })
        val api = ServerApi(http, ServerApi.parseServerUrl(env.getValue("NOTABLE_E2E_SERVER")!!))
        val user = env.getValue("NOTABLE_E2E_USER")!!
        val password = env.getValue("NOTABLE_E2E_PASSWORD")!!
        val tokenA = api.login(user, password).token
        val tokenB = api.login(user, password).token
        val syncUrl = ServerApi.syncUrl(api.baseUrl)
        val target = java.net.URI(syncUrl).let { InetSocketAddress(it.host, if (it.port > 0) it.port else 80) }
        assumeTrue("needs a plain ws:// server for the proxy", syncUrl.startsWith("ws://"))
        val net = FlakyNetwork(target).also { open += it }
        val proxied = syncUrl.replace(Regex("^ws://[^/]+"), "ws://127.0.0.1:${net.port}")

        val a = device(proxied, tokenA, "a")
        val b = device(syncUrl, tokenB, "b")
        val url = a.docs.createDoc(Document().apply { set(this, "k", "v0") })
        val bHandle = find(b.repo, url)
        val aRead = { key: String -> { a.docs.edit(url) { read(it, key) } } }
        val bRead = { key: String -> { bHandle.withDocument { read(it, key) }.get(5, TimeUnit.SECONDS) } }
        awaitValue("b", "k", "v0", read = bRead("k"))
        // Online, both ways, as before the device check's steps.
        bHandle.withDocument { set(it, "remote", "b0") }.get(5, TimeUnit.SECONDS)
        awaitValue("a online", "remote", "b0", read = aRead("remote"))

        net.wifiOff()
        Thread.sleep(1000)
        if (localEdit) a.docs.edit(url) { set(it, "local", "a1") }
        bHandle.withDocument { set(it, "remote", "b1") }.get(5, TimeUnit.SECONDS)
        Thread.sleep(2000)
        net.wifiOn()

        if (localEdit) awaitValue("b got a's offline edit", "local", "a1", read = bRead("local"))
        awaitValue("a got b's edit made while a was offline", "remote", "b1", read = aRead("remote"))
        bHandle.withDocument { set(it, "remote", "b2") }.get(5, TimeUnit.SECONDS)
        awaitValue("a got b's later edit", "remote", "b2", read = aRead("remote"))
    }

    @Test
    fun remote_edits_arrive_after_wifi_off_and_on() = scenario(localEdit = false)

    @Test
    fun remote_edits_arrive_after_an_offline_local_edit() = scenario(localEdit = true)
}
