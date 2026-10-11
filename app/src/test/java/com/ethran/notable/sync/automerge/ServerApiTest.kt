package com.ethran.notable.sync.automerge

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerApiTest {
    private val server = MockWebServer()
    private lateinit var api: ServerApi

    @Before
    fun start() {
        server.start()
        api = ServerApi(OkHttpClient(), ServerApi.parseServerUrl(server.url("/").toString()))
    }

    @After
    fun stop() = server.close()

    private fun respond(code: Int, body: String = "", vararg headers: Pair<String, String>) =
        server.enqueue(MockResponse.Builder().code(code).body(body).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build())

    @Test
    fun login_posts_json_and_returns_the_token() {
        respond(200, """{"token":"abc","username":"beat"}""")
        assertEquals(LoginResult("abc", "beat"), api.login("beat", "pw \"quoted\""))
        val req = server.takeRequest()
        assertEquals("/api/login", req.url.encodedPath)
        assertEquals("POST", req.method)
        assertTrue(req.body!!.utf8().contains("\"password\":\"pw \\\"quoted\\\"\""))
    }

    @Test
    fun wrong_password_is_its_own_error() {
        respond(401, """{"error":"invalid username or password"}""")
        assertThrows(ServerError.InvalidCredentials::class.java) { api.login("beat", "x") }
    }

    @Test
    fun rate_limit_carries_retry_after() {
        respond(429, """{"error":"slow down"}""", "retry-after" to "120")
        val e = assertThrows(ServerError.RateLimited::class.java) { api.login("beat", "x") }
        assertEquals(120L, e.retryAfterSeconds)
    }

    @Test
    fun me_sends_the_bearer_token() {
        respond(200, """{"username":"beat","libraryDocUrl":"automerge:abc","maxBlobBytes":1024}""")
        assertEquals(Me("beat", "automerge:abc", 1024), api.me("tok"))
        assertEquals("Bearer tok", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun an_expired_session_is_unauthorized() {
        respond(401, """{"error":"not logged in"}""")
        assertThrows(ServerError.Unauthorized::class.java) { api.me("tok") }
    }

    @Test
    fun put_blob_streams_the_bytes_to_the_hash_path() {
        respond(201)
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        api.putBlob("tok", "ab".repeat(32), bytes.size.toLong()) { bytes.inputStream() }
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/blobs/" + "ab".repeat(32), req.url.encodedPath)
        assertEquals(bytes.toList(), req.body!!.toByteArray().toList())
    }

    @Test
    fun too_large_is_its_own_error() {
        respond(413, """{"error":"file too large"}""")
        assertThrows(ServerError.TooLarge::class.java) {
            api.putBlob("tok", "ab".repeat(32), 3) { byteArrayOf(1, 2, 3).inputStream() }
        }
    }

    @Test
    fun other_failures_keep_status_and_message() {
        respond(500, """{"error":"disk full"}""")
        val e = assertThrows(ServerError.Http::class.java) { api.me("tok") }
        assertEquals(500, e.code)
        assertTrue(e.message!!.contains("disk full"))
    }

    @Test
    fun logout_posts_with_the_token() {
        respond(204)
        api.logout("tok")
        val req = server.takeRequest()
        assertEquals("/api/logout", req.url.encodedPath)
        assertEquals("Bearer tok", req.headers["Authorization"])
    }

    @Test
    fun sync_url_switches_to_websocket_scheme() {
        assertEquals("wss://notable.example/sync", ServerApi.syncUrl(ServerApi.parseServerUrl("https://notable.example")))
        assertEquals("ws://localhost:3040/sync", ServerApi.syncUrl(ServerApi.parseServerUrl("http://localhost:3040/")))
    }

    @Test
    fun server_urls_are_validated() {
        assertThrows(IllegalArgumentException::class.java) { ServerApi.parseServerUrl("notable.example") }
        assertThrows(IllegalArgumentException::class.java) { ServerApi.parseServerUrl("ftp://notable.example") }
        assertEquals("https://notable.example/", ServerApi.parseServerUrl(" https://notable.example ").toString())
    }

    @Test
    fun getBlob_streams_to_the_file_after_checking_the_hash() {
        val bytes = "%PDF-1.7 hello".toByteArray()
        val sha = sha256Hex(bytes)
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(bytes)).build())
        val dest = java.io.File.createTempFile("blob", ".pdf").apply { delete() }

        api.getBlob("tok", sha, dest)

        assertTrue(dest.readBytes().contentEquals(bytes))
        val req = server.takeRequest()
        assertEquals("/api/blobs/$sha", req.url.encodedPath)
        assertEquals("Bearer tok", req.headers["Authorization"])
    }

    @Test
    fun getBlob_rejects_bytes_that_do_not_match_and_leaves_no_file() {
        respond(200, "not the right bytes")
        val dest = java.io.File.createTempFile("blob", ".pdf").apply { delete() }
        assertThrows(ServerError.Http::class.java) { api.getBlob("tok", "ab".repeat(32), dest) }
        assertTrue(!dest.exists())
        assertTrue(dest.parentFile!!.listFiles()!!.none { it.name.startsWith(dest.name) })
    }

    @Test
    fun getBlob_404_is_a_missing_file() {
        respond(404, """{"error":"not found"}""")
        val dest = java.io.File.createTempFile("blob", ".pdf").apply { delete() }
        assertThrows(MissingFileException::class.java) { api.getBlob("tok", "cd".repeat(32), dest) }
    }
}
