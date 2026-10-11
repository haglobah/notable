package com.ethran.notable.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Stroke and image ids with a local write that is queued or running. The projector leaves these
 * ids alone: the local write is newer than anything it could apply, and applying a remote delete
 * between this device's doc commit and its Room write would lose the local edit in Room.
 */
@Singleton
class PendingLocalWrites @Inject constructor() {
    private val counts = HashMap<String, Int>()

    @Synchronized
    fun add(ids: Collection<String>) = ids.forEach { counts.merge(it, 1, Int::plus) }

    @Synchronized
    fun remove(ids: Collection<String>) = ids.forEach { id ->
        val left = (counts[id] ?: return@forEach) - 1
        if (left <= 0) counts.remove(id) else counts[id] = left
    }

    @Synchronized
    fun contains(id: String): Boolean = id in counts
}

/**
 * The editor's content writes, one at a time, in the order they were made. Each goes through
 * [mirror]: doc commit, wait until it's on disk, Room write, settle. A page-cut move removes and
 * re-adds the same ids, so two writes must never overtake each other.
 *
 * [enqueue] never blocks, so a pen-up adds no latency. A failed write is passed to [onFailure]
 * and the queue goes on; the in-memory page is untouched, so the next write of it persists it.
 */
class EditorWriteQueue(
    private val mirror: ContentMirror,
    private val pending: PendingLocalWrites,
    scope: CoroutineScope,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onFailure: (op: String, e: Throwable) -> Unit,
) {
    private sealed interface Item {
        class Write(val op: String, val change: LocalChange, val ids: List<String>, val queuedAt: Long, val room: suspend () -> Unit) : Item
        class Barrier(val done: CompletableDeferred<Unit>) : Item
    }

    private val items = Channel<Item>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (item in items) when (item) {
                is Item.Barrier -> item.done.complete(Unit)
                is Item.Write -> try {
                    mirror.write(item.change, item.queuedAt) { item.room() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFailure(item.op, e)
                } finally {
                    pending.remove(item.ids)
                }
            }
        }
    }

    fun enqueue(op: String, change: LocalChange, room: suspend () -> Unit) {
        val ids = change.contentIds
        pending.add(ids)
        val sent = items.trySend(Item.Write(op, change, ids, nanoTime(), room))
        if (sent.isFailure) {
            pending.remove(ids)
            throw IllegalStateException("editor write queue is closed; '$op' was not saved", sent.exceptionOrNull())
        }
    }

    /** Returns once everything enqueued before this call is done. */
    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        items.send(Item.Barrier(done))
        done.await()
    }
}
