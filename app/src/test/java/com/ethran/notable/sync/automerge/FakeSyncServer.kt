package com.ethran.notable.sync.automerge

import org.automerge.repo.Dialer
import org.automerge.repo.Repo
import org.automerge.repo.RepoConfig
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey
import org.automerge.repo.Transport
import org.automerge.repo.storage.FileSystemStorage
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * An automerge-repo peer in this process, standing in for the sync server: devices dial it over
 * in-memory transports. [docIds] are the docs it has stored, so a test can count what reached it.
 */
internal class FakeSyncServer(dir: File) : AutoCloseable {
    private val stored = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val storage = object : Storage by FileSystemStorage(dir.toPath()) {
        private val inner = FileSystemStorage(dir.toPath())
        override fun load(key: StorageKey) = inner.load(key)
        override fun loadRange(prefix: StorageKey) = inner.loadRange(prefix)
        override fun delete(key: StorageKey) = inner.delete(key)
        override fun put(key: StorageKey, value: ByteArray): CompletableFuture<Void> {
            key.parts.firstOrNull()?.let { if (it != "storage-adapter-id") stored += it }
            return inner.put(key, value)
        }
    }
    val repo: Repo = Repo.load(RepoConfig.builder().storage(storage).build())
    private val acceptor = repo.makeAcceptor("memory://server")
    private val executors = mutableListOf<ExecutorService>()

    /** Doc ids (URLs without `automerge:`) the server has stored. */
    fun docIds(): Set<String> = synchronized(stored) { stored.toSet() }

    /** A dialer for one device; every connect makes a fresh pair of transports. */
    fun dialer(): DeviceDialer = DeviceDialer()

    /** One device's network: [reachable] and [cut] stand in for its wifi. */
    inner class DeviceDialer : Dialer {
        /** While false, a dial fails, as with wifi off. */
        @Volatile var reachable = true
        /** Live connections: each one's client and server transport. */
        private val links = java.util.Collections.synchronizedList(mutableListOf<Pair<Transport, Transport>>())
        private val halfOpen = java.util.Collections.synchronizedList(mutableListOf<Transport>())

        /**
         * Drops this device's connections, as wifi off does: the server sees the close. The
         * device does too, unless [silently]: then its messages vanish until [noticeClose] (a
         * half-open socket).
         */
        fun cut(silently: Boolean = false) {
            val now = synchronized(links) { links.toList().also { links.clear() } }
            now.forEach { (client, server) ->
                server.onClose()
                if (silently) halfOpen += client else client.onClose()
            }
        }

        /** The device notices the half-open sockets of [cut] now. */
        fun noticeClose() {
            val now = synchronized(halfOpen) { halfOpen.toList().also { halfOpen.clear() } }
            now.forEach { it.onClose() }
        }

        override fun getUrl() = "memory://server"
        override fun connect(): CompletableFuture<Transport> {
            if (!reachable) return CompletableFuture.failedFuture(java.io.IOException("server unreachable"))
            val toServer = Executors.newSingleThreadExecutor().also { synchronized(executors) { executors += it } }
            val toClient = Executors.newSingleThreadExecutor().also { synchronized(executors) { executors += it } }
            lateinit var client: Transport
            lateinit var server: Transport
            lateinit var link: Pair<Transport, Transport>
            fun live() = synchronized(links) { links.any { it === link } }
            client = Transport({ data -> if (live()) toServer.execute { server.onMessage(data) } }, { toServer.execute { server.onClose() } })
            server = Transport({ data -> if (live()) toClient.execute { client.onMessage(data) } }, { toClient.execute { client.onClose() } })
            link = client to server
            links += link
            acceptor.accept(server)
            return CompletableFuture.completedFuture(client)
        }
    }

    override fun close() {
        runCatching { acceptor.close() }
        runCatching { repo.close() }
        synchronized(executors) { executors.forEach { it.shutdownNow() } }
    }
}
