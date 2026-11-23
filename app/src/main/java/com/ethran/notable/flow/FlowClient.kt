package com.ethran.notable.flow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.sync.automerge.KeystoreCipher
import com.ethran.notable.sync.automerge.SecretCipher
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private val log = ShipBook.getLogger("FlowClient")

@Serializable
data class FlowAccount(val url: String, val email: String)

sealed interface FlowLink {
    data object Off : FlowLink
    data object Connecting : FlowLink
    data object Live : FlowLink
    data class Down(val reason: String) : FlowLink
}

/**
 * The microphone dot floating over the library and over a note while its toolbar shows: [shown]
 * toggles it (the library bar's microphone), [x]/[y] are where it was dragged to, in dp from the
 * screen's top left; null: its place near the top bar, at the right.
 */
data class FlowDot(val shown: Boolean = true, val x: Float? = null, val y: Float? = null)

/** The project Flow scopes a conversation started here to (its scope pills): this repo. */
const val FLOW_SCOPE = "notable"

/** The longest side of a capture's image, in pixels: Claude reads images up to about this size. */
private const val CAPTURE_SIDE = 1568

sealed interface VoiceState {
    data object Idle : VoiceState
    data object Recording : VoiceState
    data object Transcribing : VoiceState
    data object Sending : VoiceState
    data class Failed(val reason: String) : VoiceState
}

/**
 * The tablet's line to Flow (flow.humane.tools): sign-in, the chat event stream while a screen
 * shows it or a message is on its way, and voice → transcript → message. Every message starts or
 * continues a Flow conversation, so each job is inspectable in Flow itself.
 */
