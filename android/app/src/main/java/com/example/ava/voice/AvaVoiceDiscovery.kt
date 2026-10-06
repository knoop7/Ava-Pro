package com.example.ava.voice

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * LAN Ava presence on [AvaVoiceProtocol.PORT] (UDP 19848).
 *
 * Holders (refcount-style) keep the same socket/beacon running. [HOLDER_PRESENCE] from
 * Voice Satellite keeps identity + optional Ava↔Ava sync-offset peer control alive even when
 * voice messaging and fleet cluster are off — without inventing a second UDP protocol.
 */
object AvaVoiceDiscovery {
    private const val TAG = "AvaVoiceDiscovery"

    /** Backoff before rebuilding the receive socket (port may still be in TIME_WAIT-ish state). */
    private const val LISTEN_RETRY_MS = 3_000L

    const val HOLDER_VOICE = "voice"
    const val HOLDER_FLEET = "fleet"
    /** Voice Satellite process presence — Ava identity on LAN even when cluster is off. */
    const val HOLDER_PRESENCE = "presence"
    /** Settings clone send/receive screens — presence while a clone screen is open. */
    const val HOLDER_CLONE = "clone"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenJob: Job? = null
    private var beaconJob: Job? = null
    private val running = AtomicBoolean(false)
    private val listenerHealthy = AtomicBoolean(false)
    @Volatile private var startedAtElapsedMs = 0L
    /**
     * Live receive socket. Held outside [listenLoop] so [stopInternal] can close it —
     * a blocking `receive()` ignores coroutine cancellation and would keep the port
     * bound, making the next [acquire] fail to bind.
     */
    private val listenSocket = AtomicReference<DatagramSocket?>(null)
    private val holders = ConcurrentHashMap.newKeySet<String>()

    private val _devices = MutableStateFlow<List<AvaVoiceDevice>>(emptyList())
    val devices: StateFlow<List<AvaVoiceDevice>> = _devices.asStateFlow()

    private var localDeviceId: String = ""
    private var localDeviceName: String = ""
    private var localDeviceType: AvaVoiceDeviceType = AvaVoiceDeviceType.UNKNOWN
    private var localDeviceModel: String = ""
    private var localHostIp: String = ""
    private var multicastLock: WifiManager.MulticastLock? = null
    private val advertisedClusterPort = AtomicInteger(0)
    private val advertisedWebConsole = AtomicBoolean(false)
    private val advertisedOccupied = AtomicReference<Boolean?>(null)
    private val advertisedClonePort = AtomicInteger(0)
    /** ElapsedRealtime deadline for the advertised clone window; 0 = none. */
    private val advertisedCloneDeadlineElapsed = AtomicLong(0L)

    /**
     * Acquire UDP presence for [holder]. Safe to call repeatedly.
     * Discovery stays up until every holder [release]s.
     */
    fun acquire(context: Context, holder: String, displayName: String = "") {
        val appContext = context.applicationContext
        AvaSyncOffsetPeer.bindAppContext(appContext)
        val resolvedName = resolveConfiguredDeviceName(appContext, displayName)
        val holderWasAbsent = holders.add(holder)
        if (running.get()) {
            if (resolvedName.isNotBlank()) localDeviceName = resolvedName
            localHostIp = resolveLocalHostIp()
            acquireMulticastLock(appContext)
            // Voice service (re)start must never inherit a wedged receive socket from a
            // previous run kept alive by another holder (issue #202: inbound signaling
            // silently dropped until device reboot). Closing it makes listenLoop re-bind.
            if (holder == HOLDER_VOICE && (holderWasAbsent || !listenerHealthy.get())) {
                rebuildListenSocket()
            }
            scope.launch { sendBeacon(isReply = true) }
            return
        }
        if (!running.compareAndSet(false, true)) {
            // Lost race — another thread started; still refresh name.
            if (resolvedName.isNotBlank()) localDeviceName = resolvedName
            return
        }
        localDeviceId = resolveLocalDeviceId(appContext)
        localDeviceName = resolvedName
        localDeviceType = resolveLocalDeviceType(appContext)
        localDeviceModel = resolveLocalDeviceModel()
        localHostIp = resolveLocalHostIp()
        acquireMulticastLock(appContext)
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        listenJob = scope.launch { listenLoop() }
        beaconJob = scope.launch { beaconLoop() }
        Log.d(TAG, "started holders=$holders id=$localDeviceId name=$localDeviceName ip=$localHostIp")
    }

    /** Release [holder]; stop socket only when no holders remain. */
    fun release(holder: String) {
        holders.remove(holder)
        if (holders.isNotEmpty()) {
            Log.d(TAG, "release $holder — still held by $holders")
            return
        }
        stopInternal()
    }

