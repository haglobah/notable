package com.ethran.notable.flow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.io.Reader
import java.util.concurrent.TimeUnit

/** Flow answered that the humane tools session is missing or over: sign in again. */
class FlowSignedOut(message: String = "Signed out of Flow") : IOException(message)

/** Flow answered with an error worth showing as is ("Voice input needs an ElevenLabs API key"). */
class FlowError(message: String) : IOException(message)

/**
 * Flow's HTTP API as the browser uses it (hotlustre: core/ht.ts for sign-in, a version's
 * server/agent/routes.ts for the chat, server/plugins/voice.ts for transcription). The humane tools
 * session is a cookie; [cookies] are its name → value pairs, kept by the caller.
 */
class FlowApi(http: OkHttpClient, val baseUrl: HttpUrl) {
    private val http = http.newBuilder().readTimeout(2, TimeUnit.MINUTES).build()
    /** The event stream: Flow pings every 15 s, so 60 s of silence means the connection is gone. */
    private val streaming = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    /** Transcription waits for ElevenLabs on long recordings. */
    private val slow = http.newBuilder().readTimeout(10, TimeUnit.MINUTES).build()

    /** Signs in with a humane tools account; returns the session cookies. */
    fun signIn(email: String, password: String): Map<String, String> {
        val body = buildJsonObject { put("email", email); put("password", password) }.toString()
        val req = Request.Builder().url(url("_auth/sign-in"))
            // The core and humane tools both check the origin of a sign-in.
            .header("Origin", origin())
            .post(body.toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            // A Flow without humane tools sign-in (a local one) has no such route: it needs no session.
            if (r.code == 404 && openWithoutSignIn()) return emptyMap()
            if (r.code == 401 || r.code == 403) throw FlowError("Wrong email or password")
            if (r.code == 429) throw FlowError("Too many sign-in attempts; wait a moment")
            if (!r.isSuccessful) throw FlowError("Sign-in failed: HTTP ${r.code}")
            val cookies = mergeCookies(emptyMap(), r.headers("Set-Cookie"))
            if (cookies.isEmpty()) throw FlowError("Flow sent no session")
            return cookies
        }
    }

    private fun openWithoutSignIn(): Boolean {
        val req = Request.Builder().url(url("api/chat/status")).get().build()
        return runCatching { http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /**
     * Checks the session and lets humane tools renew it: only a navigation may renew it (core/main.ts
     * `htGate`), so this asks for HTML. Returns the cookies, renewed or not.
     */
    fun renew(cookies: Map<String, String>): Map<String, String> {
        val req = Request.Builder().url(url("api/chat/status")).cookies(cookies).header("Accept", "text/html").get().build()
        // A signed-out navigation is redirected to the sign-in page: see it, don't follow it.
        http.newBuilder().followRedirects(false).build().newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 303) throw FlowSignedOut()
            r.failOn()
            return mergeCookies(cookies, r.headers("Set-Cookie"))
        }
    }

    /** Appends a batch of the app's logcat (gzip) to the day's file on Flow's machine (notable-logs.ts). */
    fun postLogs(cookies: Map<String, String>, gzipped: ByteArray) {
        val req = Request.Builder().url(url("api/notable/logs")).cookies(cookies)
            .header("Content-Encoding", "gzip")
            .post(gzipped.toRequestBody(OCTETS)).build()
        http.newCall(req).execute().use { it.failOn() }
    }

    /** Transcribes 16 kHz mono PCM16 little-endian audio with Flow's voice settings. */
    fun transcribe(cookies: Map<String, String>, pcm: ByteArray): String {
        val req = Request.Builder().url(url("api/voice/transcribe")).cookies(cookies)
            .post(pcm.toRequestBody(OCTETS)).build()
        slow.newCall(req).execute().use { r ->
            r.failOn()
            val o = Json.parseToJsonElement(r.body.string()).jsonObject
            return (o["text"] as? JsonPrimitive)?.content.orEmpty()
        }
    }

