package com.example.ava.server

import android.util.Log
import com.google.protobuf.MessageLite
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ServerException(message: String?, cause: Throwable? = null) :
    Throwable(message, cause)

/**
 * Everything that happens on the API socket, in the order it happened.
 *
 * Home Assistant may open a replacement socket before the previous one is known
 * to be dead (a Wi-Fi blip leaves the old one half-open). Each accepted client
 * therefore carries its own [sessionId], and the takeover is reported as a
 * plain event rather than inferred from a "someone is connected" boolean —
 * a boolean cannot distinguish "still the same client" from "a different one".
 */
sealed interface ServerEvent {
    val sessionId: Long
}

data class ClientAttached(override val sessionId: Long) : ServerEvent
data class ClientDetached(override val sessionId: Long) : ServerEvent
data class ClientMessage(override val sessionId: Long, val message: MessageLite) : ServerEvent

class Server(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val noisePsk: ByteArray? = null,
    private val deviceName: String = "",
    private val macAddress: String = "",
) : AutoCloseable {
    private val serverRef = AtomicReference<ServerSocket?>(null)
    private val connection = MutableStateFlow<ClientConnection?>(null)
    private val sessionCounter = AtomicLong(0)
    val isConnected = connection.map { it != null }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(port: Int = DEFAULT_SERVER_PORT): Flow<ServerEvent> = acceptClients(port)
        .catch { throw ServerException(it.message, it) }
        .flatMapMerge {
            connectClient(it)
        }
        .catch {
            if (it is ServerException) throw it
            Log.e(TAG, "Client connection error", it)
        }
        .flowOn(dispatcher)

    fun disconnectCurrentClient() {
        connection.value?.let { disconnectClient(it) }
    }

    private fun acceptClients(port: Int) = flow {
        var server: ServerSocket? = null
        try {
            server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("0.0.0.0", port))
            if (!serverRef.compareAndSet(null, server))
                error("Server already started")
            Log.i(
                TAG,
                "Server listening on 0.0.0.0:$port" +
                    if (noisePsk != null) " (Noise PSK)" else " (plaintext)",
            )

            while (true) {
                try {
                    
                    val clientSocket = withContext(Dispatchers.IO) {
                        server.accept()
                    }
                    emit(clientSocket)
                } catch (e: SocketException) {
                    if (server.isClosed) break
                    throw e
                }
            }
        } finally {
            server?.close()
            serverRef.compareAndSet(server, null)
        }
    }

    private fun connectClient(socket: Socket): Flow<ServerEvent> {
        val client = ClientConnection(socket, noisePsk, deviceName, macAddress)
        val sessionId = sessionCounter.incrementAndGet()
        return flow {
            // Install the client before announcing it: HA's handshake on the new
            // socket must never be answered on the socket it replaces.
            val replaced = connection.getAndUpdate { client }
            replaced?.close()
            // Finish Noise before ClientAttached. onConnected() sends API
            // messages (voice config, BLE proxy); those must not go out as
            // plaintext 0x00 frames while HA is still in HELLO/HANDSHAKE.
            client.completeHandshake()
            Log.i(
                TAG,
                "Client attached (session=$sessionId, peer=${client.getRemoteAddress()}" +
                    if (replaced != null) ", replacing a client that never closed)" else ")",
            )
            emit(ClientAttached(sessionId))
            emitAll(client.readMessages().map { ClientMessage(sessionId, it) })
        }.onCompletion { cause ->
            disconnectClient(client)
            // Always announce detach. handleServerEvent ignores a stale
            // session id, so a watchdog close (often CancellationException)
            // must not leave EspHomeDevice stuck on the dead session.
            Log.i(
                TAG,
                "Client detached (session=$sessionId" +
                    (if (cause != null) ", cause=${cause.javaClass.simpleName}" else "") +
                    ")",
            )
            try {
                emit(ClientDetached(sessionId))
            } catch (_: Exception) {
            }
        }
    }

    private fun disconnectClient(client: ClientConnection) {
        client.close()
        connection.compareAndSet(client, null)
    }

    suspend fun sendMessage(message: MessageLite) = withContext(dispatcher) {
        connection.value?.sendMessage(message)
    }
    
    fun getClientAddress(): String? {
        return connection.value?.getRemoteAddress()
    }

    fun isListening(): Boolean {
        val socket = serverRef.get() ?: return false
        return socket.isBound && !socket.isClosed
    }

    /** Bound local port, or null if not listening. Useful when bind used port 0 (ephemeral). */
    fun getListeningPort(): Int? {
        val socket = serverRef.get() ?: return null
        if (!socket.isBound || socket.isClosed) return null
        val port = socket.localPort
        return port.takeIf { it > 0 }
    }

    override fun close() {
        connection.getAndUpdate { null }?.close()
        serverRef.getAndSet(null)?.close()
    }

    companion object {
        const val TAG = "Server"
        const val DEFAULT_SERVER_PORT = 6053
    }
}
