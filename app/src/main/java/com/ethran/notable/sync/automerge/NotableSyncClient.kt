package com.ethran.notable.sync.automerge

import android.content.Context
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.ContentMirror
import com.ethran.notable.data.LocalChange
import com.ethran.notable.data.PendingLocalWrites
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.RemoteEdits
import com.ethran.notable.data.db.FolderDao
import com.ethran.notable.data.db.ImageDao
import com.ethran.notable.data.db.NotebookDao
import com.ethran.notable.data.db.PageDao
import com.ethran.notable.data.db.StrokeDao
import com.ethran.notable.data.getDbDir
import com.ethran.notable.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.automerge.repo.AutomergeUrl
import org.automerge.repo.DialerConfig
import org.automerge.repo.DialerHandle
import org.automerge.repo.DocHandle
import org.automerge.repo.ListenerRegistration
import org.automerge.repo.Repo
import org.automerge.repo.RepoConfig
import org.automerge.repo.storage.FileSystemStorage
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

private val log = ShipBook.getLogger("NotableSync")

/** What the sync settings show about the connection. */
sealed interface SyncStatus {
    data object SignedOut : SyncStatus
    /** The stored account can't be read (e.g. the Keystore key is gone). Log in again. */
    data class AccountUnreadable(val reason: String) : SyncStatus
    data class Connecting(val server: String, val username: String) : SyncStatus
    data class Connected(val server: String, val username: String) : SyncStatus
    data class Offline(val server: String, val username: String, val reason: String) : SyncStatus
    /** The server refused the session. Sync stopped; log in again. */
    data class AuthFailed(val server: String, val username: String) : SyncStatus
}

/** What the projection (server → this device) is doing; shown in the sync settings. */
data class ProjectionStatus(
    val running: Boolean = false,
    val docs: Int = 0,
    val lastAppliedAt: Long? = null,
    /** docUrl (or "library") → why its last projection failed. Retried on the next change. */
    val errors: Map<String, String> = emptyMap(),
    val warnings: List<String> = emptyList(),
    /** Why the last local edit did not reach the docs; null once one did. */
    val writeError: String? = null,
    /** Local edits in Room that wait to be written to the docs; retried until they are. */
    val pendingWrites: Int = 0,
    /** Files (images, PDFs) copied for sync that wait to be uploaded. */
    val pendingBlobs: Int = 0,
    /** Why the last upload of waiting files failed; null once one succeeded. */
    val blobError: String? = null,
    /** Pen-up → doc commit on disk, over the last edits. */
    val commitLatency: LatencySummary? = null,
)

sealed interface MigrationState {
    data object Idle : MigrationState
    data class Running(val progress: MigrationProgress) : MigrationState
    data class Done(val report: MigrationReport) : MigrationState
    data class Failed(val message: String) : MigrationState
}

/**
 * The Automerge sync client (ADR-001): the account, the local repo, and its WebSocket to the
 * server. One per process. The repo keeps docs on disk under `files/notable-sync/<account>/`.
 *
 * It is also the [ContentMirror]: editor writes go into the docs first (see [DocFirstMirror]).
 * With a stored account that has synced once, that works offline from launch; without one,
 * edits go to Room only.
 */
