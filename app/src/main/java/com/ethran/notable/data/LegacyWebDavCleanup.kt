package com.ethran.notable.data

import android.content.Context
import android.util.Log
import androidx.work.WorkManager

/**
 * Cancels WorkManager work that the removed WebDAV sync may have left scheduled (its periodic sync
 * and queued one-off syncs). The worker class is gone, so such work could only fail; cancelling it
 * also removes its system jobs. The stored WebDAV settings and tables go with Room's migration 37 to
 * 38 (`MIGRATION_37_38`).
 *
 * Idempotent and cheap, so it runs at every start. Remove it, and the WorkManager dependency, once
 * no device can still upgrade from a build with WebDAV sync.
 */
object LegacyWebDavCleanup {
    /** The tag the WebDAV sync gave all its work requests. */
    const val WORK_TAG = "sync-work"

    fun cancelWork(context: Context) {
        try {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelAllWorkByTag(WORK_TAG)
            workManager.pruneWork()
        } catch (e: Exception) {
            Log.w("LegacyWebDavCleanup", "Could not cancel old WebDAV sync work", e)
        }
    }
}
