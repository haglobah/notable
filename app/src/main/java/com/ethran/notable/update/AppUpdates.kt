package com.ethran.notable.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.ethran.notable.BuildConfig
import com.ethran.notable.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val log = ShipBook.getLogger("AppUpdates")

/** What cirdan publishes next to each build: `latest.json` (scripts/publish-apk.sh). */
@Serializable
data class Release(
    val versionCode: Int,
    val versionName: String,
    /** The APK, relative to `latest.json`. */
    val apk: String,
    val sha256: String,
    val size: Long = 0,
    val commit: String = "",
    /** The built commit's subject: what this update brings. */
    val subject: String = "",
)

sealed interface UpdateState {
    /** Not checked yet, or the last check could not reach the server. */
    data object Unknown : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val percent: Int) : UpdateState
    /** Handed to Android, which may ask to confirm. */
    data class Installing(val release: Release) : UpdateState
    data class Failed(val release: Release?, val message: String) : UpdateState
}

/** The release in `latest.json` when it is newer than [installedCode]; null otherwise. */
internal fun newerRelease(json: String, installedCode: Int): Release? =
    updateJson.decodeFromString<Release>(json).takeIf { it.versionCode > installedCode }

private val updateJson = Json { ignoreUnknownKeys = true }

/**
 * Notable updates itself from cirdan (docs/plans/self-update.md): it reads `latest.json` on start
 * and on resume, the settings gear gets a dot when a newer build is there, and Install downloads the
 * APK, checks its hash and hands it to Android's package installer. Android asks to confirm unless
 * Notable installed the running version itself (Android 12+, then the update is silent).
 */
@Singleton
class AppUpdates @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val latestUrl: HttpUrl = BuildConfig.UPDATE_URL.toHttpUrl()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastCheck = 0L
    private var checking: Job? = null

    /** Reads `latest.json` unless a check ran in the last few minutes ([force] skips that). */
    fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < CHECK_INTERVAL_MS) return
        when (_state.value) {
            is UpdateState.Downloading, is UpdateState.Installing -> return
            else -> {}
        }
        if (checking?.isActive == true) return
        lastCheck = now
        checking = scope.launch(Dispatchers.IO) {
            _state.value = try {
                val body = http.newCall(Request.Builder().url(latestUrl).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    r.body.string()
                }
                newerRelease(body, BuildConfig.VERSION_CODE)
                    ?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
            } catch (e: Exception) {
                log.w("update check failed: ${e.message}")
                if (force) UpdateState.Failed(null, e.message ?: e.toString()) else UpdateState.Unknown
            }
        }
    }

    /**
     * Downloads and installs [release]. Without the "install unknown apps" permission it opens that
     * setting instead; the next tap installs.
     */
    fun install(release: Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val apk = download(release)
                _state.value = UpdateState.Installing(release)
                withContext(Dispatchers.Main) { commit(apk) }
            } catch (e: Exception) {
                log.e("update install failed", e)
                _state.value = UpdateState.Failed(release, e.message ?: e.toString())
            }
        }
    }

    /** From [UpdateInstallReceiver]: the installer's verdict when it is not a success (that kills us). */
    internal fun installFailed(message: String) {
        val release = (_state.value as? UpdateState.Installing)?.release
        _state.value = UpdateState.Failed(release, message)
    }

    private fun download(release: Release): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "notable-${release.versionCode}.apk")
        val url = latestUrl.resolve(release.apk) ?: throw IOException("bad APK path: ${release.apk}")
        _state.value = UpdateState.Downloading(release, 0)
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("APK download: HTTP ${r.code}")
            val total = r.body.contentLength().takeIf { it > 0 } ?: release.size
            r.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        // e-ink: a step every 10 %, not every packet
                        val percent = if (total > 0) (done * 100 / total).toInt() / 10 * 10 else 0
                        if (percent != shown) {
                            shown = percent
                            _state.value = UpdateState.Downloading(release, percent)
                        }
                    }
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(release.sha256, ignoreCase = true)) {
            file.delete()
            throw IOException("APK hash mismatch")
        }
        return file
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("notable.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id,
                Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                // Mutable: the installer adds its status to the intent.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
    }
}
