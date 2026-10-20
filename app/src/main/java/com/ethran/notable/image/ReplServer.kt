package com.ethran.notable.image

import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

private val log = ShipBook.getLogger("ImageRepl")

/**
 * The live REPL's WebSocket (ADR-002), bound to 127.0.0.1 only: a laptop reaches it through
 * `adb forward tcp:7357 tcp:7357`. Requests of one connection are answered in order.
 *
 * Browsers always send `Origin`; the CLI doesn't. A handshake with one is refused, so a web page
 * open on the device can't talk to the REPL, even before the token check.
 */
class ReplServer(
    port: Int,
    private val protocol: ReplProtocol,
    private val scope: CoroutineScope,
) : WebSocketServer(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) {
    private class Conn(val state: ReplProtocol.Connection = ReplProtocol.Connection(), val order: Mutex = Mutex())

    private val connections = ConcurrentHashMap<WebSocket, Conn>()

    init {
        isReuseAddr = true
        connectionLostTimeout = 60
    }

    override fun onWebsocketHandshakeReceivedAsServer(conn: WebSocket, draft: Draft, request: ClientHandshake): ServerHandshakeBuilder {
        if (request.hasFieldValue("Origin")) throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "browser origins are not accepted")
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        connections[conn] = Conn()
        log.i("REPL client connected")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        connections.remove(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val c = connections[conn] ?: return
        scope.launch {
            c.order.withLock {
                val reply = protocol.handle(c.state, message)
                if (conn.isOpen) conn.send(reply.text)
                if (reply.close) conn.close(CloseFrame.POLICY_VALIDATION, "unauthorized")
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        log.e("REPL socket error", ex)
    }

    override fun onStart() {
        log.i("REPL listening on ${address.hostString}:${address.port}")
    }

    companion object {
        const val PORT = 7357

        /** 128 random bits as hex, shown on the device. */
        fun newToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
