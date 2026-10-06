package com.example.ava.voice

import android.content.Context
import android.util.Log
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.playerSettingsStore
import com.example.ava.utils.DeviceCapabilities
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
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Low-bandwidth JPEG video for live LAN voice calls (~480p / 8fps).
 * Audio stays on the existing Opus path; this is a separate UDP track.
 */
object AvaVoiceVideoBridge {
    private const val TAG = "AvaVoiceVideoBridge"
    /** @deprecated Use [VoiceCallVideoQuality.SMOOTH.params]. */
    const val RESOLUTION = 480
    /** @deprecated Use [VoiceCallVideoQuality.SMOOTH.params]. */
    const val FPS = 8
    /** @deprecated Use [VoiceCallVideoQuality.SMOOTH.params]. */
    const val JPEG_QUALITY = 75
    /** @deprecated Use [VoiceCallVideoQuality.SMOOTH.params]. */
    const val CAPTURE_CAP_SHORT_EDGE = 320
    private const val FRAME_STALE_MS = 4_000L
    /** Backoff before rebuilding the video receive socket (port may still be held). */
    private const val LISTEN_RETRY_MS = 3_000L
    /**
     * A frame that lost any chunk never completes, so its buffered chunks would otherwise sit in
     * [partialFrames] for the whole call. Frames are sent in one burst, so anything older than
     * this is unrecoverable.
     */
    private const val PARTIAL_FRAME_TTL_MS = 2_000L
    @Volatile private var remoteUiMinIntervalMs =
        VoiceCallVideoQuality.SMOOTH.params().remoteUiMinIntervalMs

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var listenJob: Job? = null
    private var staleWatchJob: Job? = null
    /**
     * Live video receive socket. Held outside the loop so [stop] can close it — a blocking
     * `receive()` ignores coroutine cancellation and would keep [AvaVoiceProtocol.VIDEO_PORT]
     * bound, making the next [start] fail to bind and killing remote video for good.
     */
    private val videoSocket = AtomicReference<DatagramSocket?>(null)

    private val sending = AtomicBoolean(false)
    private val frameSequence = AtomicInteger(0)

    @Volatile private var activeSessionId = 0
    @Volatile private var localDeviceId = ""
    @Volatile private var useFrontCamera = true
    private var activePeers = emptyList<AvaVoiceDevice>()

    private val _remoteJpeg = MutableStateFlow<ByteArray?>(null)
    val remoteJpeg: StateFlow<ByteArray?> = _remoteJpeg.asStateFlow()

    /** Throttled local JPEG for on-device self preview (same capture path as uplink). */
    private val _localJpeg = MutableStateFlow<ByteArray?>(null)
    val localJpeg: StateFlow<ByteArray?> = _localJpeg.asStateFlow()

    private val _localVideoActive = MutableStateFlow(false)
    val localVideoActive: StateFlow<Boolean> = _localVideoActive.asStateFlow()

    /** True while local capture uses the front camera — UI mirrors self preview only. */
    private val _localFrontCamera = MutableStateFlow(true)
    val localFrontCamera: StateFlow<Boolean> = _localFrontCamera.asStateFlow()

    private val _peerVideoActive = MutableStateFlow(false)
    val peerVideoActive: StateFlow<Boolean> = _peerVideoActive.asStateFlow()

    private val partialFrames = ConcurrentHashMap<Long, PartialFrame>()
    @Volatile private var lastRemoteFrameMs = 0L
    @Volatile private var lastRemoteUiUpdateMs = 0L
    @Volatile private var lastLocalUiUpdateMs = 0L
    @Volatile private var peerVideoSessionId = 0