@Singleton
class FlowClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    crypto: CryptoHelper,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val snacks: SnackDispatcher,
) {
    private val dir = File(context.filesDir, "flow")
    private val store = FlowStore(dir, KeystoreCipher(crypto))
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).build()
    private val recorder = VoiceRecorder()
    private val chatLock = Mutex()

    private val _account = MutableStateFlow(store.account())
    val account: StateFlow<FlowAccount?> = _account.asStateFlow()

    private val _chat = MutableStateFlow(store.chat())
    val chat: StateFlow<FlowChat> = _chat.asStateFlow()

    private val _link = MutableStateFlow<FlowLink>(FlowLink.Off)
    val link: StateFlow<FlowLink> = _link.asStateFlow()

    private val _voice = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voice: StateFlow<VoiceState> = _voice.asStateFlow()

    private val dotPrefs = context.getSharedPreferences("flow-dot", Context.MODE_PRIVATE)
    private val _dot = MutableStateFlow(
        FlowDot(
            shown = dotPrefs.getBoolean("shown", true),
            x = dotPrefs.getFloat("x", Float.NaN).takeUnless { it.isNaN() },
            y = dotPrefs.getFloat("y", Float.NaN).takeUnless { it.isNaN() },
        )
    )
    val dot: StateFlow<FlowDot> = _dot.asStateFlow()

    /** The conversation the screen shows and its messages go to; null: the next one starts one. */
    private val _selected = MutableStateFlow(_chat.value.open.firstOrNull()?.id)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private var cookies: Map<String, String> = store.cookies()
    private var streamJob: Job? = null
    @Volatile private var streamCall: Call? = null
    private var saveJob: Job? = null
    private var attached = 0
    /** A recording or its message is under way (from the dot, too): the stream stays open meanwhile. */
    private var holding = false

    init {
        // While the dot listens, the stream catches up, so its list of open conversations is current.
        scope.launch(Dispatchers.Main.immediate) {
            _voice.map { it == VoiceState.Recording || it == VoiceState.Transcribing || it == VoiceState.Sending }
                .distinctUntilChanged()
                .collect { busy ->
                    holding = busy
                    if (busy && streamJob?.isActive != true) restartStream()
                    else if (!busy && attached == 0) {
                        stopStream()
                        saveNow()
                    }
                }
        }
    }

    private fun api(): FlowApi? = _account.value?.let { FlowApi(http, it.url.toHttpUrl()) }

    suspend fun signIn(url: String, email: String, password: String) = withContext(Dispatchers.IO) {
        val base = url.trim().trimEnd('/').toHttpUrl()
        val got = FlowApi(http, base).signIn(email.trim(), password)
        val account = FlowAccount(base.toString().trimEnd('/'), email.trim())
        store.saveAccount(account, got)
        cookies = got
        // Another Flow's seq means nothing here, and its conversations aren't there.
        if (_chat.value.server != account.url) {
            _chat.value = FlowChat(server = account.url)
            _selected.value = null
            store.saveChat(_chat.value)
        }
        _account.value = account
        if (attached > 0 || holding) restartStream()
    }

    fun signOut() {
        stopStream()
        store.clearAccount()
        cookies = emptyMap()
        _account.value = null
        _link.value = FlowLink.Off
    }

    /** A screen showing Flow came up: keep the event stream open while one does. */
    fun attach() {
        attached++
        if (attached == 1) restartStream()
    }

    fun detach() {
        attached = maxOf(0, attached - 1)
        if (attached > 0) return
        // A recording goes on under the dot, where it can be sent; with the dot hidden it ends here.
        if (recorder.recording && !_dot.value.shown) cancelRecording()
        if (!holding) {
            stopStream()
            saveNow()
        }
    }

    fun select(conv: String?) {
        _selected.value = conv
    }

    fun showDot(shown: Boolean) {
        _dot.update { it.copy(shown = shown) }
        dotPrefs.edit().putBoolean("shown", shown).apply()
    }

    fun moveDot(x: Float, y: Float) {
        _dot.update { it.copy(x = x, y = y) }
        dotPrefs.edit().putFloat("x", x).putFloat("y", y).apply()
    }

    /**
     * The microphone's tap: start listening; while listening, send what was said to [conv], or
     * to a new conversation when null.
     */
    fun toggleRecording(conv: String?) {
        when (_voice.value) {
            VoiceState.Recording -> finishRecording(conv)
            VoiceState.Idle, is VoiceState.Failed -> { dismissVoiceError(); startRecording() }
            else -> Unit
        }
    }

    fun startRecording() {
        if (_voice.value == VoiceState.Recording) return
        try {
            recorder.start()
            _voice.value = VoiceState.Recording
        } catch (e: Exception) {
            _voice.value = VoiceState.Failed(e.message ?: "The microphone is not available")
        }
    }

    fun cancelRecording() {
        recorder.cancel()
        _voice.value = VoiceState.Idle
    }

    /** Ends the recording, transcribes it with Flow and sends the text to [conv], or starts a conversation with it. */
    fun finishRecording(conv: String?) {
        if (_voice.value != VoiceState.Recording) return
        val pcm = recorder.stop()
        _voice.value = VoiceState.Transcribing
        scope.launch {
            try {
                val api = api() ?: throw FlowSignedOut("Sign in to Flow in Settings → Sync")
                val text = api.transcribe(cookies, pcm).trim()
                if (text.isEmpty()) {
                    _voice.value = VoiceState.Failed("Nothing was heard")
                    return@launch
                }
                deliver(api, text, conv)
            } catch (e: IOException) {
                failed(e)
            }
        }
    }

    /** Sends typed text, like a transcript, to the shown conversation. */
    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        scope.launch {
            try {
                val api = api() ?: throw FlowSignedOut("Sign in to Flow in Settings → Sync")
                deliver(api, t, _selected.value)
            } catch (e: IOException) {
                failed(e)
            }
        }
    }

    private suspend fun deliver(api: FlowApi, text: String, target: String?) {
        _voice.value = VoiceState.Sending
        val conv = target ?: UUID.randomUUID().toString()
        val fresh = !_chat.value.conversations.containsKey(conv)
        val now = System.currentTimeMillis()
        chatLock.withLock { _chat.update { if (it.conversations.containsKey(conv)) it.said(conv, text, now) else it.started(conv, text, now) } }
        _selected.value = conv
        scheduleSave()
        // Flow takes a message once per cid, so a retry after a lost answer doesn't send it twice.
        val cid = UUID.randomUUID().toString()
        var tries = 0
        while (true) {
            try {
                api.send(cookies, conv, cid, text, scope = if (fresh) listOf(FLOW_SCOPE) else null)
                break
            } catch (e: FlowError) {
                // 409: a new Flow version is starting; it asks to send again in a moment.
                if (++tries >= 4) throw e
                delay(2000L * tries)
            }
        }
        _voice.value = VoiceState.Idle
        if (attached > 0 && _link.value !is FlowLink.Live && streamJob?.isActive != true) restartStream()
    }

    /** Ticks a conversation off ([done]) or opens it again, here at once and in Flow. */
    fun tick(conv: String, done: Boolean) {
        changeChat { it.ticked(conv, done) }
        scope.launch {
            try {
                val api = api() ?: throw FlowSignedOut("Sign in to Flow in Settings → Sync")
                api.tick(cookies, conv, done)
            } catch (e: IOException) {
                changeChat { it.ticked(conv, !done) }
                failed(e)
            }
        }
    }

    /**
     * Sends a selection of handwriting to Flow's writing inbox: Flow reads it and adds the thought to
     * today's daily note under "Captured", as Alt-r does in Flow. A snack says how it went.
     */
    fun captureInk(selection: Bitmap) {
        // Ink on white (the selection is ink on transparent), no larger than Claude reads an image.
        val k = minOf(1f, CAPTURE_SIDE.toFloat() / maxOf(selection.width, selection.height))
        val image = createBitmap((selection.width * k).roundToInt().coerceAtLeast(1), (selection.height * k).roundToInt().coerceAtLeast(1))
        Canvas(image).apply {
            drawColor(Color.WHITE)
            scale(k, k)
            drawBitmap(selection, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val id = "flow-capture-${UUID.randomUUID()}"
        snacks.showOrUpdateSnack(SnackConf(id = id, text = "Sending to the writing inbox…"))
        scope.launch {
            val png = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            image.recycle()
            val said = try {
                val api = api() ?: throw FlowSignedOut("Sign in to Flow in Settings → Sync")
                val (text, note) = try {
                    api.capture(cookies, png)
                } catch (e: FlowSignedOut) {
                    cookies = api.renew(cookies).also { store.saveCookies(it) }
                    api.capture(cookies, png)
                }
                val thought = if (text.length > 140) text.take(139) + "…" else text
                if (note != null) "In the writing inbox: $thought" else "Held until today's daily note is made: $thought"
            } catch (e: IOException) {
                log.w("Flow capture: ${e.message}")
                "Not sent to the writing inbox: ${e.message ?: e.javaClass.simpleName}"
            }
            snacks.showOrUpdateSnack(SnackConf(id = id, text = said, duration = 6000))
        }
    }

    private fun changeChat(change: (FlowChat) -> FlowChat) {
        _chat.update(change)
        scheduleSave()
    }

    private fun failed(e: IOException) {
        log.w("Flow: ${e.message}")
        _voice.value = VoiceState.Failed(e.message ?: e.javaClass.simpleName)
        if (e is FlowSignedOut) _link.value = FlowLink.Down(e.message ?: "Signed out")
    }

    fun dismissVoiceError() {
        if (_voice.value is VoiceState.Failed) _voice.value = VoiceState.Idle
    }

    /**
     * Ships a batch of logs (logs/LogShipper.kt) with this account's session; a session the core
     * refuses is renewed once. False when signed out: the batch waits.
     */
    suspend fun postLogs(gzipped: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val api = api() ?: return@withContext false
        try {
            api.postLogs(cookies, gzipped)
        } catch (e: FlowSignedOut) {
            cookies = api.renew(cookies).also { store.saveCookies(it) }
            api.postLogs(cookies, gzipped)
        }
        true
    }

    /** Stops the shown conversation's turn. */
    fun stopTurn() {
        val conv = _selected.value ?: return
        scope.launch {
            runCatching { api()?.stop(cookies, conv) }.onFailure { if (it is IOException) failed(it) }
        }
    }

    private fun restartStream() {
        stopStream()
        val api = api() ?: run { _link.value = FlowLink.Off; return }
        streamJob = scope.launch {
            var backoff = 1000L
            while (isActive) {
                _link.value = FlowLink.Connecting
                try {
                    cookies = api.renew(cookies).also { if (it != cookies) store.saveCookies(it) }
                    if (_chat.value.seq == null) {
                        val head = api.headSeq(cookies)
                        chatLock.withLock { _chat.update { if (it.seq == null) it.copy(seq = head) else it } }
                    }
                    _link.value = FlowLink.Live
                    backoff = 1000L
                    api.events(cookies, _chat.value.seq ?: 0, onCall = { streamCall = it }) { e ->
                        val now = System.currentTimeMillis()
                        _chat.update { it.apply(e, now) }
                        scheduleSave()
                    }
                    // The stream ended (a new Flow version, a restart): pick it up again.
                } catch (e: FlowSignedOut) {
                    _link.value = FlowLink.Down("Signed out of Flow: sign in again in Settings → Sync")
                    return@launch
                } catch (e: IOException) {
                    if (!isActive) return@launch
                    _link.value = FlowLink.Down(e.message ?: e.javaClass.simpleName)
                }
                delay(backoff)
                backoff = minOf(backoff * 2, 30_000L)
            }
        }
    }

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        streamCall?.cancel()
        streamCall = null
        if (_link.value != FlowLink.Off) _link.value = FlowLink.Off
    }

    /** Writes the chat a moment after the last change: a reply streams many events. */
    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            delay(3000)
            store.saveChat(_chat.value)
        }
    }

    private fun saveNow() {
        val chat = _chat.value
        scope.launch { store.saveChat(chat) }
    }
}

