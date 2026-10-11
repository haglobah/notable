package com.ethran.notable.sync.automerge

import android.content.Context
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.FolderDao
import com.ethran.notable.data.db.NotebookDao
import com.ethran.notable.data.db.PageDao
import com.ethran.notable.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private val lock = Mutex()
    private var session: Session? = null

    private class Session(val account: StoredAccount, val api: ServerApi, val repo: Repo, var dialer: DialerHandle?)

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
                if (session === s) _status.value = SyncStatus.Connected(account.serverUrl, account.username)
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

    fun backgroundHealth(): BackgroundHealth = BackgroundHealth.of(readBackgroundSignals(context))

    companion object {
        /** Stroke x coordinates span the shorter screen side (editor/drawing/backgrounds.kt). */
        fun canvasWidth(): Int = min(SCREEN_WIDTH, SCREEN_HEIGHT)
    }
}
