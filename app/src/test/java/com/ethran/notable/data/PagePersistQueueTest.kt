package com.ethran.notable.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/**
 * Leaving the editor cancels its scope right away. A page's preview requested on the way out
 * must still be saved, or the library keeps showing the page without its new strokes.
 */
class PagePersistQueueTest {
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `a request made just before the caller's scope ends is still persisted`() = runBlocking {
        val saved = CompletableDeferred<String>()
        val queue = PagePersistQueue(owner) { saved.complete(it) }

        val editorScope = CoroutineScope(Job())
        queue.request("page-1")
        editorScope.cancel()

        assertEquals("page-1", withTimeout(2_000) { saved.await() })
        owner.cancel()
    }

    @Test
    fun `a failed save does not stop later saves`() = runBlocking {
        val saved = Collections.synchronizedList(mutableListOf<String>())
        val done = CompletableDeferred<Unit>()
        val queue = PagePersistQueue(owner) { id ->
            if (id == "broken") error("disk full")
            saved += id
            done.complete(Unit)
        }

        queue.request("broken")
        queue.request("page-2")

        withTimeout(2_000) { done.await() }
        assertEquals(listOf("page-2"), saved.toList())
        owner.cancel()
    }
}