/** Flow's account (session cookies sealed with the Keystore key) and the tablet's conversations. */
private class FlowStore(private val dir: File, private val cipher: SecretCipher) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val accountFile = File(dir, "account.json")
    private val cookieFile = File(dir, "session")
    private val chatFile = File(dir, "chat.json")

    fun account(): FlowAccount? = runCatching { json.decodeFromString<FlowAccount>(accountFile.readText()) }.getOrNull()

    fun cookies(): Map<String, String> = runCatching {
        json.decodeFromString<Map<String, String>>(cipher.decrypt(cookieFile.readText()))
    }.getOrDefault(emptyMap())

    fun saveAccount(account: FlowAccount, cookies: Map<String, String>) {
        write(accountFile, json.encodeToString(account))
        saveCookies(cookies)
    }

    fun saveCookies(cookies: Map<String, String>) = write(cookieFile, cipher.encrypt(json.encodeToString(cookies)))

    fun clearAccount() {
        accountFile.delete()
        cookieFile.delete()
    }

    fun chat(): FlowChat = runCatching { json.decodeFromString<FlowChat>(chatFile.readText()) }.getOrDefault(FlowChat())

    @Synchronized
    fun saveChat(chat: FlowChat) = write(chatFile, json.encodeToString(chat))

    private fun write(file: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw IOException("could not write ${file.path}")
    }
}
