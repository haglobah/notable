package com.ethran.notable.logs

import android.content.Context
import android.util.Log
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.flow.FlowClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LogShipper"

/** The time a `logcat -v threadtime,year` line starts with ("2026-11-22 12:07:13.123"); null if none. */
internal fun lineTime(line: String): String? =
    line.takeIf { it.length >= 23 && it[4] == '-' && it[10] == ' ' && it[19] == '.' }?.substring(0, 23)

/**
 * Streams this app's logcat to Flow on cirdan (`POST /api/notable/logs`, Flow's
 * server/plugins/notable-logs.ts), so what the tablet did can be read there without adb.
 *
 * A reader thread follows `logcat` (an app sees only its own lines, those of earlier processes
 * too: a crash's last lines go out on the next start) from where the last shipped batch ended,
 * and appends to an outbox file. Every [INTERVAL_MS] the outbox goes out gzipped, with the Flow
 * session the chat uses; while signed out or offline it waits, capped at [MAX_OUTBOX] (the newest
 * lines kept). Off with Settings → Debug → "Send logs to cirdan" (`shipLogs`).
 */
@Singleton
class LogShipper @Inject constructor(
    @param:ApplicationContext context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val flow: FlowClient,
) {
    private val dir = File(context.filesDir, "logs")
    private val outbox = File(dir, "outbox.log")
    private val sending = File(dir, "sending.log")
    /** The time of the last line shipped or queued, for `logcat -T` after a restart. */
    private val mark = File(dir, "mark")
    private val lock = Any()
    private var started = false

    fun start() {
        if (started) return
        started = true
        dir.mkdirs()
        Thread(::follow, "log-shipper").apply { isDaemon = true }.start()
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(INTERVAL_MS)
                runCatching { ship() }.onFailure { Log.w(TAG, "logs not shipped: ${it.message}") }
            }
        }
    }

    private fun follow() {
        var backoff = 1000L
        while (true) {
            try {
                val since = runCatching { mark.readText().trim() }.getOrNull()?.takeIf { lineTime(it) != null }
                val args = mutableListOf("logcat", "-v", "threadtime,year", "-T", since ?: FIRST_LINES.toString())
                val p = ProcessBuilder(args).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().useLines { lines ->
                    val batch = StringBuilder()
                    var last: String? = null
                    var flushedAt = System.currentTimeMillis()
                    for (line in lines) {
                        if (!GlobalAppSettings.current.shipLogs) continue
                        // The line `-T since` starts at was shipped already.
                        if (since != null && last == null && lineTime(line) == since) continue
                        batch.append(line).append('\n')
                        lineTime(line)?.let { last = it }
                        val now = System.currentTimeMillis()
                        if (batch.length > 64 * 1024 || now - flushedAt > 2000) {
                            append(batch, last)
                            batch.setLength(0)
                            flushedAt = now
                        }
                    }
                    append(batch, last)
                }
                p.destroy()
                backoff = 1000L
            } catch (e: Exception) {
                Log.w(TAG, "logcat stopped: ${e.message}")
            }
            Thread.sleep(backoff)
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    private fun append(batch: CharSequence, last: String?) {
        if (batch.isEmpty()) return
        synchronized(lock) {
            outbox.appendText(batch.toString())
            if (last != null) mark.writeText(last)
            if (outbox.length() > MAX_OUTBOX) keepTail(outbox, MAX_OUTBOX / 2)
        }
    }

    private suspend fun ship() {
        if (!GlobalAppSettings.current.shipLogs) return
        // A batch that failed last time goes first; new lines keep landing in the outbox meanwhile.
        synchronized(lock) {
            if (!sending.exists() && outbox.length() > 0) {
                if (!outbox.renameTo(sending)) throw IOException("could not move the outbox")
            }
        }
        if (!sending.exists() || sending.length() == 0L) return
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { sending.inputStream().copyTo(it) } }.toByteArray()
        if (flow.postLogs(gz)) sending.delete()
    }

    private fun keepTail(file: File, bytes: Long) {
        val tail = file.inputStream().use { input ->
            input.skip(file.length() - bytes)
            input.readBytes()
        }
        val start = tail.indexOf('\n'.code.toByte()) + 1
        file.writeBytes(("--- older lines dropped: the outbox was full\n").toByteArray() + tail.copyOfRange(start, tail.size))
    }

    private companion object {
        const val INTERVAL_MS = 30_000L
        const val MAX_OUTBOX = 20L * 1024 * 1024
        /** Without a mark (the first start): this many of the newest lines. */
        const val FIRST_LINES = 5000
    }
}