    /**
     * Puts a thought into Flow's writing inbox (server/plugins/writing.ts): [png] is handwriting,
     * which Flow reads with Claude and adds to today's daily note under "Captured". Returns what it
     * read and the note's title, or null when today's note is not made yet (Flow holds it until then).
     */
    fun capture(cookies: Map<String, String>, png: ByteArray): Pair<String, String?> {
        val req = Request.Builder().url(url("api/writing/capture")).cookies(cookies)
            .post(png.toRequestBody(PNG)).build()
        http.newCall(req).execute().use { r ->
            r.failOn()
            val o = Json.parseToJsonElement(r.body.string()).jsonObject
            val note = o["note"] as? JsonPrimitive
            return (o["text"] as? JsonPrimitive)?.content.orEmpty() to note?.takeIf { it.isString }?.content
        }
    }

    /**
     * Sends a message: a new conversation for an unknown [conv], else into it (joining a running
     * turn). [cid] makes a resend count once. [scope]: the projects a new conversation is about
     * (Flow's scope pills); null leaves the conversation's as they are.
     */
    fun send(cookies: Map<String, String>, conv: String, cid: String, text: String, scope: List<String>? = null) {
        val body = buildJsonObject {
            put("cid", cid); put("conv", conv); put("text", text)
            if (scope != null) put("scope", JsonArray(scope.map(::JsonPrimitive)))
        }.toString()
        val req = Request.Builder().url(url("api/chat")).cookies(cookies).post(body.toRequestBody(JSON)).build()
        http.newCall(req).execute().use { it.failOn() }
    }

    /** Stops the conversation's turn and the agents it started. */
    fun stop(cookies: Map<String, String>, conv: String) {
        val body = buildJsonObject { put("conv", conv) }.toString()
        val req = Request.Builder().url(url("api/chat/stop")).cookies(cookies).post(body.toRequestBody(JSON)).build()
        http.newCall(req).execute().use { it.failOn() }
    }

    /** Ticks the conversation off ([done]) or opens it again, as Flow's checkbox does; its agents go with it. */
    fun tick(cookies: Map<String, String>, conv: String, done: Boolean) {
        val body = buildJsonObject { put("conv", conv); put("done", done) }.toString()
        val req = Request.Builder().url(url("api/chat/tick")).cookies(cookies).post(body.toRequestBody(JSON)).build()
        http.newCall(req).execute().use { it.failOn() }
    }

    /**
     * Where the event log stands, so the stream starts there and not at the log's beginning (tens
     * of MB). Flow has no route for it; its saved page model (`/api/state`) has the seq it last
     * folded, `chat.seq`, read here without holding the whole model.
     */
    fun headSeq(cookies: Map<String, String>): Long {
        val req = Request.Builder().url(url("api/state")).cookies(cookies).get().build()
        slow.newCall(req).execute().use { r ->
            r.failOn()
            return r.body.charStream().use { chatSeq(it) } ?: 0L
        }
    }

    /**
     * Reads the chat event stream from after [since] until it closes; blocks. [onCall] gets the
     * call, so another thread can cancel it.
     */
    fun events(cookies: Map<String, String>, since: Long, onCall: (Call) -> Unit = {}, onEvent: (JsonObject) -> Unit) {
        val u = url("api/chat/events").newBuilder().addQueryParameter("since", since.toString()).build()
        val req = Request.Builder().url(u).cookies(cookies).header("Accept", "text/event-stream").get().build()
        val call = streaming.newCall(req)
        onCall(call)
        call.execute().use { r ->
            r.failOn()
            readSse(r.body.source()) { event, data ->
                if (event != null) return@readSse
                val e = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@readSse
                onEvent(e)
            }
        }
    }

    private fun url(path: String) = baseUrl.newBuilder().addPathSegments(path).build()

    private fun origin() = "${baseUrl.scheme}://${baseUrl.host}" + (if (baseUrl.port == HttpUrl.defaultPort(baseUrl.scheme)) "" else ":${baseUrl.port}")

    private fun Request.Builder.cookies(cookies: Map<String, String>) =
        if (cookies.isEmpty()) this else header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })

    private fun Response.failOn() {
        if (isSuccessful) return
        if (code == 401) throw FlowSignedOut()
        val text = runCatching { body.string() }.getOrDefault("")
        val message = runCatching { (Json.parseToJsonElement(text).jsonObject["error"] as? JsonPrimitive)?.content }.getOrNull()
        throw FlowError(message ?: "Flow: HTTP $code ${text.take(200)}")
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val OCTETS = "application/octet-stream".toMediaType()
        private val PNG = "image/png".toMediaType()
    }
}

