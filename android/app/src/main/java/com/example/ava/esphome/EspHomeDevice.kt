package com.example.ava.esphome

import android.os.SystemClock
import android.util.Log
import com.example.ava.esphome.entities.Entity
import com.example.ava.server.ClientAttached
import com.example.ava.server.ClientDetached
import com.example.ava.server.ClientMessage
import com.example.ava.server.Server
import com.example.ava.server.ServerEvent
import com.example.ava.server.ServerException
import com.example.ava.server.noise.EspHomeNoisePsk
import com.example.ava.settings.normalizeEspNodeName
import com.example.esphomeproto.api.ConnectRequest
import com.example.esphomeproto.api.DeviceInfoRequest
import com.example.esphomeproto.api.DeviceInfoResponse
import com.example.esphomeproto.api.DisconnectRequest
import com.example.esphomeproto.api.HelloRequest
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.PingRequest
import com.example.esphomeproto.api.PingResponse
import com.example.esphomeproto.api.SubscribeHomeAssistantStatesRequest
import com.example.esphomeproto.api.SubscribeStatesRequest
import com.example.esphomeproto.api.connectResponse
import com.example.esphomeproto.api.disconnectResponse
import com.example.esphomeproto.api.helloResponse
import com.example.esphomeproto.api.listEntitiesDoneResponse
import com.example.esphomeproto.api.pingResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlin.coroutines.CoroutineContext

interface EspHomeState
data object Connected : EspHomeState
data object Disconnected : EspHomeState
data object Stopped : EspHomeState
data class ServerError(val message: String) : EspHomeState

