package com.example.ava.multidevice

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import com.example.ava.services.ChorusWakeBlurService
import com.example.ava.voice.AvaVoiceDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * LAN wake-word arbiter for chorus wake (one call, one nearby device answers).
 *
 * Uses a standing UDP listener on [PORT] — not 19847 (Bluetooth presence `AVA_CLAIM:`)
 * and not AvaVoice 19848/19849. Send uses a separate ephemeral socket so the listener
 * stays bound for the whole time the feature is on.
 *
 * Collect window is the same on every device. Winner is who heard the wake
 * louder (pre-gain RMS + confidence), not who finished voiceprint first or
 * whose wall clock is behind.
 */
object WakeWordArbiter {
    private const val TAG = "WakeWordArbiter"

    /** Dedicated chorus-wake port. Do not reuse BLE claim or AvaVoice ports. */
    const val PORT = 19851

    private const val PREFIX = "AVA_WAKE"
    private const val PREFIX_END = "AVA_WAKE_END"
    /** Winner-session keepalive; sustains the losers' short hold window. */
    private const val PREFIX_ALIVE = "AVA_WAKE_ALIVE"
    /**
     * Winner beat period. Must fit a few times into
     * [ChorusPeerSession.PEER_SESSION_SUSTAIN_MS] so the losers' hold survives
     * a couple of lost broadcasts before expiring mid-conversation.
     */
    const val ALIVE_INTERVAL_MS = 4_000L
    /**
     * Same wait on every device. The old 80ms "alone" shortcut let a discovery-blind
     * box declare `no_competitors` and always talk.
     */
    private const val COLLECT_MS = 180L
    /**
     * How far back a claim still counts as "this same wake". Must cover detect skew
     * between engines plus DTIM-buffered broadcast delivery, but stay well below the
     * fastest realistic re-wake, or a barge-in re-arbitration gets judged against the
     * previous round's scores (the winner can then yield to a loser that is muted
     * under its hold — and nobody answers). Was 2000ms; cross-round contamination.
     */
    private const val CLAIM_WINDOW_MS = 1_000L
    private const val CLAIM_RETAIN_MS = 1_500L
    /**
     * Wi-Fi broadcast is unacknowledged and DTIM-buffered; one lost claim is a
     * guaranteed double answer. Mirror the END packet's triple send.
     */
    private const val CLAIM_REPEATS = 3
    private const val CLAIM_REPEAT_GAP_MS = 45L
    /**
     * A clearly-louder claim arriving this soon after a local win still belongs to the
     * same utterance: that device detected late (slow engine or delayed broadcast) but
     * out-heard us. Revoke our seat instead of double-answering.
     */
    private const val SEAT_REVOKE_WINDOW_MS = 1_200L
    private const val PEER_SESSION_SUSTAIN_MS = ChorusPeerSession.PEER_SESSION_SUSTAIN_MS
    /** Treat scores this close as a tie so a slightly hotter mic cannot lock the seat. */
    private const val SCORE_TIE_EPS = 180
    /** Immediate yield only when a prior claim is clearly louder, not merely earlier. */
    private const val SCORE_CLEAR_LEAD = 400
    private const val PACKET_SIZE = 256
    private val SUFFIXES_TO_STRIP = listOf(
        "onnx",
        "tflite",
        "openwakeword",
        "vswakeword",
        "microwakeword",
    )

    private val startLock = Any()
    private val claimsLock = Any()
    private val recentClaims = ArrayDeque<WakeClaim>()

    @Volatile private var running = false
    @Volatile private var localDeviceId: String = ""
    @Volatile private var appContext: Context? = null
    @Volatile private var listenSocket: DatagramSocket? = null
    @Volatile private var listenThread: Thread? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile var onPeerSessionEnd: ((wakeKey: String) -> Unit)? = null

    /** Local winner dethroned by a clearly-louder late claim. Caller must yield the seat. */
    @Volatile var onSeatRevoked: ((wakeKey: String) -> Unit)? = null