/** Applies `Set-Cookie` headers: new values replace old ones, an empty or expired cookie goes. */
internal fun mergeCookies(cookies: Map<String, String>, setCookie: List<String>): Map<String, String> {
    val out = cookies.toMutableMap()
    for (header in setCookie) {
        val parts = header.split(";").map { it.trim() }
        val pair = parts.firstOrNull() ?: continue
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        val name = pair.substring(0, eq)
        val value = pair.substring(eq + 1)
        val expired = parts.drop(1).any { it.equals("Max-Age=0", ignoreCase = true) }
        if (value.isEmpty() || expired) out.remove(name) else out[name] = value
    }
    return out
}

/** Server-sent events: calls [onData] with each event's name (null for plain data) and data. */
internal fun readSse(source: BufferedSource, onData: (event: String?, data: String) -> Unit) {
    var event: String? = null
    val data = StringBuilder()
    var any = false
    while (true) {
        val line = source.readUtf8Line() ?: break
        when {
            line.isEmpty() -> {
                if (any) onData(event, data.toString())
                event = null
                data.setLength(0)
                any = false
            }

            line.startsWith(":") -> Unit
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (any) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
                any = true
            }
        }
    }
}

/**
 * `chat.seq` of a JSON document, read as a stream: everything else is skipped without being kept,
 * so a model of many MB costs no memory. Null when the document has none.
 */
internal fun chatSeq(reader: Reader): Long? = JsonScan(reader).run {
    if (next() != '{') return null
    var result: Long? = null
    eachKey { key ->
        if (key == "chat" && peek() == '{') {
            next()
            eachKey { inner -> if (inner == "seq" && peek().let { it == '-' || it.isDigit() }) result = number() else skipValue() }
        } else skipValue()
    }
    result
}

/** A forward-only JSON reader for [chatSeq]: just enough to walk keys and skip values. */
private class JsonScan(private val reader: Reader) {
    private var peeked = -1

    private fun read(): Int = if (peeked >= 0) peeked.also { peeked = -1 } else reader.read()

    /** The next character that is not whitespace, consumed. */
    fun next(): Char {
        while (true) {
            val c = read()
            if (c < 0) throw IOException("JSON ended early")
            if (!c.toChar().isWhitespace()) return c.toChar()
        }
    }

    fun peek(): Char = next().also { peeked = it.code }

    /** Inside an object (its `{` read): calls [onKey] for each key, with the reader at its value. */
    fun eachKey(onKey: (String) -> Unit) {
        if (peek() == '}') { next(); return }
        while (true) {
            if (next() != '"') throw IOException("expected a key")
            val key = string()
            if (next() != ':') throw IOException("expected ':'")
            onKey(key)
            when (next()) {
                ',' -> continue
                '}' -> return
                else -> throw IOException("expected ',' or '}'")
            }
        }
    }

    /** The rest of a string whose opening quote was read. */
    fun string(): String {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) throw IOException("JSON ended in a string")
            when (c.toChar()) {
                '"' -> return sb.toString()
                '\\' -> { sb.append('\\'); sb.append(read().toChar()) }
                else -> sb.append(c.toChar())
            }
        }
    }

    fun number(): Long {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) break
            val ch = c.toChar()
            if (ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E' || ch.isDigit()) sb.append(ch)
            else { peeked = c; break }
        }
        return sb.toString().toDouble().toLong()
    }

    fun skipValue() {
        when (val c = next()) {
            '"' -> string()
            '{' -> eachKey { skipValue() }
            '[' -> {
                if (peek() == ']') { next(); return }
                while (true) {
                    skipValue()
                    when (next()) {
                        ',' -> continue
                        ']' -> return
                        else -> throw IOException("expected ',' or ']'")
                    }
                }
            }

            else -> {
                // A number or a literal: up to the next delimiter.
                var x = c
                while (x != ',' && x != '}' && x != ']' && !x.isWhitespace()) {
                    val r = read()
                    if (r < 0) return
                    x = r.toChar()
                }
                peeked = x.code
            }
        }
    }
}
