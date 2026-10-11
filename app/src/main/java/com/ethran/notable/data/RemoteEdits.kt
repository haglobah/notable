package com.ethran.notable.data

import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A change to one page that arrived from another device and is already written to Room; or a
 * page deleted on this device outside the editor ([pageDeleted], see [LibraryEdits]).
 * [PageDataManager] applies it to its cache, so an open editor shows it without a reload.
 * Deletions are explicit: ids the other device removed, never "whatever isn't in the doc".
 */
data class RemotePageEdit(
    val pageId: String,
    val upsertStrokes: List<Stroke> = emptyList(),
    val deleteStrokes: Set<String> = emptySet(),
    val upsertImages: List<Image> = emptyList(),
    val deleteImages: Set<String> = emptySet(),
    /** Background or other page-row fields changed. */
    val pageChanged: Boolean = false,
    val pageDeleted: Boolean = false,
)

/** Process-wide stream of [RemotePageEdit]s, emitted after the Room write commits. */
object RemoteEdits {
    private val _edits = MutableSharedFlow<List<RemotePageEdit>>(extraBufferCapacity = 64)
    val edits: SharedFlow<List<RemotePageEdit>> = _edits.asSharedFlow()

    /** Never blocks; returns false when the buffer is full (the cache then reloads on next open). */
    fun publish(edits: List<RemotePageEdit>): Boolean = edits.isEmpty() || _edits.tryEmit(edits)
}

/**
 * This page's cached strokes after [edit]: upserted strokes replace their old version in place (or
 * are appended), only ids the edit names are removed. Strokes the edit doesn't mention stay,
 * including ones drawn here that aren't synced yet.
 */
fun List<Stroke>.withRemote(edit: RemotePageEdit): List<Stroke> =
    mergeById(this, edit.upsertStrokes, edit.deleteStrokes) { it.id }

fun List<Image>.withRemoteImages(edit: RemotePageEdit): List<Image> =
    mergeById(this, edit.upsertImages, edit.deleteImages) { it.id }

private fun <T> mergeById(current: List<T>, upserts: List<T>, deletes: Set<String>, id: (T) -> String): List<T> {
    if (upserts.isEmpty() && deletes.isEmpty()) return current
    val byId = upserts.associateBy(id)
    val out = ArrayList<T>(current.size + upserts.size)
    val seen = HashSet<String>()
    for (item in current) {
        val k = id(item)
        if (k in deletes && k !in byId) continue
        out += byId[k] ?: item
        seen += k
    }
    upserts.filter { id(it) !in seen }.forEach { out += it }
    return out
}
