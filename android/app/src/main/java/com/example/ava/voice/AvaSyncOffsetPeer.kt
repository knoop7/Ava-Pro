package com.example.ava.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.ava.audio.eq.MusicEqGains
import com.example.ava.audio.eq.MusicEqRuntime
import com.example.ava.audio.eq.MusicEqSource
import com.example.ava.audio.eq.toMusicEqGains
import com.example.ava.lyrics.LyricDisplayRuntime
import com.example.ava.sendspin.SendspinPeerBeacon
import com.example.ava.services.VinylCoverService
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.massapi.MassApiManager
import com.example.ava.settings.SendspinSettingsStore
import com.example.ava.settings.sendspinSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal Ava↔Ava sync-offset peer control on the always-on LAN UDP presence socket
 * ([AvaVoiceProtocol.PORT] / [AvaVoiceDiscovery] HOLDER_PRESENCE).
 *
 * Not tied to voice-message switches, fleet/cluster, or Mass player-config APIs.
 * New builds advertise `syncOffsetPeer=1` on the existing beacon; older peers ignore it.
 *
 * Wire (UTF-8, `|`-separated):
 * - GET  `AVA_SYNC_OFFSET_GET|{fromId}|{toId}`
 * - SET  `AVA_SYNC_OFFSET_SET|{fromId}|{toId}|{ms}`
 * - VAL  `AVA_SYNC_OFFSET_VAL|{fromId}|{toId}|{ms}`  (reply)
 *
 * Music-EQ (Sendspin 5-band) rides the same socket, dB as `%.1f`:
 * - GET  `AVA_MUSIC_EQ_GET|{fromId}|{toId}`
 * - SET  `AVA_MUSIC_EQ_SET|{fromId}|{toId}|{0/1}|{bass}|{lowMid}|{mid}|{upperMid}|{treble}`
 * - VAL  `AVA_MUSIC_EQ_VAL|{fromId}|{toId}|{0/1}|{bass}|{lowMid}|{mid}|{upperMid}|{treble}`
 *
 * Lyric display (overlay offset + follow step) — same GET/SET/VAL shape:
 * - GET  `AVA_LYRICS_GET|{fromId}|{toId}`
 * - SET  `AVA_LYRICS_SET|{fromId}|{toId}|{leadMs}|{followStep}`
 * - VAL  `AVA_LYRICS_VAL|{fromId}|{toId}|{leadMs}|{followStep}`
 *
 * Playback beacon (differential multi-room alignment, broadcast, no reply):
 * - BEACON `AVA_SYNC_BEACON|{fromId}|{audibleServerTsUs}|{flags}|{streamKey}`
 *   "The sample at my DAC right now was scheduled for server time X." Grouped
 *   peers subtract their own reading — common-mode sync errors cancel, and only
 *   the lagging device accelerates ([SendspinClient.onPeerPlaybackBeacon]).
 *   Mixer-head (no DAC timestamp) still broadcasts; a trusted DAC will not
 *   chase it, but untrusted boxes can still catch up.
 *
 * Media identity mirror (broadcast, no reply), fields percent-encoded:
 * - META `AVA_MEDIA_META|{fromId}|{streamKey}|{title}|{artist}|{album}|{artUrl}`
 *   Music Assistant is configured *per device*, so one Ava can hold a cover and
 *   a full artist/album while its group partner — rendering the very same PCM —
 *   only ever got a title out of sparse Sendspin metadata. This carries the
 *   richer identity across so the group looks alike.
 *
 *   Two rules keep it from becoming a second source of truth:
 *   [streamKey] must match ours (same stream, so it describes the same audio),
 *   and a receiver only ever **fills its own blanks** — never overwrites a field
 *   it holds from its own upstream. Gap-filling also removes any need for an
 *   authority election: two peers exchanging metadata cannot ping-pong when
 *   neither can overwrite the other.
 *
 * Playhead (broadcast, no reply):
 * - PROG `AVA_MEDIA_PROGRESS|{fromId}|{streamKey}|{progressMs}|{atServerTsUs}`
 *   `|{flags}|{speed}|{epoch}|{originId}|{trackKey}`
 *   A timeline assertion, not a position: `progressMs` holds at `atServerTsUs`
 *   on the shared server clock and advances at `speed` (0 = paused, so the
 *   anchor is constant). `epoch` is a Lamport clock over deliberate playhead
 *   changes — higher wins outright, equal refines drift, lower is answered with
 *   ours. See `SendspinPeerProgressSample`.
 *   `epoch` / `originId` are appended, so a peer on the older 7-field build
 *   still parses and degrades to a drift-only contributor. `trackKey` is a
 *   later append (track-generation hash, 0 = unknown): the group-stable
 *   streamKey no longer separates two songs around a track change, this does.
 *
 * Song-container window (broadcast, no reply):
 * - EXPAND `AVA_VINYL_EXPAND|{fromId}|{streamKey}|{0/1}`
 *   User opened (1) or collapsed (0) the now-playing page. A receiver on the
 *   same stream applies the matching existing HA force-expand / force-hide
 *   path. A missing flag is treated as open, so the first 3-field build still
 *   expands. Older builds ignore the prefix.
 */
object AvaSyncOffsetPeer {
    private const val TAG = "AvaSyncOffsetPeer"

    const val GET_PREFIX = "AVA_SYNC_OFFSET_GET"
    const val SET_PREFIX = "AVA_SYNC_OFFSET_SET"
    const val VAL_PREFIX = "AVA_SYNC_OFFSET_VAL"

    const val EQ_GET_PREFIX = "AVA_MUSIC_EQ_GET"
    const val EQ_SET_PREFIX = "AVA_MUSIC_EQ_SET"
    const val EQ_VAL_PREFIX = "AVA_MUSIC_EQ_VAL"

    const val LYRICS_GET_PREFIX = "AVA_LYRICS_GET"
    const val LYRICS_SET_PREFIX = "AVA_LYRICS_SET"
    const val LYRICS_VAL_PREFIX = "AVA_LYRICS_VAL"

    const val BEACON_PREFIX = "AVA_SYNC_BEACON"

    const val MEDIA_META_PREFIX = "AVA_MEDIA_META"

    const val MEDIA_PROGRESS_PREFIX = "AVA_MEDIA_PROGRESS"

    const val VINYL_EXPAND_PREFIX = "AVA_VINYL_EXPAND"

    /**
     * Playhead tick. Faster than the 1Hz beacon/identity cadence because a
     * paired bar has to converge within a second of a pause / scrub, but the
     * beacon and identity mirrors keep their own 1Hz — doubling those would only
     * double LAN traffic (the identity packet alone is up to
     * [MEDIA_META_MAX_BYTES]).
     */
    private const val MEDIA_PROGRESS_INTERVAL_MS = 500L

    /** Ticks per beacon / identity broadcast, i.e. the original 1Hz. */
    private const val BEACON_TICKS = 2

    /** Minimum spacing between playhead bursts — a burst is 5 datagrams. */
    private const val MEDIA_PROGRESS_BURST_MIN_GAP_MS = 250L

    /**
     * How long an elected playhead leader is remembered without one of its own
     * packets. Several beacon intervals, so ordinary UDP loss cannot hand
     * authority to a higher id and back.
     */
    private const val PROGRESS_LEADER_MEMORY_MS = 5_000L

    /**
     * [AvaVoiceDiscovery.receiveSession] reads into a 512-byte buffer, and an
     * over-long datagram is truncated *silently* — so the encoder budgets fields
     * instead of trusting them. Percent-encoding is what makes this tight: one
     * CJK character is 3 UTF-8 bytes and therefore 9 encoded characters, so a
     * short-looking Chinese title/album pair can already approach the limit.
     */
    private const val MEDIA_META_MAX_BYTES = 480

    /**
     * Re-announce cadence for a peer that joined mid-track or missed the change
     * packet — UDP has no retransmission and identity changes only once a song.
     */
    private const val MEDIA_META_REANNOUNCE_TICKS = 2

