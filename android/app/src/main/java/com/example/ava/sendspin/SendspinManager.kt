package com.example.ava.sendspin

import android.content.ComponentCallbacks2
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.example.ava.R
import com.example.ava.sendspin.noise.SendspinNoiseTransport
import com.example.ava.massapi.MassApiManager
import com.example.ava.mods.ModMediaOverlayExclusive
import com.example.ava.services.MediaOverlayMemoryCache
import com.example.ava.services.VinylCoverService
import com.example.ava.ui.AvaToast
import com.example.ava.voice.AvaSyncOffsetPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/**
 * Track identity mirrored between Ava devices playing one stream, so a device
 * without Music Assistant does not sit next to one that has the cover and the
 * full artist. Playhead alignment is a separate differential packet — see
 * [SendspinPeerProgressSample].
 */
data class SendspinPeerMediaIdentity(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
)

/**
 * One **playhead timeline assertion** for LAN UI alignment.
 *
 * Not a position — the anchor of a line. [progressMs] is the track position at
 * [atServerTsUs] on the shared Sendspin server clock, advancing at
 * [playbackSpeed]. Any receiver reconstructs the current position as
 * `progressMs + (myServerNow − atServerTsUs) * speed / 1_000_000`, so datagram
 * latency, broadcast tick phase and receive time all cancel out. Paused is the
 * same formula with `speed = 0`, which makes it a constant — pause, play and
 * seek need no separate wire semantics.
 *
 * [epoch] is a Lamport clock over *deliberate* playhead changes (seek, pause,
 * resume, track seat). It is what turns a lossy broadcast into a reliable
 * notification:
 * - higher epoch  → newer user intent, adopt unconditionally, whoever sent it;
 * - equal epoch   → same timeline, refine drift only ([originId] breaks ties);
 * - lower epoch   → sender is stale; ignore it and answer with ours, which
 *   re-syncs a device that missed the change without anyone polling.
 *
 * Because an adopted assertion is republished with the *same* epoch, echoing is
 * a no-op and the anchor can be broadcast forever instead of for a fixed window
 * — a peer that missed a seek entirely still converges on the next packet.
 *
 * [originId] is the beacon id that authored [epoch]; empty means this device.
 */
data class SendspinPeerProgressSample(
    val streamKey: Long,
    val progressMs: Long,
    val atServerTsUs: Long,
    val flags: Int,
    val playbackSpeed: Int,
    val epoch: Long = 0L,
    val originId: String = "",
    /**
     * Track-generation key (FNV-1a of the title whose progress this asserts);
     * 0 = unknown. The stream key survives seeks *and* track changes, so this
     * is the only axis separating two songs on the wire: samples from a peer
     * still on the previous track are dropped instead of dragging a fresh
     * 0:00 back to the old position. Appended wire field — legacy builds
     * parse it away and report 0, which stays tolerant.
     */
    val trackKey: Long = 0L,
) {
    companion object {
        const val FLAG_DAC_TRUSTED = SendspinPeerBeacon.FLAG_DAC_TRUSTED
        const val FLAG_STEADY = SendspinPeerBeacon.FLAG_STEADY
        /** Absolute freeze — [progressMs] is the shared paused playhead. */
        const val FLAG_PAUSED = 4
        /**
         * Absolute seat while still playing (local scrub / resume seat).
         * Retained as a hint for legacy senders; [epoch] is the authority.
         */
        const val FLAG_ABSOLUTE = 8
        /**
         * Armed: the position is standing still, but playback is *intended* —
         * a resume or seek target that has no PCM yet.
         *
         * Distinct from [FLAG_PAUSED] on purpose. Both hold a constant
         * position, so both travel as `speed = 0`, but a receiver must not turn
         * an armed seat into a pause hold: that is what blocked the partner's
         * thaw and made the first second after a replay disagree between the
         * two ends. Advertising the seat flat (rather than advancing it) is
         * also what the sender is really showing, so neither bar has to lie
         * while it waits for PCM.
         */
        const val FLAG_ARMED = 16
        /**
         * The author could not deliver an honored controller command for this
         * seat (controller role revoked, or seek absent from its session's
         * `supported_commands`): a partner whose own controller session works
         * should execute the transport on the author's behalf. Only the seat's
         * author sets this; an adopter that executed the command re-broadcasts
         * the seat without it, so a chain of devices issues it exactly once.
         */
        const val FLAG_TRANSPORT_PENDING = 32
    }

    /** Playback is intended; the position simply is not moving yet. */
    val isArmed: Boolean
        get() = flags and FLAG_ARMED != 0

    /** The author is asking a controller-capable partner to run the transport. */
    val transportPending: Boolean
        get() = flags and FLAG_TRANSPORT_PENDING != 0

    val isPaused: Boolean
        get() = !isArmed && (flags and FLAG_PAUSED != 0 || playbackSpeed <= 0)

    /** Absolute seat — a scrub or resume target, not a freeze. */
    val isAbsoluteSeat: Boolean
        get() = !isPaused && (isArmed || flags and FLAG_ABSOLUTE != 0)

    /** The assertion carries a usable shared-clock anchor. */
    val hasAnchor: Boolean
        get() = atServerTsUs > 0L
}

class SendspinManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val tag = "SendspinManager"
    private val inboundServerLock = Any()
    private var client: SendspinClient? = null
    private val discovery = SendspinDiscovery(context)
    private var socketServer: SendspinSocketServer? = null
    private var inboundServerPort: Int? = null
    private var inboundServerName: String? = null
    private var arbiter: SendspinArbiter? = null

    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl = _serverUrl.asStateFlow()

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning = _serverRunning.asStateFlow()

    private val _connectedServer = MutableStateFlow<String?>(null)
    val connectedServer = _connectedServer.asStateFlow()

    val discoveredServers = discovery.discoveredServers
    val isAdvertising = discovery.isAdvertising

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive = _isActive.asStateFlow()

    private var externalVolumeCallback: ((Float) -> Unit)? = null
    private var externalMuteCallback: ((Boolean) -> Unit)? = null
    private var externalPlayingCallback: ((Boolean) -> Unit)? = null
    private var vinylCoverEnabledCallback: (() -> Boolean)? = null
    private var lowMemoryModeCallback: (() -> Boolean)? = null
    private var preferredFormatCallback: (() -> String)? = null
    private var deviceNameCallback: (() -> String)? = null
    private var syncOffsetMsCallback: (() -> Int)? = null
    private var cachedSyncOffsetMs: Int? = null
    private var getVolumeCallback: (() -> Int)? = null
    private var setVolumeCallback: ((Int) -> Unit)? = null
    private var getMutedCallback: (() -> Boolean)? = null
    private var setMutedCallback: ((Boolean) -> Unit)? = null
    private var suppressDeviceVolumeObserver: ((Int) -> Unit)? = null
    private var savePairedDeviceCallback: ((String, String) -> Unit)? = null
    private var getPairedDevicesCallback: (() -> Map<String, String>)? = null
    private var savePlayedServerIdCallback: ((String) -> Unit)? = null
    private var getLastPlayedServerIdCallback: (() -> String)? = null

    private var clientStateJob: Job? = null
    private var reconnectJob: Job? = null
    private var hideVinylJob: Job? = null
    /** Suppress spurious `stopped` from the old server during arbitration handoff. */
    private var connectionHandoffUntilMs = 0L
    private var inboundMode = false
    private var inboundServerStarting = false
    /**
     * When inbound advertise gets no MA connection, silently browse
     * `_sendspin-server._tcp` and outbound-connect once. Settings UI unchanged.
     */
    private var inboundFallbackJob: Job? = null
    /** Re-bind after [java.net.BindException] without tearing the manager down. */
    private var inboundBindRetryJob: Job? = null
    private var inboundBindAttempts = 0
    private var silentOutboundWatchJob: Job? = null
    /** Prevent fallback loops after a failed silent outbound resume. */
    private var silentOutboundCooldownUntilMs = 0L
    private var silentOutboundResumePort: Int? = null
    private var silentOutboundResumeName: String? = null

    private var currentVolume = 1.0f
    private var currentMuted = false
    /** While set, ignore hardware observer echoes of our own STREAM_MUSIC write. */
    private var upstreamVolumeApplyUntilMs = 0L
    /** STREAM_MUSIC step last applied from MA/HA upstream — used to ignore echo without blocking real keys. */
    private var lastUpstreamAppliedStep: Int = -1
    /** Last hardware step reported to MA — avoid duplicate client/state spam. */
    private var lastReportedHardwareStep: Int = -1
    @Volatile
    private var isDucked = false
    /** Transient duck while voice-reply restore eases; null uses [isDucked] 0.12 / 1. */
    private var duckLinearOverride: Float? = null
    private var reconnectAttempt = 0
    private var hasReceivedFirstMetadata = false
    private var cachedSendspinTitle: String? = null
    /**
     * Track generation whose 首/活尾/duration are currently loaded.
     *
     * Separate from [cachedSendspinTitle] because MA may pre-write that field for
     * instant identity paint ([applyKnownTrackIdentity]) before the Sendspin
     * metadata packet arrives. Using the painted title as the change sentinel let
     * MA disarm it, so the real packet was deduped and the previous track's
     * progress/duration survived into the new one. Only [mergeSendspinMetadata]
     * and same-track state restores may write this. Gap-fill of a blank title
     * (peer / MA) is a same-track restore — it must latch this or the next SP
     * packet is treated as a new track and seeds 0.
     */
    private var progressIdentityTitle: String? = null
    private var cachedSendspinArtist: String? = null
    private var cachedSendspinAlbum: String? = null
    /** Always absolute — see [resolvedArtworkUrl]. */
    private var cachedSendspinArtworkUrl: String? = null
    /** Last raw `artwork_url` we could not resolve; de-dupes the warning. */
    private var lastUnresolvedArtworkUrl: String? = null
    /**
     * Any part of the current track's identity came from a LAN peer rather than
     * our own upstream. Suppresses rebroadcast — see [peerMediaIdentitySnapshot].
     * Per-track, so a device only silenced by one borrowed cover starts offering
     * again as soon as its own upstream supplies the next track.
     *
     * Written from the UDP receive loop and read from the beacon loop.
     */
    @Volatile
    private var identityFilledFromPeer = false
    /**
     * Identity snapshot taken right before empty-queue force teardown. Some
     * servers emit [stream/end] on long pause; resuming the same track then
     * sends only stream/start + PCM (no metadata) — without this snapshot the
     * overlay could not rebuild until the next track. Consumed once by
     * [noteTransportAudible]; overwritten by any newer teardown; dropped on [close].
     */
    private var queueClearedIdentityTitle: String? = null
    private var queueClearedIdentityArtist: String? = null
    private var queueClearedIdentityAlbum: String? = null
    private var queueClearedIdentityArtworkUrl: String? = null
    /** True while a Sendspin artwork@v1 binary cover is current; blocks HTTP URL fetch. */
    @Volatile private var hasBinaryArtwork = false
    /**
     * elapsedRealtime of the last audible Sendspin PCM. Unlike
     * [isAudiblyPlayingNow] it survives output stop / stream end so short
     * protocol gaps still count as "this source was just making sound".
     * 0 = never, or explicitly cleared on confirmed queue end.
     */
    @Volatile private var lastAudibleElapsedRealtimeMs = 0L
    /**
     * Candidate paused-rollback awaiting new-MA confirmation (2.10.0b10+ /
     * #5129/#5131/#5421): server freezes real progress and re-reports it.
     * Same value (± [PROGRESS_PAUSE_ECHO_MS]) within
     * [PAUSED_ROLLBACK_CONFIRM_WINDOW_MS] → authoritative accept.
     * Older MA one-off dirty rollbacks never repeat → stay rejected.
     * Do **not** clear this on ordinary pause-ACK echoes at the frozen head —
     * that raced the confirm path and left seeks/freezes stuck.
     */
    private var pendingPausedRollbackMs: Long? = null
    private var pendingPausedRollbackAtElapsedMs = 0L
    /**
     * Title/queue identity just changed. Next [track_progress] is authoritative
     * for the new track (incl. near-0) — must not be rejected as old-MA pause
     * fake-zero. Track-change PCM align is pause→play (not hard-seat).
     */
    private var pendingTrackIdentityReseat = false
    /**
     * Track-change PCM align: local pause→play (with a short gap). Cooldown +
     * gates prevent fighting open-lead drip / same-track stream/clear.
     */
    private var progressReseatNudgeCooldownUntilElapsedMs = 0L
    private var progressReseatNudgeInFlight = false
    /** Last seen server track_progress for upstream-vs-upstream discontinuity. */
    private var lastSeenUpstreamProgressMs: Long? = null
    private var lastSeenUpstreamProgressAtElapsedMs = 0L
    /** Set by [noteAndDetectUpstreamProgressDiscontinuity] for the latest sample. */
    private var lastUpstreamDiscontinuityWasTrackBoundary = false
    /**
     * MA is skipping unplayable items. Hold vinyl identity/progress and do not
     * rescue-seek / pause→play — those retried play_index and froze the mapper.
     */
    private var upstreamSkipStormUntilElapsedMs = 0L
    /**
     * After title / track-jump clearHeld: suppress pause→play from jittered
     * sparse progress that looks like a hard scrub (MA auto-next main bug).
     * Covers open-lead + early sparse drip; post-grace false scrubs are gated
     * by seek-sized [scrubWithoutClear] (not a whole-track latch).
     */
    private var trackChangeNudgeGraceUntilElapsedMs = 0L
    /**
     * Title advanced while we had playback intent — consume once on the next
     * track-boundary progress (or fire immediately from title merge) via
     * pause→play. No protocol hard-seat on this path.
     */
    private var pendingTrackChangePausePlayNudge = false
    private var cachedControllerRepeatMode: String? = null
    private var cachedControllerShuffleEnabled: Boolean? = null
    /**
     * HA / Mass API queue-transport UI paint only — not Sendspin controller protocol.
     * Priority: protocol controller > bridged UI > metadata. Cleared when controller wins.
     */
    private var bridgedUiRepeatMode: String? = null
    private var bridgedUiShuffleEnabled: Boolean? = null
    /**
     * Mass API queue-clock UI progress bridge.
     *
     * Mutex (exclusive paint ownership — do not race):
     * - **active**: only [OverlayProgressWriter.MassApiBridge] / local scrub
     *   may call [pushOverlayProgress]. Sendspin PCM / ticker / track_progress
     *   keep internal 首/活尾 but must not paint vinyl.
     * - **inactive**: Sendspin alone owns the bar (pre-Mass behavior).
     * Never writes PCM / 首 / 活尾 — paint seat only.
     */
    private var bridgedUiProgressActive: Boolean = false
    private var bridgedUiProgressMs: Long? = null
    private var bridgedUiProgressAtElapsedRealtime: Long = 0L
    private var bridgedUiProgressPlaying: Boolean = false
    /**
     * PCM has been gone long enough that wall-clock interpolation is a lie.
     * Stops the MA bar walking after a dead source without flipping transport
     * on a Queue Flow flash. Cleared by audible PCM, or by a local seek that
     * is not on an already-confirmed dead stream.
     */
    private var bridgedSilenceHold: Boolean = false
    /**
     * Silence held past [DEAD_STREAM_CONFIRM_MS] (or skip-storm idle).
     * Blocks rescue play/seek so we do not hammer an unplayable queue.
     */
    private var deadStreamConfirmed: Boolean = false
    /** Local play tap: do not re-confirm dead until this elapses without PCM. */
    private var deadStreamRetryUntilElapsed: Long = 0L
    /**
     * Path A: one upstream [next] is in flight after PCM-silence confirm.
     * Blocks rescue/seek so we do not restart the dead item; does not paint pause.
     */
    private var deadStreamAdvancePending: Boolean = false
    /** Auto-nexts in this silent run. Reset on audible PCM or user play. */
    private var deadStreamAdvanceCount: Int = 0
    /** Title at the last auto-next — same title after the wait means next was a no-op. */
    private var deadStreamAdvanceTitle: String? = null
    /**
     * elapsedRealtime of the last LAN peer beacon whose streamKey equals our
     * current `play_at`. A peer only beacons while audibly playing, so a fresh
     * mark proves the stream is alive elsewhere — our silence is then a local
     * stall, and the dead-stream watchdog must not skip the track for the
     * whole group or demote transport. Written on the UDP receive path.
     */
    @Volatile
    private var lastPeerSameStreamBeaconElapsed: Long = 0L
    private var lastDeadStreamToastElapsed: Long = 0L
    private var bridgedUiDurationMs: Long? = null
    /**
     * The bridge dropped its duration seat for a new track and no trustworthy
     * total has arrived yet. MA's item change lands before the Sendspin packet
     * that refreshes [cachedDurationMs], so during this window that field still
     * holds the *previous* track's duration — falling back to it paints the old
     * total and clamps the new position to the old end. Cleared as soon as MA
     * reports a duration or the Sendspin track-change reset runs.
     */
    private var bridgedDurationSeatDropped: Boolean = false

    /** Who is allowed to paint overlay progress while the MA bridge is up. */
    private enum class OverlayProgressWriter {
        /** Sendspin PCM / ticker / metadata — blocked when bridge active. */
        Sendspin,
        /** Mass API queue clock — sole paint owner when bridge active. */
        MassApiBridge,
        /** User scrub / local seek pin — allowed even under MA ownership. */
        LocalSeekUi,
        /**
         * Paired UDP progress (pause freeze / seek absolute seat only).
         * Live DAC differentials must not paint under the MA bridge.
         */
        PeerSync,
    }
    private var cachedMetadataRepeatMode: String? = null
    private var cachedMetadataShuffleEnabled: Boolean? = null
    @Volatile
    private var cachedProgressMs: Long? = null
    /** Last non-glitch overlay playhead; pause metadata often reports 0. */
    @Volatile
    private var lastGoodOverlayProgressMs: Long = 0L
    /**
     * Self-maintained UI progress (HA-style 首尾):
     * - 首 [progressHead*]: trusted upstream / seek anchor; local clock runs from here
     * - 活尾 [audibleTailMs]: last written chunk mapped to track ms — UI must not run ahead
     * - 硬尾 [cachedDurationMs]: track duration clamp
     * Fake-zero / stop flashes never become a new 首.
     */
    @Volatile
    private var progressHeadMs: Long? = null
    @Volatile
    private var progressHeadElapsedRealtime: Long = 0L
    /** Live audible frontier; null until first written chunk after arm. */
    @Volatile
    private var audibleTailMs: Long? = null
    /**
     * After track change / stream/clear: wall-clock stays frozen until PCM has
     * an absolute seat (metadata calibrate or cold-start 0). Continuous play
     * advances from audible writes — not from metadata drip.
     */
    @Volatile
    private var progressWaitForAudible: Boolean = false
    /**
     * Last soft-seeded upstream [track_progress] for this seat. Used once at
     * first audible birth to catch a cold-join double-count; never clamped
     * against continuous play (that broke solo bars after ~6s).
     */
    private var trustedUpstreamProgressSeedMs: Long? = null
    /** When this device attached to the current stream (first audible / title seat). */
    private var lastStreamAttachElapsed: Long = 0L
    /**
     * Differential peer playhead paint. Fresh samples override the local bar so
     * paired devices stay visually locked; the audible mapper is only hard-seated
     * when skew is large (see [applyPeerProgressSample]).
     */
    @Volatile
    private var peerPaintProgressMs: Long? = null
    @Volatile
    private var peerPaintAtElapsedRealtime: Long = 0L
    @Volatile
    private var peerPaintPlaying: Boolean = false
    /** Last peer paint came from a STEADY peer (follower may lock to it). */
    @Volatile
    private var peerPaintFromSteady: Boolean = false
    /**
     * Last peer paint was an absolute seat, not a differential sample. A seat
     * must be painted verbatim until the next packet: the scrubbing device has
     * its own bar pinned flat on that ms, so extrapolating here would creep the
     * follower ahead of the very device it is following.
     */
    @Volatile
    private var peerPaintAbsoluteSeat: Boolean = false
    /**
     * The paint lock came from a partner, not from our own pause. Only a remote
     * freeze may veto [thawProgressAfterAudibleResume] — vetoing on our own
     * freeze would keep the mapper frozen for the whole
     * [PEER_PROGRESS_PAUSED_HOLD_MS] after any resume that arrives as PCM
     * rather than as a transport command.
     */
    @Volatile
    private var peerPaintFromRemote: Boolean = false
    /**
     * Peer playhead as of [peerRawAtElapsedRealtime], recorded for **every**
     * same-stream sample including ones we do not follow. Quantisation input
     * only — see [snapBarSecondToPeer]. Kept separate from the paint lock
     * because the bar can disagree by a whole second while the two playheads
     * are tens of ms apart, which is far below any follow/calibrate threshold.
     */
    @Volatile
    private var peerRawProgressMs: Long? = null
    @Volatile
    private var peerRawAtElapsedRealtime: Long = 0L
    @Volatile
    private var peerRawAdvancing: Boolean = false
    @Volatile
    private var peerRawSpeed: Int = 1000
    @Volatile
    private var peerRawOutranksLocal: Boolean = false
    /**
     * The author of the armed seat we adopted has been *heard* past it: its
     * live steady samples landed inside the seat's acceptance window. From
     * that moment the seat is proven — the music started — and our bar may
     * walk the author's timeline instead of freezing until our own slower
     * pipeline (AudioTrack rebuild, decoder fallback) delivers local PCM.
     */
    @Volatile
    private var peerSeatFulfilledAtElapsed: Long = 0L
    /**
     * Last same-stream playhead from any peer. Progress packets keep flowing
     * while paused (FLAG_PAUSED at beacon rate), so a fresh mark means "we are
     * paired on this stream right now" — used to keep device-local DAC residue
     * out of shared decisions. Solo playback never sets it.
     */
    @Volatile
    private var lastPeerProgressSeenElapsed: Long = 0L
    @Volatile
    private var lastPeerProgressCalibrateElapsed: Long = 0L
    /**
     * Last non-zero Sendspin `play_at`. `stream/end` (MA seek) clears the live
     * key; UDP seats must still publish and land, so we remember the last one.
     */
    @Volatile
    private var lastKnownStreamKey: Long = 0L
    /**
     * After local/protocol play: ignore lagging peer FLAG_PAUSED so a still-paused
     * partner cannot re-freeze our bar mid-resume.
     */
    private var playResumeGraceUntilElapsed: Long = 0L
    /**
     * Lamport clock over deliberate playhead changes (seek, pause, resume,
     * track seat). Bumped past the highest epoch we have ever seen, so devices
     * that change the playhead concurrently still converge on one counter.
     *
     * This is what makes the broadcast a *notification* rather than a hope: a
     * peer that missed the change sees a higher epoch on any later packet and
     * adopts, and a peer that sends us a lower one gets answered instead of
     * obeyed. [playheadEpochOrigin] breaks ties between concurrent bumps;
     * empty means this device authored the current epoch.
     */
    @Volatile
    private var playheadEpoch: Long = 0L
    @Volatile
    private var playheadEpochOrigin: String = ""
    /** Highest epoch seen from anyone, so a local bump always supersedes it. */
    @Volatile
    private var highestSeenPlayheadEpoch: Long = 0L
    /** Guards epoch read-modify-write: concurrent bump/note could mint duplicate
     * or regressing epochs when the UDP, audio, and main threads race. */
    private val playheadEpochLock = Any()
    /** Anti-entropy rate limit: answering a stale peer must not become a storm. */
    private var lastStaleEpochAnswerElapsed: Long = 0L
    /**
     * Last time we adopted a peer's deliberate seat. MA's seek ACK arriving a
     * beat later must not mint a competing epoch — that is what made the
     * master ignore a tap that happened on the passive.
     */
    private var lastAdoptedPeerTimelineElapsed: Long = 0L

    /**
     * The assertion authored for [playheadEpoch], held **immutable** for the
     * life of that epoch: position [playheadAnchorMs] at
     * [playheadAnchorServerTsUs] advancing at [playheadAnchorSpeed].
     *
     * Immutability is what lets the anchor be rebroadcast indefinitely. Every
     * repeat is byte-identical, so adopting it twice is the same as adopting it
     * once and two devices cannot talk each other into drifting. Re-deriving
     * the position at each broadcast instead would restart a held seek target
     * from its own anchor every tick.
     *
     * Dormant, not consulted, while our own DAC owns the playhead: a live
     * differential is strictly better than replaying a seat. It becomes
     * relevant again the moment PCM stops describing the position, which is
     * exactly when the seat is the best statement we have.
     */
    @Volatile
    private var playheadAnchorMs: Long? = null
    @Volatile
    private var playheadAnchorServerTsUs: Long = 0L
    @Volatile
    private var playheadAnchorSpeed: Int = 0
    @Volatile
    private var playheadAnchorArmed: Boolean = false
    /** When the current assertion armed (elapsedRealtime); bounds its pin defense. */
    @Volatile
    private var playheadAnchorArmedAtElapsed: Long = 0L
    /**
     * The standing assertion still needs a controller-capable device to run
     * the transport (see [SendspinPeerProgressSample.FLAG_TRANSPORT_PENDING]).
     * Set on a local seek/resume whose own controller command cannot be
     * honored; carried on adoption so a chain can forward the request; cleared
     * once any device delivers the command or the seat retires.
     */
    @Volatile
    private var playheadAnchorTransportPending: Boolean = false
    /**
     * Position and start of the standing armed *intent*, kept across the brief
     * disarm a pause introduces so restating one position cannot keep
     * restarting [playheadAnchorArmedAtElapsed]. Cleared when the assertion is
     * genuinely retired (new track, abandoned seat).
     */
    @Volatile
    private var lastArmedIntentMs: Long? = null
    @Volatile
    private var lastArmedIntentAtElapsed: Long = 0L
    /**
     * Audible PCM is being rejected because it disagrees with the standing
     * seat. Tracked so an unreachable seat is eventually abandoned instead of
     * rejecting the real timeline forever — see [seatStarvationSpent].
     */
    @Volatile
    private var seatStarvationSinceElapsed: Long = 0L
    @Volatile
    private var seatStarvationDrops: Int = 0
    /** The seat the open starvation budget was charged against. */
    @Volatile
    private var seatStarvationSeatMs: Long? = null
    @Volatile
    private var seatStarvationLastDropElapsed: Long = 0L
    /** Last peer absolute seat we acted on — repeats only refresh the paint lock. */
    @Volatile
    private var lastAdoptedPeerAbsoluteMs: Long? = null
    @Volatile
    private var lastAdoptedPeerAbsoluteElapsed: Long = 0L
    /**
     * Armed by [stream/clear] (protocol seek / track jump). Next trusted
     * [track_progress] reseats the absolute mapper; until then UI holds last
     * good ms and does not invent a new absolute from stale head + new PCM.
     */
    @Volatile
    private var awaitingSeekReseat: Boolean = false
    /**
     * Track-jump / seek seam window (bounded, [TRACK_JUMP_SEAM_MS]). Inside it,
     * upstream pause-side signals (speed=0, brief paused/stopped) are *deferred*
     * — recorded in [seamPendingPause], never dropped — because they are almost
     * always the gap between the old and the new track, not a user pause.
     * A playing-side signal cancels the pending pause; [seamReconcileJob] applies
     * a still-standing pause when the window expires, so state always converges.
     */
    private var seamUntilElapsedRealtime: Long = 0L
    private var seamPendingPause: Boolean = false
    private var seamReconcileJob: Job? = null
    /**
     * Ghost-pause rescue window, armed on every track change while playing
     * (local next/previous, Mass Up Next, natural title change). Inside it a
     * deferred pause that reconciles quiet with playing intent is presumed to
     * be the jump gap's stray pause that stranded MA on paused (dead stream),
     * not a user pause — [reconcileTrackJumpSeam] answers with a single wire
     * `play` instead of landing it. Local pause/stop/play and queue end all
     * pass through [clearTrackJumpSeam], which disarms the window.
     */
    private var trackChangeRescueUntilElapsedRealtime: Long = 0L
    /** Rescue shots (reseat seeks) spent inside the current window. */
    private var trackChangeRescueShotsSent: Int = 0
    /**
     * Start of the current audible PCM run inside the rescue window (0 = none).
     * Latched by [handleAudibleProgress] — the only track-attributed audible
     * path — and re-latched after a [TRACK_CHANGE_RESCUE_RUN_GAP_MS] silence so
     * a legacy-MA old-track tail cannot masquerade as the new track.
     */
    private var trackChangeRescueAudibleStartElapsed: Long = 0L
    /**
     * After PCM reseat pause→play: keep "still playing" intent until audible
     * (or hold expiry). Prevents our own reseat pause ACK from sticking as a
     * real user pause (换歌后必须再点播放).
     */
    private var reseatPlayingHoldUntilElapsed: Long = 0L
    /**
     * Last play/pause glyph actually painted to [VinylCoverService].
     * The overlay transport is written ONLY through [paintOverlayPlaying]; every
     * upstream delta is still absorbed into [_isPlaying] immediately (no state is
     * ever dropped) — only the paint is coalesced, so seams cannot get stuck.
     */
    private var lastPushedOverlayPlaying: Boolean? = null
    /**
     * Pending settle paint. Upstream flip-flops (skip-seam speed=0, post-pause
     * playing echoes) settle for [PLAY_STATE_SETTLE_MS] and then paint whatever
     * [_isPlaying] says at that moment (latest wins). Local commands bypass it.
     */
    private var overlayPaintJob: Job? = null
    /**
     * Local overlay scrub target. MA often ACKs seek with track_progress ~1s ahead;
     * hold the tapped position briefly so the timestamp does not jump +1s.
     */
    @Volatile
    private var pinnedLocalSeekMs: Long? = null
    @Volatile
    private var pinnedLocalSeekUntilElapsed: Long = 0L
    private var lastProgressResyncElapsedRealtime: Long = 0L
    private var progressFollowUntilElapsedRealtime: Long = 0L
    @Volatile
    private var cachedDurationMs: Long? = null
    @Volatile
    private var cachedMetadataTimestampUs: Long? = null
    @Volatile
    private var cachedMetadataReceivedAtMs: Long = 0L
    @Volatile
    private var cachedPlaybackSpeed: Int = 1000
    private var progressOverlayJob: Job? = null

    private var lastStatsUpdateMs: Long = 0L
    private val statsUpdateThrottleMs: Long
        get() = if (isLowMemoryMode()) 250L else 220L
    private var cachedStats: Map<String, Any?>? = null

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun setVolumeCallback(callback: (Float) -> Unit) {
        externalVolumeCallback = callback
    }

    fun setMuteCallback(callback: (Boolean) -> Unit) {
        externalMuteCallback = callback
    }

    fun setPlayingCallback(callback: (Boolean) -> Unit) {
        externalPlayingCallback = callback
    }

    fun setVinylCoverEnabledCallback(callback: () -> Boolean) {
        vinylCoverEnabledCallback = callback
    }

    fun setLowMemoryModeCallback(callback: () -> Boolean) {
        lowMemoryModeCallback = callback
    }

    fun setPreferredFormatCallback(callback: () -> String) {
        preferredFormatCallback = callback
    }

    fun setDeviceNameCallback(callback: () -> String) {
        deviceNameCallback = callback
    }

    fun setSyncOffsetMsCallback(callback: () -> Int) {
        syncOffsetMsCallback = callback
        cachedSyncOffsetMs = callback().coerceIn(-1000, 1000)
    }

    fun setPairedDeviceCallbacks(
        savePairedDevice: (String, String) -> Unit,
        getPairedDevices: () -> Map<String, String>
    ) {
        savePairedDeviceCallback = savePairedDevice
        getPairedDevicesCallback = getPairedDevices
    }

    /**
     * Wire up persistence of the "last played server" the Sendspin spec requires
     * for multi-server arbitration. [save] is called every time a server we are
     * currently connected to transitions to playback_state == "playing".
     */
    fun setLastPlayedServerIdCallbacks(
        save: (String) -> Unit,
        get: () -> String
    ) {
        savePlayedServerIdCallback = save
        getLastPlayedServerIdCallback = get
    }

    fun setVolumePersistenceCallbacks(
        getVolume: () -> Int,
        setVolume: (Int) -> Unit,
        getMuted: () -> Boolean,
        setMuted: (Boolean) -> Unit
    ) {
        getVolumeCallback = getVolume
        setVolumeCallback = setVolume
        getMutedCallback = getMuted
        setMutedCallback = setMuted
        refreshLocalPlayerVolumeState()
    }

    /** Called before programmatic STREAM_MUSIC changes to avoid device→HA echo loops. */
    fun setSuppressDeviceVolumeObserverCallback(callback: (Int) -> Unit) {
        suppressDeviceVolumeObserver = callback
    }

    /**
     * Latch voice/TTS overlay attenuation. [holdLinear] keeps PCM at that gain
     * until [setDuckLinearOverride] rides the envelope; null snaps to
     * [VOICE_OVERLAY_DUCK_LINEAR]. Already-ducked + null is a no-op so an
     * in-flight fade is not reset by a second hook.
     */
    fun duck(holdLinear: Float? = null) {
        if (!_isActive.value) return
        val already = isDucked
        if (already && holdLinear == null) return
        isDucked = true
        duckLinearOverride = holdLinear?.coerceIn(0f, 1f)
        // While voice/TTS overlays, keep writing ducked PCM but do not re-steal
        // AudioFocus from URL TTS (ensureHeld → GAIN would cancel the reply).
        if (!already) client?.setVoiceOverlayDucked(true)
        applyOutputRouting()
    }

    fun unDuck() {
        duckLinearOverride = null
        if (!isDucked) return
        isDucked = false
        client?.setVoiceOverlayDucked(false)
        applyOutputRouting()
    }

    fun isVoiceOverlayDucked(): Boolean = isDucked

    fun currentDuckLinear(): Float =
        duckLinearOverride ?: if (isDucked) VOICE_OVERLAY_DUCK_LINEAR else 1f

    /** Ride the voice-reply restore envelope. Null returns to [isDucked] 0.12 / 1. */
    fun setDuckLinearOverride(linear: Float?) {
        duckLinearOverride = linear?.coerceIn(0f, 1f)
        applyOutputRouting()
    }

    fun onTrimMemory(level: Int) {
        val activeClient = client ?: return
        @Suppress("DEPRECATION")
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.e(tag, "CRITICAL memory pressure: trimming Sendspin jitter buffer")
                activeClient.trimAudioBufferCritical()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.w(tag, "MODERATE memory pressure: trimming Sendspin jitter buffer")
                activeClient.trimAudioBufferModerate()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Log.w(tag, "LOW memory pressure: trimming Sendspin jitter buffer")
                activeClient.trimAudioBufferLow()
            }
        }
    }

    fun onLowMemory() {
        Log.e(tag, "System onLowMemory: trimming Sendspin jitter buffer")
        client?.trimAudioBufferCritical()
    }

    fun enable(serverUrl: String? = null) {
        if (_enabled.value) return
        _enabled.value = true
        inboundMode = serverUrl.isNullOrBlank()
        reconnectAttempt = 0
        refreshLocalPlayerVolumeState(persistDeviceVolume = true)

        if (!serverUrl.isNullOrBlank()) {
            connectToServer(serverUrl)
            return
        }

        startInboundServer()
    }

    /**
     * Overlay play/pause with `enabled=true` but `client==null` means the inbound
     * socket died (usually a bind race). Re-listen on this manager — do **not**
     * recreate the session, which is what produces Address already in use.
     */
    fun ensureInboundListening() {
        if (!_enabled.value || !inboundMode) return
        if (client != null) return
        startInboundServer()
    }

    /**
     * @param preserveOverlayForRebind soft restart / pressure recovery: leave the
     * painted vinyl shell up and arm a one-shot rebind when the next pipeline
     * becomes live. Do **not** arm pause-idle tuck.
     */
    fun disable(preserveOverlayForRebind: Boolean = false) {
        if (!_enabled.value) {
            if (!preserveOverlayForRebind) {
                VinylCoverService.clearAwaitingPipelineRebind()
            }
            return
        }
        _enabled.value = false
        inboundMode = false
        reconnectAttempt = 0
        cancelInboundFallback()
        reconnectJob?.cancel()
        reconnectJob = null
        clientStateJob?.cancel()
        clientStateJob = null
        stopInboundServer()
        client?.cleanupResources()
        client = null
        discovery.stopDiscovery()
        discovery.stopAdvertising()
        _serverUrl.value = null
        _connectedServer.value = null
        _serverRunning.value = false
        _isActive.value = false
        // Soft rebind only when MA Media Controls still want the window — never
        // arm ignore-path birth after the user powered the overlay off.
        val mayRebindOverlay = preserveOverlayForRebind && isSendspinVinylUiEnabled()
        if (_isPlaying.value) {
            _isPlaying.value = false
            externalPlayingCallback?.invoke(false)
            if (mayRebindOverlay) {
                // Keep glyph/identity; new manager will rebind without a tuck flash.
                VinylCoverService.markAwaitingPipelineRebind()
            } else {
                pushOverlayPlayingState(false, immediate = true)
                if (!preserveOverlayForRebind || !isSendspinVinylUiEnabled()) {
                    // Controls off / hard stop: tear paint; do not leave a zombie shell.
                    if (VinylCoverService.isLiveOverlayShellVisible() ||
                        MediaOverlayMemoryCache.get().hasDisplayableContent()
                    ) {
                        VinylCoverService.hide(context, force = true)
                    } else {
                        scheduleHideVinyl()
                    }
                } else {
                    scheduleHideVinyl()
                }
                VinylCoverService.clearAwaitingPipelineRebind()
            }
        } else if (mayRebindOverlay &&
            (VinylCoverService.isLiveOverlayShellVisible() ||
                MediaOverlayMemoryCache.get().hasDisplayableContent())
        ) {
            VinylCoverService.markAwaitingPipelineRebind()
        } else {
            VinylCoverService.clearAwaitingPipelineRebind()
            if (!isSendspinVinylUiEnabled() &&
                (VinylCoverService.isLiveOverlayShellVisible() ||
                    MediaOverlayMemoryCache.get().hasDisplayableContent())
            ) {
                VinylCoverService.hide(context, force = true)
            }
        }
        cachedStats = null
    }

    fun connectToServer(url: String) {
        cancelInboundFallback()
        inboundMode = false
        stopInboundServer()
        discovery.stopDiscovery()
        discovery.stopAdvertising()
        _serverRunning.value = false
        val normalized = normalizeServerUrl(url)
        if (normalized.isBlank()) return
        _serverUrl.value = normalized
        connectInternal(normalized, _connectedServer.value)
    }

    fun startAdvertising(port: Int = 8928, name: String = "") {
        val effectiveName = name.ifEmpty { resolveClientName() }
        if (!_enabled.value) {
            _enabled.value = true
        }
        inboundMode = true
        startInboundServer(port, effectiveName)
    }

    fun stopAdvertising() {
        discovery.stopAdvertising()
    }

    /**
     * Upstream volume (HA media_player command / local UI), 0–1.
     * Writes STREAM_MUSIC once; never used for hardware-key echoes.
     */
    fun updateVolume(volume: Float) {
        val intVolume = volume.coerceIn(0f, 1f).let { (it * 100f).toInt().coerceIn(0, 100) }
        applyPlayerVolumeFromUpstream(intVolume, persist = true, syncClient = true)
    }

    fun updateMuted(muted: Boolean) {
        currentMuted = muted
        setMutedCallback?.invoke(muted)
        client?.updateMuted(muted)
        setDeviceMuted(muted)
        applyOutputRouting()
        externalMuteCallback?.invoke(muted)
        cachedStats = null
    }

    fun stopPlayback() {
        client?.stopPlayback()
        _isActive.value = false
        if (_isPlaying.value) {
            _isPlaying.value = false
            externalPlayingCallback?.invoke(false)
            pushOverlayPlayingState(false, immediate = true)
            scheduleHideVinyl()
        }
        cachedStats = null
    }

    fun updateSyncOffset(offsetMs: Int) {
        val normalized = offsetMs.coerceIn(-1000, 1000)
        cachedSyncOffsetMs = normalized
        client?.setSyncOffsetMs(normalized, userAdjusted = normalized != 0)
        cachedStats = null
    }

    /** LAN peer playback beacon (differential multi-room alignment). */
    fun peerPlaybackBeaconSnapshot(): SendspinPeerBeacon? = client?.buildPeerBeaconSnapshot()

    /**
     * The stream we are attached to, independent of whether audio is leaving the
     * speaker right now.
     *
     * [peerPlaybackBeaconSnapshot] deliberately goes null across a track change —
     * it advertises a DAC reading, and there is no honest one while the previous
     * tail drains or before the new `play_at` becomes audible. Identity mirroring
     * has no such constraint: a track change is exactly when a peer needs the new
     * cover/artist, so it matches on stream attachment instead. Still the same
     * "same `play_at` = same audio" rule, so an unrelated group cannot reach us.
     */
    fun peerStreamKeySnapshot(): Long? =
        client?.currentStreamKey() ?: lastKnownStreamKey.takeIf { it != 0L }

    /** Live `play_at`, else the last one before stream/end cleared it. */
    private fun resolvedStreamKey(): Long {
        val live = client?.currentStreamKey()
        if (live != null && live != 0L) {
            lastKnownStreamKey = live
            return live
        }
        return lastKnownStreamKey
    }

    fun onPeerPlaybackBeacon(peerId: String, audibleServerTsUs: Long, flags: Int, streamKey: Long) {
        val live = client
        if (live != null && streamKey == live.currentStreamKey()) {
            lastPeerSameStreamBeaconElapsed = SystemClock.elapsedRealtime()
        }
        live?.onPeerPlaybackBeacon(peerId, audibleServerTsUs, flags, streamKey)
    }

    fun sendMediaCommand(
        command: String,
        positionMs: Long? = null,
        offsetMs: Long? = null,
        volume: Int? = null,
        mute: Boolean? = null
    ): Boolean {
        return sendMediaCommandReporting(command, positionMs, offsetMs, volume, mute).handled
    }

    fun sendMediaCommandReporting(
        command: String,
        positionMs: Long? = null,
        offsetMs: Long? = null,
        volume: Int? = null,
        mute: Boolean? = null
    ): MediaCommandOutcome {
        val send = { dispatchMediaCommand(command, positionMs, offsetMs, volume, mute) }
        if (command == "seek" || command == "seek_relative") {
            return SeekCommandDispatch.run(deadStreamConfirmed, deadStreamAdvancePending, onIgnored = {
                if (deadStreamConfirmed) refuseDeadStreamSeek()
                else Log.i(tag, "dead-stream next in flight: ignore seek")
            }, send = send)
        }
        return if (send()) MediaCommandOutcome.ACCEPTED else MediaCommandOutcome.REJECTED
    }

    private fun dispatchMediaCommand(
        command: String,
        positionMs: Long? = null,
        offsetMs: Long? = null,
        volume: Int? = null,
        mute: Boolean? = null
    ): Boolean {
        val live = client
        if (live == null) {
            // Soft restart / pressure recovery can leave the vinyl shell up while
            // this manager has no live client — surface the miss so UI can rebind.
            Log.w(tag, "sendMediaCommand($command) dropped: no client (enabled=${_enabled.value})")
            if (_enabled.value && inboundMode) {
                ensureInboundListening()
            }
            return false
        }
        val sent = live.sendMediaCommand(
            command = command,
            positionMs = positionMs,
            offsetMs = offsetMs,
            volume = volume,
            mute = mute
        ) == true
        if (command == "pause" || command == "stop") {
            // Stop the speaker even if the wire command missed — do not skip the
            // pause/stop UI branches below.
            live.pauseAudioOutput()
        }
        // Will the server actually honor what we sent? A session whose
        // controller role was revoked still *sends* fine — the server drops the
        // command on arrival, our bar moves, and the partner's PCM then drags it
        // back (the follower's "seek never sticks"). A paired partner with a
        // working controller session can run the transport for us: seat locally,
        // flag the seat transport-pending, and let [applyPeerTransport] on the
        // partner issue the real command. Without a partner nobody can execute
        // it, so keep the old refusal.
        val honored = sent && live.supportsControllerCommand(command)
        val transportViaPeer = !honored && pairedOnThisStream() &&
            (command == "seek" || command == "seek_relative" || command == "play")
        if (!sent && command != "pause" && command != "stop" && !transportViaPeer) return false
        if (transportViaPeer) {
            Log.i(tag, "controller $command not honored here: asking paired partner to execute")
        }
        when (command) {
            "play" -> {
                _isActive.value = true
                cancelHideJob()
                VinylCoverService.cancelPauseIdleTeardown(context)
                VinylCoverService.endQueueClearedGrace(context)
                // User asked to retry a dead source. Keep the bar frozen until
                // PCM; give the watchdog a fresh confirm window so we do not
                // immediately re-demote this tap.
                deadStreamConfirmed = false
                deadStreamAdvancePending = false
                deadStreamAdvanceCount = 0
                deadStreamAdvanceTitle = null
                deadStreamRetryUntilElapsed = SystemClock.elapsedRealtime() + DEAD_STREAM_CONFIRM_MS
                // Explicit user resume — a deferred seam pause must not fire later.
                clearTrackJumpSeam()
                // Drop paused peer-paint hold so audible resume can thaw the mapper.
                clearPeerProgressLock()
                playResumeGraceUntilElapsed =
                    SystemClock.elapsedRealtime() + PEER_PROGRESS_PLAY_RESUME_GRACE_MS
                // Seat frozen head only — wall clock starts on first audible PCM
                // (AudioTrack buffer / stream often lags play by 1–2s).
                val resumeSeed = pickResumeSeed(client?.upstreamTrackProgressMs())
                reanchorProgressClock(resumeSeed)
                // Claim the resume as a new timeline. A partner still announcing
                // pause has no other way to learn where we restarted before its
                // own transport catches up, and the armed seat keeps both bars
                // on the same frozen ms until each one's PCM arrives.
                bumpPlayheadEpoch(
                    "local resume",
                    resumeSeed,
                    armed = true,
                    transportPending = transportViaPeer,
                )
                if (bridgedUiProgressActive) {
                    bridgedUiProgressMs =
                        clampToDuration(alignSeekPositionForUpstream(resumeSeed))
                    bridgedUiProgressAtElapsedRealtime = SystemClock.elapsedRealtime()
                    bridgedUiProgressPlaying = false
                }
                _isPlaying.value = true
                val nowElapsed = SystemClock.elapsedRealtime()
                progressFollowUntilElapsedRealtime = nowElapsed + PROGRESS_FOLLOW_WINDOW_MS
                lastProgressResyncElapsedRealtime = nowElapsed
                cachedPlaybackSpeed = 1000
                // Local intent paints immediately — no settle for the user's own tap.
                pushOverlayPlayingState(true, immediate = true)
                // Ticker may run for paints, but display stays frozen until audible.
                reconcileProgressOverlayTicker(true)
                pushOverlayProgress(displayProgressMs(), cachedDurationMs)
                pokePeerProgressMirror()
            }
            "pause" -> {
                // Spec: pause at current position — freeze local playhead, never roll back.
                clearReseatPlayingHold()
                clearTrackJumpSeam()
                freezeProgressAtCurrent(claimEpoch = true)
                _isPlaying.value = false
                pushOverlayPlayingState(false, immediate = true)
                // Pause-idle teardown (shared with HA); empty-queue uses its own timeline.
                retainOverlayAfterPause()
            }
            "stop" -> {
                // MA/Sendspin often maps "stop" with track_progress=0 even when the
                // user only paused. Keep the same-track playhead; only next/previous
                // (or a real title change) clears it.
                clearReseatPlayingHold()
                clearTrackJumpSeam()
                freezeProgressAtCurrent(claimEpoch = true)
                _isPlaying.value = false
                pushOverlayPlayingState(false, immediate = true)
                retainOverlayAfterPause()
            }
            "next", "previous" -> {
                // In-session skip: transport glyph does not change here. Open the
                // seam window now (stream/clear may lag the command) so the gap's
                // pause-side flashes are deferred, then reconciled.
                clearHeldProgressForTrackChange()
                // Claim 0 now. Otherwise leftover PCM / a partner still on the
                // previous track reseats the new song at ~30s (cold-join 2×
                // and the paired UDP interpolator we added for seeks).
                reanchorProgressClock(0L)
                bumpPlayheadEpoch("local skip", 0L, armed = true)
                if (_isPlaying.value) {
                    armTrackJumpSeam()
                    armTrackChangeRescueWindow("local-skip")
                }
            }
            "seek" -> {
                positionMs?.let {
                    // MA floors seek to whole seconds — pin/UI must match that grid.
                    val target = clampToDuration(alignSeekPositionForUpstream(it))
                    pinLocalSeekTarget(target)
                    // Next metadata.progress is the authority (sparse push on
                    // seek/play/pause — not a continuous drip). Arm hard-seat.
                    awaitingSeekReseat = true
                    reanchorProgressClock(target)
                    startPlayingSeekWalkClock()
                    // Finger/absolute scrub only: seat SP audible mapper on the
                    // tapped second so lyrics/PCM follow the scrub without waiting
                    // for the next write. Does not touch play/pause commands.
                    calibrateAudibleFromTrusted(target, metadataTimestampUs = null)
                    // Seat peer-paint + MA bridge to the scrub so local UI and
                    // LAN peers share one absolute playhead immediately.
                    seatLocalAbsoluteProgress(
                        target,
                        playing = _isPlaying.value,
                        transportPending = transportViaPeer,
                    )
                    pushOverlayProgress(
                        target,
                        cachedDurationMs,
                        OverlayProgressWriter.LocalSeekUi,
                    )
                    if (_isPlaying.value) reconcileProgressOverlayTicker(true)
                    pokePeerProgressMirror()
                }
            }
            "seek_relative" -> {
                offsetMs?.let { delta ->
                    val base = if (bridgedUiProgressActive) {
                        overlayBarProgressMs()
                    } else {
                        displayProgressMs()
                    }
                        ?: cachedProgressMs
                        ?: lastGoodOverlayProgressMs
                    val next = clampToDuration(alignSeekPositionForUpstream(base + delta))
                    pinLocalSeekTarget(next)
                    awaitingSeekReseat = true
                    reanchorProgressClock(next)
                    startPlayingSeekWalkClock()
                    calibrateAudibleFromTrusted(next, metadataTimestampUs = null)
                    seatLocalAbsoluteProgress(
                        next,
                        playing = _isPlaying.value,
                        transportPending = transportViaPeer,
                    )
                    pushOverlayProgress(
                        next,
                        cachedDurationMs,
                        OverlayProgressWriter.LocalSeekUi,
                    )
                    if (_isPlaying.value) reconcileProgressOverlayTicker(true)
                    pokePeerProgressMirror()
                }
            }
            "volume" -> {
                volume?.let { applyGroupVolumeFromServer(it, syncDeviceOutput = true) }
            }
            "mute" -> {
                mute?.let { applyGroupMuteFromServer(it, syncDeviceOutput = true) }
            }
        }
        return true
    }

    fun seekTo(positionMs: Long): Boolean = seekToReporting(positionMs).handled

    fun seekToReporting(positionMs: Long): MediaCommandOutcome {
        if (deadStreamConfirmed || deadStreamAdvancePending) {
            return sendMediaCommandReporting("seek", positionMs = positionMs)
        }
        val maxMs = (
            client?.seekMaxMs()
                ?: cachedDurationMs
                ?: positionMs
            ).coerceAtLeast(0L)
        // Match MA `position_ms // 1000` so local pin and ACK land on the same second.
        val target = alignSeekPositionForUpstream(positionMs.coerceIn(0L, maxMs))
        // No usable controller on this session (role revoked / seek not in
        // supported_commands): alone that is a dead end, but a paired partner
        // can execute the seek for us — let sendMediaCommand seat + flag it.
        if (client?.supportsAbsoluteSeek() == false && !pairedOnThisStream()) return MediaCommandOutcome.REJECTED
        return sendMediaCommandReporting("seek", positionMs = target)
    }

    fun seekRelative(offsetMs: Long): Boolean = seekRelativeReporting(offsetMs).handled

    fun seekRelativeReporting(offsetMs: Long): MediaCommandOutcome =
        sendMediaCommandReporting("seek_relative", offsetMs = offsetMs)

    /** Read-only now-playing snapshot for host tools; null when there is no live client. */
    fun uiStateSnapshot(): SendspinUiState? = client?.uiState?.value

    /**
     * Snap absolute progress to the SP/MA **1s hard seat**.
     *
     * **Floor**, same as MA `position_ms // 1000`. Nearest-second on each device
     * independently is what painted 1s apart; stacking that on MA's floor and a
     * ~1–2s buffer ACK is the 1–3s gap. Wire, pin, overlay and peer seats all
     * use this one grid so nobody fights the server.
     *
     * Auto-next / title soft seed must **not** round open-lead up onto 1s —
     * see [alignOpenLeadProgressForUpstream].
     */
    private fun alignSeekPositionForUpstream(positionMs: Long): Long {
        val clamped = positionMs.coerceAtLeast(0L)
        return (clamped / MA_SEEK_SECOND_MS) * MA_SEEK_SECOND_MS
    }

    /**
     * Soft paint for track change / auto-next only.
     * Below 1s stay at 0 (never round up onto the first second). From 1s on,
     * same 1s floor as [alignSeekPositionForUpstream].
     * Still **no** protocol hard-seat on this path.
     */
    private fun alignOpenLeadProgressForUpstream(positionMs: Long): Long {
        val clamped = positionMs.coerceAtLeast(0L)
        if (clamped < MA_SEEK_SECOND_MS) return 0L
        return alignSeekPositionForUpstream(clamped)
    }

    /** Absolute scrub on the overlay progress bar (Sendspin/MA). */
    fun supportsAbsoluteSeek(): Boolean = client?.supportsAbsoluteSeek() != false

    /**
     * Would [sendMediaCommand] reach the server? Lets the overlay skip an
     * optimistic repeat/shuffle paint the server will ignore, instead of
     * leaving the glyph showing a state nothing ever entered.
     *
     * No client (soft restart / rebind) counts as supported so the caller still
     * goes through [sendMediaCommand] and gets its pipeline-recovery path.
     */
    fun supportsControllerCommand(command: String): Boolean =
        client?.supportsControllerCommand(command) != false

    /**
     * Re-push the authoritative repeat/shuffle we actually hold, so a rejected
     * or ignored controller command cannot leave the overlay glyph out of sync.
     * Null stays null — the intent simply omits the extra and the overlay keeps
     * its current value rather than being reset to a value we never received.
     */
    fun repushPlaybackSettingsUi() {
        if (!isSendspinVinylUiEnabled()) return
        VinylCoverService.updatePlaybackSettings(
            context = context,
            repeatMode = cachedControllerRepeatMode
                ?: bridgedUiRepeatMode
                ?: cachedMetadataRepeatMode,
            shuffleEnabled = cachedControllerShuffleEnabled
                ?: bridgedUiShuffleEnabled
                ?: cachedMetadataShuffleEnabled,
        )
    }

    fun stopMedia(): Boolean = sendMediaCommand("stop")

    fun close(preserveOverlayForRebind: Boolean = false) {
        disable(preserveOverlayForRebind = preserveOverlayForRebind)
        discovery.close()
        if (!preserveOverlayForRebind) {
            resetSendspinMetadataCache()
            queueClearedIdentityTitle = null
            queueClearedIdentityArtist = null
            queueClearedIdentityAlbum = null
            queueClearedIdentityArtworkUrl = null
        } else {
            // Drop live clocks only — identity stays in MediaOverlayMemoryCache /
            // VinylCover for seamless rebind after the new manager is created.
            overlayPaintJob?.cancel()
            overlayPaintJob = null
            lastPushedOverlayPlaying = null
        }
    }

    /**
     * After soft recreate: hydrate sticky identity from the still-painted shell
     * and arm one-shot rebind so the first audible / metadata push reconnects UI.
     */
    fun prepareOverlayRebindIfNeeded() {
        // Media Controls off → never arm soft rebind (cover drip must not birth FAB).
        if (!isSendspinVinylUiEnabled()) {
            VinylCoverService.clearAwaitingPipelineRebind()
            return
        }
        val snap = MediaOverlayMemoryCache.get()
        val shellUp = VinylCoverService.isLiveOverlayShellVisible()
        val sendspinShell =
            VinylCoverService.isSendspinProgressOwner() || snap.isSendspinSource
        if (!sendspinShell) return
        if (!shellUp && !snap.hasDisplayableContent() &&
            !VinylCoverService.isAwaitingPipelineRebind()
        ) {
            return
        }
        seedIdentityFromOverlayCache(snap)
        VinylCoverService.markAwaitingPipelineRebind()
        VinylCoverService.cancelPauseIdleTeardown(context)
        Log.i(tag, "Prepared overlay pipeline rebind (shellUp=$shellUp)")
    }

    private fun seedIdentityFromOverlayCache(snap: MediaOverlayMemoryCache.Snapshot = MediaOverlayMemoryCache.get()) {
        if (!snap.hasDisplayableContent()) return
        if (cachedSendspinTitle.isNullOrEmpty() && snap.songTitle.isNotEmpty()) {
            cachedSendspinTitle = snap.songTitle
            // Same-track restore below re-seats 首/活尾/duration for this very track,
            // so claim the generation too — otherwise its first packet wipes them.
            progressIdentityTitle = snap.songTitle
        }
        if (cachedSendspinArtist.isNullOrEmpty() && snap.artistName.isNotEmpty()) {
            cachedSendspinArtist = snap.artistName
        }
        if (cachedSendspinAlbum.isNullOrEmpty() && snap.albumName.isNotEmpty()) {
            cachedSendspinAlbum = snap.albumName
        }
        if (cachedSendspinArtworkUrl.isNullOrEmpty() && !snap.coverUrl.isNullOrEmpty()) {
            cachedSendspinArtworkUrl = snap.coverUrl
        }
        if (snap.coverBitmap != null && !snap.coverBitmap.isRecycled) {
            hasBinaryArtwork = true
        }
        if (cachedDurationMs == null && snap.totalTimeMs > 0L) {
            cachedDurationMs = snap.totalTimeMs
        }
        val seat = snap.currentTimeMs.coerceAtLeast(0L)
        // Default cache is 0 with progressSeated=false — that is "never
        // written", not a start-of-track hard seat. Seeding that would yank
        // a mid-track rebind back to 0:00.
        if (progressHeadMs == null && (snap.progressSeated || seat > 0L)) {
            progressHeadMs = seat
            cachedProgressMs = seat
            lastGoodOverlayProgressMs = seat
            audibleTailMs = seat
            progressWaitForAudible = true
            progressHeadElapsedRealtime = 0L
        }
    }

    private fun clearPipelineRebindLatch() {
        if (VinylCoverService.isAwaitingPipelineRebind()) {
            VinylCoverService.clearAwaitingPipelineRebind()
        }
    }

    fun getStats(): Map<String, Any?> {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastStatsUpdateMs < statsUpdateThrottleMs) {
            return cachedStats ?: emptyMap()
        }
        lastStatsUpdateMs = nowMs

        val clientStats = client?.getStats()
        val stats = mapOf(
            "server_name" to (_connectedServer.value ?: "--"),
            "server_address" to (_serverUrl.value ?: "--"),
            "connection_state" to (clientStats?.connectionState ?: "Disconnected"),
            "audio_codec" to (clientStats?.audioCodec ?: "--"),
            "sample_rate" to (clientStats?.sampleRate ?: 0),
            "bit_depth" to (clientStats?.bitDepth ?: 0),
            "channels" to (clientStats?.channels ?: 0),
            "stream_source" to "Client",
            "playback_state" to (clientStats?.playbackState ?: if (_isPlaying.value) "PLAYING" else "PAUSED"),
            "sync_uncertainty_us" to (clientStats?.syncUncertaintyUs ?: 0L),
            "rtt_us" to (clientStats?.rttUs ?: 0L),
            "network_quality" to (clientStats?.networkQuality ?: "UNKNOWN"),
            "clock_stability" to (clientStats?.clockStability ?: "UNKNOWN"),
            "audio_latency_us" to (clientStats?.audioLatencyUs ?: 0L),
            "playout_offset_us" to (clientStats?.playoutOffsetUs ?: 0L),
            "late_drops" to (clientStats?.lateDrops ?: 0L),
            "queued_chunks" to (clientStats?.queuedChunks ?: 0),
            "audible_syncs" to (clientStats?.audibleSyncs ?: 0L),
            "kalman_error_count" to (clientStats?.kalmanErrorCount ?: 0L),
            "clock_drift_ppm" to (clientStats?.clockDriftPpm ?: 0.0),
            "buffer_ahead_ms" to (clientStats?.bufferAheadMs ?: 0L),
            "chunks_received" to (clientStats?.chunksReceived ?: 0L),
            "chunks_played" to (clientStats?.chunksPlayed ?: 0L),
            "chunks_dropped" to (clientStats?.chunksDropped ?: 0L),
            "buffer_underrun_count" to (clientStats?.bufferUnderrunCount ?: 0L),
            "output_device" to getOutputDeviceLabel(),
            "volume_percent" to getDeviceVolume(),
            "is_muted" to currentMuted,
            "playback_speed" to (clientStats?.playbackSpeed ?: 1.0f),
            "is_connected" to (clientStats?.isConnected ?: false),
            "drift_uncertainty_ppm" to (clientStats?.driftUncertaintyPpm ?: 0.0),
            "drift_snr" to (clientStats?.driftSnr ?: 0.0),
            "connection_drops" to (clientStats?.connectionDrops ?: 0),
            "clock_ready" to (clientStats?.clockReadyForPlayback ?: false),
            "force_resync" to (clientStats?.forceResyncActive ?: false),
            "audio_output_started" to (clientStats?.audioOutputStarted ?: false),
            "server_lateness_ms" to (clientStats?.serverLatenessMs ?: 0L),
            "network_jitter_ms" to (clientStats?.networkJitterMs ?: 0L),
            "clock_update_count" to (clientStats?.clockUpdateCount ?: 0),
            "static_delay_ms" to (clientStats?.staticDelayMs ?: 0L),
            "estimated_offset_ms" to (clientStats?.estimatedOffsetMs ?: 0L)
        )
        cachedStats = stats
        return stats
    }

    private fun connectInternal(rawUrl: String, serverName: String?) {
        val normalized = normalizeServerUrl(rawUrl)
        if (normalized.isBlank()) return
        reconnectJob?.cancel()
        reconnectJob = null
        clientStateJob?.cancel()
        clientStateJob = null
        client?.cleanupResources()
        client = null

        _serverUrl.value = normalized
        _connectedServer.value = serverName
        val syncOffsetMs = getSyncOffsetMs()
        val newClient = buildClient(normalized, syncOffsetMs)
        client = newClient
        applyOutputRouting(newClient)
        newClient.setSyncOffsetMs(syncOffsetMs, userAdjusted = syncOffsetMs != 0)

        clientStateJob = scope.launch {
            newClient.uiState.collect { state ->
                cachedStats = null
                if (!inboundMode && (state.status.startsWith("failure:") || state.status.startsWith("closed:"))) {
                    scheduleReconnect(normalized, serverName)
                }
                if (state.connected) {
                    // A live connection resets the backoff so the next drop retries
                    // fast (1s) instead of inheriting a stale attempt count and
                    // waiting up to the 60s ceiling.
                    reconnectAttempt = 0
                    if (serverName != null) {
                        savePairedDeviceCallback?.invoke(normalized, serverName)
                    }
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            newClient.connect()
        }
    }

    private fun startInboundServer(
        port: Int = DEFAULT_CLIENT_PORT,
        name: String = ""
    ) {
        @Suppress("NAME_SHADOWING")
        val name = name.ifEmpty { resolveClientName() }
        var previous: SendspinSocketServer? = null
        synchronized(inboundServerLock) {
            if (inboundServerStarting) {
                return
            }
            if (socketServer != null && inboundServerPort == port && inboundServerName == name) {
                if (!discovery.isAdvertising.value) {
                    discovery.startAdvertising(port, name)
                }
                _serverRunning.value = true
                _serverUrl.value = "ws://0.0.0.0:$port/sendspin"
                scheduleInboundFallback(port, name)
                return
            }

            inboundServerStarting = true
            previous = detachInboundServerLocked()
        }
        scope.launch(Dispatchers.IO) {
            previous?.stopBlocking()
            if (!SendspinListenPort.isFree(port)) {
                Log.w(tag, "Inbound port $port still in use; waiting for release")
                if (!SendspinListenPort.awaitFree(port)) {
                    Log.e(tag, "Inbound sendspin port $port still occupied; will retry")
                    synchronized(inboundServerLock) {
                        inboundServerStarting = false
                    }
                    scheduleInboundBindRetry(port, name)
                    return@launch
                }
            }
            if (!_enabled.value || !inboundMode) {
                synchronized(inboundServerLock) {
                    inboundServerStarting = false
                }
                return@launch
            }
            bindInboundServerNow(port, name)
        }
    }

    private fun bindInboundServerNow(port: Int, name: String) {
        synchronized(inboundServerLock) {
            if (!_enabled.value || !inboundMode) {
                inboundServerStarting = false
                return
            }
            if (socketServer != null && inboundServerPort == port && inboundServerName == name) {
                inboundServerStarting = false
                return
            }

            // Set up the Sendspin multi-server arbiter before the socket starts
            // accepting connections. The arbiter is responsible for the spec's
            // "complete handshake on new connection → decide based on
            // connection_reason + last_played_server_id" flow.
            arbiter?.shutdown()
            arbiter = SendspinArbiter(
                context = context,
                clientId = getOrCreateDeviceId(),
                clientName = resolveClientName(),
                isLowMemoryDevice = isLowMemoryMode(),
                preferredFormat = getPreferredFormat(),
                scope = scope,
                getLastPlayedServerId = {
                    getLastPlayedServerIdCallback?.invoke().orEmpty()
                },
                getIncumbentInfo = {
                    client?.let { c ->
                        if (!c.isHandshakeComplete()) return@let null
                        val id = c.getServerId()
                        if (id.isBlank()) null else id to c.getServerConnectionReason()
                    }
                },
                onDecision = { decision ->
                    scope.launch(Dispatchers.Main) {
                        handleArbitrationDecision(decision, port)
                    }
                }
            )

            val server = SendspinSocketServer(
                listenPort = port,
                onListening = {
                    synchronized(inboundServerLock) {
                        if (socketServer != null &&
                            inboundServerPort == port &&
                            inboundServerName == name
                        ) {
                            inboundServerStarting = false
                            inboundBindAttempts = 0
                            _serverRunning.value = true
                            _serverUrl.value = "ws://0.0.0.0:$port/sendspin"
                            if (!discovery.isAdvertising.value) {
                                discovery.startAdvertising(port, name)
                            }
                            scheduleInboundFallback(port, name)
                        }
                    }
                },
                onClientConnected = { remoteLabel, sendText, sendBinary, closeConnection ->
                    synchronized(inboundServerLock) {
                        inboundServerStarting = false
                    }
                    scope.launch(Dispatchers.Main) {
                        noteInboundServerConnected()
                        attachIncomingConnection(
                            remoteLabel,
                            port,
                            sendText,
                            closeConnection,
                            sendBinary = sendBinary,
                        )
                    }
                },
                onTextMessage = { text ->
                    client?.handleIncomingText(text)
                },
                onBinaryMessage = { data ->
                    client?.handleIncomingBinary(data)
                },
                onClientDisconnected = { reason ->
                    client?.handleIncomingClosed(reason)
                },
                onClientError = { error ->
                    var retryBind = false
                    var failed: SendspinSocketServer? = null
                    synchronized(inboundServerLock) {
                        inboundServerStarting = false
                        if (socketServer != null &&
                            inboundServerPort == port &&
                            inboundServerName == name &&
                            error is java.net.BindException
                        ) {
                            failed = socketServer
                            socketServer = null
                            inboundServerPort = null
                            inboundServerName = null
                            _serverRunning.value = false
                            _serverUrl.value = null
                            discovery.stopAdvertising()
                            retryBind = _enabled.value && inboundMode
                        }
                    }
                    if (failed != null || retryBind) {
                        scope.launch(Dispatchers.IO) {
                            failed?.stopBlocking()
                            if (retryBind) {
                                if (!SendspinListenPort.awaitFree(port)) {
                                    Log.e(
                                        tag,
                                        "Port $port still occupied after BindException stop"
                                    )
                                }
                                scheduleInboundBindRetry(port, name)
                            }
                        }
                    }
                    client?.handleIncomingFailure(error)
                },
                onCandidateConnected = { handle ->
                    arbiter?.acceptCandidate(handle)
                },
                onCandidateText = { handle, message ->
                    arbiter?.onCandidateMessage(handle, message)
                },
                onCandidateBinary = { handle, data ->
                    arbiter?.onCandidateBinary(handle, data)
                },
                onCandidateClosed = { handle ->
                    arbiter?.onCandidateClosed(handle)
                }
            )
            socketServer = server
            inboundServerPort = port
            inboundServerName = name
            _serverRunning.value = false
            _serverUrl.value = null
            server.start()
        }
    }

    /**
     * Apply a [SendspinArbiter.Decision]:
     *  - KeepIncumbent → send `client/goodbye{reason: 'another_server'}` on the
     *    candidate, then close it. Active connection is untouched.
     *  - Switch → send goodbye on incumbent, promote candidate as the new
     *    active transport, rebuild [SendspinClient] over it.
     *  - Timeout → close candidate, keep incumbent.
     */
    private fun handleArbitrationDecision(
        decision: SendspinArbiter.Decision,
        port: Int
    ) {
        when (decision) {
            is SendspinArbiter.Decision.KeepIncumbent -> {
                android.util.Log.i(
                    "SendspinManager",
                    "Arbitration: keep incumbent (candidate=${decision.candidate.remoteLabel} " +
                        "server_id=${decision.candidateServerId} " +
                        "reason=${decision.candidateConnectionReason} " +
                        "rationale=${decision.rationale})"
                )
                runCatching {
                    val goodbye = org.json.JSONObject()
                        .put("type", "client/goodbye")
                        .put(
                            "payload",
                            org.json.JSONObject()
                                .put("reason", SendspinClient.GoodbyeReason.ANOTHER_SERVER.wire)
                        )
                        .toString()
                    val transport = decision.noiseTransport
                    if (transport != null) {
                        transport.encryptJson(goodbye).forEach { frame ->
                            decision.candidate.sendBinary(frame)
                        }
                    } else {
                        decision.candidate.sendText(goodbye)
                    }
                }
                socketServer?.rejectCandidate(decision.candidate, code = 1000, reason = "another_server")
            }

            is SendspinArbiter.Decision.Switch -> {
                android.util.Log.i(
                    "SendspinManager",
                    "Arbitration: switch to candidate=${decision.candidate.remoteLabel} " +
                        "server_id=${decision.candidateServerId} " +
                        "reason=${decision.candidateConnectionReason} " +
                        "rationale=${decision.rationale}"
                )
                val incumbent = client
                // Spec: goodbye on the server being disconnected, then close transport.
                // Send goodbye only here — do NOT tear down the incumbent client until
                // promotion succeeds, so a failed promotion leaves playback intact.
                incumbent?.sendGoodbye(SendspinClient.GoodbyeReason.ANOTHER_SERVER)

                val server = socketServer
                if (server == null) {
                    runCatching { decision.candidate.closeConnection(1000, "manager_gone") }
                    return
                }
                val promoted = server.promoteCandidate(
                    handle = decision.candidate,
                    oldActiveCloseCode = 1000,
                    oldActiveCloseReason = "another_server"
                ) { sendText, sendBinary, close ->
                    attachIncomingConnection(
                        remoteLabel = decision.candidate.remoteLabel,
                        port = port,
                        sendText = sendText,
                        closeConnection = close,
                        sendBinary = sendBinary,
                        arbitrationServerHello = decision.serverHelloPayload,
                        establishedTransport = decision.noiseTransport,
                    )
                }
                if (promoted) {
                    incumbent?.teardownAfterTransportLost("arbitration_switched")
                    refreshVinylFromCacheIfNeeded()
                } else {
                    android.util.Log.w(
                        "SendspinManager",
                        "Candidate ${decision.candidate.remoteLabel} disappeared before promotion; " +
                            "incumbent may still be connected"
                    )
                }
            }

            is SendspinArbiter.Decision.Timeout -> {
                android.util.Log.w(
                    "SendspinManager",
                    "Arbitration: candidate ${decision.candidate.remoteLabel} timed out, dropping"
                )
                socketServer?.rejectCandidate(decision.candidate, code = 1001, reason = "handshake_timeout")
            }
        }
    }

    private fun stopInboundServer() {
        inboundBindRetryJob?.cancel()
        inboundBindRetryJob = null
        inboundBindAttempts = 0
        val stopping: SendspinSocketServer?
        synchronized(inboundServerLock) {
            inboundServerStarting = false
            stopping = detachInboundServerLocked()
        }
        if (stopping != null) {
            scope.launch(Dispatchers.IO) {
                stopping.stopBlocking()
            }
        }
    }

    private fun scheduleInboundBindRetry(port: Int, name: String) {
        if (!_enabled.value || !inboundMode) return
        if (inboundBindAttempts >= MAX_INBOUND_BIND_RETRIES) {
            Log.e(
                tag,
                "Inbound sendspin bind gave up after $inboundBindAttempts tries on $port"
            )
            inboundBindAttempts = 0
            return
        }
        inboundBindAttempts++
        val waitMs = INBOUND_BIND_RETRY_BASE_MS * inboundBindAttempts
        inboundBindRetryJob?.cancel()
        inboundBindRetryJob = scope.launch {
            delay(waitMs)
            if (!_enabled.value || !inboundMode) return@launch
            if (client != null) {
                inboundBindAttempts = 0
                return@launch
            }
            val alreadyListening = synchronized(inboundServerLock) {
                socketServer != null && inboundServerPort == port
            }
            if (alreadyListening) {
                inboundBindAttempts = 0
                return@launch
            }
            Log.w(
                tag,
                "Retrying inbound sendspin listen on $port (attempt $inboundBindAttempts)"
            )
            startInboundServer(port, name)
        }
    }

    private fun detachInboundServerLocked(): SendspinSocketServer? {
        val previous = socketServer
        socketServer = null
        arbiter?.shutdown()
        arbiter = null
        inboundServerPort = null
        inboundServerName = null
        _serverRunning.value = false
        return previous
    }

    private fun attachIncomingConnection(
        remoteLabel: String,
        port: Int,
        sendText: (String) -> Boolean,
        closeConnection: (Int, String) -> Unit,
        sendBinary: ((ByteArray) -> Boolean)? = null,
        arbitrationServerHello: org.json.JSONObject? = null,
        establishedTransport: SendspinNoiseTransport? = null,
    ) {
        reconnectJob?.cancel()
        reconnectJob = null
        clientStateJob?.cancel()
        clientStateJob = null
        cancelHideJob()
        if (arbitrationServerHello != null) {
            connectionHandoffUntilMs = System.currentTimeMillis() + 3_000L
        }
        // Arbitration switch tears down the incumbent after a successful promote;
        // all other paths must clean up the previous client here.
        if (arbitrationServerHello == null) {
            client?.cleanupResources()
        }

        val syncOffsetMs = getSyncOffsetMs()
        val newClient = buildClient("incoming://$remoteLabel:$port/sendspin", syncOffsetMs)
        client = newClient
        _connectedServer.value = remoteLabel
        _serverUrl.value = "incoming://$remoteLabel:$port/sendspin"
        applyOutputRouting(newClient)
        newClient.setSyncOffsetMs(syncOffsetMs, userAdjusted = syncOffsetMs != 0)

        clientStateJob = scope.launch {
            newClient.uiState.collect {
                cachedStats = null
            }
        }

        if (arbitrationServerHello != null) {
            newClient.acceptIncomingConnectionAfterArbitration(
                remoteLabel,
                sendText,
                closeConnection,
                arbitrationServerHello,
                sendBinary = sendBinary,
                establishedTransport = establishedTransport,
            )
            refreshVinylFromCacheIfNeeded()
        } else {
            newClient.acceptIncomingConnection(
                remoteLabel,
                sendText,
                closeConnection,
                sendBinary = sendBinary,
            )
        }
    }

    private fun buildClient(url: String, initialSyncOffsetMs: Int): SendspinClient {
        refreshLocalPlayerVolumeState(persistDeviceVolume = true)
        val volumePercent = resolvePlayerVolumePercent()
        val muted = resolvePlayerMuted()
        return SendspinClient(
            wsUrl = url,
            clientId = getOrCreateDeviceId(),
            clientName = resolveClientName(),
            context = context,
            initialSyncOffsetMs = initialSyncOffsetMs,
            initialPlayerVolumePercent = volumePercent,
            initialPlayerMuted = muted,
            forceLowMemoryMode = isLowMemoryMode(),
            preferredFormat = getPreferredFormat(),
            preferredFormatProvider = { getPreferredFormat() },
            onMetadata = { handleMetadata(it) },
            onArtwork = { bitmap -> handleBinaryArtwork(bitmap) },
            onControllerState = { handleControllerState(it) },
            onPlaybackState = { handlePlaybackState(it) },
            onAudibleProgress = { handleAudibleProgress(it) },
            onTransportAudible = { noteTransportAudible() },
            onStreamClear = { noteStreamClearForProgress() },
            onStreamEnd = { handleStreamEnd() },
            // metadata:null alone is not enough to tear down the FAB — some
            // servers emit it on pause. Empty-queue hide is [handleStreamEnd] only.
            onMetadataRoleCleared = { handleMetadataRoleCleared() },
            onVolumeCommand = { volume ->
                applyPlayerVolumeFromUpstream(volume, persist = true, syncClient = false)
            },
            onMuteCommand = { muted ->
                currentMuted = muted
                setMutedCallback?.invoke(muted)
                setDeviceMuted(muted)
                applyOutputRouting()
                externalMuteCallback?.invoke(muted)
            },
            onServerHello = { serverId, connectionReason ->
                android.util.Log.d(
                    "SendspinManager",
                    "server/hello server_id=$serverId connection_reason=$connectionReason"
                )
                // The id MA now knows us by (Noise key or ANDROID_ID) is settled
                // here; re-resolve "self" in the MA rail so it does not stay on
                // a player row left behind by the other transport.
                val sessionId = client?.activeClientId().orEmpty()
                if (sessionId.isNotBlank() && sessionId != lastSessionClientId) {
                    lastSessionClientId = sessionId
                    MassApiManager.get()?.retryIdentityResolution()
                }
            },
            onPlaybackPlaying = { serverId ->
                if (serverId.isNotEmpty()) {
                    savePlayedServerIdCallback?.invoke(serverId)
                }
            },
            onForeignGroupBlocked = { blocked ->
                if (blocked) {
                    Log.w(tag, "foreign Sendspin group blocked — ignore server play")
                    applyProtocolNotPlaying(scheduleHide = false)
                }
            },
            onGroupDissolved = { handleSharedGroupDissolved() },
            isMassSyncGrouped = { MassApiManager.get()?.isSelfMassSyncGrouped() },
        ).also { client ->
            client.setPlaybackGainRefresh { buildPlaybackGain() }
        }
    }

    /**
     * MA player list moved. The pairing proof (`synced_to` / `group_childs`)
     * rides MA's own socket and lands after the server's group/update — a
     * blocked client re-judges here instead of waiting out its probation.
     */
    fun onMassPlayersChanged() {
        client?.reevaluateForeignGroup()
    }

    private fun scheduleReconnect(url: String, serverName: String?) {
        if (!_enabled.value || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            reconnectAttempt++
            val delayMs = (1_000L * Math.pow(2.0, reconnectAttempt.toDouble())).toLong().coerceAtMost(60_000L)
            delay(delayMs)
            if (_enabled.value) {
                connectInternal(url, serverName)
            }
        }
    }

    /**
     * MA connected via the inbound `_sendspin._tcp` path — cancel silent outbound.
     */
    private fun noteInboundServerConnected() {
        cancelInboundFallback()
        discovery.stopDiscovery()
    }

    private fun cancelInboundFallback() {
        inboundFallbackJob?.cancel()
        inboundFallbackJob = null
        silentOutboundWatchJob?.cancel()
        silentOutboundWatchJob = null
        silentOutboundResumePort = null
        silentOutboundResumeName = null
    }

    /**
     * If no server-initiated connection arrives, browse `_sendspin-server._tcp` and
     * connect outbound once. On failure, resume inbound advertise (no UI change).
     */
    private fun scheduleInboundFallback(port: Int, name: String) {
        if (!_enabled.value || !inboundMode) return
        if (SystemClock.elapsedRealtime() < silentOutboundCooldownUntilMs) return
        if (client?.isHandshakeComplete() == true) return
        if (inboundFallbackJob?.isActive == true) return

        inboundFallbackJob = scope.launch {
            delay(INBOUND_FALLBACK_DELAY_MS)
            if (!_enabled.value || !inboundMode) return@launch
            if (client?.isHandshakeComplete() == true) return@launch
            if (SystemClock.elapsedRealtime() < silentOutboundCooldownUntilMs) return@launch

            Log.i(
                tag,
                "No inbound Sendspin connection after ${INBOUND_FALLBACK_DELAY_MS}ms; " +
                    "trying silent MA discovery (_sendspin-server._tcp)"
            )
            discovery.startDiscovery()
            val server = withTimeoutOrNull(SILENT_DISCOVERY_TIMEOUT_MS) {
                discovery.discoveredServers.first { it.isNotEmpty() }.firstOrNull()
            }
            discovery.stopDiscovery()

            if (!_enabled.value || !inboundMode) return@launch
            if (client?.isHandshakeComplete() == true) return@launch

            if (server == null) {
                Log.i(tag, "Silent MA discovery found no server; staying on inbound advertise")
                if (!discovery.isAdvertising.value) {
                    discovery.startAdvertising(port, name)
                }
                return@launch
            }

            Log.i(tag, "Silent outbound fallback → ${server.wsUrl} (${server.name})")
            silentOutboundResumePort = port
            silentOutboundResumeName = name
            // Spec: do not keep advertising `_sendspin._tcp` while initiating.
            inboundMode = false
            stopInboundServer()
            discovery.stopAdvertising()
            _serverRunning.value = false
            reconnectAttempt = 0
            connectInternal(server.wsUrl, server.name)

            silentOutboundWatchJob?.cancel()
            silentOutboundWatchJob = scope.launch {
                delay(SILENT_OUTBOUND_HANDSHAKE_TIMEOUT_MS)
                if (!_enabled.value) return@launch
                if (client?.isHandshakeComplete() == true) {
                    Log.i(tag, "Silent outbound handshake ok")
                    silentOutboundResumePort = null
                    silentOutboundResumeName = null
                    return@launch
                }
                val resumePort = silentOutboundResumePort ?: port
                val resumeName = silentOutboundResumeName ?: name
                Log.w(tag, "Silent outbound handshake timed out; resuming inbound advertise")
                reconnectJob?.cancel()
                reconnectJob = null
                clientStateJob?.cancel()
                clientStateJob = null
                client?.cleanupResources()
                client = null
                _connectedServer.value = null
                silentOutboundCooldownUntilMs =
                    SystemClock.elapsedRealtime() + SILENT_OUTBOUND_COOLDOWN_MS
                silentOutboundResumePort = null
                silentOutboundResumeName = null
                inboundMode = true
                startInboundServer(resumePort, resumeName)
            }
        }
    }

    private fun isLowMemoryMode(): Boolean = lowMemoryModeCallback?.invoke() ?: false

    private fun getPreferredFormat(): String = preferredFormatCallback?.invoke() ?: "automatic"

    private fun getSyncOffsetMs(): Int {
        val callbackValue = syncOffsetMsCallback?.invoke()?.coerceIn(-1000, 1000)
        if (callbackValue != null) {
            cachedSyncOffsetMs = callbackValue
            return callbackValue
        }
        return cachedSyncOffsetMs ?: 0
    }

    /**
     * STREAM_MUSIC was written outside this manager (voice-reply overlay).
     * Keep the local cache aligned without reporting to MA/HA.
     */
    fun noteExternalDeviceVolume(normalized: Float) {
        currentVolume = normalized.coerceIn(0f, 1f)
        lastUpstreamAppliedStep = currentDeviceStep()
        applyOutputRouting()
    }

    fun refreshOutputRouting() {
        if (!isUpstreamVolumeApplyActive()) {
            currentVolume = resolvePlayerVolumePercent() / 100f
        }
        applyOutputRouting()
    }

    /**
     * User changed STREAM_MUSIC via hardware keys / system UI (not MA/HA upstream).
     * Report-only: never writes STREAM_MUSIC (avoids percent↔step fight with physical keys).
     * Echoes of our own upstream [setStreamVolume] are ignored; a new step during the guard
     * window is treated as a real key press and reported to MA (issue #106).
     */
    fun onHardwareMusicVolumeChanged(normalizedLevel: Float) {
        if (!_enabled.value) return
        val step = currentDeviceStep()
        if (isUpstreamVolumeApplyActive() && step == lastUpstreamAppliedStep) return
        if (step == lastReportedHardwareStep) return
        lastReportedHardwareStep = step
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val percent = if (maxVolume > 0) {
            stepToPercent(step, maxVolume)
        } else {
            (normalizedLevel * 100f).toInt().coerceIn(0, 100)
        }
        currentVolume = percent / 100f
        setVolumeCallback?.invoke(percent)
        client?.updateVolume(percent)
        applyOutputRouting()
        // Do not invoke externalVolumeCallback here — VoiceSatelliteService mirrors
        // ESPHome / HA from the hardware path so we do not double-set player volume.
        cachedStats = null
    }

    private fun buildPlaybackGain(): SendspinPcmProcessor.PlaybackGain {
        return SendspinPcmProcessor.PlaybackGain(
            appVolumeLinear = currentVolume.coerceIn(0f, 1f),
            deviceVolumeLinear = getDeviceVolumeLinear(),
            useDeviceVolume = true,
            muted = currentMuted,
            duckLinear = duckLinearOverride ?: if (isDucked) VOICE_OVERLAY_DUCK_LINEAR else 1f,
        )
    }

    private fun applyOutputRouting(newClient: SendspinClient? = client) {
        newClient?.setVoiceOverlayDucked(isDucked)
        newClient?.setPlaybackGain(buildPlaybackGain())
    }

    private fun getDeviceVolumeLinear(): Float =
        SendspinPcmProcessor.deviceVolumeLinear(audioManager)

    private fun currentDeviceStep(): Int =
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    /** Nearest STREAM_MUSIC step for a 0–100 percent (round-trip stable with [stepToPercent]). */
    private fun percentToStep(percent: Int, maxVolume: Int): Int {
        if (maxVolume <= 0) return 0
        return ((percent.coerceIn(0, 100) * maxVolume + 50) / 100).coerceIn(0, maxVolume)
    }

    /** 0–100 from STREAM_MUSIC step (round-trip stable with [percentToStep]). */
    private fun stepToPercent(step: Int, maxVolume: Int): Int {
        if (maxVolume <= 0) return 100
        return ((step.coerceIn(0, maxVolume) * 100 + maxVolume / 2) / maxVolume).coerceIn(0, 100)
    }

    private fun setDeviceVolume(volumePercent: Int) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = percentToStep(volumePercent, maxVolume)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        suppressDeviceVolumeObserver?.invoke(4)
        lastUpstreamAppliedStep = targetVolume
        lastReportedHardwareStep = targetVolume
        if (current == targetVolume) return
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        // MA/HA-driven volume writes are suppressed from the device volume observer,
        // so flag the level change for the AEC directly.
        com.example.ava.audio.PlaybackReferenceBus.noteLevelChange()
    }

    private fun setDeviceMuted(muted: Boolean) {
        suppressDeviceVolumeObserver?.invoke(1)
        com.example.ava.audio.PlaybackReferenceBus.noteLevelChange()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0
            )
        } else {
            @Suppress("DEPRECATION")
            audioManager.setStreamMute(AudioManager.STREAM_MUSIC, muted)
        }
    }

    private fun getDeviceVolume(): Int {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return stepToPercent(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), maxVolume)
    }

    private fun getOutputDeviceLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return if (audioManager.isSpeakerphoneOn) "Speaker" else "System"
        }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val candidates = outputs.filter { it.isSink && isActiveOutputType(it.type) }
        val preferred = candidates.firstOrNull {
            it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE &&
                it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: candidates.firstOrNull()
        return preferred?.type?.let(::mapOutputDeviceType) ?: "Unknown"
    }

    private fun isActiveOutputType(type: Int): Boolean {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> true
            else -> false
        }
    }

    private fun mapOutputDeviceType(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "External"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            else -> "System"
        }
    }

    private fun handlePlaybackState(state: String) {
        when (normalizePlaybackState(state)) {
            "stopped" -> {
                if (System.currentTimeMillis() < connectionHandoffUntilMs) {
                    return
                }
                // Audio still flowing → protocol "stopped" is a track-jump gap, not
                // a real stop (cold start / clear / speed flash). Keep ❚❚.
                // Rescue window: track-change head pauses defer even when quiet —
                // reconcile decides ghost-rescue vs landing.
                if (isAudiblyPlayingNow() || inTrackJumpSeam() || inReseatPlayingHold() ||
                    inTrackChangeRescueWindow()
                ) {
                    seamPendingPause = true
                    if (!inTrackJumpSeam()) armTrackJumpSeam()
                    return
                }
                // Real stop with quiet audio — still keep FAB (track may remain).
                applyProtocolNotPlaying(scheduleHide = false)
            }
            "paused" -> {
                if (isAudiblyPlayingNow() || inTrackJumpSeam() || inReseatPlayingHold() ||
                    inTrackChangeRescueWindow()
                ) {
                    seamPendingPause = true
                    if (!inTrackJumpSeam()) armTrackJumpSeam()
                    return
                }
                applyProtocolNotPlaying(scheduleHide = false)
            }
            "playing" -> {
                if (client?.isForeignGroupBlocked() == true) return
                seamPendingPause = false
                connectionHandoffUntilMs = 0L
                applyProtocolPlaying()
            }
        }
    }

    /**
     * Promote transport to playing from a protocol "playing" signal.
     * Does not fight [noteTransportAudible] — both paths converge on ❚❚.
     */
    private fun applyProtocolPlaying() {
        cancelHideJob()
        VinylCoverService.endQueueClearedGrace(context)
        VinylCoverService.cancelPauseIdleTeardown(context)
        _isActive.value = true
        if (!_isPlaying.value) {
            // Frozen seat until PCM — do not start the UI clock on protocol "playing".
            // Drop paused peer hold so residual freeze cannot block audible thaw.
            clearPeerProgressLock()
            playResumeGraceUntilElapsed =
                SystemClock.elapsedRealtime() + PEER_PROGRESS_PLAY_RESUME_GRACE_MS
            val resumeSeed = pickResumeSeed(client?.upstreamTrackProgressMs())
            reanchorProgressClock(resumeSeed)
            // Protocol "playing" is an MA echo, not a user tap. Claiming an
            // epoch here is what let the master steal a resume/seek that
            // actually happened on the passive. The tapper already broadcast
            // the seat; we just follow it.
            _isPlaying.value = true
            externalPlayingCallback?.invoke(true)
            val nowElapsed = SystemClock.elapsedRealtime()
            progressFollowUntilElapsedRealtime = nowElapsed + PROGRESS_FOLLOW_WINDOW_MS
            lastProgressResyncElapsedRealtime = nowElapsed
            cachedPlaybackSpeed = 1000
            pokePeerProgressMirror()
        }
        pushOverlayPlayingState(true)
        val pipelineRebind = VinylCoverService.isAwaitingPipelineRebind()
        refreshVinylFromCacheIfNeeded(ignoreEnabledGate = pipelineRebind)
        if (pipelineRebind) clearPipelineRebindLatch()
    }

    /**
     * Apply a real pause/stop from protocol — only after audio has gone quiet.
     * Arms shared pause-idle teardown; empty queue uses [scheduleQueueClearedHide].
     */
    private fun applyProtocolNotPlaying(@Suppress("UNUSED_PARAMETER") scheduleHide: Boolean) {
        if (_isPlaying.value) {
            freezeProgressAtCurrent()
            _isPlaying.value = false
            externalPlayingCallback?.invoke(false)
        }
        pushOverlayPlayingState(false)
        retainOverlayAfterPause()
    }

    /**
     * PCM is leaving the device → transport glyph must be ❚❚.
     * This is the cold-start / track-jump authority that protocol speed=0 cannot override.
     */
    private fun noteTransportAudible() {
        lastAudibleElapsedRealtimeMs = SystemClock.elapsedRealtime()
        clearDeadStreamHold()
        val overlayBirthAllowed = VinylCoverService.noteSessionPlaybackStarted()
        // Empty-queue force teardown wiped identity, then the same track resumed
        // with PCM only (pause-typed stream/end) — resurrect the snapshot so the
        // FAB can rebuild now instead of waiting for next-track metadata. A real
        // new track overwrites these fields on its first metadata push.
        val resurrectedIdentity =
            cachedSendspinTitle.isNullOrEmpty() &&
                !queueClearedIdentityTitle.isNullOrEmpty()
        if (resurrectedIdentity) {
            cachedSendspinTitle = queueClearedIdentityTitle
            // Pause-typed teardown then same-track resume: not a track change.
            progressIdentityTitle = queueClearedIdentityTitle
            cachedSendspinArtist = queueClearedIdentityArtist
            cachedSendspinAlbum = queueClearedIdentityAlbum
            cachedSendspinArtworkUrl = queueClearedIdentityArtworkUrl
            queueClearedIdentityTitle = null
            queueClearedIdentityArtist = null
            queueClearedIdentityAlbum = null
            queueClearedIdentityArtworkUrl = null
        }
        // Pause-idle tuck keeps identity; re-show on real PCM when:
        // - prepared latch is set, OR
        // - surface is GONE but identity/cache still there (latch can be lost
        //   after ownership races — used to require next-track metadata), OR
        // - identity was just resurrected after a pause-typed teardown, OR
        // - soft pipeline restart armed a one-shot rebind (zombie shell stays up).
        // Do not refresh on every audible tick while already visible (unless rebind).
        val shouldReshowFromPauseIdle =
            VinylCoverService.isAwaitingPauseIdleAudibleReshow()
        val hiddenWithIdentity = VinylCoverService.shouldAudibleReshowHiddenOverlay()
        val pipelineRebind = VinylCoverService.isAwaitingPipelineRebind()
        val shouldRefreshOverlay =
            overlayBirthAllowed || shouldReshowFromPauseIdle ||
                hiddenWithIdentity || resurrectedIdentity || pipelineRebind
        seamPendingPause = false
        clearReseatPlayingHold()
        cancelHideJob()
        VinylCoverService.cancelPauseIdleTeardown(context)
        VinylCoverService.endQueueClearedGrace(context)
        _isActive.value = true
        if (!_isPlaying.value) {
            _isPlaying.value = true
            externalPlayingCallback?.invoke(true)
            val nowElapsed = SystemClock.elapsedRealtime()
            progressFollowUntilElapsedRealtime = nowElapsed + PROGRESS_FOLLOW_WINDOW_MS
            lastProgressResyncElapsedRealtime = nowElapsed
        }
        if (cachedPlaybackSpeed <= 0) cachedPlaybackSpeed = 1000
        // PCM is flowing — unfreeze mapper/MA interpolator even when we were
        // already "playing" (skip-storm freeze left speed=0 with _isPlaying true).
        thawProgressAfterAudibleResume()
        // Transport glyph only. Progress wall-clock starts in [handleAudibleProgress]
        // after a mapped write — first AudioTrack write can still precede audible
        // sound by the output buffer; never start the bar here on resume/repeat.
        if (audibleTailMs == null && progressWaitForAudible) {
            reconcileProgressOverlayTicker(true)
            pushOverlayPlayingState(true, immediate = true)
            if (shouldRefreshOverlay) {
                refreshVinylFromCacheIfNeeded(ignoreEnabledGate = pipelineRebind)
                if (pipelineRebind) clearPipelineRebindLatch()
            }
            return
        }
        if (audibleTailMs == null) {
            val needsSeat = awaitingSeekReseat || client?.needsAbsoluteAudibleSeat() == true
            if (needsSeat && pinnedLocalSeekMs == null && progressHeadMs == null) {
                reconcileProgressOverlayTicker(true)
                pushOverlayPlayingState(true, immediate = true)
                if (shouldRefreshOverlay) {
                    refreshVinylFromCacheIfNeeded(ignoreEnabledGate = pipelineRebind)
                    if (pipelineRebind) clearPipelineRebindLatch()
                }
                return
            }
            if (needsSeat && pinnedLocalSeekMs == null && progressHeadElapsedRealtime <= 0L) {
                reconcileProgressOverlayTicker(_isPlaying.value)
                pushOverlayPlayingState(true, immediate = true)
                if (shouldRefreshOverlay) {
                    refreshVinylFromCacheIfNeeded(ignoreEnabledGate = pipelineRebind)
                    if (pipelineRebind) clearPipelineRebindLatch()
                }
                return
            }
            // Cold start without an explicit wait flag: still defer clock to
            // handleAudibleProgress (same write cycle) — only seed the hold.
            val startMs = (
                pinnedLocalSeekMs
                    ?: progressHeadMs
                    ?: cachedProgressMs
                    ?: 0L
                ).coerceAtLeast(0L)
            progressHeadMs = startMs
            progressHeadElapsedRealtime = 0L
            progressWaitForAudible = true
            cachedProgressMs = startMs
            lastGoodOverlayProgressMs = startMs
            if (cachedPlaybackSpeed <= 0) cachedPlaybackSpeed = 1000
            pushOverlayProgress(startMs, cachedDurationMs)
        }
        // Soft-frozen silence: do not restart wall clock here — wait for the next
        // onAudibleProgress so we do not race the 1–2s stream restart gap.
        reconcileProgressOverlayTicker(true)
        pushOverlayPlayingState(true, immediate = true)
        if (shouldRefreshOverlay) {
            refreshVinylFromCacheIfNeeded(ignoreEnabledGate = pipelineRebind)
            if (pipelineRebind) clearPipelineRebindLatch()
        }
    }

    /** Public: MassApiManager mirrors [isLikelyMirrorOfCurrentTrack] semantics 1:1. */
    fun isAudiblyPlayingNow(): Boolean = client?.isAudiblyPlaying() == true

    /**
     * True while Sendspin PCM is audible now, or was within [withinMs].
     *
     * HA overlay claim guard: the source actually making sound keeps the
     * overlay. Brief protocol gaps (track seams, paused metadata echoes,
     * network blips) must not let a memory-resident HA state steal the paint.
     * Cleared immediately on confirmed queue end so a real HA session is not
     * blocked longer than the window.
     */
    fun wasRecentlyAudible(withinMs: Long = RECENT_AUDIBLE_GUARD_MS): Boolean {
        if (isAudiblyPlayingNow()) {
            lastAudibleElapsedRealtimeMs = SystemClock.elapsedRealtime()
            return true
        }
        val last = lastAudibleElapsedRealtimeMs
        return last != 0L && SystemClock.elapsedRealtime() - last <= withinMs
    }

    /**
     * True when an HA media_player "playing" push is this Sendspin session
     * reflected back (same track title while we are active/audible). Such an
     * echo must not stop local playback — killing it hands the overlay to the
     * stale HA copy of our own state.
     */
    fun isLikelyMirrorOfCurrentTrack(haTitle: String?): Boolean {
        if (haTitle.isNullOrEmpty()) return false
        if (!_isActive.value && !isAudiblyPlayingNow()) return false
        val own = cachedSendspinTitle
        return !own.isNullOrEmpty() && own == haTitle
    }

    /**
     * Title-only mirror check for HA queue-transport bridging (repeat/shuffle).
     * Unlike [isLikelyMirrorOfCurrentTrack], allows paused / sticky Sendspin
     * overlay ownership so MA UI queue changes can still paint while PCM is idle.
     */
    fun isLikelyHaMirrorEntity(haTitle: String?): Boolean {
        if (haTitle.isNullOrEmpty()) return false
        val own = cachedSendspinTitle
        return !own.isNullOrEmpty() && own == haTitle
    }

    /**
     * Frontend-only queue transport paint (HA attrs and/or Mass API `queue_updated`).
     *
     * **Boundary (keep separate from the music protocol):**
     * - May update vinyl repeat/shuffle **UI** only.
     * - Must **not** write [cachedControllerRepeatMode] / [cachedControllerShuffleEnabled]
     *   (those are Sendspin `controller@v1` protocol caches).
     * - Must **not** touch seek, progress, PCM, SHOW, or overlay ownership.
     *
     * Real Sendspin controller deltas still win via [handleControllerState].
     */
    fun applyQueueTransportUiBridge(
        repeatMode: String? = null,
        shuffleEnabled: Boolean? = null,
    ) {
        if (repeatMode == null && shuffleEnabled == null) return
        val uiRepeat = repeatMode?.let { normalizeHaBridgeRepeatMode(it) }
        if (uiRepeat != null) {
            bridgedUiRepeatMode = uiRepeat
        }
        if (shuffleEnabled != null) {
            bridgedUiShuffleEnabled = shuffleEnabled
        }
        val vinylEnabled = isSendspinVinylUiEnabled()
        if (!vinylEnabled) return
        VinylCoverService.updatePlaybackSettings(
            context = context,
            repeatMode = uiRepeat,
            shuffleEnabled = shuffleEnabled,
        )
    }

    /** @deprecated Use [applyQueueTransportUiBridge]. */
    fun applyHaQueueTransportBridge(
        repeatMode: String? = null,
        shuffleEnabled: Boolean? = null,
    ) = applyQueueTransportUiBridge(repeatMode, shuffleEnabled)

    /**
     * Mass API queue clock → vinyl **progress bar** UI only.
     *
     * Exclusive mutex (有 / 没有):
     * - **Active**: MA takes over vinyl progress paint. Sendspin PCM / ticker /
     *   track_progress keep internal 首/活尾 but must not call
     *   [VinylCoverService.updateProgress] (see [pushOverlayProgress] writer gate).
     * - **Cleared / never set**: Sendspin alone owns the bar (unchanged).
     *
     * Does **not** seat 首/活尾, calibrate audible mapper, or seek.
     * Lyrics keep tracking audible via lag vs 活尾.
     */
    fun applyQueueProgressUiBridge(
        positionMs: Long,
        durationMs: Long? = null,
        playing: Boolean? = null,
        capturedAtElapsedRealtime: Long = SystemClock.elapsedRealtime(),
        /** Auto-next / identity change: drop previous track's duration seat first. */
        newTrack: Boolean = false,
    ) {
        if (client?.isForeignGroupBlocked() == true) return
        // Same 1s hard seat as SP / MA seek grid — sub-second MA floats fight the bar.
        var clamped = alignSeekPositionForUpstream(positionMs.coerceAtLeast(0L))
        // Finger seek owns the bar for [LOCAL_SEEK_PIN_HOLD_MS]. MA often ACKs
        // a Queue Flow restart at 0 — do not let that become the interpolator
        // base while the pin is still live.
        val pin = pinnedLocalSeekMs
        if (pin != null &&
            SystemClock.elapsedRealtime() <= pinnedLocalSeekUntilElapsed &&
            pin > PROGRESS_OPEN_LEAD_MAX_MS &&
            clamped <= PROGRESS_OPEN_LEAD_MAX_MS
        ) {
            clamped = alignSeekPositionForUpstream(pin)
        }
        // A standing seek/resume/pause seat owns the bar until matching PCM
        // (or a matching MA ACK on the same second). Stale mid-track
        // queue_time must not become the interpolator base — that is how a
        // peer scrub jumped then snapped back after the pin TTL.
        intentSeatMs()?.let { intent ->
            // A start-of-track seat gets no buffer slack — including on
            // [newTrack]. Opening a queued track leaves the upstream reporting
            // the *previous* position (it never pushes a reset), so accepting
            // anything within a buffer depth of our 0 seat is precisely how
            // the bar resumed mid-track instead of starting the song over.
            // Mid-track the opposite holds: the gap there is real buffer depth.
            val skewBudget = if (intent <= PROGRESS_OPEN_LEAD_MAX_MS) {
                MA_SEEK_SECOND_MS
            } else if (newTrack) {
                // Identity flicker mid-track must still be allowed to reseat.
                // Only a start-of-track intent is defended above.
                return@let
            } else {
                MA_BRIDGE_INTENT_SKEW_MS
            }
            if (kotlin.math.abs(clamped - intent) > skewBudget) {
                Log.d(
                    tag,
                    "drop MA bridge stale ${clamped}ms (intent ${intent}ms)",
                )
                durationMs?.takeIf { it > 0L }?.let {
                    bridgedUiDurationMs = it
                    bridgedDurationSeatDropped = false
                }
                bridgedUiProgressActive = true
                return
            }
        }
        // Pause / mid-track: reject MA open-lead / 0:00 flashes that would yank
        // the bar to the start while SP/peer still hold a real playhead.
        // Skipped while a protocol seek is in flight ([awaitingSeekReseat]) —
        // there a near-zero really is the new position (someone scrubbed to the
        // start from another client). Our own scrub is already protected by the
        // pin substitution above, which rewrites `clamped` off the open lead.
        if (!newTrack && !awaitingSeekReseat) {
            val held = (
                peerPausedPaintHoldMs()
                    ?: pinnedLocalSeekMs?.takeIf {
                        SystemClock.elapsedRealtime() <= pinnedLocalSeekUntilElapsed
                    }
                    ?: progressHeadMs
                    ?: lastGoodOverlayProgressMs.takeIf { it > 0L }
                    ?: bridgedUiProgressMs
                )
            if (
                held != null &&
                held >= PROGRESS_FAKE_ZERO_ANCHOR_MS &&
                clamped <= PROGRESS_OPEN_LEAD_MAX_MS &&
                (held - clamped) > PROGRESS_FAKE_ZERO_DROP_MS
            ) {
                Log.d(
                    tag,
                    "drop MA bridge fake-zero ${clamped}ms (held ${held}ms)",
                )
                durationMs?.takeIf { it > 0L }?.let {
                    bridgedUiDurationMs = it
                    bridgedDurationSeatDropped = false
                }
                bridgedUiProgressActive = true
                return
            }
        }
        if (newTrack) {
            // Clear old total so the bar does not stay clamped to the previous
            // track's end (e.g. 4:04) while the next duration is still arriving.
            bridgedUiDurationMs = null
            bridgedDurationSeatDropped = true
        }
        bridgedUiProgressMs = clamped
        bridgedUiProgressAtElapsedRealtime =
            capturedAtElapsedRealtime.coerceAtMost(SystemClock.elapsedRealtime())
        // MA may promote to playing. Never let MA pause/idle freeze the bar —
        // freeze only follows Sendspin transport / audible (real user pause).
        // An armed seek/resume seat also freezes: interpolating through
        // open-lead is the paired 1–3s resume gap.
        bridgedUiProgressPlaying = if (holdBarUntilMatchingPcm()) {
            false
        } else if (bridgedSilenceHold && !isAudiblyPlayingNow()) {
            // Dead-source hold: a Queue Flow "playing" flash must not restart
            // the wall interpolator. Audible PCM is the only thaw.
            false
        } else if (playing == true) {
            true
        } else {
            _isPlaying.value || isAudiblyPlayingNow()
        }
        durationMs?.takeIf { it > 0L }?.let {
            bridgedUiDurationMs = it
            bridgedDurationSeatDropped = false
        }
        bridgedUiProgressActive = true
        // Prefer peer-aligned / pin overlay when fresh so MA clock cannot fight
        // paired sync; else paint the bridged seat.
        val vinylEnabled = isSendspinVinylUiEnabled()
        if (!vinylEnabled) return
        val barMs = overlayBarProgressMs() ?: clamped
        pushOverlayProgress(
            barMs,
            bridgedEffectiveDurationMs(),
            OverlayProgressWriter.MassApiBridge,
        )
    }

    /** Drop Mass API progress UI seat — Sendspin [displayProgressMs] resumes alone. */
    fun clearQueueProgressUiBridge() {
        if (!bridgedUiProgressActive &&
            bridgedUiProgressMs == null &&
            bridgedUiDurationMs == null &&
            !bridgedDurationSeatDropped
        ) {
            return
        }
        bridgedUiProgressActive = false
        bridgedUiProgressMs = null
        bridgedUiProgressAtElapsedRealtime = 0L
        bridgedUiProgressPlaying = false
        bridgedSilenceHold = false
        deadStreamConfirmed = false
        deadStreamRetryUntilElapsed = 0L
        deadStreamAdvancePending = false
        deadStreamAdvanceCount = 0
        deadStreamAdvanceTitle = null
        bridgedUiDurationMs = null
        bridgedDurationSeatDropped = false
        // Hand vinyl paint back to Sendspin seat (no MA clock left to fight).
        val vinylEnabled = isSendspinVinylUiEnabled()
        if (vinylEnabled && _isActive.value) {
            pushOverlayProgress(
                displayProgressMs(),
                cachedDurationMs,
                OverlayProgressWriter.Sendspin,
            )
        }
    }

    /**
     * Paint title/artist/cover we already know (e.g. Mass Up Next / queue_updated)
     * before protocol metadata arrives. On title change, clears sticky previous
     * credits/cover so a title-only SP packet cannot keep the old artist/art.
     */
    fun applyKnownTrackIdentity(
        title: String,
        artist: String? = null,
        coverUrl: String? = null,
    ) {
        val t = title.trim()
        if (t.isBlank()) return
        val titleChanged = cachedSendspinTitle != null && cachedSendspinTitle != t
        cachedSendspinTitle = t
        if (titleChanged) {
            cachedSendspinArtist = artist?.trim()?.takeIf { it.isNotBlank() }
            cachedSendspinAlbum = null
            hasBinaryArtwork = false
            cachedSendspinArtworkUrl = resolvedArtworkUrl(coverUrl)
        } else {
            if (!artist.isNullOrBlank()) {
                cachedSendspinArtist = artist.trim()
            }
            resolvedArtworkUrl(coverUrl)?.let {
                cachedSendspinArtworkUrl = it
                hasBinaryArtwork = false
            }
        }
        val vinylEnabled = isSendspinVinylUiEnabled()
        if (!vinylEnabled) {
            pokePeerMediaMirror()
            return
        }
        // Prefetch cover even when the shell is still GONE — mid-track sync join
        // often seeds identity before the first audible birth.
        maybeFetchArtworkUrl(cachedSendspinArtworkUrl)
        if (!_isActive.value && !VinylCoverService.isLiveOverlayShellVisible()) {
            pokePeerMediaMirror()
            return
        }
        if (titleChanged) {
            // Real replace: omit playhead so overlay force-zeros. MA's following
            // itemChanged paint then seats the new track; keeping the old 3:50
            // would make that 0:00 look like a glitch and get rejected.
            VinylCoverService.updateMetadata(
                context = context,
                songTitle = cachedSendspinTitle,
                artistName = cachedSendspinArtist,
                albumName = cachedSendspinAlbum,
                isSendspinSource = true,
            )
        } else {
            rememberSameTrackProgressIdentity(t)
            pushIdentityKeepingPlayhead()
        }
        pokePeerMediaMirror()
    }

    /**
     * Backup identity source: only fill blank SP identity fields when the title
     * matches (or SP title is empty). Never overwrite a richer SP packet.
     *
     * @param allowTitleReplace a *different* title means a different track. MA
     *   reads the queue this device owns, so it wins the auto-next race where the
     *   list lands before the metadata packet. A LAN peer is not authoritative
     *   about which track we are on — during a track change one side simply sees
     *   the change first — so for peers a title mismatch is ignored instead.
     *
     * @return whether anything was actually filled.
     */
    fun fillTrackIdentityGaps(
        title: String,
        artist: String? = null,
        coverUrl: String? = null,
        album: String? = null,
        allowTitleReplace: Boolean = true,
    ): Boolean {
        val t = title.trim()
        if (t.isBlank()) return false
        val own = cachedSendspinTitle
        if (!own.isNullOrBlank() && !own.equals(t, ignoreCase = true)) {
            if (!allowTitleReplace) return false
            // Different track — full replace (auto-next race: list arrived first).
            applyKnownTrackIdentity(t, artist, coverUrl)
            return true
        }
        var changed = false
        if (own.isNullOrBlank()) {
            cachedSendspinTitle = t
            changed = true
        }
        if (cachedSendspinArtist.isNullOrBlank() && !artist.isNullOrBlank()) {
            cachedSendspinArtist = artist.trim()
            changed = true
        }
        if (cachedSendspinAlbum.isNullOrBlank() && !album.isNullOrBlank()) {
            cachedSendspinAlbum = album.trim()
            changed = true
        }
        if (!hasBinaryArtwork && cachedSendspinArtworkUrl.isNullOrBlank()) {
            resolvedArtworkUrl(coverUrl)?.let {
                cachedSendspinArtworkUrl = it
                changed = true
            }
        }
        if (!changed) return false
        rememberSameTrackProgressIdentity(t)
        pokePeerMediaMirror()
        if (!isSendspinVinylUiEnabled()) return true
        maybeFetchArtworkUrl(cachedSendspinArtworkUrl)
        if (!_isActive.value && !VinylCoverService.isLiveOverlayShellVisible()) return true
        pushIdentityKeepingPlayhead()
        return true
    }

    /**
     * Same-track identity is now known. Latch [progressIdentityTitle] so the
     * next SP metadata with this title is a sticky merge, not a track-change
     * reset that invents 0.
     */
    private fun rememberSameTrackProgressIdentity(title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        if (progressIdentityTitle.isNullOrBlank() ||
            progressIdentityTitle.equals(t, ignoreCase = true)
        ) {
            progressIdentityTitle = t
        }
    }

    /**
     * Identity-only overlay paint. Always carry the playhead we already hold —
     * omitting [VinylCoverService.updateMetadata] extras is treated as a title
     * change and force-zeros progress and duration (HA already learned this).
     */
    private fun pushIdentityKeepingPlayhead() {
        VinylCoverService.updateMetadata(
            context = context,
            songTitle = cachedSendspinTitle,
            artistName = cachedSendspinArtist,
            albumName = cachedSendspinAlbum,
            currentTimeMs = identityPaintPlayheadMs(),
            totalTimeMs = identityPaintDurationMs(),
            isSendspinSource = true,
        )
    }

    private fun identityPaintPlayheadMs(): Long? =
        paintBarProgressMs()
            ?: overlayBarProgressMs()
            ?: displayProgressMs()
            ?: cachedProgressMs
            ?: lastGoodOverlayProgressMs.takeIf { it > 0L }

    private fun identityPaintDurationMs(): Long? =
        bridgedEffectiveDurationMs() ?: cachedDurationMs

    /**
     * Identity worth mirroring to LAN peers, or null when there is nothing to
     * offer or nothing we may offer.
     *
     * Null while [identityFilledFromPeer]: a device that is itself living off a
     * peer's data must not rebroadcast it. That single rule is what makes the
     * mirror a one-hop, loop-free flow from whoever actually has a source
     * (typically the Ava with Music Assistant enabled) to whoever does not.
     */
    fun peerMediaIdentitySnapshot(): SendspinPeerMediaIdentity? {
        if (identityFilledFromPeer) return null
        val title = cachedSendspinTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return SendspinPeerMediaIdentity(
            title = title,
            artist = cachedSendspinArtist?.trim()?.takeIf { it.isNotEmpty() },
            album = cachedSendspinAlbum?.trim()?.takeIf { it.isNotEmpty() },
            // Prefer a fetchable URL even while we ourselves show binary art —
            // peers cannot decode our artwork channel.
            artworkUrl = cachedSendspinArtworkUrl?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Fill our blanks from a peer proven to be rendering the same stream.
     *
     * Gap-fill only, never a title replace — see [fillTrackIdentityGaps].
     * Playhead is intentionally not mirrored (local audible/MA clocks own it).
     * First-play cover: force a vinyl paint when the peer supplied art we lacked.
     */
    fun applyPeerMediaIdentity(
        title: String,
        artist: String?,
        album: String?,
        artworkUrl: String?,
    ) {
        val hadArt =
            hasBinaryArtwork || !cachedSendspinArtworkUrl.isNullOrBlank()
        val filled = fillTrackIdentityGaps(
            title = title,
            artist = artist,
            coverUrl = artworkUrl,
            album = album,
            allowTitleReplace = false,
        )
        if (filled) {
            identityFilledFromPeer = true
            Log.i(tag, "peer media mirror filled identity gaps for '$title'")
            val gotArt = !hadArt &&
                (hasBinaryArtwork || !cachedSendspinArtworkUrl.isNullOrBlank())
            if (isSendspinVinylUiEnabled()) {
                maybeFetchArtworkUrl(cachedSendspinArtworkUrl)
                if (gotArt || _isPlaying.value || isAudiblyPlayingNow() || _isActive.value) {
                    pushIdentityKeepingPlayhead()
                    refreshVinylFromCacheIfNeeded(ignoreEnabledGate = true)
                }
            }
            return
        }
        if (
            !hasBinaryArtwork &&
            cachedSendspinArtworkUrl.isNullOrBlank() &&
            !artworkUrl.isNullOrBlank()
        ) {
            resolvedArtworkUrl(artworkUrl)?.let { url ->
                cachedSendspinArtworkUrl = url
                identityFilledFromPeer = true
                if (isSendspinVinylUiEnabled()) {
                    maybeFetchArtworkUrl(url)
                    pushIdentityKeepingPlayhead()
                    refreshVinylFromCacheIfNeeded(ignoreEnabledGate = true)
                }
                Log.i(tag, "peer media mirror filled cover for '$title'")
            }
        }
    }

    /** Push identity/cover onto the LAN mirror without waiting for 1Hz. */
    private fun pokePeerMediaMirror() {
        AvaSyncOffsetPeer.requestMediaMetaBurst()
    }

    /** Push playhead (incl. paused freeze) onto the LAN without waiting for 1Hz. */
    private fun pokePeerProgressMirror() {
        AvaSyncOffsetPeer.requestMediaProgressBurst(force = true)
    }

    /**
     * Track-generation key for LAN playhead packets: FNV-1a of the title whose
     * progress we currently hold ([progressIdentityTitle], the generation
     * sentinel — not the MA pre-painted display title). 0 = no metadata yet,
     * which stays tolerant so cold joiners still converge. Same hash the
     * fallback stream key uses, so every device derives the same value from
     * the same server strings.
     */
    private fun currentTrackIdentityKey(): Long {
        val title = (progressIdentityTitle ?: cachedSendspinTitle)?.trim()
        if (title.isNullOrEmpty()) return 0L
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (ch in title) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        // 0 is the "unknown" sentinel on the wire.
        return if (hash == 0L) 1L else hash
    }

    /**
     * Our current playhead timeline assertion for the LAN.
     *
     * Two sources, in priority order:
     *
     * 1. **Live DAC** — once our own PCM is mapped, the position at the DAC's
     *    server timestamp is the most accurate statement available, and it
     *    self-corrects every tick. It carries the current epoch unchanged: it
     *    refines the timeline rather than declaring a new one.
     * 2. **Stored assertion** — a seek target, pause freeze or resume seat,
     *    replayed byte-identically for as long as its epoch stands. This is
     *    what a device with no PCM yet (buffering, paused, mid-seek) publishes,
     *    and what makes a missed seek recoverable: the notification is not a
     *    one-shot event but a standing claim.
     *
     * Flags stay honest — a seat must not borrow STEADY it has not earned, or a
     * cold-joining peer would out-rank a healthy master on the drift path.
     */
    fun peerProgressSnapshot(): SendspinPeerProgressSample? {
        val live = client ?: return null
        // stream/end on seek clears live play_at. A standing UDP seat must
        // still leave the device — that is how the partner hears the tap.
        val streamKey = resolvedStreamKey()
        val locallyPaused = !_isPlaying.value && !isAudiblyPlayingNow()
        val hasLiveAudibleSeat = !progressWaitForAudible && audibleTailMs != null
        val pin = pinnedLocalSeekMs?.takeIf {
            SystemClock.elapsedRealtime() <= pinnedLocalSeekUntilElapsed
        }
        // A live differential beats the stored seat while an armed seek is
        // still waiting for PCM — leftover must not republish the old tail.
        // Once matching PCM landed, the pin is paint-only: keeping it on the
        // wire froze the partner on the tap for the whole 3s hold while our
        // speaker already walked, and the 1–5s catch-up never saw live DAC.
        val useLiveDac =
            !locallyPaused && hasLiveAudibleSeat && !armedAssertionActive()
        if (!useLiveDac) {
            // A stored assertion is authoritative for its epoch, with one
            // exception: if it claims motion or arming while the transport is
            // actually paused, some path changed state without claiming an
            // epoch. Trust the transport over a claim it has outlived.
            val stored = playheadAnchorMs?.takeUnless {
                locallyPaused && (playheadAnchorArmed || playheadAnchorSpeed > 0)
            }
            // Live tail outranks a consumed seek target. Replaying the origin
            // after PCM has already moved past it is what put a stalled device
            // back at the scrub point while its partner sat 30s later.
            val progress = pin
                ?: audibleTailMs?.takeIf { !progressWaitForAudible }
                ?: stored
                ?: progressHeadMs
                ?: cachedProgressMs
                ?: lastGoodOverlayProgressMs.takeIf { it > 0L }
                ?: return null
            if (progress < 0L) return null
            // A cache value past our track end is a leftover from another
            // timeline (previous longer track caught mid-change). Broadcasting
            // its clamp is how the partner's bar flashed to the last second —
            // stay silent this tick and let the partner's healthy seat win.
            val seatedProgress = seatWithinTrackOrNull(progress) ?: return null
            // Replay a stored assertion verbatim, speed included. It may be a
            // peer's moving differential we adopted before our own PCM landed,
            // and flattening it here would tell a third device we are paused.
            val speed = if (stored != null) playheadAnchorSpeed else 0
            val armed = if (stored != null) armedAssertionActive() else !locallyPaused
            // Only a stored assertion owns a frozen anchor time. A fallback
            // position is true *now*, so it must be stamped now.
            val anchorTs = if (stored != null) {
                playheadAnchorServerTsUs
            } else {
                localAnchorServerTsUs()
            }
            val base = when {
                speed > 0 -> SendspinPeerProgressSample.FLAG_ABSOLUTE
                armed -> SendspinPeerProgressSample.FLAG_ARMED or
                    SendspinPeerProgressSample.FLAG_ABSOLUTE
                else -> SendspinPeerProgressSample.FLAG_PAUSED
            }
            // Standing request for a controller-capable partner to run the
            // transport. Only meaningful on the stored assertion — a fallback
            // position was never a command.
            val flags = if (stored != null && playheadAnchorTransportPending) {
                base or SendspinPeerProgressSample.FLAG_TRANSPORT_PENDING
            } else {
                base
            }
            return SendspinPeerProgressSample(
                streamKey = streamKey,
                progressMs = seatedProgress,
                atServerTsUs = anchorTs,
                flags = flags,
                playbackSpeed = speed,
                epoch = playheadEpoch,
                originId = resolvedPlayheadOrigin(),
                trackKey = currentTrackIdentityKey(),
            )
        }
        val atServerTsUs = live.audibleNowServerTsUs() ?: return null
        // Same-domain pair: atServerTsUs is the presentation instant ("the
        // sample leaving the DAC right now"), so the advertised progress must
        // be the presentation progress too. Broadcasting the write-clock tail
        // here made peers adopt a position 1-2s ahead of what our own bar
        // showed. Fall back to the tail only when the presentation clock has
        // no anchor yet.
        val progress = live.presentationTrackProgressMs()?.let { clampToDuration(it) }
            ?: audibleTailMs
            ?: displayProgressMs()
            ?: return null
        if (progress < 0L) return null
        var flags = 0
        if (live.audibleNowDacTrusted()) {
            flags = flags or SendspinPeerProgressSample.FLAG_DAC_TRUSTED
        }
        val beacon = live.buildPeerBeaconSnapshot()
        if (beacon != null && beacon.flags and SendspinPeerBeacon.FLAG_STEADY != 0) {
            flags = flags or SendspinPeerProgressSample.FLAG_STEADY
        }
        val speed = cachedPlaybackSpeed.takeIf { it > 0 } ?: 1000
        return SendspinPeerProgressSample(
            streamKey = streamKey,
            progressMs = progress,
            atServerTsUs = atServerTsUs,
            flags = flags,
            playbackSpeed = speed,
            epoch = playheadEpoch,
            originId = resolvedPlayheadOrigin(),
            trackKey = currentTrackIdentityKey(),
        )
    }

    /**
     * Apply a same-stream peer playhead with continuous auto-calibration.
     *
     * Arbitration is by epoch, not by heuristics:
     *
     * - **newer** epoch: a deliberate change we have not seen. Adopt it whoever
     *   sent it and whatever we were doing — newest user intent wins. This is
     *   the jump notification, and because the sender keeps asserting it, one
     *   lost datagram no longer loses the seek.
     * - **equal** epoch: same timeline. Pause/seek/resume seats from a different
     *   origin are concurrent claims (lower id wins). Live DAC samples only
     *   refine drift. Re-adopting a live sample here is what used to make two
     *   devices trade positions forever.
     * - **older** epoch: the sender missed a change. Answer with ours so it
     *   catches up, and never obey it. Epoch 0 live samples are legacy senders
     *   and may still contribute drift.
     */
    fun applyPeerProgressSample(
        sample: SendspinPeerProgressSample,
        peerOutranksLocal: Boolean,
    ) {
        val live = client ?: return
        if (sample.progressMs < 0L) return
        val ourKey = live.currentStreamKey()
        if (ourKey != null && ourKey != 0L) lastKnownStreamKey = ourKey
        val deliberateSeat = sample.isPaused || sample.isAbsoluteSeat
        // Same stream only — including pause/seek. Crossing keys let an
        // unpaired LAN box adopt our transport. Pairing is enforced on the
        // UDP receive path; this is the second door.
        if (ourKey == null || ourKey == 0L ||
            sample.streamKey == 0L ||
            sample.streamKey != ourKey
        ) {
            return
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        lastPeerProgressSeenElapsed = nowElapsed
        peerRawOutranksLocal = peerOutranksLocal
        noteSeenPlayheadEpoch(sample.epoch)

        // Third door: track generation. The stream key survives seeks *and*
        // track changes, so around auto-next the slower device's previous-song
        // samples still carry a valid stream key — and a same-epoch live
        // "refinement" from another song dragged a freshly opened 0:00 back to
        // the old position and froze it there. Positions from another track
        // are not comparable at any epoch. Pairing memory and the Lamport
        // clock (above) are still fed; only the position influence stops.
        // Unknown (0) stays tolerant: cold joiners and legacy builds carry no
        // title yet and must still converge.
        val ourTrackKey = currentTrackIdentityKey()
        if (sample.trackKey != 0L && ourTrackKey != 0L && sample.trackKey != ourTrackKey) {
            return
        }

        // Echo guard, seats only. Partners forward assertions verbatim (by
        // design, for 3+ device chains), so a seat we authored comes back
        // carrying our own origin id. Re-adopting it re-arms the very wait we
        // are trying to leave: the adopt path re-raises the wait-for-PCM flag,
        // which is what kept a starved seek alive indefinitely across the
        // pair. A *live differential* from the same origin is the opposite —
        // the partner adopted our timeline and is reporting its own DAC, which
        // is real information and must still refine drift.
        if (deliberateSeat &&
            sample.originId.isNotEmpty() &&
            sample.originId == localOriginId()
        ) {
            if (sample.epoch < playheadEpoch) answerStalePlayhead(nowElapsed)
            return
        }

        val legacyLive =
            sample.epoch == 0L && !sample.isPaused && !sample.isAbsoluteSeat

        if (sample.epoch < playheadEpoch) {
            // Legacy senders have no epoch — they can still contribute live
            // drift, but a stale pause/seek must not override a newer claim.
            // Epoch 0 is a cold-joining follower: answer with our live seat
            // rather than letting it invent 0 / duration-tail.
            if (!legacyLive) {
                answerStalePlayhead(nowElapsed)
                return
            }
        } else if (isConcurrentPlayheadClaim(sample)) {
            // Concurrent seeks/pauses/resumes: lower origin id wins so N
            // devices pick the same survivor without negotiating. Live DAC
            // samples never take this path — they refine, they do not reseat.
            // The tiebreak only converges if the loser actually yields, so a
            // local pin must not override it either: both ends hold a pin at
            // this point (that is why both have a claim), so letting the pin
            // win means both keep their own position forever.
            if (!originOutranksLocal(sample.originId)) {
                answerStalePlayhead(nowElapsed)
                return
            }
            if (rejectStalePeerSeatOnNewTrack(sample, nowElapsed)) return
            adoptPeerTimeline(sample)
            return
        } else if (sample.epoch > playheadEpoch) {
            // No local-pin veto here, deliberately. Epochs are minted as
            // max(ours, highest seen) + 1, so a strictly greater one was
            // authored *after* seeing our claim: it is the newer finger by
            // construction. Vetoing it because our own scrub pin was still
            // warm made both devices refuse each other for the pin's lifetime
            // — each holding its own tap, neither converging, which is the
            // paired "seek on both ends and the bars split" report. Track
            // identity is a separate axis and still vetoes below: a position
            // from another song is not comparable, however new it is.
            if (rejectStalePeerSeatOnNewTrack(sample, nowElapsed)) return
            adoptPeerTimeline(sample)
            return
        }

        // Same timeline from here on: refinement only.
        if (pinnedLocalSeekMs != null && nowElapsed <= pinnedLocalSeekUntilElapsed) {
            return
        }

        if (sample.isPaused) {
            if (stalePeerSeatOnNewTrack(sample.progressMs)) {
                Log.i(tag, "drop stale peer pause ${sample.progressMs}ms on new track")
                answerStalePlayhead(nowElapsed)
                return
            }
            // A freeze past our track end is a leftover from another timeline
            // (previous longer track). Adopting its clamp seated both bars on
            // the exact last second — answer with our real seat instead.
            val frozen = seatWithinTrackOrNull(sample.progressMs)
            if (frozen == null) {
                Log.i(
                    tag,
                    "drop peer pause ${sample.progressMs}ms past " +
                        "duration ${cachedDurationMs}ms",
                )
                answerStalePlayhead(nowElapsed)
                return
            }
            notePeerRawProgress(frozen, advancing = false, speed = 0)
            if (inPlayResumeGrace()) return
            adoptPeerFrozenProgress(frozen)
            return
        }

        if (sample.isAbsoluteSeat) {
            val projected = projectPeerAnchor(sample) ?: return
            if (stalePeerSeatOnNewTrack(projected)) {
                Log.i(tag, "drop stale peer seat ${projected}ms on new track")
                answerStalePlayhead(nowElapsed)
                return
            }
            // Replaying a seat we already share. Only land it while we still
            // have no PCM of our own — rebasing a live mapper onto the original
            // seek origin after the pin TTL would yank the bar backwards.
            if (alreadyHoldingSeekSeat(projected)) {
                notePeerRawProgress(
                    projected,
                    advancing = sample.playbackSpeed > 0,
                    speed = sample.playbackSpeed,
                )
                return
            }
            if (progressWaitForAudible || audibleTailMs == null) {
                notePeerRawProgress(
                    projected,
                    advancing = sample.playbackSpeed > 0,
                    speed = sample.playbackSpeed,
                )
                adoptPeerAbsoluteProgress(
                    seated = projected,
                    playing = _isPlaying.value || isAudiblyPlayingNow(),
                    peerSpeed = sample.playbackSpeed,
                )
            }
            return
        }

        val speed = sample.playbackSpeed.takeIf { it > 0 } ?: 1000
        val seated = projectPeerAnchor(sample) ?: return
        // Record before the local-playing gate: a paused / cold-started device
        // must still learn where the group is, or pickResumeSeed() degenerates
        // to 0 and one tap of play drags every playing partner back to the
        // track start.
        notePeerRawProgress(seated, advancing = true, speed = speed)
        maybeMarkSeatFulfilledByAuthor(sample, seated)
        if (!_isPlaying.value && !isAudiblyPlayingNow()) return

        val mineTs = live.audibleNowServerTsUs()
        val now = nowElapsed
        val oursPresented = live.presentationTrackProgressMs()?.let { clampToDuration(it) }
        val oursBefore = oursPresented
            ?: audibleTailMs
            ?: displayProgressMs()
            ?: progressHeadMs
            ?: cachedProgressMs
        val skew = if (oursBefore != null) {
            kotlin.math.abs(oursBefore - seated)
        } else {
            PEER_PROGRESS_HARD_SKEW_MS
        }
        // Speaker-to-speaker only. Write-tail vs their DAC is the standing
        // 1–2s buffer lead and must not look like a real split.
        val behindPeerMs = if (oursPresented != null) seated - oursPresented else 0L
        val peerSteady = sample.flags and SendspinPeerProgressSample.FLAG_STEADY != 0
        val peerTrusted = sample.flags and SendspinPeerProgressSample.FLAG_DAC_TRUSTED != 0
        val localSteady = localPlaybackSteady()
        // Same rule the audio layer already applies in
        // [SendspinClient.onPeerBeacon]: never chase a peer clock that is
        // weaker evidence than our own. FLAG_STEADY only reports that the time
        // filter converged — it stays set while a starved AudioTrack replays
        // the same frames, and a starved device's presentation clock crawls
        // because played-frame counters only advance on frames the DAC really
        // consumed. Without this, a partner stuttering after a burst of seeks
        // published a slow-but-STEADY position and we rebased our own mapper
        // *backwards* onto it, twice, until the bar sat seconds behind our own
        // speaker and froze a pause there.
        val peerClockOutranksOurs = peerTrusted || !live.audibleNowDacTrusted()
        // Never follow merely because *we* wait for PCM after our seek — that
        // re-imported the peer's pre-scrub playhead.
        val coldJoinNoSeat =
            (progressWaitForAudible || audibleTailMs == null) &&
                progressHeadMs == null &&
                pinnedLocalSeekMs == null
        val recentJoin =
            lastStreamAttachElapsed > 0L &&
                now - lastStreamAttachElapsed < PEER_PROGRESS_JOIN_GRACE_MS
        // Symmetric: each side only ever steps *forward* onto the other
        // speaker. The leader does not rewind. Both keep doing this every
        // cooldown so a 1–5s post-seek split closes instead of sitting
        // there because both were STEADY and under the old 4s open-lead gate.
        val urgentCatchUp =
            !holdBarUntilMatchingPcm() &&
                oursPresented != null &&
                behindPeerMs >= PEER_PROGRESS_HARD_SKEW_MS &&
                behindPeerMs <= PEER_URGENT_CATCHUP_MAX_MS &&
                (peerSteady || peerTrusted) &&
                peerClockOutranksOurs
        val followPeer = when {
            urgentCatchUp -> true
            coldJoinNoSeat -> true
            (peerSteady || peerTrusted) && !localSteady -> true
            peerSteady && localSteady && skew > PEER_URGENT_CATCHUP_MAX_MS ->
                peerOutranksLocal && peerClockOutranksOurs
            else -> false
        }
        if (!followPeer) return

        val joining = coldJoinNoSeat || (recentJoin && !localSteady)
        val cooling = lastPeerProgressCalibrateElapsed > 0L &&
            now - lastPeerProgressCalibrateElapsed < PEER_PROGRESS_CALIBRATE_COOLDOWN_MS
        val skewNeedsMapper =
            urgentCatchUp ||
                skew >= PEER_PROGRESS_HARD_SKEW_MS ||
                (joining && skew >= PEER_PROGRESS_SOFT_SKEW_MS) ||
                ((peerSteady || peerTrusted) && !localSteady && skew >= PEER_PROGRESS_SOFT_SKEW_MS)
        // Rebasing the mapper moves the whole local timeline, so it needs the
        // stronger clock as well as the wider skew.
        val hardCalibrate = skewNeedsMapper &&
            mineTs != null &&
            !cooling &&
            peerClockOutranksOurs &&
            (peerSteady || peerTrusted || joining || urgentCatchUp)

        // Live DAC is speaker-sync, not the vinyl clock. Do not take the
        // overlay paint lock — SP-only pairing used to chase this and fight
        // the UDP seat. Mapper calibrate below still runs (including when
        // the MA bridge owns vinyl — presentation is the bar).
        if (!bridgedUiProgressActive) {
            if (!pairedOnThisStream() &&
                (skew >= PEER_PROGRESS_SOFT_SKEW_MS || oursBefore == null)
            ) {
                progressHeadMs = seated
                cachedProgressMs = seated
                lastGoodOverlayProgressMs = seated
                progressHeadElapsedRealtime = now
                if (cachedPlaybackSpeed <= 0) cachedPlaybackSpeed = speed
                if (hardCalibrate || progressWaitForAudible || audibleTailMs == null) {
                    audibleTailMs = seated
                    progressWaitForAudible = false
                }
            }
        }
        if (!hardCalibrate) return
        lastPeerProgressCalibrateElapsed = now
        trustedUpstreamProgressSeedMs = seated
        Log.i(
            tag,
            "peer progress auto-calibrate ${oursBefore}ms -> ${seated}ms " +
                "(skew=${skew}ms behind=${behindPeerMs}ms urgent=$urgentCatchUp " +
                "steady=$peerSteady trusted=$peerTrusted " +
                "localSteady=$localSteady joining=$joining)",
        )
        live.calibrateAudibleProgress(
            progressMs = seated,
            metadataTimestampUs = mineTs,
            playbackSpeed = speed,
            durationMs = cachedDurationMs,
        )
        pokePeerProgressMirror()
    }

    /** Our own beacon id — the tiebreak key for concurrent epoch claims. */
    private fun localOriginId(): String =
        com.example.ava.voice.AvaVoiceDiscovery.localId()

    /** Who authored the epoch we currently hold (empty origin means us). */
    private fun currentOriginId(): String =
        playheadEpochOrigin.ifEmpty { localOriginId() }

    /** Origin written on the wire — never leave it blank for receivers. */
    private fun resolvedPlayheadOrigin(): String = currentOriginId()

    /**
     * Concurrent pause/seek/resume at the same epoch: lower beacon id wins.
     * Live DAC samples are excluded — those refine drift, they do not reseat.
     * Epoch 0 is "no claim yet" and also excluded, or two devices that have
     * never seeked would adopt each other's every tick.
     */
    private fun isConcurrentPlayheadClaim(sample: SendspinPeerProgressSample): Boolean =
        sample.epoch == playheadEpoch &&
            sample.epoch > 0L &&
            sample.originId.isNotEmpty() &&
            sample.originId != currentOriginId() &&
            (sample.isPaused || sample.isAbsoluteSeat)

    private fun originOutranksLocal(originId: String): Boolean {
        val mine = currentOriginId()
        if (originId.isEmpty()) return false
        if (mine.isEmpty()) return true
        return originId < mine
    }

    private fun answerStalePlayhead(nowElapsed: Long) {
        if (nowElapsed - lastStaleEpochAnswerElapsed < STALE_EPOCH_ANSWER_MIN_GAP_MS) return
        lastStaleEpochAnswerElapsed = nowElapsed
        pokePeerProgressMirror()
    }

    /**
     * A peer declared a newer playhead timeline: replace ours wholesale.
     *
     * Everything local that could argue with it is dropped first — a stale seek
     * pin, a paint lock, the adopted-seat dedupe — because the whole point of a
     * newer epoch is that it supersedes whatever we were defending. Then the
     * assertion is stored verbatim so we re-broadcast the sender's exact
     * numbers, and a third device sees one timeline rather than two projections
     * of it.
     */
    private fun adoptPeerTimeline(sample: SendspinPeerProgressSample) {
        val projected = projectPeerAnchor(sample) ?: return
        Log.i(
            tag,
            "peer timeline epoch ${playheadEpoch}->${sample.epoch} " +
                "origin=${sample.originId} seat=${projected}ms " +
                "paused=${sample.isPaused} armed=${sample.isArmed}",
        )
        val before = audibleTailMs ?: progressHeadMs ?: displayProgressMs()
        lastAdoptedPeerAbsoluteMs = null
        lastAdoptedPeerAbsoluteElapsed = 0L
        adoptPlayheadAssertion(sample)
        lastAdoptedPeerTimelineElapsed = SystemClock.elapsedRealtime()
        // An armed seat is flat until PCM lands, so it must not be extrapolated
        // — only a positive speed means the position is actually moving.
        notePeerRawProgress(
            projected,
            advancing = sample.playbackSpeed > 0,
            speed = sample.playbackSpeed,
        )
        if (sample.isPaused) {
            pinnedLocalSeekMs = null
            pinnedLocalSeekUntilElapsed = 0L
            adoptPeerFrozenProgress(projected)
        } else {
            // Pin even when we skip a second controller seek: otherwise stale
            // MA queue_time paints over the UDP seat the moment it arrives.
            pinLocalSeekTarget(projected)
            awaitingSeekReseat = true
            reanchorProgressClock(projected)
            startPlayingSeekWalkClock()
            if (!_isPlaying.value) {
                _isPlaying.value = true
                externalPlayingCallback?.invoke(true)
                pushOverlayPlayingState(true, immediate = true)
                reconcileProgressOverlayTicker(true)
            }
            adoptPeerAbsoluteProgress(
                seated = projected,
                playing = true,
                peerSpeed = sample.playbackSpeed,
            )
        }
        applyPeerTransport(sample, projected, beforePlayheadMs = before)
    }

    /**
     * The tap happened on a partner — actually pause / play / seek here too.
     * Progress paint alone left the master playing through a passive pause and
     * sitting on old PCM through a passive scrub (the 1–3s catch-up).
     *
     * Goes through [SendspinClient.sendMediaCommand] so we do **not** re-enter
     * [sendMediaCommand] and mint a new epoch. The peer's epoch is the claim.
     */
    private fun applyPeerTransport(
        sample: SendspinPeerProgressSample,
        seated: Long,
        beforePlayheadMs: Long?,
    ) {
        val live = client ?: return
        // The author explicitly asked us to run the transport: its own
        // controller command cannot be honored (role revoked / seek missing
        // from its session's supported_commands), so "the server already has
        // the tapper's command" does not hold — skipping here is exactly the
        // follower's "seek never sticks, master drags it back".
        val executeForAuthor = sample.transportPending
        // Same play_at: the server already has the tapper's controller command
        // and will stream/clear every member. Re-issuing seek/play/pause here
        // mints a second MA ACK that fights the seat we just painted.
        if (!executeForAuthor &&
            (sharesServerStream(sample) || live.inRecentStreamSplice())
        ) {
            if (!sample.isPaused) {
                Log.i(
                    tag,
                    "peer transport skipped: " +
                        if (sharesServerStream(sample)) {
                            "shared streamKey (server owns it)"
                        } else {
                            "recent stream splice (already clearing)"
                        },
                )
                return
            }
            // Extra pause is idempotent; still apply locally in case this
            // session's controller role never reached the server.
        }
        if (executeForAuthor) {
            Log.i(tag, "peer transport: executing for author ${sample.originId} @${seated}ms")
        }
        val target = alignSeekPositionForUpstream(seated)
        val jumped = beforePlayheadMs == null ||
            kotlin.math.abs(beforePlayheadMs - target) > MA_SEEK_SECOND_MS
        if (sample.isPaused) {
            // A paused scrub the author could not seek upstream: move the
            // queue before freezing, or the eventual resume replays the
            // pre-scrub second.
            if (executeForAuthor && jumped) {
                live.sendMediaCommand("seek", positionMs = target)
            }
            live.sendMediaCommand("pause")
            live.pauseAudioOutput()
            if (_isPlaying.value) {
                _isPlaying.value = false
                externalPlayingCallback?.invoke(false)
            }
            pushOverlayPlayingState(false, immediate = true)
            reconcileProgressOverlayTicker(false)
            markPeerTransportDelivered(executeForAuthor, live)
            return
        }
        if (jumped) {
            pinLocalSeekTarget(target)
            awaitingSeekReseat = true
            reanchorProgressClock(target)
            live.sendMediaCommand("seek", positionMs = target)
        }
        if (!_isPlaying.value) {
            playResumeGraceUntilElapsed =
                SystemClock.elapsedRealtime() + PEER_PROGRESS_PLAY_RESUME_GRACE_MS
            live.sendMediaCommand("play")
            _isPlaying.value = true
            externalPlayingCallback?.invoke(true)
            cachedPlaybackSpeed = cachedPlaybackSpeed.takeIf { it > 0 } ?: 1000
            if (bridgedUiProgressActive) {
                bridgedUiProgressMs = target
                bridgedUiProgressAtElapsedRealtime = SystemClock.elapsedRealtime()
                bridgedUiProgressPlaying = false
            }
            pushOverlayPlayingState(true, immediate = true)
            reconcileProgressOverlayTicker(true)
        }
        markPeerTransportDelivered(executeForAuthor, live)
    }

    /**
     * Stop forwarding the author's transport request once a session that can
     * actually deliver commands has acted on it. A device that cannot deliver
     * (same role problem as the author) keeps the flag on its re-broadcast so
     * a third, capable device can still execute.
     */
    private fun markPeerTransportDelivered(executeForAuthor: Boolean, live: SendspinClient) {
        if (!executeForAuthor) return
        if (live.supportsControllerCommand("seek")) {
            playheadAnchorTransportPending = false
        }
    }

    /**
     * Forget every peer-derived playhead lock (paint + adopted-seat dedupe) so
     * the next peer sample is re-applied from scratch. Used wherever a local
     * transport change makes us the authority again.
     */
    private fun clearPeerProgressLock() {
        clearPeerPaintLock()
        lastAdoptedPeerAbsoluteMs = null
        lastAdoptedPeerAbsoluteElapsed = 0L
        // Quantisation input goes too: after a local transport change our own
        // playhead is the reference until a fresh peer sample arrives.
        peerRawProgressMs = null
        peerRawAtElapsedRealtime = 0L
        peerRawAdvancing = false
        peerRawOutranksLocal = false
    }

    /**
     * Drop only the paint lock. The quantisation input ([peerRawProgressMs]) is
     * refreshed by every peer sample, not just followed ones, and carries its
     * own TTL — an expiring paint lock must not blind the second snap.
     */
    private fun clearPeerPaintLock() {
        peerPaintProgressMs = null
        peerPaintAtElapsedRealtime = 0L
        peerPaintPlaying = false
        peerPaintFromSteady = false
        peerPaintAbsoluteSeat = false
        peerPaintFromRemote = false
    }

    private fun notePeerRawProgress(progressMs: Long, advancing: Boolean, speed: Int) {
        peerRawProgressMs = progressMs
        peerRawAtElapsedRealtime = SystemClock.elapsedRealtime()
        peerRawAdvancing = advancing
        peerRawSpeed = speed.takeIf { it > 0 } ?: 1000
    }

    /**
     * A live steady sample from the seat's own author, inside the seat's
     * acceptance window, is the partner's ear confirming the seek/resume
     * actually started sounding. Note it so the display can walk the shared
     * timeline instead of freezing on the seat until *our* PCM lands — the
     * paired follower's AudioTrack rebuild takes seconds and the bar sitting
     * dead through it is the visible "can't keep up" gap. Everything else
     * (mapper reseat, MA-bridge rejection, starvation watchdog) still waits
     * for local PCM: this is proof of the music, not a substitute for it.
     */
    private fun maybeMarkSeatFulfilledByAuthor(
        sample: SendspinPeerProgressSample,
        seated: Long,
    ) {
        if (!armedAssertionActive() || playheadAnchorSpeed <= 0) return
        val anchor = playheadAnchorMs ?: return
        if (sample.originId.isEmpty() || sample.originId != currentOriginId()) return
        if (sample.flags and SendspinPeerProgressSample.FLAG_STEADY == 0) return
        val lead = seated - anchor
        if (lead < -MA_SEEK_SECOND_MS || lead > PROGRESS_OPEN_LEAD_MAX_MS) return
        if (peerSeatFulfilledAtElapsed == 0L) {
            Log.i(tag, "seat ${anchor}ms fulfilled by author @${seated}ms (walk peer timeline)")
        }
        peerSeatFulfilledAtElapsed = SystemClock.elapsedRealtime()
    }

    /**
     * Last recorded peer position, aged forward while the peer was advancing
     * and bounded by the paired-memory TTL. Resume seeding from a raw value
     * that stopped aging would land seconds behind a still-playing partner.
     */
    private fun projectedPeerRawProgressMs(): Long? {
        val base = peerRawProgressMs ?: return null
        val at = peerRawAtElapsedRealtime
        if (at <= 0L) return null
        val ageMs = SystemClock.elapsedRealtime() - at
        if (ageMs > PEER_PROGRESS_PAIRED_MEMORY_MS) return null
        if (!peerRawAdvancing) return base
        return base + ageMs * peerRawSpeed / 1000L
    }

    /**
     * A partner is on this stream right now. Progress packets keep flowing
     * through a pause, so this stays true while paired and never turns on for
     * solo playback.
     */
    private fun pairedOnThisStream(): Boolean =
        lastPeerProgressSeenElapsed > 0L &&
            SystemClock.elapsedRealtime() - lastPeerProgressSeenElapsed <
            PEER_PROGRESS_PAIRED_MEMORY_MS

    /**
     * Same Sendspin `play_at` as the peer sample. Grouped players share that
     * timeline, so the server already owns pause/play/seek; a second controller
     * command from the follower fights the tapper's ACK.
     */
    private fun sharesServerStream(sample: SendspinPeerProgressSample): Boolean {
        val ourKey = resolvedStreamKey()
        if (ourKey == 0L || sample.streamKey == 0L) return false
        return sample.streamKey == ourKey
    }

    /**
     * Seek / resume seat that has not been consumed by matching PCM. The MA
     * interpolator and leftover DAC writes must not run ahead of it.
     */
    private fun holdBarUntilMatchingPcm(): Boolean =
        progressWaitForAudible || armedAssertionActive()

    /**
     * Whether this mapped write is on the standing seat.
     *
     * Must compare the PCM itself, never a pin/head copy used for painting.
     * The pin is the bar; the write is the proof. Mixing them released the
     * assertion on leftover drain and let live UDP yank the partner.
     */
    private fun audiblePcmMatchesSeat(pcmMs: Long, seatMs: Long?): Boolean {
        if (seatMs == null) return true
        // 1–5s is buffer / pair split, not leftover from another tap.
        // 2.5s used to drop the first post-seek writes (open-lead 3–4s) and
        // the wait never cleared — bar frozen, live DAC never published.
        return kotlin.math.abs(pcmMs - seatMs) <= PEER_URGENT_CATCHUP_MAX_MS
    }

    /**
     * Start-of-track hard seat still waiting for matching PCM.
     *
     * Uses the raw armed flag (not the 10s paint TTL) so a queued 0 is still
     * defended after the overlay stops pinning the tap. Leftover last-song
     * metadata and cold-join ≪-seed must not rewrite this into a mid-track
     * seed — that is how PCM from 0 never released the bar.
     */
    private fun startOfTrackDefenseMs(): Long? {
        pinnedLocalSeekMs?.takeIf {
            SystemClock.elapsedRealtime() <= pinnedLocalSeekUntilElapsed &&
                it <= PROGRESS_OPEN_LEAD_MAX_MS
        }?.let { return clampToDuration(it) }
        if (playheadAnchorArmed) {
            playheadAnchorMs?.takeIf { it <= PROGRESS_OPEN_LEAD_MAX_MS }
                ?.let { return clampToDuration(it) }
        }
        if (progressWaitForAudible) {
            trustedUpstreamProgressSeedMs?.takeIf { it <= PROGRESS_OPEN_LEAD_MAX_MS }
                ?.let { return clampToDuration(it) }
        }
        return null
    }

    /**
     * Is the standing armed assertion still worth defending?
     *
     * An armed seat is an *intent* — a seek or resume whose PCM has not
     * arrived yet. Every consumer used to read [playheadAnchorArmed] raw,
     * which gave the intent unlimited authority: when the server never
     * actually moved to the requested position, the seat rejected upstream
     * progress, the MA bridge, and the peer's real playhead forever (the
     * bar froze on the tapped second while audio ran on elsewhere).
     * The seek pin and the adopted-seat lock already expire; this makes the
     * armed assertion expire on the same principle.
     */
    private fun armedAssertionActive(): Boolean {
        if (!playheadAnchorArmed) return false
        val armedAt = playheadAnchorArmedAtElapsed
        if (armedAt <= 0L) return false
        return SystemClock.elapsedRealtime() - armedAt <= PLAYHEAD_ARMED_PIN_MAX_MS
    }

    /**
     * PCM is arriving but never lands on the standing seat.
     *
     * The seat is what we *asked* for (a seek); the PCM is what the server is
     * actually sending, and the server owns the timeline. Rejecting every
     * chunk while waiting for a position the server never moved to is a
     * livelock: the wait flag never clears, so the seat never retires, so the
     * next chunk is rejected too — the bar sat on the tapped second while
     * audio played on minutes away.
     *
     * @return true once the budget is spent and the caller must stop
     *   defending the seat. Both a minimum drop count and a wall-clock window
     *   are required so a couple of genuinely stale chunks across a real seek
     *   do not trip it.
     *
     * The budget is keyed to the *intent*, the same way the armed TTL is:
     * it survives only while (a) the drops keep indicting the same seat —
     * a fresh seek opens a fresh budget instead of inheriting drops charged
     * against its predecessor — and (b) the drops are contiguous — a gap
     * longer than the budget (pause, network stall) ends the episode, so a
     * window opened before a ten-minute pause cannot come back pre-spent
     * and shoot down a legitimate resume on its first few drain chunks.
     * Keying here rather than clearing at every seek/pause call site means
     * new reseat paths are covered by construction, not by discipline.
     * Same-seat uses [PROGRESS_SEEK_THRESHOLD_MS]: a peer re-adopting one
     * stuck assertion re-projects it with tens-of-ms wobble, which must not
     * read as a new intent or the livelock watchdog would never fire.
     */
    private fun seatStarvationSpent(nowElapsed: Long, seatMs: Long?): Boolean {
        val priorSeat = seatStarvationSeatMs
        val sameSeat = seatMs != null && priorSeat != null &&
            kotlin.math.abs(seatMs - priorSeat) <= PROGRESS_SEEK_THRESHOLD_MS
        val contiguous = seatStarvationSinceElapsed != 0L &&
            nowElapsed - seatStarvationLastDropElapsed <= SEAT_STARVATION_GIVE_UP_MS
        if (!sameSeat || !contiguous) {
            seatStarvationSinceElapsed = nowElapsed
            seatStarvationLastDropElapsed = nowElapsed
            seatStarvationSeatMs = seatMs
            seatStarvationDrops = 1
            return false
        }
        seatStarvationLastDropElapsed = nowElapsed
        seatStarvationDrops++
        return seatStarvationDrops >= SEAT_STARVATION_MIN_DROPS &&
            nowElapsed - seatStarvationSinceElapsed >= SEAT_STARVATION_GIVE_UP_MS
    }

    private fun clearSeatStarvation() {
        if (seatStarvationSinceElapsed == 0L && seatStarvationDrops == 0) return
        seatStarvationSinceElapsed = 0L
        seatStarvationLastDropElapsed = 0L
        seatStarvationSeatMs = null
        seatStarvationDrops = 0
    }

    /**
     * Give up on an unreachable seat and adopt the timeline the PCM actually
     * carries. A fresh epoch goes out with it: peers that adopted our stuck
     * intent are sitting on the same dead position, and only a higher epoch
     * pulls them out instead of letting them pull us back in.
     */
    private fun abandonStarvedSeat(adoptedMs: Long, seatMs: Long?) {
        Log.w(
            tag,
            "seat starvation: dropping seat ${seatMs}ms after $seatStarvationDrops " +
                "rejected chunks; adopting audible ${adoptedMs}ms",
        )
        clearSeatStarvation()
        clearDeadStreamHold()
        retirePlayheadAssertion()
        pinnedLocalSeekMs = null
        pinnedLocalSeekUntilElapsed = 0L
        awaitingSeekReseat = false
        pendingTrackIdentityReseat = false
        progressWaitForAudible = false
        trustedUpstreamProgressSeedMs = null
        clearPeerProgressLock()
        audibleTailMs = adoptedMs
        progressHeadMs = adoptedMs
        progressHeadElapsedRealtime = SystemClock.elapsedRealtime()
        cachedProgressMs = adoptedMs
        lastGoodOverlayProgressMs = adoptedMs
        if (cachedPlaybackSpeed <= 0) cachedPlaybackSpeed = 1000
        client?.reseatAudibleOrigin(
            progressMs = adoptedMs,
            playbackSpeed = cachedPlaybackSpeed.coerceAtLeast(1000),
        )
        if (bridgedUiProgressActive) {
            bridgedUiProgressMs = adoptedMs
            bridgedUiProgressAtElapsedRealtime = SystemClock.elapsedRealtime()
            bridgedUiProgressPlaying = true
        }
        bumpPlayheadEpoch("seat starvation", adoptedMs, armed = false)
        reconcileProgressOverlayTicker(true)
        pushOverlayProgress(adoptedMs, cachedDurationMs)
    }

    /**
     * The second we are defending against stale MA ticks: local pin, standing
     * armed seek/resume assertion, or a just-adopted peer seat. A pause freeze
     * is not included — that would reject a phone seek while paused.
     */
    private fun intentSeatMs(): Long? {
        pinnedLocalSeekMs?.takeIf {
            SystemClock.elapsedRealtime() <= pinnedLocalSeekUntilElapsed
        }?.let { return clampToDuration(it) }
        if (armedAssertionActive()) {
            playheadAnchorMs?.let { return clampToDuration(it) }
        }
        lastAdoptedPeerAbsoluteMs?.takeIf {
            SystemClock.elapsedRealtime() - lastAdoptedPeerAbsoluteElapsed <
                LOCAL_SEEK_PIN_HOLD_MS
        }?.let { return clampToDuration(it) }
        return null
    }

    /**
     * Shared second for the paired bar.
     *
     * [alignSeekPositionForUpstream] floors to the MA 1s grid on each device.
     * Two playheads 40ms apart that straddle an exact second still paint one
     * second apart — and nothing ever corrects it, because the real skew is
     * far below [PEER_PROGRESS_SOFT_SKEW_MS]. That is the standing "off by 1s":
     * a display artefact, not a sync error.
     *
     * So when the underlying difference is pure quantisation, adopt the peer's
     * second. One-directional (only the lower beacon id's second is taken) or
     * the two would snap to each other and flip every tick.
     */
    private fun snapBarSecondToPeer(ourSecondMs: Long, ourRawMs: Long): Long {
        if (!peerRawOutranksLocal) return ourSecondMs
        val base = peerRawProgressMs ?: return ourSecondMs
        val at = peerRawAtElapsedRealtime
        if (at <= 0L) return ourSecondMs
        val age = SystemClock.elapsedRealtime() - at
        if (age > PEER_PROGRESS_PAINT_STALE_MS) return ourSecondMs
        // Live DAC is speaker-sync. Snapping the vinyl second to it is how
        // paired bars drifted a standing 1s after the pin — MA and SP-only.
        if (!peerPaintAbsoluteSeat &&
            (bridgedUiProgressActive || pairedOnThisStream())
        ) {
            return ourSecondMs
        }
        val peerNow = if (peerRawAdvancing) base + age * peerRawSpeed / 1000L else base
        // Anything wider than this is a real skew and belongs to the calibrator,
        // not the painter.
        if (kotlin.math.abs(ourRawMs - peerNow) > PROGRESS_PEER_SECOND_SNAP_MAX_MS) {
            return ourSecondMs
        }
        val peerSecond = clampToDuration(alignSeekPositionForUpstream(peerNow))
        if (kotlin.math.abs(peerSecond - ourSecondMs) > MA_SEEK_SECOND_MS) return ourSecondMs
        return peerSecond
    }

    /** True while our playback beacon reports STEADY (we are the sync authority). */
    private fun localPlaybackSteady(): Boolean {
        val beacon = client?.buildPeerBeaconSnapshot() ?: return false
        return beacon.flags and SendspinPeerBeacon.FLAG_STEADY != 0
    }

    private fun inPlayResumeGrace(): Boolean =
        playResumeGraceUntilElapsed > 0L &&
            SystemClock.elapsedRealtime() < playResumeGraceUntilElapsed

    /**
     * A deliberate local playhead change: claim the next epoch and announce it.
     *
     * Stepping past [highestSeenPlayheadEpoch] rather than our own counter is
     * what keeps the order total across devices — a device that was behind
     * cannot mint an epoch that loses to the state it is trying to replace.
     */
    private fun bumpPlayheadEpoch(
        reason: String,
        positionMs: Long?,
        armed: Boolean,
        transportPending: Boolean = false,
    ) {
        synchronized(playheadEpochLock) {
            playheadEpoch = maxOf(playheadEpoch, highestSeenPlayheadEpoch) + 1L
            highestSeenPlayheadEpoch = playheadEpoch
            playheadEpochOrigin = ""
        }
        // Every deliberate change lands on a position that is, at that instant,
        // standing still: a pause freeze, a seek target, or a resume seat whose
        // PCM has not arrived. So the assertion is always flat, and `armed`
        // only records whether it is going to start moving.
        seatPlayheadAssertion(
            positionMs = positionMs,
            serverTsUs = localAnchorServerTsUs(),
            speed = 0,
            armed = armed,
            transportPending = transportPending,
        )
        Log.i(
            tag,
            "playhead epoch -> $playheadEpoch @${positionMs}ms armed=$armed " +
                "pendingTransport=$transportPending ($reason)",
        )
        pokePeerProgressMirror()
    }

    /** Freeze the assertion this epoch will keep replaying. */
    private fun seatPlayheadAssertion(
        positionMs: Long?,
        serverTsUs: Long,
        speed: Int,
        armed: Boolean,
        transportPending: Boolean = false,
    ) {
        val nextAnchorMs = positionMs?.let { clampToDuration(it).coerceAtLeast(0L) }
        playheadAnchorMs = nextAnchorMs
        playheadAnchorServerTsUs = serverTsUs
        playheadAnchorSpeed = speed
        playheadAnchorArmed = armed
        playheadAnchorTransportPending = transportPending
        // Re-arming on roughly the same position is the *same* intent restated,
        // not a new one, so it must not restart the defense window. Rapid
        // pause/play taps refreshed this stamp on every tap, which made the
        // bounded window unbounded in exactly the case that needs it most —
        // the bar stayed frozen for as long as the user kept tapping. The
        // intent is tracked separately from [playheadAnchorArmed] because a
        // pause disarms in between, so the pair alternates false/true over
        // what is really one position.
        playheadAnchorArmedAtElapsed = if (!armed) {
            0L
        } else {
            val priorMs = lastArmedIntentMs
            val priorAt = lastArmedIntentAtElapsed
            val restated = priorMs != null &&
                priorAt > 0L &&
                nextAnchorMs != null &&
                kotlin.math.abs(nextAnchorMs - priorMs) <= PROGRESS_SEEK_THRESHOLD_MS
            val stamp = if (restated) priorAt else SystemClock.elapsedRealtime()
            lastArmedIntentMs = nextAnchorMs
            lastArmedIntentAtElapsed = stamp
            stamp
        }
        nextAnchorMs?.let { stampServerProgressSeat(it, serverTsUs) }
    }

    /**
     * Adopt a peer's assertion as our own, verbatim. Replaying the sender's
     * exact triple (rather than re-deriving it here) is what keeps a chain of
     * three or more devices on one timeline: everyone forwards the same
     * numbers, so no device accumulates its own projection error.
     */
    private fun adoptPlayheadAssertion(sample: SendspinPeerProgressSample) {
        synchronized(playheadEpochLock) {
            playheadEpoch = sample.epoch
            highestSeenPlayheadEpoch = maxOf(highestSeenPlayheadEpoch, sample.epoch)
            playheadEpochOrigin = sample.originId
        }
        seatPlayheadAssertion(
            positionMs = sample.progressMs,
            serverTsUs = sample.atServerTsUs,
            speed = sample.playbackSpeed,
            armed = sample.isArmed,
            // Forwarded so a chain can relay the request; [applyPeerTransport]
            // clears it right after this adoption if we deliver the command.
            transportPending = sample.transportPending,
        )
    }

    /** Our own epoch no longer describes the playhead — our DAC does. */
    private fun retirePlayheadAssertion() {
        playheadAnchorMs = null
        playheadAnchorServerTsUs = 0L
        playheadAnchorSpeed = 0
        playheadAnchorArmed = false
        playheadAnchorArmedAtElapsed = 0L
        playheadAnchorTransportPending = false
        lastArmedIntentMs = null
        lastArmedIntentAtElapsed = 0L
        peerSeatFulfilledAtElapsed = 0L
    }

    /**
     * Matching PCM arrived on the UDP seat. Copy the assertion onto the
     * server-clock interpolator, then drop the standing claim so vinyl follows
     * Sendspin `metadata.progress` + server now — not Android wall time.
     */
    private fun releasePlayheadAssertionToRun() {
        playheadAnchorMs?.let { stampServerProgressSeat(it, playheadAnchorServerTsUs) }
        retirePlayheadAssertion()
    }

    /**
     * Last accepted playhead on the **Sendspin server clock**, cached as the
     * shared metadata seat both paired devices fall back to.
     */
    private fun stampServerProgressSeat(positionMs: Long, serverTsUs: Long) {
        val seated = clampToDuration(positionMs)
        cachedProgressMs = seated
        lastGoodOverlayProgressMs = seated
        if (serverTsUs > 0L) {
            cachedMetadataTimestampUs = serverTsUs
        } else {
            client?.serverNowUs()?.let { cachedMetadataTimestampUs = it }
        }
    }

    /**
     * True while we have opened a new track at ~0 and are waiting for its PCM.
     * A partner still publishing the previous song (~30s) must not win vinyl.
     */
    private fun newTrackStartSeatMs(): Long? {
        // Deliberately the raw flag, not [armedAssertionActive]. This seat is
        // protective: the upstream never pushes a reset when a queued track is
        // opened, so our own start-of-track assertion is the only statement
        // that the song began. Letting it time out would make a partner's
        // previous-song position look valid again and resume the bar mid-track
        // instead of starting over.
        if (!progressWaitForAudible &&
            !playheadAnchorArmed &&
            !inTrackJumpSeam() &&
            !pendingTrackIdentityReseat
        ) {
            return null
        }
        return trustedUpstreamProgressSeedMs
            ?: playheadAnchorMs
            ?: progressHeadMs
            // Invent 0 only for a deliberate skip seam. Title identity on a
            // cold join has no seed — treating that as "new track at 0"
            // dropped the master's live mid-track UDP as leftover ~30s.
            ?: 0L.takeIf { inTrackJumpSeam() }
    }

    private fun stalePeerSeatOnNewTrack(progressMs: Long): Boolean {
        val start = newTrackStartSeatMs() ?: return false
        if (start > PROGRESS_OPEN_LEAD_MAX_MS) return false
        return progressMs > PROGRESS_OPEN_LEAD_MAX_MS
    }

    /**
     * First seat is already on the last seconds of [durationMs] while we only
     * have an empty / open-lead clock. That is stale SP leftover after a
     * master's MA seek (logs: 188257 vs pin 27000), not a song that is
     * actually ending — we would already be living on this tail if it were.
     */
    private fun looksLikeUnconfirmedDurationTail(
        positionMs: Long,
        durationMs: Long? = cachedDurationMs,
        heldMs: Long = lastGoodOverlayProgressMs.takeIf { it > 0L }
            ?: progressHeadMs?.takeIf { it > 0L }
            ?: cachedProgressMs?.takeIf { it > 0L }
            ?: 0L,
    ): Boolean {
        val duration = durationMs ?: return false
        if (duration <= MA_SEEK_SECOND_MS) return false
        if (positionMs <= PROGRESS_OPEN_LEAD_MAX_MS) return false
        if (positionMs < duration - PROGRESS_DURATION_TAIL_MS) return false
        if (heldMs >= duration - PROGRESS_DURATION_TAIL_MS) return false
        if (heldMs > PROGRESS_OPEN_LEAD_MAX_MS) return false
        return true
    }

    /**
     * Just attached to this stream (first title/progress). Peer UDP has often
     * not arrived yet, so [pairedOnThisStream] is still false. Long enough to
     * cover the same metadata packet + one sparse follow-up; short enough that
     * an unpaired device sitting on a song that is actually ending can accept
     * the tail on the next drip.
     */
    private fun inColdJoinAttachWindow(): Boolean {
        if (lastStreamAttachElapsed <= 0L) return playheadEpoch == 0L
        return SystemClock.elapsedRealtime() - lastStreamAttachElapsed <
            PEER_PROGRESS_PAINT_STALE_MS
    }

    private fun rejectStalePeerSeatOnNewTrack(
        sample: SendspinPeerProgressSample,
        nowElapsed: Long,
    ): Boolean {
        val projected = projectPeerAnchor(sample) ?: sample.progressMs
        if (!stalePeerSeatOnNewTrack(projected)) return false
        Log.i(tag, "drop stale peer seat ${projected}ms on new track")
        answerStalePlayhead(nowElapsed)
        return true
    }

    /**
     * Paired vinyl from a standing UDP freeze (pause / armed seat). Playing
     * interpolation was a private clock that lagged the service by open-lead;
     * after PCM the bar follows SP/MA again.
     */
    private fun pairedAssertionDisplayMs(): Long? {
        if (bridgedUiProgressActive || !pairedOnThisStream()) return null
        val base = playheadAnchorMs ?: return null
        if (stalePeerSeatOnNewTrack(base)) return null
        // Expired armed seat is not a display lock. Leaving it here after the
        // 10s TTL is how a finger tap froze the paired bar on the tapped
        // second forever — retire only runs on matching PCM, and pairing
        // kept re-arming the wait so PCM never released it.
        if (!armedAssertionActive()) return null
        // Author-fulfilled seat: the partner is audibly past this position, so
        // walk its projected timeline (never behind the seat itself) instead
        // of freezing until our own PCM lands. Freshness-gated: if the
        // author's samples stop, fall back to the walking seat.
        if (playheadAnchorSpeed > 0 && peerSeatFulfilledAtElapsed > 0L &&
            SystemClock.elapsedRealtime() - peerSeatFulfilledAtElapsed <=
            PROGRESS_FOLLOW_WINDOW_MS
        ) {
            projectedPeerRawProgressMs()?.takeIf { it >= base }?.let {
                return clampToDuration(it)
            }
        }
        return walkPlayingSeatMs(base, playheadAnchorArmedAtElapsed)
    }

    /**
     * Same 1s-grid seat we already claimed (finger tap, adopted UDP, or head).
     * Beacon repeats must not re-enter wait-for-PCM — that reset the mapper
     * every 2.5s and left both bars dead on the tap.
     */
    private fun alreadyHoldingSeekSeat(progressMs: Long): Boolean {
        fun near(candidate: Long?): Boolean {
            if (candidate == null) return false
            return kotlin.math.abs(candidate - progressMs) <= MA_SEEK_SECOND_MS
        }
        val now = SystemClock.elapsedRealtime()
        if (near(
                pinnedLocalSeekMs?.takeIf { now <= pinnedLocalSeekUntilElapsed },
            )
        ) {
            return true
        }
        if (near(playheadAnchorMs)) return true
        if (near(lastAdoptedPeerAbsoluteMs)) return true
        if (near(progressHeadMs)) return true
        return false
    }

    /**
     * Finger / peer seek: jump to the second, then keep walking while playing.
     * A flat freeze until PCM is what "stuck in the middle" looked like.
     */
    private fun walkPlayingSeatMs(seatMs: Long, originElapsed: Long): Long {
        val seated = clampToDuration(alignSeekPositionForUpstream(seatMs))
        if (originElapsed <= 0L) return seated
        if (!groupAllowsSeekWalk()) return seated
        val elapsed = (SystemClock.elapsedRealtime() - originElapsed).coerceAtLeast(0L)
        val speed = cachedPlaybackSpeed.takeIf { it > 0 } ?: 1000
        return clampToDuration(seated + elapsed * speed / 1000L)
    }

    /** Playing scrub: start the wall clock so the bar does not sit on the tap. */
    private fun startPlayingSeekWalkClock() {
        if (!groupAllowsSeekWalk()) return
        if (cachedPlaybackSpeed <= 0) cachedPlaybackSpeed = 1000
        progressHeadElapsedRealtime = SystemClock.elapsedRealtime()
    }

    /**
     * May the bar walk across a stream/clear or seek pin?
     *
     * Stream/clear also arrives on pause/stop/idle. Walking then invents
     * seconds while the peer or upstream is frozen. Only a playing seek
     * we authored, or a peer that actually moved (playing absolute),
     * keeps the clock. Pause on either side wins.
     */
    private fun groupAllowsSeekWalk(): Boolean {
        if (!_isPlaying.value && !isAudiblyPlayingNow()) return false
        val localSeek = localPlayingSeekIntent()
        if (peerTransportPausedNow() && !localSeek) return false
        if (upstreamTransportPausedNow() && !localSeek) return false
        return localSeek || peerPlayingSeekChangeNow()
    }

    private fun localPlayingSeekIntent(): Boolean {
        if (!_isPlaying.value && !isAudiblyPlayingNow()) return false
        val now = SystemClock.elapsedRealtime()
        if (pinnedLocalSeekMs != null && now <= pinnedLocalSeekUntilElapsed) return true
        if (armedAssertionActive()) return true
        return awaitingSeekReseat
    }

    /** Fresh UDP pause freeze — the partner has not unpaused. */
    private fun peerTransportPausedNow(): Boolean {
        if (!pairedOnThisStream()) return false
        if (peerPausedPaintHoldMs() != null) return true
        val at = peerRawAtElapsedRealtime
        if (at <= 0L) return false
        if (SystemClock.elapsedRealtime() - at > PEER_PROGRESS_PAINT_STALE_MS) return false
        return !peerRawAdvancing && peerRawSpeed <= 0
    }

    /** Partner published a playing seat or is still advancing. */
    private fun peerPlayingSeekChangeNow(): Boolean {
        if (!pairedOnThisStream()) return false
        if (peerTransportPausedNow()) return false
        val now = SystemClock.elapsedRealtime()
        if (lastAdoptedPeerAbsoluteElapsed > 0L &&
            now - lastAdoptedPeerAbsoluteElapsed < LOCAL_SEEK_PIN_HOLD_MS &&
            peerPaintPlaying
        ) {
            return true
        }
        if (peerRawAdvancing &&
            peerRawSpeed > 0 &&
            peerRawAtElapsedRealtime > 0L &&
            now - peerRawAtElapsedRealtime < PEER_PROGRESS_PAINT_STALE_MS
        ) {
            return true
        }
        return false
    }

    private fun upstreamTransportPausedNow(): Boolean =
        client?.upstreamPlayStatePaused() == true

    /** Keep the Lamport clock monotone against everything we observe. */
    private fun noteSeenPlayheadEpoch(epoch: Long) {
        synchronized(playheadEpochLock) {
            if (epoch > highestSeenPlayheadEpoch) highestSeenPlayheadEpoch = epoch
        }
    }

    /**
     * Whether an upstream jump to [seated] is actually news.
     *
     * It is not, if we are already asserting that position: either our own
     * scrub claimed it a moment ago, or we adopted a peer's claim and upstream
     * is merely confirming it. Re-claiming an epoch for either would have the
     * pair trade epochs on every metadata confirmation — one device bumping,
     * the other adopting and bumping back. Compared on the Music Assistant
     * seek grid, since that is the resolution upstream reports.
     */
    private fun upstreamJumpNeedsEpoch(seated: Long): Boolean {
        playheadAnchorMs?.let { asserted ->
            if (kotlin.math.abs(asserted - seated) <= MA_SEEK_SECOND_MS) return false
        }
        val live = audibleTailMs
            ?: progressHeadMs
            ?: displayProgressMs()
            ?: cachedProgressMs
            ?: return true
        // A 1s MA grid tick is not a new timeline. Only a jump past the grid
        // (remote scrub, phone seek) deserves a fresh epoch.
        return kotlin.math.abs(live - seated) > MA_SEEK_SECOND_MS
    }

    /**
     * Position of a peer assertion re-projected onto **our** clock now.
     *
     * The anchor makes this exact: the sender's position at its anchor time
     * plus the elapsed shared-clock time, so datagram latency and tick phase
     * drop out instead of being absorbed as skew. Paused anchors carry
     * `speed = 0` and therefore project to themselves.
     *
     * Falls back to the raw value when either side lacks a converged clock —
     * that is the legacy behaviour and still better than discarding the sample.
     *
     * Every return goes through [seatWithinTrackOrNull]: a projection (or raw
     * seat) that lands past the end of our track is not "the end of the song",
     * it is a stale anchor whose timeline has already moved on. Clamping it
     * used to hand the adopt paths a position on the exact last second, which
     * both bars then adopted and re-asserted — the paired flash-to-end freeze.
     */
    private fun projectPeerAnchor(sample: SendspinPeerProgressSample): Long? {
        val speed = sample.playbackSpeed
        if (speed <= 0) return seatWithinTrackOrNull(sample.progressMs)
        if (!sample.hasAnchor) return seatWithinTrackOrNull(sample.progressMs)
        val live = client ?: return null
        val ourNow = live.audibleNowServerTsUs() ?: live.serverNowUs()
            ?: return seatWithinTrackOrNull(sample.progressMs)
        val deltaUs = ourNow - sample.atServerTsUs
        // Beyond this the two are not on the same timeline at all (stale anchor
        // across a track change, or a clock that has not settled).
        if (kotlin.math.abs(deltaUs) > PEER_ANCHOR_MAX_AGE_US) return null
        return seatWithinTrackOrNull(
            (sample.progressMs + deltaUs * speed / 1_000_000L).coerceAtLeast(0L),
        )
    }

    /**
     * Shared-clock stamp for a flat assertion (pause freeze, seek target,
     * resume seat). Zero when no shared clock exists yet, which costs nothing:
     * a flat assertion projects to itself, so its anchor time only matters for
     * staleness checks. A *moving* assertion is stamped from the DAC instead —
     * see [peerProgressSnapshot].
     */
    private fun localAnchorServerTsUs(): Long = client?.serverNowUs() ?: 0L

    /**
     * Paint paired UDP progress even when the Mass API bridge owns the bar.
     * Updates [bridgedUiProgressMs] first so the Mass writer path cannot paint
     * a stale queue_time over the synced seat.
     */
    private fun pushPeerSyncedProgress(seated: Long) {
        val now = SystemClock.elapsedRealtime()
        if (bridgedUiProgressActive) {
            bridgedUiProgressMs = seated
            bridgedUiProgressAtElapsedRealtime = now
            pushOverlayProgress(
                seated,
                bridgedEffectiveDurationMs() ?: cachedDurationMs,
                OverlayProgressWriter.PeerSync,
            )
        } else {
            pushOverlayProgress(
                seated,
                cachedDurationMs,
                OverlayProgressWriter.PeerSync,
            )
        }
    }

    /**
     * Local scrub: seat local heads + the MA bridge on [target] and claim a new
     * playhead epoch so LAN peers lock the same second.
     *
     * Deliberately does **not** write [peerPaintProgressMs]: peer paint
     * extrapolates on the wall clock, so borrowing it for our own scrub would
     * race the bar ahead of silent audio and snap back at the paint TTL.
     * [overlayBarProgressMs] already ranks the local pin above peer paint.
     */
    private fun seatLocalAbsoluteProgress(
        target: Long,
        playing: Boolean,
        transportPending: Boolean = false,
    ) {
        val seated = clampToDuration(target)
        val now = SystemClock.elapsedRealtime()
        progressHeadMs = seated
        cachedProgressMs = seated
        lastGoodOverlayProgressMs = seated
        trustedUpstreamProgressSeedMs = seated
        // Our own seat outranks any peer seat we adopted before the scrub.
        clearPeerProgressLock()
        if (bridgedUiProgressActive) {
            bridgedUiProgressMs = seated
            bridgedUiProgressAtElapsedRealtime = now
            bridgedUiProgressPlaying = playing
        }
        // A local scrub is the strongest kind of intent — claim a new epoch so
        // every peer adopts the target even if it is mid-seek itself, and keep
        // asserting it until our own PCM lands on it.
        bumpPlayheadEpoch("local seek", seated, armed = playing, transportPending = transportPending)
    }

    /**
     * Peer scrub / absolute seat while transport may still be playing.
     * Does not flip pause — only locks the shared playhead.
     *
     * An absolute seat carries no DAC timestamp, so it may only re-base what we
     * already have; when our own PCM is running we keep the live tail and just
     * move its origin. Dropping back into wait-for-PCM here (as the first cut
     * did) stalls the bar until the next write and, since the seat is
     * reannounced at beacon rate, stalls it permanently.
     */
    private fun adoptPeerAbsoluteProgress(seated: Long, playing: Boolean, peerSpeed: Int) {
        val live = client ?: return
        val now = SystemClock.elapsedRealtime()
        val before = audibleTailMs ?: progressHeadMs ?: cachedProgressMs
        val speed = peerSpeed.takeIf { it > 0 } ?: 1000
        peerPaintProgressMs = seated
        peerPaintAtElapsedRealtime = now
        peerPaintPlaying = playing
        peerPaintFromSteady = true
        peerPaintAbsoluteSeat = true
        peerPaintFromRemote = true
        // The sender repeats one seat for its whole window; only re-run the
        // reseat when it actually moved. Scoped to the paint TTL so a second
        // scrub to a nearby ms is not mistaken for that repeat.
        val alreadySeated = lastAdoptedPeerAbsoluteMs?.let {
            kotlin.math.abs(it - seated) <= MA_SEEK_SECOND_MS
        } ?: false
        if (alreadySeated) {
            pushPeerSyncedProgress(seated)
            return
        }
        lastAdoptedPeerAbsoluteMs = seated
        lastAdoptedPeerAbsoluteElapsed = now
        progressHeadMs = seated
        cachedProgressMs = seated
        lastGoodOverlayProgressMs = seated
        trustedUpstreamProgressSeedMs = seated
        // Our mapper is live (not just "PCM heard recently") — move its origin
        // instead of wiping it, so the bar keeps running from the new seat.
        // Seek-sized jump: leftover PCM is the old timeline. Rebasing it onto
        // the new seat lets the next write republish the old tail. Wait for
        // post-clear chunks instead.
        val jumped = before == null ||
            kotlin.math.abs(before - seated) > MA_SEEK_SECOND_MS
        val rebaseLiveSeat =
            playing && !jumped && !progressWaitForAudible && audibleTailMs != null
        if (rebaseLiveSeat) {
            cachedPlaybackSpeed = speed
            audibleTailMs = seated
            progressHeadElapsedRealtime = now
        } else {
            // No PCM yet: hold the seat frozen and let the first audible write
            // map from it instead of from 0.
            audibleTailMs = null
            progressWaitForAudible = true
            progressHeadElapsedRealtime = if (playing) 0L else now
        }
        if (bridgedUiProgressActive) {
            bridgedUiProgressMs = seated
            bridgedUiProgressAtElapsedRealtime = now
            bridgedUiProgressPlaying = playing
        }
        live.calibrateAudibleProgress(
            progressMs = seated,
            metadataTimestampUs = if (rebaseLiveSeat) live.audibleNowServerTsUs() else null,
            playbackSpeed = if (playing) speed else 0,
            durationMs = cachedDurationMs,
        )
        pushPeerSyncedProgress(seated)
        if (before == null || kotlin.math.abs(before - seated) >= 500L) {
            Log.i(tag, "peer progress absolute-seat ${before}ms -> ${seated}ms playing=$playing")
        }
    }

    /**
     * Absolute peer pause freeze — overlay, local heads, MA bridge, and mapper
     * all seat to the same ms. Bypasses [calibrateAudibleFromTrusted]'s
     * force-1000 so a still-draining PCM tail cannot thaw the bar.
     */
    private fun adoptPeerFrozenProgress(seated: Long) {
        val now = SystemClock.elapsedRealtime()
        val before = audibleTailMs ?: progressHeadMs ?: cachedProgressMs
        peerPaintProgressMs = seated
        peerPaintAtElapsedRealtime = now
        peerPaintPlaying = false
        peerPaintFromSteady = true
        peerPaintAbsoluteSeat = true
        peerPaintFromRemote = true
        // Paused seats reannounce at beacon rate — refresh the hold, but only
        // re-run the reseat when the freeze point actually moved.
        val alreadySeated = lastAdoptedPeerAbsoluteMs?.let {
            it == seated && now - lastAdoptedPeerAbsoluteElapsed < PEER_PROGRESS_PAUSED_HOLD_MS
        } ?: false
        if (alreadySeated) {
            pushPeerSyncedProgress(seated)
            return
        }
        lastAdoptedPeerAbsoluteMs = seated
        lastAdoptedPeerAbsoluteElapsed = now
        progressHeadMs = seated
        progressHeadElapsedRealtime = now
        cachedProgressMs = seated
        lastGoodOverlayProgressMs = seated
        audibleTailMs = seated
        progressWaitForAudible = false
        cachedPlaybackSpeed = 0
        if (bridgedUiProgressActive) {
            bridgedUiProgressMs = seated
            bridgedUiProgressAtElapsedRealtime = now
            bridgedUiProgressPlaying = false
        }
        client?.calibrateAudibleProgress(
            progressMs = seated,
            metadataTimestampUs = null,
            playbackSpeed = 0,
            durationMs = cachedDurationMs,
        )
        pushPeerSyncedProgress(seated)
        if (before == null || kotlin.math.abs(before - seated) >= 500L) {
            Log.i(tag, "peer progress pause-freeze ${before}ms -> ${seated}ms")
        }
    }

    /** Fresh paused peer-paint hold (absolute freeze still authoritative). */
    private fun peerPausedPaintHoldMs(): Long? {
        val base = peerPaintProgressMs ?: return null
        if (peerPaintPlaying) return null
        val at = peerPaintAtElapsedRealtime
        if (at <= 0L) return null
        if (SystemClock.elapsedRealtime() - at > PEER_PROGRESS_PAUSED_HOLD_MS) return null
        return clampToDuration(base)
    }

    /** Peer-aligned overlay playhead while the differential / pause lock is fresh. */
    private fun peerAlignedDisplayProgressMs(): Long? {
        val base = peerPaintProgressMs ?: return null
        val at = peerPaintAtElapsedRealtime
        if (at <= 0L) return null
        val age = SystemClock.elapsedRealtime() - at
        val staleLimit = if (peerPaintPlaying) {
            PEER_PROGRESS_PAINT_STALE_MS
        } else {
            PEER_PROGRESS_PAUSED_HOLD_MS
        }
        if (age > staleLimit) {
            clearPeerPaintLock()
            return null
        }
        // STEADY local authority: do not let a non-steady peer paint override us.
        if (peerPaintPlaying && localPlaybackSteady() && !peerPaintFromSteady) {
            return null
        }
        // A seat is painted verbatim: the sender's own bar is pinned flat on it,
        // so extrapolating would put the follower ahead of who it follows.
        if (peerPaintAbsoluteSeat || !peerPaintPlaying || cachedPlaybackSpeed <= 0) {
            return clampToDuration(base)
        }
        val delta = age * cachedPlaybackSpeed / 1000L
        return clampToDuration(base + delta.coerceAtLeast(0L))
    }

    /**
     * Mass Up Next / playlist replace: same as SP next — declare playing intent
     * and open the track-jump seam so gap pause flashes cannot stick before PCM.
     */
    fun noteExpectedTrackChangeWhilePlaying() {
        if (inUpstreamSkipStorm()) return
        if (!(_isPlaying.value || isAudiblyPlayingNow() || wasRecentlyAudible())) return
        if (!_isPlaying.value) {
            _isPlaying.value = true
            externalPlayingCallback?.invoke(true)
            pushOverlayPlayingState(true, immediate = true)
        }
        armTrackJumpSeam()
        armReseatPlayingHold()
        armTrackChangeRescueWindow("ma-expected")
        progressWaitForAudible = true
    }

    /**
     * User opened a queued item (playlist row / play-now / replace). Upstream
     * never pushes a reset — it keeps reporting the *previous* song's second —
     * so the only statement that this track starts at 0 is our own. Seat 0,
     * claim an epoch, and publish it: a partner still on the last playhead
     * would otherwise keep walking mid-track, and after [stream/clear] wiped
     * the mapper there would be nothing to rebuild the audible stream from.
     *
     * Unlike [noteExpectedTrackChangeWhilePlaying] this runs on a cold start
     * (no PCM, not yet playing). That is the case the previous helper skipped.
     */
    fun noteQueuedTrackStart() {
        if (inUpstreamSkipStorm()) return
        clearHeldProgressForTrackChange()
        reanchorProgressClock(0L)
        pinLocalSeekTarget(0L)
        awaitingSeekReseat = true
        progressWaitForAudible = true
        bumpPlayheadEpoch("queued track start", 0L, armed = true)
        _isActive.value = true
        if (!_isPlaying.value) {
            _isPlaying.value = true
            externalPlayingCallback?.invoke(true)
            pushOverlayPlayingState(true, immediate = true)
        }
        armTrackJumpSeam()
        armReseatPlayingHold()
        armTrackChangeRescueWindow("queued-start")
        pokePeerProgressMirror()
        pushOverlayProgress(0L, cachedDurationMs, forcePlayhead = true)
        Log.i(tag, "queued track start: hard-seat 0ms (upstream will not reset)")
    }

    /**
     * MA is bursting through unplayable queue rows. Hold the bar and disarm
     * rescue play/seek so we do not retry play_index or seek to the old
     * playhead on a new item.
     */
    fun noteUpstreamSkipStorm() {
        upstreamSkipStormUntilElapsedMs =
            SystemClock.elapsedRealtime() + UPSTREAM_SKIP_STORM_HOLD_MS
        pendingTrackChangePausePlayNudge = false
        progressReseatNudgeInFlight = false
        trackChangeRescueUntilElapsedRealtime = 0L
        trackChangeRescueShotsSent = 0
        seamPendingPause = false
        Log.i(tag, "upstream skip-storm: hold progress, disarm rescue")
    }

    /**
     * MA skip-storm landed idle/paused: source cannot play. Freeze the bar
     * now (do not wait for the silence watchdog) and confirm dead if PCM
     * is already gone so rescue cannot keep hitting play_index.
     */
    fun noteUpstreamPlaybackDead(reason: String) {
        armBridgedSilenceHold("upstream-dead:$reason")
        // A grouped peer audibly on our stream contradicts "source dead" —
        // freeze only, and let the beacons go stale before any give-up.
        if (!isAudiblyPlayingNow() && !peersStillRenderingOurStream()) {
            confirmDeadStream("upstream-dead:$reason")
        }
    }

    fun clearUpstreamSkipStorm() {
        if (upstreamSkipStormUntilElapsedMs == 0L) return
        upstreamSkipStormUntilElapsedMs = 0L
    }

    fun inUpstreamSkipStorm(): Boolean =
        SystemClock.elapsedRealtime() < upstreamSkipStormUntilElapsedMs

    /**
     * Sendspin / MA player id used for queue binding and MA-API self lookup.
     *
     * An encrypted session is registered in MA under the X25519 identity from
     * `client/init`; a plaintext one under ANDROID_ID. Resolving this to the
     * ANDROID_ID while an encrypted session plays made the MA rail adopt the
     * stale legacy player — cover, artist, title and progress frozen at the
     * last track that player ever played. The last session's id is kept so a
     * reconnect gap does not flip the rail back to the wrong row.
     */
    fun deviceClientId(): String =
        client?.activeClientId()
            ?: lastSessionClientId.takeIf { it.isNotBlank() }
            ?: getOrCreateDeviceId()

    /** Legacy ANDROID_ID form, for MA rows created by a plaintext hello. */
    fun legacyDeviceClientId(): String = getOrCreateDeviceId()

    @Volatile
    private var lastSessionClientId: String = ""

    /** Sendspin client display name used for queue name matching. */
    fun clientDisplayName(): String = resolveClientName()

    private fun normalizeHaBridgeRepeatMode(raw: String): String =
        when (raw.trim().lowercase()) {
            "one", "repeat_one" -> "one"
            "all", "repeat_all" -> "all"
            else -> "off"
        }

    /**
     * UI play/pause. Protocol deltas settle briefly; audio-stream promotions and
     * local taps paint immediately. Settle always re-reads [_isPlaying] **and**
     * refuses to paint ▶ while PCM is still flowing.
     */
    private fun pushOverlayPlayingState(playing: Boolean, immediate: Boolean = false) {
        if (immediate) {
            overlayPaintJob?.cancel()
            overlayPaintJob = null
            paintOverlayPlaying(effectiveTransportPlaying(playing))
            return
        }
        if (overlayPaintJob == null && lastPushedOverlayPlaying == effectiveTransportPlaying(playing)) {
            return
        }
        // Restart settle so a later opposite delta is not ignored forever.
        overlayPaintJob?.cancel()
        overlayPaintJob = scope.launch {
            delay(PLAY_STATE_SETTLE_MS)
            overlayPaintJob = null
            paintOverlayPlaying(effectiveTransportPlaying(_isPlaying.value))
        }
    }

    /**
     * Audio stream wins over a stale paused _isPlaying (cold start / skip seam).
     * Explicit pause intent still wins when audio has already gone quiet.
     */
    private fun effectiveTransportPlaying(desired: Boolean): Boolean {
        if (isAudiblyPlayingNow()) return true
        return desired
    }

    /**
     * Single writer of the overlay transport glyph. Takes Sendspin overlay
     * ownership when this session is active so a stale HA owner cannot leave the
     * button stuck on the wrong glyph.
     */
    private fun paintOverlayPlaying(playing: Boolean) {
        // Media Controls off: never claim ownership or poke playback-state (that
        // path can re-show a GONE shell from cover-only memory after service churn).
        if (!isSendspinVinylUiEnabled()) {
            lastPushedOverlayPlaying = playing
            return
        }
        // Reclaim BEFORE dedupe: a stale HA owner must not survive just because
        // the glyph did not change — deduped paints used to skip the reclaim
        // and leave the overlay stuck on HA content while Sendspin played on.
        if (_isActive.value && !VinylCoverService.ownsOverlayProgress(fromSendspin = true)) {
            VinylCoverService.claimOverlayProgressOwner(fromSendspin = true)
        }
        // Dedupe: identical paints still restarted the service Intent and retriggered
        // FAB Crossfade even when the glyph did not need to change.
        if (lastPushedOverlayPlaying == playing) return
        lastPushedOverlayPlaying = playing
        VinylCoverService.updatePlaybackState(
            context,
            isPlaying = playing,
            isSendspinSource = true,
        )
    }

    private fun normalizePlaybackState(state: String): String {
        return when (state.trim().lowercase()) {
            "play", "playing", "resume", "resumed" -> "playing"
            "pause", "paused" -> "paused"
            "stop", "stopped", "idle" -> "stopped"
            else -> state.trim().lowercase()
        }
    }

    /**
     * MA Media Controls master gate. Default **false** when the callback is
     * unset — never birth overlay from a half-wired manager after service churn.
     */
    private fun isSendspinVinylUiEnabled(): Boolean =
        vinylCoverEnabledCallback?.invoke() == true

    /**
     * Birth / full repaint requires Media Controls **and** real stream evidence
     * (or an already-visible shell / intentional pause-idle reshow).
     * Cover / protocol metadata alone must not open the window after service toggles.
     *
     * @param allowPipelineRebindRefresh when true, still requires Media Controls;
     * only relaxes the "already painted shell" path for soft restart rebind.
     */
    private fun canPaintSendspinOverlay(allowPipelineRebindRefresh: Boolean = false): Boolean {
        if (!isSendspinVinylUiEnabled()) return false
        if (ModMediaOverlayExclusive.isActive(context)) return false
        if (VinylCoverService.isLiveOverlayShellVisible()) return true
        if (VinylCoverService.isAwaitingPauseIdleAudibleReshow()) return true
        if (allowPipelineRebindRefresh && VinylCoverService.isAwaitingPipelineRebind()) {
            // Soft restart: only reconnect a shell that was already wanted — still
            // need audible / recent PCM so cover drip cannot birth a fresh FAB.
            return isAudiblyPlayingNow() || wasRecentlyAudible()
        }
        return isAudiblyPlayingNow() || wasRecentlyAudible()
    }

    /** Re-show vinyl overlay from cached metadata after reconnect / server switch. */
    fun refreshVinylFromCacheIfNeeded(ignoreEnabledGate: Boolean = false) {
        // [ignoreEnabledGate] only means "pipeline rebind refresh" — Media Controls
        // and audio-stream evidence are never skipped.
        if (!canPaintSendspinOverlay(allowPipelineRebindRefresh = ignoreEnabledGate)) {
            return
        }
        val title = cachedSendspinTitle
        if (title.isNullOrEmpty() && cachedSendspinArtworkUrl.isNullOrEmpty() && !hasBinaryArtwork) {
            pushOverlayPlayingState(_isPlaying.value)
            return
        }
        // Do not abort an in-flight empty-queue hide with a sticky cache repaint.
        if (client?.isStreamEnded() == true && !isAudiblyPlayingNow()) {
            return
        }
        cancelHideJob()
        // Never let an unsettled seam state leak through a full show() repaint.
        val paintPlaying = overlayPlayingForPaint()
        val barMs = paintBarProgressMs()
        VinylCoverService.show(
            context = context,
            coverUrl = cachedSendspinArtworkUrl.takeUnless { hasBinaryArtwork },
            songTitle = title,
            artistName = cachedSendspinArtist,
            albumName = cachedSendspinAlbum,
            isPlaying = paintPlaying,
            currentTimeMs = barMs,
            totalTimeMs = bridgedEffectiveDurationMs(),
            isSendspinSource = true,
            lyricAudibleLagMs = overlayLyricAudibleLagMs(barProgressMs = barMs),
        )
        lastPushedOverlayPlaying = paintPlaying
        pushOverlayPlayingState(_isPlaying.value)
        reconcileProgressOverlayTicker(_isPlaying.value)
    }

    private fun handleBinaryArtwork(bitmap: android.graphics.Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            hasBinaryArtwork = true
            // No Media Controls → keep local flag only; do not seed overlay cache.
            if (!isSendspinVinylUiEnabled()) return
            VinylCoverService.applyCoverBitmap(bitmap)
        } else {
            hasBinaryArtwork = false
            // Empty artwork channel alone is not queue-clear (pause/track gaps).
            if (!isSendspinVinylUiEnabled()) return
            VinylCoverService.applyCoverBitmap(null)
        }
        // Only poke when peers can fetch a URL — binary alone is not shareable.
        if (!cachedSendspinArtworkUrl.isNullOrBlank()) {
            pokePeerMediaMirror()
        }
    }

    /**
     * Confirmed empty queue ([stream/end]). Used by minimal pause-idle stale-clear
     * to wipe sticky identity without waiting for FAB force-teardown.
     */
    fun isUpstreamQueueEmpty(): Boolean = client?.isStreamEnded() == true

    /**
     * Spec [stream/end]: playback over and queue empty.
     * Same hide timeline as [scheduleQueueClearedHide] — not the pause path.
     */
    private fun handleStreamEnd() {
        // Flow churn inside the track-change head window: MA tears down and
        // rebuilds the flow stream around a jump, and that gap arrives here as
        // stream/end. Landing it would paint a dead pause and disarm the
        // rescue — defer into the seam instead; a real queue end lands via
        // reconcile once the window/budget expires.
        if (_isPlaying.value && inTrackChangeRescueWindow()) {
            seamPendingPause = true
            if (!inTrackJumpSeam()) armTrackJumpSeam()
            return
        }
        // Confirmed queue end: release the recent-audible HA claim guard now
        // instead of waiting for the window to expire.
        lastAudibleElapsedRealtimeMs = 0L
        if (_isPlaying.value) {
            freezeProgressAtCurrent()
            _isPlaying.value = false
            externalPlayingCallback?.invoke(false)
        }
        scheduleQueueClearedHide()
    }

    /**
     * Spec `metadata: null` clears the metadata role. Some servers also emit this
     * on pause — that must NOT tear down the FAB. Keep sticky identity on screen;
     * only [handleStreamEnd] / confirmed empty queue may hide.
     */
    private fun handleMetadataRoleCleared() {
        // Same flow-churn gap as stream/end: inside the head window this is
        // MA blanking the role mid-rebuild, not an empty queue. Defer so the
        // rescue keeps working; sticky identity already keeps the art up.
        if (_isPlaying.value && inTrackChangeRescueWindow()) {
            seamPendingPause = true
            if (!inTrackJumpSeam()) armTrackJumpSeam()
            return
        }
        if (_isPlaying.value) {
            freezeProgressAtCurrent()
            _isPlaying.value = false
            externalPlayingCallback?.invoke(false)
        }
        pushOverlayPlayingState(false, immediate = true)
        reconcileProgressOverlayTicker(false)
        retainOverlayAfterPause()
        // If the client already ended the stream, this is a real empty queue.
        if (client?.isStreamEnded() == true) {
            scheduleQueueClearedHide()
        }
    }

    /**
     * Queue cleared (`stream/end` or `metadata: null`), often right after pause.
     *
     * Human-paced timeline (predicts “I just cleared / still glancing at art”):
     * 1) brief confirm coalesce
     * 2) **30s** keep expanded identity on screen (aligned with pause yield)
     * 3) soft → FAB (position memory kept); no-FAB legacy tears down here
     * 4) Service grace: expand → 30s idle → FAB; FAB → 60s → force teardown
     *
     * Unexpected blips must not wipe remembered FAB norms — only content clears
     * at final teardown.
     */
    private fun scheduleQueueClearedHide() {
        cancelHideJob()
        // Empty-queue timeline replaces pause-idle.
        VinylCoverService.cancelPauseIdleTeardown(context)
        clearTrackJumpSeam()
        if (_isPlaying.value) {
            freezeProgressAtCurrent()
            _isPlaying.value = false
            externalPlayingCallback?.invoke(false)
        }
        pushOverlayPlayingState(false, immediate = true)
        reconcileProgressOverlayTicker(false)
        hideVinylJob = scope.launch {
            delay(QUEUE_CLEAR_CONFIRM_MS)
            pushOverlayPlayingState(false, immediate = true)
            reconcileProgressOverlayTicker(false)
            // Same 30s settle as pause yield before soft→FAB / legacy teardown.
            delay(QUEUE_CLEAR_SOFT_FAB_DELAY_MS)
            // Soft → FAB; remembered FAB spot must survive (hide force=false).
            VinylCoverService.hide(context, force = false)
            VinylCoverService.beginQueueClearedGrace(context)
        }
    }

    /**
     * Final empty-queue teardown — invoked by [VinylCoverService] after the FAB
     * grace (60s) expires. Safe to call more than once.
     */
    fun finishQueueClearedTeardown() {
        cancelHideJob()
        VinylCoverService.cancelPauseIdleTeardown(context)
        VinylCoverService.endQueueClearedGrace(context)
        applyQueueClearedLocalState()
        VinylCoverService.hide(context, force = true)
    }

    /**
     * Our sanctioned pairing dissolved (MA unsync re-homed us to a solo
     * group). The re-home rides foreign probation, which swallows the
     * stream/clear + stream/end that would normally start the teardown
     * timeline — so the ex-follower kept the leader's title/artist painted
     * forever while only the cover (whose artwork stream died with the
     * session) went away. Wipe like an MA queue clear: this device has no
     * queue of its own anymore.
     */
    private fun handleSharedGroupDissolved() {
        Log.i(tag, "shared group dissolved: wiping mirrored identity + playhead")
        paintWaitingForMediaAfterMaQueueClear()
    }

    /**
     * MA clear-queue / unsync peer wipe / upstream empty current-item.
     * Soft shell → localized waiting-for-media; no FAB grace / force-hide.
     * Also drops [queueClearedIdentity*] so a later PCM blip cannot resurrect
     * the leader's title/artist onto the peer after clear.
     */
    fun paintWaitingForMediaAfterMaQueueClear() {
        cancelHideJob()
        VinylCoverService.endQueueClearedGrace(context)
        VinylCoverService.cancelPauseIdleTeardown(context)
        clearTrackJumpSeam()
        // Intentional wipe — do not keep stream/end resurrection snapshot.
        queueClearedIdentityTitle = null
        queueClearedIdentityArtist = null
        queueClearedIdentityAlbum = null
        queueClearedIdentityArtworkUrl = null
        resetSendspinMetadataCache()
        hasBinaryArtwork = false
        lastAudibleElapsedRealtimeMs = 0L
        _isActive.value = false
        if (_isPlaying.value) {
            _isPlaying.value = false
            externalPlayingCallback?.invoke(false)
        }
        lastPushedOverlayPlaying = null
        pushOverlayPlayingState(false, immediate = true)
        reconcileProgressOverlayTicker(false)
        pushOverlayProgress(0L, 0L)
        VinylCoverService.resetToWaitingForMedia(context)
    }

    /**
     * Drop Sendspin sticky caches, playhead, overlay titles/cover/progress, and
     * process memory snapshot. Call only when tearing down after a confirmed
     * empty queue — never mid soft-window or the expanded player goes blank.
     */
    private fun applyQueueClearedLocalState() {
        // Keep a resurrection snapshot: pause-typed stream/end + same-track resume
        // delivers PCM without metadata; this is the only way back before next track.
        if (!cachedSendspinTitle.isNullOrEmpty()) {
            queueClearedIdentityTitle = cachedSendspinTitle
            queueClearedIdentityArtist = cachedSendspinArtist
            queueClearedIdentityAlbum = cachedSendspinAlbum
            queueClearedIdentityArtworkUrl = cachedSendspinArtworkUrl
        }
        resetSendspinMetadataCache()
        hasBinaryArtwork = false
        lastAudibleElapsedRealtimeMs = 0L
        _isActive.value = false
        if (_isPlaying.value) {
            _isPlaying.value = false
            externalPlayingCallback?.invoke(false)
        }
        lastPushedOverlayPlaying = null
        pushOverlayPlayingState(false, immediate = true)
        pushOverlayProgress(0L, 0L)
        VinylCoverService.clearContentForQueueCleared(context)
    }

    /** HTTP artwork_url only when binary cover is not already present. */
    /**
     * Absolute form of a protocol `artwork_url`, or null when it cannot be made
     * fetchable. Everything downstream (cover fetch, overlay show, overlay memory
     * cache) is fed through here so no consumer has to re-guess a scheme or host.
     *
     * A relative URL used to be dropped without a word, which made "the server
     * sent no cover" and "the server sent a path we refused" identical from the
     * overlay — so unresolvable values are logged once each.
     */
    private fun resolvedArtworkUrl(raw: String?): String? {
        val url = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        client?.resolveServerRelativeUrl(url)?.let { return it }
        if (lastUnresolvedArtworkUrl != url) {
            lastUnresolvedArtworkUrl = url
            Log.w(tag, "artwork_url has no resolvable server origin: $url")
        }
        return null
    }

    private fun maybeFetchArtworkUrl(url: String?) {
        if (!isSendspinVinylUiEnabled()) return
        if (hasBinaryArtwork) return
        val resolved = resolvedArtworkUrl(url) ?: return
        VinylCoverService.updateCover(context, resolved)
    }

    private fun handleMetadata(metadata: SendspinMetadata) {
        val wasPlaying = _isPlaying.value || isAudiblyPlayingNow()
        if (metadata.isPlaying == true) {
            seamPendingPause = false
        }
        // speed=0 during jump / cold-start gap: never demote while audio flows.
        val deferPause =
            metadata.isPlaying == false &&
                (
                    inTrackJumpSeam() ||
                        inReseatPlayingHold() ||
                        isAudiblyPlayingNow() ||
                        inTrackChangeRescueWindow() ||
                        wasPlaying && awaitingSeekReseat
                    )
        val effectiveMetadata =
            if (deferPause) {
                seamPendingPause = true
                if (!inTrackJumpSeam() &&
                    (isAudiblyPlayingNow() || awaitingSeekReseat ||
                        inReseatPlayingHold() || inTrackChangeRescueWindow())
                ) {
                    armTrackJumpSeam()
                }
                metadata.copy(isPlaying = null, playbackSpeed = null)
            } else {
                metadata
            }
        val becomingPaused = wasPlaying && effectiveMetadata.isPlaying == false
        if (becomingPaused) {
            // Final guard: PCM still leaving the speaker → not a real pause.
            // Reseat hold: our own pause→play ACK must not stick as user pause.
            if (isAudiblyPlayingNow() || inReseatPlayingHold()) {
                seamPendingPause = true
                if (!inTrackJumpSeam()) armTrackJumpSeam()
            } else {
                freezeProgressAtCurrent()
                _isPlaying.value = false
                externalPlayingCallback?.invoke(false)
            }
        }

        val merged = mergeSendspinMetadata(
            if (becomingPaused && (isAudiblyPlayingNow() || inReseatPlayingHold())) {
                effectiveMetadata.copy(isPlaying = null, playbackSpeed = null)
            } else {
                effectiveMetadata
            }
        )
        val artworkUrl = merged.artworkUrl
        val becomingPlaying = !_isPlaying.value && merged.isPlaying == true

        if (becomingPlaying) {
            reanchorProgressClock(
                pickResumeSeed(merged.trackProgressMs ?: client?.upstreamTrackProgressMs())
            )
        }

        merged.isPlaying?.let { playing ->
            if (playing) {
                if (_isPlaying.value != true) {
                    _isPlaying.value = true
                    externalPlayingCallback?.invoke(true)
                    val nowElapsed = SystemClock.elapsedRealtime()
                    progressFollowUntilElapsedRealtime = nowElapsed + PROGRESS_FOLLOW_WINDOW_MS
                    lastProgressResyncElapsedRealtime = nowElapsed
                    if (cachedPlaybackSpeed <= 0) cachedPlaybackSpeed = 1000
                }
            } else if (!isAudiblyPlayingNow() && !inReseatPlayingHold() && _isPlaying.value) {
                _isPlaying.value = false
                externalPlayingCallback?.invoke(false)
            }
        }

        val isPlaying = merged.isPlaying == true || isAudiblyPlayingNow() || _isPlaying.value && deferPause
        val hasTitle = !merged.title.isNullOrEmpty()
        if (hasTitle || merged.isPlaying != null || isAudiblyPlayingNow()) {
            _isActive.value = true
        }

        if (!hasReceivedFirstMetadata) {
            hasReceivedFirstMetadata = true
            val usable = hasTitle || !artworkUrl.isNullOrEmpty() || merged.isPlaying != null
            if (!usable) return
        }

        val vinylEnabled = isSendspinVinylUiEnabled()
        val canPaint = canPaintSendspinOverlay(
            allowPipelineRebindRefresh = VinylCoverService.isAwaitingPipelineRebind(),
        )
        updateProgressCache(merged)
        // Overlay bar may prefer Mass API UI bridge; SP displayProgressMs stays for protocol.
        val positionMs = overlayBarProgressMs()
        val durationMs = if (bridgedUiProgressActive) {
            bridgedEffectiveDurationMs()
        } else {
            merged.trackDurationMs ?: cachedDurationMs
        }

        if (isPlaying) {
            // Stale isPlaying after stream/end must not cancel the empty-queue hide.
            // Real resume always clears streamEnded on stream/start first.
            if (client?.isStreamEnded() == true && !isAudiblyPlayingNow()) {
                reconcileProgressOverlayTicker(false)
            } else {
                cancelHideJob()
                // Protocol "playing" + title is not enough after service churn — need
                // Media Controls + audible/stream evidence (see [canPaintSendspinOverlay]).
                if (hasTitle && canPaint) {
                    val paintPlaying = overlayPlayingForPaint()
                    val barMs = paintBarProgressMs(positionMs)
                    VinylCoverService.show(
                        context = context,
                        coverUrl = artworkUrl.takeUnless { hasBinaryArtwork },
                        songTitle = merged.title,
                        artistName = merged.artist,
                        albumName = merged.album,
                        isPlaying = paintPlaying,
                        currentTimeMs = barMs,
                        totalTimeMs = durationMs,
                        isSendspinSource = true,
                        lyricAudibleLagMs = overlayLyricAudibleLagMs(barProgressMs = barMs),
                    )
                    lastPushedOverlayPlaying = paintPlaying
                    pushOverlayPlayingState(true)
                    clearPipelineRebindLatch()
                } else if (vinylEnabled) {
                    pushOverlayPlayingState(true)
                    pushOverlayProgress(positionMs, durationMs)
                    if (VinylCoverService.isAwaitingPipelineRebind()) {
                        refreshVinylFromCacheIfNeeded(ignoreEnabledGate = true)
                        clearPipelineRebindLatch()
                    }
                }
                maybeFetchArtworkUrl(artworkUrl)
                reconcileProgressOverlayTicker(true)
            }
        } else if (merged.isPlaying == false && !isAudiblyPlayingNow()) {
            reconcileProgressOverlayTicker(false)
            if (vinylEnabled) {
                pushOverlayPlayingState(false)
                pushOverlayProgress(positionMs, durationMs)
                // Paused sticky title must not birth a fresh FAB after restart —
                // only refresh when shell / audible evidence already allows paint.
                if (hasTitle && client?.isStreamEnded() != true) {
                    refreshVinylFromCacheIfNeeded()
                }
            }
            maybeFetchArtworkUrl(artworkUrl)
            if (wasPlaying && System.currentTimeMillis() >= connectionHandoffUntilMs) {
                retainOverlayAfterPause()
            }
        } else if (vinylEnabled) {
            if (canPaint || VinylCoverService.isLiveOverlayShellVisible()) {
                VinylCoverService.updateMetadata(
                    context = context,
                    songTitle = merged.title,
                    artistName = merged.artist,
                    albumName = merged.album,
                    isPlaying = overlayPlayingForPaint(),
                    currentTimeMs = paintBarProgressMs(positionMs),
                    totalTimeMs = durationMs,
                    isSendspinSource = true,
                )
            }
            maybeFetchArtworkUrl(artworkUrl)
            pushOverlayProgress(positionMs, durationMs)
            if (merged.trackProgressMs != null || !merged.title.isNullOrEmpty()) {
                refreshVinylFromCacheIfNeeded()
            }
            reconcileProgressOverlayTicker(_isPlaying.value || isAudiblyPlayingNow())
        }
        merged.repeatMode?.let { cachedMetadataRepeatMode = it }
        merged.shuffleEnabled?.let { cachedMetadataShuffleEnabled = it }

        VinylCoverService.updatePlaybackSettings(
            context = context,
            repeatMode = effectiveRepeatMode(merged),
            shuffleEnabled = effectiveShuffleEnabled(merged)
        )
    }

    private fun handleControllerState(delta: SendspinControllerStateDelta) {
        if (delta.repeatModePresent) {
            cachedControllerRepeatMode = delta.repeatMode
            // Protocol owns transport now — drop frontend bridge sticky value.
            bridgedUiRepeatMode = null
        }
        if (delta.shufflePresent) {
            cachedControllerShuffleEnabled = delta.shuffleEnabled
            bridgedUiShuffleEnabled = null
        }
        if (delta.groupVolumePresent) {
            // Server push: update UI/PCM gain only — do not move device STREAM_MUSIC volume.
            // Player volume still arrives via server/command; this matches pre-change behavior.
            delta.groupVolume?.let { applyGroupVolumeFromServer(it, syncDeviceOutput = false) }
        }
        if (delta.groupMutedPresent) {
            delta.groupMuted?.let { applyGroupMuteFromServer(it, syncDeviceOutput = false) }
        }
        val vinylEnabled = isSendspinVinylUiEnabled()
        if (!vinylEnabled) return
        if (delta.repeatModePresent || delta.shufflePresent) {
            VinylCoverService.updatePlaybackSettings(
                context = context,
                repeatMode = if (delta.repeatModePresent) {
                    cachedControllerRepeatMode ?: cachedMetadataRepeatMode
                } else {
                    null
                },
                shuffleEnabled = if (delta.shufflePresent) {
                    cachedControllerShuffleEnabled ?: cachedMetadataShuffleEnabled
                } else {
                    null
                }
            )
        }
    }

    private fun effectiveRepeatMode(metadata: SendspinMetadata): String? =
        cachedControllerRepeatMode
            ?: bridgedUiRepeatMode
            ?: cachedMetadataRepeatMode
            ?: metadata.repeatMode

    private fun effectiveShuffleEnabled(metadata: SendspinMetadata): Boolean? =
        cachedControllerShuffleEnabled
            ?: bridgedUiShuffleEnabled
            ?: cachedMetadataShuffleEnabled
            ?: metadata.shuffleEnabled

    private fun applyGroupVolumeFromServer(volumePercent: Int, syncDeviceOutput: Boolean) {
        if (syncDeviceOutput) {
            applyPlayerVolumeFromUpstream(volumePercent, persist = true, syncClient = true)
            return
        }
        // Group state push: vinyl UI only. Per-player volume still arrives via server/command.
        val vinylEnabled = isSendspinVinylUiEnabled()
        if (vinylEnabled && _isActive.value) {
            VinylCoverService.updatePlaybackSettings(
                context = context,
                volumeLevel = effectiveVolumeLinear(),
            )
        }
    }

    /**
     * Apply MA / HA commanded volume (0–100) to STREAM_MUSIC once.
     * Mirrors use the *actual* step after write so percent↔step stays idempotent (#106).
     * [syncClient] false when [SendspinClient] already echoed server/command volume.
     */
    private fun applyPlayerVolumeFromUpstream(
        percent: Int,
        persist: Boolean,
        syncClient: Boolean,
    ) {
        val clamped = percent.coerceIn(0, 100)
        upstreamVolumeApplyUntilMs = SystemClock.elapsedRealtime() + 900L
        setDeviceVolume(clamped)
        // Always re-derive from the live STREAM_MUSIC step (not the requested percent).
        val actualPercent = getDeviceVolume()
        currentVolume = actualPercent / 100f
        if (persist) {
            setVolumeCallback?.invoke(actualPercent)
        }
        if (syncClient) {
            client?.updateVolume(actualPercent)
        }
        applyOutputRouting()
        externalVolumeCallback?.invoke(currentVolume)
        cachedStats = null
    }

    private fun isUpstreamVolumeApplyActive(): Boolean =
        SystemClock.elapsedRealtime() < upstreamVolumeApplyUntilMs

    /** Logical Sendspin volume is always system STREAM_MUSIC. */
    private fun resolvePlayerVolumePercent(): Int = getDeviceVolume()

    private fun resolvePlayerMuted(): Boolean = getMutedCallback?.invoke() ?: false

    private fun effectiveVolumeLinear(): Float = getDeviceVolumeLinear()

    private fun refreshLocalPlayerVolumeState(persistDeviceVolume: Boolean = false) {
        val percent = resolvePlayerVolumePercent()
        currentVolume = percent / 100f
        currentMuted = resolvePlayerMuted()
        if (persistDeviceVolume) {
            setVolumeCallback?.invoke(percent)
        }
    }

    private fun applyGroupMuteFromServer(muted: Boolean, syncDeviceOutput: Boolean) {
        currentMuted = muted
        if (syncDeviceOutput) {
            setMutedCallback?.invoke(muted)
            setDeviceMuted(muted)
        }
        applyOutputRouting()
        externalMuteCallback?.invoke(muted)
    }

    private fun mergeSendspinMetadata(metadata: SendspinMetadata): SendspinMetadata {
        // Compare against [progressIdentityTitle], not the painted title: MA writes
        // the painted one ahead of this packet, which would dedupe the reset below.
        if (!metadata.title.isNullOrEmpty() && metadata.title != progressIdentityTitle) {
            if (inUpstreamSkipStorm() &&
                cachedSendspinTitle != null &&
                metadata.title != cachedSendspinTitle
            ) {
                Log.i(
                    tag,
                    "skip-storm: hold identity/progress through SP title '${metadata.title}'",
                )
                return metadata.copy(
                    title = cachedSendspinTitle,
                    artist = cachedSendspinArtist,
                    album = cachedSendspinAlbum,
                    artworkUrl = cachedSendspinArtworkUrl,
                    trackProgressMs = cachedProgressMs,
                    trackDurationMs = cachedDurationMs,
                    metadataTimestampUs = cachedMetadataTimestampUs,
                    playbackSpeed = cachedPlaybackSpeed.takeIf { it > 0 },
                )
            }
            // prePaintedByMa means MA already wrote the display title, so comparing
            // against it would have deduped this reset and leaked the values logged
            // as held* into the new track. Must stay first — the block clears them.
            Log.i(
                tag,
                "track change: progress generation " +
                    "'${progressIdentityTitle ?: "-"}' -> '${metadata.title}' " +
                    "(prePaintedByMa=${cachedSendspinTitle == metadata.title}, " +
                    "heldProgress=$cachedProgressMs, heldDuration=$cachedDurationMs)",
            )
            progressIdentityTitle = metadata.title
            cachedSendspinArtist = null
            cachedSendspinAlbum = null
            cachedSendspinArtworkUrl = null
            lastUnresolvedArtworkUrl = null
            identityFilledFromPeer = false
            // Allow URL fallback until the next binary artwork packet arrives.
            hasBinaryArtwork = false
            // New track: accept near-0 progress as real (not old-MA fake-zero).
            // Align is pause→play — do not protocol hard-seat here (that delayed
            // ~1s into the next song and then fought PLAY).
            pendingTrackIdentityReseat = true
            // Title-only: hold the playhead. Inventing 0 here rewound mid-track
            // joins and user seeks after a peer/MA identity fill. A later
            // track_progress (incl. near-0 auto-next) still reseats.
            if (metadata.trackProgressMs == null) {
                Log.i(tag, "track change: title-only, hold playhead until progress")
                if (metadata.trackDurationMs != null && metadata.trackDurationMs > 0L) {
                    cachedDurationMs = metadata.trackDurationMs
                }
                if (metadata.playbackSpeed != null && metadata.playbackSpeed > 0) {
                    cachedPlaybackSpeed = metadata.playbackSpeed
                }
            } else {
            clearHeldProgressForTrackChange()
            progressWaitForAudible = true
            audibleTailMs = null
            // New identity, new duration. Sticky old duration would wrongly clamp
            // radio's continuous elapsed and defeat the no-duration nudge gate on
            // track→radio; a positive duration in this same message refills below.
            cachedDurationMs = null
            // From here the Sendspin seat is this track's (null until its own
            // packet refills it), so the bridge may trust it again.
            bridgedDurationSeatDropped = false
            // Soft UI seed only. pause→play is gated in maybeNudge — never fire
            // at open-lead / after stream/clear (auto-next ~1s stop).
            // Open-lead uses floor-to-0; mid-track soft seed may ±1s nearest seat.
            // Duration is still null here (cleared above) so pass this packet's
            // duration: a stale SP leftover at ~track end must not become the
            // cold-join seed (188257ms while the master sits at 27s).
            val rawSeed = alignOpenLeadProgressForUpstream(metadata.trackProgressMs)
            val droppedTailSeed = looksLikeUnconfirmedDurationTail(
                rawSeed,
                durationMs = metadata.trackDurationMs,
                heldMs = 0L,
            )
            noteStreamAttach()
            if (droppedTailSeed) {
                // Stale SP leftover at ~track end. Do not invent 0 — the master
                // already holds the live seat and will UDP it onto this device.
                Log.i(
                    tag,
                    "track change: drop cold-join duration-tail seed ${rawSeed}ms " +
                        "(wait for peer seat)",
                )
            } else {
            val seed = run {
                val duration = metadata.trackDurationMs
                if (duration != null && duration > 0L) {
                    rawSeed.coerceAtMost(duration).coerceAtLeast(0L)
                } else {
                    rawSeed.coerceAtLeast(0L)
                }
            }
            progressHeadMs = seed
            progressHeadElapsedRealtime = 0L
            cachedProgressMs = seed
            lastGoodOverlayProgressMs = seed
            trustedUpstreamProgressSeedMs = seed
            // Boundary-anchor the audible channel: gapless/ICY metadata lands
            // buffer-lead seconds before the boundary is audible. seed@snapshot-ts
            // keeps old-tail chunks silent and maps the first new-track write to
            // its true position (also seats cold-start joins mid-track instead of
            // the auto-arm-at-0 restart).
            //
            // Stale-timestamp guard: some servers leave metadata.timestamp at
            // stream play_at while track_progress is already mid-track. Mapping
            // seed@(play_at) then adds (chunk-play_at) ≈ seed again → bar runs
            // ~2× ahead (the cold-join "+30s" shape). Pending-arm on the next
            // write seats seed at the current chunk instead.
            val boundaryTsUs = metadata.metadataTimestampUs
            val playAt = client?.currentStreamKey()
            val boundaryLooksLikePlayAt =
                boundaryTsUs != null &&
                    playAt != null &&
                    seed > PROGRESS_OPEN_LEAD_MAX_MS &&
                    kotlin.math.abs(boundaryTsUs - playAt) <= STALE_METADATA_TS_PLAY_AT_SLACK_US
            if (boundaryTsUs != null && !boundaryLooksLikePlayAt) {
                val rawSpeed = metadata.playbackSpeed ?: cachedPlaybackSpeed
                val speed = if (rawSpeed <= 0 &&
                    (_isPlaying.value || isAudiblyPlayingNow() || wasRecentlyAudible())
                ) {
                    1000
                } else {
                    rawSpeed
                }
                client?.calibrateAudibleProgressAtBoundary(
                    progressMs = seed,
                    boundaryServerTsUs = boundaryTsUs,
                    playbackSpeed = speed,
                    durationMs = metadata.trackDurationMs ?: cachedDurationMs,
                )
            } else {
                if (boundaryLooksLikePlayAt) {
                    Log.w(
                        tag,
                        "track change: metadata.timestamp≈play_at with seed=${seed}ms — " +
                            "pending-arm to avoid double-count",
                    )
                }
                calibrateAudibleFromTrusted(
                    progressMs = seed,
                    metadataTimestampUs = null,
                    playbackSpeed = metadata.playbackSpeed ?: cachedPlaybackSpeed,
                )
            }
            pushOverlayProgress(seed, metadata.trackDurationMs ?: cachedDurationMs)
            if (_isPlaying.value || isAudiblyPlayingNow() || wasRecentlyAudible()) {
                if (!inUpstreamSkipStorm()) {
                    // Arm only — progress path / maybeNudge decide whether to fire.
                    pendingTrackChangePausePlayNudge = true
                    armTrackChangeRescueWindow("sp-metadata")
                }
            }
            }
            // Consume identity reseat only — do NOT clear awaitingSeekReseat
            // (stream/clear may still own the PCM restart).
            pendingTrackIdentityReseat = false
            if (metadata.trackDurationMs != null && metadata.trackDurationMs > 0L) {
                cachedDurationMs = metadata.trackDurationMs
            }
            if (metadata.playbackSpeed != null && metadata.playbackSpeed > 0) {
                cachedPlaybackSpeed = metadata.playbackSpeed
            }
            }
        }
        // Sticky merge: only non-empty values update cache. Leaf JSON null/"" is
        // ignored here so pause packets that null title cannot wipe identity.
        // Real queue-clear uses metadata:null / stream/end → scheduleQueueClearedHide.
        val prevTitle = cachedSendspinTitle
        val prevArtist = cachedSendspinArtist
        val prevAlbum = cachedSendspinAlbum
        val prevArt = cachedSendspinArtworkUrl
        if (!metadata.title.isNullOrEmpty()) cachedSendspinTitle = metadata.title
        if (!metadata.artist.isNullOrEmpty()) cachedSendspinArtist = metadata.artist
        if (!metadata.album.isNullOrEmpty()) cachedSendspinAlbum = metadata.album
        // Cache the absolute form only: a path we cannot resolve is not a cover,
        // and the track-change reset above already cleared the previous one.
        resolvedArtworkUrl(metadata.artworkUrl)?.let { cachedSendspinArtworkUrl = it }
        if (metadata.trackDurationMs != null && metadata.trackDurationMs > 0L) {
            cachedDurationMs = metadata.trackDurationMs
        }
        // Progress / speed: gated. Accepted samples become a new 首 only — never drive UI alone.
        applyUpstreamProgressFields(
            trackProgressMs = metadata.trackProgressMs,
            metadataTimestampUs = metadata.metadataTimestampUs,
            playbackSpeed = metadata.playbackSpeed,
            playingHint = metadata.isPlaying,
        )
        if (cachedSendspinTitle != prevTitle ||
            cachedSendspinArtist != prevArtist ||
            cachedSendspinAlbum != prevAlbum ||
            cachedSendspinArtworkUrl != prevArt
        ) {
            pokePeerMediaMirror()
        }

        return metadata.copy(
            // Empty string from leaf null must not blank the merged identity.
            title = metadata.title?.takeIf { it.isNotEmpty() } ?: cachedSendspinTitle,
            artist = metadata.artist?.takeIf { it.isNotEmpty() } ?: cachedSendspinArtist,
            album = metadata.album?.takeIf { it.isNotEmpty() } ?: cachedSendspinAlbum,
            artworkUrl = resolvedArtworkUrl(metadata.artworkUrl) ?: cachedSendspinArtworkUrl,
            trackProgressMs = metadata.trackProgressMs ?: cachedProgressMs,
            trackDurationMs = metadata.trackDurationMs ?: cachedDurationMs,
            metadataTimestampUs = metadata.metadataTimestampUs ?: cachedMetadataTimestampUs,
            playbackSpeed = metadata.playbackSpeed ?: cachedPlaybackSpeed,
        )
    }

    /**
     * Local clock from 首. Paused / speed≤0 / pre-PCM / PCM-silent → frozen.
     * While PCM is flowing, this only fills gaps between audible emits; live
     * position authority is [audibleTailMs] via [displayProgressMs].
     */
    private fun localProgressFromHeadMs(): Long? {
        val head = progressHeadMs ?: cachedProgressMs ?: return null
        if (!_isPlaying.value || cachedPlaybackSpeed <= 0 || progressHeadElapsedRealtime <= 0L) {
            return head.coerceAtLeast(0L)
        }
        // Waiting for matching PCM after a playing seek: still walk the
        // tapped second. Freezing here is the paired "finger tap, bar dead
        // in the middle" — leftover writes stay dropped; only the paint walks.
        if (progressWaitForAudible || audibleTailMs == null) {
            if (!groupAllowsSeekWalk()) return head.coerceAtLeast(0L)
            val elapsed = SystemClock.elapsedRealtime() - progressHeadElapsedRealtime
            val deltaMs = elapsed.coerceAtLeast(0L) * cachedPlaybackSpeed / 1000L
            return (head + deltaMs).coerceAtLeast(0L)
        }
        // Pause without speed=0 (or underrun): do not keep inventing seconds.
        if (!isAudiblyPlayingNow()) {
            return (audibleTailMs ?: head).coerceAtLeast(0L)
        }
        val elapsed = SystemClock.elapsedRealtime() - progressHeadElapsedRealtime
        val deltaMs = elapsed.coerceAtLeast(0L) * cachedPlaybackSpeed / 1000L
        return (head + deltaMs).coerceAtLeast(0L)
    }

    /**
     * Progress-bar playhead. While PCM is audible, mapped chunk progress is the
     * authority; wall-clock only smooths gaps between emits (up to
     * [PROGRESS_AUDIBLE_SMOOTH_SLACK_MS]).
     *
     * Lyrics must not consume this fill-in lead — [overlayLyricAudibleLagMs]
     * adds it so highlight tracks the speaker / audible tail after lag subtract.
     *
     * Drift fix is [handleAudibleProgress] reseating 首 on every write — not a
     * razor-thin slack. Slack must cover one audible-emit interval (~180ms) so
     * the bar does not freeze then jump; 250ms-without-reseat was the old bug
     * (rate nudge sawtooth). With reseat, ~one emit gap is enough and safe.
     *
     * This is the **Sendspin branch**. Overlay paint may prefer
     * [overlayBarProgressMs] when Mass API UI bridge is active.
     */
    private fun displayProgressMs(): Long? {
        val tail = audibleTailMs
        if (tail != null && isAudiblyPlayingNow() && !progressWaitForAudible) {
            val audible = clampToDuration(tail)
            val local = localProgressFromHeadMs()?.let { clampToDuration(it) }
            return when {
                local == null -> audible
                // Write clock jumped ahead (catch-up) — never paint behind it.
                local < audible -> audible
                local > audible + PROGRESS_AUDIBLE_SMOOTH_SLACK_MS -> audible
                else -> local
            }
        }
        localProgressFromHeadMs()?.let { return clampToDuration(it) }
        progressHeadMs?.let { return clampToDuration(it) }
        cachedProgressMs?.let { return clampToDuration(it) }
        return lastGoodOverlayProgressMs.takeIf { it > 0L }?.let { clampToDuration(it) }
    }

    /**
     * Overlay progress bar source:
     * 1) local scrub pin (brief, 1s seek grid)
     * 2) standing armed seek/resume assertion
     * 3) Paired UDP pause freeze
     * 4) **Presentation clock** (DAC now vs mapper origin) — the speaker, not
     *    send-ahead metadata. Hard-stepping two send clocks 1–3s apart never
     *    moved the music; this clock is the music.
     * 5) Peer absolute seat / pause freeze while fresh
     * 6) Mass API UI bridge / Sendspin write-clock while buffering
     *
     * Seek pins stay on the MA 1s grid. Steady play is not floored — that
     * turned a 40ms presentation skew into a standing 1s split and a useless
     * hard-step.
     */
    private fun overlayBarProgressMs(): Long? {
        pinnedLocalSeekMs?.let { pin ->
            if (SystemClock.elapsedRealtime() <= pinnedLocalSeekUntilElapsed) {
                return walkPlayingSeatMs(
                    pin,
                    pinnedLocalSeekUntilElapsed - LOCAL_SEEK_PIN_HOLD_MS,
                )
            }
        }
        if (armedAssertionActive()) {
            playheadAnchorMs?.let {
                return walkPlayingSeatMs(it, playheadAnchorArmedAtElapsed)
            }
        }
        pairedAssertionDisplayMs()?.let {
            return clampToDuration(alignSeekPositionForUpstream(it))
        }
        presentationProgressMs()?.let { return it }
        // Absolute seats / pause holds keep both bars on the tap.
        peerAlignedDisplayProgressMs()?.let {
            if (peerPaintAbsoluteSeat || !peerPaintPlaying) {
                return clampToDuration(alignSeekPositionForUpstream(it))
            }
        }
        val raw = if (bridgedUiProgressActive) {
            bridgedDisplayProgressMs()
        } else {
            displayProgressMs()
        } ?: return null
        return clampToDuration(raw)
    }

    /**
     * Speaker playhead: mapper origin + DAC-now on the Sendspin server
     * timeline. Null while buffering, paused, or mid-seek (pins own the bar).
     */
    private fun presentationProgressMs(): Long? {
        if (holdBarUntilMatchingPcm()) return null
        if (!_isPlaying.value && !isAudiblyPlayingNow()) return null
        val presented = client?.presentationTrackProgressMs() ?: return null
        return clampToDuration(presented)
    }

    /**
     * Value for VinylCover show / metadata paints.
     * Mass-owned: clamp only — never run Sendspin fake-zero / pause heuristics on MA.
     * Sendspin-owned: [safeOverlayProgressMs].
     * Seek pins are already 1s-seated inside [overlayBarProgressMs]; steady
     * presentation is not floored.
     */
    private fun paintBarProgressMs(preferredMs: Long? = null): Long? {
        val source = preferredMs ?: overlayBarProgressMs()
        return if (bridgedUiProgressActive) {
            source?.let { clampBridgedDuration(it) }
        } else {
            safeOverlayProgressMs(source)
        }
    }

    /**
     * Interpolate Mass API UI seat; frozen when not playing.
     * Seat + paint stay on the 1s grid — wall delta only advances whole seconds.
     */
    private fun bridgedDisplayProgressMs(): Long? {
        val base = bridgedUiProgressMs ?: return null
        val at = bridgedUiProgressAtElapsedRealtime
        if (bridgedSilenceHold && !isAudiblyPlayingNow()) {
            bridgedUiProgressPlaying = false
            return clampBridgedDuration(alignSeekPositionForUpstream(base))
        }
        if (holdBarUntilMatchingPcm()) {
            val livePlaying = _isPlaying.value || isAudiblyPlayingNow()
            if (!livePlaying || at <= 0L || !groupAllowsSeekWalk()) {
                bridgedUiProgressPlaying = false
                return clampBridgedDuration(alignSeekPositionForUpstream(base))
            }
            // Finger tap while paired: keep interpolating from the seat.
            // Freezing here pinned both bars on the tapped second.
            bridgedUiProgressPlaying = true
            val deltaMs = (SystemClock.elapsedRealtime() - at).coerceAtLeast(0L)
            return clampBridgedDuration(alignSeekPositionForUpstream(base + deltaMs))
        }
        val livePlaying = _isPlaying.value || isAudiblyPlayingNow()
        if (at <= 0L || !livePlaying) {
            if (!livePlaying) bridgedUiProgressPlaying = false
            return clampBridgedDuration(alignSeekPositionForUpstream(base))
        }
        if (!bridgedUiProgressPlaying) {
            // Catch up to the snapshot clock (MA ACK / resume seat). Do not
            // rebase `at` to now — that discarded the shared origin and left
            // paired bars 1–3s apart after open-lead.
            bridgedUiProgressPlaying = true
            if (at <= 0L) {
                bridgedUiProgressAtElapsedRealtime = SystemClock.elapsedRealtime()
                return clampBridgedDuration(alignSeekPositionForUpstream(base))
            }
            val catchUpMs = (SystemClock.elapsedRealtime() - at).coerceAtLeast(0L)
            return clampBridgedDuration(
                alignSeekPositionForUpstream(base + catchUpMs),
            )
        }
        val deltaMs = (SystemClock.elapsedRealtime() - at).coerceAtLeast(0L)
        return clampBridgedDuration(
            alignSeekPositionForUpstream(base + deltaMs),
        )
    }

    /**
     * The only duration the bar may use: MA's own seat, else the Sendspin seat
     * unless it is known to still belong to the previous track
     * ([bridgedDurationSeatDropped]). Null is a supported result — radio has no
     * duration, and no total is better than the wrong one, which would clamp a
     * continuous elapsed to a foreign end.
     */
    private fun bridgedEffectiveDurationMs(): Long? {
        bridgedUiDurationMs?.takeIf { it > 0L }?.let { return it }
        if (bridgedDurationSeatDropped) return null
        return cachedDurationMs
    }

    private fun clampBridgedDuration(positionMs: Long): Long {
        val pos = positionMs.coerceAtLeast(0L)
        val max = bridgedEffectiveDurationMs()
        return if (max != null && max > 0L) pos.coerceAtMost(max) else pos
    }

    /**
     * How far the painted bar sits ahead of the last audible write for smoothness.
     * Zero when not audibly playing / waiting for PCM — lyrics share the bar then.
     */
    private fun displayFillInLeadMs(barProgressMs: Long?): Long {
        val tail = audibleTailMs ?: return 0L
        if (!isAudiblyPlayingNow() || progressWaitForAudible) return 0L
        val bar = barProgressMs?.takeIf { it >= 0L } ?: return 0L
        val audible = clampToDuration(tail)
        return (bar - audible).coerceIn(0L, PROGRESS_AUDIBLE_SMOOTH_SLACK_MS)
    }

    /** @deprecated name kept for call sites — same as local head clock before tail cap. */
    private fun interpolatedProgressMs(): Long? = localProgressFromHeadMs()?.let { clampToDuration(it) }

    /**
     * Audible PCM write → update 活尾. Primary bar authority while playing;
     * lyrics undo bar fill-in via [overlayLyricAudibleLagMs].
     * First mapped write after a wait starts the wall-clock fill-in.
     */
    private fun handleAudibleProgress(positionMs: Long) {
        val nowElapsed = SystemClock.elapsedRealtime()
        // Rescue-window "new track really sounded" clock. This callback is
        // track-attributed (client boundary-hold keeps old-tail chunks out on
        // ts-capable MA); the run re-latch covers legacy MA whose old tail
        // still emits here — a ≥ run-gap silence starts a fresh run.
        if (inTrackChangeRescueWindow() &&
            (trackChangeRescueAudibleStartElapsed == 0L ||
                nowElapsed - lastAudibleElapsedRealtimeMs > TRACK_CHANGE_RESCUE_RUN_GAP_MS)
        ) {
            trackChangeRescueAudibleStartElapsed = nowElapsed
        }
        lastAudibleElapsedRealtimeMs = nowElapsed
        if (!_isPlaying.value) {
            noteTransportAudible()
        } else {
            // PCM is flowing while transport already reads "playing". A peer
            // pause freeze leaves the mapper at speed=0 without touching
            // transport, and nothing else ever thaws it, so the bar stayed
            // frozen for the rest of the track after a partner's pause. This
            // is the case noteTransportAudible()'s own comment claims to
            // cover ("even when we were already playing") — it never ran
            // here because that call is gated on !isPlaying. The thaw keeps
            // its own guards, so a freeze that is still authoritative
            // (partner genuinely paused) is unaffected.
            thawProgressAfterAudibleResume()
        }
        var clamped = clampToDuration(positionMs)
        if (holdBarUntilMatchingPcm()) {
            val seat = playheadAnchorMs
                ?: pinnedLocalSeekMs
                ?: trustedUpstreamProgressSeedMs
                ?: 0L.takeIf { inTrackJumpSeam() }
            if (seat != null && !audiblePcmMatchesSeat(clamped, seat)) {
                if (seatStarvationSpent(nowElapsed, seat)) {
                    abandonStarvedSeat(clamped, seat)
                    return
                }
                Log.w(
                    tag,
                    "drop leftover audible ${clamped}ms (seat ${seat}ms)",
                )
                client?.reseatAudibleOrigin(
                    progressMs = seat,
                    playbackSpeed = cachedPlaybackSpeed.coerceAtLeast(1000),
                )
                return
            }
        }
        val firstAudible = audibleTailMs == null || progressWaitForAudible
        // Seat matching must see this write, not a later seed/head rewrite.
        // Cold-join used to copy leftover last-song into [clamped] and then
        // pcmOnSeat compared the copy — start-of-track 0 never matched.
        val mappedWriteMs = clamped
        val defendingStart = startOfTrackDefenseMs() != null
        // One-shot only: never run on steady play. Catches cold-join
        // double-count (mapped ≫ upstream seed) or auto-arm-at-0 (mapped ≪ seed).
        // Resume uses a tighter threshold so pause→play cannot birth at 2s
        // while the seed sits at mid-track (log: 2000 ≪ 59000).
        if (firstAudible) {
            trustedUpstreamProgressSeedMs?.let { seed ->
                val resumeSkew = if (progressWaitForAudible || inPlayResumeGrace()) {
                    PEER_PROGRESS_RESUME_MAP_SKEW_MS
                } else {
                    COLD_JOIN_MAP_SKEW_MS
                }
                when {
                    clamped > seed + resumeSkew -> {
                        Log.w(
                            tag,
                            "cold-join first audible ${clamped}ms ≫ seed ${seed}ms — reseat",
                        )
                        clamped = seed
                        client?.reseatAudibleOrigin(
                            progressMs = seed,
                            playbackSpeed = cachedPlaybackSpeed.coerceAtLeast(1000),
                        )
                    }
                    // A standing 0 / open-lead seat owns this write. Leftover
                    // mid-track seed is the previous song (upstream never
                    // resets); pulling the intro onto it froze the bar at 0.
                    !defendingStart &&
                        seed > PROGRESS_OPEN_LEAD_MAX_MS &&
                        clamped + resumeSkew < seed -> {
                        Log.w(
                            tag,
                            "cold-join first audible ${clamped}ms ≪ seed ${seed}ms — reseat",
                        )
                        clamped = seed
                        client?.reseatAudibleOrigin(
                            progressMs = seed,
                            playbackSpeed = cachedPlaybackSpeed.coerceAtLeast(1000),
                        )
                    }
                }
            }
            // Prefer the reanchor/freeze head over a bogus near-start map.
            if (!defendingStart) {
                progressHeadMs?.let { head ->
                    if (head > PROGRESS_OPEN_LEAD_MAX_MS &&
                        clamped + PEER_PROGRESS_RESUME_MAP_SKEW_MS < head
                    ) {
                        clamped = head
                        client?.reseatAudibleOrigin(
                            progressMs = head,
                            playbackSpeed = cachedPlaybackSpeed.coerceAtLeast(1000),
                        )
                    }
                }
            }
        }
        val prevTail = audibleTailMs
        if (prevTail != null && !progressWaitForAudible) {
            val forward = clamped - prevTail
            if (forward > PROGRESS_AUDIBLE_TAIL_MAX_FORWARD_MS &&
                forward < PROGRESS_SEEK_THRESHOLD_MS
            ) {
                // Slew, don't freeze. Real timeline splices (late catch-up drops
                // land the next write 0.5–2.5s ahead) used to be rejected with no
                // recovery: every later emit stayed > budget ahead of the stalled
                // tail, so lyrics lagged further until the gap grew seek-sized
                // (2.5s) and snapped — the "drifts more late in the track,
                // sometimes fine" sawtooth. Advancing by the budget per emit
                // (~2.5x real time) converges on honest bursts within ~1s while
                // still bounding a single spurious forward stamp to 450ms,
                // which honest backward emits correct immediately.
                clamped = prevTail + PROGRESS_AUDIBLE_TAIL_MAX_FORWARD_MS
            }
        }
        if (firstAudible) {
            // Prefer absolute seats: local scrub, then mapped write after a PCM
            // wait / frozen wall, then a live wall head. Preferring a stale
            // metadata/hold head over the first mapped write reseated the
            // audible origin late and left lyrics a beat behind for the song.
            // Snapshot once: these fields are cleared from other threads
            // (seek pin release, transport changes), and this runs on the
            // audio thread with no try/catch above it — a `!!` racing a null
            // write was a real NPE that killed the playout job.
            val pinnedSnapshot = pinnedLocalSeekMs
            val headSnapshot = progressHeadMs
            val startMs = when {
                pinnedSnapshot != null -> clampToDuration(pinnedSnapshot)
                awaitingSeekReseat &&
                    headSnapshot != null &&
                    kotlin.math.abs(clamped - headSnapshot) >= PROGRESS_SCRUB_MS ->
                    clamped
                // Coming out of wait-for-PCM / soft-freeze: mapped chunk is truth.
                progressWaitForAudible || progressHeadElapsedRealtime <= 0L -> clamped
                headSnapshot != null && progressHeadElapsedRealtime > 0L -> headSnapshot
                else -> clamped
            }
            val seat = playheadAnchorMs ?: pinnedLocalSeekMs
            // Match the mapped write, not [startMs] and not a cold-join
            // rewrite. Pin / head copy themselves into startMs so they can
            // paint the bar; using that copy here made onSeat tautological
            // for the pin's lifetime. Leftover drain then retired the
            // assertion and poked live UDP at the current epoch.
            val pcmOnSeat = audiblePcmMatchesSeat(mappedWriteMs, seat)
            if (!pcmOnSeat) {
                if (holdBarUntilMatchingPcm()) {
                    if (seatStarvationSpent(nowElapsed, seat)) {
                        abandonStarvedSeat(mappedWriteMs, seat)
                        return
                    }
                    client?.reseatAudibleOrigin(
                        progressMs = seat ?: startMs,
                        playbackSpeed = cachedPlaybackSpeed.coerceAtLeast(1000),
                    )
                }
                return
            }
            // Accepted, so the stream is genuinely alive. Clearing this on
            // mere arrival (as it used to) meant a seat that rejected every
            // chunk still looked healthy and no watchdog could fire on it.
            clearSeatStarvation()
            clearDeadStreamHold()
            awaitingSeekReseat = false
            progressWaitForAudible = false
            progressHeadMs = startMs
            progressHeadElapsedRealtime = SystemClock.elapsedRealtime()
            cachedProgressMs = startMs
            lastGoodOverlayProgressMs = startMs
            audibleTailMs = startMs
            noteStreamAttach()
            if (cachedPlaybackSpeed <= 0) cachedPlaybackSpeed = 1000
            // PCM is on the seat — the assertion may retire and live UDP
            // may start. Leftover chunks never reach here.
            releasePlayheadAssertionToRun()
            pokePeerProgressMirror()
            if (bridgedUiProgressActive) {
                bridgedUiProgressPlaying = true
            }
            client?.reseatAudibleOrigin(
                progressMs = startMs,
                playbackSpeed = cachedPlaybackSpeed.coerceAtLeast(1000),
            )
            reconcileProgressOverlayTicker(true)
            pushOverlayProgress(overlayBarProgressMs() ?: startMs, cachedDurationMs)
            return
        }
        clearSeatStarvation()
        clearDeadStreamHold()
        // Soft-frozen silence → restart fill-in clock from live audible.
        if (progressHeadElapsedRealtime <= 0L) {
            if (cachedPlaybackSpeed <= 0) cachedPlaybackSpeed = 1000
        }
        audibleTailMs = clamped
        // Relock wall fill-in to every accepted write. Without this, head keeps
        // aging at wall rate while audible follows server timestamps / AudioTrack
        // rate nudges — local drifts up to the lead slack then snaps back.
        progressHeadMs = clamped
        progressHeadElapsedRealtime = SystemClock.elapsedRealtime()
        cachedProgressMs = clamped
        if (cachedPlaybackSpeed <= 0) cachedPlaybackSpeed = 1000
        val shown = displayProgressMs() ?: clamped
        if (shown >= PROGRESS_FAKE_ZERO_ANCHOR_MS || lastGoodOverlayProgressMs < PROGRESS_FAKE_ZERO_ANCHOR_MS) {
            lastGoodOverlayProgressMs = shown
        }
        pushOverlayProgress(overlayBarProgressMs() ?: shown, cachedDurationMs)
    }

    private fun calibrateAudibleFromTrusted(
        progressMs: Long,
        metadataTimestampUs: Long?,
        playbackSpeed: Int? = null,
    ) {
        val rawSpeed = playbackSpeed ?: cachedPlaybackSpeed
        // While waiting for / in playback, never arm the mapper frozen (speed=0) —
        // cold-start seam packets would otherwise silence onAudibleProgress forever.
        val speed = when {
            rawSpeed < 0 -> 1000
            rawSpeed == 0 && (_isPlaying.value || progressWaitForAudible || isAudiblyPlayingNow()) -> 1000
            else -> rawSpeed
        }
        client?.calibrateAudibleProgress(
            progressMs = progressMs,
            metadataTimestampUs = metadataTimestampUs,
            playbackSpeed = speed,
            durationMs = cachedDurationMs,
        )
    }

    /**
     * Paint upstream [track_progress] immediately, keep wall-clock frozen
     * ([progressHeadElapsedRealtime]=0 / [progressWaitForAudible]) until PCM.
     * Spec path for seek / track jump display sync — no client progress callback.
     */
    private fun seatUpstreamProgressFrozen(
        positionMs: Long,
        metadataTimestampUs: Long?,
        playbackSpeed: Int?,
    ) {
        // Protocol / MA second grid — hard-seat UI + mapper on whole seconds.
        val clamped = clampToDuration(alignSeekPositionForUpstream(positionMs))
        progressHeadMs = clamped
        progressHeadElapsedRealtime = 0L
        cachedProgressMs = clamped
        lastGoodOverlayProgressMs = clamped
        trustedUpstreamProgressSeedMs = clamped
        cachedMetadataTimestampUs = metadataTimestampUs
        cachedMetadataReceivedAtMs = System.currentTimeMillis()
        if (playbackSpeed != null && playbackSpeed > 0) {
            cachedPlaybackSpeed = playbackSpeed
        }
        calibrateAudibleFromTrusted(
            progressMs = clamped,
            metadataTimestampUs = metadataTimestampUs,
            playbackSpeed = playbackSpeed ?: cachedPlaybackSpeed.coerceAtLeast(1000),
        )
        pushOverlayProgress(clamped, cachedDurationMs)
    }

    /**
     * Seat 首 (and optionally clear 活尾). Used by seek / resume / trusted metadata.
     */
    private fun seatProgressHead(
        positionMs: Long,
        metadataTimestampUs: Long?,
        playbackSpeed: Int? = null,
        resetAudibleTail: Boolean,
    ) {
        var clamped = clampToDuration(positionMs)
        // Mid-play / resume: never seat 首 ahead of 活尾 (metadata often leads ~0.5–1s).
        if (!resetAudibleTail) {
            audibleTailMs?.let { tail ->
                clamped = clamped.coerceAtMost(tail + PROGRESS_METADATA_LEAD_SLACK_MS)
            }
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        progressHeadMs = clamped
        progressHeadElapsedRealtime = nowElapsed
        cachedProgressMs = clamped
        lastGoodOverlayProgressMs = clamped
        trustedUpstreamProgressSeedMs = clamped
        cachedMetadataTimestampUs = metadataTimestampUs
        cachedMetadataReceivedAtMs = System.currentTimeMillis()
        if (playbackSpeed != null && playbackSpeed > 0) {
            cachedPlaybackSpeed = playbackSpeed
        }
        if (resetAudibleTail) {
            audibleTailMs = null
        }
        // Steady-drip guard: when the DAC-anchored presentation clock already
        // agrees with this sample (projected onto "now" on the server clock),
        // keep the measured anchor. Re-seating the mapper from every routine
        // metadata refresh pulled the presentation timeline back into the
        // sender's domain — on send-clock servers that re-introduced the
        // 1-2s jitter-buffer lead the presentation clock exists to remove.
        // Large disagreement (missed seek, stale anchor) still re-anchors.
        if (!resetAudibleTail && metadataTimestampUs != null) {
            val speedNow = (playbackSpeed ?: cachedPlaybackSpeed)
            val live = client
            if (speedNow > 0 && live != null) {
                val presented = live.presentationTrackProgressMs()
                val serverNow = live.serverNowUs()
                if (presented != null && serverNow != null) {
                    val implied =
                        clamped + (serverNow - metadataTimestampUs) * speedNow / 1_000_000L
                    if (kotlin.math.abs(implied - presented) <= PROGRESS_OPEN_LEAD_MAX_MS) {
                        return
                    }
                }
            }
        }
        calibrateAudibleFromTrusted(
            progressMs = clamped,
            metadataTimestampUs = metadataTimestampUs,
            playbackSpeed = playbackSpeed ?: cachedPlaybackSpeed,
        )
    }

    private fun updateProgressCache(metadata: SendspinMetadata) {
        // Duration only — progress 首 was applied in mergeSendspinMetadata.
        metadata.trackDurationMs?.takeIf { it > 0L }?.let { cachedDurationMs = it }
    }

    /**
     * Accept upstream [track_progress].
     *
     * - **Track change / auto-next**: soft UI seed + **pause→play** (primary).
     *   No protocol hard-seat — that path landed ~1s into the next song and
     *   chained a fighting PLAY.
     * - **Local seek / same-track clear**: hard-seat UI + mapper from sparse
     *   progress (MA second grid).
     * - Old-MA paused dirty rollbacks still go through
     *   [acceptPausedUpstreamProgress] only.
     */
    private fun applyUpstreamProgressFields(
        trackProgressMs: Long?,
        metadataTimestampUs: Long?,
        playbackSpeed: Int?,
        playingHint: Boolean?,
    ) {
        val locallyPaused = !_isPlaying.value
        var progressAccepted = false

        if (trackProgressMs != null) {
            val rawClamped = clampToDuration(trackProgressMs)
            val wasAwaitingSeekReseat = awaitingSeekReseat
            val wasIdentityReseat = pendingTrackIdentityReseat
            val pendingTitleNudge = pendingTrackChangePausePlayNudge
            val userSeekPin = pinnedLocalSeekMs != null
            // Seek / scrub only — identity/title no longer force hard-seat.
            val forceSeekReseat = wasAwaitingSeekReseat || userSeekPin
            val upstreamDiscontinuity =
                noteAndDetectUpstreamProgressDiscontinuity(rawClamped, locallyPaused)
            val trackBoundary = lastUpstreamDiscontinuityWasTrackBoundary
            val trackChangeAlign =
                pendingTitleNudge || trackBoundary || wasIdentityReseat

            if (shouldAcceptUpstreamProgress(trackProgressMs, locallyPaused, playingHint)) {
                var clamped = rawClamped
                clamped = applyLocalSeekPin(clamped)
                val predicted = displayProgressMs() ?: lastGoodOverlayProgressMs
                val delta = kotlin.math.abs(clamped - predicted)
                // MA seek grid is 1s — a whole-second jump is always seek-like.
                val secondJump =
                    kotlin.math.abs(
                        clamped / MA_SEEK_SECOND_MS - predicted / MA_SEEK_SECOND_MS,
                    ) >= 1L
                val seekLike =
                    forceSeekReseat ||
                        delta >= PROGRESS_SCRUB_MS ||
                        secondJump ||
                        upstreamDiscontinuity

                if (trackChangeAlign) {
                    // Soft paint only — PCM align is pause→play, not hard-seat.
                    // Open-lead → 0; mid-track may nearest-second (±1s). Never
                    // protocol seek here (that delayed ~1s into auto-next + fought PLAY).
                    var seated = alignOpenLeadProgressForUpstream(clamped)
                    if (looksLikeUnconfirmedDurationTail(seated)) {
                        Log.i(
                            tag,
                            "track-change: drop cold-join duration-tail ${seated}ms " +
                                "(wait for peer seat)",
                        )
                        pendingTrackIdentityReseat = false
                        pendingTrackChangePausePlayNudge = false
                        noteStreamAttach()
                        progressAccepted = true
                    } else if (
                        startOfTrackDefenseMs() != null &&
                        seated > PROGRESS_OPEN_LEAD_MAX_MS
                    ) {
                        // Same rule as the MA bridge: opening a queued track
                        // leaves the upstream on the previous second. Writing
                        // that into seed/head made first-audible ≪-seed
                        // rewrite intro PCM and freeze the bar at 0.
                        Log.d(
                            tag,
                            "drop track-change leftover ${seated}ms " +
                                "(start-of-track seat)",
                        )
                        pendingTrackChangePausePlayNudge = false
                        noteStreamAttach()
                        progressAccepted = true
                    } else {
                    progressHeadMs = seated
                    progressHeadElapsedRealtime = 0L
                    cachedProgressMs = seated
                    lastGoodOverlayProgressMs = seated
                    trustedUpstreamProgressSeedMs = seated
                    noteStreamAttach()
                    cachedMetadataTimestampUs = metadataTimestampUs
                    cachedMetadataReceivedAtMs = System.currentTimeMillis()
                    if (playbackSpeed != null && playbackSpeed > 0) {
                        cachedPlaybackSpeed = playbackSpeed
                    }
                    progressWaitForAudible = true
                    pendingTrackIdentityReseat = false
                    lastProgressResyncElapsedRealtime = SystemClock.elapsedRealtime()
                    progressAccepted = true
                    // Soft seed only. Do not mint a new epoch: the tapper already
                    // claimed 0 on skip, and a second clock here yanked the
                    // master off 152s onto the follower's 11s open-lead.
                    playheadAnchorMs?.let { anchor ->
                        if (stalePeerSeatOnNewTrack(anchor)) {
                            retirePlayheadAssertion()
                        }
                    }
                    pushOverlayProgress(seated, cachedDurationMs)
                    if (_isPlaying.value || isAudiblyPlayingNow() || playingHint == true) {
                        reconcileProgressOverlayTicker(true)
                    }
                    Log.i(
                        tag,
                        "track-change soft progress ${seated}ms " +
                            "(no hard-seat; open-lead safe; nudge gated)",
                    )
                    // Protocol stream/clear already restarts PCM — do not pause→play
                    // on top. Only nudge when clear did not run and we are past
                    // open-lead (true mid-track identity jump without clear).
                    if (!wasAwaitingSeekReseat && !inTrackChangeNudgeGrace()) {
                        maybeNudgePausePlayForUpstreamReseat(
                            upstreamMs = clamped,
                            wasAwaitingSeekReseat = false,
                            userSeekPin = userSeekPin,
                            locallyPaused = locallyPaused,
                            trackBoundary = true,
                        )
                    }
                    pendingTrackChangePausePlayNudge = false
                    }
                } else if (forceSeekReseat || seekLike) {
                    val udpIntent = intentSeatMs()
                    val paired = pairedOnThisStream()
                    val pausedHold = locallyPaused && playingHint != true
                    // Playing: open-lead (≤4s) is the service catching up.
                    // Paused: that same 2–3s ACK jumped the freeze (41183→43480).
                    val held = if (pausedHold) {
                        udpIntent
                            ?: lastGoodOverlayProgressMs.takeIf { it > 0L }
                            ?: progressHeadMs
                    } else {
                        udpIntent
                    }
                    val disagreeLimit = if (pausedHold) {
                        MA_SEEK_SECOND_MS
                    } else {
                        PROGRESS_OPEN_LEAD_MAX_MS
                    }
                    val disagreesIntent = held != null &&
                        kotlin.math.abs(clamped - held) > disagreeLimit
                    val dropRemoteSpSeat = paired &&
                        !userSeekPin &&
                        !inTrackJumpSeam() &&
                        !pendingTrackIdentityReseat &&
                        disagreesIntent
                    // No local intent yet (cold join): SP still publishing the
                    // previous tail must not hard-seat last-second. Wait for
                    // the master's UDP seat — do not invent 0. Paired flag is
                    // false until the first peer datagram, so also cover the
                    // same-packet window after we just attached.
                    val dropColdJoinTail = !userSeekPin &&
                        looksLikeUnconfirmedDurationTail(clamped) &&
                        (paired || inColdJoinAttachWindow())
                    if (disagreesIntent || dropRemoteSpSeat || dropColdJoinTail) {
                        Log.i(
                            tag,
                            "drop SP hard-seat ${clamped}ms" +
                                when {
                                    dropColdJoinTail -> " (cold-join duration-tail)"
                                    held != null -> " (held ${held}ms)"
                                    else -> " (paired, no udp intent)"
                                },
                        )
                    } else {
                    // Local seek / same-track scrub: hard-seat UI + mapper.
                    seatUpstreamProgressFrozen(
                        positionMs = clamped,
                        metadataTimestampUs = metadataTimestampUs,
                        playbackSpeed = playbackSpeed,
                    )
                    awaitingSeekReseat = false
                    pendingTrackIdentityReseat = false
                    lastProgressResyncElapsedRealtime = SystemClock.elapsedRealtime()
                    progressAccepted = true
                    val playingNow =
                        _isPlaying.value || isAudiblyPlayingNow() || playingHint == true
                    if (playingNow) {
                        reconcileProgressOverlayTicker(true)
                    }
                    if (userSeekPin) {
                        progressWaitForAudible = true
                    }
                    // Local seek already claimed an epoch. MA/SP ACK must not
                    // mint another one — that is how paired bars traded clocks
                    // on every metadata confirmation.
                    val justAdoptedPeer =
                        lastAdoptedPeerTimelineElapsed > 0L &&
                            SystemClock.elapsedRealtime() - lastAdoptedPeerTimelineElapsed <
                            PEER_PROGRESS_PAINT_STALE_MS
                    val shouldAnnounceRemoteJump =
                        !userSeekPin &&
                            !justAdoptedPeer &&
                            !paired &&
                            (
                                wasAwaitingSeekReseat ||
                                    (
                                        upstreamDiscontinuity &&
                                            !trackBoundary &&
                                            delta >= MA_SEEK_SECOND_MS
                                    )
                            )
                    if (shouldAnnounceRemoteJump && upstreamJumpNeedsEpoch(clamped)) {
                        bumpPlayheadEpoch("upstream seek", clamped, armed = playingNow)
                    }
                    Log.i(
                        tag,
                        "protocol progress hard-seat at ${clamped}ms " +
                            "(force=$forceSeekReseat seekLike=$seekLike " +
                            "speed=${playbackSpeed ?: cachedPlaybackSpeed})",
                    )
                    // Remote scrub without stream/clear may still need PCM align.
                    // Require seek-sized delta (≥2.5s): MA 1s grid / sparse drip
                    // after grace looked like scrub and false pause→play at 6–10s.
                    // Do NOT whole-track-latch — that left UI@0 vs audible@tail racing.
                    val scrubWithoutClear =
                        upstreamDiscontinuity &&
                            !wasAwaitingSeekReseat &&
                            seekLike &&
                            !progressWaitForAudible &&
                            !inTrackChangeNudgeGrace() &&
                            delta >= PROGRESS_SEEK_THRESHOLD_MS
                    if (scrubWithoutClear) {
                        maybeNudgePausePlayForUpstreamReseat(
                            upstreamMs = clamped,
                            wasAwaitingSeekReseat = wasAwaitingSeekReseat,
                            userSeekPin = userSeekPin,
                            locallyPaused = locallyPaused,
                            trackBoundary = false,
                        )
                    }
                    }
                } else {
                    val preAudiblePlaying =
                        !locallyPaused &&
                            (progressWaitForAudible || audibleTailMs == null) &&
                            !userSeekPin

                    if (preAudiblePlaying) {
                        // Open-lead drip while waiting for PCM — soft seed only.
                        progressHeadMs = clamped
                        progressHeadElapsedRealtime = 0L
                        cachedProgressMs = clamped
                        lastGoodOverlayProgressMs = clamped
                        trustedUpstreamProgressSeedMs = clamped
                        cachedMetadataTimestampUs = metadataTimestampUs
                        cachedMetadataReceivedAtMs = System.currentTimeMillis()
                        if (playbackSpeed != null && playbackSpeed > 0) {
                            cachedPlaybackSpeed = playbackSpeed
                        }
                        lastProgressResyncElapsedRealtime = SystemClock.elapsedRealtime()
                        progressAccepted = true
                        pushOverlayProgress(clamped, cachedDurationMs)
                        if (_isPlaying.value || isAudiblyPlayingNow()) {
                            reconcileProgressOverlayTicker(true)
                        }
                    } else {
                        seatProgressHead(
                            positionMs = clamped,
                            metadataTimestampUs = metadataTimestampUs,
                            playbackSpeed = if (!locallyPaused || playingHint == true) {
                                playbackSpeed
                            } else {
                                0
                            },
                            resetAudibleTail = seekLike,
                        )
                        if (seekLike) {
                            if (locallyPaused && playingHint != true) {
                                cachedPlaybackSpeed = 0
                                audibleTailMs = clamped
                                progressWaitForAudible = false
                                calibrateAudibleFromTrusted(clamped, null, 0)
                                pushOverlayProgress(clamped, cachedDurationMs)
                            } else {
                                seatUpstreamProgressFrozen(
                                    positionMs = clamped,
                                    metadataTimestampUs = metadataTimestampUs,
                                    playbackSpeed = playbackSpeed,
                                )
                                if (userSeekPin) {
                                    progressWaitForAudible = false
                                    progressHeadElapsedRealtime = SystemClock.elapsedRealtime()
                                    audibleTailMs = clamped
                                }
                            }
                        } else if (locallyPaused && playingHint != true) {
                            cachedPlaybackSpeed = 0
                            calibrateAudibleFromTrusted(
                                progressMs = (audibleTailMs ?: progressHeadMs ?: clamped),
                                metadataTimestampUs = null,
                                playbackSpeed = 0,
                            )
                        }
                        lastProgressResyncElapsedRealtime = SystemClock.elapsedRealtime()
                        progressAccepted = true
                    }
                }
            }
        }
        if (playbackSpeed != null) {
            if (locallyPaused && playingHint != true) {
                cachedPlaybackSpeed = 0
            } else if (!progressAccepted) {
                cachedPlaybackSpeed = playbackSpeed
            }
            if (!progressAccepted) {
                cachedMetadataReceivedAtMs = System.currentTimeMillis()
            }
        }
    }

    /**
     * Compare consecutive upstream track_progress samples (not local clock).
     * Steady open-lead advances ≈ wall time; a remote seek / auto-next is a
     * discontinuity while MA stays in playing — track change then uses
     * pause→play (not hard-seat).
     */
    private fun noteAndDetectUpstreamProgressDiscontinuity(
        upstreamMs: Long,
        locallyPaused: Boolean,
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = lastSeenUpstreamProgressMs
        val lastAt = lastSeenUpstreamProgressAtElapsedMs
        lastSeenUpstreamProgressMs = upstreamMs
        lastSeenUpstreamProgressAtElapsedMs = now
        lastUpstreamDiscontinuityWasTrackBoundary = false
        if (locallyPaused || last == null || lastAt <= 0L) return false
        if (progressReseatNudgeInFlight) return false

        // Auto-next / skip: mid/late track → near start of the next song.
        val trackBoundary =
            last >= PROGRESS_FAKE_ZERO_ANCHOR_MS &&
                upstreamMs <= PROGRESS_OPEN_LEAD_MAX_MS &&
                (last - upstreamMs) >= PROGRESS_FAKE_ZERO_DROP_MS
        if (trackBoundary) {
            lastUpstreamDiscontinuityWasTrackBoundary = true
            return true
        }

        val wallMs = (now - lastAt).coerceAtLeast(0L)
        val step = upstreamMs - last
        // Seek / freeze backward while still "playing" on the wire.
        if (step < -PROGRESS_PAUSE_ECHO_MS) return true
        // Forward jump beyond wall drip + scrub slack (remote scrub ahead).
        val expectedMaxForward =
            wallMs + PROGRESS_METADATA_LEAD_SLACK_MS + PROGRESS_SCRUB_MS
        if (step > expectedMaxForward && step >= PROGRESS_SCRUB_MS) return true
        return false
    }

    /**
     * Mid-track PCM align: wire pause→play (no inter-command delay).
     *
     * Never used for open-lead / stream/clear / post-title grace — those already
     * restart audio or are jitter false-scrubs on MA auto-next.
     */
    private fun maybeNudgePausePlayForUpstreamReseat(
        upstreamMs: Long,
        wasAwaitingSeekReseat: Boolean,
        userSeekPin: Boolean,
        locallyPaused: Boolean,
        trackBoundary: Boolean = false,
    ) {
        if (userSeekPin) return
        if (deadStreamBlocksAutoRecover()) return
        if (inUpstreamSkipStorm()) return
        // User-paused: never auto resume. Track-boundary / title change with
        // recent audio may still reseat even if a pause packet raced in.
        if (locallyPaused && !trackBoundary) return
        if (locallyPaused && trackBoundary && !_isPlaying.value && !wasRecentlyAudible()) return
        // stream/clear already restarts the pipeline — never double-nudge.
        if (wasAwaitingSeekReseat) return
        if (inTrackChangeNudgeGrace()) return
        if (!_isPlaying.value && !trackBoundary) return
        if (!trackBoundary && !isAudiblyPlayingNow() && audibleTailMs == null) return
        if (trackBoundary && !isAudiblyPlayingNow() && !wasRecentlyAudible()) return
        // Open-lead / auto-next head: already seated at start — pause→play only
        // fights the new stream (Ignore PLAY / ~1s stop).
        if (trackBoundary && upstreamMs <= PROGRESS_OPEN_LEAD_MAX_MS) {
            Log.d(
                tag,
                "progress reseat nudge skipped: open-lead ${upstreamMs}ms " +
                    "(stream start needs no pause→play)",
            )
            return
        }
        // Radio / flow (no duration): MA pause degrades to stop + a 30s
        // watch-pause auto-stop, and resume rejoins at the live edge — a nudge
        // can only make things worse there.
        val duration = cachedDurationMs
        if (duration == null || duration <= 0L) {
            Log.d(tag, "progress reseat nudge skipped: no duration (radio/flow)")
            return
        }
        if (progressReseatNudgeInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (now < progressReseatNudgeCooldownUntilElapsedMs) return

        progressReseatNudgeInFlight = true
        progressReseatNudgeCooldownUntilElapsedMs = now + PROGRESS_RESEAT_NUDGE_COOLDOWN_MS
        Log.i(
            tag,
            "upstream ${if (trackBoundary) "track-change" else "progress"} " +
                "discontinuity → wire pause→play PCM align (cursor ${upstreamMs}ms)",
        )
        // Intent stays playing for the whole pipeline restart.
        _isPlaying.value = true
        armTrackJumpSeam()
        armReseatPlayingHold()
        scope.launch {
            try {
                if (!sendControllerWireOnly("pause")) {
                    Log.w(tag, "progress reseat nudge: wire pause failed")
                    return@launch
                }
                // MA runs each controller event in its own task and cmd_play
                // silently drops while playback_state is still PLAYING —
                // zero-gap pause→play loses the play and leaves the group
                // paused. Give the pause one beat to land first.
                delay(NUDGE_PAUSE_PLAY_GAP_MS)
                // Server pause echoes are deferred by the reseat hold, so false
                // here can only be a real local pause during the gap — honor it.
                if (!_isPlaying.value) {
                    Log.i(tag, "progress reseat nudge: user paused during gap — abandon play")
                    return@launch
                }
                var playOk = sendControllerWireOnly("play")
                if (!playOk) {
                    Log.w(tag, "progress reseat nudge: wire play failed — retry once")
                    playOk = sendControllerWireOnly("play")
                }
                if (!playOk) {
                    Log.w(
                        tag,
                        "progress reseat nudge: wire play failed after pause — recovery play",
                    )
                    if (!sendMediaCommand("play")) {
                        Log.w(tag, "progress reseat nudge: recovery play also failed")
                    } else {
                        armTrackJumpSeam()
                        armReseatPlayingHold()
                    }
                    return@launch
                }
                cachedPlaybackSpeed = 1000
                progressWaitForAudible = true
                pushOverlayPlayingState(true, immediate = true)
                armTrackJumpSeam()
                armReseatPlayingHold()
                reconcileProgressOverlayTicker(true)
            } finally {
                progressReseatNudgeInFlight = false
            }
        }
    }

    private fun inTrackChangeNudgeGrace(): Boolean =
        SystemClock.elapsedRealtime() < trackChangeNudgeGraceUntilElapsedMs

    private fun armTrackChangeNudgeGrace() {
        trackChangeNudgeGraceUntilElapsedMs =
            SystemClock.elapsedRealtime() + TRACK_CHANGE_NUDGE_GRACE_MS
    }

    /** Wire-only controller command — no optimistic transport / seam side effects. */
    private fun sendControllerWireOnly(command: String): Boolean {
        if (client == null) {
            Log.w(tag, "sendControllerWireOnly($command) dropped: no client")
            return false
        }
        return client?.sendMediaCommand(command = command) == true
    }

    /**
     * Paused upstream progress — single authority for new-MA freeze confirm vs
     * old-MA dirty reject vs seek. Priority:
     * 1) local pin → accept
     * 2) near-head echo → accept (does **not** clear pending confirm)
     * 3) new-MA stable repeat rollback → accept + reseat
     * 4) seek-sized jump either direction → accept (must not wait for repeat)
     * 5) else arm pending / reject (old-MA one-off)
     */
    private fun acceptPausedUpstreamProgress(
        positionMs: Long,
        predicted: Long,
        delta: Long,
    ): Boolean {
        // Local scrub/seek while paused always wins (pin set by our seek path).
        if (pinnedLocalSeekMs != null) {
            pendingPausedRollbackMs = null
            return true
        }

        // Near the frozen head — pause ACK / echo. Keep pending intact so a
        // following new-MA freeze re-report can still confirm.
        if (delta <= PROGRESS_PAUSE_ECHO_MS) {
            return true
        }

        val behind = predicted - positionMs
        if (behind > PROGRESS_PAUSE_ECHO_MS) {
            val pending = pendingPausedRollbackMs
            val nowElapsed = SystemClock.elapsedRealtime()
            // (1) New MA first: stable repeat of the frozen real progress.
            if (
                pending != null &&
                kotlin.math.abs(positionMs - pending) <= PROGRESS_PAUSE_ECHO_MS &&
                nowElapsed - pendingPausedRollbackAtElapsedMs <=
                    PAUSED_ROLLBACK_CONFIRM_WINDOW_MS
            ) {
                Log.i(
                    tag,
                    "Paused rollback confirmed by repeat " +
                        "(${predicted}ms → ${positionMs}ms); accepting upstream freeze",
                )
                pendingPausedRollbackMs = null
                return true
            }
            // (2) Seek-sized jump (user / remote) — both directions. Waiting for
            // a second packet here left the bar stuck under the old-MA reject path.
            if (delta >= PROGRESS_SCRUB_MS) {
                pendingPausedRollbackMs = null
                return true
            }
            // (3) Old MA one-off / new-MA first sample of a small freeze — arm only.
            pendingPausedRollbackMs = positionMs
            pendingPausedRollbackAtElapsedMs = nowElapsed
            return false
        }

        // Forward scrub / remote seek ahead while paused.
        if (positionMs > predicted && delta >= PROGRESS_SCRUB_MS) {
            pendingPausedRollbackMs = null
            return true
        }
        return false
    }

    private fun shouldAcceptUpstreamProgress(
        positionMs: Long,
        locallyPaused: Boolean,
        playingHint: Boolean?,
    ): Boolean {
        val last = lastGoodOverlayProgressMs
        val pausingOrPaused = locallyPaused || playingHint == false
        val predicted = if (pausingOrPaused) {
            (progressHeadMs ?: cachedProgressMs ?: last).coerceAtLeast(0L)
        } else {
            displayProgressMs() ?: last
        }
        val delta = kotlin.math.abs(positionMs - predicted)

        val fakeZeroRollback =
            last >= PROGRESS_FAKE_ZERO_ANCHOR_MS &&
                positionMs < PROGRESS_FAKE_ZERO_MAX_MS &&
                (last - positionMs) > PROGRESS_FAKE_ZERO_DROP_MS

        // Spec seek-to-start after stream/clear is real; pause flashes of 0 are not.
        // Title/queue identity change also makes near-0 real (new track).
        if (fakeZeroRollback && !awaitingSeekReseat && !pendingTrackIdentityReseat) {
            return false
        }

        // After stream/clear, local seek, or new title — next progress is authoritative.
        // MA only pushes on state/jump (~500ms drift), not every ms; must not miss it.
        if (awaitingSeekReseat || pendingTrackIdentityReseat || pinnedLocalSeekMs != null) {
            pendingPausedRollbackMs = null
            return true
        }

        if (pausingOrPaused) {
            return acceptPausedUpstreamProgress(positionMs, predicted, delta)
        }
        pendingPausedRollbackMs = null

        // Pre-audible / waiting for absolute seat after clear: accept any progress
        // (including small seeks) so lyrics can reseat when the server does push.
        if (progressWaitForAudible || audibleTailMs == null || awaitingSeekReseat) {
            return true
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val sinceResync = nowElapsed - lastProgressResyncElapsedRealtime
        val secondJump =
            kotlin.math.abs(positionMs / MA_SEEK_SECOND_MS - predicted / MA_SEEK_SECOND_MS) >= 1L
        // MA pushes sparsely (state / ~500ms drift), not a continuous drip.
        // Accept real jumps (incl. whole-second seeks) + light periodic resync;
        // local wall/PCM fills the gaps between pushes.
        return lastProgressResyncElapsedRealtime == 0L ||
            sinceResync >= PROGRESS_RESYNC_MS ||
            delta >= PROGRESS_SCRUB_MS ||
            secondJump
    }

    /**
     * The hard seat both vinyls must rebuild from after a timeline splice:
     * slider click, queued-track 0, adopted UDP seat, or the last agreed
     * second. Null only when nobody has claimed a position yet (true cold
     * join waiting for the first peer/MA sample).
     */
    private fun standingHardSeatMs(): Long? {
        pinnedLocalSeekMs?.takeIf {
            SystemClock.elapsedRealtime() <= pinnedLocalSeekUntilElapsed
        }?.let { return clampToDuration(it) }
        // Raw armed flag — a start-of-track 0 must survive the armed TTL or
        // [stream/clear] would rebuild from the previous song's last-good.
        if (playheadAnchorArmed) {
            playheadAnchorMs?.let { return clampToDuration(it) }
        }
        lastAdoptedPeerAbsoluteMs?.takeIf {
            SystemClock.elapsedRealtime() - lastAdoptedPeerAbsoluteElapsed <
                LOCAL_SEEK_PIN_HOLD_MS
        }?.let { return clampToDuration(it) }
        trustedUpstreamProgressSeedMs?.let { return clampToDuration(it) }
        progressHeadMs?.let { return clampToDuration(it) }
        return lastGoodOverlayProgressMs.takeIf { it >= 0L }?.let { clampToDuration(it) }
            ?: cachedProgressMs?.let { clampToDuration(it) }
    }

    /**
     * Protocol [stream/clear] wiped the mapper and raised
     * [SendspinClient.needsAbsoluteAudibleSeat]. PCM writes then emit nothing
     * until an absolute seat comes back — and the upstream often never sends
     * one. Re-apply the standing hard seat (slider click / queued 0 / UDP
     * adopt) as a pending-arm origin so the first post-clear chunk rebuilds
     * the audible progress stream and both bars walk from the same second.
     */
    private fun rebuildAudibleStreamFromHardSeat(reason: String) {
        val seat = standingHardSeatMs() ?: return
        trustedUpstreamProgressSeedMs = seat
        progressHeadMs = seat
        cachedProgressMs = seat
        lastGoodOverlayProgressMs = seat
        val playing = _isPlaying.value
        calibrateAudibleFromTrusted(
            progressMs = seat,
            metadataTimestampUs = null,
            playbackSpeed = if (playing) {
                cachedPlaybackSpeed.coerceAtLeast(1000)
            } else {
                0
            },
        )
        Log.i(tag, "$reason: rebuild audible stream from hard-seat ${seat}ms")
    }

    /**
     * Protocol [stream/clear]: seek or track jump. Absolute track ms is unknown
     * until we re-apply the standing hard seat. Hold that seat on the bar;
     * do not keep wall-clock or mapper running on the old absolute position.
     */
    private fun noteStreamClearForProgress() {
        awaitingSeekReseat = true
        pendingPausedRollbackMs = null
        audibleTailMs = null
        progressWaitForAudible = true
        progressHeadElapsedRealtime = 0L
        // New stream timeline — do not compare against previous-track samples.
        lastSeenUpstreamProgressMs = null
        lastSeenUpstreamProgressAtElapsedMs = 0L
        lastUpstreamDiscontinuityWasTrackBoundary = false
        armTrackChangeNudgeGrace()
        val hold = standingHardSeatMs()
        progressHeadMs = hold
        cachedProgressMs = hold
        if (hold != null) {
            lastGoodOverlayProgressMs = hold
        }
        rebuildAudibleStreamFromHardSeat("stream/clear")
        // Walk only when this clear is a playing seek and the peer /
        // upstream are not paused. A pause or idle clear must freeze.
        if (groupAllowsSeekWalk()) {
            startPlayingSeekWalkClock()
            reconcileProgressOverlayTicker(true)
        } else {
            reconcileProgressOverlayTicker(false)
        }
        if (hold != null) {
            pushOverlayProgress(hold, cachedDurationMs, forcePlayhead = true)
        }
        armTrackJumpSeam()
        pokePeerProgressMirror()
    }

    /**
     * Open (or extend) the bounded track-jump seam window and guarantee a
     * reconcile at its end. Deferred ≠ dropped: if the seam expires and the last
     * standing signal was pause **and audio is quiet**, apply it for real.
     */
    private fun armTrackJumpSeam() {
        seamUntilElapsedRealtime = SystemClock.elapsedRealtime() + TRACK_JUMP_SEAM_MS
        seamReconcileJob?.cancel()
        seamReconcileJob = scope.launch {
            delay(TRACK_JUMP_SEAM_MS)
            seamReconcileJob = null
            reconcileTrackJumpSeam()
        }
    }

    private fun inTrackJumpSeam(): Boolean =
        SystemClock.elapsedRealtime() < seamUntilElapsedRealtime

    private fun clearTrackJumpSeam() {
        seamUntilElapsedRealtime = 0L
        seamPendingPause = false
        seamReconcileJob?.cancel()
        seamReconcileJob = null
        // Every explicit local transport command and confirmed queue end funnels
        // through here — auto-rescue must never outlive real user intent.
        trackChangeRescueUntilElapsedRealtime = 0L
    }

    /** Arm the ghost-pause rescue window (track change while playing). */
    private fun armTrackChangeRescueWindow(source: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < trackChangeRescueUntilElapsedRealtime) {
            // Re-arm is not idempotent: it refills the shot budget and re-opens
            // the establishment gate. Every shot is a real same-position seek, so
            // a double arm can double the seeks spent on one boundary — and a
            // seek moves the bar. MA pre-announces the change and the Sendspin
            // packet arms again; this line is what proves how often that happens.
            Log.i(
                tag,
                "rescue window re-armed by $source " +
                    "(${trackChangeRescueUntilElapsedRealtime - now}ms left, " +
                    "discarding shots=$trackChangeRescueShotsSent, " +
                    "audibleRun=${trackChangeRescueAudibleStartElapsed != 0L})",
            )
        } else {
            Log.i(tag, "rescue window armed by $source")
        }
        trackChangeRescueUntilElapsedRealtime = now + TRACK_CHANGE_RESCUE_WINDOW_MS
        trackChangeRescueShotsSent = 0
        trackChangeRescueAudibleStartElapsed = 0L
    }

    private fun inTrackChangeRescueWindow(): Boolean =
        SystemClock.elapsedRealtime() < trackChangeRescueUntilElapsedRealtime

    /**
     * The new track audibly played long enough that a person is listening.
     * A pause standing after that is deliberate (a phone-side user pause also
     * stops the stream) — land it, never fight it. A ghost pause strands the
     * stream before establishment: PCM never came, or died within its first
     * beat (the classic ~1s auto-next stop).
     */
    private fun newTrackAudiblyEstablished(): Boolean {
        val start = trackChangeRescueAudibleStartElapsed
        if (start == 0L) return false
        return lastAudibleElapsedRealtimeMs - start >= TRACK_CHANGE_RESCUE_ESTABLISHED_MS
    }

    /**
     * The rescue shot: same-position seek through the full user-scrub path
     * ([seekTo] → pin + reseat + overlay), exactly like a progress-bar tap —
     * the gesture that provenly always reconnects. MA rebuilds the whole flow
     * stream for a seek regardless of play/pause state and resumes playback
     * by itself. Fires on a proven-dead stream only, so unlike the removed
     * track-change calibration seek it can never interrupt a healthy head.
     */
    private fun rescueReseatSeekToCurrent(): Boolean {
        if (deadStreamBlocksAutoRecover()) return false
        if (inUpstreamSkipStorm()) return false
        val duration = cachedDurationMs
        if (duration == null || duration <= 0L) return false // radio: no seek grid
        if (client?.supportsAbsoluteSeek() == false) return false
        val pos = displayProgressMs() ?: cachedProgressMs ?: progressHeadMs ?: 0L
        return seekTo(pos)
    }

    private fun armReseatPlayingHold() {
        reseatPlayingHoldUntilElapsed =
            SystemClock.elapsedRealtime() + RESEAT_PLAYING_HOLD_MS
    }

    private fun clearReseatPlayingHold() {
        reseatPlayingHoldUntilElapsed = 0L
    }

    private fun inReseatPlayingHold(): Boolean =
        SystemClock.elapsedRealtime() < reseatPlayingHoldUntilElapsed

    /**
     * Seam expired. Apply deferred pause only when PCM has gone quiet — never
     * while the stream is still writing (that was the self-interference bug).
     */
    private fun reconcileTrackJumpSeam() {
        seamUntilElapsedRealtime = 0L
        if (!seamPendingPause) return
        if (isAudiblyPlayingNow()) {
            // Audio recovered inside the seam — keep transport on playing.
            seamPendingPause = false
            clearReseatPlayingHold()
            if (!_isPlaying.value) {
                _isPlaying.value = true
                externalPlayingCallback?.invoke(true)
            }
            pushOverlayPlayingState(true, immediate = true)
            return
        }
        // Ghost pause: a deferred pause is standing, PCM is dead, and we are in
        // the track-change head window with playing intent — nobody paused
        // locally (local pause/stop clears seam + window and drops intent).
        // Whether MA stranded on paused or thinks it is playing over a dead
        // flow, a same-position seek restarts the flow stream and resumes
        // playback — the progress-bar tap that provenly always reconnects.
        // Never pause→play here — the swallowed-PLAY race is exactly what
        // strands the group on pause. The bar stays frozen at the head, so a
        // successful rescue is seamless; after the budget a real pause lands.
        // Establishment gate: once the new track audibly played ≥3s, a standing
        // pause means someone was listening and stopped it — honor it instantly.
        if (_isPlaying.value &&
            !deadStreamBlocksAutoRecover() &&
            inTrackChangeRescueWindow() &&
            !newTrackAudiblyEstablished() &&
            trackChangeRescueShotsSent < TRACK_CHANGE_RESCUE_SHOT_MAX
        ) {
            // Every shot is the full progress-bar-tap path (same-position
            // seek): MA rebuilds the flow stream for a seek regardless of
            // transport state and resumes playback on its own — no wire play
            // needed, and play alone is swallowed in the Ignore-PLAY strand
            // anyway. Play remains only as the radio fallback where no seek
            // grid exists.
            val viaSeek = rescueReseatSeekToCurrent()
            val sent = viaSeek || sendControllerWireOnly("play")
            if (sent) {
                trackChangeRescueShotsSent++
                Log.i(
                    tag,
                    "ghost pause with dead PCM in track-change head — rescue " +
                        "#$trackChangeRescueShotsSent " +
                        "(${if (viaSeek) "reseat seek" else "play, radio fallback"})",
                )
                // Keep the pending pause standing: if this shot is swallowed
                // too, no new pause event will arrive — the next reconcile
                // either sees PCM back or retries/lands within the budget.
                armTrackJumpSeam()
                armReseatPlayingHold()
                return
            }
            Log.w(tag, "ghost pause rescue: send failed")
        } else {
            // Four separate gates collapse into this branch; without the reason a
            // "no rescue happened" report cannot be acted on. Order mirrors the
            // short-circuit above; all four checks are pure reads.
            val reason = when {
                !_isPlaying.value -> "no playing intent"
                !inTrackChangeRescueWindow() -> "window expired"
                newTrackAudiblyEstablished() -> "new track established, real pause"
                else -> "shot budget spent ($trackChangeRescueShotsSent)"
            }
            Log.i(tag, "ghost pause landing without rescue: $reason")
        }
        // Still waiting for PCM after reseat play — extend seam, do not demote.
        if (inReseatPlayingHold()) {
            armTrackJumpSeam()
            return
        }
        seamPendingPause = false
        if (!_isPlaying.value) return
        freezeProgressAtCurrent()
        _isPlaying.value = false
        externalPlayingCallback?.invoke(false)
        pushOverlayPlayingState(false, immediate = true)
        reconcileProgressOverlayTicker(false)
        retainOverlayAfterPause()
    }

    /**
     * Transport glyph for full show()/refresh. Audio stream wins.
     */
    private fun overlayPlayingForPaint(): Boolean {
        if (isAudiblyPlayingNow()) return true
        return lastPushedOverlayPlaying ?: _isPlaying.value
    }

    private fun pinLocalSeekTarget(targetMs: Long) {
        if (!deadStreamConfirmed && !deadStreamAdvancePending) {
            clearDeadStreamHold()
        }
        val clamped = clampToDuration(targetMs)
        pinnedLocalSeekMs = clamped
        pinnedLocalSeekUntilElapsed =
            SystemClock.elapsedRealtime() + LOCAL_SEEK_PIN_HOLD_MS
        // Local scrub supersedes any in-flight new-MA freeze candidate.
        pendingPausedRollbackMs = null
    }

    /**
     * One-shot: absorb MA seek-ACK (second grid + ~1s buffer lead), then release
     * so normal progress can advance. Must not keep clamping for the whole hold.
     */
    private fun applyLocalSeekPin(upstreamMs: Long): Long {
        val pin = pinnedLocalSeekMs ?: return upstreamMs
        val now = SystemClock.elapsedRealtime()
        if (now > pinnedLocalSeekUntilElapsed) {
            pinnedLocalSeekMs = null
            return upstreamMs
        }
        val lead = upstreamMs - pin
        val matches = kotlin.math.abs(lead) <= MA_SEEK_SECOND_MS ||
            lead in 1L..LOCAL_SEEK_ACK_LEAD_MAX_MS
        if (matches) {
            pinnedLocalSeekMs = null
            return pin
        }
        // Stale SP/MA tick during a UDP/finger seat. Do not consume the pin —
        // treating 91963 as "someone else scrubbed" is how the bar snapped
        // off 165000 in the paired logs.
        // holdBarUntilMatchingPcm() already covers a live armed assertion; the
        // separate raw read kept the pin alive after the assertion expired.
        if (holdBarUntilMatchingPcm()) {
            Log.d(tag, "keep seek pin ${pin}ms, drop upstream ${upstreamMs}ms")
            return pin
        }
        pinnedLocalSeekMs = null
        return upstreamMs
    }

    /**
     * Resume seed: catch up to the best mid-track reference among frozen UI,
     * upstream [track_progress], and last audible mapped progress.
     * Still reject fake-zero / large backwards flashes — but do NOT keep a lagging
     * freeze when upstream/audio already sit further into the track.
     *
     * Every candidate must be a value **both** paired devices compute the same
     * way, because [reanchorProgressClock] writes this straight into the audible
     * mapper: a seed that differs by 300ms means the two bars are already apart
     * when the first sample sounds, and only the peer calibrator pulls them back
     * a second later. `lastAudibleTrackProgressMs` is DAC-drain residue — how
     * much buffered PCM this particular device got through before it went quiet
     * — so it is dropped from the candidate set while paired. Upstream
     * `track_progress` comes from the same MA for both ends and stays.
     */
    private fun pickResumeSeed(upstreamMs: Long?): Long {
        val frozen = (
            playheadAnchorMs?.takeUnless { armedAssertionActive() }
                ?: displayProgressMs()
                ?: progressHeadMs
                ?: cachedProgressMs
                ?: lastGoodOverlayProgressMs
            ).coerceAtLeast(0L)
        val audible = client?.lastAudibleTrackProgressMs()?.let { clampToDuration(it) }
        val upstream = upstreamMs?.let { clampToDuration(it) }

        fun unusableRollback(candidate: Long): Boolean {
            if (
                frozen >= PROGRESS_FAKE_ZERO_ANCHOR_MS &&
                candidate < PROGRESS_FAKE_ZERO_MAX_MS &&
                (frozen - candidate) > PROGRESS_FAKE_ZERO_DROP_MS
            ) {
                return true
            }
            return frozen >= PROGRESS_FAKE_ZERO_ANCHOR_MS &&
                candidate + PROGRESS_SEEK_THRESHOLD_MS < frozen
        }

        val candidates = if (pairedOnThisStream()) {
            // Upstream track_progress runs 1–4s ahead of the speaker (open
            // lead). Chasing it on resume is the paired 1–3s gap. The shared
            // freeze is what both bars already agreed on.
            emptyList()
        } else {
            listOfNotNull(upstream, audible)
        }
        var best = frozen
        candidates.forEach { candidate ->
            if (unusableRollback(candidate)) return@forEach
            // Mid / audio ahead of freeze → catch up (pause→play must meet upstream).
            if (candidate > best) best = candidate
        }
        // Cold join: the master's UDP seat is the resume clock, not 0 and not
        // a stale SP duration-tail we never actually played.
        val peerSeat = lastAdoptedPeerAbsoluteMs?.takeIf {
            SystemClock.elapsedRealtime() - lastAdoptedPeerAbsoluteElapsed <
                PEER_PROGRESS_PAUSED_HOLD_MS
        } ?: peerPaintProgressMs ?: projectedPeerRawProgressMs()
        if (peerSeat != null &&
            peerSeat > PROGRESS_OPEN_LEAD_MAX_MS &&
            pinnedLocalSeekMs == null &&
            (best <= PROGRESS_OPEN_LEAD_MAX_MS || looksLikeUnconfirmedDurationTail(best))
        ) {
            Log.i(tag, "resume seed: peer seat ${peerSeat}ms (was ${best}ms)")
            best = peerSeat
        }
        return clampToDuration(best)
    }

    /** Drop 首/活尾 (next / previous / title change). */
    private fun clearHeldProgressForTrackChange() {
        cachedProgressMs = null
        lastGoodOverlayProgressMs = 0L
        progressHeadMs = null
        progressHeadElapsedRealtime = 0L
        audibleTailMs = null
        progressWaitForAudible = true
        trustedUpstreamProgressSeedMs = null
        lastStreamAttachElapsed = 0L
        clearPeerProgressLock()
        lastPeerProgressCalibrateElapsed = 0L
        playResumeGraceUntilElapsed = 0L
        // A new track invalidates the position, not the ordering: keep the epoch
        // counter so the pair stays comparable, but stop asserting a seat that
        // belongs to the previous track.
        retirePlayheadAssertion()
        // Keep awaitingSeekReseat if stream/clear already armed — title must not
        // wipe that or auto-next will false-nudge as "scrub without clear".
        pinnedLocalSeekMs = null
        pinnedLocalSeekUntilElapsed = 0L
        lastProgressResyncElapsedRealtime = 0L
        progressFollowUntilElapsedRealtime = 0L
        cachedMetadataTimestampUs = null
        cachedMetadataReceivedAtMs = 0L
        // Drop previous-track upstream sample so the next progress cannot look
        // like a huge backward/forward hard scrub across the seam.
        lastSeenUpstreamProgressMs = null
        lastSeenUpstreamProgressAtElapsedMs = 0L
        lastUpstreamDiscontinuityWasTrackBoundary = false
        armTrackChangeNudgeGrace()
        client?.clearAudibleProgressAnchor()
    }

    private fun noteStreamAttach() {
        if (lastStreamAttachElapsed == 0L) {
            lastStreamAttachElapsed = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Seat 首 at [positionMs] but keep the wall-clock frozen until the first
     * audible mapped write. Used by resume / protocol playing / seek so the bar
     * does not race the 1–2s AudioTrack / stream restart gap.
     * Always nearest-second hard seat so pause→play can +1s or −1s vs a floored
     * freeze and still meet the server second.
     */
    private fun reanchorProgressClock(positionMs: Long) {
        val clamped = clampToDuration(alignSeekPositionForUpstream(positionMs))
        progressHeadMs = clamped
        progressHeadElapsedRealtime = 0L
        progressWaitForAudible = true
        cachedProgressMs = clamped
        lastGoodOverlayProgressMs = clamped
        audibleTailMs = null
        // Resume/cold-join first-audible guard — must survive stream/clear races.
        trustedUpstreamProgressSeedMs = clamped
        if (cachedPlaybackSpeed <= 0) cachedPlaybackSpeed = 1000
        stampServerProgressSeat(clamped, client?.serverNowUs() ?: 0L)
        calibrateAudibleFromTrusted(
            progressMs = clamped,
            metadataTimestampUs = null,
            playbackSpeed = cachedPlaybackSpeed.coerceAtLeast(1000),
        )
        pushOverlayProgress(clamped, cachedDurationMs, forcePlayhead = true)
    }

    private fun clampToDuration(positionMs: Long): Long {
        val pos = positionMs.coerceAtLeast(0L)
        val duration = cachedDurationMs
        return if (duration != null && duration > 0L) pos.coerceAtMost(duration) else pos
    }

    /**
     * A position from the wire (peer seat, anchor projection) or from our own
     * caches at broadcast time must lie *inside* the current track. Only the
     * projection wobble of a song genuinely on its last seconds may poke past
     * the end; anything further belongs to a different timeline — a leftover
     * from a previous longer track, or a moving anchor replayed long after its
     * stream stopped. Clamping such a value used to seat it on the exact last
     * second: both paired bars flashed to the end of the track and froze there,
     * because every cache then re-seeded resumes and fresh epochs from the
     * clamp. Returns null for cross-timeline garbage, the clamped position
     * otherwise.
     */
    private fun seatWithinTrackOrNull(positionMs: Long): Long? {
        val duration = cachedDurationMs
        if (duration != null && duration > 0L &&
            positionMs > duration + PROGRESS_DURATION_OVERSHOOT_MAX_MS
        ) {
            return null
        }
        return clampToDuration(positionMs)
    }

    /**
     * PCM is flowing again after a pause/skip freeze. Mapper speed=0 and the
     * MA interpolator snapshot would otherwise keep the bar stuck even though
     * audio recovered. Reseat clocks at the held playhead — do not add the
     * freeze gap as a jump.
     */
    private fun thawProgressAfterAudibleResume() {
        // A *partner's* pause freeze still owns the bar — residual PCM drain on
        // our side must not thaw it. Our own freeze is not a veto: PCM coming
        // back is exactly what ends it.
        if (peerPaintFromRemote && peerPausedPaintHoldMs() != null) return
        if (holdBarUntilMatchingPcm()) return
        client?.thawAudibleMapperIfFrozen()
        if (bridgedUiProgressActive && !bridgedUiProgressPlaying) {
            // Keep the MA snapshot timestamp so both ends catch up to the same
            // queue second. Resetting it to now started a second interpolator.
            bridgedUiProgressPlaying = true
        }
    }

    /** Freeze 首 + 活尾 at the current displayed playhead (pause / stop). */
    private fun freezeProgressAtCurrent(claimEpoch: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        playResumeGraceUntilElapsed = 0L
        val mirroredFreeze = peerPausedPaintHoldMs()
        // Mirroring a partner's freeze means we are already carrying its epoch.
        // Claiming a new one for the same position would have both devices bump
        // on every pause, forever, each answering the other.
        val mirroring = peerPaintFromRemote && mirroredFreeze != null
        // Prefer a fresh peer pause lock so master/passive freeze to the same ms.
        // Then the audible tail rather than [displayProgressMs]: the latter adds
        // up to [PROGRESS_AUDIBLE_SMOOTH_SLACK_MS] of wall-clock fill-in, which
        // is per-device smoothing, not a position. Freezing on it puts the two
        // ends a random sub-second apart and skips that much audio on resume.
        val frozen = clampToDuration(
            mirroredFreeze
                ?: client?.presentationTrackProgressMs()
                ?: audibleTailMs?.takeIf { !progressWaitForAudible }
                ?: displayProgressMs()
                ?: localProgressFromHeadMs()
                ?: cachedProgressMs
                ?: lastGoodOverlayProgressMs
        )
        progressHeadMs = frozen
        progressHeadElapsedRealtime = now
        cachedProgressMs = frozen
        lastGoodOverlayProgressMs = frozen
        audibleTailMs = frozen
        progressWaitForAudible = false
        cachedPlaybackSpeed = 0
        cachedMetadataTimestampUs = null
        cachedMetadataReceivedAtMs = System.currentTimeMillis()
        // Same authority for overlay peer-paint and local heads. Paused paint
        // never extrapolates, so holding our own freeze here cannot race the bar.
        peerPaintProgressMs = frozen
        peerPaintAtElapsedRealtime = now
        peerPaintPlaying = false
        peerPaintFromSteady = true
        peerPaintAbsoluteSeat = true
        peerPaintFromRemote = false
        // We are already at this ms — a partner announcing the same freeze is a
        // no-op, a different one still reseats.
        lastAdoptedPeerAbsoluteMs = frozen
        lastAdoptedPeerAbsoluteElapsed = now
        if (bridgedUiProgressActive) {
            bridgedUiProgressMs = frozen
            bridgedUiProgressAtElapsedRealtime = now
            bridgedUiProgressPlaying = false
        }
        calibrateAudibleFromTrusted(
            progressMs = frozen,
            metadataTimestampUs = null,
            playbackSpeed = 0,
        )
        // Force speed=0 even if still briefly "playing"/audible during drain.
        client?.calibrateAudibleProgress(
            progressMs = frozen,
            metadataTimestampUs = null,
            playbackSpeed = 0,
            durationMs = cachedDurationMs,
        )
        reconcileProgressOverlayTicker(false)
        pushPeerSyncedProgress(frozen)
        if (mirroring) {
            seatPlayheadAssertion(
                positionMs = frozen,
                serverTsUs = playheadAnchorServerTsUs.takeIf { it > 0L }
                    ?: localAnchorServerTsUs(),
                speed = 0,
                armed = false,
            )
            pokePeerProgressMirror()
        } else if (claimEpoch) {
            bumpPlayheadEpoch("local pause", frozen, armed = false)
        } else {
            seatPlayheadAssertion(
                positionMs = frozen,
                serverTsUs = localAnchorServerTsUs(),
                speed = 0,
                armed = false,
            )
            pokePeerProgressMirror()
        }
    }

    private fun safeOverlayProgressMs(positionMs: Long?): Long? {
        if (positionMs == null) return null
        val last = lastGoodOverlayProgressMs
        if (!_isPlaying.value) {
            val frozen = (progressHeadMs ?: cachedProgressMs ?: last).coerceAtLeast(0L)
            // Always paint the held playhead while paused/stopped — never let a
            // protocol 0:00 flash or long-pause metadata wipe the bar.
            if (
                frozen >= PROGRESS_FAKE_ZERO_ANCHOR_MS &&
                positionMs < PROGRESS_FAKE_ZERO_MAX_MS &&
                (frozen - positionMs) > PROGRESS_FAKE_ZERO_DROP_MS
            ) {
                return frozen
            }
            val delta = kotlin.math.abs(positionMs - frozen)
            if (delta <= PROGRESS_PAUSE_ECHO_MS) return frozen
            fun paintPausedScrub(targetMs: Long): Long {
                val clamped = clampToDuration(targetMs)
                seatProgressHead(
                    positionMs = clamped,
                    metadataTimestampUs = null,
                    playbackSpeed = 0,
                    resetAudibleTail = true,
                )
                audibleTailMs = clamped
                return clamped
            }
            // Paint-only mirror of [acceptPausedUpstreamProgress] — do not arm /
            // clear pending here (that raced confirm). Seating is owned by
            // applyUpstreamProgressFields; this only avoids painting dirty flashes.
            if (pinnedLocalSeekMs != null && delta >= PROGRESS_SCRUB_MS) {
                return paintPausedScrub(positionMs)
            }
            if (delta <= PROGRESS_PAUSE_ECHO_MS) {
                return frozen
            }
            // Seek-sized jump either direction (user / remote scrub).
            if (delta >= PROGRESS_SCRUB_MS) {
                return paintPausedScrub(positionMs)
            }
            // Small paused rollback — keep frozen until Manager confirms (new MA).
            if (positionMs < frozen) {
                return frozen
            }
            return frozen
        }
        // Playing: trust displayProgressMs (already 首/尾 capped).
        val clamped = clampToDuration(positionMs)
        if (clamped >= PROGRESS_FAKE_ZERO_ANCHOR_MS) {
            lastGoodOverlayProgressMs = clamped
        } else if (last < PROGRESS_FAKE_ZERO_ANCHOR_MS) {
            lastGoodOverlayProgressMs = clamped
        }
        return clamped
    }

    private fun pushOverlayProgress(
        positionMs: Long?,
        durationMs: Long?,
        writer: OverlayProgressWriter = OverlayProgressWriter.Sendspin,
        forcePlayhead: Boolean = false,
    ) {
        // Hard mutex: with Mass queue clock active, plain Sendspin paints are
        // dropped — but LocalSeekUi / PeerSync must still land (paired sync).
        if (bridgedUiProgressActive && writer == OverlayProgressWriter.Sendspin) {
            return
        }
        if (positionMs == null && durationMs == null && !bridgedUiProgressActive) return
        // Seek / progress paints must not be dropped under a stale HA owner.
        if (_isActive.value && !VinylCoverService.ownsOverlayProgress(fromSendspin = true)) {
            VinylCoverService.claimOverlayProgressOwner(fromSendspin = true)
        }
        // Paint source: pin and explicit seek/peer seats first. Steady play
        // uses [overlayBarProgressMs] (presentation clock) so a Sendspin
        // metadata tick cannot yank the bar 1–3s ahead of the speaker.
        val seekPaint = writer == OverlayProgressWriter.PeerSync ||
            writer == OverlayProgressWriter.LocalSeekUi
        val pinLive = pinnedLocalSeekMs != null &&
            SystemClock.elapsedRealtime() <= pinnedLocalSeekUntilElapsed
        val sourceMs = when {
            pinLive -> walkPlayingSeatMs(
                pinnedLocalSeekMs!!,
                pinnedLocalSeekUntilElapsed - LOCAL_SEEK_PIN_HOLD_MS,
            )
            seekPaint -> positionMs
            else -> overlayBarProgressMs() ?: positionMs
        }
        val totalMs = when {
            bridgedUiProgressActive -> durationMs ?: bridgedEffectiveDurationMs()
            else -> durationMs
        }
        val barMs = if (
            bridgedUiProgressActive ||
            seekPaint ||
            pinLive
        ) {
            sourceMs?.let { clampBridgedDuration(it) }
        } else {
            safeOverlayProgressMs(sourceMs)
        }?.let {
            // Walking pin is already on the seek grid at the origin.
            // Flooring every paint snapped the bar back to the tap.
            if (seekPaint && !pinLive) alignSeekPositionForUpstream(it) else it
        }
        VinylCoverService.updateProgress(
            context = context,
            currentTimeMs = barMs,
            totalTimeMs = totalMs,
            lyricAudibleLagMs = overlayLyricAudibleLagMs(barProgressMs = barMs),
            isSendspinSource = true,
            forcePlayhead = forcePlayhead || seekPaint || pinLive,
        )
    }

    /**
     * Lag passed into vinyl/lyrics only:
     * - pipeline write→speaker ([reportedAudioLatencyMs] / fallback estimate)
     * - plus bar fill-in lead so lyrics undo [displayProgressMs] smoothness
     *   (highlight ≈ audible tail − latency, not bar − latency)
     *
     * Does not change Client playout / auto-offset / protocol payloads.
     */
    private fun overlayLyricAudibleLagMs(barProgressMs: Long? = overlayBarProgressMs()): Long {
        // Presentation-clock bar is already at the speaker. Subtracting
        // pipeline lag again would put lyrics behind the music.
        if (presentationProgressMs() != null) return 0L
        val latencyMs = client?.reportedAudioLatencyMs() ?: 0L
        val pipeline = if (latencyMs > 0L) {
            latencyMs.coerceIn(0L, OVERLAY_LYRIC_PIPELINE_LAG_MAX_MS)
        } else {
            (client?.estimatedLyricAudibleLagMs() ?: 0L)
                .coerceIn(0L, OVERLAY_LYRIC_PIPELINE_LAG_MAX_MS)
        }
        // MA bar may lead audible — undo lead so lyrics stay on speaker/PCM branch.
        if (bridgedUiProgressActive) {
            val bar = barProgressMs?.takeIf { it >= 0L }
            val audible = audibleTailMs?.let { clampToDuration(it) }
            val lead = if (bar != null && audible != null) {
                (bar - audible).coerceAtLeast(0L)
            } else {
                0L
            }
            return (pipeline + lead).coerceIn(0L, OVERLAY_LYRIC_LAG_MAX_MS)
        }
        val fillIn = displayFillInLeadMs(barProgressMs)
        return (pipeline + fillIn).coerceIn(0L, OVERLAY_LYRIC_LAG_MAX_MS)
    }

    /**
     * Soft-freeze the wall-clock fill-in at the current playhead without flipping
     * transport / mapper speed. Used when PCM goes quiet but protocol may still
     * say playing (pause without speed=0, brief underrun past audible window).
     */
    private fun pcmSilenceMs(): Long {
        if (isAudiblyPlayingNow()) return 0L
        val last = lastAudibleElapsedRealtimeMs
        if (last == 0L) return 0L
        return (SystemClock.elapsedRealtime() - last).coerceAtLeast(0L)
    }

    /** Rescue / auto-seek must not restart a dead item (give-up or in-flight next). */
    private fun deadStreamBlocksAutoRecover(): Boolean =
        deadStreamConfirmed || deadStreamAdvancePending

    /**
     * Fresh same-streamKey beacon from a LAN peer: the stream is audibly alive
     * on another device right now. Any local silence is then this device's own
     * pipeline stall — never a dead source.
     */
    private fun peersStillRenderingOurStream(): Boolean {
        val at = lastPeerSameStreamBeaconElapsed
        return at != 0L &&
            SystemClock.elapsedRealtime() - at < PEER_STREAM_ALIVE_MS
    }

    /**
     * Seek / track-jump / reseat: PCM is expected to be gone. Do not treat
     * those gaps as a dead source. Skip-storm is MA already advancing —
     * do not confirm dead or send another next on top of it.
     */
    private fun inExpectedPlaybackSilence(): Boolean {
        if (pinnedLocalSeekMs != null &&
            SystemClock.elapsedRealtime() <= pinnedLocalSeekUntilElapsed
        ) {
            return true
        }
        if (SystemClock.elapsedRealtime() < deadStreamRetryUntilElapsed) return true
        if (inUpstreamSkipStorm()) return true
        return awaitingSeekReseat ||
            inTrackJumpSeam() ||
            inReseatPlayingHold() ||
            inTrackChangeNudgeGrace()
    }

    private fun armBridgedSilenceHold(reason: String) {
        if (bridgedSilenceHold) return
        bridgedSilenceHold = true
        bridgedUiProgressPlaying = false
        val frozen = overlayBarProgressMs()
            ?: bridgedUiProgressMs
            ?: displayProgressMs()
            ?: lastGoodOverlayProgressMs
        frozen?.let {
            val seated = clampToDuration(alignSeekPositionForUpstream(it))
            bridgedUiProgressMs = seated
            bridgedUiProgressAtElapsedRealtime = SystemClock.elapsedRealtime()
            lastGoodOverlayProgressMs = seated
        }
        Log.i(tag, "dead-stream hold: freeze interpolator ($reason)")
    }

    /**
     * Give-up only: skip-storm already idle, no [next] available, same-title
     * no-op, or the silent-run advance budget is spent. Path A (PCM silence
     * while still playing) tries [requestUpstreamSkipPastDeadItem] first.
     */
    private fun confirmDeadStream(reason: String) {
        if (deadStreamConfirmed) {
            armBridgedSilenceHold(reason)
            return
        }
        deadStreamConfirmed = true
        deadStreamAdvancePending = false
        armBridgedSilenceHold(reason)
        pendingTrackChangePausePlayNudge = false
        progressReseatNudgeInFlight = false
        trackChangeRescueUntilElapsedRealtime = 0L
        trackChangeRescueShotsSent = 0
        seamPendingPause = false
        clearReseatPlayingHold()
        clearTrackJumpSeam()
        Log.w(tag, "dead-stream confirmed ($reason): stop rescue, freeze transport")
        applyProtocolNotPlaying(scheduleHide = false)
        seatDeadStreamPlayheadAtStart()
        showDeadStreamCheckSourceToast()
        reconcileProgressOverlayTicker(false)
    }

    /**
     * Path A: current item has been silent long enough and we still intend
     * to play. Ask the server to skip once. Do not go through
     * [sendMediaCommand]("next") — that arms rescue seek on the dead item.
     * Glyph stays playing; bar stays frozen. A later silent confirm is a
     * new decision (new title → one more next, same title / budget → give-up).
     */
    private fun requestUpstreamSkipPastDeadItem(reason: String): Boolean {
        if (deadStreamConfirmed) return false
        if (inUpstreamSkipStorm()) return false
        if (!_isPlaying.value) return false
        // A grouped peer is still rendering this stream — the source is fine
        // and skipping would change the track on every device. Local-only stall.
        if (peersStillRenderingOurStream()) return false
        if (deadStreamAdvancePending) {
            if (SystemClock.elapsedRealtime() < deadStreamRetryUntilElapsed) return false
            deadStreamAdvancePending = false
        }
        if (deadStreamAdvanceCount >= DEAD_STREAM_ADVANCE_MAX) return false
        val titleNow = cachedSendspinTitle
        if (deadStreamAdvanceCount > 0 && titleNow == deadStreamAdvanceTitle) {
            return false
        }
        val live = client ?: return false
        if (!live.supportsControllerCommand("next")) return false
        val sent = live.sendMediaCommand(command = "next")
        if (!sent) return false
        deadStreamAdvancePending = true
        deadStreamAdvanceCount += 1
        deadStreamAdvanceTitle = titleNow
        armBridgedSilenceHold(reason)
        pendingTrackChangePausePlayNudge = false
        progressReseatNudgeInFlight = false
        trackChangeRescueUntilElapsedRealtime = 0L
        trackChangeRescueShotsSent = 0
        seamPendingPause = false
        clearReseatPlayingHold()
        clearTrackJumpSeam()
        deadStreamRetryUntilElapsed =
            SystemClock.elapsedRealtime() + DEAD_STREAM_CONFIRM_MS
        Log.i(
            tag,
            "dead-stream: one next to upstream ($reason) count=$deadStreamAdvanceCount",
        )
        return true
    }

    /**
     * Dead source: do not send seek (restarts Queue Flow). Keep the hold so
     * the interpolator cannot walk, and put the bar at 0.
     */
    private fun refuseDeadStreamSeek() {
        seatDeadStreamPlayheadAtStart()
        showDeadStreamCheckSourceToast()
        Log.i(tag, "dead-stream seek ignored: hold playhead at 0")
    }

    private fun seatDeadStreamPlayheadAtStart() {
        bridgedSilenceHold = true
        bridgedUiProgressPlaying = false
        awaitingSeekReseat = false
        pinnedLocalSeekMs = null
        pinnedLocalSeekUntilElapsed = 0L
        progressHeadMs = 0L
        progressHeadElapsedRealtime = 0L
        cachedProgressMs = 0L
        lastGoodOverlayProgressMs = 0L
        audibleTailMs = 0L
        progressWaitForAudible = true
        bridgedUiProgressMs = 0L
        bridgedUiProgressAtElapsedRealtime = SystemClock.elapsedRealtime()
        pushOverlayProgress(
            0L,
            bridgedEffectiveDurationMs() ?: cachedDurationMs,
            if (bridgedUiProgressActive) {
                OverlayProgressWriter.MassApiBridge
            } else {
                OverlayProgressWriter.Sendspin
            },
            forcePlayhead = true,
        )
    }

    private fun showDeadStreamCheckSourceToast() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDeadStreamToastElapsed < DEAD_STREAM_TOAST_COOLDOWN_MS) return
        lastDeadStreamToastElapsed = now
        AvaToast.show(
            context,
            R.string.media_overlay_dead_stream_check_source,
            tag = "dead-stream",
            durationMs = AvaToast.LONG_MS,
        )
    }

    private fun clearDeadStreamHold() {
        if (!bridgedSilenceHold && !deadStreamConfirmed && !deadStreamAdvancePending) return
        bridgedSilenceHold = false
        deadStreamConfirmed = false
        deadStreamRetryUntilElapsed = 0L
        deadStreamAdvancePending = false
        deadStreamAdvanceCount = 0
        deadStreamAdvanceTitle = null
    }

    /**
     * MA interpolator walks on wall clock while [_isPlaying] is true, even
     * after PCM dies. Freeze after a short silence; Path A asks upstream
     * for one [next] before giving up. Queue Flow flashes inside the
     * confirm window never reach this.
     */
    private fun maybeWatchDeadStream() {
        if (isAudiblyPlayingNow()) {
            clearDeadStreamHold()
            return
        }
        if (inExpectedPlaybackSilence()) return
        val silent = pcmSilenceMs()
        if (silent <= 0L) return
        if (silent >= DEAD_STREAM_CONFIRM_MS) {
            if (peersStillRenderingOurStream()) {
                // Stream provably alive on a grouped peer: local stall only.
                // Freeze our bar, but no pause paint, no 0:00, no group next.
                armBridgedSilenceHold("local-stall-peers-playing")
                return
            }
            if (requestUpstreamSkipPastDeadItem("pcm-silent-${silent}ms")) return
            confirmDeadStream("pcm-silent-${silent}ms")
            return
        }
        if (silent >= BRIDGED_SILENCE_FREEZE_MS) {
            armBridgedSilenceHold("pcm-silent-${silent}ms")
        }
    }

    private fun softFreezeProgressClock() {
        if (progressHeadElapsedRealtime <= 0L && progressWaitForAudible) return
        val frozen = clampToDuration(
            audibleTailMs
                ?: displayProgressMs()
                ?: progressHeadMs
                ?: cachedProgressMs
                ?: lastGoodOverlayProgressMs
        )
        progressHeadMs = frozen
        progressHeadElapsedRealtime = 0L
        // Require a new audible map emit before the bar moves again — otherwise
        // the ticker restarts the clock on the first buffered write / stale
        // isAudiblyPlaying window and races the stream by 1–2s.
        progressWaitForAudible = true
        cachedProgressMs = frozen
        lastGoodOverlayProgressMs = frozen
        audibleTailMs = frozen
        pushOverlayProgress(frozen, cachedDurationMs)
    }

    private fun reconcileProgressOverlayTicker(playing: Boolean) {
        progressOverlayJob?.cancel()
        progressOverlayJob = null
        if (!playing) return
        val vinylEnabled = isSendspinVinylUiEnabled()
        if (!vinylEnabled) return
        progressOverlayJob = scope.launch {
            while (isActive) {
                if (!VinylCoverService.ownsOverlayProgress(fromSendspin = true)) break
                // Under Mass ownership, do not soft-freeze SP 首/活尾 from PCM gaps —
                // that would stomp lyric lag math while the MA bar keeps moving.
                maybeWatchDeadStream()
                if (!bridgedUiProgressActive &&
                    !isAudiblyPlayingNow() &&
                    !holdBarUntilMatchingPcm()
                ) {
                    softFreezeProgressClock()
                }
                // Wall clock is started only by handleAudibleProgress — never here.
                // With Mass active this ticker is the MA interpolator (not SP paint).
                val positionMs = overlayBarProgressMs()
                if (positionMs != null) {
                    val writer = if (bridgedUiProgressActive) {
                        OverlayProgressWriter.MassApiBridge
                    } else {
                        OverlayProgressWriter.Sendspin
                    }
                    pushOverlayProgress(
                        positionMs,
                        bridgedEffectiveDurationMs(),
                        writer,
                    )
                }
                delay(200)
            }
        }
    }

    private fun resetSendspinMetadataCache() {
        cachedSendspinTitle = null
        progressIdentityTitle = null
        cachedSendspinArtist = null
        cachedSendspinAlbum = null
        cachedSendspinArtworkUrl = null
        lastUnresolvedArtworkUrl = null
        identityFilledFromPeer = false
        hasBinaryArtwork = false
        cachedControllerRepeatMode = null
        cachedControllerShuffleEnabled = null
        bridgedUiRepeatMode = null
        bridgedUiShuffleEnabled = null
        clearQueueProgressUiBridge()
        clearUpstreamSkipStorm()
        cachedMetadataRepeatMode = null
        cachedMetadataShuffleEnabled = null
        cachedProgressMs = null
        lastGoodOverlayProgressMs = 0L
        progressHeadMs = null
        progressHeadElapsedRealtime = 0L
        audibleTailMs = null
        progressWaitForAudible = false
        trustedUpstreamProgressSeedMs = null
        lastStreamAttachElapsed = 0L
        clearPeerProgressLock()
        // Session teardown forgets the pairing; a track change does not.
        lastPeerProgressSeenElapsed = 0L
        lastPeerProgressCalibrateElapsed = 0L
        lastKnownStreamKey = 0L
        playResumeGraceUntilElapsed = 0L
        retirePlayheadAssertion()
        // Session teardown is the one place the epoch itself resets: there is no
        // pairing left to stay comparable with.
        playheadEpoch = 0L
        playheadEpochOrigin = ""
        highestSeenPlayheadEpoch = 0L
        lastStaleEpochAnswerElapsed = 0L
        lastAdoptedPeerTimelineElapsed = 0L
        awaitingSeekReseat = false
        pendingPausedRollbackMs = null
        pendingTrackIdentityReseat = false
        pendingTrackChangePausePlayNudge = false
        progressReseatNudgeInFlight = false
        progressReseatNudgeCooldownUntilElapsedMs = 0L
        trackChangeNudgeGraceUntilElapsedMs = 0L
        lastSeenUpstreamProgressMs = null
        lastSeenUpstreamProgressAtElapsedMs = 0L
        lastUpstreamDiscontinuityWasTrackBoundary = false
        clearTrackJumpSeam()
        clearReseatPlayingHold()
        overlayPaintJob?.cancel()
        overlayPaintJob = null
        lastPushedOverlayPlaying = null
        pinnedLocalSeekMs = null
        pinnedLocalSeekUntilElapsed = 0L
        lastProgressResyncElapsedRealtime = 0L
        progressFollowUntilElapsedRealtime = 0L
        cachedDurationMs = null
        cachedMetadataTimestampUs = null
        cachedMetadataReceivedAtMs = 0L
        cachedPlaybackSpeed = 1000
        progressOverlayJob?.cancel()
        progressOverlayJob = null
        hasReceivedFirstMetadata = false
        client?.clearAudibleProgressAnchor()
    }

    private fun cancelHideJob() {
        hideVinylJob?.cancel()
        hideVinylJob = null
    }

    /**
     * Pause / stop / deferred seam pause: keep the overlay for a shared pause-idle
     * window (30s yield → FAB / hide), while cancelling mistaken empty-queue grace
     * that some servers arm via metadata:null on pause.
     *
     * Must **not** run after a confirmed [stream/end]: that path uses
     * [scheduleQueueClearedHide] instead.
     */
    private fun retainOverlayAfterPause() {
        if (client?.isStreamEnded() == true) return
        cancelHideJob()
        VinylCoverService.endQueueClearedGrace(context)
        // Idempotent: repeated paused protocol echoes must not reset the yield clock.
        VinylCoverService.schedulePauseIdleTeardown(context, restart = false)
    }

    /**
     * Alias kept for disable / stopPlayback call sites — same pause-idle path.
     */
    private fun scheduleHideVinyl() {
        retainOverlayAfterPause()
    }

    private fun normalizeServerUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return trimmed
        val withWsScheme = when {
            trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
            trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://${trimmed.substring(7)}"
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://${trimmed.substring(8)}"
            else -> "ws://$trimmed"
        }
        return runCatching {
            val uri = URI(withWsScheme)
            val host = uri.host ?: return@runCatching withWsScheme
            val hostPart = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
            val portPart = if (uri.port >= 0) ":${uri.port}" else ""
            val pathValue = uri.path?.takeIf { it.isNotBlank() && it != "/" } ?: "/sendspin"
            val normalizedPath = if (pathValue.startsWith("/")) pathValue else "/$pathValue"
            val queryPart = uri.rawQuery?.let { "?$it" } ?: ""
            "${uri.scheme ?: "ws"}://$hostPart$portPart$normalizedPath$queryPart"
        }.getOrDefault(withWsScheme)
    }

    private fun getOrCreateDeviceId(): String {
        val existing = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return existing ?: "unknown"
    }

    private fun getDeviceLabel(): String {
        val global = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        if (!global.isNullOrBlank()) return global

        val product = Build.PRODUCT
        val model = Build.MODEL
        return if (product.isNotBlank() && product != model) "$model $product" else model
    }

    private fun resolveClientName(): String {
        val custom = deviceNameCallback?.invoke()?.trim().orEmpty()
        return custom.ifEmpty { "Ava - ${getDeviceLabel()}" }
    }

    companion object {
        private const val TAG = "SendspinManager"
        /** Voice/TTS overlay floor — attenuate, do not hard-mute (avoids unDuck burst). */
        internal const val VOICE_OVERLAY_DUCK_LINEAR = 0.12f
        internal const val DEFAULT_CLIENT_PORT = 8928
        private const val MAX_INBOUND_BIND_RETRIES = 6
        private const val INBOUND_BIND_RETRY_BASE_MS = 400L
        /** Wait this long for MA to connect inbound before silent `_sendspin-server` browse. */
        private const val INBOUND_FALLBACK_DELAY_MS = 25_000L
        /** How long to wait for at least one MA server via mDNS. */
        private const val SILENT_DISCOVERY_TIMEOUT_MS = 12_000L
        /** Outbound handshake must complete within this window or we resume inbound. */
        private const val SILENT_OUTBOUND_HANDSHAKE_TIMEOUT_MS = 20_000L
        /** After a failed silent outbound, do not immediately fallback again. */
        private const val SILENT_OUTBOUND_COOLDOWN_MS = 120_000L
        /**
         * How long after the last audible PCM the Sendspin session still blocks
         * HA overlay claims. Covers track seams and short protocol silences
         * without locking HA out after a real stop.
         */
        private const val RECENT_AUDIBLE_GUARD_MS = 10_000L
        /**
         * Legacy play-follow arm (still written on play). Acceptance no longer
         * drip-follows every push inside this window — MA progress is sparse.
         */
        private const val PROGRESS_FOLLOW_WINDOW_MS = 5_000L
        /** Light periodic resync against sparse server progress (~10s). */
        private const val PROGRESS_RESYNC_MS = 10_000L
        /** Larger jumps are treated as seeks and accepted immediately. */
        private const val PROGRESS_SEEK_THRESHOLD_MS = 2_500L
        /**
         * MA `player_queues.seek` uses `position_ms // 1000`. Absolute seeks and
         * local pins align to this grid so ACK [track_progress] matches UI.
         */
        private const val MA_SEEK_SECOND_MS = 1_000L
        /**
         * Min gap between pause→play PCM aligns. Covers one MA pause/play ACK
         * round-trip so echo progress cannot stack nudges.
         */
        private const val PROGRESS_RESEAT_NUDGE_COOLDOWN_MS = 3_000L

        /**
         * Beat between wire pause and play: MA handles controller events in
         * parallel tasks and cmd_play no-ops while state is still PLAYING, so
         * back-to-back sends race and the play gets swallowed (stuck pause).
         */
        private const val NUDGE_PAUSE_PLAY_GAP_MS = 400L
        /**
         * After title / track-jump: ignore pause→play from sparse progress that
         * looks like a hard scrub (MA auto-next + network jitter). Post-grace
         * 1s-grid false scrubs are blocked by seek-sized scrubWithoutClear.
         */
        private const val TRACK_CHANGE_NUDGE_GRACE_MS = 5_000L
        /**
         * After reseat wire play: keep playing intent until PCM (or expiry).
         * Longer than [TRACK_JUMP_SEAM_MS] so seam can re-arm while waiting.
         */
        private const val RESEAT_PLAYING_HOLD_MS = 8_000L
        /**
         * Soft scrub / remote seek. Protocol seek often lands with a smaller jump than
         * [PROGRESS_SEEK_THRESHOLD_MS]; ≥500ms vs local playhead reseats the UI timestamp.
         */
        private const val PROGRESS_SCRUB_MS = 500L
        /** Hold tapped scrub against MA seek-ACK lead. */
        private const val LOCAL_SEEK_PIN_HOLD_MS = 3_000L
        /** Typical upstream seek ACK sits this far ahead of the requested position. */
        private const val LOCAL_SEEK_ACK_LEAD_MAX_MS = 1_750L
        /** While paused, upstream within this of frozen position is a harmless echo. */
        private const val PROGRESS_PAUSE_ECHO_MS = 750L
        /**
         * A paused rollback repeated within this window (same value ± echo)
         * is a newer-MA authoritative freeze, not a dirty pause flash.
         * Entry threshold is [PROGRESS_PAUSE_ECHO_MS] behind the frozen head
         * (not [PROGRESS_FAKE_ZERO_DROP_MS]) so typical 2.10 freezes confirm.
         */
        private const val PAUSED_ROLLBACK_CONFIRM_WINDOW_MS = 15_000L
        /**
         * How far metadata may sit ahead of 活尾 when seating 首 / picking resume.
         * Larger values re-introduce the ~1s pause/resume lead.
         */
        private const val PROGRESS_METADATA_LEAD_SLACK_MS = 250L
        /**
         * Max wall-clock fill-in ahead of the last audible write.
         * Must cover [SendspinClient] audible-emit throttle (~180ms), with a
         * little margin for Intent/ticker skew — tighter than that freezes the
         * bar between emits (reads as lag). Cumulative drift is prevented by
         * reseating 首 on every write, not by starving the fill-in.
         */
        private const val PROGRESS_AUDIBLE_SMOOTH_SLACK_MS = 160L
        /**
         * Max 活尾 forward jump from one audible sample unless seek-sized.
         * Blocks pending-arm stamping metadata lead onto the write clock.
         */
        private const val PROGRESS_AUDIBLE_TAIL_MAX_FORWARD_MS = 450L
        /**
         * Server open-lead / buffer often reports track_progress already 1–4s
         * before the first sample is audible. UI starts at 0 until PCM, and the
         * first write treats mapped values below this as start-of-track.
         */
        private const val PROGRESS_OPEN_LEAD_MAX_MS = 4_000L

        /**
         * How far the MA queue clock may sit from a standing intent seat and
         * still be accepted as describing the same moment.
         *
         * The two are in different domains: a seat is our presentation clock
         * (what the speaker is emitting) while MA reports the queue/buffer
         * position, and [PROGRESS_OPEN_LEAD_MAX_MS] documents that gap as a
         * structural 1–4s. Comparing them on the [MA_SEEK_SECOND_MS] seek grid
         * measured a real buffer depth as disagreement, so every MA push was
         * rejected for the life of the seat — with a seat re-armed on each
         * pause, the bar stopped moving for the rest of the track. Only a gap
         * beyond the buffer depth means MA is really describing a *different*
         * position (a stale pre-scrub queue_time), which is what this guard
         * exists to reject.
         */
        private const val MA_BRIDGE_INTENT_SKEW_MS = PROGRESS_OPEN_LEAD_MAX_MS

        /**
         * How long an armed (PCM-pending) local assertion may keep rejecting
         * peer seats. PCM normally lands within a couple of seconds; the
         * dead-stream watchdog gives up at ~8s. Past this window the local
         * arm is presumed stuck and the peer's claim wins.
         */
        private const val PLAYHEAD_ARMED_PIN_MAX_MS = 10_000L

        /**
         * How long audible PCM may be rejected for disagreeing with the
         * standing seat before the seat is abandoned. Longer than a normal
         * seek ACK plus an AudioTrack rebuild, shorter than the dead-stream
         * confirm so recovery happens while the user is still watching.
         */
        private const val SEAT_STARVATION_GIVE_UP_MS = 5_000L

        /** Also require this many rejects, so a couple of stale chunks across
         * a real seek cannot trip the give-up path. */
        private const val SEAT_STARVATION_MIN_DROPS = 8
        /**
         * Last seconds of duration. Stale SP leftover after a master's MA seek
         * lands here (188s of ~193s, or interpolator clamp to duration). Wider
         * than the 1s grid so a 4–5s remainder still counts as the tail.
         */
        private const val PROGRESS_DURATION_TAIL_MS = 5_000L
        /**
         * How far past the track duration a pre-clamp position may reach and
         * still be treated as the song's real last seconds (fresh-anchor
         * projection wobble, ~1s beacon spacing). Beyond this the value is
         * from another timeline entirely and is dropped instead of clamped —
         * see [seatWithinTrackOrNull].
         */
        private const val PROGRESS_DURATION_OVERSHOOT_MAX_MS = 2_000L
        /**
         * One-shot first-audible skew vs upstream seed. Far above open/buffer
         * lead so normal play never trips; catches cold-join double-count (~30s+)
         * or auto-arm-at-0 vs mid-track seed.
         */
        private const val COLD_JOIN_MAP_SKEW_MS = 15_000L
        /** metadata.timestamp within this of play_at counts as "stream start", not snapshot. */
        private const val STALE_METADATA_TS_PLAY_AT_SLACK_US = 2_000_000L
        /** Overlay follows peer differential paint while samples are this fresh. */
        private const val PEER_PROGRESS_PAINT_STALE_MS = 2_500L
        /**
         * Paused absolute freeze remains authoritative this long without a
         * refresh (paused samples reannounce at 1Hz; covers a missed UDP tick).
         */
        private const val PEER_PROGRESS_PAUSED_HOLD_MS = 8_000L
        /** Soft-seat local heads to peer when skew reaches this (½s grid). */
        private const val PEER_PROGRESS_SOFT_SKEW_MS = 500L
        /**
         * Mapper hard-calibrate floor (1s grid noise). Below this is
         * quantisation; from here through [PEER_URGENT_CATCHUP_MAX_MS] the
         * behind speaker steps forward onto the other (presentation only).
         */
        private const val PEER_PROGRESS_HARD_SKEW_MS = 1_000L
        /**
         * Widest same-timeline split that is still a seek/buffer gap, not a
         * different tap. Both ends keep catching up while inside this band.
         */
        private const val PEER_URGENT_CATCHUP_MAX_MS = 5_000L
        /** After a mapper hard calibrate, do not reseat again this soon. */
        private const val PEER_PROGRESS_CALIBRATE_COOLDOWN_MS = 1_000L
        /** Joining window: accept non-STEADY peer samples for hard calibrate. */
        private const val PEER_PROGRESS_JOIN_GRACE_MS = 20_000L
        /**
         * After local/protocol play: ignore lagging peer FLAG_PAUSED so a partner
         * still announcing pause cannot re-freeze our resume.
         */
        private const val PEER_PROGRESS_PLAY_RESUME_GRACE_MS = 5_000L
        /**
         * Oldest peer anchor still worth projecting. Past this the two are not
         * on one timeline at all — a seat left over from another track, or a
         * clock that has not settled — and extrapolating it would invent a
         * position rather than share one.
         */
        private const val PEER_ANCHOR_MAX_AGE_US = 120_000_000L
        /**
         * Minimum gap between anti-entropy answers. Without it, N devices that
         * all notice the same stale peer answer it N times per packet, and a
         * correction turns into a broadcast storm.
         */
        private const val STALE_EPOCH_ANSWER_MIN_GAP_MS = 400L
        /**
         * Resume first-audible vs seed/head: tighter than cold-join so pause→play
         * cannot birth at ~2s while the freeze seed is mid-track.
         */
        private const val PEER_PROGRESS_RESUME_MAP_SKEW_MS = 2_000L
        /**
         * Widest raw gap still treated as pure 1s-grid disagreement by
         * [snapBarSecondToPeer]. Above [PEER_PROGRESS_SOFT_SKEW_MS] so the
         * seconds stay locked while a calibrate is in flight, but well under a
         * full grid step — a real ~1s skew must not be papered over.
         */
        private const val PROGRESS_PEER_SECOND_SNAP_MAX_MS = 700L
        /**
         * How long a seen peer playhead means "paired". Progress packets flow at
         * beacon rate through a pause, so a few missed datagrams cannot make a
         * paired resume fall back to device-local DAC residue.
         */
        private const val PEER_PROGRESS_PAIRED_MEMORY_MS = 10_000L
        /** Mid-song last-good below which near-start flashes are not filtered. */
        private const val PROGRESS_FAKE_ZERO_ANCHOR_MS = 1_500L
        /**
         * Pipeline write→speaker clamp (not a protocol change).
         * Typical [reportedAudioLatencyMs] is ~40–500ms.
         */
        private const val OVERLAY_LYRIC_PIPELINE_LAG_MAX_MS = 500L
        /**
         * Total overlay lyric lag = pipeline + bar fill-in undo
         * ([PROGRESS_AUDIBLE_SMOOTH_SLACK_MS]).
         */
        private const val OVERLAY_LYRIC_LAG_MAX_MS =
            OVERLAY_LYRIC_PIPELINE_LAG_MAX_MS + PROGRESS_AUDIBLE_SMOOTH_SLACK_MS
        /** Pause glitch window (covers 0:00–0:0x flashes including 0:01). */
        private const val PROGRESS_FAKE_ZERO_MAX_MS = 3_000L
        private const val PROGRESS_FAKE_ZERO_DROP_MS = 2_000L
        /**
         * Upstream play/pause paint settle (non-seam). Post-pause playing echoes
         * and duplicate paints coalesce inside it; a real remote pause/resume
         * lands right after. State itself is absorbed instantly — only the paint
         * waits, so nothing can get stuck.
         */
        private const val PLAY_STATE_SETTLE_MS = 700L
        /**
         * Bounded track-jump / seek seam. Pause-side signals inside it are
         * deferred (old→new track gap, incl. buffering); the reconcile job at
         * expiry applies a still-standing pause, so a real stop always lands.
         */
        private const val TRACK_JUMP_SEAM_MS = 2_500L
        /**
         * Track-change head window: a deferred pause reconciling quiet in here
         * (and nobody paused locally) is a ghost pause that stranded MA — the
         * dead jump-gap stream cannot restart itself, so answer with a single
         * wire `play`.
         */
        private const val TRACK_CHANGE_RESCUE_WINDOW_MS = 10_000L
        /** Max rescue shots per window — after this a standing pause lands for real. */
        private const val TRACK_CHANGE_RESCUE_SHOT_MAX = 2
        /**
         * New-track listening time that turns a later windowed pause into a
         * deliberate one (rescue disabled). Above the ~1s ghost-stop shape and
         * the ≤1.2s transport-audible refresh inflation.
         */
        private const val TRACK_CHANGE_RESCUE_ESTABLISHED_MS = 3_000L
        /**
         * Audible silence that splits PCM runs for the establishment clock —
         * longer than the transport-audible window so a stale refresh cannot
         * bridge a real gap.
         */
        private const val TRACK_CHANGE_RESCUE_RUN_GAP_MS = 1_500L
        /**
         * MA unplayable-skip burst. Rescue play/seek and skip-title paints stay
         * down for this long after the last skip so play_index is not retried.
         */
        private const val UPSTREAM_SKIP_STORM_HOLD_MS = 8_000L
        /**
         * PCM gone this long → freeze the MA wall interpolator. Longer than a
         * seek/stream-clear gap, shorter than a dead source walking the bar.
         * Queue Flow flashes that produce audio inside this window never freeze.
         */
        private const val BRIDGED_SILENCE_FREEZE_MS = 4_000L
        /**
         * PCM gone this long → Path A asks upstream for one [next], or
         * give-up if that is not available. Matches skip-storm hold so
         * Flow retries that actually emit PCM thaw via [noteTransportAudible]
         * first.
         */
        private const val DEAD_STREAM_CONFIRM_MS = 8_000L
        /** Auto-nexts without PCM in one silent run. Then give-up + toast. */
        private const val DEAD_STREAM_ADVANCE_MAX = 2
        /**
         * Same-stream peer beacon younger than this proves the stream is alive
         * elsewhere. Matches the 1Hz beacon cadence + two missed packets
         * (AvaSyncOffsetPeer.PEER_STREAM_STALE_MS).
         */
        private const val PEER_STREAM_ALIVE_MS = 3_500L
        /** Repeat seek/taps must not stack AvaToast. */
        private const val DEAD_STREAM_TOAST_COOLDOWN_MS = 3_000L
        /** Coalesce pause→clear duplicate signals before the soft wait. */
        private const val QUEUE_CLEAR_CONFIRM_MS = 600L
        /**
         * After empty queue: keep expanded art/title on screen before soft FAB
         * (or legacy direct teardown). Aligned with pause-idle yield (30s).
         */
        private const val QUEUE_CLEAR_SOFT_FAB_DELAY_MS = 30_000L
    }
}
