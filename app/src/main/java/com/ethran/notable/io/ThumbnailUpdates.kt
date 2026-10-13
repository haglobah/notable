package com.ethran.notable.io

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells page previews that a page's thumbnail file was written or dropped.
 *
 * Thumbnails come from [ThumbnailGenerator], from the editor when a page is left, and sync drops
 * them for remotely edited pages. Every writer announces here, so a visible preview reloads.
 */
@Singleton
class ThumbnailUpdates @Inject constructor() {
    private val _updated = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Page IDs whose thumbnail file changed. */
    val updated: SharedFlow<String> = _updated.asSharedFlow()

    fun announce(pageId: String) {
        _updated.tryEmit(pageId)
    }
}

/**
 * Image cache key for a thumbnail file, or null if the file is missing. The key carries the
 * file's modification time, so a rewritten thumbnail is never served from an older cache entry.
 */
fun thumbnailCacheKey(file: File): String? {
    val modified = file.lastModified()
    return if (modified == 0L) null else "${file.absolutePath}@$modified"
}