    /** Ticks a *changed* identity is repeated on — see [maybeBroadcastMediaMeta]. */
    private const val MEDIA_META_CHANGE_BURST = 5

    /** Pause/play/scrub playhead bursts — UDP has no retransmission. */
    private const val MEDIA_PROGRESS_CHANGE_BURST = 5

    /** Spacing inside a playhead burst. */
    private const val MEDIA_PROGRESS_BURST_SPACING_MS = 40L

    /** FAB expand has no ACK — repeat like a playhead change. */
    private const val VINYL_EXPAND_BURST = 5

    /** Same clamp as the Mass rail “this device” slider. */
    const val MIN_MS = -1000
    const val MAX_MS = 1000

    private const val REPLY_TIMEOUT_MS = 2_500L

    /**
     * Capability probes (GET) only. A LAN UDP round trip is single-digit ms, so
     * the write timeout spent most of its budget waiting on peers that were
     * never going to answer — with several id candidates tried in sequence that
     * alone delayed the first row by seconds. Retries cover a dropped packet.
     */
    private const val PROBE_TIMEOUT_MS = 900L

    private val appContextRef = AtomicReference<Context?>(null)
    private val pending =
        ConcurrentHashMap<String, CompletableDeferred<Int>>()
    private val pendingEq =
        ConcurrentHashMap<String, CompletableDeferred<MusicEqGains>>()
    private val pendingLyrics =
        ConcurrentHashMap<String, CompletableDeferred<LyricPeerState>>()
    /** Last UDP source host seen for a peer id (VAL / GET / SET). */
    private val lastHostById = ConcurrentHashMap<String, String>()

    /**
     * Last playback beacon per peer id. [SendspinPeerBeacon.streamKey] is the
     * group-stable stream key (hash of server+group id; `play_at` only before
     * the first group/update), which every member of a sync group shares, so a
     * peer whose key equals ours is provably rendering our stream right now.
     */
    private val peerStreamKeyById = ConcurrentHashMap<String, StreamMark>()

    private data class StreamMark(val streamKey: Long, val atElapsed: Long)

    /** Two missed 1Hz beacons still count as present; three drop the peer. */
    private const val PEER_STREAM_STALE_MS = 3_500L

    /** ANDROID_ID is 64 bits rendered as hex — the tail to look for in a Mass id. */
    private const val ANDROID_ID_LEN = 16

    /**
     * Last successful probe per Mass player id. Re-opening the delay / EQ /
     * lyrics page paints from here at once instead of running the whole
     * discovery again; the page still refreshes in the background and
     * overwrites these.
     */
    private val boundCache = ConcurrentHashMap<String, BoundPeer>()
    private val boundEqCache = ConcurrentHashMap<String, BoundEqPeer>()
    private val boundLyricsCache = ConcurrentHashMap<String, BoundLyricsPeer>()

