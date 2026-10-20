package com.ethran.notable.image

import android.content.Context
import android.os.Build
import com.ethran.notable.BuildConfig
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.sync.automerge.AmLibrary
import com.ethran.notable.sync.automerge.AmSource
import com.ethran.notable.sync.automerge.CodeDocState
import com.ethran.notable.sync.automerge.NotableSyncClient
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.utils.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private val log = ShipBook.getLogger("LiveImage")

/** The page open in the editor, for `notable.page()`. [index] is 0-based. */
data class OpenPage(val id: String, val notebookId: String?, val index: Int, val pageCount: Int)

sealed interface ReplState {
    data object Off : ReplState
    data class Listening(val port: Int, val token: String) : ReplState
    data class Failed(val reason: String) : ReplState
}

/**
 * The live code image on this device (ADR-002): starts the runtime (or safe mode), feeds it the
 * code doc from [NotableSyncClient.code], runs the REPL server, and implements host API v1.
 *
 * Settings live in shared preferences, not [com.ethran.notable.data.datastore.AppSettings]: safe
 * mode must be known before anything of the image runs, and AppSettings loads asynchronously.
 */
@Singleton
class ImageService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sync: NotableSyncClient,
    private val snacks: SnackDispatcher,
    @param:ApplicationScope private val scope: CoroutineScope,
) : EditorImage {
    private val prefs = context.getSharedPreferences("live-image", Context.MODE_PRIVATE)
    private val bootGuard = BootGuard(File(context.filesDir, "image/failed-starts"))
    private val runtime = JsRuntime()
    private val started = AtomicBoolean(false)
    private val loaded = AtomicBoolean(false)
    private val drawn = AtomicBoolean(false)

    /** Set by the editor while a page is open. */
    @Volatile
    override var openPage: OpenPage? = null

    val device = "android:${Build.MODEL}"

    private val host = object : ImageHost {
        override fun log(line: String) = log.i(line)
        override fun snack(text: String) = snacks.showOrUpdateSnack(SnackConf(text = text, duration = 4000))
        override fun libraryJson(): String = libraryView(sync.libraryNow())
        override fun pageJson(): String = openPage?.let { p ->
            buildJsonObject {
                put("id", p.id)
                if (p.notebookId == null) put("notebookId", JsonNull) else put("notebookId", p.notebookId)
                put("index", p.index)
                put("pageCount", p.pageCount)
            }.toString()
        } ?: "null"
    }

    val image = LiveImage(
        runtime = runtime,
        prelude = { context.assets.open("prelude.js").bufferedReader().use { it.readText() } },
        host = host,
        builtIns = listOf(
            BuiltInCommand("command/page-info", "Page info") {
                val p = openPage
                host.snack(if (p == null) "No page is open" else "Page ${p.index + 1} of ${p.pageCount}")
            },
            BuiltInCommand("command/image-status", "Live code status") { s ->
                host.snack("Live code: ${LiveImage.modeText(s.mode)}, ${s.loaded} loaded, ${s.failed} failed")
            },
        ),
    )

    private val _repl = MutableStateFlow<ReplState>(ReplState.Off)
    val repl: StateFlow<ReplState> = _repl.asStateFlow()
    private var replServer: ReplServer? = null

    var safeModeSetting: Boolean
        get() = prefs.getBoolean(KEY_SAFE_MODE, false)
        set(v) = prefs.edit().putBoolean(KEY_SAFE_MODE, v).apply()

    var replSetting: Boolean
        get() = prefs.getBoolean(KEY_REPL, false)
        set(v) {
            prefs.edit().putBoolean(KEY_REPL, v).apply()
            if (v || BuildConfig.DEBUG) startRepl() else stopRepl()
        }

    /** Starts the image once per process, off the main thread. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val failedStarts = bootGuard.begin()
        val safe = if (safeModeSetting) "switched on in settings" else failedStarts
        if (failedStarts != null) log.w("starting in safe mode: $failedStarts")
        scope.launch {
            image.start(safe)
            if (safe != null) markLoaded()
            sync.code.collect { state ->
                when (state) {
                    CodeDocState.Opening -> return@collect
                    CodeDocState.None -> image.apply(emptyMap())
                    is CodeDocState.Ready -> image.apply(state.image.defs)
                    is CodeDocState.Unreadable -> image.codeDocUnreadable(state.reason)
                }
                image.status.value.defs.values.filterIsInstance<DefState.Failed>().forEach { log.w("${it.def.name} failed: ${it.reason}") }
                markLoaded()
            }
        }
        if (BuildConfig.DEBUG || replSetting) startRepl()
    }

    /** MainActivity's first frame. With the image loaded, the start counts as healthy. */
    fun firstScreenDrawn() {
        drawn.set(true)
        if (loaded.get()) bootGuard.healthy()
    }

    private fun markLoaded() {
        if (loaded.compareAndSet(false, true) && drawn.get()) bootGuard.healthy()
    }

    override val commands get() = image.commands

    override fun runCommand(id: String) {
        scope.launch {
            val r = image.runCommand(id)
            if (r is AppResult.Error) {
                log.w("command $id failed: ${r.error.message}")
                snacks.showOrUpdateSnack(SnackConf(text = "Command failed: ${r.error.message}", duration = 6000))
            }
        }
    }

    @Synchronized
    private fun startRepl() {
        if (replServer != null) return
        val token = ReplServer.newToken()
        val server = ReplServer(ReplServer.PORT, ReplProtocol(token, image, sync, device), scope)
        try {
            server.start()
            replServer = server
            _repl.value = ReplState.Listening(ReplServer.PORT, token)
        } catch (e: Exception) {
            log.e("REPL could not start", e)
            _repl.value = ReplState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    @Synchronized
    private fun stopRepl() {
        replServer?.let { s -> Thread { runCatching { s.stop(1000) } }.start() }
        replServer = null
        _repl.value = ReplState.Off
    }

    companion object {
        /** ISO-8601 with milliseconds, as JS `Date.toISOString()` writes it. */
        fun isoMillis(d: java.util.Date): String = ISO_MILLIS.format(d.toInstant())
        private val ISO_MILLIS = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

        private const val KEY_SAFE_MODE = "safeMode"
        private const val KEY_REPL = "repl"

        /** `notable.library()`: the same shape as the PWA's (web/app/src/image/host.ts). */
        fun libraryView(lib: AmLibrary?): String = buildJsonObject {
            put("folders", buildJsonObject {
                lib?.folders?.values?.sortedBy { it.id }?.forEach { f ->
                    put(f.id, buildJsonObject {
                        put("id", f.id); put("title", f.title)
                        if (f.parentId == null) put("parentId", JsonNull) else put("parentId", f.parentId)
                    })
                }
            })
            put("notebooks", buildJsonObject {
                lib?.notebooks?.values?.sortedBy { it.id }?.forEach { n ->
                    put(n.id, buildJsonObject {
                        put("id", n.id); put("title", n.title)
                        if (n.folderId == null) put("folderId", JsonNull) else put("folderId", n.folderId)
                        put("kind", n.kind.key)
                        put("source", when (n.source) { AmSource.None -> "none"; is AmSource.Pdf -> "pdf"; is AmSource.Epub -> "epub" })
                        put("updatedAt", isoMillis(n.updatedAt))
                    })
                }
            })
        }.toString()
    }
}
