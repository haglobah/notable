package com.ethran.notable.data

import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Saves page previews one at a time, in [scope] rather than the caller's.
 *
 * The editor requests a save as it is left, and its own scope is cancelled right after, so the
 * save must not depend on it. [request] never suspends and never drops a page. A failed save is
 * logged and does not stop later ones.
 */
class PagePersistQueue(
    scope: CoroutineScope,
    private val persist: suspend (pageId: String) -> Unit,
) {
    private val log = ShipBook.getLogger("PagePersistQueue")
    private val requests = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (pageId in requests) {
                try {
                    persist(pageId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e("Saving the preview of page $pageId failed", e)
                }
            }
        }
    }

    fun request(pageId: String) {
        val sent = requests.trySend(pageId)
        if (sent.isFailure) log.e("Preview save for page $pageId was not queued: queue closed")
    }
}