    private data class PartialFrame(
        val chunkCount: Int,
        val chunks: Array<ByteArray?>,
        var received: Int = 0,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        listenJob = scope.launch { videoListenLoop() }
        staleWatchJob = scope.launch { staleFrameWatchLoop() }
        Log.d(TAG, "video listener started on port ${AvaVoiceProtocol.VIDEO_PORT}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // Close before cancel: unblocks receive() so the port is free for the next start().
        videoSocket.getAndSet(null)?.let { runCatching { it.close() } }
        listenJob?.cancel()
        listenJob = null
        staleWatchJob?.cancel()
        staleWatchJob = null
        resetAll()
        Log.d(TAG, "stopped")
    }

    internal fun handlePeerVideoStart(signal: AvaVoiceVideoSignal) {
        if (!_peerVideoActive.value || peerVideoSessionId != signal.sessionId) {
            peerVideoSessionId = signal.sessionId
            _peerVideoActive.value = true
            Log.d(TAG, "peer video start session=${signal.sessionId} from=${signal.fromDeviceId}")
        }
    }

    internal fun handlePeerVideoStop(signal: AvaVoiceVideoSignal) {
        if (peerVideoSessionId == signal.sessionId || peerVideoSessionId == 0) {
            _peerVideoActive.value = false
            _remoteJpeg.value = null
            partialFrames.clear()
            peerVideoSessionId = 0
            Log.d(TAG, "peer video stop session=${signal.sessionId} from=${signal.fromDeviceId}")
        }
    }

    suspend fun setLocalVideoEnabled(
        context: Context,
        enabled: Boolean,
        sessionId: Int,
        deviceId: String,
        peers: List<AvaVoiceDevice>
    ) {
        if (enabled) {
            enableLocalVideo(context, sessionId, deviceId, peers)
        } else {
            disableLocalVideo(sessionId, deviceId, peers)
        }
    }

    fun flipLocalCamera(context: Context) {
        if (!_localVideoActive.value) return
        val appContext = context.applicationContext
        val nextFront = !useFrontCamera
        val canFlip = if (nextFront) {
            DeviceCapabilities.hasFrontCamera(appContext)
        } else {
            DeviceCapabilities.hasBackCamera(appContext)
        }
        if (!canFlip) return
        useFrontCamera = nextFront
        _localFrontCamera.value = nextFront
        scope.launch {
            VoiceSatelliteService.restartVoiceCallVideo(useFrontCamera) { jpeg ->
                onLocalFrame(jpeg)
            }
        }
    }

    internal fun setRemoteUiMinInterval(intervalMs: Long) {
        remoteUiMinIntervalMs = intervalMs.coerceAtLeast(50L)
    }

    /** Sync remote preview throttle from saved preset; capture picks up on next video start. */
    suspend fun applySavedQuality(context: Context) {
        val stored = com.example.ava.settings.PlayerSettingsStore(
            context.applicationContext.playerSettingsStore
        ).get().voiceCallVideoQuality
        val quality = VoiceCallVideoQuality.fromStored(stored)
        setRemoteUiMinInterval(quality.params().remoteUiMinIntervalMs)
    }

    fun resetAll() {
        scope.launch {
            val sessionId = activeSessionId
            val deviceId = localDeviceId
            val peers = activePeers
            if (_localVideoActive.value && sessionId != 0 && deviceId.isNotBlank()) {
                disableLocalVideoInternal(sessionId, deviceId, peers, notifyPeers = true)
            }
            _peerVideoActive.value = false
            _remoteJpeg.value = null
            _localJpeg.value = null
            lastLocalUiUpdateMs = 0L
            _localFrontCamera.value = true
            useFrontCamera = true
            partialFrames.clear()
            peerVideoSessionId = 0
            activeSessionId = 0
            localDeviceId = ""
            activePeers = emptyList()
        }
    }

    private suspend fun enableLocalVideo(
        context: Context,
        sessionId: Int,
        deviceId: String,
        peers: List<AvaVoiceDevice>
    ) = withContext(Dispatchers.IO) {
        if (_localVideoActive.value) return@withContext
        val appContext = context.applicationContext
        val reachablePeers = peers.filter { it.host.isNotBlank() }
        if (reachablePeers.isEmpty()) return@withContext

        useFrontCamera = when {
            DeviceCapabilities.hasFrontCamera(appContext) -> true
            DeviceCapabilities.hasBackCamera(appContext) -> false
            else -> {
                Log.w(TAG, "no camera available for voice video")
                return@withContext
            }
        }
        _localFrontCamera.value = useFrontCamera

        activeSessionId = sessionId
        localDeviceId = deviceId
        activePeers = reachablePeers
        frameSequence.set(0)

        reachablePeers.forEach { peer ->
            AvaVoiceTransport.sendControl(
                peer.host,
                buildVoiceVideoStart(sessionId, deviceId, peer.id)
            )
        }

        sending.set(true)
        val started = VoiceSatelliteService.startVoiceCallVideo(useFrontCamera) { jpeg ->
            onLocalFrame(jpeg)
        }
        if (!started) {
            sending.set(false)
            activeSessionId = 0
            localDeviceId = ""
            activePeers = emptyList()
            Log.w(TAG, "voice call video failed to start")
            return@withContext
        }
        _localVideoActive.value = true
        Log.d(TAG, "local video enabled session=$sessionId front=$useFrontCamera peers=${reachablePeers.size}")
    }

    private suspend fun disableLocalVideo(
        sessionId: Int,
        deviceId: String,
        peers: List<AvaVoiceDevice>
    ) = withContext(Dispatchers.IO) {
        disableLocalVideoInternal(sessionId, deviceId, peers, notifyPeers = true)
    }

    private suspend fun disableLocalVideoInternal(
        sessionId: Int,
        deviceId: String,
        peers: List<AvaVoiceDevice>,
        notifyPeers: Boolean
    ) {
        if (!_localVideoActive.value && !sending.get()) return
        sending.set(false)
        VoiceSatelliteService.stopVoiceCallVideo()
        _localVideoActive.value = false
        _localJpeg.value = null
        lastLocalUiUpdateMs = 0L
        _localFrontCamera.value = true
        useFrontCamera = true
        if (notifyPeers && sessionId != 0 && deviceId.isNotBlank()) {
            peers.filter { it.host.isNotBlank() }.forEach { peer ->
                AvaVoiceTransport.sendControl(
                    peer.host,
                    buildVoiceVideoStop(sessionId, deviceId, peer.id)
                )
            }
        }
        activeSessionId = 0
        localDeviceId = ""
        activePeers = emptyList()
        Log.d(TAG, "local video disabled session=$sessionId")
    }

    private fun onLocalFrame(jpeg: ByteArray) {
        if (!sending.get() || jpeg.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastLocalUiUpdateMs >= remoteUiMinIntervalMs) {
            lastLocalUiUpdateMs = now
            _localJpeg.value = jpeg
        }

        val sessionId = activeSessionId
        if (sessionId == 0) return
        val peers = activePeers
        if (peers.isEmpty()) return

        val seq = frameSequence.getAndIncrement() and 0xFFFF
        val chunkCount = (jpeg.size + AvaVoiceVideoPacket.MAX_CHUNK_PAYLOAD - 1) /
            AvaVoiceVideoPacket.MAX_CHUNK_PAYLOAD
        var offset = 0
        var chunkIndex = 0
        while (offset < jpeg.size) {
            val chunkLen = minOf(AvaVoiceVideoPacket.MAX_CHUNK_PAYLOAD, jpeg.size - offset)
            val packet = AvaVoiceVideoPacket.encode(
                sessionId = sessionId,
                frameSequence = seq,
                chunkIndex = chunkIndex,
                chunkCount = chunkCount,
                payload = jpeg,
                offset = offset,
                length = chunkLen
            )
            peers.forEach { peer ->
                AvaVoiceTransport.sendVideoSync(peer.host, packet)
            }
            offset += chunkLen
            chunkIndex++
        }
    }

    private fun onRemoteChunk(decoded: AvaVoiceVideoPacket.Decoded) {
        if (!AvaVoiceSessionHub.isActiveCallSession(decoded.sessionId)) return
        if (!_peerVideoActive.value && peerVideoSessionId != decoded.sessionId) {
            peerVideoSessionId = decoded.sessionId
            _peerVideoActive.value = true
        }
        val key = (decoded.sessionId.toLong() shl 32) or decoded.frameSequence.toLong()
        val frame = partialFrames.compute(key) { _, existing ->
            if (existing != null && existing.chunkCount == decoded.chunkCount) {
                existing
            } else {
                PartialFrame(decoded.chunkCount, arrayOfNulls(decoded.chunkCount))
            }
        } ?: return
        if (frame.chunks[decoded.chunkIndex] == null) {
            frame.chunks[decoded.chunkIndex] = decoded.payload
            frame.received++
        }
        if (frame.received >= frame.chunkCount) {
            val assembled = ByteArray(frame.chunks.sumOf { it?.size ?: 0 })
            var pos = 0
            for (chunk in frame.chunks) {
                if (chunk == null) {
                    partialFrames.remove(key)
                    return
                }
                System.arraycopy(chunk, 0, assembled, pos, chunk.size)
                pos += chunk.size
            }
            partialFrames.remove(key)
            if (assembled.isNotEmpty()) {
                lastRemoteFrameMs = System.currentTimeMillis()
                val now = lastRemoteFrameMs
                if (now - lastRemoteUiUpdateMs >= remoteUiMinIntervalMs) {
                    lastRemoteUiUpdateMs = now
                    _remoteJpeg.value = assembled
                }
            }
        }
    }

    private suspend fun staleFrameWatchLoop() {
        while (scope.isActive && running.get()) {
            delay(1_000L)
            evictStalePartialFrames()
            if (!_peerVideoActive.value) continue
            val last = lastRemoteFrameMs
            if (last == 0L) continue
            if (System.currentTimeMillis() - last > FRAME_STALE_MS) {
                _remoteJpeg.value = null
            }
        }
    }

    /** Drop chunk buffers of frames that can no longer be completed (any chunk lost in transit). */
    private fun evictStalePartialFrames() {
        if (partialFrames.isEmpty()) return
        val cutoff = System.currentTimeMillis() - PARTIAL_FRAME_TTL_MS
        partialFrames.entries.removeAll { it.value.createdAtMs < cutoff }
    }

    /**
     * Supervises [videoReceiveSession]. A dead video socket leaves the call running with a
     * permanently black remote preview, so rebuild until the bridge stops.
     */
    private suspend fun videoListenLoop() {
        while (running.get() && scope.isActive) {
            val clean = videoReceiveSession()
            if (!running.get() || !scope.isActive) return
            if (!clean) delay(LISTEN_RETRY_MS)
        }
    }

    /** @return true when the loop exited because the bridge stopped, false on error. */
    private suspend fun videoReceiveSession(): Boolean {
        var socket: DatagramSocket? = null
        try {
            // SO_REUSEADDR only takes effect on an unbound socket, so bind explicitly — otherwise
            // a socket lingering from a previous bridge instance blocks the video port for good.
            socket = DatagramSocket(null as SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(AvaVoiceProtocol.VIDEO_PORT))
            }
            videoSocket.getAndSet(socket)?.let { old ->
                if (old !== socket) runCatching { old.close() }
            }
            val buffer = ByteArray(AvaVoiceVideoPacket.HEADER_SIZE + AvaVoiceVideoPacket.MAX_CHUNK_PAYLOAD + 64)
            while (running.get() && scope.isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val decoded = AvaVoiceVideoPacket.decode(packet.data, packet.length) ?: continue
                onRemoteChunk(decoded)
            }
            return true
        } catch (e: Exception) {
            if (running.get()) {
                Log.w(TAG, "video listen error: ${e.javaClass.simpleName}: ${e.message} — retrying")
            }
            return false
        } finally {
            videoSocket.compareAndSet(socket, null)
            runCatching { socket?.close() }
        }
    }
}
