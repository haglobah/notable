package com.ethran.notable.sync.automerge

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Log out now; what waits stays on this device and goes up at the next login to the same account. */
    Anyway,
}

sealed interface LogoutResult {
    /** Still logged in: [pending] would not reach the server. [reason] says why, if known. */
    data class StillPending(val pending: PendingSync, val reason: String?) : LogoutResult

    /**
     * Logged out. The device library stays (ADR-001, local-only mode). [kept] edits and files did
     * not reach the server; they go up at the next login to the same account.
     */
    data class LoggedOut(val serverWarning: String?, val kept: PendingSync) : LogoutResult
}

/** Logout must never leave edits or files that wait for sync behind without saying so. */
object LogoutGuard {
    fun mayEnd(choice: LogoutChoice, pending: PendingSync): Boolean =
        pending.isEmpty || choice == LogoutChoice.Anyway

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
