package com.ethran.notable.image

import com.ethran.notable.sync.automerge.AmCodeDef
import com.ethran.notable.sync.automerge.AutomergeSchema
import com.ethran.notable.sync.automerge.CodeDocState
import com.ethran.notable.sync.automerge.CodeDocWriter
import com.ethran.notable.sync.automerge.CodeDocs
import com.ethran.notable.sync.automerge.FixturePaths
import com.ethran.notable.sync.automerge.MemoryLocalDocs
import com.ethran.notable.utils.AppResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.automerge.Document
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.Base64
import java.util.Date
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The REPL protocol (ADR-002) against a real image and an in-memory code doc that "syncs" back. */
class ReplProtocolTest {
    /** The code doc on this device; every write comes back through [code], as the sync client's does. */
    internal class FakeCodeDocs : CodeDocs {
        val docs = MemoryLocalDocs()
        private val writer = CodeDocWriter(docs)
        override val code = MutableStateFlow<CodeDocState>(CodeDocState.None)
        private fun publish() {
            code.value = writer.codeDocUrl()?.let { CodeDocState.Ready(it, writer.read()!!) } ?: CodeDocState.None
        }
        override suspend fun define(def: AmCodeDef) = AppResult.Success(writer.define(def)).also { publish() }
        override suspend fun undefine(name: String) = AppResult.Success(writer.undefine(name)).also { publish() }
        override suspend fun codeDocBytes() = AppResult.Success(writer.save())
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtime = JsRuntime(JsRuntime.Limits(evalTimeoutMs = 500))
    private val image = LiveImage(runtime, { File(FixturePaths.dir, "../runtime/prelude.js").readText() }, LiveImageTest.FakeHost(), emptyList())
    private val docs = FakeCodeDocs()
    private var clock = 1_000L
    private val protocol = ReplProtocol(TOKEN, image, docs, "android:test", now = { Date(clock++) })

    init {
        runBlocking { image.start(null) }
        scope.launch { docs.code.collect { (it as? CodeDocState.Ready)?.let { r -> image.apply(r.image.defs) } } }
    }