    /** @deprecated Prefer [acquire]/[release] with an explicit holder. */
    fun start(context: Context, displayName: String = "") {
        acquire(context, HOLDER_VOICE, displayName)
    }

    /** @deprecated Prefer [release]. Stops only when no holders remain if using acquire/release. */
    fun stop() {
        release(HOLDER_VOICE)
    }

    /** Cluster agent HTTP port to append on beacons (`0` = Ava identity only, agent off). */
    fun setAdvertisedClusterPort(port: Int) {
        advertisedClusterPort.set(port.coerceIn(0, 65535))
    }

    fun advertisedClusterPort(): Int = advertisedClusterPort.get()

    /** Whether this device is serving the website SPA (`webConsole=0|1` on beacons). */
    fun setAdvertisedWebConsole(enabled: Boolean) {
        advertisedWebConsole.set(enabled)
    }

    fun advertisedWebConsole(): Boolean = advertisedWebConsole.get()

    /**
     * Bayesian occupancy verdict to append on beacons (`occupied=0|1`);
     * `null` = sensor off, field omitted. Rides the identity beacon so peer
     * rooms can shape their occupancy prior — no second UDP protocol.
     */
    fun setAdvertisedOccupancy(occupied: Boolean?) {
        advertisedOccupied.set(occupied)
    }

    /**
     * One-shot settings-clone TCP port to append on beacons (`clonePort=<port>`);
     * `0` = clone window closed, field omitted. Set only while the clone send
     * screen is open — same beacon-hitchhike rule as [setAdvertisedClusterPort].
     */
    fun setAdvertisedClonePort(port: Int) {
        val bounded = port.coerceIn(0, 65535)
        advertisedClonePort.set(bounded)
        if (bounded == 0) advertisedCloneDeadlineElapsed.set(0L)
    }

    /**
     * Sender-local [SystemClock.elapsedRealtime] instant when the clone window
     * closes. Each beacon then advertises `cloneRemain` so receivers share the clock.
     */
    fun setAdvertisedCloneDeadlineElapsed(deadlineElapsedRealtime: Long) {
        advertisedCloneDeadlineElapsed.set(deadlineElapsedRealtime.coerceAtLeast(0L))
    }

    private fun advertisedCloneRemainSec(): Int {
        val deadline = advertisedCloneDeadlineElapsed.get()
        if (deadline <= 0L || advertisedClonePort.get() <= 0) return 0
        val remainMs = deadline - SystemClock.elapsedRealtime()
        return ((remainMs + 999L) / 1000L).toInt().coerceAtLeast(0)
    }

    fun isRunning(): Boolean = running.get()

    /** True only after two presence intervals completed without seeing another Ava device. */
    fun canAssumeSingleDevice(): Boolean {
        if (!running.get() || !listenerHealthy.get() || startedAtElapsedMs <= 0L ||
            _devices.value.isNotEmpty()
        ) return false
        return SystemClock.elapsedRealtime() - startedAtElapsedMs >=
            AvaVoiceProtocol.BEACON_INTERVAL_MS * 2 + SINGLE_DEVICE_SETTLE_MS
    }

    fun localId(): String = localDeviceId

    fun localName(): String = localDeviceName.ifBlank { "Ava Device" }

    fun localHost(): String = localHostIp

    fun localType(): AvaVoiceDeviceType = localDeviceType

    fun localModel(): String = localDeviceModel

