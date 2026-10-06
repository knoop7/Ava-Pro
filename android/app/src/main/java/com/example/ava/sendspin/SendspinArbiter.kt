package com.example.ava.sendspin

import android.content.Context
import android.util.Log
import com.example.ava.sendspin.noise.SendspinLegacyGate
import com.example.ava.sendspin.noise.SendspinNoiseFailure
import com.example.ava.sendspin.noise.SendspinNoiseHandshake
import com.example.ava.sendspin.noise.SendspinNoiseIdentity
import com.example.ava.sendspin.noise.SendspinNoiseTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Sendspin spec [multi-server](https://github.com/Sendspin/spec/blob/main/README.md#multiple-servers)
 * arbiter.
 *
 * When a second Sendspin server connects while the client is already attached
 * to one, the spec requires the client to:
 *
 * 1. Complete the handshake (`client/hello` → `server/hello`) with the new
 *    server before deciding anything.
 * 2. Compare `connection_reason` (`'discovery'` vs `'playback'`) and the
 *    persisted "last played server id" to pick a winner.
 * 3. Send `client/goodbye{reason: 'another_server'}` to the loser before
 *    closing the connection.
 *
 * This class implements steps 1+2; the caller wires step 3 via [onDecision].
 *
 * Each candidate connection is identified by an opaque [tag] (typically the
 * underlying [org.java_websocket.WebSocket] object). The transport layer
 * provides `sendText`/`close` lambdas so the arbiter can drive the mini
 * handshake without owning the socket.
 */
class SendspinArbiter(
    private val context: Context,
    private val clientId: String,
    private val clientName: String,
    private val isLowMemoryDevice: Boolean,
    private val preferredFormat: String = "automatic",
    private val scope: CoroutineScope,
    /** Returns the persisted server_id of the last server that played, or "". */
    private val getLastPlayedServerId: () -> String,
    /**
     * Returns the currently active server (server_id, connection_reason) pair,
     * or null if no incumbent. Used as the tie-breaker baseline.
     */
    private val getIncumbentInfo: () -> Pair<String, String>?,
    /** Callback fired exactly once per candidate. */
    private val onDecision: (Decision) -> Unit
) {
    /** Identifying token for a candidate connection. Opaque to the arbiter. */
    interface CandidateHandle {
        val remoteLabel: String
        fun sendText(message: String): Boolean
        fun sendBinary(data: ByteArray): Boolean
        fun closeConnection(code: Int, reason: String)
    }

    /** Decision the caller must enact. */
    sealed class Decision {
        /**
         * Keep the existing connection; reject the candidate. Caller should
         * call [CandidateHandle.sendText] with a `client/goodbye` then
         * [CandidateHandle.closeConnection].
         */
        data class KeepIncumbent(
            val candidate: CandidateHandle,
            val candidateServerId: String,
            val candidateConnectionReason: String,
            val rationale: String,
            val noiseTransport: SendspinNoiseTransport? = null,
        ) : Decision()

        /**
         * Switch to the candidate; close the incumbent. Caller should send a
         * `client/goodbye{reason: 'another_server'}` on the incumbent, then
         * promote the candidate as the new active transport.
         */
        data class Switch(
            val candidate: CandidateHandle,
            val candidateServerId: String,
            val candidateConnectionReason: String,
            /** Full `server/hello` payload from the arbitration handshake. */
            val serverHelloPayload: JSONObject,
            val rationale: String,
            val noiseTransport: SendspinNoiseTransport? = null,
        ) : Decision()

        /** Candidate failed to complete handshake within the timeout. */
        data class Timeout(val candidate: CandidateHandle) : Decision()
    }

    private data class Pending(
        val handle: CandidateHandle,
        val timeoutJob: Job,
        val handshake: SendspinNoiseHandshake? = null,
        var transport: SendspinNoiseTransport? = null,
        /**
         * Encrypted path: `server/hello` already received and `client/hello`
         * sent; waiting for the first `server/activate`, which is where the
         * spec puts `activities` (the `connection_reason` replacement) and
         * `active_roles`.
         */
        var encryptedHello: JSONObject? = null,
    )

    private val pending = ConcurrentHashMap<CandidateHandle, Pending>()

    fun acceptCandidate(handle: CandidateHandle) {
        Log.i(TAG, "Accepting arbitration candidate ${handle.remoteLabel}")

        if (SendspinLegacyGate.preferLegacy()) {
            startLegacyHello(handle)
            return
        }
        val hs = SendspinNoiseHandshake(
            identity = SendspinNoiseIdentity.get(context),
            sendText = { handle.sendText(it) },
        )
        if (!hs.start()) {
            Log.w(TAG, "Failed to send client/init to ${handle.remoteLabel}; trying legacy hello")
            SendspinLegacyGate.armAfterFailedInit()
            startLegacyHello(handle)
            return
        }
        armTimeout(handle, handshake = hs)
    }

    private fun startLegacyHello(handle: CandidateHandle) {
        if (!sendLegacyHello(handle)) {
            handle.closeConnection(1011, "arbitration_send_failed")
            return
        }
        armTimeout(handle, handshake = null)
    }

    private fun startLegacyHelloWithoutTimeout(handle: CandidateHandle) {
        sendLegacyHello(handle)
    }

    private fun sendLegacyHello(handle: CandidateHandle): Boolean {
        val helloPayload = SendspinClient.buildClientHelloPayload(
            context = context,
            clientId = clientId,
            clientName = clientName,
            isLowMemoryDevice = isLowMemoryDevice,
            preferredFormat = preferredFormat
        )
        val helloMessage = JSONObject()
            .put("type", "client/hello")
            .put("payload", helloPayload)
            .toString()
        val sent = runCatching { handle.sendText(helloMessage) }.getOrDefault(false)
        if (!sent) {
            Log.w(TAG, "Failed to send arbitration client/hello to ${handle.remoteLabel}")
        }
        return sent
    }

    private fun armTimeout(handle: CandidateHandle, handshake: SendspinNoiseHandshake?) {
        val timeoutJob = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            val popped = pending.remove(handle) ?: return@launch
            val hello = popped.encryptedHello
            val transport = popped.transport
            if (hello != null && transport != null) {
                // Hellos exchanged but no server/activate: decide on the hello alone.
                Log.w(TAG, "No server/activate from ${popped.handle.remoteLabel}; deciding without it")
                finishHello(popped.handle, hello, transport)
                return@launch
            }
            Log.w(TAG, "Arbitration handshake timed out for ${popped.handle.remoteLabel}")
            if (popped.handshake != null && popped.transport == null) {
                SendspinLegacyGate.armAfterFailedInit()
            }
            onDecision(Decision.Timeout(popped.handle))
        }
        pending[handle] = Pending(handle, timeoutJob, handshake)
    }

    /**
     * Feed a text frame received on a candidate connection. The arbiter only
     * cares about `server/hello`; everything else is ignored (the connection
     * is still in handshake state per spec).
     */
    fun onCandidateMessage(handle: CandidateHandle, text: String) {
        val pendingEntry = pending[handle] ?: return
        val hs = pendingEntry.handshake
        if (hs != null && pendingEntry.transport == null) {
            when (val ev = hs.onText(text)) {
                is SendspinNoiseHandshake.Event.Waiting -> return
                is SendspinNoiseHandshake.Event.Established -> {
                    pendingEntry.transport = ev.transport
                    SendspinLegacyGate.clear()
                    return
                }
                is SendspinNoiseHandshake.Event.LegacyHello -> {
                    pendingEntry.timeoutJob.cancel()
                    pending.remove(handle)
                    SendspinLegacyGate.armAfterFailedInit()
                    startLegacyHelloWithoutTimeout(handle)
                    finishHello(handle, ev.payload)
                    return
                }
                is SendspinNoiseHandshake.Event.Failed -> {
                    pendingEntry.timeoutJob.cancel()
                    pending.remove(handle)
                    Log.w(TAG, "Sendspin Noise handshake failed: ${ev.reason}")
                    SendspinLegacyGate.armAfterRejectedNoiseFrame()
                    onDecision(Decision.Timeout(handle))
                    return
                }
            }
        }
        if (pendingEntry.transport != null) return
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (obj.optString("type") != "server/hello") return
        pending.remove(handle)
        pendingEntry.timeoutJob.cancel()
        finishHello(handle, obj.optJSONObject("payload") ?: JSONObject())
    }

    fun onCandidateBinary(handle: CandidateHandle, data: ByteArray) {
        val pendingEntry = pending[handle] ?: return
        val transport = pendingEntry.transport ?: return
        val plain = try {
            transport.decryptFrame(data) ?: return
        } catch (_: SendspinNoiseFailure) {
            pending.remove(handle)
            pendingEntry.timeoutJob.cancel()
            onDecision(Decision.Timeout(handle))
            return
        }
        if (plain.isEmpty() || plain[0].toInt() and 0xFF != SendspinNoiseTransport.MSG_JSON) return
        val text = String(plain, 1, plain.size - 1, Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val payload = obj.optJSONObject("payload") ?: JSONObject()
        when (obj.optString("type")) {
            "server/hello" -> {
                if (pendingEntry.encryptedHello != null) return
                pendingEntry.encryptedHello = payload
                sendEncryptedHello(handle, transport)
                // aiosendspin sends server/activate right after ingesting our
                // hello; decide once it arrives so `activities` can be compared.
            }
            "server/activate" -> {
                val hello = pendingEntry.encryptedHello ?: return
                pending.remove(handle)
                pendingEntry.timeoutJob.cancel()
                finishHello(handle, mergeActivate(hello, payload), transport)
            }
            else -> return
        }
    }

    /**
     * Fold the first `server/activate` into the hello payload the client will
     * adopt: `activities`/`active_roles` verbatim, plus a synthesized
     * `connection_reason` so the legacy tie-break below keeps working.
     */
    private fun mergeActivate(hello: JSONObject, activate: JSONObject): JSONObject {
        val merged = JSONObject(hello.toString())
        activate.optJSONArray("active_roles")?.let { merged.put("active_roles", it) }
        val activities = activate.optJSONArray("activities")
        if (activities != null) {
            merged.put("activities", activities)
            val playback = (0 until activities.length()).any { activities.optString(it) == "playback" }
            merged.put("connection_reason", if (playback) "playback" else "discovery")
        }
        return merged
    }

    private fun sendEncryptedHello(handle: CandidateHandle, transport: SendspinNoiseTransport) {
        val hello = SendspinClient.buildClientHelloPayload(
            context = context,
            clientId = clientId,
            clientName = clientName,
            isLowMemoryDevice = isLowMemoryDevice,
            preferredFormat = preferredFormat,
            includeLegacyClientId = false,
        )
        val json = JSONObject().put("type", "client/hello").put("payload", hello).toString()
        runCatching {
            transport.encryptJson(json).forEach { handle.sendBinary(it) }
        }
    }

    private fun finishHello(
        handle: CandidateHandle,
        payload: JSONObject,
        transport: SendspinNoiseTransport? = null,
    ) {
        // Encrypted server/hello has no server_id; use the handshake-authenticated one.
        val serverId = payload.optString("server_id", "")
            .ifEmpty { transport?.serverId.orEmpty() }
        val connectionReason = payload.optString("connection_reason", "discovery")
            .ifBlank { "discovery" }
        onDecision(decide(handle, serverId, connectionReason, payload, transport))
    }

    /** Drop a candidate that closed (or errored) before the decision. */
    fun onCandidateClosed(handle: CandidateHandle) {
        val popped = pending.remove(handle) ?: return
        popped.timeoutJob.cancel()
        if (popped.handshake != null && popped.transport == null) {
            SendspinLegacyGate.armAfterFailedInit()
        }
    }

    /** Drop all pending candidates (e.g. component teardown). */
    fun shutdown() {
        for (entry in pending.values) entry.timeoutJob.cancel()
        pending.clear()
    }

    /**
     * Apply the Sendspin spec tie-breaker.
     *
     *  - new=playback                                     → switch
     *  - new=discovery & existing=playback                → keep existing
     *  - both=discovery & new matches last_played_server  → switch
     *  - both=discovery & existing matches                → keep existing
     *  - both=discovery & no history                      → keep existing
     */
    private fun decide(
        handle: CandidateHandle,
        candidateServerId: String,
        candidateConnectionReason: String,
        serverHelloPayload: JSONObject,
        noiseTransport: SendspinNoiseTransport? = null,
    ): Decision {
        val incumbent = getIncumbentInfo()
        if (incumbent == null) {
            return Decision.Switch(
                candidate = handle,
                candidateServerId = candidateServerId,
                candidateConnectionReason = candidateConnectionReason,
                serverHelloPayload = serverHelloPayload,
                rationale = "no_incumbent",
                noiseTransport = noiseTransport,
            )
        }
        val (incumbentId, incumbentReason) = incumbent

        return when {
            candidateConnectionReason == "playback" -> Decision.Switch(
                candidate = handle,
                candidateServerId = candidateServerId,
                candidateConnectionReason = candidateConnectionReason,
                serverHelloPayload = serverHelloPayload,
                rationale = "new_connection_reason_playback",
                noiseTransport = noiseTransport,
            )

            candidateConnectionReason == "discovery" && incumbentReason == "playback" ->
                Decision.KeepIncumbent(
                    candidate = handle,
                    candidateServerId = candidateServerId,
                    candidateConnectionReason = candidateConnectionReason,
                    rationale = "incumbent_is_playback",
                    noiseTransport = noiseTransport,
                )

            else -> {
                val lastPlayed = getLastPlayedServerId()
                when {
                    lastPlayed.isNotEmpty() && candidateServerId == lastPlayed ->
                        Decision.Switch(
                            candidate = handle,
                            candidateServerId = candidateServerId,
                            candidateConnectionReason = candidateConnectionReason,
                            serverHelloPayload = serverHelloPayload,
                            rationale = "candidate_matches_last_played",
                            noiseTransport = noiseTransport,
                        )
                    lastPlayed.isNotEmpty() && incumbentId == lastPlayed ->
                        Decision.KeepIncumbent(
                            candidate = handle,
                            candidateServerId = candidateServerId,
                            candidateConnectionReason = candidateConnectionReason,
                            rationale = "incumbent_matches_last_played",
                            noiseTransport = noiseTransport,
                        )
                    else ->
                        Decision.KeepIncumbent(
                            candidate = handle,
                            candidateServerId = candidateServerId,
                            candidateConnectionReason = candidateConnectionReason,
                            rationale = "both_discovery_no_history",
                            noiseTransport = noiseTransport,
                        )
                }
            }
        }
    }

    companion object {
        private const val TAG = "SendspinArbiter"
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
    }
}
