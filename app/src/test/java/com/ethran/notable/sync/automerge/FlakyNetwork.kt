package com.ethran.notable.sync.automerge

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A TCP proxy in front of a server, standing in for a device's wifi. [wifiOff] cuts every
 * connection the way a phone losing its network does: the device's side closes at once, while the
 * server's side stays open and hears nothing more (it finds out only by its keep-alive). New
 * connections are refused until [wifiOn].
 */
internal class FlakyNetwork(private val target: InetSocketAddress) : AutoCloseable {
    private val listener = ServerSocket(0)
    val port: Int get() = listener.localPort
    @Volatile private var online = true
    private val links = CopyOnWriteArrayList<Link>()

    private inner class Link(val device: Socket, val server: Socket) {
        @Volatile var dead = false
        fun pump(from: InputStream, to: OutputStream) = thread(isDaemon = true) {
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    if (dead) continue // swallowed: the other side never hears of it
                    to.write(buf, 0, n)
                    to.flush()
                }
            } catch (_: Exception) {
            }
            if (!dead) close()
        }

        fun close() {
            runCatching { device.close() }
            runCatching { server.close() }
            links.remove(this)
        }
    }

    init {
        thread(isDaemon = true) {
            while (!listener.isClosed) {
                val device = try {
                    listener.accept()
                } catch (_: Exception) {
                    break
                }
                if (!online) {
                    device.close()
                    continue
                }
                val server = Socket().apply { connect(target) }
                val link = Link(device, server)
                links += link
                link.pump(device.getInputStream(), server.getOutputStream())
                link.pump(server.getInputStream(), device.getOutputStream())
            }
        }
    }

    fun wifiOff() {
        online = false
        for (l in links) {
            l.dead = true
            runCatching { l.device.close() }
            links.remove(l)
        }
    }

    fun wifiOn() {
        online = true
    }

    override fun close() {
        runCatching { listener.close() }
        links.forEach { it.close() }
    }
}
