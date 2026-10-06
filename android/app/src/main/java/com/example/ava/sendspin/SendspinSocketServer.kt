package com.example.ava.sendspin

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Local WebSocket endpoint that Sendspin servers connect to.
 *
 * Per the Sendspin spec ([multi-server section](https://github.com/Sendspin/spec#multiple-servers))
 * the client (us) must NOT immediately replace an existing server connection
 * when a second one arrives; it must complete the handshake on the new
 * connection and then arbitrate which to keep based on `connection_reason`
 * and the last-played server id. To enable that, this server supports two
 * connection slots:
 *
 *  - **active**: the connection currently driving playback / receiving audio
 *  - **candidate(s)**: new connections that have just opened and are still in
 *    the spec's arbitration window
 *
 * The owning [SendspinManager] runs a [SendspinArbiter] over each candidate
 * and then calls [promoteCandidate] or [rejectCandidate] to enact the
 * decision.
 */
class SendspinSocketServer(
    private val listenPort: Int,
    private val path: String = "/sendspin",
    private val onListening: (() -> Unit)? = null,
    private val onClientConnected: (remoteLabel: String, sendText: (String) -> Boolean, sendBinary: (ByteArray) -> Boolean, closeConnection: (Int, String) -> Unit) -> Unit,
    private val onTextMessage: (String) -> Unit,
    private val onBinaryMessage: (ByteArray) -> Unit,
    private val onClientDisconnected: (String) -> Unit,
    private val onClientError: (Throwable) -> Unit,
    /**
     * Fired when a new connection arrives while [activeConnection] is already
     * in use. Receives a stable handle the manager can pass to
     * [promoteCandidate] / [rejectCandidate] after arbitration. The handle's
     * [SendspinArbiter.CandidateHandle.sendText] / closeConnection lambdas are
     * how the arbiter drives the mini-handshake.
     */
    private val onCandidateConnected: ((handle: SendspinArbiter.CandidateHandle) -> Unit)? = null,
    /** Text frames received from a candidate connection (handshake replies). */
    private val onCandidateText: ((handle: SendspinArbiter.CandidateHandle, message: String) -> Unit)? = null,
    /** Binary frames received from a candidate (encrypted server/hello). */
    private val onCandidateBinary: ((handle: SendspinArbiter.CandidateHandle, data: ByteArray) -> Unit)? = null,
    /** Candidate closed before arbitration finished. */
    private val onCandidateClosed: ((handle: SendspinArbiter.CandidateHandle) -> Unit)? = null
) : WebSocketServer(InetSocketAddress("0.0.0.0", listenPort)) {

    private val activeConnection = AtomicReference<WebSocket?>(null)
    private val candidateHandles = ConcurrentHashMap<WebSocket, SendspinArbiter.CandidateHandle>()
    @Volatile
    private var shuttingDown = false

    init {
        setReuseAddr(true)
    }

    override fun onStart() {
        shuttingDown = false
        onListening?.invoke()
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val resource = handshake.resourceDescriptor ?: "/"
        if (resource != path) {
            Log.w(TAG, "Rejecting sendspin socket with unexpected path: $resource")
            conn.close(1008, "unexpected_path")
            return
        }

        val remote = conn.remoteSocketAddress?.address?.hostAddress
            ?: conn.remoteSocketAddress?.hostString
            ?: "unknown"

        // No active connection yet → adopt this conn as the active one.
        if (activeConnection.compareAndSet(null, conn)) {
            onClientConnected(
                remote,
                sendTextFor(conn, remote, isActive = true),
                sendBinaryFor(conn, remote, isActive = true),
                closeFor(conn),
            )
            return
        }

        // Otherwise, treat as a candidate awaiting arbitration. Don't kick the
        // active connection here — that's the manager's call after running the
        // SendspinArbiter on the new connection's server/hello.
        if (onCandidateConnected == null) {
            // No arbiter wired up (e.g. tests) — fall back to legacy behaviour
            // (replace the active conn) so we don't deadlock.
            Log.w(TAG, "No arbiter wired; falling back to legacy replace for $remote")
            activeConnection.getAndSet(conn)?.close(1012, "replaced")
            onClientConnected(
                remote,
                sendTextFor(conn, remote, isActive = true),
                sendBinaryFor(conn, remote, isActive = true),
                closeFor(conn),
            )
            return
        }

        val handle = object : SendspinArbiter.CandidateHandle {
            override val remoteLabel: String = remote
            override fun sendText(message: String): Boolean =
                sendTextFor(conn, remote, isActive = false).invoke(message)
            override fun sendBinary(data: ByteArray): Boolean =
                sendBinaryFor(conn, remote, isActive = false).invoke(data)
            override fun closeConnection(code: Int, reason: String) {
                closeFor(conn).invoke(code, reason)
            }
        }
        candidateHandles[conn] = handle
        Log.i(TAG, "New candidate connection from $remote (active is busy)")
        onCandidateConnected.invoke(handle)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        if (activeConnection.get() === conn) {
            onTextMessage(message)
            return
        }
        val candidate = candidateHandles[conn] ?: return
        onCandidateText?.invoke(candidate, message)
    }

    override fun onMessage(conn: WebSocket, message: java.nio.ByteBuffer) {
        val data = ByteArray(message.remaining())
        message.get(data)
        if (activeConnection.get() === conn) {
            onBinaryMessage(data)
            return
        }
        val candidate = candidateHandles[conn] ?: return
        onCandidateBinary?.invoke(candidate, data)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        if (activeConnection.compareAndSet(conn, null)) {
            onClientDisconnected(reason.ifBlank { "closed:$code" })
            return
        }
        val candidate = candidateHandles.remove(conn)
        if (candidate != null) {
            onCandidateClosed?.invoke(candidate)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (shuttingDown && ex is java.net.BindException) {
            return
        }
        if (conn == null || activeConnection.get() === conn) {
            Log.e(TAG, "Inbound sendspin socket error", ex)
            onClientError(ex)
        } else {
            Log.w(TAG, "Candidate connection error", ex)
        }
    }

    /**
     * Promote an arbitration candidate to be the new active connection.
     * Returns false if the candidate is no longer connected.
     *
     * The previously active connection (if any) is closed with the given
     * websocket [oldActiveCloseCode] / [oldActiveCloseReason]. The manager
     * is expected to have already sent `client/goodbye{reason: 'another_server'}`
     * on the old conn before calling this.
     */
    fun promoteCandidate(
        handle: SendspinArbiter.CandidateHandle,
        oldActiveCloseCode: Int = 1000,
        oldActiveCloseReason: String = "another_server",
        onPromoted: (
            sendText: (String) -> Boolean,
            sendBinary: (ByteArray) -> Boolean,
            close: (Int, String) -> Unit,
        ) -> Unit
    ): Boolean {
        val conn = candidateHandles.entries.firstOrNull { it.value === handle }?.key ?: return false
        candidateHandles.remove(conn)
        val oldActive = activeConnection.getAndSet(conn)
        oldActive?.runCatching { close(oldActiveCloseCode, oldActiveCloseReason) }
        val remote = conn.remoteSocketAddress?.address?.hostAddress
            ?: conn.remoteSocketAddress?.hostString
            ?: handle.remoteLabel
        onPromoted(
            sendTextFor(conn, remote, isActive = true),
            sendBinaryFor(conn, remote, isActive = true),
            closeFor(conn),
        )
        return true
    }

    /** Reject an arbitration candidate (close its socket). */
    fun rejectCandidate(
        handle: SendspinArbiter.CandidateHandle,
        code: Int = 1000,
        reason: String = "another_server"
    ) {
        val conn = candidateHandles.entries.firstOrNull { it.value === handle }?.key
        if (conn != null) {
            candidateHandles.remove(conn)
            runCatching { conn.close(code, reason) }
        } else {
            // Already gone — best-effort goodbye on the lambda still in flight.
            runCatching { handle.closeConnection(code, reason) }
        }
    }

    fun stopBlocking(timeoutMs: Int = 4_000) {
        shuttingDown = true
        activeConnection.getAndSet(null)?.close(1001, "server_shutdown")
        for (conn in candidateHandles.keys.toList()) {
            runCatching { conn.close(1001, "server_shutdown") }
        }
        candidateHandles.clear()
        try {
            stop(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to stop sendspin socket server cleanly on port $listenPort", t)
        }
        // Java-WebSocket 1.5.4 stop() only joins the selector. The listen
        // channel is closed in doServerShutdown after five empty selects, so a
        // timed join can return while 8928 is still LISTEN. Close it here.
        forceReleaseListenChannel()
    }

    private fun forceReleaseListenChannel() {
        runCatching {
            val serverField = WebSocketServer::class.java.getDeclaredField("server")
            serverField.isAccessible = true
            (serverField.get(this) as? ServerSocketChannel)?.close()
        }.onFailure { t ->
            Log.w(TAG, "Could not force-close listen channel on port $listenPort", t)
        }
        runCatching {
            val threadField = WebSocketServer::class.java.getDeclaredField("selectorthread")
            threadField.isAccessible = true
            val selectorThread = threadField.get(this) as? Thread
            if (selectorThread != null &&
                selectorThread.isAlive &&
                selectorThread !== Thread.currentThread()
            ) {
                selectorThread.interrupt()
            }
        }
    }

    private fun sendTextFor(
        conn: WebSocket,
        remote: String,
        isActive: Boolean
    ): (String) -> Boolean = { message ->
        try {
            if (!conn.isOpen) {
                Log.d(TAG, "Skipping send on closed ${if (isActive) "active" else "candidate"} websocket for $remote")
                false
            } else {
                conn.send(message)
                true
            }
        } catch (t: Throwable) {
            if (t is org.java_websocket.exceptions.WebsocketNotConnectedException) {
                Log.d(TAG, "Send skipped, websocket no longer connected for $remote")
            } else {
                Log.e(TAG, "Failed to send sendspin message", t)
            }
            false
        }
    }

    private fun sendBinaryFor(
        conn: WebSocket,
        remote: String,
        isActive: Boolean
    ): (ByteArray) -> Boolean = { data ->
        try {
            if (!conn.isOpen) {
                Log.d(TAG, "Skipping binary send on closed ${if (isActive) "active" else "candidate"} websocket for $remote")
                false
            } else {
                conn.send(data)
                true
            }
        } catch (t: Throwable) {
            if (t is org.java_websocket.exceptions.WebsocketNotConnectedException) {
                Log.d(TAG, "Binary send skipped, websocket no longer connected for $remote")
            } else {
                Log.e(TAG, "Failed to send sendspin binary", t)
            }
            false
        }
    }

    private fun closeFor(conn: WebSocket): (Int, String) -> Unit = { code, reason ->
        runCatching { conn.close(code, reason) }
    }

    companion object {
        private const val TAG = "SendspinSocketServer"
    }
}