    @Volatile private var lastWinScore = 0
    @Volatile private var lastWinKey = ""
    @Volatile private var lastWinAtElapsed = 0L

    private val peerSession = ChorusPeerSession(
        timeoutMs = PEER_SESSION_SUSTAIN_MS,
        nowMs = { SystemClock.elapsedRealtime() },
    )

    fun isPeerChorusSessionActive(): Boolean {
        if (peerSession.consumeExpired()) {
            Log.d(TAG, "peer chorus session timed out")
            notifyPeerSessionEnd("timeout")
        }
        return peerSession.isActive()
    }

    fun setLocalChorusWinner(won: Boolean) {
        peerSession.setLocalWinner(won)
        if (!won) {
            lastWinAtElapsed = 0L
        }
    }

    fun markPeerChorusSession(wakeKey: String = "") {
        if (peerSession.markPeer()) {
            Log.d(TAG, "peer chorus session hold key=$wakeKey")
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        val id = AvaVoiceDiscovery.resolveLocalDeviceId(app)
        synchronized(startLock) {
            localDeviceId = id
            if (running && listenSocket?.isClosed == false) return
            stopLocked()
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(PORT))
                    soTimeout = 1_000
                }
                listenSocket = socket
                running = true
                acquireMulticastLock(app)
                listenThread = Thread({ listenLoop(socket) }, "WakeWordArbiter").apply {
                    isDaemon = true
                    start()
                }
                Log.d(TAG, "listener started port=$PORT id=$id")
            } catch (e: Exception) {
                running = false
                listenSocket = null
                Log.e(TAG, "failed to start listener: ${e.message}")
            }
        }
    }

    fun stop() {
        synchronized(startLock) {
            stopLocked()
        }
    }

    suspend fun arbitrate(
        context: Context,
        wakeWordId: String,
        wakeWordPhrase: String = "",
        timestamp: Long = System.currentTimeMillis(),
        confidence: Float = 1f,
        detectRms: Float = 0f,
    ): ArbiterResult = withContext(Dispatchers.IO) {
        if (!running) start(context)
        val myId = localDeviceId.ifBlank {
            AvaVoiceDiscovery.resolveLocalDeviceId(context.applicationContext).also { localDeviceId = it }
        }
        val wakeKey = normalizeWakeKey(wakeWordId, wakeWordPhrase)
        val myScore = packScore(confidence, detectRms)
        val already = snapshotCompetitors(myId, timestamp, wakeKey)
        if (already.isNotEmpty()) {
            val priorWinner = pickWinner(myId, timestamp, myScore, already)
            val priorLead = already.maxOf { it.score } - myScore
            if (priorWinner != myId && priorLead > SCORE_CLEAR_LEAD) {
                Log.d(TAG, "late yield to $priorWinner key=$wakeKey lead=$priorLead score=$myScore")
                sendClaim(myId, timestamp, wakeKey, myScore)
                return@withContext ArbiterResult(false, "lost_to_$priorWinner", already.size)
            }
        }
        val sent = sendClaim(myId, timestamp, wakeKey, myScore)
        if (!sent && !running) {
            Log.e(TAG, "arbitration failed: send and listen both down")
            recordSeatWin(wakeKey, myScore)
            return@withContext ArbiterResult(true, "error_fallback", 0)
        }
        delay(COLLECT_MS)
        val competitors = snapshotCompetitors(myId, timestamp, wakeKey)
        Log.d(
            TAG,
            "collected ${competitors.size} competitors key=$wakeKey wait=${COLLECT_MS}ms " +
                "score=$myScore conf=${"%.2f".format(confidence)} rms=${"%.3f".format(detectRms)}",
        )
        if (competitors.isEmpty()) {
            recordSeatWin(wakeKey, myScore)
            return@withContext ArbiterResult(true, "no_competitors", 0)
        }
        val winner = pickWinner(myId, timestamp, myScore, competitors)
        val shouldRespond = winner == myId
        Log.d(TAG, "winner=$winner shouldRespond=$shouldRespond myScore=$myScore")
        if (shouldRespond) {
            recordSeatWin(wakeKey, myScore)
        }
        ArbiterResult(
            shouldRespond = shouldRespond,
            reason = if (shouldRespond) "won" else "lost_to_$winner",
            competitorCount = competitors.size,
        )
    }

    private fun recordSeatWin(wakeKey: String, score: Int) {
        lastWinScore = score
        lastWinKey = wakeKey
        lastWinAtElapsed = SystemClock.elapsedRealtime()
    }

    /**
     * Winner-side keepalive, sent every [ALIVE_INTERVAL_MS] while the session is
     * active (VoiceSatellite drives the loop). Losers re-arm their short hold on
     * each beat; when the beats stop — session over, app killed, Wi-Fi gone —
     * the hold expires within [ChorusPeerSession.PEER_SESSION_SUSTAIN_MS] even
     * if every END broadcast was lost. Single send: the cadence is the retry.
     */
    fun announceSessionAlive(context: Context? = null, wakeKey: String = "") {
        context?.applicationContext?.let { appContext = it }
        val myId = localDeviceId.ifBlank {
            appContext?.let { AvaVoiceDiscovery.resolveLocalDeviceId(it) }.orEmpty()
        }
        if (myId.isBlank()) return
        val key = wakeKey.ifBlank { "wake" }
        val ts = System.currentTimeMillis()
        thread(name = "WakeWordArbiterAlive", isDaemon = true) {
            sendPacket(PREFIX_ALIVE, myId, ts, key)
        }
    }

    fun announceSessionEnd(context: Context? = null, wakeKey: String = "") {
        context?.applicationContext?.let { appContext = it }
        val myId = localDeviceId.ifBlank {
            appContext?.let { AvaVoiceDiscovery.resolveLocalDeviceId(it) }.orEmpty()
        }
        if (myId.isBlank()) {
            Log.w(TAG, "session end skipped: no local id")
            return
        }
        val key = wakeKey.ifBlank { "wake" }
        val ts = System.currentTimeMillis()
        thread(name = "WakeWordArbiterEnd", isDaemon = true) {
            // One UDP miss used to leave losers stuck under the dim. Spread the
            // repeats (was 40 ms apart): DTIM buffering and Wi-Fi congestion drop
            // in bursts, so tightly-packed copies live or die together. Losers
            // also expire on their own now (keepalive sustain), END is just the
            // fast path.
            val gapsMs = longArrayOf(200L, 600L)
            repeat(3) { index ->
                val sent = sendPacket(PREFIX_END, myId, ts, key)
                Log.d(TAG, "session end broadcast[$index] sent=$sent id=$myId key=$key")
                gapsMs.getOrNull(index)?.let { gap ->
                    try {
                        Thread.sleep(gap)
                    } catch (_: InterruptedException) {
                        return@thread
                    }
                }
            }
        }
    }

    private fun stopLocked() {
        running = false
        val socket = listenSocket
        listenSocket = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        listenThread = null
        synchronized(claimsLock) {
            recentClaims.clear()
        }
        peerSession.reset()
        lastWinAtElapsed = 0L
        releaseMulticastLock()
    }

    /**
     * Android's APF filter drops incoming IPv4 broadcast unless a multicast lock is
     * held. The presence beacon happens to hold one today, but the arbiter must not
     * depend on another feature's lock staying alive for its whole lifetime.
     */
    private fun acquireMulticastLock(context: Context) {
        if (multicastLock?.isHeld == true) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("$TAG::MulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "multicast lock acquired")
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

    private fun listenLoop(socket: DatagramSocket) {
        val buffer = ByteArray(PACKET_SIZE)
        while (running && !socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val end = parseSessionEnd(data)
                if (end != null) {
                    if (end.deviceId != localDeviceId) {
                        Log.d(TAG, "heard session end: id=${end.deviceId} key=${end.wakeKey}")
                        notifyPeerSessionEnd(end.wakeKey)
                    }
                    continue
                }
                // Keepalive only sustains the hold: no claim memory, no seat
                // challenge, no blur restart. markPeer() is true only on a fresh
                // engage, so the 4 s cadence cannot spam the log.
                val alive = parsePrefixed(data, PREFIX_ALIVE)
                if (alive != null) {
                    if (alive.deviceId != localDeviceId && peerSession.markPeer()) {
                        Log.d(TAG, "peer chorus session hold from keepalive id=${alive.deviceId}")
                    }
                    continue
                }
                val claim = parsePrefixed(data, PREFIX) ?: continue
                if (claim.deviceId == localDeviceId) continue
                rememberClaim(claim)
                maybeRevokeSeat(claim)
                // A claim heard while we are already yielding means the user re-woke
                // the winner: our own detection is gated during the hold, so this UDP
                // packet is the only re-wake signal a dimmed loser gets. Restart the
                // dim so the effect follows every wake, not just the first.
                val wasYielding = peerSession.isActive()
                if (peerSession.markPeer()) {
                    Log.d(TAG, "peer chorus session hold from claim id=${claim.deviceId}")
                }
                if (wasYielding) {
                    ChorusWakeBlurService.restartForPeerWake()
                }
                Log.d(TAG, "heard competitor: id=${claim.deviceId} ts=${claim.timestamp} key=${claim.wakeKey}")
            } catch (_: SocketTimeoutException) {
                pruneClaims()
                if (peerSession.consumeExpired()) {
                    Log.d(TAG, "peer chorus session timed out")
                    notifyPeerSessionEnd("timeout")
                }
            } catch (e: Exception) {
                if (running && !socket.isClosed) {
                    Log.w(TAG, "listen error: ${e.message}")
                }
            }
        }
    }

    private fun sendClaim(deviceId: String, timestamp: Long, wakeKey: String, score: Int): Boolean {
        val sent = sendPacket(PREFIX, deviceId, timestamp, wakeKey, score)
        if (sent) {
            Log.d(TAG, "broadcast sent: deviceId=$deviceId ts=$timestamp wakeWord=$wakeKey score=$score")
        }
        // Repeats run off-thread so they do not eat into the caller's collect window.
        // Receivers dedupe by deviceId, so extra copies only refresh what a lost first
        // packet would have delivered.
        thread(name = "WakeWordArbiterClaim", isDaemon = true) {
            repeat(CLAIM_REPEATS - 1) {
                try {
                    Thread.sleep(CLAIM_REPEAT_GAP_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                sendPacket(PREFIX, deviceId, timestamp, wakeKey, score)
            }
        }
        return sent
    }

    private fun sendPacket(
        prefix: String,
        deviceId: String,
        timestamp: Long,
        wakeKey: String,
        score: Int = 0,
    ): Boolean {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
            }
            val message = if (prefix == PREFIX_END) {
                "$prefix|$deviceId|$timestamp|$wakeKey"
            } else {
                "$prefix|$deviceId|$timestamp|$wakeKey|$score"
            }
            val bytes = message.toByteArray(Charsets.UTF_8)
            var sentAny = false
            broadcastTargets().forEach { target ->
                try {
                    socket.send(DatagramPacket(bytes, bytes.size, target, PORT))
                    sentAny = true
                } catch (e: Exception) {
                    Log.w(TAG, "broadcast to $target failed: ${e.message}")
                }
            }
            sentAny
        } catch (e: Exception) {
            Log.e(TAG, "broadcast failed: ${e.message}")
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * A device with a slower engine (or a DTIM-delayed broadcast) can detect the same
     * utterance after our collect window closed. It never saw the race it lost — and we
     * never saw the claim that should have beaten us. If its claim clearly out-scores
     * our recent win, hand over the seat instead of double-answering.
     *
     * Deliberately does not consume [lastWinAtElapsed]: the handler may reject a revoke
     * that lands before the new session reaches Listening, and the repeated claim
     * copies then serve as retries. The handler dedupes accepted revokes itself, and
     * losing the seat zeroes the window via [setLocalChorusWinner].
     */
    private fun maybeRevokeSeat(claim: WakeClaim) {
        val wonAt = lastWinAtElapsed
        if (wonAt == 0L) return
        if (SystemClock.elapsedRealtime() - wonAt > SEAT_REVOKE_WINDOW_MS) return
        if (!keysOverlap(claim.wakeKey, lastWinKey)) return
        if (claim.score - lastWinScore <= SCORE_CLEAR_LEAD) return
        Log.d(
            TAG,
            "seat challenged by ${claim.deviceId}: score=${claim.score} vs local=$lastWinScore key=$lastWinKey",
        )
        onSeatRevoked?.invoke(lastWinKey)
    }

    private fun rememberClaim(claim: WakeClaim) {
        val now = SystemClock.elapsedRealtime()
        synchronized(claimsLock) {
            recentClaims.addLast(claim.copy(receivedAtElapsed = now))
            pruneClaimsLocked(now)
        }
    }

    private fun pruneClaims() {
        synchronized(claimsLock) {
            pruneClaimsLocked(SystemClock.elapsedRealtime())
        }
    }

    private fun pruneClaimsLocked(nowElapsed: Long) {
        while (recentClaims.isNotEmpty()) {
            val oldest = recentClaims.first()
            if (nowElapsed - oldest.receivedAtElapsed <= CLAIM_RETAIN_MS) break
            recentClaims.removeFirst()
        }
    }

    private fun snapshotCompetitors(
        myId: String,
        myTimestamp: Long,
        wakeKey: String,
    ): List<ChorusClaim> {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(claimsLock) {
            pruneClaimsLocked(nowElapsed)
            return recentClaims
                .filter { claim ->
                    claim.deviceId != myId &&
                        keysOverlap(claim.wakeKey, wakeKey) &&
                        (
                            abs(claim.timestamp - myTimestamp) <= CLAIM_WINDOW_MS ||
                                nowElapsed - claim.receivedAtElapsed <= CLAIM_WINDOW_MS
                            )
                }
                // Newest claim per device: a re-claiming device must be judged on its
                // current score, not the oldest copy still in the retain buffer.
                .sortedByDescending { it.receivedAtElapsed }
                .map { it.toChorusClaim() }
                .distinctBy { it.deviceId }
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

    private fun notifyPeerSessionEnd(wakeKey: String) {
        peerSession.endPeer()
        onPeerSessionEnd?.invoke(wakeKey)
        appContext?.let { ChorusWakeBlurService.fadeOut(it) }
    }

    private fun parsePrefixed(data: String, prefix: String): WakeClaim? {
        val parts = data.trim('\u0000', ' ', '\n', '\r', '\t').split('|')
        if (parts.size < 4 || parts[0] != prefix) return null
        val id = parts[1].ifBlank { return null }
        val ts = parts[2].toLongOrNull() ?: return null
        val wakeKey = parts[3].ifBlank { return null }
        val score = parts.getOrNull(4)?.toIntOrNull() ?: 0
        return WakeClaim(id, ts, wakeKey, score, 0L)
    }

    /**
     * END packets used to require exactly 4 `|` fields. A missing wake key, or
     * `AVA_WAKE_END|id|ts`, dropped the packet and left losers under the dim.
     */
    private fun parseSessionEnd(data: String): WakeClaim? {
        val trimmed = data.trim('\u0000', ' ', '\n', '\r', '\t')
        if (!trimmed.startsWith("$PREFIX_END|") && trimmed != PREFIX_END) return null
        val parts = trimmed.split('|')
        val id = parts.getOrNull(1)?.ifBlank { null } ?: return null
        val ts = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val wakeKey = parts.getOrNull(3).orEmpty()
        return WakeClaim(id, ts, wakeKey, 0, 0L)
    }

    private fun pickWinner(
        myId: String,
        myTimestamp: Long,
        myScore: Int,
        competitors: List<ChorusClaim>,
    ): String = pickChorusWinner(
        myId = myId,
        myTimestamp = myTimestamp,
        myScore = myScore,
        claims = competitors,
    )

    private data class WakeClaim(
        val deviceId: String,
        val timestamp: Long,
        val wakeKey: String,
        val score: Int,
        val receivedAtElapsed: Long,
    ) {
        fun toChorusClaim(): ChorusClaim =
            ChorusClaim(deviceId = deviceId, timestamp = timestamp, score = score)
    }

    /**
     * Open uses catalog / native ids (`hey_jarvis`) while the spoken label is
     * `Hey Jarvis`. Compact both so Micro and Open share one chorus key.
     */
    internal fun normalizeWakeKey(wakeWordId: String, wakeWordPhrase: String = ""): String {
        val aliases = linkedSetOf<String>()
        compactWakeToken(wakeWordId).takeIf { it.isNotBlank() }?.let { aliases.add(it) }
        compactWakeToken(wakeWordPhrase).takeIf { it.isNotBlank() }?.let { aliases.add(it) }
        return aliases.joinToString(",").ifBlank { "wake" }
    }

    private fun compactWakeToken(raw: String): String {
        val alnum = buildString(raw.length) {
            raw.trim().lowercase().forEach { ch ->
                if (ch.isLetterOrDigit()) append(ch)
            }
        }
        var compact = alnum
        SUFFIXES_TO_STRIP.forEach { suffix ->
            if (compact.endsWith(suffix) && compact.length > suffix.length) {
                compact = compact.removeSuffix(suffix)
            }
        }
        compact = compact.replace(Regex("v\\d+$"), "")
        return compact
    }

    private fun keysOverlap(left: String, right: String): Boolean {
        val a = left.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val b = right.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (a.isEmpty() || b.isEmpty()) return true
        for (leftToken in a) {
            for (rightToken in b) {
                if (leftToken == rightToken) return true
                if (leftToken.length >= 4 && rightToken.length >= 4 &&
                    (leftToken.contains(rightToken) || rightToken.contains(leftToken))
                ) {
                    return true
                }
            }
        }
        return false
    }
}

data class ArbiterResult(
    val shouldRespond: Boolean,
    val reason: String,
    val competitorCount: Int = 0,
)

internal data class ChorusClaim(
    val deviceId: String,
    val timestamp: Long,
    val score: Int,
)

internal fun packScore(confidence: Float, detectRms: Float): Int {
    val rms = detectRms.coerceIn(0f, 1f)
    val conf = confidence.coerceIn(0f, 1f)
    return (rms * 9_000f).toInt() + (conf * 999f).toInt()
}

/**
 * Loudest wake wins. Wall-clock and device-id used to always seat the same box.
 * Near-ties rotate from the shared claim timestamps so both sides agree.
 */
internal fun pickChorusWinner(
    myId: String,
    myTimestamp: Long,
    myScore: Int,
    claims: List<ChorusClaim>,
    scoreTieEps: Int = 180,
): String {
    val all = claims + ChorusClaim(deviceId = myId, timestamp = myTimestamp, score = myScore)
    val best = all.maxOf { it.score }
    val top = all.filter { best - it.score <= scoreTieEps }
    if (top.size == 1) return top.first().deviceId
    val salt = (all.minOf { it.timestamp } / 2_000L).toString()
    return top.minBy { mixChorusTie(it.deviceId, salt) }.deviceId
}

private fun mixChorusTie(deviceId: String, salt: String): Int {
    var hash = 0
    val text = "$deviceId|$salt"
    for (ch in text) {
        hash = hash * 31 + ch.code
    }
    return hash
}

suspend fun arbitrateWakeWord(
    context: Context,
    wakeWordId: String,
    wakeWordPhrase: String = "",
    timestamp: Long = System.currentTimeMillis(),
): ArbiterResult = WakeWordArbiter.arbitrate(context, wakeWordId, wakeWordPhrase, timestamp)