    /** Same stable id scheme as voice/BLE claim: unsigned hash of ANDROID_ID. */
    fun resolveLocalDeviceId(context: Context): String {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID,
            ) ?: "default"
            stableAvaDeviceId(androidId)
        } catch (_: Exception) {
            "ava_default"
        }
    }

    /**
     * Stable fleet/voice device id from a seed (usually ANDROID_ID).
     * Uses unsigned 32-bit hash so ids never look like `ava_-123…`.
     */
    fun stableAvaDeviceId(seed: String): String {
        val raw = seed.ifBlank { "default" }
        val unsigned = raw.hashCode().toLong() and 0xFFFFFFFFL
        return "ava_$unsigned"
    }

    /** Set when a socket rebuild is intentional so [listenLoop] skips the error backoff. */
    private val listenRebuildRequested = AtomicBoolean(false)

    /**
     * Force-close the receive socket so [listenLoop] exits with an error and re-binds
     * on a fresh socket. Recovers from wedged states that survive service restarts
     * because another holder (e.g. [HOLDER_PRESENCE]) kept [running] true.
     */
    private fun rebuildListenSocket() {
        listenRebuildRequested.set(true)
        val closed = listenSocket.getAndSet(null)?.let { socket ->
            runCatching { socket.close() }.isSuccess
        } ?: false
        Log.d(TAG, "listen socket force-closed for rebuild (closedExisting=$closed)")
    }

    private fun stopInternal() {
        if (!running.compareAndSet(true, false)) return
        // Close before cancel: unblocks receive() so the port is free for the next acquire.
        listenSocket.getAndSet(null)?.let { runCatching { it.close() } }
        listenerHealthy.set(false)
        listenRebuildRequested.set(false)
        listenJob?.cancel()
        beaconJob?.cancel()
        listenJob = null
        beaconJob = null
        _devices.value = emptyList()
        startedAtElapsedMs = 0L
        releaseMulticastLock()
        Log.d(TAG, "stopped")
    }

    private suspend fun beaconLoop() {
        while (running.get() && scope.isActive) {
            sendBeacon(isReply = false)
            delay(AvaVoiceProtocol.BEACON_INTERVAL_MS)
        }
    }

    private const val SINGLE_DEVICE_SETTLE_MS = 1_000L

    /**
     * Supervises [receiveSession]. A dead receive loop is invisible from the outside —
     * beacons keep going out on ephemeral sockets, so the device stays listed while
     * answering nothing (sync-offset / music-EQ peers silently stop working). Rebuild
     * until every holder released.
     */
    private suspend fun listenLoop() {
        while (running.get() && scope.isActive) {
            val clean = receiveSession()
            if (!running.get() || !scope.isActive) return
            // An intentional rebuild (service restart) re-binds immediately; only genuine
            // errors back off — otherwise every restart would go deaf for LISTEN_RETRY_MS.
            if (!clean && !listenRebuildRequested.compareAndSet(true, false)) {
                delay(LISTEN_RETRY_MS)
            }
        }
    }

    /** @return true when the loop exited because discovery stopped, false on error. */
    private suspend fun receiveSession(): Boolean {
        var socket: DatagramSocket? = null
        try {
            // SO_REUSEADDR only takes effect on an unbound socket, so bind explicitly —
            // otherwise a socket lingering from a previous service instance blocks us.
            socket = DatagramSocket(null as SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(AvaVoiceProtocol.PORT))
                broadcast = true
            }
            listenerHealthy.set(true)
            listenSocket.getAndSet(socket)?.let { old ->
                if (old !== socket) runCatching { old.close() }
            }
            val buffer = ByteArray(512)
            while (running.get() && scope.isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val sourceHost = packet.address?.hostAddress ?: continue
                when {
                    message.startsWith("${AvaVoiceProtocol.QUERY_PREFIX}|") -> {
                        val requesterId = message.substringAfter('|').trim()
                        if (requesterId != localDeviceId) {
                            sendBeacon(isReply = true)
                        }
                    }
                    message.startsWith("${AvaVoiceProtocol.BEACON_PREFIX}|") -> {
                        val device = parseBeacon(message, sourceHost) ?: continue
                        if (device.id == localDeviceId) continue
                        upsertDevice(device)
                    }
                    // Ava↔Ava sync-offset / music-EQ — presence UDP only; never gated by
                    // voice-message flags.
                    AvaSyncOffsetPeer.isGet(message) ||
                        AvaSyncOffsetPeer.isSet(message) ||
                        AvaSyncOffsetPeer.isVal(message) ||
                        AvaSyncOffsetPeer.isEqMessage(message) ||
                        AvaSyncOffsetPeer.isLyricsMessage(message) ||
                        AvaSyncOffsetPeer.isPlaybackBeacon(message) ||
                        AvaSyncOffsetPeer.isMediaMeta(message) ||
                        AvaSyncOffsetPeer.isMediaProgress(message) ||
                        AvaSyncOffsetPeer.isVinylExpand(message) -> {
                        try {
                            AvaSyncOffsetPeer.handleInbound(message, sourceHost, localDeviceId)
                        } catch (e: Exception) {
                            Log.w(TAG, "sync-offset peer failed: ${e.message}")
                        }
                    }
                    message.startsWith("${AvaVoiceProtocol.BEGIN_PREFIX}|") ||
                        message.startsWith("${AvaVoiceProtocol.END_PREFIX}|") ||
                        message.startsWith("${AvaVoiceProtocol.HANGUP_PREFIX}|") ||
                        message.startsWith("${AvaVoiceProtocol.ANSWER_PREFIX}|") ||
                        message.startsWith("${AvaVoiceProtocol.DECLINE_PREFIX}|") ||
                        message.startsWith("${AvaVoiceProtocol.NO_ANSWER_PREFIX}|") ||
                        message.startsWith("${AvaVoiceProtocol.MESSAGE_PREFIX}|") -> {
                        if (!AvaVoiceNetwork.isFeatureEnabled()) continue
                        // Session setup may throw if AudioFlinger is exhausted (e.g. duplicate call
                        // streams); must not kill this UDP loop or discovery/teardown stops working.
                        runCatching {
                            AvaVoiceSessionHub.handleControlMessage(message, localDeviceId, sourceHost)
                        }.onFailure { Log.w(TAG, "control message failed: ${it.message}") }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            if (running.get()) {
                Log.w(TAG, "listen error: ${e.javaClass.simpleName}: ${e.message} — retrying")
            }
            return false
        } finally {
            if (listenSocket.compareAndSet(socket, null)) {
                listenerHealthy.set(false)
            }
            socket?.close()
        }
    }

    private fun upsertDevice(device: AvaVoiceDevice) {
        val now = System.currentTimeMillis()
        val updated = device.copy(lastSeenMs = now)
        val current = _devices.value.toMutableList()
        val index = current.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            current[index] = updated
        } else {
            current.add(updated)
        }
        _devices.value = current
            .filter { now - it.lastSeenMs <= AvaVoiceProtocol.DEVICE_STALE_MS }
            .sortedBy { it.name.lowercase() }
    }

    private fun sendBeacon(isReply: Boolean) {
        try {
            val payload = buildBeacon(
                deviceId = localDeviceId,
                deviceName = localDeviceName,
                deviceType = localDeviceType,
                hostIp = localHostIp,
                clusterPort = advertisedClusterPort.get(),
                webConsole = advertisedWebConsole.get(),
                syncOffsetPeer = true,
                // Presence can outlive the voice-message master; advertise the
                // live flag so peer pickers can hide unreachable voice targets.
                voiceMessaging = AvaVoiceNetwork.isFeatureEnabled(),
                occupied = advertisedOccupied.get(),
                clonePort = advertisedClonePort.get(),
                cloneRemainSec = advertisedCloneRemainSec(),
                model = localDeviceModel,
            )
            val bytes = payload.toByteArray(Charsets.UTF_8)
            DatagramSocket().use { socket ->
                socket.broadcast = true
                broadcastTargets().forEach { target ->
                    socket.send(DatagramPacket(bytes, bytes.size, target, AvaVoiceProtocol.PORT))
                }
            }
            if (!isReply) {
                broadcastDiscoveryQuery()
            }
        } catch (e: Exception) {
            Log.w(TAG, "beacon send failed: ${e.message}")
        }
    }

    /**
     * Push a beacon immediately (e.g. after voice-message master toggles) so
     * peers update [AvaVoiceDevice.voiceMessaging] without waiting for the 5s loop.
     */
    fun refreshBeaconNow() {
        if (!running.get()) return
        scope.launch { sendBeacon(isReply = true) }
    }

    private fun broadcastDiscoveryQuery() {
        try {
            val payload = "${AvaVoiceProtocol.QUERY_PREFIX}|$localDeviceId"
            val bytes = payload.toByteArray(Charsets.UTF_8)
            DatagramSocket().use { socket ->
                socket.broadcast = true
                broadcastTargets().forEach { target ->
                    socket.send(DatagramPacket(bytes, bytes.size, target, AvaVoiceProtocol.PORT))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "query send failed: ${e.message}")
        }
    }

    /** Nudge LAN peers to re-beacon (e.g. before Mass sync-delay peer match). */
    fun pokeLanDiscovery() {
        if (!running.get()) return
        scope.launch {
            broadcastDiscoveryQuery()
            sendBeacon(isReply = true)
        }
    }

    private fun resolveLocalDeviceName(context: Context): String {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                if (!name.isNullOrBlank()) return name
            }
        } catch (_: Exception) {
        }
        return Build.MODEL?.takeIf { it.isNotBlank() } ?: "Ava Device"
    }

    private fun resolveConfiguredDeviceName(context: Context, displayName: String): String =
        displayName.trim().takeIf { it.isNotBlank() } ?: resolveLocalDeviceName(context)

    private fun resolveLocalDeviceModel(): String =
        Build.MODEL?.trim().orEmpty().replace('|', ' ').replace('=', ' ')

    private fun resolveLocalDeviceType(context: Context): AvaVoiceDeviceType {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return AvaVoiceDeviceType.TV
        }
        val smallestWidth = context.resources.configuration.smallestScreenWidthDp
        if (smallestWidth >= 600) {
            return AvaVoiceDeviceType.TABLET
        }
        return AvaVoiceDeviceType.PHONE
    }

    private fun resolveLocalHostIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
                ?: "0.0.0.0"
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses.asSequence() }
                .mapNotNull { it.broadcast }
                .forEach { targets.add(it) }
        } catch (e: Exception) {
            Log.w(TAG, "broadcast target resolve failed: ${e.message}")
        }
        return targets.toList()
    }

    private fun acquireMulticastLock(context: Context) {
        if (multicastLock?.isHeld == true) return
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("$TAG::MulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "acquired multicast lock")
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock acquire failed: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock release failed: ${e.message}")
        } finally {
            multicastLock = null
        }
    }
}
