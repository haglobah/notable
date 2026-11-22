package com.ethran.notable.flow

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringReader

class FlowApiTest {
    private val server = MockWebServer()
    private lateinit var api: FlowApi

    @Before
    fun start() {
        server.start()
        api = FlowApi(OkHttpClient(), server.url("/"))
    }

    @After
    fun stop() = server.close()

    private fun respond(code: Int, body: String = "", vararg headers: Pair<String, String>) =
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build())

    @Test
    fun sign_in_keeps_the_session_cookies_and_sends_the_origin() {
        respond(200, """{"ok":true}""",
            "Set-Cookie" to "__Secure-humane-shared.session_token=abc.def; Path=/; HttpOnly; Secure",
            "Set-Cookie" to "humane-shared.session_data=xyz; Max-Age=300")
        val cookies = api.signIn("me@example.org", "pw")
        assertEquals(mapOf("__Secure-humane-shared.session_token" to "abc.def", "humane-shared.session_data" to "xyz"), cookies)
        val req = server.takeRequest()
        assertEquals("/_auth/sign-in", req.url.encodedPath)
        assertEquals("http://${server.hostName}:${server.port}", req.headers["Origin"])
        assertTrue(req.body!!.utf8().contains("\"email\":\"me@example.org\""))
    }

    @Test
    fun a_wrong_password_says_so() {
        respond(401, """{"ok":false}""")
        val e = assertThrows(FlowError::class.java) { api.signIn("me@example.org", "nope") }
        assertEquals("Wrong email or password", e.message)
    }

    @Test
    fun renew_asks_as_a_navigation_and_sees_a_redirect_as_signed_out() {
        respond(200, "{}", "Set-Cookie" to "s=new")
        assertEquals(mapOf("s" to "new"), api.renew(mapOf("s" to "old")))
        val req = server.takeRequest()
        assertEquals("text/html", req.headers["Accept"])
        assertEquals("s=old", req.headers["Cookie"])
        respond(303, "", "Location" to "/_auth/sign-in")
        assertThrows(FlowSignedOut::class.java) { api.renew(mapOf("s" to "old")) }
    }

    @Test
    fun send_posts_the_conversation_and_shows_flow_s_error() {
        respond(202, """{"ok":true}""")
        api.send(mapOf("s" to "1"), "conv-1", "cid-1", "hello")
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("\"conv\":\"conv-1\"") && body.contains("\"cid\":\"cid-1\"") && body.contains("\"text\":\"hello\""))
        respond(409, """{"error":"v0466 is starting; send again in a moment"}""")
        val e = assertThrows(FlowError::class.java) { api.send(emptyMap(), "conv-1", "cid-2", "again") }
        assertEquals("v0466 is starting; send again in a moment", e.message)
    }

    @Test
    fun send_scopes_a_new_conversation_and_leaves_a_scope_alone_otherwise() {
        respond(202, """{"ok":true}""")
        api.send(emptyMap(), "conv-1", "cid-1", "hello", scope = listOf("notable"))
        assertTrue(server.takeRequest().body!!.utf8().contains("\"scope\":[\"notable\"]"))
        respond(202, """{"ok":true}""")
        api.send(emptyMap(), "conv-1", "cid-2", "more")
        assertTrue(!server.takeRequest().body!!.utf8().contains("scope"))
    }

    @Test
    fun transcribe_sends_raw_pcm() {
        respond(200, """{"text":" make it bigger "}""")
        assertEquals(" make it bigger ", api.transcribe(emptyMap(), ByteArray(6400)))
        val req = server.takeRequest()
        assertEquals("/api/voice/transcribe", req.url.encodedPath)
        assertEquals(6400L, req.bodySize)
    }

    @Test
    fun events_read_from_since_and_skip_pings() {
        respond(200, ": chat\n\nevent: ping\ndata: 1\n\ndata: {\"seq\":5,\"type\":\"done\",\"conv\":\"c\"}\n\n", "Content-Type" to "text/event-stream")
        val got = mutableListOf<String>()
        api.events(emptyMap(), 4) { got += it.toString() }
        assertEquals(listOf("""{"seq":5,"type":"done","conv":"c"}"""), got)
        assertEquals("4", server.takeRequest().url.queryParameter("since"))
    }

    @Test
    fun sse_joins_multi_line_data() {
        val got = mutableListOf<Pair<String?, String>>()
        readSse(Buffer().writeUtf8("data: a\ndata: b\n\nevent: ping\ndata: 1\n\n")) { e, d -> got += e to d }
        assertEquals(listOf(null to "a\nb", "ping" to "1"), got)
    }

    @Test
    fun chat_seq_is_found_behind_large_values() {
        val json = """{"other":{"x":[1,2,{"seq":99}]},"chat":{"conversations":[{"id":"a","text":"a \"quoted\" } ] text","n":-1.5e3,"ok":true,"none":null}],"seq":33682,"open":true}}"""
        assertEquals(33682L, chatSeq(StringReader(json)))
        assertNull(chatSeq(StringReader("""{"chat":{"conversations":[]}}""")))
        assertNull(chatSeq(StringReader("null")))
    }

    @Test
    fun cookies_merge_and_expire() {
        val merged = mergeCookies(mapOf("a" to "1", "b" to "2"), listOf("a=3; Path=/", "b=; Max-Age=0", "c=4"))
        assertEquals(mapOf("a" to "3", "c" to "4"), merged)
    }
}
