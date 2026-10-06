package com.example.ava.sendspin

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

object SendspinPortChecker {
    private const val TIMEOUT_MS = 3_000

    sealed class PortCheckResult {
        data class PortOpen(val host: String, val port: Int) : PortCheckResult()
        data class PortClosed(val host: String, val port: Int) : PortCheckResult()
        data class ServerUnreachable(
            val host: String,
            val port: Int,
            val error: String
        ) : PortCheckResult()
    }

    suspend fun checkPort(host: String, port: Int): PortCheckResult {
        return try {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                PortCheckResult.PortOpen(host, port)
            } catch (e: SocketTimeoutException) {
                PortCheckResult.PortClosed(host, port)
            } catch (e: Exception) {
                PortCheckResult.PortClosed(host, port)
            } finally {
                runCatching { socket.close() }
            }
        } catch (e: Exception) {
            PortCheckResult.ServerUnreachable(host, port, e.message ?: "Unknown error")
        }
    }
}
