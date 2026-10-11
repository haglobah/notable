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
    fun dialer(): Dialer = object : Dialer {
        override fun getUrl() = "memory://server"
        override fun connect(): CompletableFuture<Transport> {
            val toServer = Executors.newSingleThreadExecutor().also { synchronized(executors) { executors += it } }
            val toClient = Executors.newSingleThreadExecutor().also { synchronized(executors) { executors += it } }
            lateinit var client: Transport
            lateinit var server: Transport
            client = Transport({ data -> toServer.execute { server.onMessage(data) } }, { toServer.execute { server.onClose() } })
            server = Transport({ data -> toClient.execute { client.onMessage(data) } }, { toClient.execute { client.onClose() } })
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
