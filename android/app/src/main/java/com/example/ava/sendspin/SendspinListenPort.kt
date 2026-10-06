package com.example.ava.sendspin

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Local listen-port probe for inbound Sendspin.
 *
 * Java-WebSocket [org.java_websocket.server.WebSocketServer.stop] joins the
 * selector thread but only closes [java.nio.channels.ServerSocketChannel] in
 * `doServerShutdown` after five empty selects. A timed join can return while
 * 8928 is still LISTEN. Fixed sleeps cannot observe that; this probe can.
 */
internal object SendspinListenPort {
    private const val TAG = "SendspinListenPort"

    fun isFree(port: Int): Boolean {
        return try {
            ServerSocket().use { probe ->
                probe.reuseAddress = true
                probe.bind(InetSocketAddress("0.0.0.0", port))
            }
            true
        } catch (_: java.net.BindException) {
            false
        } catch (t: Throwable) {
            Log.w(TAG, "Port $port probe failed: ${t.message}")
            false
        }
    }

    suspend fun awaitFree(
        port: Int,
        timeoutMs: Long = 5_000L,
        pollMs: Long = 50L,
    ): Boolean {
        if (isFree(port)) return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(pollMs)
            if (isFree(port)) return true
        }
        val free = isFree(port)
        if (!free) {
            Log.e(TAG, "Port $port still occupied after ${timeoutMs}ms")
        }
        return free
    }
}
