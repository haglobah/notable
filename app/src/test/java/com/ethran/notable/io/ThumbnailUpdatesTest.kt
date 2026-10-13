package com.ethran.notable.io

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Library cards must not show a thumbnail older than the file on disk. The editor and sync
 * rewrite or drop thumbnails behind the preview's back; the cache key and the signal cover both.
 */
class ThumbnailUpdatesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a missing thumbnail has no cache key`() {
        assertNull(thumbnailCacheKey(tmp.root.resolve("absent.webp")))
    }

    @Test
    fun `a rewritten thumbnail gets a new cache key`() {
        val file = tmp.newFile("page.webp").apply { writeText("old"); setLastModified(1_000_000) }
        val before = thumbnailCacheKey(file)
        file.writeText("new with strokes")
        file.setLastModified(2_000_000)
        assertNotEquals(before, thumbnailCacheKey(file))
    }

    @Test
    fun `an announced page reaches previews listening for it`() = runBlocking {
        val updates = ThumbnailUpdates()
        val heard = async(start = CoroutineStart.UNDISPATCHED) { updates.updated.first() }
        updates.announce("page-1")
        assertEquals("page-1", heard.await())
    }
}