@OptIn(ExperimentalCoroutinesApi::class)
abstract class EspHomeDevice(
    coroutineContext: CoroutineContext,
    protected val name: String,
    protected val port: Int = Server.DEFAULT_SERVER_PORT,
    entities: Iterable<Entity> = emptyList(),
    encryptionKey: String = "",
    noiseMacAddress: String = "",
) : AutoCloseable {
    protected val server = Server(
        noisePsk = EspHomeNoisePsk.decodeOrNull(encryptionKey),
        deviceName = normalizeEspNodeName(name),
        macAddress = noiseMacAddress,
    )
    protected val entities = entities.toMutableList()
    /**
     * Keys from the constructor [buildEntities] snapshot (and later
     * [replaceStaticEntities] rebuilds). Runtime addEntity targets
     * (STT, camera, mods) must never be wiped by a settings rediscover.
     */
    private val staticEntityKeys = this.entities.map { it.key }.toMutableSet()
    // Starts as Stopped: the device is constructed but not yet listening.
    // start() → startServer() keeps it at Stopped until a client connects
    // (onConnected sets Connected). Disconnected is only reached when a
    // previously-connected client drops, which is semantically different.
    protected val _state = MutableStateFlow<EspHomeState>(Stopped)
    val state = _state.asStateFlow()
    protected val isSubscribedToEntityState = MutableStateFlow(false)
    /**
     * Bumped on every SubscribeStates (and live add/remove) so collectors restart
     * even when [isSubscribedToEntityState] is already true. StateFlow would
     * otherwise swallow a duplicate `true` and skip the ESPHome state dump HA
     * needs after a TCP client swap / Wi-Fi blip.
     */
    private val entityStateSubscriptionGeneration = MutableStateFlow(0)

    /** Session whose events are currently honored; 0 while no client is attached. */
    @Volatile
    private var currentSessionId = 0L

    /** [SystemClock.elapsedRealtime] of the last message the live session sent. */
    @Volatile
    private var lastInboundElapsedMs = 0L
    /** Consecutive stale-watchdog ticks while idle past the timeout; reset on traffic. */
    @Volatile
    private var staleClientConfirmations = 0
    /** False until the session decrypts a real API frame (HelloRequest). */
    @Volatile
    private var receivedHaApiFrame = false

    /** API version the connected client announced in its HelloRequest; 0 until it arrives. */
    @Volatile
    private var clientApiVersionMajor = 0
    @Volatile
    private var clientApiVersionMinor = 0

    /**
     * Home Assistant only answers service calls that set `wants_response` from
     * aioesphomeapi 42 (API 1.13) onward; older clients drop them silently.
     */
    protected fun clientSupportsHaActionResponses(): Boolean =
        clientApiVersionMajor > 1 ||
            (clientApiVersionMajor == 1 && clientApiVersionMinor >= 13)

    protected val scope = CoroutineScope(
        coroutineContext + Job(coroutineContext.job) + CoroutineName("${this.javaClass.simpleName} Scope")
    )

    /** Live ESPHome list. Unpublished features are not in this snapshot. */
    fun snapshotEntities(): List<Entity> = entities.toList()

    fun addEntity(entity: Entity) {
        if (!entities.contains(entity)) {
            entities.add(entity)
            if (isSubscribedToEntityState.value) {
                bumpEntityStateSubscription()
            }
        }
    }

    fun removeEntity(entity: Entity) {
        if (entities.remove(entity)) {
            if (isSubscribedToEntityState.value) {
                bumpEntityStateSubscription()
            }
        }
    }

    /**
     * Diff the settings-driven entity snapshot. Existing keys keep the live
     * instance (subscriptions stay). Keys that left the snapshot are removed.
     * Runtime-only entities are left alone.
     */
    fun replaceStaticEntities(newStatic: List<Entity>): Pair<Int, Int> {
        val newKeys = newStatic.map { it.key }.toSet()
        val currentKeys = entities.map { it.key }.toSet()
        val obsolete = entities.filter { it.key in staticEntityKeys && it.key !in newKeys }
        obsolete.forEach { removeEntity(it) }
        var added = 0
        for (entity in newStatic) {
            if (entity.key !in currentKeys) {
                addEntity(entity)
                added++
            }
        }
        staticEntityKeys.clear()
        staticEntityKeys.addAll(newKeys)
        return added to obsolete.size
    }

    /** Drop the HA native-API client so it reconnects and ListEntities. */
    fun requestHaClientReconnect() {
        server.disconnectCurrentClient()
    }

    private fun bumpEntityStateSubscription() {
        entityStateSubscriptionGeneration.update { it + 1 }
    }

    open fun start() {
        // Server is now listening — no client yet, so we are Disconnected
        // (waiting for HA to connect). Stopped → Disconnected → Connected.
        _state.value = Disconnected
        startServer()
        startStaleClientWatchdog()
        listenForEntityStateChanges()
    }

    fun isApiServerListening(): Boolean = server.isListening()

    fun getApiServerListeningPort(): Int? = server.getListeningPort()

    protected abstract suspend fun getDeviceInfo(): DeviceInfoResponse

    private fun startServer() {
        server.start(port)
            .onEach { handleServerEvent(it) }
            .catch { e ->
                if (e !is ServerException) throw e
                Log.e(TAG, "Server error: ${e.message}", e)
                _state.value = ServerError(e.message ?: "Unknown error")
            }
            .launchIn(scope)
    }

    /**
     * Single ordered consumer of the socket's lifetime. Attach/detach must be
     * handled on the same coroutine as the messages so a session's teardown can
     * never land after its successor's handshake — that ordering is what keeps
     * [onDisconnected] from clearing a subscription the new client just made.
     */
    private suspend fun handleServerEvent(event: ServerEvent) {
        when (event) {
            is ClientAttached -> {
                // A half-open socket lets Home Assistant give up and open a
                // replacement while the old one still looks alive here, so a swap
                // never passes through "no client". Run the teardown the outgoing
                // session never got; otherwise its pipeline state, mic streaming
                // and BLE proxy registration outlive it.
                if (currentSessionId != 0L) {
                    Log.w(
                        TAG,
                        "Session $currentSessionId was replaced by ${event.sessionId} " +
                            "without closing; running its missed teardown",
                    )
                    onDisconnected()
                }
                currentSessionId = event.sessionId
                lastInboundElapsedMs = SystemClock.elapsedRealtime()
                staleClientConfirmations = 0
                receivedHaApiFrame = false
                onConnected()
            }

            is ClientDetached -> {
                // A superseded session's close is noise: its replacement is live.
                if (currentSessionId != event.sessionId) return
                currentSessionId = 0L
                receivedHaApiFrame = false
                staleClientConfirmations = 0
                onDisconnected()
            }

            is ClientMessage -> {
                // Replies go to whichever socket is installed, so answering a
                // superseded session would talk to the wrong client.
                if (currentSessionId != event.sessionId) return
                lastInboundElapsedMs = SystemClock.elapsedRealtime()
                receivedHaApiFrame = true
                staleClientConfirmations = 0
                handleMessage(event.message)
            }
        }
    }

    /**
     * Whether the display is interactive for HA-link decisions. Screen-off / doze
     * delays Wi-Fi and HA pings; subclasses that can see PowerManager should
     * override so the stale watchdog does not treat a late ping as a dead peer.
     */
    protected open fun isDisplayInteractiveForHaLink(): Boolean = true

    /**
     * A long inbound silence is NOT proof the peer is gone: aioesphomeapi cancels
     * its own keepalive ping whenever any message arrives from us ("Any valid
     * message from the remote cancels the pending ping"), and a BLE proxy streams
     * advertisements constantly — so a healthy Home Assistant goes completely
     * silent for as long as we keep talking. Dropping on silence alone put the
     * session on an exact 150s unavailable/reconnect loop (120s idle + 30s tick).
     *
     * So silence past the idle floor first gets an active probe: send our own
     * PingRequest — aioesphomeapi answers it immediately, like real ESPHome
     * firmware relies on — and only tear the session down after
     * [STALE_CLIENT_UNANSWERED_PROBES] of them go unanswered, each with a full
     * check interval to reply in. A genuinely dead socket (Wi-Fi drop, router
     * reboot, HA restart) answers nothing and is still reaped; without that Ava
     * keeps reporting [Connected], holds the mic armed and never releases the BLE
     * proxy until some new client happens to connect.
     *
     * One unanswered probe is not enough either way. On combo chips whose Bluetooth
     * and Wi-Fi share a radio, a scan storm can swallow a single round trip while
     * the peer is perfectly alive, and hanging up on it costs the same visible
     * unavailable flash this whole path exists to prevent. The display only changes
     * the idle floor, because doze delays the first ping rather than the answer.
     */
    private fun startStaleClientWatchdog() = scope.launch {
        var wasInteractive = isDisplayInteractiveForHaLink()
        while (isActive) {
            delay(STALE_CLIENT_CHECK_MS)
            if (currentSessionId == 0L) {
                staleClientConfirmations = 0
                wasInteractive = isDisplayInteractiveForHaLink()
                continue
            }
            val idleMs = SystemClock.elapsedRealtime() - lastInboundElapsedMs
            val displayOn = isDisplayInteractiveForHaLink()
            // First check after the display wakes still uses the screen-off floor.
            // Doze can leave idle already past the 120s interactive timeout; hanging
            // up here is the HA "unavailable" flash on screen-on.
            val justWoke = displayOn && !wasInteractive
            wasInteractive = displayOn
            val useOffRules = !displayOn || justWoke
            val timeoutMs = when {
                !receivedHaApiFrame -> STALE_CLIENT_TIMEOUT_HANDSHAKE_MS
                useOffRules -> STALE_CLIENT_TIMEOUT_SCREEN_OFF_MS
                else -> STALE_CLIENT_TIMEOUT_MS
            }
            if (idleMs < timeoutMs) {
                staleClientConfirmations = 0
                continue
            }
            staleClientConfirmations++
            // +1: the last window before the drop is the final probe's answer window.
            val needed = if (receivedHaApiFrame) 1 + STALE_CLIENT_UNANSWERED_PROBES else 2
            if (staleClientConfirmations < needed) {
                Log.i(
                    TAG,
                    "No HA traffic for ${idleMs}ms on session $currentSessionId; " +
                        "ping probe $staleClientConfirmations/$needed before drop" +
                        if (!receivedHaApiFrame) " (no HelloRequest yet)"
                        else if (useOffRules) " (display off / wake grace)" else "",
                )
                // Any reply (PingResponse or anything else) resets lastInboundElapsedMs
                // via the normal inbound path. Write errors are swallowed by
                // ClientConnection; a dead socket simply never answers.
                sendMessage(PingRequest.getDefaultInstance())
                continue
            }
            val droppingId = currentSessionId
            staleClientConfirmations = 0
            Log.w(
                TAG,
                "No HA traffic for ${idleMs}ms and no ping response on session " +
                    "$droppingId; dropping stale client",
            )
            server.disconnectCurrentClient()
            // close() can cancel the read loop without a ClientDetached
            // (CancellationException was previously swallowed). Don't leave
            // this session id live or the watchdog spins forever.
            if (currentSessionId == droppingId) {
                currentSessionId = 0L
                receivedHaApiFrame = false
                onDisconnected()
            }
        }
    }

    fun listenForEntityStateChanges() = combine(
        isSubscribedToEntityState,
        entityStateSubscriptionGeneration,
    ) { subscribed, _ -> subscribed }
        .flatMapLatest { subscribed ->
            if (!subscribed)
                emptyFlow()
            else
                entities.toList()
                    .map { it.subscribe() }
                    .merge()
                    .onEach { sendMessage(it) }
        }.launchIn(scope)

    protected open suspend fun handleMessage(message: MessageLite) {
        when (message) {
            is HelloRequest -> {
                clientApiVersionMajor = message.apiVersionMajor
                clientApiVersionMinor = message.apiVersionMinor
                Log.i(
                    TAG,
                    "HA HelloRequest: clientInfo=${message.clientInfo}, " +
                        "api=${message.apiVersionMajor}.${message.apiVersionMinor}",
                )
                sendMessage(helloResponse {
                    name = normalizeEspNodeName(this@EspHomeDevice.name)
                    apiVersionMajor = 1
                    apiVersionMinor = 12
                })
            }

            is ConnectRequest -> sendMessage(connectResponse { })

            is DisconnectRequest -> {
                sendMessage(disconnectResponse { })
                server.disconnectCurrentClient()
            }

            is DeviceInfoRequest -> sendMessage(getDeviceInfo())

            is PingRequest -> sendMessage(pingResponse { })

            // Answer to this side's stale-probe ping; arrival already refreshed
            // lastInboundElapsedMs, which is all the probe needed.
            is PingResponse -> Unit

            // HA→device direction (Home Assistant pushing its own entity states).
            // Not a device→HA dump; subclasses that care handle it themselves.
            is SubscribeHomeAssistantStatesRequest -> Unit

            is SubscribeStatesRequest -> {
                // Every SubscribeStates owes HA a full state dump, including a repeat
                // one on a session that was already subscribed — HA marks entities
                // unavailable while disconnected and only clears that on a state.
                isSubscribedToEntityState.value = true
                bumpEntityStateSubscription()
                Log.i(TAG, "SubscribeStates: dumping ${entities.size} entity states")
            }

            is ListEntitiesRequest -> {
                entities.map { it.handleMessage(message) }.asFlow().flattenConcat()
                    .collect { response -> sendMessage(response) }
                sendMessage(listEntitiesDoneResponse { })
            }

            else -> {
                entities.map { it.handleMessage(message) }.asFlow().flattenConcat()
                    .collect { response -> sendMessage(response) }
            }
        }
    }

    protected suspend fun sendMessage(message: MessageLite) {
        server.sendMessage(message)
    }

    protected open suspend fun onConnected() {
        _state.value = Connected
    }

    protected open suspend fun onDisconnected() {
        isSubscribedToEntityState.value = false
        clientApiVersionMajor = 0
        clientApiVersionMinor = 0
        _state.value = Disconnected
    }

    override fun close() {
        scope.cancel()
        server.close()
    }

    companion object {
        const val TAG: String = "EspHomeDevice"

        private const val STALE_CLIENT_CHECK_MS = 30_000L

        /**
         * Generous next to HA's ping cadence: a wrong guess here costs a reconnect,
         * and reconnects are only cheap because the swap is now handled properly.
         */
        private const val STALE_CLIENT_TIMEOUT_MS = 120_000L
        /** Handshake finished but HA never sent HelloRequest — half-open, drop fast. */
        private const val STALE_CLIENT_TIMEOUT_HANDSHAKE_MS = 30_000L
        /**
         * Screen-off / doze can stretch HA's ping past the interactive timeout without
         * the peer actually being gone. Still bounded — a real HA restart reconnects
         * on its own; this only stops us from being the one who hangs up.
         */
        private const val STALE_CLIENT_TIMEOUT_SCREEN_OFF_MS = 180_000L

        /**
         * Consecutive PingRequests that must go unanswered, each with its own check
         * interval, before the session is considered dead. Two rather than one so a
         * radio hiccup that eats a single round trip cannot hang up on a live peer.
         */
        private const val STALE_CLIENT_UNANSWERED_PROBES = 2
    }
}
