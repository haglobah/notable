package com.ethran.notable

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.PageSaver
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.StrokeMigrationHelper
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.image.ImageService
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.navigation.DeepLink
import com.ethran.notable.navigation.DeepLinkResolver
import com.ethran.notable.navigation.parseDeepLink
import com.ethran.notable.ui.AppEventUiBridge
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.components.NotableApp
import com.ethran.notable.ui.components.crashLogsAsText
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.utils.SteadyRotationController
import com.ethran.notable.utils.hasUsableStorage
import com.onyx.android.sdk.api.device.epd.EpdController
import dagger.hilt.android.AndroidEntryPoint
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject


private const val TAG = "MainActivity"
const val APP_SETTINGS_KEY = "APP_SETTINGS"
const val PACKAGE_NAME = BuildConfig.APPLICATION_ID

// TODO: Check if migrating to LocalConfiguration in Compose is good idea
var SCREEN_WIDTH = EpdController.getEpdHeight().toInt()
var SCREEN_HEIGHT = EpdController.getEpdWidth().toInt()

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Delay the init till we have the permissions required
    @Inject
    lateinit var kvProxy: dagger.Lazy<KvProxy>

    @Inject
    lateinit var strokeMigrationHelper: dagger.Lazy<StrokeMigrationHelper>

    @Inject
    lateinit var editorSettingCacheManager: dagger.Lazy<EditorSettingCacheManager>

    @Inject
    lateinit var appRepositoryLazy: dagger.Lazy<AppRepository>

    @Inject
    lateinit var exportEngineLazy: dagger.Lazy<ExportEngine>

    @Inject
    lateinit var pageDataManager: dagger.Lazy<PageDataManager>

    @Inject
    lateinit var pageSaver: dagger.Lazy<PageSaver>

    @Inject
    lateinit var deepLinkResolver: dagger.Lazy<DeepLinkResolver>

    @Inject
    lateinit var snackDispatcher: SnackDispatcher

    @Inject
    lateinit var appEventUiBridge: AppEventUiBridge

    @Inject
    lateinit var imageService: ImageService

    /** Links waiting for the UI; only the latest is kept. */
    private val deepLinks = Channel<DeepLink>(Channel.CONFLATED)

    // One flow for the activity: a new one on each composition would restart its collector.
    private val deepLinkFlow = deepLinks.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableFullScreen()
        ShipBook.start(
            this.application, BuildConfig.SHIPBOOK_APP_ID, BuildConfig.SHIPBOOK_APP_KEY
        )
        // ShipBook installs its own uncaught handler in start(); re-install ours on top so it is
        // outermost and still writes the durable crash file even if ShipBook doesn't chain back,
        // and flush any startup telemetry (crash-loop signal) that predated ShipBook being up.
        (application as? NotableApp)?.onShipBookStarted()
        maybeShowCrashLoopHint()

        Log.i(TAG, "Notable started")

        SCREEN_WIDTH = applicationContext.resources.displayMetrics.widthPixels
        SCREEN_HEIGHT = applicationContext.resources.displayMetrics.heightPixels

        trackSystemGestureBlocking()
        trackSteadyRotation()

        // A recreated activity gets its launching intent again; that link was already opened.
        if (savedInstanceState == null) receiveDeepLink(intent)

        val snackState = SnackState()

        setContent {
            var isInitialized by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                if (hasUsableStorage(this@MainActivity)) {
                    withContext(Dispatchers.IO) {
                        // Init app settings, also do migration
                        val savedSettings =
                            kvProxy.get().getOrDefault(
                                APP_SETTINGS_KEY,
                                AppSettings.serializer(),
                                AppSettings(version = 1)
                            )

                        GlobalAppSettings.update(savedSettings)
                        strokeMigrationHelper.get().reencodeStrokePointsToBinary()
                        pageDataManager.get()
                            .registerComponentCallbacks(this@MainActivity.applicationContext)
                        editorSettingCacheManager.get().init()
                    }
                }
                isInitialized = true
            }
            // Safe mode's start counter (ADR-002): the first screen is up.
            LaunchedEffect(isInitialized) {
                if (isInitialized) imageService.firstScreenDrawn()
            }
            InkaTheme {
                CompositionLocalProvider(LocalSnackContext provides snackState) {
                    if (isInitialized) {
                        NotableApp(
                            // Call .get() here so they are only instantiated AFTER the permission check runs
                            exportEngine = exportEngineLazy.get(),
                            snackState = snackState,
                            snackDispatcher = snackDispatcher,
                            appRepository = appRepositoryLazy.get(),
                            deepLinks = deepLinkFlow,
                            deepLinkResolver = deepLinkResolver.get(),
                        )
                    } else {
                        ShowInitMessage()
                    }
                }
            }
        }
    }


    /**
     * If startup detected a likely crash loop, show one dismissible snack with a "Copy logs" action
     * that copies the local crash files to the clipboard. Simplest possible surface — the full
     * viewer lives in Settings → Debug. Returns whether a loop was detected (so a test
     * harness can crash only on the non-recovery launch).
     */
    private fun maybeShowCrashLoopHint(): Boolean {
        if ((application as? NotableApp)?.consumeCrashLoopDetected() != true) return false
        snackDispatcher.showOrUpdateSnack(
            SnackConf(
                text = "Notable restarted after repeated crashes.",
                duration = null, // stays until dismissed
                actions = listOf(
                    "Copy logs" to {
                        val text = crashLogsAsText(this)
                        getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Notable crash logs", text))
                        Toast.makeText(this, "Copied crash logs", Toast.LENGTH_SHORT).show()
                    }
                )
            )
        )
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveDeepLink(intent)
    }

    private fun receiveDeepLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        // Reopened from recents: the link that started the task was already opened.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uri = intent.dataString ?: return
        val link = parseDeepLink(uri)
        if (link == null) {
            Log.w(TAG, "Not a Notable link: $uri")
            snackDispatcher.showOrUpdateSnack(
                SnackConf(text = "Unrecognized link. Maybe it's from a newer version of Notable?", duration = 3000)
            )
            return
        }
        Log.i(TAG, "Received link $link")
        deepLinks.trySend(link)
    }

    override fun onRestart() {
        super.onRestart()
        // redraw after device sleep
        this.lifecycleScope.launch {
            CanvasEventBus.reinitSignal.emit(Unit)
        }
    }

    override fun onPause() {
        super.onPause()
        CanvasEventBus.setResumed(false)
        this.lifecycleScope.launch {
            Log.d("QuickSettings", "App is paused - maybe quick settings opened?")
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    override fun onStop() {
        super.onStop()
        // The process may be killed while in the background; don't leave edits waiting.
        if (hasUsableStorage(this)) pageSaver.get().saveSoon()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        Log.d(TAG, "OnWindowFocusChanged: $hasFocus")
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableFullScreen()
        }
        CanvasEventBus.setWindowFocus(hasFocus)
    }

    override fun onResume() {
        super.onResume()
        enableFullScreen()
        CanvasEventBus.setResumed(true)
    }

    // Onyx SystemUI's TouchInteractionService swallows multi-finger touches for its
    // global gestures (three-finger screenshot), which steals our three-finger
    // swipes. Raising this flag makes it stand down — but the same pipeline serves
    // the side/bottom edge navigation swipes, so blocking is opt-in via settings.
    // The flag is global and sticky (nothing else clears it), so it is held only
    // while the activity is resumed AND the setting is on, released otherwise.
    private var onyxSystemGesturesBlocked = false

    private fun trackSystemGestureBlocking() {
        if (!DeviceCompat.isOnyxDevice) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    snapshotFlow { GlobalAppSettings.current.blockSystemGestures }
                        .collect { setOnyxSystemGesturesBlocked(it) }
                } finally {
                    setOnyxSystemGesturesBlocked(false)
                }
            }
        }
    }

    // Our own, more hesitant auto-rotate; held only while resumed and enabled.
    private val steadyRotation = SteadyRotationController(this)

    private fun trackSteadyRotation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    snapshotFlow { GlobalAppSettings.current.steadyRotation }
                        .collect { if (it) steadyRotation.start() else steadyRotation.stop() }
                } finally {
                    steadyRotation.stop()
                }
            }
        }
    }

    // Writing, a resting hand, or a hovering pen keep the screen where it is.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        steadyRotation.onInput()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        steadyRotation.onInput()
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun setOnyxSystemGesturesBlocked(blocked: Boolean) {
        if (blocked == onyxSystemGesturesBlocked) return
        onyxSystemGesturesBlocked = blocked
        val action =
            if (blocked) "onyx.action.INTERCEPT_GESTURE"
            else "onyx.action.DO_NOT_INTERCEPT_GESTURE"
        sendBroadcast(Intent(action))
    }


    // when the screen orientation is changed, set new screen width restart is not necessary,
    // as we need first to update page dimensions which is done in EditorView()
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.i(TAG, "Switched to Landscape")
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Log.i(TAG, "Switched to Portrait")
        }
        SCREEN_WIDTH = applicationContext.resources.displayMetrics.widthPixels
        SCREEN_HEIGHT = applicationContext.resources.displayMetrics.heightPixels
    }

    private fun enableFullScreen() {
        // Clearer intent broadcasting syntax
        val optimizeIntent = Intent("com.onyx.app.optimize.setting").apply {
            putExtra("optimize_fullScreen", true)
            putExtra(
                "optimize_pkgName", packageName
            ) // Use Context.packageName instead of hardcoding
        }
        sendBroadcast(optimizeIntent)

        // Modern, backwards-compatible AndroidX way to handle fullscreen / insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
@Preview(showBackground = true)
fun ShowInitMessage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Initializing...",
            color = Color.Black,
            fontSize = 30.sp
        )
    }
}