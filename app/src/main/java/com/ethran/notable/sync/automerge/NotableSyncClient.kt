package com.ethran.notable.sync.automerge

import android.content.Context
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
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
    @param:ApplicationScope private val scope: CoroutineScope,
) {
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
    private var session: Session? = null

    private class Session(val account: StoredAccount, val api: ServerApi, val repo: Repo, var dialer: DialerHandle?) {
        var projectionJob: Job? = null
        val queue = Channel<String>(Channel.UNLIMITED)
        val handles = java.util.concurrent.ConcurrentHashMap<String, DocHandle>()
        val listeners = java.util.concurrent.ConcurrentHashMap<String, ListenerRegistration>()
        var libraryUrl: String? = null
        var projector: AutomergeProjector? = null
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
     * Ends the session on the server and forgets it here, including this account's local repo.
     * Returns a warning when the server could not be told; the local token is gone either way.
     */
    suspend fun logout(): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = session
            val warning = s?.let {
                runCatching { it.api.logout(it.account.token) }.exceptionOrNull()
                    ?.let { e -> "The server could not be reached, so its session stays valid until it expires: ${e.message}" }
            }
            disconnect()
            s?.let { accountDir(it.account).deleteRecursively() }
            accounts.clear()
            _status.value = SyncStatus.SignedOut
            _migration.value = MigrationState.Idle
            warning
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

    private suspend fun runMigration(): MigrationReport {
        val s = lock.withLock { session } ?: throw IllegalStateException("Log in first")
        withTimeoutOrNull(30_000) { status.first { it is SyncStatus.Connected || it is SyncStatus.AuthFailed } }
        when (val st = status.value) {
            is SyncStatus.Connected -> Unit
            is SyncStatus.AuthFailed -> throw ServerError.Unauthorized()
            else -> throw IllegalStateException("Not connected to the server (${st.javaClass.simpleName})")
        }
        val me = s.api.me(s.account.token)
        val library = findWithRetry(s.repo, me.libraryDocUrl)
            ?: throw IllegalStateException("The server did not send the library document")
        val migration = RoomToAutomergeMigration(
            local = RoomLocalLibrary(folderDao, notebookDao, pageDao),
            blobs = UploadingBlobStore(s.api, s.account.token, androidFileOpener(context)),
            docs = RepoDocStore(s.repo, library),
            state = MigrationStateStore(File(accountDir(s.account), "migration.json")),
            canvasWidth = canvasWidth().toDouble(),
        )
        return migration.run { _migration.value = MigrationState.Running(it) }
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
        val dir = File(accountDir(account), "repo").apply { mkdirs() }
        val repo = Repo.load(RepoConfig.builder().storage(FileSystemStorage(dir.toPath())).build())
        val api = ServerApi(http, ServerApi.parseServerUrl(account.serverUrl))
        val s = Session(account, api, repo, null)
        session = s
        _status.value = SyncStatus.Connecting(account.serverUrl, account.username)
        val dialer = OkHttpDialer(http, ServerApi.syncUrl(api.baseUrl), account.token, object : OkHttpDialer.Listener {
            override fun onOpen() {
                if (session !== s) return
                _status.value = SyncStatus.Connected(account.serverUrl, account.username)
                startProjection(s)
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

    /** Must be called with [lock] held. */
    private fun disconnect() {
        val s = session ?: return
        session = null
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
                val me = s.api.me(s.account.token)
                s.libraryUrl = me.libraryDocUrl
                s.projector = AutomergeProjector(
                    sink = RoomProjectionSink(folderDao, notebookDao, pageDao, strokeDao, imageDao),
                    files = DownloadingProjectionFiles(
                        download = { sha, dest -> s.api.getBlob(s.account.token, sha, dest) },
                        dir = File(getDbDir(), "sync-blobs").apply { mkdirs() },
                        open = androidFileOpener(context),
                    ),
                    state = ProjectionStateStore(File(accountDir(s.account), "projection.json")),
                    localCanvasWidth = canvasWidth().toDouble(),
                )
                follow(s, me.libraryDocUrl)
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
                val result = projector.apply(plan)
                result.follow.forEach { e ->
                    runCatching { follow(s, e.docUrl) }.onFailure { err ->
                        _projection.value = _projection.value.copy(errors = _projection.value.errors + (e.docUrl to "${e.title}: ${err.message}"))
                    }
                }
                warn(result.warnings)
            } else {
                val plan = handle.withDocument { projector.prepareNotebook(it, url) }.get(60, TimeUnit.SECONDS)
                val result = projector.apply(plan)
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
        /** Stroke x coordinates span the shorter screen side (editor/drawing/backgrounds.kt). */
        fun canvasWidth(): Int = min(SCREEN_WIDTH, SCREEN_HEIGHT)
    }
}
