package com.ethran.notable.sync.automerge

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.automerge.repo.Dialer
import org.automerge.repo.Transport
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** The server refused the session token on the WebSocket upgrade. Retrying won't help. */
class AuthFailedException : IOException("sync server rejected the session (401)")

/**
 * automerge-repo [Dialer] over an OkHttp WebSocket. Each binary frame is one repo message; the
 * repo (samod) runs the handshake itself. The token goes in `Authorization` on the upgrade, which
 * browsers can't do but OkHttp can.
 */
class OkHttpDialer(
    private val http: OkHttpClient,
    private val url: String,
    private val token: String,
    private val listener: Listener,
) : Dialer {

    interface Listener {
        fun onOpen()
        fun onClosed(reason: String)
        fun onAuthFailed()
    }

    override fun getUrl() = url

    override fun connect(): CompletableFuture<Transport> {
        val result = CompletableFuture<Transport>()
        val socket = CompletableFuture<WebSocket>()
        val closed = AtomicBoolean(false)
        val transport = Transport(
            { data -> if (!socket.get().send(data.toByteString())) throw IOException("websocket closed") },
            { socket.thenAccept { it.close(1000, null) } },
        )
        fun closeOnce(reason: String) {
            if (closed.compareAndSet(false, true)) {
                transport.onClose()
                listener.onClosed(reason)
            }
        }
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket.complete(webSocket)
                result.complete(transport)
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = transport.onMessage(bytes.toByteArray())

            override fun onMessage(webSocket: WebSocket, text: String) {
                // The repo protocol is binary only; a text frame means a confused peer.
                webSocket.close(1003, "binary frames only")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                closeOnce("closed by server ($code)")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closeOnce("closed ($code)")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401) {
                    result.completeExceptionally(AuthFailedException())
                    listener.onAuthFailed()
                    return
                }
                if (!result.isDone) result.completeExceptionally(t) else closeOnce(t.message ?: "connection lost")
            }
        })
        return result
    }
}
