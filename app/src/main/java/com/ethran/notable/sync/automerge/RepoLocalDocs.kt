package com.ethran.notable.sync.automerge

import org.automerge.ChangeHash
import org.automerge.Document
import org.automerge.repo.AutomergeUrl
import org.automerge.repo.DocHandle
import org.automerge.repo.Repo
import org.automerge.repo.Storage
import org.automerge.repo.StorageKey
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * A [Storage] that records which writes have finished, so a caller can wait until a commit is on
 * disk. automerge-java 0.0.9 returns from `DocHandle.withDocument` before it stores the change.
 *
 * The repo stores each change at `<docId>/incremental/<changeHash>`. It compacts in the
 * background: a `<docId>/snapshot/<hash>` holding everything so far, then deletes the
 * incrementals it covers. A failed write is remembered and fails its waiter.
 */
class DurableStorage(private val inner: Storage) : Storage {
    private val lock = Object()
    /** Finished incremental puts, `<docId>/<hashHex>` → error (null when written). Bounded. */
    private val finished = object : LinkedHashMap<String, Throwable?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Throwable?>) = size > MAX_FINISHED
    }
    /** docId → the highest [issued] number of a snapshot put that finished. */
    private val snapshotDone = HashMap<String, Long>()
    /** docId → puts and deletes not finished yet. */
    private val inFlight = HashMap<String, Int>()
    /** Counts every put; see [mark]. */
    private var issued = 0L

    override fun load(key: StorageKey): CompletableFuture<java.util.Optional<ByteArray>> = inner.load(key)
    override fun loadRange(prefix: StorageKey): CompletableFuture<Map<StorageKey, ByteArray>> = inner.loadRange(prefix)

    override fun put(key: StorageKey, value: ByteArray): CompletableFuture<Void> {
        val parts = key.parts
        val doc = parts.getOrNull(0)
        val n = synchronized(lock) {
            if (doc != null) inFlight.merge(doc, 1, Int::plus)
            ++issued
        }
        val f = try {
            inner.put(key, value)
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }
        return f.whenComplete { _, err ->
            synchronized(lock) {
                if (doc != null) {
                    when (parts.getOrNull(1)) {
                        INCREMENTAL -> parts.getOrNull(2)?.let { finished["$doc/$it"] = err ?: null }
                        SNAPSHOT -> if (err == null && n > (snapshotDone[doc] ?: 0L)) snapshotDone[doc] = n
                    }
                    release(doc)
                }
                lock.notifyAll()
            }
        }
    }

    override fun delete(key: StorageKey): CompletableFuture<Void> {
        val doc = key.parts.getOrNull(0)
        synchronized(lock) { if (doc != null) inFlight.merge(doc, 1, Int::plus) }
        val f = try {
            inner.delete(key)
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }
        return f.whenComplete { _, _ ->
            synchronized(lock) {
                if (doc != null) release(doc)
                lock.notifyAll()
            }
        }
    }

    private fun release(doc: String) {
        val left = (inFlight[doc] ?: 1) - 1
        if (left <= 0) inFlight.remove(doc) else inFlight[doc] = left
    }

    /** A point in the write order. A snapshot put issued after it holds every change made before it. */
    fun mark(): Long = synchronized(lock) { issued }

    /**
     * Waits until each of [heads] of [docId] is on disk: its incremental was written, or a snapshot
     * issued after [since] was.
     * @throws IOException when a write failed or didn't finish in [timeoutMs].
     */
    fun awaitHeads(docId: String, heads: Collection<ChangeHash>, since: Long, timeoutMs: Long = TIMEOUT_MS) {
        val keys = heads.map { "$docId/${hex(it.bytes)}" }
        await(timeoutMs, { "heads ${keys.joinToString()} were not written" }) {
            if ((snapshotDone[docId] ?: 0L) > since) return@await true
            for (k in keys) {
                if (k !in finished) return@await false
                finished[k]?.let { throw IOException("storing $k failed: ${it.message}", it) }
            }
            true
        }
    }

    /** Waits until no put or delete of [docId] is in flight. */
    fun awaitIdle(docId: String, timeoutMs: Long = TIMEOUT_MS) =
        await(timeoutMs, { "writes of $docId are still in flight" }) { docId !in inFlight }

    private inline fun await(timeoutMs: Long, what: () -> String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(lock) {
            while (!done()) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) throw IOException("${what()} after $timeoutMs ms")
                lock.wait(left)
            }
        }
    }

    companion object {
        const val INCREMENTAL = "incremental"
        const val SNAPSHOT = "snapshot"
        const val TIMEOUT_MS = 30_000L
        private const val MAX_FINISHED = 4096
        internal fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}

