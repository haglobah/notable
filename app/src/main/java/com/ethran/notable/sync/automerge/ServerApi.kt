package com.ethran.notable.sync.automerge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream

/** Failures of the Notable sync server's HTTP API (web/server/README.md). */
sealed class ServerError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Login rejected: wrong username or password. */
    class InvalidCredentials : ServerError("Wrong username or password")
    /** The session token is missing, expired or revoked. */
    class Unauthorized : ServerError("Not logged in, or the session has expired")
    class RateLimited(val retryAfterSeconds: Long?) :
        ServerError("Too many attempts" + (retryAfterSeconds?.let { "; try again in $it s" } ?: ""))
    class TooLarge : ServerError("The file is larger than the server accepts")
    class Network(cause: IOException) : ServerError("Can't reach the server: ${cause.message}", cause)
    class Http(val code: Int, detail: String) : ServerError("Server error $code: $detail")
}

data class LoginResult(val token: String, val username: String)
data class Me(val username: String, val libraryDocUrl: String, val maxBlobBytes: Long)

/** Blocking client for the server's HTTP API. Call it off the main thread. */
class ServerApi(private val http: OkHttpClient, val baseUrl: HttpUrl) {

    fun login(username: String, password: String): LoginResult {
        val body = JsonObject(mapOf("username" to JsonPrimitive(username), "password" to JsonPrimitive(password)))
        val req = Request.Builder().url(url("api/login")).post(body.toString().toRequestBody(JSON)).build()
        return call(req, login = true) { res ->
            val o = parse(res)
            LoginResult(o.getValue("token").jsonPrimitive.content, o.getValue("username").jsonPrimitive.content)
        }
    }

    fun logout(token: String) {
        val req = Request.Builder().url(url("api/logout")).bearer(token).post(ByteArray(0).toRequestBody()).build()
        call(req) { }
    }

    fun me(token: String): Me {
        val req = Request.Builder().url(url("api/me")).bearer(token).get().build()
        return call(req) { res ->
            val o = parse(res)
            Me(
                o.getValue("username").jsonPrimitive.content,
                o.getValue("libraryDocUrl").jsonPrimitive.content,
                o.getValue("maxBlobBytes").jsonPrimitive.long,
            )
        }
    }

    /** Streams [size] bytes from [open] to `PUT /api/blobs/<sha256>`; the server checks the hash. */
    fun putBlob(token: String, sha256: String, size: Long, open: () -> InputStream) {
        val body = object : RequestBody() {
            override fun contentType(): MediaType = OCTETS
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                open().source().use { sink.writeAll(it) }
            }
        }
        val req = Request.Builder().url(url("api/blobs/$sha256")).bearer(token).put(body).build()
        call(req) { }
    }

    /**
     * Downloads `GET /api/blobs/<sha256>` into [dest] through a temp file, checking the hash
     * before the rename, so [dest] only ever holds the right bytes.
     * @throws MissingFileException when the server doesn't have it (or not for this account).
     */
    fun getBlob(token: String, sha256: String, dest: java.io.File) {
        val req = Request.Builder().url(url("api/blobs/$sha256")).bearer(token).get().build()
        val tmp = java.io.File(dest.absoluteFile.parentFile, dest.name + ".part")
        try {
            call(req, notFound = { throw MissingFileException("blob $sha256 (not on the server)") }) { res ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                dest.absoluteFile.parentFile?.mkdirs()
                res.body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (got != sha256) throw ServerError.Http(200, "blob $sha256: received bytes hash to $got")
                if (!tmp.renameTo(dest)) throw IOException("could not move ${tmp.path} to ${dest.path}")
            }
        } finally {
            tmp.delete()
        }
    }

    private fun url(path: String) = baseUrl.newBuilder().addPathSegments(path).build()

    private fun Request.Builder.bearer(token: String) = header("Authorization", "Bearer $token")

    private fun <T> call(req: Request, login: Boolean = false, notFound: (() -> Nothing)? = null, onOk: (Response) -> T): T {
        val res = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw ServerError.Network(e)
        }
        res.use {
            if (it.isSuccessful) return onOk(it)
            if (it.code == 404 && notFound != null) notFound()
            val detail = runCatching { parse(it)["error"]?.jsonPrimitive?.content }.getOrNull() ?: it.message
            throw when (it.code) {
                401 -> if (login) ServerError.InvalidCredentials() else ServerError.Unauthorized()
                413 -> ServerError.TooLarge()
                429 -> ServerError.RateLimited(it.header("retry-after")?.toLongOrNull())
                else -> ServerError.Http(it.code, detail)
            }
        }
    }

    private fun parse(res: Response): JsonObject = Json.parseToJsonElement(res.body.string()).jsonObject

    companion object {
        private val JSON = "application/json".toMediaType()
        private val OCTETS = "application/octet-stream".toMediaType()

        /** Accepts `https://host[:port][/path]` (or http for local testing); rejects anything else. */
        fun parseServerUrl(input: String): HttpUrl {
            val trimmed = input.trim()
            require(trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
                "Server address must start with https://"
            }
            val url = trimmed.toHttpUrlOrNull() ?: throw IllegalArgumentException("Not a valid server address: $trimmed")
            return if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
        }

        /** The WebSocket endpoint the sync repo dials. */
        fun syncUrl(base: HttpUrl): String {
            val http = base.newBuilder().addPathSegments("sync").build().toString()
            return when {
                http.startsWith("https://") -> "wss://" + http.removePrefix("https://")
                else -> "ws://" + http.removePrefix("http://")
            }
        }
    }
}