    @After
    fun close() {
        scope.cancel()
        runtime.close()
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.ok() = getValue("ok").jsonPrimitive.boolean

    private fun authorized() = ReplProtocol.Connection().also { c ->
        runBlocking { assertTrue(json(protocol.handle(c, """{"id":0,"op":"hello","token":"$TOKEN"}""").text).ok()) }
    }

    private fun call(c: ReplProtocol.Connection, request: String) = runBlocking { json(protocol.handle(c, request).text) }

    @Test
    fun nothing_works_before_hello_with_the_right_token() = runBlocking {
        val c = ReplProtocol.Connection()
        val before = protocol.handle(c, """{"id":1,"op":"eval","code":"1"}""")
        assertTrue(before.close)
        assertEquals("unauthorized", json(before.text).s("error"))
        val wrong = protocol.handle(c, """{"id":2,"op":"hello","token":"nope"}""")
        assertTrue(wrong.close)
        assertFalse(c.authorized)
        val hello = json(protocol.handle(c, """{"id":3,"op":"hello","token":"$TOKEN"}""").text)
        assertEquals("3", hello.s("id"))
        assertEquals("android:test", hello.s("device"))
        assertEquals("$HOST_API", hello.s("hostApi"))
    }

    @Test
    fun def_commits_to_the_code_doc_and_reports_how_it_evaluated_here() {
        val c = authorized()
        val loaded = call(c, """{"id":1,"op":"def","name":"command/hi","source":"() => 'hi'"}""")
        assertEquals("loaded", loaded.s("state"))
        assertEquals("() => 'hi'", AutomergeSchema.readCode(docs.docs.docs.getValue(docs.docs.library().codeDocUrl!!)).defs.getValue("command/hi").source)
        val failed = call(c, """{"id":2,"op":"def","name":"command/bad","source":"(() => {\n throw new Error('x')\n})()"}""")
        assertEquals("failed", failed.s("state"))
        assertTrue(failed.s("error"), failed.s("error").startsWith("def:command/bad:2"))
        val ls = call(c, """{"id":3,"op":"ls"}""")
        assertEquals(listOf("command/bad" to "failed", "command/hi" to "loaded"),
            ls.getValue("defs").jsonArray.map { it.jsonObject.s("name") to it.jsonObject.s("state") })
        assertEquals("ready", ls.s("codeDoc"))
        val show = call(c, """{"id":4,"op":"show","name":"command/hi"}""").getValue("def").jsonObject
        assertEquals("() => 'hi'", show.s("source"))
        assertEquals("android:test", show.s("device"))
    }

    @Test
    fun def_refuses_bad_names_and_newer_host_apis_fail_on_this_device() {
        val c = authorized()
        assertFalse(call(c, """{"id":1,"op":"def","name":"nokind","source":"1"}""").ok())
        val future = call(c, """{"id":2,"op":"def","name":"command/f","source":"() => 1","hostApi":${HOST_API + 1}}""")
        assertEquals("failed", future.s("state"))
        assertTrue(future.s("error").startsWith("needs host API"))
    }

    @Test
    fun eval_runs_live_and_writes_nothing() {
        val c = authorized()
        val r = call(c, """{"id":1,"op":"eval","code":"notable.log('x'); 6 * 7"}""")
        assertEquals("42", r.s("value"))
        assertEquals(listOf("x"), r.getValue("logs").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(CodeDocState.None, docs.code.value)
        val e = call(c, """{"id":2,"op":"eval","code":"null.x"}""")
        assertFalse(e.ok())
        assertTrue(e.s("error").isNotBlank())
    }

    @Test
    fun undef_and_doc_history() {
        val c = authorized()
        call(c, """{"id":1,"op":"def","name":"command/a","source":"() => 1"}""")
        call(c, """{"id":2,"op":"def","name":"command/a","source":"() => 2"}""")
        assertEquals("true", call(c, """{"id":3,"op":"undef","name":"command/a"}""").s("existed"))
        assertEquals("false", call(c, """{"id":4,"op":"undef","name":"command/a"}""").s("existed"))
        assertTrue(call(c, """{"id":5,"op":"ls"}""").getValue("defs").jsonArray.isEmpty())
        val doc = Document.load(Base64.getDecoder().decode(call(c, """{"id":6,"op":"doc"}""").s("doc")))
        assertTrue(AutomergeSchema.readCode(doc).defs.isEmpty())
        assertEquals("unknown op 'nope'", call(c, """{"id":7,"op":"nope"}""").s("error"))
    }

    /* The socket: 127.0.0.1 only, the token, and no browser origins. */

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private class Client(url: String, origin: String? = null) {
        val messages = LinkedBlockingQueue<String>()
        val closed = LinkedBlockingQueue<String>()
        val ws: WebSocket = OkHttpClient().newWebSocket(
            Request.Builder().url(url).apply { origin?.let { header("Origin", it) } }.build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { messages += text }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { closed += "$code" }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed += "failure ${response?.code}" }
            },
        )
        fun next() = messages.poll(5, TimeUnit.SECONDS)
    }

    @Test
    fun the_socket_speaks_the_protocol_and_closes_on_a_wrong_token() {
        val port = freePort()
        val server = ReplServer(port, protocol, scope).apply { start() }
        try {
            Thread.sleep(300)
            val good = Client("ws://127.0.0.1:$port")
            good.ws.send("""{"id":1,"op":"hello","token":"$TOKEN"}""")
            assertTrue(json(good.next()).ok())
            good.ws.send("""{"id":2,"op":"eval","code":"[1,2].map(x => x * 2)"}""")
            assertEquals("[2,4]", json(good.next()).s("value"))
            good.ws.close(1000, null)

            val bad = Client("ws://127.0.0.1:$port")
            bad.ws.send("""{"id":1,"op":"hello","token":"wrong"}""")
            assertEquals("unauthorized", json(bad.next()).s("error"))
            assertNotNull(bad.closed.poll(5, TimeUnit.SECONDS))

            val browser = Client("ws://127.0.0.1:$port", origin = "https://evil.example")
            assertTrue(browser.closed.poll(5, TimeUnit.SECONDS)!!.startsWith("failure"))
            assertEquals(null, browser.messages.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            server.stop(1000)
        }
    }

    @Test
    fun the_socket_listens_on_loopback_only() {
        val port = freePort()
        val server = ReplServer(port, protocol, scope)
        assertTrue(server.address.address.isLoopbackAddress)
    }

    companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
    }
}