/**
 * [LocalDocs] over the session's repo. [edit] and [createDoc] return once their changes are on
 * disk (see [DurableStorage]), so a commit they returned survives a process kill. Reads that
 * commit nothing don't wait.
 */
class RepoLocalDocs(
    private val repo: Repo,
    private val storage: DurableStorage,
    private val libraryUrl: String,
) : LocalDocs {
    private val handles = ConcurrentHashMap<String, DocHandle>()

    override fun libraryUrl() = libraryUrl

    override fun <T> edit(url: String, block: (Document) -> T): T {
        val handle = handle(url)
        val run = get(handle.withDocument { doc ->
            val before = doc.heads
            val result = block(doc)
            val after = doc.heads
            // Inside withDocument: no snapshot issued from here on can miss this change.
            Edit(result, if (sameHeads(before, after)) null else newHeads(before, after), storage.mark())
        })
        run.heads?.let { storage.awaitHeads(docId(handle), it, run.mark) }
        return run.result
    }

    override fun createDoc(doc: Document): String {
        val handle = createDurably(repo, storage, doc)
        val url = handle.url.toString()
        handles[url] = handle
        return url
    }

    /** Loads the doc's stored chunks (snapshots, then incrementals) without the repo. */
    override fun <T> peek(url: String, block: (Document) -> T): T {
        val id = url.removePrefix("automerge:")
        val chunks = get(storage.loadRange(StorageKey(id)))
        val ordered = chunks.entries.sortedBy { if (it.key.parts.getOrNull(1) == DurableStorage.SNAPSHOT) 0 else 1 }
        if (ordered.isEmpty()) throw IllegalStateException("doc $url is not stored on this device")
        val bytes = java.io.ByteArrayOutputStream().apply { ordered.forEach { write(it.value) } }.toByteArray()
        val doc = Document.load(bytes)
        return try {
            block(doc)
        } finally {
            doc.free()
        }
    }

    private class Edit<T>(val result: T, val heads: List<ChangeHash>?, val mark: Long)

    private fun newHeads(before: Array<ChangeHash>, after: Array<ChangeHash>): List<ChangeHash> {
        val old = before.map { it.bytes.toList() }.toSet()
        return after.filter { it.bytes.toList() !in old }
    }

    private fun handle(url: String): DocHandle = handles[url] ?: run {
        val found = get(repo.find(AutomergeUrl.parse(url)))
        if (!found.isPresent) throw IllegalStateException("doc $url is not in the local repo")
        handles.putIfAbsent(url, found.get()) ?: found.get()
    }

    companion object {
        private const val TIMEOUT_S = 60L

        /** Stores [doc] as a new doc of [repo]; returns once all of it is on disk. */
        fun createDurably(repo: Repo, storage: DurableStorage, doc: Document): DocHandle {
            val mark = storage.mark()
            val handle = get(repo.create(doc.save()))
            val id = docId(handle)
            storage.awaitHeads(id, doc.heads.toList(), mark)
            storage.awaitIdle(id)
            return handle
        }

        /** The storage key's first part: the URL without its scheme. */
        private fun docId(handle: DocHandle) = handle.url.toString().removePrefix("automerge:")

        internal fun <T> get(f: CompletableFuture<T>): T = try {
            f.get(TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
