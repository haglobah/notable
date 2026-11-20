package com.ethran.notable.flow

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Records the microphone as 16 kHz mono PCM16 little-endian, the format Flow's
 * `/api/voice/transcribe` takes. One recording at a time; needs RECORD_AUDIO granted.
 */
class VoiceRecorder {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val out = ByteArrayOutputStream()
    @Volatile private var running = false

    val recording get() = running

    @SuppressLint("MissingPermission") // The screen asks for RECORD_AUDIO before it starts.
    fun start() {
        if (running) return
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min, RATE / 5 * 2) // at least 200 ms
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("The microphone is not available")
        }
        synchronized(out) { out.reset() }
        record = r
        running = true
        r.startRecording()
        worker = thread(name = "flow-voice") {
            val buf = ByteArray(size)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) synchronized(out) { out.write(buf, 0, n) }
            }
        }
    }

    /** Stops and returns what was recorded. */
    fun stop(): ByteArray {
        if (!running) return ByteArray(0)
        running = false
        worker?.join(1000)
        worker = null
        record?.run { runCatching { stop() }; release() }
        record = null
        return synchronized(out) { out.toByteArray().also { out.reset() } }
    }

    /** Stops and drops the recording. */
    fun cancel() {
        stop()
    }

    companion object {
        const val RATE = 16_000
    }
}