@Singleton
class NotableSyncClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    crypto: CryptoHelper,
    private val folderDao: FolderDao,
    private val notebookDao: NotebookDao,
    private val pageDao: PageDao,
    private val strokeDao: StrokeDao,
    private val imageDao: ImageDao,
    private val pendingWrites: PendingLocalWrites,
    @param:ApplicationScope private val scope: CoroutineScope,
) : ContentMirror {
    private val root = File(context.filesDir, "notable-sync")
    private val accounts = AccountStore(File(root, "account.json"), KeystoreCipher(crypto))
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES) // large blob uploads
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.SignedOut)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val _migration = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val migration: StateFlow<MigrationState> = _migration.asStateFlow()

    private val _projection = MutableStateFlow(ProjectionStatus())
    val projection: StateFlow<ProjectionStatus> = _projection.asStateFlow()

    private val lock = Mutex()
    @Volatile
    private var session: Session? = null

    private val commitLatency = LatencyStats()
    private val mirror = DocFirstMirror(
        writer = { session?.writer },
        outbox = { session?.outbox },
        report = ::onWriteResult,
        latency = commitLatency,
    )

    private class Session(
        val account: StoredAccount, val api: ServerApi, val repo: Repo, val storage: DurableStorage, dir: File,
        /** Edits in Room that wait for the docs; see [DocFirstMirror]. */
        val outbox: DocOutbox,
        /** Files copied for sync that wait for the server. */
        val blobs: QueuedBlobStore,
        /** Wakes [backgroundJob] early. */
        val wake: Channel<Unit>,
    ) {
        var dialer: DialerHandle? = null
        var projectionJob: Job? = null
        /** Retries [outbox] and uploads [blobs]; see [startBackground]. */
        var backgroundJob: Job? = null
        val queue = Channel<String>(Channel.UNLIMITED)
        val handles = java.util.concurrent.ConcurrentHashMap<String, DocHandle>()
        val listeners = java.util.concurrent.ConcurrentHashMap<String, ListenerRegistration>()
        val libraryUrlCache = LibraryUrlCache(File(dir, "library-url"))
        /** One per session, shared by the writer (settle) and the projector. */
        val projectionState = ProjectionStateStore(File(dir, "projection.json"))
        val migrationState = MigrationStateStore(File(dir, "migration.json"))
        @Volatile var libraryUrl: String? = null
        @Volatile var writer: LocalDocWriter? = null
        var projector: AutomergeProjector? = null
    }

    override suspend fun <T> write(change: LocalChange, queuedAtNanos: Long?, room: suspend () -> T): T =
        mirror.write(change, queuedAtNanos, room)

    override suspend fun <T> create(change: LocalChange, room: suspend () -> T): T = mirror.create(change, room)

    private var latencyLoggedAt = 0L

    private fun onWriteResult(error: String?) {
        val latency = commitLatency.summary()
        val before = _projection.getAndUpdate { it.copy(writeError = error, commitLatency = latency) }
        if (error != null && error != before.writeError) log.e(error)
        val recorded = commitLatency.recorded()
        if (latency != null && recorded >= latencyLoggedAt + 100) {
            latencyLoggedAt = recorded
            log.i("pen-up to commit: p50 ${latency.p50Ms} ms, max ${latency.maxMs} ms over the last ${latency.count}")
        }
    }

    /** Connects with the stored account, if any. Safe to call more than once. */
    fun start() {
        scope.launch {
            lock.withLock {
                if (session != null) return@withLock
                when (val load = accounts.load()) {
                    AccountLoad.None -> _status.value = SyncStatus.SignedOut
                    is AccountLoad.Unreadable -> _status.value = SyncStatus.AccountUnreadable(load.reason)
                    is AccountLoad.Present -> connect(load.account)
                }
            }
        }
    }

    /** @throws ServerError or IllegalArgumentException (bad server address), for the UI to show. */
    suspend fun login(serverUrl: String, username: String, password: String) = withContext(Dispatchers.IO) {
        val base = ServerApi.parseServerUrl(serverUrl)
        val api = ServerApi(http, base)
        val result = api.login(username.trim(), password)
        api.me(result.token) // fails fast if the session is unusable
        lock.withLock {
            disconnect()
            val account = StoredAccount(base.toString(), result.username, result.token)
            accounts.save(account)
            connect(account)
        }
    }

    /**
     * Ends the session on the server and forgets it here. Edits and files that wait for sync are
     * never dropped silently ([LogoutGuard]): with any pending, [LogoutChoice.IfNothingPending]
     * stays logged in and says what waits; [LogoutChoice.WaitForSync] syncs first;
     * [LogoutChoice.Anyway] logs out and keeps them (Room, and the account folder for the next
     * login to this account). Without anything pending, the account's local repo is deleted.
     */
    suspend fun logout(choice: LogoutChoice = LogoutChoice.IfNothingPending): LogoutResult = withContext(Dispatchers.IO) {
        if (choice == LogoutChoice.WaitForSync) {
            session?.wake?.trySend(Unit)
            LogoutGuard.awaitSynced(projection, status, WAIT_FOR_SYNC_MS, QUIET_MS)?.let { return@withContext it }
        }
        lock.withLock {
            val pending = PendingSync.of(_projection.value)
            if (!LogoutGuard.mayEnd(choice, pending)) return@withLock LogoutResult.StillPending(pending, null)
            val s = session
            val warning = s?.let {
                runCatching { it.api.logout(it.account.token) }.exceptionOrNull()
                    ?.let { e -> "The server could not be reached, so its session stays valid until it expires: ${e.message}" }
            }
            disconnect()
            val kept = s?.let { LogoutGuard.forgetAccountFiles(accountDir(it.account), pending) } ?: false
            if (kept) log.w("logged out with ${pending.writes} edits and ${pending.files} files not synced; kept in ${s?.let { accountDir(it.account) }}")
            accounts.clear()
            _status.value = SyncStatus.SignedOut
            _migration.value = MigrationState.Idle
            LogoutResult.LoggedOut(warning, if (kept) pending else PendingSync(0, 0))
        }
    }

    /** Copies the Room library into Automerge docs; see [RoomToAutomergeMigration]. */
    fun migrate() {
        if (_migration.value is MigrationState.Running) return
        scope.launch(Dispatchers.IO) {
            _migration.value = MigrationState.Running(MigrationProgress(0, 0, null))
            _migration.value = try {
                MigrationState.Done(runMigration())
            } catch (e: Exception) {
                log.e("migration failed", e)
                MigrationState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Runs the session writer's migration: durable through [RepoLocalDocs], and under the same
     * lock as the writer's on-demand migration of a notebook, so no notebook gets two docs.
     */
    private suspend fun runMigration(): MigrationReport {
        val s = lock.withLock { session } ?: throw IllegalStateException("Log in first")
        withTimeoutOrNull(30_000) { status.first { it is SyncStatus.Connected || it is SyncStatus.AuthFailed } }
        when (val st = status.value) {
            is SyncStatus.Connected -> Unit
            is SyncStatus.AuthFailed -> throw ServerError.Unauthorized()
            else -> throw IllegalStateException("Not connected to the server (${st.javaClass.simpleName})")
        }
        val me = s.api.me(s.account.token)
        val writer = withTimeoutOrNull(60_000) {
            while (s.writer == null && session === s) delay(200)
            s.writer
        } ?: throw IllegalStateException("The library document has not arrived from the server yet")
        if (s.libraryUrl != me.libraryDocUrl)
            throw IllegalStateException("This device's library (${s.libraryUrl}) is not the server's (${me.libraryDocUrl}); log out and in again")
        return writer.migrateAll { _migration.value = MigrationState.Running(it) }
    }

    /** A doc not yet stored here arrives over the network; the first find may come back empty. */
    private suspend fun findWithRetry(repo: Repo, url: String) = withContext(Dispatchers.IO) {
        val parsed = AutomergeUrl.parse(url)
        repeat(10) { attempt ->
            val found = repo.find(parsed).get(30, TimeUnit.SECONDS)
            if (found.isPresent) return@withContext found.get()
            kotlinx.coroutines.delay(500L * (attempt + 1))
        }
        null
    }

    /** Must be called with [lock] held. */
    private fun connect(account: StoredAccount) {
        val accountDir = accountDir(account)
        val dir = File(accountDir, "repo").apply { mkdirs() }
        val storage = DurableStorage(FileSystemStorage(dir.toPath()))
        val repo = Repo.load(RepoConfig.builder().storage(storage).build())
        val api = ServerApi(http, ServerApi.parseServerUrl(account.serverUrl))
        val wake = Channel<Unit>(Channel.CONFLATED)
        val blobs = QueuedBlobStore(File(accountDir, "blobs"), androidFileOpener(context)) { n ->
            _projection.update { it.copy(pendingBlobs = n) }
            if (n > 0) wake.trySend(Unit)
        }
        val s = Session(account, api, repo, storage, accountDir, loadOutbox(accountDir), blobs, wake)
        session = s
        _projection.update { it.copy(pendingWrites = s.outbox.size, pendingBlobs = blobs.pendingCount()) }
        startBackground(s)
        _status.value = SyncStatus.Connecting(account.serverUrl, account.username)
        // Known from an earlier session: write and project from the local repo, before (or without) a connection.
        val cached = try {
            s.libraryUrlCache.load()
        } catch (e: java.io.IOException) {
            log.e("library URL cache unreadable; waiting for the server", e)
            null
        }
        if (cached != null) {
            useLibrary(s, cached)
            startProjection(s)
        }
        val dialer = OkHttpDialer(http, ServerApi.syncUrl(api.baseUrl), account.token, object : OkHttpDialer.Listener {
            override fun onOpen() {
                if (session !== s) return
                _status.value = SyncStatus.Connected(account.serverUrl, account.username)
                startProjection(s)
                s.wake.trySend(Unit)
            }

            override fun onClosed(reason: String) {
                if (session === s && _status.value !is SyncStatus.AuthFailed)
                    _status.value = SyncStatus.Offline(account.serverUrl, account.username, reason)
            }

            override fun onAuthFailed() {
                if (session !== s) return
                log.w("sync server rejected the session; stopping")
                _status.value = SyncStatus.AuthFailed(account.serverUrl, account.username)
                scope.launch { lock.withLock { if (session === s) s.dialer?.close() } }
            }
        })
        s.dialer = repo.dial(dialer, DialerConfig.defaults())
    }

    /** The library doc is known: editor writes go to the docs from now on. */
    private fun useLibrary(s: Session, url: String) {
        s.libraryUrl = url
        s.writer = LocalDocWriter(
            docs = RepoLocalDocs(s.repo, s.storage, url),
            local = RoomLocalLibrary(folderDao, notebookDao, pageDao),
            blobs = s.blobs,
            migrationState = s.migrationState,
            projection = s.projectionState,
            canvasWidth = canvasWidth().toDouble(),
        )
        s.wake.trySend(Unit)
    }

    /**
     * The session's edits for the docs: `pending-doc-writes.json`. An unreadable file is moved
     * aside and reported: its edits are in Room, and can't be told apart from synced ones anymore.
     */
    private fun loadOutbox(accountDir: File): DocOutbox {
        val file = File(accountDir, "pending-doc-writes.json")
        val onChange: (Int) -> Unit = { n -> _projection.update { it.copy(pendingWrites = n) } }
        return try {
            DocOutbox(file, pendingWrites, onChange)
        } catch (e: Exception) {
            val aside = File(accountDir, "pending-doc-writes.unreadable-${System.currentTimeMillis()}.json")
            file.renameTo(aside)
            val why = "Edits waiting for sync could not be read back (${e.message}); they are on this device only. Kept in ${aside.name}"
            log.e(why, e)
            _projection.update { it.copy(writeError = why) }
            DocOutbox(file, pendingWrites, onChange)
        }
    }

    /**
     * Retries the outbox and uploads waiting files: at session start, when woken (a file queued,
     * the writer ready, the server back), and every [RETRY_MS] while anything waits.
     */
    private fun startBackground(s: Session) {
        s.backgroundJob = scope.launch(Dispatchers.IO) {
            while (true) {
                if (s.outbox.size > 0 && s.writer != null) try {
                    mirror.retryPending()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e("retrying edits for sync failed", e)
                }
                if (s.blobs.pendingCount() > 0 && status.value is SyncStatus.Connected) uploadBlobs(s)
                withTimeoutOrNull(RETRY_MS) { s.wake.receive() }
            }
        }
    }

    private fun uploadBlobs(s: Session) {
        try {
            val r = s.blobs.drain({ sha, size, open -> s.api.putBlob(s.account.token, sha, size, open) })
            _projection.update { it.copy(blobError = null) }
            warn(r.rejected.map { "File ${it.sha.take(12)} stays on this device and won't sync: ${it.reason}" })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _projection.update { it.copy(blobError = e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Must be called with [lock] held. */
    private fun disconnect() {
        val s = session ?: return
        session = null
        s.writer = null
        s.backgroundJob?.cancel()
        s.outbox.release()
        s.projectionJob?.cancel()
        s.listeners.values.forEach { runCatching { it.remove() } }
        s.listeners.clear()
        _projection.value = ProjectionStatus()
        runCatching { s.dialer?.close() }
        runCatching { s.repo.close() }
    }

    /** One folder per server+user, so a second account never sees the first one's docs. */
    private fun accountDir(account: StoredAccount): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest("${account.serverUrl}\n${account.username}".toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        return File(root, key)
    }

    /**
     * Server → Room (plan Phase 3 part B). One worker per session applies queued docs in order;
     * change listeners only enqueue, since automerge-java forbids blocking inside them. On every
     * (re)connect all known docs are re-queued, which applies whatever arrived while offline.
     */
    private fun startProjection(s: Session) {
        if (s.projectionJob?.isActive == true) {
            s.handles.keys.forEach { s.queue.trySend(it) }
            return
        }
        s.projectionJob = scope.launch(Dispatchers.IO) {
            try {
                val libraryUrl = s.libraryUrl ?: s.api.me(s.account.token).libraryDocUrl.also { url ->
                    s.libraryUrlCache.save(url)
                    if (session === s) useLibrary(s, url)
                }
                s.projector = AutomergeProjector(
                    sink = RoomProjectionSink(folderDao, notebookDao, pageDao, strokeDao, imageDao),
                    files = DownloadingProjectionFiles(
                        // A file of this device's not uploaded yet is still in the queue.
                        download = { sha, dest -> if (!s.blobs.copyQueued(sha, dest)) s.api.getBlob(s.account.token, sha, dest) },
                        dir = File(getDbDir(), "sync-blobs").apply { mkdirs() },
                        open = androidFileOpener(context),
                    ),
                    state = s.projectionState,
                    localCanvasWidth = canvasWidth().toDouble(),
                    localPending = pendingWrites::contains,
                )
                follow(s, libraryUrl)
                for (url in s.queue) {
                    drainInto(s.queue, url).forEach { project(s, it) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e("projection stopped", e)
                _projection.value = _projection.value.copy(running = false,
                    errors = _projection.value.errors + ("library" to (e.message ?: e.javaClass.simpleName)))
            }
        }
    }

    /** Coalesces a burst of change events: each doc once, library first. */
    private suspend fun drainInto(queue: Channel<String>, first: String): List<String> {
        delay(250)
        val urls = LinkedHashSet<String>().apply { add(first) }
        while (true) urls += queue.tryReceive().getOrNull() ?: break
        val s = session
        return urls.sortedBy { if (it == s?.libraryUrl) 0 else 1 }
    }

    /** Finds [url] in the repo, listens for its changes, and queues a first projection. */
    private suspend fun follow(s: Session, url: String) {
        if (s.handles.containsKey(url)) return
        val handle = findWithRetry(s.repo, url) ?: throw IllegalStateException("the server did not send $url")
        if (s.handles.putIfAbsent(url, handle) != null) return
        s.listeners[url] = handle.addChangeListener { s.queue.trySend(url) }
        s.queue.trySend(url)
    }

    private suspend fun project(s: Session, url: String) {
        val projector = s.projector ?: return
        val handle = s.handles[url] ?: return
        _projection.value = _projection.value.copy(running = true, docs = s.handles.size)
        val key = if (url == s.libraryUrl) "library" else url
        try {
            if (url == s.libraryUrl) {
                val plan = handle.withDocument { projector.prepareLibrary(it, url) }.get(60, TimeUnit.SECONDS)
                val result = mirror.exclusive { projector.apply(plan) }
                result.follow.forEach { e ->
                    runCatching { follow(s, e.docUrl) }.onFailure { err ->
                        _projection.value = _projection.value.copy(errors = _projection.value.errors + (e.docUrl to "${e.title}: ${err.message}"))
                    }
                }
                warn(result.warnings)
            } else {
                val plan = handle.withDocument { projector.prepareNotebook(it, url) }.get(60, TimeUnit.SECONDS)
                val result = mirror.exclusive { projector.apply(plan) }
                result.edits.forEach { dropPagePreviews(it.pageId) }
                if (!RemoteEdits.publish(result.edits)) log.w("editor cache missed ${result.edits.size} remote edits; pages reload on open")
                warn(result.warnings)
            }
            _projection.value = _projection.value.copy(
                running = false, docs = s.handles.size, lastAppliedAt = System.currentTimeMillis(),
                errors = _projection.value.errors - key,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            log.e("projecting $url failed", cause)
            _projection.value = _projection.value.copy(running = false,
                errors = _projection.value.errors + (key to (cause.message ?: cause.javaClass.simpleName)))
            // Its heads were not advanced; try again later even if nothing else changes.
            scope.launch { delay(30_000); if (session === s) s.queue.trySend(url) }
        }
    }

    /**
     * Saved previews count as fresh while newer than `page.updatedAt`, and synced rows carry the
     * other device's timestamps (a stroke added elsewhere doesn't even bump them). A remotely
     * edited page's previews would show the old content, so they go; they're redrawn on open.
     */
    private fun dropPagePreviews(pageId: String) {
        for (dir in listOf("pages/previews/full", "pages/previews/thumbs")) {
            File(context.filesDir, dir).listFiles { f -> f.name == pageId || f.name.startsWith("$pageId-") || f.name.startsWith("$pageId.") }
                ?.forEach { if (!it.delete()) log.w("could not delete stale preview ${it.path}") }
        }
    }

    private fun warn(w: List<String>) {
        if (w.isEmpty()) return
        w.forEach { log.w(it) }
        _projection.value = _projection.value.copy(warnings = (_projection.value.warnings + w).takeLast(20))
    }

    /** Debug: forget what was projected and apply every doc again in full (upsert only). */
    fun rebuildFromServer() {
        scope.launch {
            val s = lock.withLock { session } ?: return@launch
            s.projector?.forgetAll()
            s.handles.keys.forEach { s.queue.trySend(it) }
        }
    }

    fun backgroundHealth(): BackgroundHealth = BackgroundHealth.of(readBackgroundSignals(context))

    companion object {
        private const val RETRY_MS = 15_000L
        /** How long "wait and sync" at logout waits for pending edits and files. */
        private const val WAIT_FOR_SYNC_MS = 120_000L
        /** After nothing is pending, time for the repo to send its last commits. */
        private const val QUIET_MS = 3_000L

        /** Stroke x coordinates span the shorter screen side (editor/drawing/backgrounds.kt). */
        fun canvasWidth(): Int = min(SCREEN_WIDTH, SCREEN_HEIGHT)
    }
}
