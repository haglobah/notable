package com.ethran.notable.sync.automerge

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class OkHttpDialerTest {
    private val server = MockWebServer()
    private val events = LinkedBlockingQueue<String>()
    private val listener = object : OkHttpDialer.Listener {
        override fun onOpen() { events += "open" }
        override fun onClosed(reason: String) { events += "closed" }
        override fun onAuthFailed() { events += "auth-failed" }
    }

    /** The server end of the socket, for sending frames to the client. */
    private val serverSockets = LinkedBlockingQueue<WebSocket>()
    private val serverReceived = LinkedBlockingQueue<ByteString>()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun dialer() = OkHttpDialer(OkHttpClient(), server.url("/sync").toString().replace("http", "ws"), "tok", listener)

    private fun acceptUpgrade() = server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { serverSockets += webSocket }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { serverReceived += bytes }
    }).build())

    @Test
    fun frames_pass_through_whole_in_both_directions() {
        acceptUpgrade()
        val transport = dialer().connect().get(5, TimeUnit.SECONDS)
        assertEquals("Bearer tok", server.takeRequest().headers["Authorization"])
        assertEquals("open", events.poll(5, TimeUnit.SECONDS))

        val received = LinkedBlockingQueue<ByteArray>()
        transport.connect({ received += it }, {})
        val big = ByteArray(200_000) { (it * 7).toByte() }
        serverSockets.take().send(big.toByteString())
        assertArrayEquals(big, received.poll(5, TimeUnit.SECONDS))

        transport.send(byteArrayOf(9, 8, 7))
        assertArrayEquals(byteArrayOf(9, 8, 7), serverReceived.poll(5, TimeUnit.SECONDS)!!.toByteArray())
    }

    @Test
    fun a_server_close_reaches_the_transport() {
        acceptUpgrade()
        val transport = dialer().connect().get(5, TimeUnit.SECONDS)
        val closed = LinkedBlockingQueue<Unit>()
        transport.connect({}, { closed += Unit })
        serverSockets.take().close(1000, "bye")
        assertEquals(Unit, closed.poll(5, TimeUnit.SECONDS))
        assertTrue(generateSequence { events.poll(1, TimeUnit.SECONDS) }.take(3).contains("closed"))
    }

    @Test
    fun a_401_upgrade_reports_auth_failure_and_fails_the_dial() {
        server.enqueue(MockResponse.Builder().code(401).body("Unauthorized").build())
        val e = runCatching { dialer().connect().get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue(e.toString(), e is ExecutionException && e.cause is AuthFailedException)
        assertEquals("auth-failed", events.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun an_unreachable_server_fails_the_dial_without_auth_failure() {
        server.close()
        val e = runCatching { dialer().connect().get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue(e.toString(), e is ExecutionException && e.cause !is AuthFailedException)
        assertTrue(events.none { it == "auth-failed" })
    }
}
