package com.ethran.notable.sync.automerge

import com.ethran.notable.data.LocalChange
import com.ethran.notable.data.PendingLocalWrites
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogoutGuardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val connected = SyncStatus.Connected("https://s", "u")

    @Test
    fun `logout with nothing pending goes ahead`() {
        assertTrue(LogoutGuard.mayEnd(LogoutChoice.IfNothingPending, PendingSync(0, 0)))
    }

    @Test
    fun `logout with pending writes or files stops, unless chosen anyway`() {
        for (p in listOf(PendingSync(1, 0), PendingSync(0, 2))) {
            assertFalse(LogoutGuard.mayEnd(LogoutChoice.IfNothingPending, p))
            assertFalse(LogoutGuard.mayEnd(LogoutChoice.WaitForSync, p))
            assertTrue(LogoutGuard.mayEnd(LogoutChoice.Anyway, p))
        }
    }

    @Test
    fun `a logout with nothing pending deletes the account folder`() {
        val dir = accountDir()
        val kept = LogoutGuard.forgetAccountFiles(dir, PendingSync(0, 0))
        assertFalse(kept)
        assertFalse(dir.exists())
    }

    @Test
    fun `a logout anyway keeps the waiting edits and files for the next login`() {
        val dir = accountDir()
        val outbox = File(dir, "pending-doc-writes.json")
        DocOutbox(outbox, PendingLocalWrites()).record(LocalChange.StrokesDeleted("p1", listOf("s1")))
        val blobs = QueuedBlobStore(File(dir, "blobs"), { File(it).inputStream() })
        val img = tmp.newFile("img.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        blobs.upload(img.path)

        val kept = LogoutGuard.forgetAccountFiles(dir, PendingSync(1, 1))

        assertTrue(kept)
        assertEquals(listOf(DocDebt.Strokes("p1", setOf("s1"))), DocOutbox(outbox, PendingLocalWrites()).debts())
        assertEquals(1, QueuedBlobStore(File(dir, "blobs"), { File(it).inputStream() }).pendingCount())
        assertTrue("the repo holds commits the server may not have yet", File(dir, "repo/doc").isFile)
    }

    @Test
    fun `an unreadable outbox set aside is never deleted by a logout`() {
        val dir = accountDir()
        File(dir, "pending-doc-writes.unreadable-1.json").writeText("garbage")
        assertTrue(LogoutGuard.forgetAccountFiles(dir, PendingSync(0, 0)))
        assertTrue(File(dir, "pending-doc-writes.unreadable-1.json").isFile)
    }

    @Test
    fun `waiting for sync ends once nothing is pending while connected`() = runBlocking {
        val projection = MutableStateFlow(ProjectionStatus(pendingWrites = 2, pendingBlobs = 1))
        val status = MutableStateFlow<SyncStatus>(SyncStatus.Offline("https://s", "u", "down"))
        launch {
            delay(50)
            projection.value = ProjectionStatus(pendingWrites = 0, pendingBlobs = 1)
            delay(50)
            projection.value = ProjectionStatus(pendingWrites = 0, pendingBlobs = 0)
            delay(50)
            status.value = connected // nothing counts as sent while offline
        }
        assertNull(LogoutGuard.awaitSynced(projection, status, timeoutMs = 5_000, quietMs = 10))
    }

    @Test
    fun `waiting for sync gives up after the timeout and says what is left`() = runBlocking {
        val projection = MutableStateFlow(ProjectionStatus(pendingWrites = 0, pendingBlobs = 3))
        val status = MutableStateFlow<SyncStatus>(connected)
        val left = LogoutGuard.awaitSynced(projection, status, timeoutMs = 100, quietMs = 10)
        assertEquals(PendingSync(0, 3), left?.pending)
    }

    @Test
    fun `nothing pending but offline is not synced`() = runBlocking {
        val projection = MutableStateFlow(ProjectionStatus())
        val status = MutableStateFlow<SyncStatus>(SyncStatus.Offline("https://s", "u", "no network"))
        val left = LogoutGuard.awaitSynced(projection, status, timeoutMs = 100, quietMs = 10)
        assertEquals(PendingSync(0, 0), left?.pending)
        assertEquals("not connected to the server (no network)", left?.reason)
    }

    private fun accountDir(): File = tmp.newFolder("acct").apply {
        File(this, "repo").mkdirs()
        File(this, "repo/doc").writeText("x")
        File(this, "library-url").writeText("automerge:abc")
    }
}
