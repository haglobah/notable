package com.ethran.notable.sync.automerge

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Local edits not yet in the docs ([writes]) and files not yet uploaded ([files]). */
data class PendingSync(val writes: Int, val files: Int) {
    val isEmpty: Boolean get() = writes == 0 && files == 0

    companion object {
        fun of(p: ProjectionStatus) = PendingSync(p.pendingWrites, p.pendingBlobs)
    }
}

enum class LogoutChoice {
    /** Log out only if nothing waits for sync. */
    IfNothingPending,
    /** Sync what waits first; log out only once it's sent. */
    WaitForSync,
    /** Log out now; what waits is kept on this device for the next login to the same account. */
    Anyway,
}

sealed interface LogoutResult {
    /** Still logged in: [pending] would not reach the server. [reason] says why, if known. */
    data class StillPending(val pending: PendingSync, val reason: String?) : LogoutResult

    /**
     * Logged out. [kept] edits and files did not reach the server; they stay in Room and in the
     * account folder, and go up at the next login to the same account.
     */
    data class LoggedOut(val serverWarning: String?, val kept: PendingSync) : LogoutResult
}

/** Logout must never drop edits or files that wait for sync without saying so. */
object LogoutGuard {
    fun mayEnd(choice: LogoutChoice, pending: PendingSync): Boolean =
        pending.isEmpty || choice == LogoutChoice.Anyway

    /**
     * Forgets a logged-out account's local files ([dir]: repo, outbox, queued files). With
     * anything [pending], or an outbox that was set aside unreadable, the folder is kept whole:
     * the repo may hold commits the server lacks, and the next login to this account sends them.
     * Returns true when kept.
     */
    fun forgetAccountFiles(dir: File, pending: PendingSync): Boolean {
        val setAside = dir.listFiles { f -> f.name.startsWith("pending-doc-writes.unreadable-") }.orEmpty()
        if (!pending.isEmpty || setAside.isNotEmpty()) return true
        if (dir.exists() && !dir.deleteRecursively()) throw java.io.IOException("could not delete ${dir.path}")
        return false
    }

    /**
     * Waits until nothing is pending while connected, then [quietMs] more so the repo can send
     * the last commits (automerge-java 0.0.9 doesn't tell what the server acknowledged). Returns
     * null once synced, or what is left after [timeoutMs] and why.
     */
    suspend fun awaitSynced(
        projection: StateFlow<ProjectionStatus>,
        status: StateFlow<SyncStatus>,
        timeoutMs: Long,
        quietMs: Long,
    ): LogoutResult.StillPending? {
        val synced = withTimeoutOrNull(timeoutMs) {
            combine(projection, status) { p, s -> PendingSync.of(p).isEmpty && s is SyncStatus.Connected }
                .first { it }
            delay(quietMs)
        } != null
        if (synced) return null
        val p = projection.value
        val reason = when (val s = status.value) {
            !is SyncStatus.Connected -> "not connected to the server" + ((s as? SyncStatus.Offline)?.reason?.let { " ($it)" } ?: "")
            else -> p.blobError ?: p.writeError ?: "still sending"
        }
        return LogoutResult.StillPending(PendingSync.of(p), reason)
    }
}
