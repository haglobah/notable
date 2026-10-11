package com.ethran.notable.sync.automerge

import com.ethran.notable.data.ContentMirror
import com.ethran.notable.data.LocalChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ContentMirror] over the session's [LocalDocWriter] (ADR-001: the docs are the source of truth).
 *
 * A write is: doc commit, which returns once it's on disk; Room write; settle, so the projector
 * doesn't apply the commit to Room again. Signed out ([outbox] gives null) only Room is written.
 *
 * A doc write that fails, or that can't run yet because the library isn't known ([writer] gives
 * null), is recorded in the [DocOutbox] before Room is written, and [retryPending] brings it to the
 * doc later. Room never holds an edit the doc will never get: if even the outbox can't record it,
 * the write fails before Room is touched. [report] gets a sentence for the sync status, or null
 * once a doc write succeeded.
 *
 * Writes and retries run one at a time, and [exclusive] runs between them: the projector's Room
 * writes must not land between a local Room write and its settle, or the settle would advance the
 * heads past a remote change that undid the local one in Room.
 */
class DocFirstMirror(
    private val writer: () -> LocalDocWriter?,
    private val outbox: () -> DocOutbox?,
    private val report: (String?) -> Unit,
    private val latency: LatencyStats,
    private val io: CoroutineContext = Dispatchers.IO,
    private val nanoTime: () -> Long = System::nanoTime,
) : ContentMirror {
    private val lock = Mutex()

    override suspend fun <T> write(change: LocalChange, queuedAtNanos: Long?, room: suspend () -> T): T = lock.withLock {
        val o = outbox() ?: return@withLock room()
        val w = writer() ?: return@withLock owe(o, change) { room() }
        val receipt = docStep(WRITE_FAILED) { w.write(change) } ?: return@withLock owe(o, change) { room() }
        if (queuedAtNanos != null) latency.record((nanoTime() - queuedAtNanos) / 1_000_000)
        val result = room()
        settle(w, receipt)
        result
    }

    /** Room first, because the doc is built from the new rows; then the doc, then settle. */
    override suspend fun <T> create(change: LocalChange, room: suspend () -> T): T = lock.withLock {
        val o = outbox() ?: return@withLock room()
        val w = writer()
        val debt = DocDebt.of(change)
        // An earlier failed edit of the same rows stays owed: this creation doesn't replay it.
        val owedBefore = o.owes(debt)
        // Recorded first: a crash right after the Room write must not leave a Room-only row.
        withContext(io) { o.record(change) }
        val result = room()
        if (w != null) docStep(WRITE_FAILED) { w.write(change) }?.let { r ->
            if (!owedBefore) withContext(io) { o.done(debt) }
            settle(w, r)
        }
        result
    }

    /**
     * Brings the outbox's edits to the doc, oldest first. A pass stops at the first failure (a
     * storage timeout must not hold the editor's writes up once per debt) and moves that debt to
     * the end. Returns how many are left.
     */
    suspend fun retryPending(): Int = lock.withLock {
        val o = outbox() ?: return@withLock 0
        val w = writer() ?: return@withLock o.size
        for (debt in o.debts()) {
            val receipt = docStep(RETRY_FAILED) { w.replay(debt) }
            if (receipt == null) {
                withContext(io) { o.defer(debt) }
                break
            }
            withContext(io) { o.done(debt) }
            settle(w, receipt)
        }
        o.size
    }

    /** Runs [block] between writes; see the class comment. */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    /** Records [change] for a retry, then writes Room; a failed record fails the write. */
    private suspend fun <T> owe(o: DocOutbox, change: LocalChange, room: suspend () -> T): T {
        withContext(io) { o.record(change) }
        return room()
    }

    private suspend fun settle(w: LocalDocWriter, receipt: WriteReceipt) {
        docStep(SETTLE_FAILED, clears = false) { w.settle(receipt) }
    }

    private suspend fun <T> docStep(failed: String, clears: Boolean = true, step: () -> T): T? = try {
        withContext(io) { step() }.also { if (clears) report(null) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report("$failed: ${e.message ?: e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val WRITE_FAILED = "An edit is saved on this device and waits to be written for sync"
        const val RETRY_FAILED = "Edits waiting to be written for sync failed again"
        const val SETTLE_FAILED = "An edit synced, but this device may apply it to itself again"
    }
}

data class LatencySummary(val count: Int, val p50Ms: Long, val maxMs: Long)

/** The last [window] latencies; [summary] gives their median and maximum. */
class LatencyStats(private val window: Int = 256) {
    private val samples = ArrayDeque<Long>()
    private var total = 0L

    @Synchronized
    fun record(ms: Long) {
        total++
        samples.addLast(ms)
        if (samples.size > window) samples.removeFirst()
    }

    /** How many latencies were recorded in all. */
    @Synchronized
    fun recorded(): Long = total

    @Synchronized
    fun summary(): LatencySummary? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        return LatencySummary(sorted.size, sorted[(sorted.size - 1) / 2], sorted.last())
    }
}
