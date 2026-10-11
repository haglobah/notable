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
 * doesn't apply the commit to Room again. Without a session ([writer] gives null) only Room is
 * written. A failed doc write goes to [report], as a sentence for the sync status, and Room is
 * still written, so no local edit is lost; the next good write reports null.
 *
 * Writes run one at a time, and [exclusive] runs between them: the projector's Room writes must
 * not land between a local Room write and its settle, or the settle would advance the heads past a
 * remote change that undid the local one in Room.
 */
class DocFirstMirror(
    private val writer: () -> LocalDocWriter?,
    private val report: (String?) -> Unit,
    private val latency: LatencyStats,
    private val io: CoroutineContext = Dispatchers.IO,
    private val nanoTime: () -> Long = System::nanoTime,
) : ContentMirror {
    private val lock = Mutex()

    override suspend fun <T> write(change: LocalChange, queuedAtNanos: Long?, room: suspend () -> T): T = lock.withLock {
        val w = writer() ?: return@withLock room()
        val receipt = docStep(WRITE_FAILED) { w.write(change) }
        if (receipt != null && queuedAtNanos != null) latency.record((nanoTime() - queuedAtNanos) / 1_000_000)
        val result = room()
        receipt?.let { r -> docStep(SETTLE_FAILED) { w.settle(r) } }
        result
    }

    /** Room first, because the doc is built from the new rows; then the doc, then settle. */
    override suspend fun <T> create(change: LocalChange, room: suspend () -> T): T = lock.withLock {
        val result = room()
        val w = writer() ?: return@withLock result
        docStep(WRITE_FAILED) { w.write(change) }?.let { r -> docStep(SETTLE_FAILED) { w.settle(r) } }
        result
    }

    /** Runs [block] between writes; see the class comment. */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    private suspend fun <T> docStep(failed: String, step: () -> T): T? = try {
        withContext(io) { step() }.also { report(null) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report("$failed: ${e.message ?: e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val WRITE_FAILED = "An edit is saved on this device only and won't sync"
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