    private val beaconScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val beaconLoopStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Serialized worker for inbound messages. GET/SET handlers hit DataStore
     * and reply over the network; running them inline on the presence-socket
     * receive loop blocked `socket.receive()`, so progress datagrams arriving
     * meanwhile were dropped in the kernel buffer. Single-parallelism keeps
     * per-sender ordering (bursts are 40ms apart and order-sensitive).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val inboundScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Epoch sanity cap: a corrupt / hostile epoch near Long.MAX_VALUE would
     * permanently poison [SendspinManager]'s Lamport clock (its +1 overflows
     * negative, demoting the device below every peer forever). */
    private const val MAX_PEER_EPOCH = 1_000_000_000L

    /** Progress sanity cap (7 days) — beyond this it is corruption, not audio. */
    private const val MAX_PEER_PROGRESS_MS = 604_800_000L

    private var mediaMetaTick = 0
    /** Identity of the last mirror packet sent; unchanged identity is not resent. */
    private var lastSentMediaMetaKey: String? = null
    /** Remaining repeats of the current identity change. */
    private var mediaMetaRepeatsLeft = 0
    private var lastMediaProgressBurstElapsed = 0L
    /** Lowest peer beacon id currently publishing a playhead — see [isProgressLeader]. */
    @Volatile
    private var lowestPeerProgressId: String? = null
    @Volatile
    private var lowestPeerProgressAtElapsed = 0L

    data class BoundPeer(
        val peer: AvaVoiceDevice,
        val offsetMs: Int,
    )

    data class BoundEqPeer(
        val peer: AvaVoiceDevice,
        val gains: MusicEqGains,
    )

    /** Overlay lyric tuner state mirrored over presence UDP. */
    data class LyricPeerState(
        val leadMs: Long,
        val followStep: Int,
    ) {
        fun normalized(): LyricPeerState = LyricPeerState(
            leadMs = leadMs.coerceIn(
                LyricDisplayRuntime.OFFSET_MIN_MS,
                LyricDisplayRuntime.OFFSET_MAX_MS,
            ),
            followStep = followStep.coerceIn(
                LyricDisplayRuntime.FOLLOW_MIN,
                LyricDisplayRuntime.FOLLOW_MAX,
            ),
        )
    }

    data class BoundLyricsPeer(
        val peer: AvaVoiceDevice,
        val state: LyricPeerState,
    )

    fun bindAppContext(context: Context) {
        appContextRef.set(context.applicationContext)
        ensurePlaybackBeaconLoop()
    }

    /**
     * Identity or cover just changed locally — send a mirror burst now instead
     * of waiting up to 1s for the beacon tick (first-play paired cover).
     */
    fun requestMediaMetaBurst() {
        lastSentMediaMetaKey = null
        mediaMetaRepeatsLeft = MEDIA_META_CHANGE_BURST
        beaconScope.launch {
            try {
                val service = VoiceSatelliteService.getInstance() ?: return@launch
                val localId = localBeaconId()
                if (localId.isBlank()) return@launch
                val streamKey = service.sendspinPeerStreamKey() ?: return@launch
                maybeBroadcastMediaMeta(localId, streamKey)
            } catch (e: Exception) {
                Log.w(TAG, "media meta burst failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * User opened or collapsed the now-playing page. Burst so a single dropped
     * UDP datagram does not leave the paired window out of step.
     */
    fun requestVinylWindow(expanded: Boolean) {
        beaconScope.launch {
            try {
                val service = VoiceSatelliteService.getInstance() ?: return@launch
                val localId = localBeaconId()
                if (localId.isBlank()) return@launch
                val streamKey = service.sendspinPeerStreamKey() ?: return@launch
                if (streamKey == 0L) return@launch
                val payload = buildVinylExpand(localId, streamKey, expanded)
                repeat(VINYL_EXPAND_BURST) { index ->
                    if (index > 0) delay(MEDIA_PROGRESS_BURST_SPACING_MS)
                    sendBroadcast(payload)
                }
            } catch (e: Exception) {
                Log.w(TAG, "vinyl window burst failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Pause freeze / resume seat / scrub just changed — push the playhead now
     * instead of waiting for the next tick (paired UI must lock the same second
     * immediately). Rate-limited: a burst is [MEDIA_PROGRESS_CHANGE_BURST]
     * datagrams, and the callers include per-write paths.
     */
    fun requestMediaProgressBurst(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastMediaProgressBurstElapsed < MEDIA_PROGRESS_BURST_MIN_GAP_MS) return
        lastMediaProgressBurstElapsed = now
        beaconScope.launch {
            try {
                repeat(MEDIA_PROGRESS_CHANGE_BURST) { index ->
                    if (index > 0) delay(MEDIA_PROGRESS_BURST_SPACING_MS)
                    val localId = localBeaconId()
                    if (localId.isBlank()) return@launch
                    maybeBroadcastMediaProgress(localId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "media progress burst failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Our beacon id. [AvaVoiceDiscovery.localId] is only populated once presence
     * has been acquired, so derive the same deterministic id when a probe runs
     * before that — otherwise every peer row reads "not supported" purely
     * because this device had not named itself yet.
     */
    private fun localBeaconId(): String =
        AvaVoiceDiscovery.localId().ifBlank {
            appContextRef.get()?.let { AvaVoiceDiscovery.resolveLocalDeviceId(it) }.orEmpty()
        }

    /**
     * Broadcast loop for the playback beacon, the identity mirror and the
     * playhead. Ticks at [MEDIA_PROGRESS_INTERVAL_MS]; the beacon and identity
     * only ride every [BEACON_TICKS]th tick, keeping their 1Hz contract.
     *
     * The beacon is only sent while Sendspin audio is actually leaving the
     * speaker (the snapshot is null otherwise); the mirror rides the same tick
     * but keeps running through that gap — see [maybeBroadcastMediaMeta].
     */
    private fun ensurePlaybackBeaconLoop() {
        if (!beaconLoopStarted.compareAndSet(false, true)) return
        beaconScope.launch {
            var tick = 0
            while (true) {
                delay(MEDIA_PROGRESS_INTERVAL_MS)
                try {
                    val service = VoiceSatelliteService.getInstance() ?: continue
                    // Deterministic fallback id: presence acquisition is async on
                    // cold start, and a master already playing must not sit mute
                    // until the discovery layer names this device.
                    val localId = localBeaconId()
                    if (localId.isBlank()) continue
                    val slowTick = (++tick % BEACON_TICKS) == 0
                    val beacon = service.sendspinPeerPlaybackBeacon()
                    if (slowTick && beacon != null) {
                        sendBroadcast(buildPlaybackBeacon(localId, beacon))
                    }
                    val streamKey = beacon?.streamKey ?: service.sendspinPeerStreamKey() ?: continue
                    if (slowTick) maybeBroadcastMediaMeta(localId, streamKey)
                    maybeBroadcastMediaProgress(localId)
                } catch (e: Exception) {
                    Log.w(TAG, "playback beacon tick failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /**
     * Broadcast our identity when it changed, plus a periodic re-announce for
     * peers that were not listening at the moment of change.
     *
     * Not gated on the playback beacon. A track change silences the beacon on
     * *both* sides for a few seconds (previous tail draining, `play_at` not yet
     * audible, the ~1s auto-next stop), which is precisely the window in which
     * the new cover and artist need to travel — gating on it meant the change
     * packet was neither sent nor accepted, and the peer sat on the old cover
     * until the next re-announce.
     *
     * A change is repeated over [MEDIA_META_CHANGE_BURST] ticks because UDP has
     * no retransmission and the receiver only fills blanks *its own* upstream
     * has already reset, which can be a second or two after we saw the change.
     * Repeats are idempotent: a peer that already filled ignores them.
     */
    private suspend fun maybeBroadcastMediaMeta(localId: String, streamKey: Long) {
        val identity = VoiceSatelliteService.getInstance()
            ?.sendspinPeerMediaIdentity() ?: return
        val meta = MediaMeta(
            streamKey = streamKey,
            title = identity.title,
            artist = identity.artist,
            album = identity.album,
            artworkUrl = identity.artworkUrl,
        )
        val key =
            "$streamKey|${meta.title}|${meta.artist}|${meta.album}|${meta.artworkUrl}"
        val reannounceDue = (++mediaMetaTick % MEDIA_META_REANNOUNCE_TICKS) == 0
        if (key != lastSentMediaMetaKey) {
            mediaMetaRepeatsLeft = MEDIA_META_CHANGE_BURST
        } else if (mediaMetaRepeatsLeft <= 0 && !reannounceDue) {
            return
        }
        val payload = buildMediaMeta(localId, meta) ?: return
        if (mediaMetaRepeatsLeft > 0) mediaMetaRepeatsLeft--
        lastSentMediaMetaKey = key
        sendBroadcast(payload)
    }

    /**
     * Is [fromId] the playhead leader — the lowest beacon id currently on the
     * air, and lower than ours?
     *
     * Sync authority has to be a total order, not a pairwise comparison. With
     * three devices a plain `fromId < localId` makes *two* peers outrank the
     * highest id, which then alternates between their playheads every packet.
     * Tracking the lowest peer id instead gives every device one stable leader.
     *
     * The mark is refreshed only by the incumbent (or someone lower), so a
     * leader that leaves the group ages out and the next packet re-elects.
     */
    private fun isProgressLeader(fromId: String, localId: String, sameStream: Boolean): Boolean {
        // Leadership is only meaningful among devices rendering *our* stream.
        // Letting any low-id device on the LAN — playing an unrelated group —
        // seize incumbency hijacked authority for 5s at a time while the real
        // stream partner stayed permanently outranked.
        if (!sameStream) return false
        val now = SystemClock.elapsedRealtime()
        val incumbent = lowestPeerProgressId
        val expired = now - lowestPeerProgressAtElapsed > PROGRESS_LEADER_MEMORY_MS
        when {
            incumbent == null || expired || fromId <= incumbent -> {
                lowestPeerProgressId = fromId
                lowestPeerProgressAtElapsed = now
            }
            else -> return false
        }
        return fromId < localId
    }

    /**
     * Differential playhead (playing) or absolute freeze (paused). Always sent
     * when we hold a seat — both sides publish so either can lock the other.
     * Re-sampled per datagram so a burst carries the current seat, not a copy.
     */
    private suspend fun maybeBroadcastMediaProgress(localId: String) {
        val sample = VoiceSatelliteService.getInstance()
            ?.sendspinPeerProgressSample() ?: return
        val payload = buildMediaProgress(localId, sample) ?: return
        sendBroadcast(payload)
    }

    /**
     * Ava beacon ids currently rendering **our** Sendspin stream, proven by a
     * fresh playback beacon whose `streamKey` (group-stable key) equals
     * ours. This is the audio layer's own truth about who plays with us, so it
     * holds even while MA's `synced_to` / `group_members` still lag, and it
     * needs no MA identity at all.
     *
     * Empty while this device is not audibly playing — nothing to be grouped
     * with — and stale entries are dropped so an ex-member cannot linger.
     */
    fun peersSyncPlayingWithUs(): Set<String> {
        val ourKey = VoiceSatelliteService.getInstance()
            ?.sendspinPeerPlaybackBeacon()
            ?.streamKey
            ?: return emptySet()
        val now = SystemClock.elapsedRealtime()
        val out = mutableSetOf<String>()
        val it = peerStreamKeyById.entries.iterator()
        while (it.hasNext()) {
            val (peerId, mark) = it.next()
            if (now - mark.atElapsed > PEER_STREAM_STALE_MS) {
                it.remove()
                continue
            }
            if (mark.streamKey == ourKey) out.add(peerId)
        }
        return out
    }

    /**
     * The sender is on our MA sync group. Unpaired LAN boxes must not
     * adopt pause/seek/play or follow our bar.
     *
     * Refuse only on a live "not paired". No arbiter (MA still connecting on
     * cold start, or not configured on this device) must not mute the LAN
     * fallback layer — the same-stream key check on every packet still holds,
     * and a stream key is only shared by a user-sanctioned Sendspin group
     * (see SendspinGroupIdentity).
     */
    fun isPairedLanPeer(peerAvaId: String): Boolean {
        if (peerAvaId.isBlank()) return false
        return MassApiManager.get()?.isLanPeerMassPaired(peerAvaId) != false
    }

    /** Whether [playerId] (Mass player id or beacon id) plays our stream now. */
    fun isSyncPlayingWithUs(playerId: String): Boolean {
        val candidates = avaIdCandidates(playerId)
        if (candidates.isEmpty()) return false
        val playing = peersSyncPlayingWithUs()
        return candidates.any { it in playing }
    }

    /** Last known probe result for [playerId], or null if never reached. */
    fun cachedBoundPeer(playerId: String): BoundPeer? = boundCache[playerId]

    fun cachedBoundEqPeer(playerId: String): BoundEqPeer? = boundEqCache[playerId]

    fun cachedBoundLyricsPeer(playerId: String): BoundLyricsPeer? = boundLyricsCache[playerId]

    fun clampMs(ms: Int): Int = ms.coerceIn(MIN_MS, MAX_MS)

    fun isGet(message: String): Boolean =
        message.startsWith("$GET_PREFIX|")

    fun isSet(message: String): Boolean =
        message.startsWith("$SET_PREFIX|")

    fun isVal(message: String): Boolean =
        message.startsWith("$VAL_PREFIX|")

    fun isEqGet(message: String): Boolean =
        message.startsWith("$EQ_GET_PREFIX|")

    fun isEqSet(message: String): Boolean =
        message.startsWith("$EQ_SET_PREFIX|")

    fun isEqVal(message: String): Boolean =
        message.startsWith("$EQ_VAL_PREFIX|")

    fun isEqMessage(message: String): Boolean =
        isEqGet(message) || isEqSet(message) || isEqVal(message)

    fun isLyricsGet(message: String): Boolean =
        message.startsWith("$LYRICS_GET_PREFIX|")

    fun isLyricsSet(message: String): Boolean =
        message.startsWith("$LYRICS_SET_PREFIX|")

    fun isLyricsVal(message: String): Boolean =
        message.startsWith("$LYRICS_VAL_PREFIX|")

    fun isLyricsMessage(message: String): Boolean =
        isLyricsGet(message) || isLyricsSet(message) || isLyricsVal(message)

    fun isPlaybackBeacon(message: String): Boolean =
        message.startsWith("$BEACON_PREFIX|")

    fun buildPlaybackBeacon(fromId: String, beacon: SendspinPeerBeacon): String =
        listOf(
            BEACON_PREFIX,
            fromId,
            beacon.audibleServerTsUs.toString(),
            beacon.flags.toString(),
            beacon.streamKey.toString(),
        ).joinToString("|")

    data class PlaybackBeacon(
        val fromId: String,
        val audibleServerTsUs: Long,
        val flags: Int,
        val streamKey: Long,
    )

    fun parsePlaybackBeacon(message: String): PlaybackBeacon? {
        val parts = message.split('|')
        if (parts.size < 5) return null
        val fromId = parts[1]
        if (fromId.isBlank()) return null
        val audibleServerTsUs = parts[2].toLongOrNull() ?: return null
        // Negative server timestamps are corruption; extreme values overflow
        // the receiver's delta arithmetic.
        if (audibleServerTsUs < 0L) return null
        return PlaybackBeacon(
            fromId = fromId,
            audibleServerTsUs = audibleServerTsUs,
            flags = parts[3].toIntOrNull() ?: return null,
            streamKey = parts[4].toLongOrNull() ?: return null,
        )
    }

    fun isMediaMeta(message: String): Boolean =
        message.startsWith("$MEDIA_META_PREFIX|")

    fun isMediaProgress(message: String): Boolean =
        message.startsWith("$MEDIA_PROGRESS_PREFIX|")

    fun isVinylExpand(message: String): Boolean =
        message.startsWith("$VINYL_EXPAND_PREFIX|")

    fun buildVinylExpand(fromId: String, streamKey: Long, expanded: Boolean): String =
        listOf(
            VINYL_EXPAND_PREFIX,
            fromId,
            streamKey.toString(),
            if (expanded) "1" else "0",
        ).joinToString("|")

    data class VinylExpand(
        val fromId: String,
        val streamKey: Long,
        val expanded: Boolean,
    )

    fun parseVinylExpand(message: String): VinylExpand? {
        val parts = message.split('|')
        if (parts.size < 3) return null
        val fromId = parts[1]
        if (fromId.isBlank()) return null
        val streamKey = parts[2].toLongOrNull() ?: return null
        if (streamKey == 0L) return null
        val expanded = when (parts.getOrNull(3)) {
            null, "", "1" -> true
            "0" -> false
            else -> return null
        }
        return VinylExpand(fromId = fromId, streamKey = streamKey, expanded = expanded)
    }

    /** One track's identity as a peer should see it. */
    data class MediaMeta(
        val streamKey: Long,
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val artworkUrl: String? = null,
    )

    data class MediaMetaBeacon(
        val fromId: String,
        val meta: MediaMeta,
    )

    private fun pctEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun pctDecode(value: String): String? =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrNull()

    /**
     * Encode a mirror packet, dropping whatever does not fit [MEDIA_META_MAX_BYTES].
     *
     * Fields are added by descending usefulness, and an over-budget field is
     * *omitted* rather than cut: a sliced percent-escape will not decode, and
     * half an artwork URL is a broken fetch. Title is the one field worth
     * shortening in place, since a clipped title still names the song — and
     * without it the packet carries nothing a receiver can match on.
     */
    fun buildMediaMeta(fromId: String, meta: MediaMeta): String? {
        val title = meta.title.trim()
        if (title.isBlank()) return null
        val head = "$MEDIA_META_PREFIX|$fromId|${meta.streamKey}|"
        var budget = MEDIA_META_MAX_BYTES - head.toByteArray(Charsets.UTF_8).size
        if (budget <= 0) return null

        var encodedTitle = pctEncode(title)
        var trimmed = title
        while (encodedTitle.length > budget / 2 && trimmed.length > 8) {
            // Drop a whole code point: an emoji in a title is ordinary, and
            // slicing its surrogate pair leaves a lone surrogate that does not
            // survive encoding.
            val last = trimmed.length - 1
            val cut = if (last >= 1 && Character.isLowSurrogate(trimmed[last])) 2 else 1
            trimmed = trimmed.substring(0, trimmed.length - cut)
            encodedTitle = pctEncode(trimmed)
        }
        if (encodedTitle.length > budget) return null
        budget -= encodedTitle.length

        // Three separators still to come, one per optional identity field.
        budget -= 3
        val optional = mutableListOf<String>()
        for (field in listOf(meta.artist, meta.album, meta.artworkUrl)) {
            val encoded = field?.trim()?.takeIf { it.isNotEmpty() }?.let { pctEncode(it) }
            if (encoded != null && encoded.length <= budget) {
                optional.add(encoded)
                budget -= encoded.length
            } else {
                optional.add("")
            }
        }
        return head + encodedTitle + "|" + optional.joinToString("|")
    }

    fun parseMediaMeta(message: String): MediaMetaBeacon? {
        val parts = message.split('|')
        if (parts.size < 7) return null
        val fromId = parts[1]
        if (fromId.isBlank()) return null
        val streamKey = parts[2].toLongOrNull() ?: return null
        // A failed decode means a truncated or corrupt datagram; taking the
        // readable half would hand the overlay a mangled title.
        val title = pctDecode(parts[3])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        fun optional(raw: String): String? =
            raw.takeIf { it.isNotEmpty() }?.let { pctDecode(it) }?.trim()?.takeIf { it.isNotEmpty() }
        // parts[7+] (legacy progressMs) intentionally ignored.
        return MediaMetaBeacon(
            fromId = fromId,
            meta = MediaMeta(
                streamKey = streamKey,
                title = title,
                artist = optional(parts[4]),
                album = optional(parts[5]),
                artworkUrl = optional(parts[6]),
            ),
        )
    }

    fun buildMediaProgress(
        fromId: String,
        sample: com.example.ava.sendspin.SendspinPeerProgressSample,
    ): String? {
        if (fromId.isBlank()) return null
        if (sample.progressMs < 0L) return null
        // A flat assertion — paused freeze or armed seat — must keep speed 0.
        // Coercing it to 1000 would tell the receiver to advance a position the
        // sender is holding still.
        val speed = when {
            sample.isPaused || sample.isArmed -> 0
            sample.playbackSpeed > 0 -> sample.playbackSpeed
            else -> 1000
        }
        // speed ≤ 0 alone means paused; make it explicit on the wire, but never
        // for an armed seat — that is flat and still going to play.
        val flags = if (sample.isPaused && !sample.isArmed) {
            sample.flags or com.example.ava.sendspin.SendspinPeerProgressSample.FLAG_PAUSED
        } else {
            sample.flags
        }
        return listOf(
            MEDIA_PROGRESS_PREFIX,
            fromId,
            sample.streamKey.toString(),
            sample.progressMs.toString(),
            sample.atServerTsUs.toString(),
            flags.toString(),
            speed.toString(),
            sample.epoch.toString(),
            // Empty origin means "authored here" — resolve it before it leaves,
            // so every receiver can compare origins without knowing who sent it.
            sample.originId.ifEmpty { fromId },
            sample.trackKey.toString(),
        ).joinToString("|")
    }

    data class MediaProgressBeacon(
        val fromId: String,
        val sample: com.example.ava.sendspin.SendspinPeerProgressSample,
    )

    fun parseMediaProgress(message: String): MediaProgressBeacon? {
        val parts = message.split('|')
        if (parts.size < 7) return null
        val fromId = parts[1]
        if (fromId.isBlank()) return null
        val streamKey = parts[2].toLongOrNull() ?: return null
        val progressMs = parts[3].toLongOrNull() ?: return null
        val atServerTsUs = parts[4].toLongOrNull() ?: return null
        val flags = parts[5].toIntOrNull() ?: 0
        // Clamp the rate: an absurd speed would overflow the receiver's anchor
        // projection (progress + delta * speed).
        val speedRaw = (parts[6].toIntOrNull() ?: 1000).coerceIn(0, 10_000)
        if (progressMs < 0L || progressMs > MAX_PEER_PROGRESS_MS) return null
        // 0 is the legitimate "no anchor yet" sentinel; anything negative
        // (including Long.MIN_VALUE) overflows `now - atServerTsUs` on the
        // receiver and defeats the max-age check.
        if (atServerTsUs < 0L) return null
        val armed = flags and com.example.ava.sendspin.SendspinPeerProgressSample.FLAG_ARMED != 0
        val flat = armed ||
            flags and com.example.ava.sendspin.SendspinPeerProgressSample.FLAG_PAUSED != 0 ||
            speedRaw <= 0
        val speed = if (flat) 0 else speedRaw.coerceAtLeast(1)
        // A legacy sender signals pause by speed=0 only — normalise to the flag,
        // but an armed seat is flat without being paused.
        val outFlags = if (flat && !armed) {
            flags or com.example.ava.sendspin.SendspinPeerProgressSample.FLAG_PAUSED
        } else {
            flags
        }
        // Epoch / origin are appended fields: a peer still on the 7-field build
        // reports epoch 0, which orders below every real assertion and simply
        // leaves it as a drift-only contributor instead of breaking the pair.
        val epoch = parts.getOrNull(7)?.toLongOrNull() ?: 0L
        // A single packet with a near-MAX epoch would lock the receiver's
        // Lamport clock forever (see MAX_PEER_EPOCH). Drop, don't clamp:
        // clamping would still elect the corrupt claim over honest ones.
        if (epoch < 0L || epoch > MAX_PEER_EPOCH) return null
        val originId = parts.getOrNull(8)?.takeIf { it.isNotBlank() } ?: fromId
        // Appended field: legacy senders have none — 0 keeps them tolerant
        // (track door only closes when both sides declare a generation).
        val trackKey = parts.getOrNull(9)?.toLongOrNull() ?: 0L
        return MediaProgressBeacon(
            fromId = fromId,
            sample = com.example.ava.sendspin.SendspinPeerProgressSample(
                streamKey = streamKey,
                progressMs = progressMs,
                atServerTsUs = atServerTsUs,
                flags = outFlags,
                playbackSpeed = speed,
                epoch = epoch.coerceAtLeast(0L),
                originId = originId,
                trackKey = trackKey,
            ),
        )
    }

    fun buildGet(fromId: String, toId: String): String =
        listOf(GET_PREFIX, fromId, toId).joinToString("|")

    fun buildSet(fromId: String, toId: String, ms: Int): String =
        listOf(SET_PREFIX, fromId, toId, clampMs(ms).toString()).joinToString("|")

    fun buildVal(fromId: String, toId: String, ms: Int): String =
        listOf(VAL_PREFIX, fromId, toId, clampMs(ms).toString()).joinToString("|")

    private fun eqFields(gains: MusicEqGains): List<String> {
        val n = gains.normalized()
        fun fmt(v: Float) = String.format(java.util.Locale.US, "%.1f", v)
        return listOf(
            if (n.enabled) "1" else "0",
            fmt(n.bassDb),
            fmt(n.lowMidDb),
            fmt(n.midDb),
            fmt(n.upperMidDb),
            fmt(n.trebleDb),
        )
    }

    fun buildEqGet(fromId: String, toId: String): String =
        listOf(EQ_GET_PREFIX, fromId, toId).joinToString("|")

    fun buildEqSet(fromId: String, toId: String, gains: MusicEqGains): String =
        (listOf(EQ_SET_PREFIX, fromId, toId) + eqFields(gains)).joinToString("|")

    fun buildEqVal(fromId: String, toId: String, gains: MusicEqGains): String =
        (listOf(EQ_VAL_PREFIX, fromId, toId) + eqFields(gains)).joinToString("|")

    /** Fields 3..8 of an EQ SET/VAL message; null on malformed payloads. */
    fun parseEqGains(message: String): MusicEqGains? {
        val parts = message.split('|')
        if (parts.size < 9) return null
        val dbs = FloatArray(5)
        for (i in 0 until 5) {
            dbs[i] = parts[4 + i].toFloatOrNull() ?: return null
        }
        return MusicEqGains(
            enabled = parts[3] == "1",
            bassDb = dbs[0],
            lowMidDb = dbs[1],
            midDb = dbs[2],
            upperMidDb = dbs[3],
            trebleDb = dbs[4],
        ).normalized()
    }

    fun buildLyricsGet(fromId: String, toId: String): String =
        listOf(LYRICS_GET_PREFIX, fromId, toId).joinToString("|")

    fun buildLyricsSet(fromId: String, toId: String, state: LyricPeerState): String {
        val n = state.normalized()
        return listOf(
            LYRICS_SET_PREFIX,
            fromId,
            toId,
            n.leadMs.toString(),
            n.followStep.toString(),
        ).joinToString("|")
    }

    fun buildLyricsVal(fromId: String, toId: String, state: LyricPeerState): String {
        val n = state.normalized()
        return listOf(
            LYRICS_VAL_PREFIX,
            fromId,
            toId,
            n.leadMs.toString(),
            n.followStep.toString(),
        ).joinToString("|")
    }

    /** Fields 3..4 of a lyrics SET/VAL message; null on malformed payloads. */
    fun parseLyricsState(message: String): LyricPeerState? {
        val parts = message.split('|')
        if (parts.size < 5) return null
        val leadMs = parts[3].toLongOrNull() ?: return null
        val followStep = parts[4].toIntOrNull() ?: return null
        return LyricPeerState(leadMs = leadMs, followStep = followStep).normalized()
    }

    data class Envelope(
        val fromId: String,
        val toId: String,
        val ms: Int? = null,
    )

    fun parseEnvelope(message: String, expectMs: Boolean): Envelope? {
        val parts = message.split('|')
        if (parts.size < 3) return null
        val fromId = parts[1]
        val toId = parts[2]
        if (fromId.isBlank() || toId.isBlank()) return null
        val ms = if (expectMs) {
            parts.getOrNull(3)?.toIntOrNull()?.let { clampMs(it) } ?: return null
        } else {
            null
        }
        return Envelope(fromId = fromId, toId = toId, ms = ms)
    }

    /**
     * Every beacon id [playerId] could stand for, best first. Mass player_id is
     * usually ANDROID_ID; beacon id is `ava_<hash>`.
     *
     * A Mass server may namespace the client id it was handed: this device
     * announces ANDROID_ID `ce77…86b9` and Mass reports the player as
     * `upce77…86b9`. Hashing the prefixed form yields a beacon id no peer will
     * ever advertise, so the bare trailing ANDROID_ID has to be a candidate too.
     * Both forms are offered instead of stripping outright, because an id that is
     * itself long and hex must not lose its head to a guess.
     */
    fun avaIdCandidates(playerId: String): List<String> {
        val id = playerId.trim()
        if (id.isBlank()) return emptyList()
        if (id.startsWith("ava_")) return listOf(id)
        return buildList {
            add(AvaVoiceDiscovery.stableAvaDeviceId(id))
            namespacedDeviceId(id)?.let { add(AvaVoiceDiscovery.stableAvaDeviceId(it)) }
        }.distinct()
    }

    /** Best-guess single beacon id; prefer [avaIdCandidates] when matching. */
    fun resolveTargetAvaId(playerId: String): String =
        avaIdCandidates(playerId).firstOrNull().orEmpty()

    /** The ANDROID_ID [id] ends with once a server prefix is dropped, or null. */
    private fun namespacedDeviceId(id: String): String? {
        if (id.length <= ANDROID_ID_LEN) return null
        val tail = id.takeLast(ANDROID_ID_LEN)
        if (!tail.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return tail
    }

    /**
     * Match a Mass player to a LAN Ava beacon.
     * Prefer peers advertising [AvaVoiceDevice.syncOffsetPeer]; fall back to any
     * Ava identity so a briefly stale beacon flag does not block probing.
     */
    fun findPeerForMassPlayer(playerId: String, displayName: String = ""): AvaVoiceDevice? {
        if (playerId.isBlank()) return null
        val all = AvaVoiceDiscovery.devices.value
        if (all.isEmpty()) return null
        val hashed = avaIdCandidates(playerId)
        fun match(list: List<AvaVoiceDevice>): AvaVoiceDevice? {
            if (list.isEmpty()) return null
            list.firstOrNull { it.id in hashed || it.id == playerId.trim() }?.let { return it }
            val want = normalizePeerName(displayName)
            if (want.isBlank()) return null
            return list.firstOrNull { normalizePeerName(it.name) == want }
        }
        match(all.filter { it.syncOffsetPeer })?.let { return it }
        return match(all)
    }

    /**
     * Probe peer capability + current offset. Prefers unicast via beacon, else LAN broadcast GET.
     * Only returns non-null when a VAL reply arrives (our Ava build with sync-offset peer).
     *
     * Important: when a beacon peer is known, address them by [AvaVoiceDevice.id]
     * (pending key + wire toId). Do not rely only on hash(Mass player_id) — name
     * match can find the right host while the hash diverges, so VAL never completes.
     */
    suspend fun bindForMassPlayer(playerId: String, displayName: String = ""): BoundPeer? {
        if (playerId.isBlank()) return null
        val localId = localBeaconId().ifBlank { return null }
        val known = findPeerForMassPlayer(playerId, displayName)
        val peerIds = peerIdCandidates(playerId, known)
        if (peerIds.isEmpty()) return null

        if (known != null && known.host.isNotBlank() && known.host != "0.0.0.0") {
            for (peerId in peerIds) {
                val ms = roundTripToHost(
                    known.host, peerId, buildGet(localId, peerId), PROBE_TIMEOUT_MS,
                )
                if (ms != null) {
                    return BoundPeer(known.copy(syncOffsetPeer = true), ms)
                        .also { boundCache[playerId] = it }
                }
            }
        }

        for (peerId in peerIds) {
            val ms = roundTripBroadcast(peerId, buildGet(localId, peerId), PROBE_TIMEOUT_MS)
                ?: continue
            val host = lastHostById[peerId]?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                ?: continue
            val peer = known?.copy(host = host, syncOffsetPeer = true)
                ?: AvaVoiceDevice(
                    id = peerId,
                    name = displayName.trim().ifBlank { peerId },
                    host = host,
                    type = AvaVoiceDeviceType.UNKNOWN,
                    syncOffsetPeer = true,
                )
            return BoundPeer(peer, ms).also { boundCache[playerId] = it }
        }
        return null
    }

    /** Order: beacon id first (authoritative), then Mass-derived hash / raw ava_ id. */
    private fun peerIdCandidates(playerId: String, known: AvaVoiceDevice?): List<String> =
        buildList {
            known?.id?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(avaIdCandidates(playerId))
            val raw = playerId.trim()
            if (raw.startsWith("ava_")) add(raw)
        }.distinct().filter { it.isNotBlank() }

    /**
     * Probe peer capability + current music-EQ state. Same flow as [bindForMassPlayer];
     * only returns non-null when an EQ VAL reply arrives (our Ava build with music-EQ peer).
     */
    suspend fun bindEqForMassPlayer(playerId: String, displayName: String = ""): BoundEqPeer? {
        if (playerId.isBlank()) return null
        val localId = localBeaconId().ifBlank { return null }
        val known = findPeerForMassPlayer(playerId, displayName)
        val peerIds = peerIdCandidates(playerId, known)
        if (peerIds.isEmpty()) return null

        if (known != null && known.host.isNotBlank() && known.host != "0.0.0.0") {
            for (peerId in peerIds) {
                val gains = eqRoundTripToHost(
                    known.host, peerId, buildEqGet(localId, peerId), PROBE_TIMEOUT_MS,
                )
                if (gains != null) {
                    return BoundEqPeer(known.copy(syncOffsetPeer = true), gains)
                        .also { boundEqCache[playerId] = it }
                }
            }
        }

        for (peerId in peerIds) {
            val gains = eqRoundTripBroadcast(peerId, buildEqGet(localId, peerId), PROBE_TIMEOUT_MS)
                ?: continue
            val host = lastHostById[peerId]?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                ?: continue
            val peer = known?.copy(host = host, syncOffsetPeer = true)
                ?: AvaVoiceDevice(
                    id = peerId,
                    name = displayName.trim().ifBlank { peerId },
                    host = host,
                    type = AvaVoiceDeviceType.UNKNOWN,
                    syncOffsetPeer = true,
                )
            return BoundEqPeer(peer, gains).also { boundEqCache[playerId] = it }
        }
        return null
    }

    /**
     * Probe peer capability + current lyric tuner state. Same flow as
     * [bindEqForMassPlayer]; only returns non-null when a LYRICS VAL arrives.
     */
    suspend fun bindLyricsForMassPlayer(
        playerId: String,
        displayName: String = "",
    ): BoundLyricsPeer? {
        if (playerId.isBlank()) return null
        val localId = localBeaconId().ifBlank { return null }
        val known = findPeerForMassPlayer(playerId, displayName)
        val peerIds = peerIdCandidates(playerId, known)
        if (peerIds.isEmpty()) return null

        if (known != null && known.host.isNotBlank() && known.host != "0.0.0.0") {
            for (peerId in peerIds) {
                val state = lyricsRoundTripToHost(
                    known.host, peerId, buildLyricsGet(localId, peerId), PROBE_TIMEOUT_MS,
                )
                if (state != null) {
                    return BoundLyricsPeer(known.copy(syncOffsetPeer = true), state)
                        .also { boundLyricsCache[playerId] = it }
                }
            }
        }

        for (peerId in peerIds) {
            val state = lyricsRoundTripBroadcast(
                peerId, buildLyricsGet(localId, peerId), PROBE_TIMEOUT_MS,
            ) ?: continue
            val host = lastHostById[peerId]?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
                ?: continue
            val peer = known?.copy(host = host, syncOffsetPeer = true)
                ?: AvaVoiceDevice(
                    id = peerId,
                    name = displayName.trim().ifBlank { peerId },
                    host = host,
                    type = AvaVoiceDeviceType.UNKNOWN,
                    syncOffsetPeer = true,
                )
            return BoundLyricsPeer(peer, state).also { boundLyricsCache[playerId] = it }
        }
        return null
    }

    /**
     * Push lyric offset + follow to the peer. The VAL echoes the peer's state
     * *after* applying — success only when it matches what we sent, so a
     * silently-refused SET cannot report as applied.
     */
    suspend fun setLyricsState(peer: AvaVoiceDevice, state: LyricPeerState): Boolean {
        if (peer.host.isBlank() || peer.host == "0.0.0.0") return false
        val localId = localBeaconId().ifBlank { return false }
        val targetId = peer.id.ifBlank { return false }
        val payload = buildLyricsSet(localId, targetId, state)
        val expected = state.normalized()
        if (lyricsRoundTripToHost(peer.host, targetId, payload) == expected) return true
        return lyricsRoundTripBroadcast(targetId, payload) == expected
    }

    /** VAL echoes post-apply gains; only band-for-band agreement is success. */
    private fun eqAppliedMatches(sent: MusicEqGains, replied: MusicEqGains): Boolean {
        // The receiver refuses SETs while its EQ master switch is off but
        // still echoes its current (unchanged) state — that must read as
        // failure, not success.
        if (!replied.enabled) return false
        val s = sent.normalized()
        // Wire format is %.1f — allow half of that resolution as tolerance.
        fun close(a: Float, b: Float) = kotlin.math.abs(a - b) <= 0.05f
        return close(s.bassDb, replied.bassDb) &&
            close(s.lowMidDb, replied.lowMidDb) &&
            close(s.midDb, replied.midDb) &&
            close(s.upperMidDb, replied.upperMidDb) &&
            close(s.trebleDb, replied.trebleDb)
    }

    /** Push a full EQ curve to the peer; success only when the echo matches. */
    suspend fun setEqGains(peer: AvaVoiceDevice, gains: MusicEqGains): Boolean {
        if (peer.host.isBlank() || peer.host == "0.0.0.0") return false
        val localId = localBeaconId().ifBlank { return false }
        val targetId = peer.id.ifBlank { return false }
        val replied = eqRoundTripToHost(peer.host, targetId, buildEqSet(localId, targetId, gains))
        if (replied != null && eqAppliedMatches(gains, replied)) return true
        // Unicast miss → broadcast once (host may have changed).
        val broadcasted = eqRoundTripBroadcast(targetId, buildEqSet(localId, targetId, gains))
        return broadcasted != null && eqAppliedMatches(gains, broadcasted)
    }

    suspend fun setOffsetMs(peer: AvaVoiceDevice, ms: Int): Boolean {
        if (peer.host.isBlank() || peer.host == "0.0.0.0") return false
        val localId = localBeaconId().ifBlank { return false }
        val targetId = peer.id.ifBlank { return false }
        val clamped = clampMs(ms)
        val replied = roundTripToHost(
            peer.host,
            targetId,
            buildSet(localId, targetId, clamped),
        )
        if (replied == clamped) return true
        // Unicast miss → broadcast once (host may have changed).
        val broadcasted = roundTripBroadcast(targetId, buildSet(localId, targetId, clamped))
        return broadcasted == clamped
    }

    fun onValReply(message: String, sourceHost: String = "") {
        val env = parseEnvelope(message, expectMs = true) ?: return
        if (sourceHost.isNotBlank()) {
            lastHostById[env.fromId] = sourceHost
        }
        // VAL from peer: fromId=peer, toId=us
        val deferred = pending[env.fromId] ?: return
        deferred.complete(env.ms ?: return)
    }

    fun onEqValReply(message: String, sourceHost: String = "") {
        val env = parseEnvelope(message, expectMs = false) ?: return
        val gains = parseEqGains(message) ?: return
        if (sourceHost.isNotBlank()) {
            lastHostById[env.fromId] = sourceHost
        }
        pendingEq[env.fromId]?.complete(gains)
    }

    fun onLyricsValReply(message: String, sourceHost: String = "") {
        val env = parseEnvelope(message, expectMs = false) ?: return
        val state = parseLyricsState(message) ?: return
        if (sourceHost.isNotBlank()) {
            lastHostById[env.fromId] = sourceHost
        }
        pendingLyrics[env.fromId]?.complete(state)
    }

    /**
     * Handle inbound GET/SET addressed to this device. Called from the presence UDP loop
     * (never gated by voice-message feature flags).
     *
     * Enqueue-and-return: the caller is the socket receive loop, and the
     * handlers below block on DataStore / network replies / the audio stack.
     * Handling them inline stalled `socket.receive()` long enough to drop
     * progress datagrams. [inboundScope] is single-parallelism, so message
     * order is preserved.
     */
    suspend fun handleInbound(message: String, sourceHost: String, localId: String) {
        inboundScope.launch {
            try {
                handleInboundSerial(message, sourceHost, localId)
            } catch (e: Exception) {
                Log.w(TAG, "inbound failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private suspend fun handleInboundSerial(message: String, sourceHost: String, localId: String) {
        when {
            isGet(message) -> {
                val env = parseEnvelope(message, expectMs = false) ?: return
                if (env.toId != localId) return
                if (sourceHost.isNotBlank()) lastHostById[env.fromId] = sourceHost
                val ms = readLocalOffsetMs()
                sendTo(sourceHost, buildVal(localId, env.fromId, ms))
            }
            isSet(message) -> {
                val env = parseEnvelope(message, expectMs = true) ?: return
                if (env.toId != localId) return
                if (sourceHost.isNotBlank()) lastHostById[env.fromId] = sourceHost
                val ms = env.ms ?: return
                applyLocalOffsetMs(ms)
                sendTo(sourceHost, buildVal(localId, env.fromId, ms))
            }
            isVal(message) -> onValReply(message, sourceHost)
            isPlaybackBeacon(message) -> {
                val beacon = parsePlaybackBeacon(message) ?: return
                // Broadcasts loop back to the sender — never steer toward ourselves.
                if (beacon.fromId == localId) return
                if (!isPairedLanPeer(beacon.fromId)) return
                if (sourceHost.isNotBlank()) lastHostById[beacon.fromId] = sourceHost
                peerStreamKeyById[beacon.fromId] =
                    StreamMark(beacon.streamKey, SystemClock.elapsedRealtime())
                VoiceSatelliteService.getInstance()?.onSendspinPeerPlaybackBeacon(
                    beacon.fromId,
                    beacon.audibleServerTsUs,
                    beacon.flags,
                    beacon.streamKey,
                )
            }
            isMediaMeta(message) -> {
                val packet = parseMediaMeta(message) ?: return
                if (packet.fromId == localId) return
                if (!isPairedLanPeer(packet.fromId)) return
                if (sourceHost.isNotBlank()) lastHostById[packet.fromId] = sourceHost
                // Only a peer on our own stream describes our own audio. Without
                // this an unrelated group's track would overwrite our blanks.
                // Attachment, not audibility: the track change we most need to
                // hear about is also the moment our own beacon goes quiet.
                val ourKey = VoiceSatelliteService.getInstance()
                    ?.sendspinPeerStreamKey() ?: return
                if (packet.meta.streamKey != ourKey) return
                VoiceSatelliteService.getInstance()?.onSendspinPeerMediaMeta(
                    packet.meta.title,
                    packet.meta.artist,
                    packet.meta.album,
                    packet.meta.artworkUrl,
                )
            }
            isMediaProgress(message) -> {
                val packet = parseMediaProgress(message) ?: return
                if (packet.fromId == localId) return
                if (!isPairedLanPeer(packet.fromId)) return
                if (sourceHost.isNotBlank()) lastHostById[packet.fromId] = sourceHost
                val ourKey = VoiceSatelliteService.getInstance()?.sendspinPeerStreamKey()
                val sameStream = ourKey != null && ourKey != 0L &&
                    packet.sample.streamKey != 0L &&
                    packet.sample.streamKey == ourKey
                if (!sameStream) return
                VoiceSatelliteService.getInstance()?.onSendspinPeerProgress(
                    packet.sample,
                    peerOutranksLocal = isProgressLeader(packet.fromId, localId, sameStream),
                )
            }
            isVinylExpand(message) -> {
                val packet = parseVinylExpand(message) ?: return
                if (packet.fromId == localId) return
                if (!isPairedLanPeer(packet.fromId)) return
                if (sourceHost.isNotBlank()) lastHostById[packet.fromId] = sourceHost
                // Same-stream only — do not open/close an unrelated Ava on the LAN.
                val ourKey = VoiceSatelliteService.getInstance()
                    ?.sendspinPeerStreamKey() ?: return
                if (packet.streamKey != ourKey) return
                val ctx = appContextRef.get() ?: return
                if (packet.expanded) {
                    VinylCoverService.showExpandedFromHa(ctx)
                } else {
                    VinylCoverService.hideExpandedFromHa(ctx)
                }
            }
            isEqGet(message) -> {
                val env = parseEnvelope(message, expectMs = false) ?: return
                if (env.toId != localId) return
                if (sourceHost.isNotBlank()) lastHostById[env.fromId] = sourceHost
                sendTo(sourceHost, buildEqVal(localId, env.fromId, readLocalEqGains()))
            }
            isEqSet(message) -> {
                val env = parseEnvelope(message, expectMs = false) ?: return
                if (env.toId != localId) return
                val gains = parseEqGains(message) ?: return
                if (sourceHost.isNotBlank()) lastHostById[env.fromId] = sourceHost
                applyLocalEqGains(gains)
                sendTo(sourceHost, buildEqVal(localId, env.fromId, readLocalEqGains()))
            }
            isEqVal(message) -> onEqValReply(message, sourceHost)
            isLyricsGet(message) -> {
                val env = parseEnvelope(message, expectMs = false) ?: return
                if (env.toId != localId) return
                if (sourceHost.isNotBlank()) lastHostById[env.fromId] = sourceHost
                sendTo(sourceHost, buildLyricsVal(localId, env.fromId, readLocalLyricsState()))
            }
            isLyricsSet(message) -> {
                val env = parseEnvelope(message, expectMs = false) ?: return
                if (env.toId != localId) return
                val state = parseLyricsState(message) ?: return
                if (sourceHost.isNotBlank()) lastHostById[env.fromId] = sourceHost
                applyLocalLyricsState(state)
                sendTo(sourceHost, buildLyricsVal(localId, env.fromId, readLocalLyricsState()))
            }
            isLyricsVal(message) -> onLyricsValReply(message, sourceHost)
        }
    }

    private fun normalizePeerName(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("Ava - ", ignoreCase = true)) {
            s = s.substring(6).trim()
        } else if (s.startsWith("Ava-", ignoreCase = true)) {
            s = s.substring(4).trim()
        }
        return s.lowercase()
    }

    private suspend fun roundTripToHost(
        host: String,
        peerId: String,
        payload: String,
        timeoutMs: Long = REPLY_TIMEOUT_MS,
    ): Int? {
        pending.remove(peerId)?.cancel()
        val deferred = CompletableDeferred<Int>()
        pending[peerId] = deferred
        return try {
            sendTo(host, payload)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(peerId, deferred)
        }
    }

    private suspend fun roundTripBroadcast(
        peerId: String,
        payload: String,
        timeoutMs: Long = REPLY_TIMEOUT_MS,
    ): Int? {
        pending.remove(peerId)?.cancel()
        val deferred = CompletableDeferred<Int>()
        pending[peerId] = deferred
        return try {
            sendBroadcast(payload)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(peerId, deferred)
        }
    }

    private suspend fun eqRoundTripToHost(
        host: String,
        peerId: String,
        payload: String,
        timeoutMs: Long = REPLY_TIMEOUT_MS,
    ): MusicEqGains? {
        pendingEq.remove(peerId)?.cancel()
        val deferred = CompletableDeferred<MusicEqGains>()
        pendingEq[peerId] = deferred
        return try {
            sendTo(host, payload)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingEq.remove(peerId, deferred)
        }
    }

    private suspend fun eqRoundTripBroadcast(
        peerId: String,
        payload: String,
        timeoutMs: Long = REPLY_TIMEOUT_MS,
    ): MusicEqGains? {
        pendingEq.remove(peerId)?.cancel()
        val deferred = CompletableDeferred<MusicEqGains>()
        pendingEq[peerId] = deferred
        return try {
            sendBroadcast(payload)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingEq.remove(peerId, deferred)
        }
    }

    private suspend fun lyricsRoundTripToHost(
        host: String,
        peerId: String,
        payload: String,
        timeoutMs: Long = REPLY_TIMEOUT_MS,
    ): LyricPeerState? {
        pendingLyrics.remove(peerId)?.cancel()
        val deferred = CompletableDeferred<LyricPeerState>()
        pendingLyrics[peerId] = deferred
        return try {
            sendTo(host, payload)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingLyrics.remove(peerId, deferred)
        }
    }

    private suspend fun lyricsRoundTripBroadcast(
        peerId: String,
        payload: String,
        timeoutMs: Long = REPLY_TIMEOUT_MS,
    ): LyricPeerState? {
        pendingLyrics.remove(peerId)?.cancel()
        val deferred = CompletableDeferred<LyricPeerState>()
        pendingLyrics[peerId] = deferred
        return try {
            sendBroadcast(payload)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingLyrics.remove(peerId, deferred)
        }
    }

    private suspend fun readLocalOffsetMs(): Int {
        val ctx = appContextRef.get() ?: return 0
        return runCatching {
            SendspinSettingsStore(ctx.sendspinSettingsStore).get().syncOffsetMs.let { clampMs(it) }
        }.getOrDefault(0)
    }

    private suspend fun readLocalEqGains(): MusicEqGains {
        val ctx = appContextRef.get() ?: return MusicEqGains.FLAT
        return runCatching {
            SendspinSettingsStore(ctx.sendspinSettingsStore).get().toMusicEqGains()
        }.getOrDefault(MusicEqGains.FLAT)
    }

    private suspend fun applyLocalEqGains(gains: MusicEqGains) {
        val ctx = appContextRef.get() ?: return
        runCatching {
            val store = SendspinSettingsStore(ctx.sendspinSettingsStore)
            val current = store.get().toMusicEqGains()
            // Settings master switch is the only EQ gate (rail is not).
            // Off → refuse bands and do not flip the switch on.
            if (!current.enabled) return
            // The wire carries bands only; adaptive stays a local choice.
            val applied = gains.normalized().copy(
                enabled = true,
                adaptiveEnabled = current.adaptiveEnabled,
            )
            store.setMusicEq(applied)
            MusicEqRuntime.set(MusicEqSource.SENDSPIN, applied)
        }.onFailure { Log.w(TAG, "apply local eq failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private suspend fun applyLocalOffsetMs(ms: Int) {
        val clamped = clampMs(ms)
        val ctx = appContextRef.get() ?: return
        runCatching {
            SendspinSettingsStore(ctx.sendspinSettingsStore).syncOffsetMs.set(clamped)
            VoiceSatelliteService.getInstance()?.updateSendspinSyncOffset(clamped)
        }.onFailure { Log.w(TAG, "apply local offset failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun readLocalLyricsState(): LyricPeerState {
        val ctx = appContextRef.get()
        if (ctx != null) LyricDisplayRuntime.ensureLoaded(ctx)
        return LyricPeerState(
            leadMs = LyricDisplayRuntime.currentLeadMs(),
            followStep = LyricDisplayRuntime.currentFollowStep(),
        ).normalized()
    }

    private fun applyLocalLyricsState(state: LyricPeerState) {
        val ctx = appContextRef.get() ?: return
        val n = state.normalized()
        runCatching {
            LyricDisplayRuntime.ensureLoaded(ctx)
            LyricDisplayRuntime.setLeadMs(ctx, n.leadMs)
            LyricDisplayRuntime.setFollowStep(ctx, n.followStep)
        }.onFailure {
            Log.w(TAG, "apply local lyrics failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** Always off main thread — Compose LaunchedEffect is Main. */
    private suspend fun sendTo(host: String, payload: String) = withContext(Dispatchers.IO) {
        try {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val address = InetAddress.getByName(host)
            DatagramSocket().use { socket ->
                socket.send(
                    DatagramPacket(bytes, bytes.size, address, AvaVoiceProtocol.PORT),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "send failed to $host: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun sendBroadcast(payload: String) = withContext(Dispatchers.IO) {
        try {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            DatagramSocket().use { socket ->
                socket.broadcast = true
                broadcastTargets().forEach { target ->
                    socket.send(
                        DatagramPacket(bytes, bytes.size, target, AvaVoiceProtocol.PORT),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "broadcast failed: ${e.javaClass.simpleName}: ${e.message}")
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
            Log.w(TAG, "broadcast target resolve failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return targets.toList()
    }
}
