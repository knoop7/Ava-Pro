package com.example.ava.sendspin

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import com.example.ava.sendspin.noise.SendspinLegacyGate
import com.example.ava.sendspin.noise.SendspinNoiseFailure
import com.example.ava.sendspin.noise.SendspinNoiseHandshake
import com.example.ava.sendspin.noise.SendspinNoiseIdentity
import com.example.ava.sendspin.noise.SendspinNoiseTransport
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class SendspinMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val trackProgressMs: Long? = null,
    val trackDurationMs: Long? = null,
    /** Server clock microseconds when this metadata snapshot is valid. */
    val metadataTimestampUs: Long? = null,
    /** playback_speed from progress object (×1000, 0 = paused). */
    val playbackSpeed: Int? = null,
    val isPlaying: Boolean? = null,
    val repeatMode: String? = null,
    val shuffleEnabled: Boolean? = null
)

/** Partial `server/state.controller` delta; absent fields were not in the JSON update. */
data class SendspinControllerStateDelta(
    val repeatMode: String? = null,
    val repeatModePresent: Boolean = false,
    val shuffleEnabled: Boolean? = null,
    val shufflePresent: Boolean = false,
    val groupVolume: Int? = null,
    val groupVolumePresent: Boolean = false,
    val groupMuted: Boolean? = null,
    val groupMutedPresent: Boolean = false,
    val supportedCommands: Set<String> = emptySet(),
    val supportedCommandsPresent: Boolean = false,
    val seekMaxMs: Long? = null,
    val seekMaxMsPresent: Boolean = false,
)

data class SendspinUiState(
    val wsUrl: String = "",
    val clientId: String = "",
    val clientName: String = "",
    val connected: Boolean = false,
    val status: String = "idle",
    val activeRoles: String = "",
    val playbackState: String = "",
    val groupName: String = "",
    val streamDesc: String = "",
    val offsetUncertaintyUs: Long = 0,
    val driftPpm: Double = 0.0,
    val driftUncertaintyPpm: Double = 0.0,
    val driftSnr: Double = 0.0,
    val rttUs: Long = 0,
    val networkQuality: String = "UNKNOWN",
    val stability: String = "UNKNOWN",
    val connectionType: String = "UNKNOWN",
    val queuedChunks: Int = 0,
    val bufferAheadMs: Long = 0,
    val lateDrops: Long = 0,
    val playoutOffsetMs: Long = 0,
    val audibleSyncCount: Long = 0,
    val kalmanErrorCount: Long = 0,
    val hasController: Boolean = false,
    val groupVolume: Int = 100,
    val groupMuted: Boolean = false,
    val supportedCommands: Set<String> = emptySet(),
    val playerVolume: Int = 100,
    val playerMuted: Boolean = false,
    val playerVolumeFromServer: Boolean = false,
    val playerMutedFromServer: Boolean = false,
    val isLowMemoryDevice: Boolean = false,
    val hasMetadata: Boolean = false,
    val metadataTimestamp: Long? = null,
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    val albumTitle: String? = null,
    val albumArtist: String? = null,
    val trackYear: Int? = null,
    val trackNumber: Int? = null,
    val artworkUrl: String? = null,
    val trackProgress: Long? = null,
    val trackDuration: Long? = null,
    val playbackSpeed: Int? = null,
    val repeatMode: String? = null,
    val shuffleEnabled: Boolean? = null,
    val playbackSpeedMultiplier: Float = 1.0f,
    val smoothedLatencyMs: Double = 0.0
)

data class SendspinClientStats(
    val connectionState: String,
    val audioCodec: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val playbackState: String,
    val syncUncertaintyUs: Long,
    val rttUs: Long,
    val networkQuality: String,
    val clockStability: String,
    val audioLatencyUs: Long,
    val playoutOffsetUs: Long,
    val lateDrops: Long,
    val queuedChunks: Int,
    val audibleSyncs: Long,
    val kalmanErrorCount: Long,
    val clockDriftPpm: Double,
    val driftUncertaintyPpm: Double,
    val driftSnr: Double,
    val bufferAheadMs: Long,
    val chunksReceived: Long,
    val chunksPlayed: Long,
    val chunksDropped: Long,
    val bufferUnderrunCount: Long,
    val playbackSpeed: Float,
    val isConnected: Boolean,
    val connectionDrops: Int,
    val clockReadyForPlayback: Boolean,
    val forceResyncActive: Boolean,
    val audioOutputStarted: Boolean,
    val serverLatenessMs: Long,
    val networkJitterMs: Long,
    val clockUpdateCount: Int,
    val staticDelayMs: Long,
    val estimatedOffsetMs: Long
)

/**
 * One LAN playback beacon: "the sample leaving my DAC right now was scheduled
 * for server time [audibleServerTsUs]". Grouped peers compare beacons directly,
 * so every common-mode error (clock offset, network asymmetry, pipeline
 * estimate) cancels and only the true relative playback offset remains.
 * [streamKey] is the stream's play_at — group members share it, which keeps
 * beacons from unrelated streams from ever steering this player.
 */
data class SendspinPeerBeacon(
    val audibleServerTsUs: Long,
    val flags: Int,
    val streamKey: Long,
) {
    companion object {
        /** [AudioTrack.getTimestamp] presented this sample; mixer-head fallback omits it. */
        const val FLAG_DAC_TRUSTED = 1
        const val FLAG_STEADY = 2
    }
}

class SendspinClient(
    private val wsUrl: String,
    private val clientId: String,
    private val clientName: String,
    private val context: Context,
    initialSyncOffsetMs: Int = 0,
    initialPlayerVolumePercent: Int = 100,
    initialPlayerMuted: Boolean = false,
    private val forceLowMemoryMode: Boolean = false,
    private val preferredFormat: String = "automatic",
    private val preferredFormatProvider: (() -> String)? = null,
    private val onMetadata: ((SendspinMetadata) -> Unit)? = null,
    private val onArtwork: ((Bitmap?) -> Unit)? = null,
    private val onControllerState: ((SendspinControllerStateDelta) -> Unit)? = null,
    private val onPlaybackState: ((String) -> Unit)? = null,
    /** Track-relative ms derived from a written (audible) chunk + metadata anchor. */
    private val onAudibleProgress: ((Long) -> Unit)? = null,
    /**
     * Fired (throttled) whenever PCM is successfully written to the device.
     * Manager uses this as transport-glyph authority — protocol speed=0 flashes
     * must not paint ▶ while audio is flowing (cold start / track jump).
     */
    private val onTransportAudible: (() -> Unit)? = null,
    /**
     * Seek / track-jump discontinuity ([stream/clear]). Manager should arm for the
     * next authoritative [track_progress] per protocol.
     */
    private val onStreamClear: (() -> Unit)? = null,
    /**
     * Playback over / empty queue ([stream/end]). Manager schedules the
     * queue-cleared hide timeline (not pause soft-hide).
     */
    private val onStreamEnd: (() -> Unit)? = null,
    /**
     * Whole [server/state] `metadata: null` — role cleared (empty queue). Same
     * timeline as [onStreamEnd]; must not be confused with pause leaf updates.
     */
    private val onMetadataRoleCleared: (() -> Unit)? = null,
    private val onVolumeCommand: ((Int) -> Unit)? = null,
    private val onMuteCommand: ((Boolean) -> Unit)? = null,
    private val onServerHello: ((serverId: String, connectionReason: String) -> Unit)? = null,
    private val onPlaybackPlaying: ((serverId: String) -> Unit)? = null,
    /** Fired when [isForeignGroupBlocked] flips. */
    private val onForeignGroupBlocked: ((Boolean) -> Unit)? = null,
    /**
     * A user-made shared group dissolved under us (MA unsync re-homed this
     * client to a solo group). The manager must wipe the mirrored identity
     * itself: the re-home rides foreign probation, which swallows the
     * stream/clear + stream/end that would normally start the teardown — the
     * ex-follower kept the leader's title/artist painted forever.
     */
    private val onGroupDissolved: (() -> Unit)? = null,
    /**
     * User's own pairing proof: true when Music Assistant shows this device in
     * a user-made sync group, false when MA is live and shows none, null when
     * no arbiter exists (MA not connected / identity unresolved). A server
     * `group_id` change is only refused on a live `false`.
     */
    private val isMassSyncGrouped: (() -> Boolean?)? = null,
) {
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    /**
     * Sendspin spec [client/goodbye.reason]. The first four are ordinary local
     * decisions; the last four are refusals the spec requires us to name so the
     * server knows whether to auto-reconnect — a wrong reason here is why a
     * server retries a connection it can never complete.
     *
     * Any caller-supplied legacy string is normalised through [fromLegacy].
     */
    enum class GoodbyeReason(val wire: String) {
        ANOTHER_SERVER("another_server"),
        SHUTDOWN("shutdown"),
        RESTART("restart"),
        USER_REQUEST("user_request"),
        /** Server declared an activity set we are not authorized for. */
        UNAUTHORIZED("unauthorized"),
        /** Unpaired access refused because we do not admit it. */
        PAIRING_REQUIRED("pairing_required"),
        /** A higher-or-equal-priority connection is already active. */
        CONCURRENT_ATTEMPT("concurrent_attempt"),
        /** We processed [server/unpair] from this server. */
        UNPAIRED("unpaired");

        companion object {
            fun fromLegacy(legacy: String?): GoodbyeReason {
                if (legacy.isNullOrBlank()) return SHUTDOWN
                return when (legacy.lowercase()) {
                    "another_server", "switch_server", "server_switch" -> ANOTHER_SERVER
                    "shutdown", "shutting_down", "stop", "stopping" -> SHUTDOWN
                    "restart", "restarting", "reconnect", "reconnecting" -> RESTART
                    "user_request", "user", "manual", "manual_disconnect", "disabled" -> USER_REQUEST
                    "unauthorized", "forbidden" -> UNAUTHORIZED
                    "pairing_required", "needs_pairing" -> PAIRING_REQUIRED
                    "concurrent_attempt", "busy" -> CONCURRENT_ATTEMPT
                    "unpaired", "unpair" -> UNPAIRED
                    else -> SHUTDOWN
                }
            }
        }
    }

    companion object {
        private const val TAG = "SendspinClient"
        /** Do not hammer `external_source` if the server re-adds us. */
        private const val FOREIGN_GROUP_LEAVE_MIN_GAP_MS = 3_000L
        /**
         * How long a blocked group id may prove itself before we act: MA's
         * player_updated (the pairing proof) rides a separate socket and lands
         * a beat after the server's own group/update multicast.
         */
        private const val FOREIGN_GROUP_PROBATION_MS = 4_000L
        /**
         * After `external_source`: if no replacement solo group ever arrives
         * and the blocked id stays quiet, it *was* our solo — recover instead
         * of staying deaf forever (the server does not re-home an already-solo
         * client).
         */
        private const val FOREIGN_GROUP_SELF_HEAL_MS = 6_000L
        private val FOREIGN_GROUP_BLOCKED_COMMANDS = setOf(
            "play", "pause", "stop", "seek", "seek_relative",
            "next", "previous", "switch",
        )
        private const val DEFAULT_AUTO_PLAYOUT_OFFSET_US = 0L
        private const val BASELINE_PLAYOUT_OFFSET_US = 0L
        /**
         * Soft drop catch-up is a last resort (stutters). Millisecond drift must be
         * handled by play-through + tiny rate nudge — never by discarding frames.
         */
        private const val CATCHUP_DROP_LATE_MS = 220L
        private const val CATCHUP_TARGET_LATE_MS = 80L
        private const val CATCHUP_MAX_DROPS = 50
        private const val HARD_CUT_LATE_MS = 500L
        /**
         * Emergency tier below the 220ms drop line: ~100ms persistent mid-play
         * lag arms [pendingManualCatchupUs] once — a bounded shed down to
         * [EMERGENCY_CATCHUP_KEEP_WITHIN_US], then the rate loop closes the
         * rest and glides back to 1.0x. Strikes + cooldown keep a network blip
         * from thrashing repeated drops (the old "endless catch-up" failure mode).
         */
        private const val EMERGENCY_CATCHUP_LATE_MS = 100L
        private const val EMERGENCY_CATCHUP_STRIKES = 10
        private const val EMERGENCY_CATCHUP_COOLDOWN_MS = 5_000L
        /**
         * Fixed heard-domain floor for the emergency shed. The old window
         * loosened as the budget drained (120ms − pending/2), so the shed got
         * weaker over its own progress and parked mid-band with the budget
         * wedged armed (which also blocked re-arming). 40ms is the tightest
         * window the old code already used on its first burst, and sits
         * squarely inside the rate loop's correctable range.
         */
        private const val EMERGENCY_CATCHUP_KEEP_WITHIN_US = 40_000L
        /** First seconds after output start: mild auto-offset settle. */
        private const val SETTLE_CATCHUP_WINDOW_US = 12_000_000L
        /**
         * Floor for "too late to keep" in the server-time jitter path.
         * Below this, mild lag is playable — dropping it causes the audible glitch
         * and then forces a later catch-up.
         */
        private const val LATE_DROP_FLOOR_US = 150_000L
        private const val LATE_DROP_CEILING_US = 400_000L
        private const val PRESTART_MIN_BUFFER_MS = -20L
        private const val PRESTART_DROP_TRIGGER_AHEAD_MS = -60L
        private const val MAX_USER_OFFSET_MS = 1_000
        // Normal: stable default (4MB). Optimization mode: extra headroom (5MB).
        private const val NORMAL_BUFFER_CAPACITY = 4_000_000
        private const val LOW_MEMORY_BUFFER_CAPACITY = 5_000_000
        private const val AUTO_OFFSET_PREFS = "sendspin_auto_offset"
        private const val AUTO_OFFSET_KEY = "auto_playout_offset_us_v4"
        private const val AUTO_OFFSET_MIN_US = -250_000L
        private const val AUTO_OFFSET_MAX_US = 250_000L
        private const val AUTO_OFFSET_SAVE_STEP_US = 2_000L
        /**
         * DAC vs server-now: wait past AudioTrack ramp + timestamp warm-up
         * ([SendspinPcmAudioOutput] already requires several stable HAL reads).
         * Was 2s, which left the waterline learner in charge at start — that
         * path delays when the write is late, so the first sound sat behind
         * a locked peer. Still shorter than the peer-align stream hold.
         */
        private const val SERVER_ALIGN_MIN_OUTPUT_US = 750_000L
        /** Ignore DAC↔server chatter inside this band (speed loop owns it). */
        private const val SERVER_ALIGN_DEADBAND_US = 4_000L
        /** One sample of DAC↔server error cannot exceed this. */
        private const val SERVER_ALIGN_SAMPLE_CLAMP_US = 80_000L

        // LAN peer differential alignment (playback beacons). Only the lagging
        // device ever steers: the speed controller is late-only, so a positive
        // (delaying) shift cannot be realized mid-stream anyway — the leading
        // peer stays put and the laggard accelerates toward it.
        /** Relative offsets inside this band are left alone (DAC-trusted path). */
        private const val PEER_ALIGN_DEADBAND_US = 12_000L
        /**
         * Mixer-head fallback: HAL lead is unknown, so chatter below this is
         * ignored. Wider than the trusted band on purpose.
         */
        private const val PEER_ALIGN_COARSE_DEADBAND_US = 35_000L
        /** Max single correction step — stays well inside the pure rate-nudge band. */
        private const val PEER_ALIGN_STEP_MAX_US = 25_000L
        /** Total learned correction clamp (≤ 0 — accelerate only). */
        private const val PEER_ALIGN_TOTAL_MIN_US = -100_000L
        /**
         * Cap when either side is on the mixer-head fallback. Was -60ms, which
         * two real devices exhausted in three steps while still measuring
         * ~120ms apart — the budget itself was the reason they never met.
         * Now that only one side steers (see `yieldsCoarseAlignmentTo`), that
         * side has to be able to cover the whole gap on its own.
         */
        private const val PEER_ALIGN_COARSE_TOTAL_MIN_US = -180_000L
        /** Deltas beyond this are treated as a broken comparison, not a lag. */
        private const val PEER_ALIGN_SANITY_US = 400_000L
        private const val PEER_ALIGN_WINDOW_MS = 12_000L
        private const val PEER_ALIGN_WINDOW_MAX = 12
        private const val PEER_ALIGN_MIN_SAMPLES = 5
        private const val PEER_ALIGN_STEP_COOLDOWN_MS = 6_000L
        /**
         * No steering right after stream/start — gapless boundaries mix
         * timelines. 10s leaves room for the new stream to settle without the
         * old 12s verse-length mute.
         */
        private const val PEER_ALIGN_STREAM_START_HOLD_MS = 10_000L
        /** Auto-offset EMA pause while a step's deliberate lateness drains. */
        private const val PEER_ALIGN_LEARN_HOLD_US = 8_000_000L
        /** Local timeline must sit this close to its target before steering. */
        private const val PEER_ALIGN_STEADY_BAND_US = 30_000L
        /** Typical LAN half-RTT: the peer's playhead advanced this much in flight. */
        private const val PEER_BEACON_OWD_COMP_US = 1_500L
        /** Lyrics-only audible lag clamp (user sync contribution). */
        private const val LYRIC_AUDIBLE_LAG_MAX_MS = 150L
        /** UI progress emit cadence from the audible write path. */
        private const val AUDIBLE_PROGRESS_EMIT_MIN_US = 180_000L
        private const val AUDIBLE_PROGRESS_EMIT_MIN_DELTA_MS = 400L
        /** Throttle Manager transport-glyph promotions from the playout loop. */
        private const val TRANSPORT_AUDIBLE_NOTIFY_MIN_MS = 250L
        /** How fresh a PCM write must be to count as "audibly playing". */
        private const val TRANSPORT_AUDIBLE_WITHIN_MS = 800L

        // stream/request-format adaptive downgrade tuning
        private const val FORMAT_REQUEST_COOLDOWN_US = 30_000_000L  // 30 s
        private const val UNDERRUN_DOWNGRADE_THRESHOLD = 3L
        private const val LATE_DROP_DOWNGRADE_THRESHOLD = 30L
        /** Consecutive non-empty FLAC frames that decoded to silence before Opus fallback. */
        private const val FLAC_EMPTY_DECODE_FALLBACK_THRESHOLD = 12
        private val PLAYBACK_STATE_TOKENS = setOf("playing", "paused", "stopped")
        private val SUPPORTED_STREAM_CODECS = setOf("pcm", "opus", "flac")

        private val controllerCommandAliases = mapOf(
            "play" to listOf("resume"),
            "pause" to listOf("media_pause"),
            "next" to listOf("skip_next"),
            "previous" to listOf("skip_previous")
        )

        private val sharedOkHttp = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        /**
         * Spec-compliant `player@v1_support` object. Exposed as a static helper so
         * the arbitration probe ([SendspinArbiter]) can build an identical hello
         * without instantiating a full [SendspinClient].
         *
         * supported_formats is in priority order (first = preferred). bit_depth
         * only allows 16/24 per spec. Codec preference:
         *  1. FLAC — 44.1 kHz / 16-bit first, then 48 kHz variants.
         *  2. PCM  — probed per sendspinlite (48/44.1 kHz × 16/24/32).
         * Opus is omitted from automatic hello; UI manual pick only.
         */
        fun buildPlayerSupportObjectStatic(
            isLowMemoryDevice: Boolean,
            preferredFormat: String = "automatic"
        ): JSONObject {
            val normalizedFormat = SendspinFormatCatalog.normalizePreferredFormat(preferredFormat)
            val bufferCapacity =
                if (isLowMemoryDevice) LOW_MEMORY_BUFFER_CAPACITY else NORMAL_BUFFER_CAPACITY
            return SendspinFormatCatalog.buildPlayerSupportObject(bufferCapacity, normalizedFormat)
        }

        /**
         * Build a full spec-compliant `client/hello` payload. Used both by the
         * regular [SendspinClient] and the [SendspinArbiter] mini-handshake.
         */
        fun buildClientHelloPayload(
            context: Context,
            clientId: String,
            clientName: String,
            isLowMemoryDevice: Boolean,
            preferredFormat: String = "automatic",
            includeLegacyClientId: Boolean = true,
        ): JSONObject {
            val softwareVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (_: Throwable) {
                ""
            }
            val hello = JSONObject()
                .put("name", clientName)
            if (includeLegacyClientId) {
                hello.put("client_id", clientId)
                hello.put("version", 1)
            }
            hello.put(
                    "device_info",
                    JSONObject()
                        .put("product_name", android.os.Build.MODEL)
                        .put("manufacturer", android.os.Build.MANUFACTURER)
                        .put("software_version", softwareVersion)
                )
                .put(
                    "supported_roles",
                    JSONArray()
                        .put("player@v1")
                        .put("metadata@v1")
                        .put("controller@v1")
                        .put("artwork@v1")
                )
                /*
                 * trust_level none + unpaired_access: Sentinel PSK only, no
                 * pairing records. Encrypted sessions authenticate with the
                 * X25519 id on client/init, not ANDROID_ID here.
                 */
                .put("trust_level", "none")
                .put("unpaired_access", JSONObject().put("enabled", true))
            val playerSupport = buildPlayerSupportObjectStatic(isLowMemoryDevice, preferredFormat)
            hello.put("player@v1_support", playerSupport)
            if (includeLegacyClientId) {
                /*
                 * Unversioned alias for pre-versioned servers only. aiosendspin
                 * records any `player_support` key as "legacy_support_keys_used"
                 * and, with "allow legacy clients" off (strict mode), rejects
                 * the hello outright — so the encrypted path must not send it.
                 */
                hello.put("player_support", playerSupport)
            }
            hello.put("controller@v1_support", JSONObject())
            // Lightweight artwork: one album channel, JPEG only, small decode budget.
            val artEdge = if (isLowMemoryDevice) 256 else 384
            hello.put(
                "artwork@v1_support",
                JSONObject().put(
                    "channels",
                    JSONArray().put(
                        JSONObject()
                            .put("source", "album")
                            .put("format", "jpeg")
                            .put("media_width", artEdge)
                            .put("media_height", artEdge)
                    )
                )
            )
            return hello
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playoutScope = CoroutineScope(SupervisorJob() + SendspinPlayout.dispatcher)
    private val isLowMemoryDevice = forceLowMemoryMode || checkIsLowMemoryDevice()

    private var ws: WebSocket? = null
    private var transportSendText: ((String) -> Boolean)? = null
    private var transportSendBinary: ((ByteArray) -> Boolean)? = null
    private var transportClose: ((Int, String) -> Unit)? = null
    private val isConnected = AtomicBoolean(false)
    private var handshakeComplete = false
    private var noiseHandshake: SendspinNoiseHandshake? = null
    private var noiseTransport: SendspinNoiseTransport? = null
    private var awaitingEncryptedServerHello = false

    val timeFilter = SendspinTimeFilter()
    private val jitter = SendspinAudioJitterBuffer(
        timeFilter,
        lowMemoryMode = isLowMemoryDevice,
        maxBufferBytes =
            (if (isLowMemoryDevice) LOW_MEMORY_BUFFER_CAPACITY else NORMAL_BUFFER_CAPACITY).toLong()
    )
    private val output = SendspinPcmAudioOutput(
        lowMemoryMode = isLowMemoryDevice,
        context = context,
    )
    private val audioFocus = SendspinAudioFocusController(context) {
        output.ensurePlaying()
    }
    private val speedController = SendspinPlaybackSpeedController(output, jitter)
    private val syncController = SendspinPlaybackSyncController()

    private var timeLoopJob: Job? = null
    private var playoutJob: Job? = null
    private var statsJob: Job? = null
    private var speedAdjustmentJob: Job? = null
    private var watchdogJob: Job? = null
    private var memoryMonitorJob: Job? = null

    @Volatile
    private var outputStartedAtUs = 0L

    @Volatile
    private var nextStartAttemptUs = 0L

    @Volatile
    private var codec = ""
    @Volatile
    private var streamFormatReady = false
    /** True after stream/start carries an artwork object (or first type-8 while hello advertised art). */
    private var artworkStreamActive = false
    private var artworkDecodeJob: Job? = null
    private var sampleRate = 48_000
    private var channels = 2
    private var bitDepth = 16
    private var opusDecoder: SendspinOpusDecoder? = null
    private var flacDecoder: SendspinFlacDecoder? = null
    private var opusDecoderConfig: String? = null
    private var flacDecoderConfig: String? = null
    private var lastHardCorrectionAtUs = 0L
    /** Optional `stream/start.player.codec_header` (base64 FLAC STREAMINFO). */
    private var pendingFlacCodecHeader: String? = null
    @Volatile
    private var playAtServerUs = Long.MIN_VALUE

    /**
     * Stream-identity fallback for servers that no longer send
     * `stream/start.player.play_at` (removed from the current Sendspin spec).
     * Derived deterministically from server_id + group identity, so paired
     * devices in the same group compute the identical key. Group-level
     * granularity is correct for beacon steering and live drift refinement:
     * same group == same content timeline.
     */
    @Volatile
    private var fallbackStreamKey = Long.MIN_VALUE

    private val groupIdentity = SendspinGroupIdentity()
    private var foreignLeaveJob: Job? = null
    private var lastForeignLeaveElapsed = 0L
    private var foreignProbationJob: Job? = null
    private var foreignSelfHealJob: Job? = null
    /** Probation resolved to "really foreign" — leave retries are now allowed. */
    @Volatile
    private var foreignConfirmed = false
    /** The blocked group streamed / declared playing since the last leave. */
    @Volatile
    private var foreignStreamActivity = false
    /**
     * The group we sat in when the current probation began was a user-made
     * shared group — its quiet resolution is a real unpair, not a fan-out.
     * Deliberately outside [clearForeignTracking]: the quiet-adopt path clears
     * tracking before the dissolution verdict is delivered.
     */
    @Volatile
    private var foreignLeftSharedGroup = false
    /**
     * The one `stream/start` a joiner gets, held while blocked. A join that is
     * confirmed user-made a moment later (MA event lag) replays it — dropping
     * it left the adopted stream with no format header until the next track.
     */
    @Volatile
    private var pendingBlockedStreamStart: JSONObject? = null
    /** Last `group/update.group_name`, for late fallback-stream-key adoption. */
    @Volatile
    private var lastGroupUpdateName = ""

    /** See [notePresentationProgress]; MIN_VALUE = no floor yet. */
    @Volatile
    private var presentationFloorMs = Long.MIN_VALUE

    @Volatile
    private var userSyncOffsetMs = initialSyncOffsetMs.toLong().takeIf { it != 0L } ?: 0L

    @Volatile
    private var appliedManualOffsetUs = if (initialSyncOffsetMs != 0) initialSyncOffsetMs.toLong() * 1000L else 0L

    @Volatile
    private var playoutOffsetAdjustmentUs = 0L

    @Volatile
    private var pendingManualCatchupUs = 0L

    /** Emergency-tier confirmation: consecutive 100–220ms-late chunks. */
    private var emergencyLateStrikes = 0
    private var lastEmergencyCatchupMs = 0L

    private var lastManualOffsetRampUs = 0L

    @Volatile
    private var autoPlayoutOffsetUs = loadPersistedAutoOffsetUs()

    /** Content-frame anchor of the last chunk handed to the AudioTrack (immutable snapshot). */
    private class AudibleFrameAnchor(
        val serverTsUs: Long,
        val frames: Long,
        val generation: Long,
    )

    @Volatile
    private var audibleFrameAnchor: AudibleFrameAnchor? = null

    /**
     * Extra playout offset learned from grouped LAN peers' playback beacons
     * (differential alignment). Always ≤ 0: only a lagging device accelerates.
     * Survives track changes (it corrects a device-pair bias, not per-track
     * state); dies with the client instance on reconnect.
     */
    @Volatile
    private var peerAlignOffsetUs = 0L

    /** While set, [updateAutoOffsetModel] must not learn (peer-align step draining). */
    @Volatile
    private var autoOffsetLearnHoldUntilUs = 0L

    private class PeerAlignSample(
        val atElapsedMs: Long,
        val deltaUs: Long,
        val peerTrusted: Boolean,
        val coarse: Boolean,
    )

    private val peerAlignLock = Any()
    /** Per-peer recent comparisons. Mixing rooms in one queue made 3+ devices drift. */
    private val peerAlignWindows = HashMap<String, ArrayDeque<PeerAlignSample>>()
    @Volatile
    private var peerSteerHoldUntilMs = 0L
    private var lastPeerStepMs = 0L

    /**
     * Last `set_static_delay` the server asked for (0–5000). Spec / aiosendspin
     * `compute_play_time`: heard = Kalman(server_ts) − this. MA AirPlay reports
     * 0 here and puts its device wait into `required_lead_time_ms` instead.
     */
    @Volatile
    private var protocolStaticDelayMs = 0

    /** Last post-presentation lead advertised in `required_lead_time_ms`. */
    private var advertisedPostPresentationUs = -1L

    private var autoOffsetEarlyErrorEmaUs = 0.0
    private var autoOffsetBufferErrorEmaMs = 0.0
    private var autoOffsetInitialized = false
    private var lastAutoOffsetUpdateUs = 0L
    private var lastPersistedAutoOffsetUs = autoPlayoutOffsetUs
    /**
     * DAC vs Sendspin server-now error EMA (positive = speaker late).
     * Separate from [autoOffsetEarlyErrorEmaUs] — that one is write-waterline
     * and has the opposite late-sign, so they must not share a filter.
     */
    private var autoOffsetServerErrorEmaUs = 0.0
    private var autoOffsetServerEmaInitialized = false
    private var lastMeasuredAutoLatencyUs = 0L

    private var decodeLatencyUs = 0L
    private val decodeLatencySamples = ArrayDeque<Long>(30)

    @Volatile
    private var streamEnded = false

    private var lastChunkServerTimestampUs = Long.MIN_VALUE
    private val discontinuityThresholdUs = 500_000L

    /**
     * Audible UI playhead: map written chunk server timestamps onto track ms using a
     * trusted metadata (or seek) anchor. Not driven by raw PCM byte counts.
     */
    @Volatile private var audibleAnchorProgressMs: Long? = null
    @Volatile private var audibleAnchorServerTsUs: Long? = null
    @Volatile private var audibleAnchorSpeed: Int = 1000
    /** Server timestamp of the last successfully written chunk (for UI reseat). */
    @Volatile private var lastWrittenChunkServerTsUs: Long = Long.MIN_VALUE
    @Volatile private var audibleDurationMs: Long? = null
    @Volatile private var audibleTrackProgressMs: Long = -1L
    private var lastAudibleProgressEmitUs: Long = 0L
    /**
     * After [stream/clear] the server timeline spliced; absolute track ms is unknown
     * until [calibrateAudibleProgress] / [reseatAudibleOrigin]. Do not invent 0.
     */
    @Volatile private var audibleNeedsAbsoluteSeat: Boolean = false

    /**
     * Boundary hold from [calibrateAudibleProgressAtBoundary]: written chunks whose
     * play-ts precede this still belong to the previous track (gapless / ICY metadata
     * arrives buffer-lead seconds early) — they must not drive the new track's bar.
     */
    @Volatile private var audibleEmitHoldUntilServerTsUs: Long = Long.MIN_VALUE

    @Volatile
    private var inDiscontinuityMode = false

    private var inDiscontinuityModeUntilUs = 0L
    /** Last stream/clear or stream/end. UDP must not issue a second seek into this window. */
    @Volatile
    private var lastStreamSpliceUs: Long = 0L

    @Volatile
    private var forceResyncMode = false
    private var forceResyncUntilUs = 0L
    private var lastForceResyncLogMs = 0L
    private var lateAlignmentStrikeCount = 0
    private var aheadAlignmentStrikeCount = 0
    private var lastAlignmentMonitorUs = 0L
    /**
     * Arm one startup hard-correction after stream/start or stream/clear only.
     * Mid-play output restarts must not re-arm — that caused sudden pause + UI snap.
     */
    @Volatile
    private var startupHardCorrectionArmed = false

    /**
     * Freshest playout timeline sample for late-only speed catch-up.
     * earlyUs = heardPlayUs − nowUs; [Long.MIN_VALUE] = no sample yet.
     */
    @Volatile
    private var lastTimelineEarlyUs: Long = Long.MIN_VALUE

    @Volatile
    private var lastTimelinePipelineLatencyUs: Long = 0L

    private var lastUiUpdateUs = 0L
    private val uiUpdateThrottleMs = if (isLowMemoryDevice) 280L else 250L

    private var audibleSyncCount: Long = 0
    private var lastPlaybackHeartbeatMs: Long = 0L
    private var lastStatsHeartbeatMs: Long = 0L
    private var lastRestartCatchupLogMs: Long = 0L
    private var lastAudioCutMs: Long = 0L
    private var lastSpliceLateDrops: Long = 0L
    private var audioScheduleDebugCount = 0
    private var chunksReceived = 0L
    private var chunksPlayed = 0L
    private var chunksDropped = 0L
    private var underrunCount = 0L
    /** Wall clock of last successful PCM write — transport "is playing" signal. */
    @Volatile
    private var lastPcmWriteElapsedRealtimeMs: Long = 0L
    private var lastTransportAudibleNotifyMs: Long = 0L

    // stream/request-format adaptive downgrade bookkeeping
    @Volatile
    private var lastFormatRequestUs = 0L
    private var flacEmptyDecodeStreak = 0
    private var underrunCountAtLastRequest = 0L
    private var lateDropsAtLastRequest = 0L

    private var controllerRoleActive = false
    /**
     * Has any server message actually told us the active role set?
     *
     * The spec carries `active_roles` on [server/activate], but we historically
     * read it off [server/hello] and some servers do send it there. Absent from
     * both, roles are *unknown* rather than empty — and unknown must not gate
     * commands off, or a server that never declares them loses every control.
     */
    private var activeRolesDeclared = false
    /** Last declared roles; [server/activate] omitting the field means "unchanged". */
    private var activeRolesCsv: String = ""
    /** [server/activate.activities]; empty until the server declares them. */
    private var serverActivities: Set<String> = emptySet()
    /** Encrypted path: initial client/state held until the first server/activate. */
    private var initialStatePending = false
    private var serverSupportedControllerCommands: Set<String> = emptySet()
    private var serverSeekMaxMs: Long? = null
    private var currentVolume = initialPlayerVolumePercent.coerceIn(0, 100)
    private var currentMuted = initialPlayerMuted

    @Volatile
    private var serverId: String = ""

    @Volatile
    private var serverConnectionReason: String = "discovery"  // spec default for server-initiated connections

    @Volatile
    private var lastReportedPlayingServerId: String = ""

    fun getServerId(): String = serverId

    /**
     * The `client_id` MA knows this session by. Encrypted sessions authenticate
     * with the X25519 identity from `client/init`, so MA registers the player
     * under that 43-char key — not the legacy ANDROID_ID sent in a plaintext
     * hello. Anything that looks this device up in the MA API must use this.
     */
    fun activeClientId(): String =
        if (noiseTransport != null) SendspinNoiseIdentity.get(context).peerId else clientId
    fun getServerConnectionReason(): String = serverConnectionReason

    private val _connectionState = kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _currentCodec = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val currentCodec = _currentCodec.asStateFlow()

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(
        SendspinUiState(
            wsUrl = wsUrl,
            clientId = clientId,
            clientName = clientName,
            playerVolume = currentVolume,
            isLowMemoryDevice = isLowMemoryDevice,
            playoutOffsetMs = currentAppliedOffsetMs()
        )
    )
    val uiState = _uiState.asStateFlow()

    suspend fun connect() {
        val req = Request.Builder().url(wsUrl).build()
        _connectionState.value = ConnectionState.CONNECTING
        
        patchUi {
            it.copy(
                wsUrl = wsUrl,
                clientId = clientId,
                clientName = clientName,
                status = "connecting...",
                connected = false,
                playerVolume = currentVolume,
                isLowMemoryDevice = isLowMemoryDevice,
                playoutOffsetMs = currentAppliedOffsetMs()
            )
        }

        try {
            val url = java.net.URL(wsUrl)
            val host = url.host ?: "localhost"
            val port = if (url.port == -1) {
                if (wsUrl.startsWith("wss://")) 443 else 80
            } else {
                url.port
            }

            when (val result = SendspinPortChecker.checkPort(host, port)) {
                is SendspinPortChecker.PortCheckResult.PortOpen -> {
                    patchUi { it.copy(status = "port_open") }
                }
                is SendspinPortChecker.PortCheckResult.PortClosed -> {
                    teardown("failure: port_closed")
                    _connectionState.value = ConnectionState.ERROR
                    return
                }
                is SendspinPortChecker.PortCheckResult.ServerUnreachable -> {
                    Log.e(TAG, "Server unreachable: ${result.error}")
                    teardown("failure: server_unreachable")
                    _connectionState.value = ConnectionState.ERROR
                    return
                }
            }
        } catch (e: Exception) {
            // Skip port preflight when URL parsing or scheme handling is unsupported.
        }

        output.checkAudioCapabilities(context)
        ws = sharedOkHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                transportSendText = { message -> webSocket.send(message) }
                transportSendBinary = { bytes -> webSocket.send(okio.ByteString.of(*bytes)) }
                transportClose = { code, reason -> webSocket.close(code, reason) }
                markTransportOpen("ws_open")
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleText(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                handleBinary(bytes.toByteArray())

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WS closed code=$code reason=$reason")
                if (noiseHandshake != null && noiseTransport == null) {
                    SendspinLegacyGate.armAfterFailedInit()
                }
                teardown("closed: $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}", t)
                if (noiseHandshake != null && noiseTransport == null) {
                    SendspinLegacyGate.armAfterFailedInit()
                }
                teardown("failure: ${t.message}")
                _connectionState.value = ConnectionState.ERROR
            }
        })
    }

    fun acceptIncomingConnection(
        remoteLabel: String,
        sendText: (String) -> Boolean,
        closeTransport: (Int, String) -> Unit,
        sendBinary: ((ByteArray) -> Boolean)? = null,
    ) {
        _connectionState.value = ConnectionState.CONNECTING
        patchUi {
            it.copy(
                wsUrl = "incoming://$remoteLabel/sendspin",
                clientId = clientId,
                clientName = clientName,
                status = "incoming_connecting",
                connected = false,
                playerVolume = currentVolume,
                isLowMemoryDevice = isLowMemoryDevice
            )
        }
        output.checkAudioCapabilities(context)
        transportSendText = sendText
        transportSendBinary = sendBinary
        transportClose = closeTransport
        markTransportOpen("incoming_open")
    }

    /**
     * Adopt a connection that already completed the arbitration mini-handshake
     * ([SendspinArbiter] sent `client/hello` and received `server/hello`).
     * Skips a second hello on the same WebSocket and immediately applies the
     * cached `server/hello` payload.
     */
    fun acceptIncomingConnectionAfterArbitration(
        remoteLabel: String,
        sendText: (String) -> Boolean,
        closeTransport: (Int, String) -> Unit,
        serverHelloPayload: JSONObject,
        sendBinary: ((ByteArray) -> Boolean)? = null,
        establishedTransport: SendspinNoiseTransport? = null,
    ) {
        _connectionState.value = ConnectionState.CONNECTING
        patchUi {
            it.copy(
                wsUrl = "incoming://$remoteLabel/sendspin",
                clientId = clientId,
                clientName = clientName,
                status = "arbitration_adopting",
                connected = true,
                playerVolume = currentVolume,
                isLowMemoryDevice = isLowMemoryDevice
            )
        }
        output.checkAudioCapabilities(context)
        transportSendText = sendText
        transportSendBinary = sendBinary
        transportClose = closeTransport
        isConnected.set(true)
        noiseTransport = establishedTransport
        if (establishedTransport != null) {
            SendspinLegacyGate.clear()
        }
        // Hello already went out during arbitration; do not send a second one.
        awaitingEncryptedServerHello = false
        applyServerHello(serverHelloPayload)
    }

    /** Send a spec `client/goodbye` without closing the transport. */
    fun sendGoodbye(reason: GoodbyeReason) {
        runCatching { sendClientGoodbye(reason) }
    }

    /** Tear down client state when the transport was already closed externally. */
    fun teardownAfterTransportLost(status: String) {
        teardown(status)
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun isHandshakeComplete(): Boolean = handshakeComplete

    /**
     * Backward-compatible string form. Normalised to a spec-valid [GoodbyeReason].
     * Pass `"resource_cleanup"` to skip the goodbye entirely.
     */
    fun close(reason: String) {
        if (reason == "resource_cleanup") {
            closeWithReason(null, transportReason = reason)
        } else {
            closeWithReason(GoodbyeReason.fromLegacy(reason), transportReason = reason)
        }
    }

    /** Type-safe variant – preferred for new call sites (e.g. server arbitration). */
    fun close(reason: GoodbyeReason) {
        closeWithReason(reason, transportReason = reason.wire)
    }

    private fun closeWithReason(reason: GoodbyeReason?, transportReason: String) {
        if (reason != null) {
            runCatching { sendClientGoodbye(reason) }
        }
        transportClose?.invoke(1000, transportReason)
        ws?.close(1000, transportReason)
        teardown("client_close:$transportReason")
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun cleanupResources() {
        runCatching { close("resource_cleanup") }
        playoutScope.coroutineContext.cancel()
        scope.coroutineContext.cancel()
    }

    fun setSyncOffsetMs(ms: Int, userAdjusted: Boolean = false) {
        userSyncOffsetMs = if (userAdjusted) {
            ms.coerceIn(-MAX_USER_OFFSET_MS, MAX_USER_OFFSET_MS).toLong()
        } else {
            0L
        }
        appliedManualOffsetUs = userSyncOffsetMs * 1000L
        applyProtocolStaticDelayMs(protocolStaticDelayMs)
        patchUi { it.copy(playoutOffsetMs = currentAppliedOffsetMs()) }
    }

    fun updateVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        currentVolume = clamped
        sendClientStatePlayer(volume = clamped, muted = null)
        patchUi { it.copy(playerVolume = clamped, playerVolumeFromServer = false) }
    }

    fun updateMuted(muted: Boolean) {
        currentMuted = muted
        sendClientStatePlayer(volume = null, muted = muted)
        patchUi { it.copy(playerMuted = muted, playerMutedFromServer = false) }
    }

    // NOTE: never add an AudioTrack.setVolume()-based volume path here — gain applied
    // after the PCM tee is invisible to the AEC reference and breaks echo cancellation.
    // Volume must go through setPlaybackGain (in-PCM) or device STREAM_MUSIC volume
    // (flagged to the AEC via PlaybackReferenceBus.noteLevelChange()).

    /** Apply user/device volume in PCM (attenuation only); AudioTrack stays at unity. */
    fun setPlaybackGain(gain: SendspinPcmProcessor.PlaybackGain) {
        output.setPlaybackGain(gain)
        output.setVolume(1f)
    }

    /**
     * Voice/TTS overlay: PCM stays ducked via [SendspinManager.duck], but playout
     * must not re-request [AudioManager.AUDIOFOCUS_GAIN] — that steals focus from
     * URL TTS (ExoPlayer) and aborts the reply + floating subtitles.
     */
    @Volatile
    private var voiceOverlayDucked: Boolean = false

    fun setVoiceOverlayDucked(ducked: Boolean) {
        voiceOverlayDucked = ducked
    }

    private var playbackGainRefresh: (() -> SendspinPcmProcessor.PlaybackGain)? = null

    fun setPlaybackGainRefresh(refresh: () -> SendspinPcmProcessor.PlaybackGain) {
        playbackGainRefresh = refresh
    }

    private fun refreshPlaybackGainBeforeStart() {
        playbackGainRefresh?.invoke()?.let { setPlaybackGain(it) }
    }

    /** Pause the speaker without tearing the session — user pause / failed wire send. */
    fun pauseAudioOutput() {
        output.pause()
    }

    fun stopPlayback() {
        output.stop()
        audioFocus.release()
        outputStartedAtUs = 0L
        speedController.reset()
        jitter.clear()
        opusDecoder?.release()
        opusDecoder = null
        flacDecoder?.release()
        flacDecoder = null
        pendingFlacCodecHeader = null
        streamFormatReady = false
        artworkStreamActive = false
        artworkDecodeJob?.cancel()
        artworkDecodeJob = null
        codec = ""
        _currentCodec.value = null
        patchUi {
            it.copy(
                streamDesc = "",
                queuedChunks = 0,
                bufferAheadMs = 0,
                playbackState = "stopped"
            )
        }
    }

    fun isForeignGroupBlocked(): Boolean = groupIdentity.blocked

    fun sendMediaCommand(
        command: String,
        positionMs: Long? = null,
        offsetMs: Long? = null,
        volume: Int? = null,
        mute: Boolean? = null
    ): Boolean {
        val negotiated = resolveControllerCommand(command)
        if (groupIdentity.blocked && negotiated in FOREIGN_GROUP_BLOCKED_COMMANDS) {
            Log.w(TAG, "Drop $negotiated: foreign Sendspin group ${groupIdentity.currentGroupId}")
            return false
        }
        // Spec: commands not in supported_commands are ignored by the server.
        if (serverSupportedControllerCommands.isNotEmpty() &&
            negotiated !in serverSupportedControllerCommands
        ) {
            Log.w(TAG, "Drop unsupported controller command=$negotiated supported=$serverSupportedControllerCommands")
            return false
        }
        val controllerObj = JSONObject().put("command", negotiated)
        when (negotiated) {
            "seek" -> positionMs?.let { controllerObj.put("position_ms", it.coerceAtLeast(0L)) }
            "seek_relative" -> offsetMs?.let { controllerObj.put("offset_ms", it) }
            "volume" -> volume?.let { controllerObj.put("volume", it.coerceIn(0, 100)) }
            "mute" -> mute?.let { controllerObj.put("mute", it) }
        }
        val payload = JSONObject().put("controller", controllerObj)
        return sendJson("client/command", payload)
    }

    /**
     * May we issue controller commands at all? The server decides per session via
     * `active_roles`, and may revoke the role mid-session, after which every
     * command is dropped on arrival.
     *
     * Undeclared roles count as usable — see [activeRolesDeclared].
     */
    private fun controllerRoleUsable(): Boolean = !activeRolesDeclared || controllerRoleActive

    /** Absolute seek allowed by server controller advertisement. */
    fun supportsAbsoluteSeek(): Boolean {
        if (!controllerRoleUsable()) return false
        val supported = serverSupportedControllerCommands
        // Unknown until first controller object — allow; server will ignore if absent.
        if (supported.isEmpty()) return true
        return "seek" in supported
    }

    /**
     * Would [sendMediaCommand] reach the server for [command]? Same negotiation
     * (role + aliases + `supported_commands`) the send path uses, so callers can
     * skip an optimistic UI paint the server is going to ignore.
     *
     * Unknown (no `controller` object yet) counts as supported — matching
     * [supportsAbsoluteSeek]; the server drops what it does not implement.
     */
    fun supportsControllerCommand(command: String): Boolean {
        if (!controllerRoleUsable()) return false
        val supported = serverSupportedControllerCommands
        if (supported.isEmpty()) return true
        return resolveControllerCommand(command) in supported
    }

    /** Server-advertised `supported_commands`; empty = not negotiated yet. */
    fun supportedControllerCommands(): Set<String> = serverSupportedControllerCommands

    /**
     * Make a server-supplied URL fetchable. The spec calls `artwork_url` a URL,
     * but we do not get to pick our upstream and servers do emit bare paths, so
     * absolute stays as-is, `//host/path` inherits our transport scheme, and a
     * path resolves against the server's own origin.
     *
     * Null when nothing can be resolved: in **inbound** mode the server dialled
     * us, so [wsUrl] carries *our* listen port and the server's HTTP port is
     * unknown — no cover beats hammering the wrong port.
     */
    fun resolveServerRelativeUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val secure = wsUrl.startsWith("wss://")
        if (url.startsWith("//")) return if (secure) "https:$url" else "http:$url"
        if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) return null
        val origin = runCatching {
            val parsed = java.net.URI(wsUrl)
            val host = parsed.host?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val scheme = if (secure) "https" else "http"
            if (parsed.port == -1) "$scheme://$host" else "$scheme://$host:${parsed.port}"
        }.getOrNull() ?: return null
        return if (url.startsWith("/")) "$origin$url" else "$origin/$url"
    }

    fun seekMaxMs(): Long? = serverSeekMaxMs

    fun isHealthy(): Boolean {
        if (!isConnected.get() || !handshakeComplete) return false
        val now = System.currentTimeMillis()
        return (now - lastPlaybackHeartbeatMs) <= 10_000L &&
            (now - lastStatsHeartbeatMs) <= 10_000L
    }

    fun getStats(): SendspinClientStats {
        val state = uiState.value
        return SendspinClientStats(
            connectionState = when (_connectionState.value) {
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.CONNECTING -> "Connecting"
                ConnectionState.ERROR -> "Error"
                ConnectionState.DISCONNECTED -> "Disconnected"
            },
            audioCodec = codec.ifBlank { "--" },
            sampleRate = if (codec.isBlank()) 0 else sampleRate,
            bitDepth = if (codec.isBlank()) 0 else bitDepth,
            channels = if (codec.isBlank()) 0 else channels,
            playbackState = if (state.playbackState.isBlank()) "Unknown" else state.playbackState,
            syncUncertaintyUs = timeFilter.getOffsetUncertaintyUs(),
            rttUs = timeFilter.getAverageRttUs(),
            networkQuality = timeFilter.getNetworkConditionQuality().name,
            clockStability = timeFilter.getClockStability().name,
            // Scheduling latency — same estimate the wait/auto-offset loops use.
            audioLatencyUs = output.getSchedulingPipelineLatencyUs(),
            playoutOffsetUs = targetPlayoutOffsetUs(),
            lateDrops = state.lateDrops,
            queuedChunks = state.queuedChunks,
            audibleSyncs = audibleSyncCount,
            kalmanErrorCount = timeFilter.getKalmanErrorCount(),
            clockDriftPpm = timeFilter.estimatedDriftPpm(),
            bufferAheadMs = state.bufferAheadMs,
            chunksReceived = chunksReceived,
            chunksPlayed = chunksPlayed,
            driftUncertaintyPpm = timeFilter.getDriftUncertaintyPpm(),
            driftSnr = timeFilter.getDriftSnr(),
            chunksDropped = chunksDropped,
            bufferUnderrunCount = underrunCount,
            playbackSpeed = output.getCurrentPlaybackSpeed(),
            isConnected = _connectionState.value == ConnectionState.CONNECTED,
            connectionDrops = 0,
            clockReadyForPlayback = isClockReadyForPlayback(),
            forceResyncActive = forceResyncMode,
            audioOutputStarted = output.isStarted(),
            serverLatenessMs = 0L,
            networkJitterMs = timeFilter.getEstimatedNetworkJitterUs() / 1000L,
            clockUpdateCount = timeFilter.getUpdateCount(),
            staticDelayMs = (timeFilter.getEffectiveDelayUs() / 1000L),
            // Residual Kalman offset only (not baseline+offset absolute wall delta).
            estimatedOffsetMs = timeFilter.getOffsetUs() / 1000L
        )
    }

    private fun checkIsLowMemoryDevice(): Boolean {
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            (memInfo.totalMem) < 2_000_000_000L
        } catch (_: Exception) {
            false
        }
    }

    private fun getConnectionType(): String {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return "Unknown"
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val info = connectivityManager.activeNetworkInfo ?: return "Disconnected"
                @Suppress("DEPRECATION")
                return when (info.type) {
                    android.net.ConnectivityManager.TYPE_WIFI -> "WiFi"
                    android.net.ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
                    android.net.ConnectivityManager.TYPE_MOBILE -> "Cellular"
                    android.net.ConnectivityManager.TYPE_BLUETOOTH -> "Bluetooth"
                    else -> "Other"
                }
            }
            val activeNetwork = connectivityManager.activeNetwork ?: return "Disconnected"
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return "Unknown"
            when {
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                else -> "Other"
            }
        } catch (_: Exception) {
            "Unknown"
        }
    }

    private fun getActualSystemVolume(): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            (current * 100 / maxVolume).coerceIn(0, 100)
        } catch (_: Exception) {
            100
        }
    }

    private fun teardown(status: String) {
        isConnected.set(false)
        handshakeComplete = false
        ws = null
        transportSendText = null
        transportSendBinary = null
        transportClose = null
        noiseHandshake = null
        noiseTransport = null
        awaitingEncryptedServerHello = false

        playoutJob?.cancel()
        playoutJob = null
        timeLoopJob?.cancel()
        timeLoopJob = null
        statsJob?.cancel()
        statsJob = null
        speedAdjustmentJob?.cancel()
        speedAdjustmentJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        memoryMonitorJob?.cancel()
        memoryMonitorJob = null

        output.stop()
        audioFocus.release()
        outputStartedAtUs = 0L
        speedController.reset()
        jitter.clear()
        opusDecoder?.release()
        opusDecoder = null
        flacDecoder?.release()
        flacDecoder = null
        pendingFlacCodecHeader = null
        streamFormatReady = false
        artworkStreamActive = false
        artworkDecodeJob?.cancel()
        artworkDecodeJob = null
        codec = ""
        decodeLatencyUs = 0L
        decodeLatencySamples.clear()
        playAtServerUs = Long.MIN_VALUE
        fallbackStreamKey = Long.MIN_VALUE
        foreignLeaveJob?.cancel()
        foreignLeaveJob = null
        lastForeignLeaveElapsed = 0L
        foreignProbationJob?.cancel()
        foreignProbationJob = null
        foreignSelfHealJob?.cancel()
        foreignSelfHealJob = null
        foreignConfirmed = false
        foreignStreamActivity = false
        foreignLeftSharedGroup = false
        pendingBlockedStreamStart = null
        lastGroupUpdateName = ""
        val wasBlocked = groupIdentity.blocked
        groupIdentity.reset()
        if (wasBlocked) onForeignGroupBlocked?.invoke(false)
        lastChunkServerTimestampUs = Long.MIN_VALUE
        audioScheduleDebugCount = 0
        controllerRoleActive = false
        activeRolesDeclared = false
        activeRolesCsv = ""
        serverActivities = emptySet()
        initialStatePending = false
        serverSupportedControllerCommands = emptySet()
        serverSeekMaxMs = null
        _currentCodec.value = null

        patchUi {
            it.copy(
                status = status,
                connected = false,
                activeRoles = "",
                // Derived from activeRoles: clearing one without the others left
                // the next session showing the previous server's roles.
                hasController = false,
                hasMetadata = false,
                streamDesc = "",
                playbackState = "",
                groupName = "",
                queuedChunks = 0,
                bufferAheadMs = 0,
                trackTitle = null,
                trackArtist = null,
                albumTitle = null,
                albumArtist = null,
                trackYear = null,
                trackNumber = null,
                artworkUrl = null,
                trackProgress = null,
                trackDuration = null,
                playbackSpeed = null,
                repeatMode = null,
                shuffleEnabled = null
            )
        }
    }

    private fun normalizeStreamCodec(raw: String): String = raw.trim().lowercase()

    private fun isSupportedStreamCodec(codecName: String): Boolean =
        codecName in SUPPORTED_STREAM_CODECS

    private fun currentPreferredFormat(): String =
        SendspinFormatCatalog.normalizePreferredFormat(preferredFormatProvider?.invoke() ?: preferredFormat)

    /** RFC 9639 FLAC frame sync or leading stream marker in a type-4 payload. */
    private fun looksLikeFlac(data: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0) return false
        if (SendspinFlacDecoder.hasFlacStreamMarker(data, offset, length)) return true
        return length >= 2 &&
            data[offset] == 0xFF.toByte() &&
            (data[offset + 1].toInt() and 0xFE) == 0xF8
    }

    private fun decodeOpusChunk(chunk: SendspinAudioJitterBuffer.Chunk): ByteArray {
        val decodeStart = nowUs()
        val decoded = opusDecoder?.decode(chunk.data, chunk.offset, chunk.length) ?: ByteArray(0)
        recordDecodeLatency(nowUs() - decodeStart)
        return decoded
    }

    private fun decodeFlacChunk(chunk: SendspinAudioJitterBuffer.Chunk): ByteArray {
        val decodeStart = nowUs()
        val decoded = decodeFlacFrame(chunk.copyPayload())
        recordDecodeLatency(nowUs() - decodeStart)
        return decoded
    }

    private fun decodeChunkToPcm(chunk: SendspinAudioJitterBuffer.Chunk): ByteArray =
        when (codec) {
            "opus" -> decodeOpusChunk(chunk)
            "flac" -> decodeFlacChunk(chunk)
            else -> chunk.copyPayload()
        }

    private fun serverTimestampHeardUs(serverTimestampUs: Long): Long {
        val effectiveServerTsUs = if (playAtServerUs != Long.MIN_VALUE) {
            maxOf(serverTimestampUs, playAtServerUs)
        } else {
            serverTimestampUs
        }
        return timeFilter.convertServerToClient(effectiveServerTsUs) -
            timeFilter.getEffectiveDelayUs() -
            output.getPostPresentationLatencyUs() +
            effectivePlayoutOffsetUs()
    }

    private fun chunkHeardPlayUs(chunk: SendspinAudioJitterBuffer.Chunk): Long =
        serverTimestampHeardUs(chunk.serverTimestampUs)

    private fun isClockReadyForPlayback(): Boolean {
        val avgRttUs = timeFilter.getAverageRttUs()
        val maxUncertaintyUs = maxOf(50_000L, avgRttUs / 2L)
        return timeFilter.hasConverged() &&
            timeFilter.getOffsetUncertaintyUs() <= maxUncertaintyUs &&
            avgRttUs <= 2_000_000L
    }

    /**
     * Advance along the server timeline only when past [playThroughLateUs].
     * Mild lateness must play through — skipping here is what makes sync "choppy"
     * and then look like it needs aggressive catch-up.
     */
    private fun alignChunkToTimeline(
        chunk: SendspinAudioJitterBuffer.Chunk,
        pcmData: ByteArray,
        playThroughLateUs: Long,
        lateDropUs: Long
    ): Pair<SendspinAudioJitterBuffer.Chunk, ByteArray> {
        var activeChunk = chunk
        var activePcm = pcmData
        var earlyUs = chunkHeardPlayUs(activeChunk) - nowUs()
        var skips = 0
        while (earlyUs < -playThroughLateUs && skips < 50) {
            val next = jitter.pollPlayable(nowUs(), lateDropUs) ?: break
            skips++
            activeChunk = next
            activePcm = decodeChunkToPcm(next)
            if (activePcm.isEmpty()) continue
            earlyUs = chunkHeardPlayUs(activeChunk) - nowUs()
        }
        if (skips > 0) {
            audibleSyncCount += skips
            output.requestSpliceFade()
        }
        return activeChunk to activePcm
    }

    private suspend fun waitUntilScheduled(heardPlayUs: Long) {
        val pipelineLatencyUs = output.getSchedulingPipelineLatencyUs()
        var scheduleRemainingUs = heardPlayUs - nowUs() - pipelineLatencyUs
        while (scheduleRemainingUs > 2_000L && output.isStarted()) {
            val waitMs = (scheduleRemainingUs / 1000L).coerceIn(1L, 10L)
            delay(waitMs)
            scheduleRemainingUs = heardPlayUs - nowUs() - pipelineLatencyUs
        }
    }

    private suspend fun runCatchUpDrop(lateDropUs: Long): Boolean {
        var dropped = 0
        while (dropped < CATCHUP_MAX_DROPS) {
            val next = jitter.pollPlayable(nowUs(), lateDropUs) ?: break
            val nextPcm = decodeChunkToPcm(next)
            dropped++
            if (nextPcm.isEmpty()) continue
            val nextEarlyUs = chunkHeardPlayUs(next) - nowUs()
            if (serverLatenessMs(nextEarlyUs) <= CATCHUP_TARGET_LATE_MS) {
                output.requestSpliceFade()
                waitUntilScheduled(chunkHeardPlayUs(next))
                if (output.ensurePlaying()) {
                    val (anchorFrames, anchorGeneration) = output.writtenFramesAndGeneration()
                    if (output.writePcm(nextPcm)) {
                        audibleFrameAnchor = AudibleFrameAnchor(
                            serverTsUs = next.serverTimestampUs,
                            frames = anchorFrames,
                            generation = anchorGeneration,
                        )
                        chunksPlayed++
                        notePcmWritten()
                        noteAudibleChunkWritten(next.serverTimestampUs)
                    }
                }
                break
            }
        }
        if (dropped > 0) {
            audibleSyncCount++
        }
        return dropped > 0
    }

    private var syncedFlacPcmEncoding = Int.MIN_VALUE

    private fun decodeFlacFrame(encoded: ByteArray): ByteArray {
        val decoder = flacDecoder ?: return ByteArray(0)
        val frame = SendspinFlacDecoder.stripLeadingStreamHeaderIfPresent(encoded)
        val pcm = decoder.decode(frame)
        syncFlacDecoderFormat(decoder)
        return pcm
    }

    private fun syncFlacDecoderFormat(decoder: SendspinFlacDecoder) {
        val encoding = decoder.outputPcmEncoding
        if (encoding == syncedFlacPcmEncoding) return
        syncedFlacPcmEncoding = encoding
        val format = SendspinPcmProcessor.SourceFormat(
            bitDepth = decoder.outputPcmBitDepth,
            pcmEncoding = encoding
        )
        output.setSourceFormat(format)
        Log.i(
            TAG,
            "FLAC output format synced: encoding=$encoding depth=${decoder.outputPcmBitDepth} " +
                "layout=${format.toInputFormat()}"
        )
    }

    private fun recordDecodeLatency(decodeTimeUs: Long) {
        decodeLatencySamples.addLast(decodeTimeUs)
        while (decodeLatencySamples.size > 30) {
            decodeLatencySamples.removeFirst()
        }
        decodeLatencyUs = decodeLatencySamples.average().toLong()
    }

    private fun throttledUiUpdate(block: (SendspinUiState) -> SendspinUiState) {
        val now = nowUs()
        if (now - lastUiUpdateUs >= uiUpdateThrottleMs * 1000L) {
            lastUiUpdateUs = now
            patchUi(block)
        }
    }

    private fun patchUi(block: (SendspinUiState) -> SendspinUiState) {
        _uiState.value = block(_uiState.value)
    }

    private fun sendJson(type: String, payload: JSONObject): Boolean {
        val text = JSONObject().put("type", type).put("payload", payload).toString()
        val encrypted = noiseTransport
        if (encrypted != null) {
            val sendBin = transportSendBinary ?: return false
            return try {
                encrypted.encryptJson(text).all { sendBin(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "encrypted send failed: ${t.message}")
                false
            }
        }
        return transportSendText?.invoke(text) == true
    }

    private fun markTransportOpen(status: String) {
        isConnected.set(true)
        handshakeComplete = false
        noiseHandshake = null
        noiseTransport = null
        awaitingEncryptedServerHello = false
        patchUi { it.copy(status = status, connected = true) }
        val sendText = transportSendText
        if (sendText == null || SendspinLegacyGate.preferLegacy()) {
            sendClientHello()
            return
        }
        val hs = SendspinNoiseHandshake(SendspinNoiseIdentity.get(context), sendText)
        noiseHandshake = hs
        if (!hs.start()) {
            noiseHandshake = null
            sendClientHello()
        }
    }

    private fun buildPlayerSupportObject(): JSONObject =
        buildPlayerSupportObjectStatic(isLowMemoryDevice, currentPreferredFormat())

    private fun sendClientHello() {
        sendJson(
            "client/hello",
            buildClientHelloPayload(
                context,
                clientId,
                clientName,
                isLowMemoryDevice,
                currentPreferredFormat(),
                includeLegacyClientId = noiseTransport == null,
            ),
        )
        patchUi { it.copy(status = "sent client/hello") }
    }

    private fun applyProtocolStaticDelayMs(delayMs: Int) {
        protocolStaticDelayMs = delayMs.coerceIn(0, 5_000)
        timeFilter.setStaticDelayMsUserAdjusted(protocolStaticDelayMs.toDouble())
    }

    private fun refreshProtocolStaticDelay() {
        applyProtocolStaticDelayMs(protocolStaticDelayMs)
    }

    /**
     * Spec-compliant `client/state` payload.
     *
     * Per Sendspin spec, `state` is a top-level field on the payload (one of
     * `synchronized` / `error` / `external_source`); `player` is a sibling
     * object carrying player-role fields. `required_lead_time_ms` and
     * `min_buffer_ms` are "always required for players" per spec—they tell
     * the server how far ahead to schedule the first audio chunk timestamp.
     */
    private fun buildClientStatePayload(
        state: String,
        volume: Int? = null,
        muted: Boolean? = null,
        includePlayer: Boolean = true
    ): JSONObject {
        val payload = JSONObject()
        if (noiseTransport != null) {
            /*
             * Spec field. aiosendspin flags any top-level `state` as a legacy
             * deviation and, in strict mode (MA "allow legacy clients" off),
             * drops the client on the first client/state. Its own legacy
             * mapping is `available = state != "external_source"`.
             */
            payload.put("available", state != "external_source")
        } else {
            payload.put("state", state)
        }
        if (includePlayer && playerRoleUsable()) {
            refreshProtocolStaticDelay()
            val player = JSONObject()
                .put("static_delay_ms", protocolStaticDelayMs)
                .put("required_lead_time_ms", computeRequiredLeadTimeMs())
                .put("min_buffer_ms", computeMinBufferMs())
                .put("supported_commands", JSONArray().put("set_static_delay"))
            volume?.let { player.put("volume", it) }
            muted?.let { player.put("muted", it) }
            payload.put("player", player)
        }
        return payload
    }

    private fun maybeAdvertisePostPresentationLead() {
        val postUs = output.getPostPresentationLatencyUs()
        if (postUs < 40_000L) return
        if (advertisedPostPresentationUs >= 0L &&
            kotlin.math.abs(postUs - advertisedPostPresentationUs) < 80_000L
        ) {
            return
        }
        advertisedPostPresentationUs = postUs
        sendClientStateSynchronized()
    }

    private fun computeRequiredLeadTimeMs(): Int {
        val audioTrackBufferMs = (output.getConfiguredStaticDelayUs() / 1000L).toInt()
        // Pre-port path getTimestamp cannot see (JS outputLatency remainder).
        // Spec: required_lead includes client processing, not output_delay_ms.
        val postPresentationMs = (output.getPostPresentationLatencyUs() / 1000L).toInt()
        // Codec init + Kalman warmup + AudioTrack backend buffering.
        // Spec range is 0–5000; the old 2000 cap could not match AirPlay's
        // 2500ms warm lead when this device was the group's max.
        return (audioTrackBufferMs + 150 + postPresentationMs).coerceIn(200, 5_000)
    }

    private fun computeMinBufferMs(): Int {
        // Ongoing jitter buffer depth. For Opus (20ms frames) network jitter
        // of even 100ms depletes a shallow buffer instantly. A deeper buffer
        // gives the server room to send further ahead, smoothing transient
        // network stalls without breaking sync.
        return if (isLowMemoryDevice) 750 else 600
    }

    private fun sendClientStatePlayer(volume: Int? = null, muted: Boolean? = null) {
        sendJson(
            "client/state",
            buildClientStatePayload(
                state = "synchronized",
                volume = volume,
                muted = muted
            )
        )
    }

    private fun sendClientGoodbye(reason: GoodbyeReason) {
        sendJson("client/goodbye", JSONObject().put("reason", reason.wire))
    }

    /**
     * Apply an `active_roles` array from whichever message carried it.
     *
     * Returns false when the field was absent, which for [server/activate] means
     * "keep the previous set" (spec: required on the first activate, persists
     * across later ones that omit it) rather than "no roles".
     */
    private fun applyActiveRoles(payload: JSONObject): Boolean {
        val arr = payload.optJSONArray("active_roles") ?: return false
        val roles = (0 until arr.length()).joinToString(",") { arr.getString(it) }
        activeRolesCsv = roles
        activeRolesDeclared = true
        controllerRoleActive = roles.contains("controller")
        patchUi {
            it.copy(
                activeRoles = roles,
                hasController = roles.contains("controller"),
                hasMetadata = roles.contains("metadata"),
            )
        }
        return true
    }

    /**
     * `server/activate` declares this connection's purpose and the roles we may
     * actually use, and may be re-sent at any time to change either. Without it
     * a role the server grants late (or revokes mid-session) never reaches our
     * state, so the overlay keeps offering controls the server has stopped
     * accepting.
     *
     * The spec has the server end a removed role's output itself — `stream/end`
     * for stream roles, a null role object for state roles — so there is nothing
     * to tear down here beyond the flags.
     */
    private fun applyServerActivate(payload: JSONObject) {
        payload.optJSONArray("activities")?.let { arr ->
            serverActivities = (0 until arr.length()).map { arr.getString(it) }.toSet()
            // Encrypted servers do not send connection_reason; `activities`
            // is its replacement, and the arbiter's incumbent tie-break reads it.
            serverConnectionReason = if ("playback" in serverActivities) "playback" else "discovery"
        }
        val playerWasActive = playerRoleUsable()
        val rolesDeclared = applyActiveRoles(payload)
        Log.i(
            TAG,
            "server/activate activities=$serverActivities roles=" +
                if (rolesDeclared) activeRolesCsv else "$activeRolesCsv (unchanged)"
        )
        /*
         * Encrypted sessions defer the initial client/state to here (roles were
         * unknown at server/hello). A later activate that grants player — MA
         * does this once it auto-approves guest access — also needs a fresh
         * state, because aiosendspin treats the first client/state after the
         * player role becomes active as the role's required initial state.
         */
        if (initialStatePending || (rolesDeclared && playerRoleUsable() && !playerWasActive)) {
            initialStatePending = false
            sendClientStateSynchronized()
        }
    }

    /**
     * Whether a `player` object may be sent in client/state. Undeclared roles
     * count as usable (legacy servers); once declared, aiosendspin flags — and in
     * strict mode rejects — a player object for an inactive role.
     */
    private fun playerRoleUsable(): Boolean = !activeRolesDeclared || activeRolesCsv.contains("player")

    private fun applyServerHello(payload: JSONObject) {
        handshakeComplete = true
        _connectionState.value = ConnectionState.CONNECTED
        // Spec puts `active_roles` on server/activate, but servers that predate
        // that still put it here; treated as a declaration either way.
        applyActiveRoles(payload)
        // Arbitration may fold the first `server/activate` into this payload.
        payload.optJSONArray("activities")?.let { arr ->
            serverActivities = (0 until arr.length()).map { arr.getString(it) }.toSet()
        }

        // Encrypted server/hello carries only `name`; the server id is the
        // handshake-authenticated static key from server/init.
        serverId = payload.optString("server_id", "")
            .ifEmpty { noiseTransport?.serverId.orEmpty() }
        serverConnectionReason = payload
            .optString("connection_reason", "discovery")
            .ifBlank { "discovery" }
        onServerHello?.invoke(serverId, serverConnectionReason)

        // Roles are left to applyActiveRoles: undeclared must stay unknown here,
        // not be flattened to "no roles".
        patchUi { it.copy(status = "server/hello", connected = true) }
        if (awaitingEncryptedServerHello) {
            awaitingEncryptedServerHello = false
            sendClientHello()
        }
        startTimeSyncLoop()
        startPlayoutLoop()
        startStatsLoop()
        startPlaybackSpeedAdjustmentLoop()
        startWatchdogLoop()
        startMemoryMonitoringLoop()
        if (noiseTransport != null && !activeRolesDeclared) {
            // Encrypted hello carries no roles; server/activate follows at once.
            initialStatePending = true
        } else {
            sendClientStateSynchronized()
        }
    }

    fun trimAudioBufferCritical() = trimAudioBuffer(1.0 / 3.0, minChunks = 150, level = "CRITICAL")

    fun trimAudioBufferModerate() = trimAudioBuffer(0.5, minChunks = 200, level = "MODERATE")

    fun trimAudioBufferLow() = trimAudioBuffer(0.5, minChunks = 200, level = "LOW")

    private fun trimAudioBuffer(targetPercent: Double, minChunks: Int, level: String) {
        val currentSize = jitter.size()
        if (currentSize <= minChunks) return
        val targetSize = (currentSize * targetPercent).toInt().coerceAtLeast(minChunks)
        if (targetSize >= currentSize) return
        val dropped = jitter.trimTo(targetSize)
        Log.w(TAG, "$level memory trim: $currentSize -> $targetSize chunks (dropped=$dropped)")
        val snapshot = jitter.snapshot()
        chunksDropped = snapshot.lateDrops
        throttledUiUpdate {
            it.copy(
                queuedChunks = snapshot.queuedChunks,
                bufferAheadMs = snapshot.bufferAheadMs,
                lateDrops = snapshot.lateDrops,
            )
        }
    }

    private fun startMemoryMonitoringLoop() {
        if (!isLowMemoryDevice) return
        memoryMonitorJob?.cancel()
        memoryMonitorJob = scope.launch {
            while (isActive && isConnected.get()) {
                try {
                    val activityManager =
                        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val memInfo = ActivityManager.MemoryInfo()
                    activityManager?.getMemoryInfo(memInfo)
                    val availableMemMb = (memInfo?.availMem ?: 0L) / (1024 * 1024)
                    when {
                        memInfo?.lowMemory == true -> {
                            Log.w(TAG, "System lowMemory flag set, trimming jitter buffer")
                            trimAudioBufferLow()
                        }
                        availableMemMb < 50 -> {
                            Log.e(TAG, "Critical memory available (${availableMemMb}MB), trimming jitter buffer")
                            trimAudioBufferCritical()
                        }
                        availableMemMb < 100 -> {
                            Log.w(TAG, "Low memory available (${availableMemMb}MB), trimming jitter buffer")
                            trimAudioBufferModerate()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error during memory monitoring", e)
                }
                delay(5_000L)
            }
        }
    }

    private fun sendClientStateSynchronized(volume: Int = currentVolume, muted: Boolean = currentMuted) {
        sendJson(
            "client/state",
            buildClientStatePayload(
                state = "synchronized",
                volume = volume,
                muted = muted
            )
        )
    }

    private fun sendClientStateError(volume: Int = currentVolume, muted: Boolean = currentMuted) {
        if (streamEnded) return
        sendJson(
            "client/state",
            buildClientStatePayload(
                state = "error",
                volume = volume,
                muted = muted
            )
        )
    }

    private fun startTimeSyncLoop() {
        timeLoopJob?.cancel()
        timeLoopJob = scope.launch {
            while (isActive && isConnected.get()) {
                sendJson("client/time", JSONObject().put("client_transmitted", nowUs()))
                val nextIntervalMs = timeFilter.getRecommendedSyncFrequencyMs()
                delay(nextIntervalMs)
            }
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && isConnected.get()) {
                val snapshot = jitter.snapshot()
                val lateDropsNow = snapshot.lateDrops
                chunksDropped = lateDropsNow
                lastStatsHeartbeatMs = System.currentTimeMillis()
                throttledUiUpdate {
                    it.copy(
                        offsetUncertaintyUs = timeFilter.getOffsetUncertaintyUs(),
                        driftPpm = timeFilter.estimatedDriftPpm(),
                        driftUncertaintyPpm = timeFilter.getDriftUncertaintyPpm(),
                        driftSnr = timeFilter.getDriftSnr(),
                        rttUs = timeFilter.getAverageRttUs(),
                        networkQuality = timeFilter.getNetworkConditionQuality().toString(),
                        stability = timeFilter.getClockStability().toString(),
                        connectionType = getConnectionType(),
                        queuedChunks = snapshot.queuedChunks,
                        bufferAheadMs = snapshot.bufferAheadMs,
                        lateDrops = lateDropsNow,
                        audibleSyncCount = audibleSyncCount,
                        kalmanErrorCount = timeFilter.getKalmanErrorCount(),
                        playoutOffsetMs = currentAppliedOffsetMs(),
                        playbackSpeedMultiplier = output.getCurrentPlaybackSpeed(),
                        smoothedLatencyMs = output.getSmoothedLatencyMs()
                    )
                }
                considerAdaptiveFormatDowngrade(lateDropsNow)
                delay(1_000L)
            }
        }
    }

    /**
     * Previously downgraded to Opus under network stress; disabled because
     * Opus soft-decode stutters. We only request smooth lossless fallbacks now.
     */
    private fun considerAdaptiveFormatDowngrade(@Suppress("UNUSED_PARAMETER") currentLateDrops: Long) {
        // No Opus downgrade — stay on server-selected FLAC/PCM.
    }

    private fun enterDiscontinuityMode() {
        inDiscontinuityMode = true
        inDiscontinuityModeUntilUs = nowUs() + 1_000_000L
        lastStreamSpliceUs = nowUs()
        nextStartAttemptUs = 0L
        forceResyncMode = false
        forceResyncUntilUs = 0L
    }

    /**
     * Server just spliced this session (seek/clear/end). A paired follower
     * that also sends controller.seek would cut AudioTrack a second time.
     */
    fun inRecentStreamSplice(): Boolean {
        if (inDiscontinuityMode) return true
        return lastStreamSpliceUs > 0L && nowUs() - lastStreamSpliceUs < 2_500_000L
    }

    private fun resetStreamPlaybackForTrackChange(codecChanged: Boolean) {
        if (codecChanged) {
            output.stop()
            opusDecoder?.release()
            opusDecoder = null
            flacDecoder?.release()
            flacDecoder = null
        } else {
            output.pause()
            opusDecoder?.reset()
            flacDecoder?.reset()
        }
        outputStartedAtUs = 0L
        speedController.reset()
    }

    private fun sendStreamRequestPreferredSmoothFormat(reason: String, preferFlac: Boolean = false) {
        val payload = SendspinFormatCatalog.buildStreamRequestFormatPayload(preferFlac) ?: return
        val player = payload.getJSONObject("player")
        Log.w(
            TAG,
            "Requesting smooth format ${player.optString("codec")} " +
                "${player.optInt("sample_rate")}/${player.optInt("bit_depth")} via stream/request-format ($reason)"
        )
        sendJson("stream/request-format", payload)
        lastFormatRequestUs = nowUs()
        underrunCountAtLastRequest = underrunCount
        lateDropsAtLastRequest = jitter.snapshot().lateDrops
    }

    private fun startPlaybackSpeedAdjustmentLoop() {
        speedAdjustmentJob?.cancel()
        speedAdjustmentJob = playoutScope.launch {
            while (isActive && isConnected.get()) {
                // Manual offset is sacred: user chose a fixed delay — no rate
                // chasing, no auto anything. Catch-up belongs to 0ms auto mode.
                if (userSyncOffsetMs != 0L) {
                    output.setPlaybackSpeed(1.0f)
                    speedController.reset()
                    lastTimelineEarlyUs = Long.MIN_VALUE
                    delay(1_000L)
                    continue
                }
                // Must pass timeline samples — without them steady-state catch-up
                // is drift-only and initial desync can linger for a minute+.
                val needsHardCorrection = speedController.adjustSpeed(
                    nowUs = nowUs(),
                    driftPpm = timeFilter.estimatedDriftPpm(),
                    timelineEarlyUs = lastTimelineEarlyUs,
                    pipelineLatencyUs = lastTimelinePipelineLatencyUs
                )
                if (needsHardCorrection && output.isStarted() &&
                    nowUs() - lastHardCorrectionAtUs > 10_000_000L &&
                    !inDiscontinuityMode &&
                    (lastStreamSpliceUs == 0L || nowUs() - lastStreamSpliceUs > 4_000_000L) &&
                    (outputStartedAtUs == 0L || nowUs() - outputStartedAtUs > 4_000_000L)
                ) {
                    lastHardCorrectionAtUs = nowUs()
                    startupHardCorrectionArmed = false
                    Log.w(TAG, "Startup buffer error beyond hard-correction threshold, restarting playback")
                    output.pause()
                    outputStartedAtUs = 0L
                    speedController.reset()
                    triggerForceResync("startup_hard_correction")
                    nextStartAttemptUs = nowUs() + 100_000L
                }
                val inStartup =
                    outputStartedAtUs > 0L && (nowUs() - outputStartedAtUs) < 4_000_000L
                val lateSample = lastTimelineEarlyUs != Long.MIN_VALUE &&
                    lastTimelinePipelineLatencyUs > 0L &&
                    (lastTimelineEarlyUs - lastTimelinePipelineLatencyUs) < -5_000L
                val delayMs = when {
                    inStartup || lateSample -> 100L
                    else -> 400L
                }
                delay(delayMs)
            }
        }
    }

    private fun startPlayoutLoop() {
        playoutJob?.cancel()
        playoutJob = playoutScope.launch {
            // Timeline playout: skip only when a chunk is too late, then always schedule precisely.
            var lateRestartLoops = 0
            var restartBackoffMs = 200L
            nextStartAttemptUs = 0L

            while (isActive && isConnected.get()) {
                if (inDiscontinuityMode && nowUs() >= inDiscontinuityModeUntilUs) {
                    inDiscontinuityMode = false
                }

                val snapshot = jitter.snapshot()
                lastPlaybackHeartbeatMs = System.currentTimeMillis()
                throttledUiUpdate {
                    it.copy(
                        queuedChunks = snapshot.queuedChunks,
                        bufferAheadMs = snapshot.bufferAheadMs,
                        lateDrops = snapshot.lateDrops,
                        audibleSyncCount = audibleSyncCount,
                        kalmanErrorCount = timeFilter.getKalmanErrorCount()
                    )
                }

                if (!output.isStarted()) {
                    val nowLocalUs = nowUs()
                    if (nextStartAttemptUs > nowLocalUs) {
                        delay(((nextStartAttemptUs - nowLocalUs) / 1000L).coerceAtLeast(10L))
                        continue
                    }

                    if (!isSupportedStreamCodec(codec)) {
                        delay(50L)
                        continue
                    }

                    if (!isClockReadyForPlayback()) {
                        if (snapshot.queuedChunks > 0) {
                            jitter.dropWhileLate(nowUs(), 100_000L)
                        }
                        delay(25L)
                        continue
                    }

                    val startupPipelineLatencyUs = output.getSchedulingPipelineLatencyUs()
                    val startupLateToleranceUs =
                        (startupPipelineLatencyUs +
                            estimatedSafetyMarginUs() +
                            timeFilter.getEstimatedNetworkJitterUs())
                            .coerceIn(80_000L, 350_000L)

                    if (forceResyncMode) {
                        jitter.dropWhileLate(nowUs(), startupLateToleranceUs / 2)
                    } else if (snapshot.queuedChunks > 0) {
                        jitter.dropWhileLate(nowUs(), startupLateToleranceUs)
                    }

                    val snap2 = jitter.snapshot()
                    val headServerUs = snap2.headServerUs
                    if (headServerUs == null) {
                        delay(10L)
                        continue
                    }

                    val headHeardUs = serverTimestampHeardUs(headServerUs)
                    val writeAtUs = headHeardUs - startupPipelineLatencyUs
                    val nowForStartUs = nowUs()
                    if (!inDiscontinuityMode && nowForStartUs - headHeardUs > startupLateToleranceUs) {
                        jitter.dropWhileLate(nowForStartUs, startupLateToleranceUs / 2)
                        delay(1L)
                        continue
                    }

                    val startLeadUs =
                        (decodeLatencyUs + estimatedSafetyMarginUs()).coerceIn(20_000L, 120_000L)
                    val restartMinQueued = if (codec == "opus") 3 else 2
                    val canStartNormally =
                        snap2.queuedChunks >= restartMinQueued ||
                            writeAtUs - nowForStartUs <= startLeadUs

                    if (!canStartNormally) {
                        lateRestartLoops++
                        val waitMs = ((writeAtUs - nowForStartUs - startLeadUs) / 1000L)
                            .coerceIn(1L, 10L)
                        delay(waitMs)
                        continue
                    }

                    var sourceBitDepth = bitDepth
                    if (codec == "opus") {
                        val opusConfig = "$sampleRate:$channels"
                        val existingOpus = opusDecoder
                        if (existingOpus != null && opusDecoderConfig == opusConfig) {
                            existingOpus.reset()
                        } else {
                            existingOpus?.release()
                            opusDecoder = null
                            opusDecoder = try {
                                SendspinOpusDecoder(sampleRate, channels)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to create Opus decoder", e)
                                sendClientStateError()
                                triggerForceResync("opus_decoder_failed")
                                delay(100L)
                                continue
                            }
                            opusDecoderConfig = opusConfig
                        }
                        sourceBitDepth = 16
                    } else if (codec == "flac") {
                        val flacConfig = "$sampleRate:$channels:$bitDepth:${pendingFlacCodecHeader ?: ""}"
                        val existingFlac = flacDecoder
                        if (existingFlac != null && flacDecoderConfig == flacConfig) {
                            existingFlac.reset()
                        } else {
                            existingFlac?.release()
                            flacDecoder = null
                            val embeddedHeader = jitter.peekFirst()?.let { chunk ->
                                if (SendspinFlacDecoder.hasFlacStreamMarker(
                                        chunk.data,
                                        chunk.offset,
                                        chunk.length
                                    )
                                ) {
                                    chunk.copyPayload()
                                } else {
                                    null
                                }
                            }
                            flacDecoder = try {
                                SendspinFlacDecoder(
                                    sampleRate,
                                    channels,
                                    bitDepth,
                                    pendingFlacCodecHeader,
                                    embeddedHeader
                                )
                            } catch (e: Throwable) {
                                Log.e(TAG, "Failed to create FLAC decoder; falling back to PCM", e)
                                sendClientStateError()
                                sendStreamRequestPreferredSmoothFormat("flac_decoder_failed")
                                triggerForceResync("flac_decoder_failed")
                                delay(100L)
                                continue
                            }
                            flacDecoderConfig = flacConfig
                        }
                        sourceBitDepth = flacDecoder!!.outputPcmBitDepth
                        syncedFlacPcmEncoding = flacDecoder!!.outputPcmEncoding
                        output.setSourceFormat(
                            SendspinPcmProcessor.SourceFormat(
                                bitDepth = flacDecoder!!.outputPcmBitDepth,
                                pcmEncoding = flacDecoder!!.outputPcmEncoding
                            )
                        )
                    }

                    if (codec != "flac") {
                        output.setSourceBitDepth(sourceBitDepth)
                    }
                    refreshPlaybackGainBeforeStart()
                    output.start(sampleRate, channels, 16)
                    output.setVolume(1f)
                    syncController.onLocalPlaybackStarted(System.currentTimeMillis())
                    lateRestartLoops = 0
                    if (!output.isStarted()) {
                        outputStartedAtUs = 0L
                        speedController.reset()
                        triggerForceResync("start_failed")
                        nextStartAttemptUs = nowUs() + (restartBackoffMs * 1000L)
                        restartBackoffMs = (restartBackoffMs * 2).coerceAtMost(3_000L)
                        continue
                    }

                    outputStartedAtUs = nowUs()
                    audioFocus.acquire()
                    val armHard = startupHardCorrectionArmed
                    startupHardCorrectionArmed = false
                    speedController.notifyOutputStarted(
                        outputStartedAtUs,
                        enableHardCorrection = armHard,
                    )
                    sendClientStateSynchronized()
                    restartBackoffMs = 200L
                    nextStartAttemptUs = 0L
                }

                if (!timeFilter.isReady) {
                    delay(10L)
                    continue
                }

                val pipelineLatencyUs = output.getSchedulingPipelineLatencyUs()
                val safetyMarginUs = estimatedSafetyMarginUs()
                // One shared lateness budget for poll + play-through. A tighter
                // secondary threshold used to discard chunks pollPlayable had just
                // accepted → stutter on millisecond lag, then drift while recovering.
                val lateDropUs =
                    (pipelineLatencyUs + safetyMarginUs + timeFilter.getEstimatedNetworkJitterUs())
                        .coerceIn(LATE_DROP_FLOOR_US, LATE_DROP_CEILING_US)
                // Play-through ceiling in the heard domain: same magnitude as lateDropUs
                // so we never drop tighter than the jitter poll already applied.
                val playThroughLateUs = lateDropUs

                val chunk = jitter.pollPlayable(nowUs(), lateDropUs)
                if (chunk == null) {
                    underrunCount++
                    if (output.isStarted()) {
                        if (!voiceOverlayDucked) audioFocus.ensureHeld()
                        output.ensurePlaying()
                    }
                    delay(1L)
                    continue
                }

                val pcmData = when (codec) {
                    "opus" -> {
                        if (looksLikeFlac(chunk.data, chunk.offset, chunk.length)) {
                            Log.w(TAG, "FLAC payload while codec=opus; correcting to flac")
                            codec = "flac"
                            output.stop()
                            opusDecoder?.release()
                            opusDecoder = null
                            continue
                        }
                        decodeOpusChunk(chunk)
                    }
                    "flac" -> {
                        val decoded = decodeFlacChunk(chunk)
                        if (decoded.isEmpty() && chunk.length > 0) {
                            flacEmptyDecodeStreak++
                            if (flacEmptyDecodeStreak >= FLAC_EMPTY_DECODE_FALLBACK_THRESHOLD) {
                                Log.w(
                                    TAG,
                                    "FLAC decode produced no PCM for $flacEmptyDecodeStreak frames; " +
                                        "requesting PCM fallback"
                                )
                                sendStreamRequestPreferredSmoothFormat("flac_empty_decode")
                                flacEmptyDecodeStreak = 0
                            }
                        } else if (decoded.isNotEmpty()) {
                            flacEmptyDecodeStreak = 0
                        }
                        decoded
                    }
                    else -> chunk.copyPayload()
                }
                if (pcmData.isEmpty()) continue

                var activeChunk = chunk
                var activePcm = pcmData
                var heardPlayUs = chunkHeardPlayUs(activeChunk)
                var earlyUs = heardPlayUs - nowUs()
                lastTimelineEarlyUs = earlyUs
                lastTimelinePipelineLatencyUs = pipelineLatencyUs
                // Mild late (≥~8ms) already deserves a rate nudge; do not wait for 25ms.
                // Still rate-only — never a reason to discard frames.
                if (earlyUs - pipelineLatencyUs < -8_000L && userSyncOffsetMs == 0L) {
                    speedController.adjustSpeed(
                        nowUs = nowUs(),
                        driftPpm = timeFilter.estimatedDriftPpm(),
                        timelineEarlyUs = earlyUs,
                        pipelineLatencyUs = pipelineLatencyUs
                    )
                }

                // Only shed when past the same budget pollPlayable already uses.
                // Mild heard-domain lateness: keep the chunk and write ASAP (anti-drop).
                if (earlyUs < -playThroughLateUs) {
                    jitter.dropWhileLate(nowUs(), lateDropUs)
                }

                applyPendingManualCatchup(earlyUs)
                // Auto-offset is sampled after waitUntilScheduled (below): dequeue-time
                // earlyUs is structurally +1 chunk ahead of the write waterline and was
                // teaching a false ~−35ms playout offset on FLAC@44.1k.
                monitorTimelineAlignment(earlyUs, snapshot.bufferAheadMs)

                if (forceResyncMode) {
                    if (earlyUs < -playThroughLateUs) {
                        jitter.dropWhileLate(nowUs(), lateDropUs)
                    }
                    if (earlyUs in -playThroughLateUs..120_000L || nowUs() >= forceResyncUntilUs) {
                        forceResyncMode = false
                        forceResyncUntilUs = 0L
                    }
                }

                val nowMs = System.currentTimeMillis()
                val timeSinceLastCutMs = nowMs - lastAudioCutMs
                val latenessMs = serverLatenessMs(earlyUs)

                // Emergency tier (100–220ms, 0ms auto mode ONLY — manual offset
                // keeps its fixed-delay semantics untouched): persistent lag arms
                // the bounded shed once; applyPendingManualCatchup drops into
                // the rate loop's takeover window and the speed controller glides
                // back to 1.0x. Strikes confirm it is real (not one late chunk);
                // the cooldown prevents the old endless drop-recover-drop cycle.
                if (userSyncOffsetMs == 0L &&
                    !forceResyncMode && !inDiscontinuityMode &&
                    latenessMs > EMERGENCY_CATCHUP_LATE_MS &&
                    latenessMs <= CATCHUP_DROP_LATE_MS
                ) {
                    emergencyLateStrikes++
                    if (emergencyLateStrikes >= EMERGENCY_CATCHUP_STRIKES &&
                        pendingManualCatchupUs == 0L &&
                        nowMs - lastEmergencyCatchupMs > EMERGENCY_CATCHUP_COOLDOWN_MS
                    ) {
                        emergencyLateStrikes = 0
                        lastEmergencyCatchupMs = nowMs
                        pendingManualCatchupUs = latenessMs * 1000L
                        Log.w(TAG, "Emergency catch-up armed: ${latenessMs}ms late")
                    }
                } else {
                    emergencyLateStrikes = 0
                }

                // Drop-catch only for large lag. Millisecond drift must not enter here.
                if (latenessMs > CATCHUP_DROP_LATE_MS &&
                    latenessMs <= HARD_CUT_LATE_MS
                ) {
                    runCatchUpDrop(lateDropUs)
                    continue
                }

                if (!inDiscontinuityMode &&
                    !forceResyncMode &&
                    latenessMs > HARD_CUT_LATE_MS &&
                    timeSinceLastCutMs > 2000L
                ) {
                    output.pause()
                    outputStartedAtUs = 0L
                    speedController.reset()
                    triggerForceResync("audio_out_of_sync_late_drop")
                    lastAudioCutMs = nowMs
                    nextStartAttemptUs = nowUs() + 100_000L
                    continue
                }

                val aligned = alignChunkToTimeline(
                    activeChunk,
                    activePcm,
                    playThroughLateUs,
                    lateDropUs
                )
                activeChunk = aligned.first
                activePcm = aligned.second
                if (activePcm.isEmpty()) continue
                heardPlayUs = chunkHeardPlayUs(activeChunk)
                earlyUs = heardPlayUs - nowUs()

                // Any chunk silently dropped since the last write (pollPlayable / dropWhileLate)
                // is a timeline splice; crossfade the next write to avoid an audible click.
                val lateDropsNow = jitter.lateDropCount()
                if (lateDropsNow != lastSpliceLateDrops) {
                    lastSpliceLateDrops = lateDropsNow
                    output.requestSpliceFade()
                }

                waitUntilScheduled(heardPlayUs)

                // Sample at the write waterline: after wait, earlyUs ≈ pipelineLatency
                // when on-time (residual ~0 → offset stays near 0). Negative residual
                // only appears on real write backpressure / lateness.
                val writePipelineLatencyUs = output.getSchedulingPipelineLatencyUs()
                val writeEarlyUs = heardPlayUs - nowUs()
                updateAutoOffsetModel(
                    writeEarlyUs,
                    snapshot.bufferAheadMs,
                    writePipelineLatencyUs,
                )

                // Focus can be stolen mid-track (VOICE_CALL / another GAIN holder).
                // Without re-acquire, AudioTrack keeps "playing" but the device stays
                // silent until process kill — match Portal / long-session reports.
                // During voice overlay, never re-steal GAIN from URL TTS.
                if (!voiceOverlayDucked && !audioFocus.ensureHeld()) {
                    Log.w(TAG, "audio focus not held; retrying ensurePlaying")
                }
                var wrotePcm = false
                if (output.ensurePlaying()) {
                    // Frame anchor BEFORE the write: totalFramesWritten is then the
                    // stream frame index of this chunk's first sample — the
                    // content↔server-ts mapping behind the peer playback beacon.
                    val (anchorFrames, anchorGeneration) = output.writtenFramesAndGeneration()
                    wrotePcm = output.writePcm(activePcm)
                    if (wrotePcm) {
                        audibleFrameAnchor = AudibleFrameAnchor(
                            serverTsUs = activeChunk.serverTimestampUs,
                            frames = anchorFrames,
                            generation = anchorGeneration,
                        )
                    }
                }
                if (wrotePcm) {
                    chunksPlayed++
                    notePcmWritten()
                    noteAudibleChunkWritten(activeChunk.serverTimestampUs)
                    maybeAdvertisePostPresentationLead()
                } else if (output.isStarted()) {
                    // Recoverable write hiccup while the track keeps playing: re-arm the
                    // speed controller instead of killing it (a dead controller leaves the
                    // last PlaybackParams speed applied forever, e.g. pinned at 0.998x).
                    Log.w(TAG, "pcm write failed (recoverable); re-arming speed controller")
                    if (!voiceOverlayDucked) audioFocus.ensureHeld()
                    output.setPlaybackSpeed(1.0f)
                    outputStartedAtUs = nowUs()
                    // Mid-play recovery: never arm startup hard-correction (would pause + snap UI).
                    speedController.notifyOutputStarted(
                        outputStartedAtUs,
                        enableHardCorrection = false,
                    )
                } else {
                    Log.w(TAG, "pcm write failed; output stopped — will recreate AudioTrack")
                    if (!voiceOverlayDucked) audioFocus.ensureHeld()
                    outputStartedAtUs = 0L
                    speedController.reset()
                    nextStartAttemptUs = nowUs() + 80_000L
                }
            }
        }
    }

    private fun handleText(text: String) {
        val pendingNoise = noiseHandshake
        if (pendingNoise != null && noiseTransport == null) {
            when (val ev = pendingNoise.onText(text)) {
                is SendspinNoiseHandshake.Event.Waiting -> return
                is SendspinNoiseHandshake.Event.Established -> {
                    noiseHandshake = null
                    noiseTransport = ev.transport
                    awaitingEncryptedServerHello = true
                    SendspinLegacyGate.clear()
                    Log.i(
                        TAG,
                        if (ev.sentinelFallback) {
                            "Sendspin Noise handshake complete (Sentinel fallback)"
                        } else {
                            "Sendspin Noise handshake complete (Sentinel PSK)"
                        },
                    )
                    return
                }
                is SendspinNoiseHandshake.Event.LegacyHello -> {
                    noiseHandshake = null
                    SendspinLegacyGate.armAfterFailedInit()
                    Log.i(TAG, "Peer sent server/hello after client/init; using plaintext path")
                    sendClientHello()
                    handleText(ev.rawText)
                    return
                }
                is SendspinNoiseHandshake.Event.Failed -> {
                    noiseHandshake = null
                    SendspinLegacyGate.armAfterRejectedNoiseFrame()
                    Log.w(TAG, "Sendspin Noise handshake failed: ${ev.reason}")
                    transportClose?.invoke(1000, "noise_handshake_failed")
                    return
                }
            }
        }
        try {
            val obj = JSONObject(text)
            val type = obj.optString("type", "")
            val payload = obj.optJSONObject("payload") ?: JSONObject()

            when (type) {
                "server/hello" -> applyServerHello(payload)

                "server/activate" -> applyServerActivate(payload)

                /*
                 * A server dropping its own pairing record. We hold no pairing
                 * records at all (no Noise/PSK layer here), so this connection is
                 * `trust_level: 'none'` and the spec has us ignore the message and
                 * continue unchanged — there is nothing to remove, and closing
                 * would drop playback over a record we never had.
                 *
                 * Logged rather than left to the unknown-type path so this reads as
                 * a decision instead of an oversight if pairing lands later.
                 */
                "server/unpair" -> Log.i(
                    TAG,
                    "server/unpair ignored: no pairing records held (trust_level none)"
                )

                "server/time" -> {
                    val clientTx = payload.getLong("client_transmitted")
                    val serverReceived = payload.getLong("server_received")
                    val serverTransmitted = payload.getLong("server_transmitted")
                    val clientRx = nowUs()
                    timeFilter.onServerTime(clientTx, clientRx, serverReceived, serverTransmitted)
                }

                "stream/start" -> {
                    if (groupIdentity.blocked) {
                        // Hold, never drop: if this join proves user-made a
                        // moment later (MA event lag), this is the only format
                        // header the server will ever send us for this stream.
                        pendingBlockedStreamStart = payload
                        foreignStreamActivity = true
                        if (foreignConfirmed) scheduleLeaveForeignGroup()
                        Log.w(
                            TAG,
                            "stream/start held: foreign group ${groupIdentity.currentGroupId}",
                        )
                        return
                    }
                    handleStreamStart(payload)
                }

                "stream/clear" -> {
                    if (groupIdentity.blocked) {
                        // A pausing room still proves the id is somebody's
                        // live session — it must not pass for a quiet solo.
                        foreignStreamActivity = true
                        Log.w(
                            TAG,
                            "stream/clear ignored: foreign group ${groupIdentity.currentGroupId}",
                        )
                        return
                    }
                    enterDiscontinuityMode()
                    jitter.clear()
                    lastChunkServerTimestampUs = Long.MIN_VALUE
                    lastTimelineEarlyUs = Long.MIN_VALUE
                    lastTimelinePipelineLatencyUs = 0L
                    opusDecoder?.reset()
                    flacDecoder?.reset()
                    output.pause()
                    outputStartedAtUs = 0L
                    speedController.reset()
                    startupHardCorrectionArmed = false
                    // Timeline splice (seek / jump): drop absolute ms — keeping the
                    // old playhead permanently offsets lyrics when metadata.progress
                    // never arrives. Next calibrate()/reseat() supplies absolute ms.
                    breakAudibleTimeline(/* keepProgress = */ false)
                    audibleNeedsAbsoluteSeat = true
                    onStreamClear?.invoke()
                }

                "stream/end" -> {
                    if (groupIdentity.blocked) {
                        // An ending session still proves the id is somebody's
                        // room — and their teardown must not hide our overlay.
                        foreignStreamActivity = true
                        Log.w(
                            TAG,
                            "stream/end ignored: foreign group ${groupIdentity.currentGroupId}",
                        )
                        return
                    }
                    streamEnded = true
                    streamFormatReady = false
                    artworkStreamActive = false
                    codec = ""
                    playAtServerUs = Long.MIN_VALUE
                    pendingFlacCodecHeader = null
                    _currentCodec.value = null
                    patchUi { it.copy(status = "stream/end", streamDesc = "") }
                    // Stop mapping; freeze last audible emission for the UI.
                    breakAudibleTimeline(/* keepProgress = */ true)
                    enterDiscontinuityMode()
                    output.pause()
                    outputStartedAtUs = 0L
                    speedController.reset()
                    jitter.clear()
                    lastChunkServerTimestampUs = Long.MIN_VALUE
                    opusDecoder?.release()
                    opusDecoder = null
                    flacDecoder?.release()
                    flacDecoder = null
                    // Spec: stream/end when playback is over and the queue is empty.
                    onStreamEnd?.invoke()
                }

                "group/update" -> {
                    val playbackState = payload.optString("playback_state", "")
                    val groupName = payload.optString("group_name", "")
                    val groupId = payload.optString("group_id", "")
                    lastGroupUpdateName = groupName
                    noteGroupIdentity(groupId, playbackState)
                    // Only an unblocked identity may steer the LAN stream key —
                    // a blocked repeat (UNCHANGED) must not sneak the foreign
                    // group's key into our beacons/PROG gates either.
                    if (!groupIdentity.blocked) {
                        updateFallbackStreamKey(
                            groupId = groupId,
                            groupName = groupName,
                        )
                    }
                    // While blocked the room's name/state is not ours to paint.
                    if (!groupIdentity.blocked) {
                        patchUi { it.copy(playbackState = playbackState, groupName = groupName) }
                    }
                    if (playbackState.isNotBlank() && !groupIdentity.blocked) {
                        onPlaybackState?.invoke(playbackState)
                    }

                    // Sendspin spec multi-server tie-breaker: clients must persistently
                    // store the server_id of the server that most recently had
                    // playback_state == "playing" (the "last played server"). We notify
                    // the manager exactly once per playing transition so it can persist.
                    if (!groupIdentity.blocked &&
                        playbackState == "playing" &&
                        serverId.isNotEmpty() &&
                        serverId != lastReportedPlayingServerId
                    ) {
                        lastReportedPlayingServerId = serverId
                        onPlaybackPlaying?.invoke(serverId)
                    } else if (playbackState == "stopped") {
                        lastReportedPlayingServerId = ""
                    }

                    if (!groupIdentity.blocked) {
                        payload.optJSONObject("metadata")?.let { metadata ->
                            applyMetadataToUi(metadata)
                            onMetadata?.invoke(parseMetadata(metadata))
                        }
                    }
                    // Spec: whole role object null clears that role's state.
                    if (payload.has("metadata") && payload.isNull("metadata")) {
                        clearMetadataRoleState()
                    }
                }

                "server/state" -> {
                    if (groupIdentity.blocked) {
                        // Everything in here describes the foreign session —
                        // metadata clears, controller state, playback tokens.
                        // Our frozen state must not track any of it.
                        return
                    }
                    // Metadata first, then controller — so canonical controller repeat/shuffle
                    // wins over legacy metadata dual-emit in the same server/state frame.
                    if (payload.has("metadata") && payload.isNull("metadata")) {
                        // Spec: metadata: null clears all metadata (e.g. empty queue).
                        clearMetadataRoleState()
                    } else {
                        val metadata = payload.optJSONObject("metadata")
                        if (metadata != null) {
                            applyMetadataToUi(metadata)
                            val parsed = parseMetadata(metadata)
                            onMetadata?.invoke(parsed)

                            val progress = metadata.optJSONObject("progress")
                            if (progress != null) {
                                val positionMs = progress.optLong("track_progress", 0L)
                                val durationMs = progress.optLong("track_duration", 0L)
                                val playbackSpeed = progress.optInt("playback_speed", 0)
                                val playState = when {
                                    playbackSpeed > 0 -> SendspinPlaybackSyncController.PlayState.PLAYING
                                    playbackSpeed == 0 -> SendspinPlaybackSyncController.PlayState.PAUSED
                                    else -> SendspinPlaybackSyncController.PlayState.STOPPED
                                }
                                syncController.onServerPositionUpdate(positionMs, durationMs, playState)

                                // Drive vinyl / UI playback state from metadata.progress
                                // (spec primary path for progress; group/update is separate).
                                if (progress.has("playback_speed")) {
                                    val playbackState = if (playbackSpeed > 0) "playing" else "paused"
                                    patchUi { it.copy(playbackState = playbackState) }
                                    onPlaybackState?.invoke(playbackState)
                                }
                            } else if (metadata.has("progress") && metadata.isNull("progress")) {
                                // Spec: leaf null clears progress fields only.
                                // Do NOT map to playback "stopped" — track jumps often
                                // null progress while the group is still playing; that
                                // must not flip the vinyl/FAB transport glyph.
                                patchUi {
                                    it.copy(
                                        trackProgress = null,
                                        trackDuration = null,
                                        playbackSpeed = null,
                                    )
                                }
                            }
                        }
                    }

                    if (payload.has("controller") && payload.isNull("controller")) {
                        serverSupportedControllerCommands = emptySet()
                        serverSeekMaxMs = null
                        patchUi {
                            it.copy(
                                repeatMode = null,
                                shuffleEnabled = null,
                                supportedCommands = emptySet(),
                            )
                        }
                        onControllerState?.invoke(
                            SendspinControllerStateDelta(
                                repeatModePresent = true,
                                shufflePresent = true,
                                groupVolumePresent = true,
                                groupMutedPresent = true,
                                supportedCommandsPresent = true,
                                seekMaxMsPresent = true,
                            )
                        )
                    } else {
                        payload.optJSONObject("controller")?.let { controller ->
                            applyControllerState(controller)
                        }
                    }

                    // server/state has no spec top-level playback `state` field — only
                    // client/state uses operational values like synchronized/error.
                    // Ignore non-playback tokens so reconnect handoff doesn't hide vinyl.
                    payload.optString("state").takeIf { it.isNotBlank() }?.let { raw ->
                        val normalized = raw.lowercase()
                        if (normalized in PLAYBACK_STATE_TOKENS) {
                            patchUi { it.copy(playbackState = normalized) }
                            onPlaybackState?.invoke(normalized)
                        }
                    }
                }

                "server/command" -> {
                    if (groupIdentity.blocked) {
                        // The original leak class: an unpaired box obeying the
                        // foreign room's volume/mute fan-out.
                        foreignStreamActivity = true
                        Log.w(
                            TAG,
                            "server/command ignored: foreign group ${groupIdentity.currentGroupId}",
                        )
                        return
                    }
                    val player = payload.optJSONObject("player")
                    if (player != null) {
                        when (player.optString("command", "")) {
                            "volume" -> {
                                val volume = player.optInt("volume", 100)
                                currentVolume = volume
                                onVolumeCommand?.invoke(volume)
                                patchUi {
                                    it.copy(playerVolume = volume, playerVolumeFromServer = true)
                                }
                                sendClientStatePlayer(volume = volume, muted = null)
                            }
                            "mute" -> {
                                val muted = player.optBoolean("mute", false)
                                currentMuted = muted
                                onMuteCommand?.invoke(muted)
                                patchUi {
                                    it.copy(playerMuted = muted, playerMutedFromServer = true)
                                }
                                sendClientStatePlayer(volume = null, muted = muted)
                            }
                            "set_static_delay" -> {
                                val delayMs = player.optLong("static_delay_ms", 0L).coerceIn(0L, 5_000L)
                                applyProtocolStaticDelayMs(delayMs.toInt())
                                Log.i(TAG, "Server set static delay: ${delayMs}ms")
                                sendClientStatePlayer(volume = null, muted = null)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Bad JSON: ${t.message}", t)
        }
    }

    private fun handleBinary(data: ByteArray) {
        val encrypted = noiseTransport
        if (encrypted != null) {
            val plain = try {
                encrypted.decryptFrame(data) ?: return
            } catch (e: SendspinNoiseFailure) {
                Log.w(TAG, "Sendspin Noise decrypt failed: ${e.message}")
                transportClose?.invoke(1000, "noise_transport_mac")
                return
            }
            if (plain.isEmpty()) return
            if (plain[0].toInt() and 0xFF == SendspinNoiseTransport.MSG_JSON) {
                handleText(String(plain, 1, plain.size - 1, Charsets.UTF_8))
                return
            }
            dispatchBinary(plain)
            return
        }
        dispatchBinary(data)
    }

    private fun dispatchBinary(data: ByteArray) {
        if (!handshakeComplete || data.isEmpty()) return
        val type = data[0].toInt() and 0xFF
        when (type) {
            // Player PCM/Opus/FLAC chunk
            4 -> {
                if (groupIdentity.blocked) {
                    // Live audio at a blocked id is the hijack proof the
                    // probation / self-heal timers key off.
                    foreignStreamActivity = true
                    return
                }
                if (!streamFormatReady) return
                if (!isSupportedStreamCodec(codec)) return
                if (data.size < 9) return

                val tsServerUs = readInt64BE(data, 1)
                if (lastChunkServerTimestampUs != Long.MIN_VALUE) {
                    val timestampJumpUs = tsServerUs - lastChunkServerTimestampUs
                    if (kotlin.math.abs(timestampJumpUs) > discontinuityThresholdUs) {
                        Log.w(TAG, "Stream discontinuity detected: jump=${timestampJumpUs / 1000}ms")
                        enterDiscontinuityMode()
                        // A short forward hole is a stalled read (this panel's runtime
                        // pauses to compile). Wiping the queue disables AudioTrack on
                        // the following underrun, so the song never stays audible.
                        // Drop only on a seek (backward) or a real splice.
                        if (timestampJumpUs < 0 || timestampJumpUs > 3_000_000L) {
                            jitter.clear()
                        }
                    }
                }
                lastChunkServerTimestampUs = tsServerUs
                chunksReceived++
                jitter.offer(tsServerUs, data, 9, data.size - 9)
            }
            // Artwork channel 0 only (we advertise a single album JPEG channel)
            8 -> handleArtworkBinary(data)
            else -> Unit
        }
    }

    /**
     * Spec binary artwork: `[type:1][timestamp_be:8][image…]`.
     * Empty image payload clears the channel. Display immediately (no clock wait) to keep this light.
     */
    private fun handleArtworkBinary(data: ByteArray) {
        if (groupIdentity.blocked) {
            // A cover push is session traffic too — quiet-solo adoption must
            // not fire while somebody's room is still feeding us anything.
            foreignStreamActivity = true
            return
        }
        if (data.size < 9) return
        artworkStreamActive = true
        if (data.size == 9) {
            artworkDecodeJob?.cancel()
            onArtwork?.invoke(null)
            return
        }
        val imageBytes = data.copyOfRange(9, data.size)
        artworkDecodeJob?.cancel()
        artworkDecodeJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                decodeArtworkBytes(imageBytes)
            }
            if (!isActive) {
                bitmap?.takeIf { !it.isRecycled }?.recycle()
                return@launch
            }
            onArtwork?.invoke(bitmap)
        }
    }

    private fun decodeArtworkBytes(bytes: ByteArray): Bitmap? {
        return try {
            val maxEdge = if (isLowMemoryDevice) 256 else 384
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w / sample > maxEdge || h / sample > maxEdge) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (t: Throwable) {
            Log.w(TAG, "Artwork decode failed: ${t.message}")
            null
        }
    }

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && isConnected.get()) {
                delay(5_000L)
                val now = System.currentTimeMillis()
                val playbackDead = (now - lastPlaybackHeartbeatMs) > 10_000L
                val statsDead = (now - lastStatsHeartbeatMs) > 10_000L
                if (playbackDead || statsDead) {
                    Log.e(TAG, "Watchdog: playbackDead=$playbackDead statsDead=$statsDead")
                    sendClientStateError()
                    triggerForceResync(
                        when {
                            playbackDead && statsDead -> "watchdog_playback_stats_dead"
                            playbackDead -> "watchdog_playback_dead"
                            else -> "watchdog_stats_dead"
                        }
                    )
                }
            }
        }
    }

    private fun applyControllerState(controller: JSONObject) {
        val delta = parseControllerStateDelta(controller)
        if (delta.supportedCommandsPresent) {
            serverSupportedControllerCommands = delta.supportedCommands
        }
        if (delta.seekMaxMsPresent) {
            serverSeekMaxMs = delta.seekMaxMs?.takeIf { it > 0L }
        }
        patchUi { current ->
            var next = current
            if (delta.groupVolumePresent) {
                next = next.copy(groupVolume = delta.groupVolume ?: 100)
            }
            if (delta.groupMutedPresent) {
                next = next.copy(groupMuted = delta.groupMuted ?: false)
            }
            if (delta.supportedCommandsPresent) {
                next = next.copy(supportedCommands = delta.supportedCommands)
            }
            if (delta.repeatModePresent) {
                next = next.copy(repeatMode = delta.repeatMode)
            }
            if (delta.shufflePresent) {
                next = next.copy(shuffleEnabled = delta.shuffleEnabled)
            }
            next
        }
        onControllerState?.invoke(delta)
    }

    private fun parseControllerStateDelta(controller: JSONObject): SendspinControllerStateDelta {
        val supportedCommands = controller.optJSONArray("supported_commands")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } ?: emptySet()
        return SendspinControllerStateDelta(
            repeatMode = if (controller.has("repeat") && !controller.isNull("repeat")) {
                normalizeRepeatMode(controller.optString("repeat"))
            } else {
                null
            },
            repeatModePresent = controller.has("repeat"),
            shuffleEnabled = if (controller.has("shuffle") && !controller.isNull("shuffle")) {
                controller.optBoolean("shuffle")
            } else {
                null
            },
            shufflePresent = controller.has("shuffle"),
            groupVolume = if (controller.has("volume")) controller.optInt("volume", 100) else null,
            groupVolumePresent = controller.has("volume"),
            groupMuted = if (controller.has("muted")) controller.optBoolean("muted", false) else null,
            groupMutedPresent = controller.has("muted"),
            supportedCommands = supportedCommands,
            supportedCommandsPresent = controller.has("supported_commands"),
            seekMaxMs = if (controller.has("seek_max_ms") && !controller.isNull("seek_max_ms")) {
                controller.optLong("seek_max_ms")
            } else {
                null
            },
            seekMaxMsPresent = controller.has("seek_max_ms"),
        )
    }

    private fun normalizeRepeatMode(raw: String?): String =
        when (raw?.lowercase()) {
            "one", "repeat_one" -> "one"
            "all", "repeat_all" -> "all"
            else -> "off"
        }

    private fun applyMetadataToUi(metadata: JSONObject) {
        patchUi { current ->
            var next = current
            if (metadata.has("timestamp")) {
                next = next.copy(metadataTimestamp = metadata.getLong("timestamp"))
            }
            if (metadata.has("title")) {
                next = next.copy(trackTitle = clearableStringOrNull(metadata, "title"))
            }
            if (metadata.has("artist")) {
                next = next.copy(trackArtist = clearableStringOrNull(metadata, "artist"))
            }
            if (metadata.has("album")) {
                next = next.copy(albumTitle = clearableStringOrNull(metadata, "album"))
            }
            if (metadata.has("album_artist")) {
                next = next.copy(albumArtist = clearableStringOrNull(metadata, "album_artist"))
            }
            if (metadata.has("year")) {
                next = next.copy(trackYear = if (metadata.isNull("year")) null else metadata.optInt("year"))
            }
            if (metadata.has("track")) {
                next = next.copy(trackNumber = if (metadata.isNull("track")) null else metadata.optInt("track"))
            }
            if (metadata.has("artwork_url")) {
                next = next.copy(artworkUrl = clearableStringOrNull(metadata, "artwork_url"))
            }
            if (metadata.has("progress")) {
                val progress = metadata.optJSONObject("progress")
                next = if (progress != null) {
                    next.copy(
                        trackProgress = progress.optLong("track_progress"),
                        trackDuration = progress.optLong("track_duration"),
                        playbackSpeed = progress.optInt("playback_speed")
                    )
                } else {
                    next.copy(trackProgress = null, trackDuration = null, playbackSpeed = null)
                }
            }
            if (metadata.has("repeat")) {
                next = next.copy(
                    repeatMode = clearableStringOrNull(metadata, "repeat")?.let { normalizeRepeatMode(it) }
                )
            }
            if (metadata.has("shuffle")) {
                next = if (metadata.isNull("shuffle")) {
                    next.copy(shuffleEnabled = null)
                } else {
                    next.copy(shuffleEnabled = metadata.optBoolean("shuffle"))
                }
            }
            next
        }
    }

    /**
     * Spec [server/state]: whole `metadata: null` clears that role.
     * Notify manager via [onMetadataRoleCleared] only — do **not** synthesize
     * empty-string leaf metadata (upstream pause often sends title:null and that
     * must not look like a queue clear).
     */
    private fun clearMetadataRoleState() {
        patchUi {
            it.copy(
                trackTitle = null,
                trackArtist = null,
                albumTitle = null,
                albumArtist = null,
                trackYear = null,
                trackNumber = null,
                artworkUrl = null,
                trackProgress = null,
                trackDuration = null,
                playbackSpeed = null,
                metadataTimestamp = null,
                repeatMode = null,
                shuffleEnabled = null,
                playbackState = "stopped",
            )
        }
        onMetadataRoleCleared?.invoke()
    }

    /**
     * Spec leaf null clears state. Absent key → null (no update for sticky merge).
     * Present null/blank → "" so managers can distinguish clear from omit.
     */
    private fun optClearableString(metadata: JSONObject, key: String): String? {
        if (!metadata.has(key)) return null
        if (metadata.isNull(key)) return ""
        return metadata.optString(key)
    }

    /** UI helper: present+null/blank → null display value. */
    private fun clearableStringOrNull(metadata: JSONObject, key: String): String? {
        if (!metadata.has(key) || metadata.isNull(key)) return null
        return metadata.optString(key).takeIf { it.isNotBlank() }
    }

    private fun parseMetadata(metadata: JSONObject): SendspinMetadata {
        val progress = metadata.optJSONObject("progress")
        return SendspinMetadata(
            // Absent → null (sticky). JSON null / "" → "" (explicit clear).
            title = optClearableString(metadata, "title"),
            artist = optClearableString(metadata, "artist"),
            album = optClearableString(metadata, "album"),
            artworkUrl = optClearableString(metadata, "artwork_url"),
            trackProgressMs = progress
                ?.takeIf { it.has("track_progress") }
                ?.optLong("track_progress"),
            trackDurationMs = progress
                ?.takeIf { it.has("track_duration") }
                ?.optLong("track_duration")
                ?.takeIf { it > 0L },
            metadataTimestampUs = metadata.optLong("timestamp").takeIf { metadata.has("timestamp") },
            playbackSpeed = progress?.optInt("playback_speed")?.takeIf { progress.has("playback_speed") },
            isPlaying = when {
                // Spec: progress:null only clears progress fields — it is NOT a
                // playback-state signal (real pause always carries playback_speed=0).
                // Track jumps null progress mid-seam while the group keeps playing.
                progress?.has("playback_speed") == true -> progress.optInt("playback_speed", 0) > 0
                metadata.has("is_playing") -> metadata.optBoolean("is_playing")
                else -> null
            },
            repeatMode = optClearableString(metadata, "repeat")
                ?.takeIf { it.isNotBlank() }
                ?.let { normalizeRepeatMode(it) },
            shuffleEnabled = if (metadata.has("shuffle") && !metadata.isNull("shuffle")) {
                metadata.optBoolean("shuffle")
            } else {
                null
            }
        )
    }

    private fun resolveControllerCommand(command: String): String {
        val supported = serverSupportedControllerCommands
        if (supported.isEmpty() || command in supported) return command
        controllerCommandAliases[command]?.firstOrNull { it in supported }?.let { return it }
        controllerCommandAliases.entries.firstOrNull { command in it.value && it.key in supported }?.let {
            return it.key
        }
        Log.w(TAG, "Controller role active=$controllerRoleActive unsupported command=$command supported=$supported")
        return command
    }

    private fun effectivePlayoutOffsetUs(): Long {
        return if (userSyncOffsetMs != 0L) {
            userSyncOffsetMs * 1000L
        } else {
            BASELINE_PLAYOUT_OFFSET_US + autoPlayoutOffsetUs + playoutOffsetAdjustmentUs +
                peerAlignOffsetUs
        }
    }

    private fun serverLatenessMs(earlyUs: Long): Long =
        (-earlyUs).coerceAtLeast(0L) / 1000L

    private fun targetPlayoutOffsetUs(): Long =
        if (userSyncOffsetMs == 0L) {
            BASELINE_PLAYOUT_OFFSET_US + autoPlayoutOffsetUs + playoutOffsetAdjustmentUs +
                peerAlignOffsetUs
        } else {
            userSyncOffsetMs * 1000L
        }

    private fun startupPlayoutOffsetUs(): Long {
        val target = targetPlayoutOffsetUs()
        return target.coerceIn(-250_000L, 250_000L)
    }

    private fun applyPendingManualCatchup(earlyUs: Long) {
        val pendingUs = pendingManualCatchupUs
        if (pendingUs <= 0L) return
        // This tier is 0ms-auto-only (arming has the same gate). If the user
        // flips to a manual offset while armed, effectivePlayoutOffsetUs()
        // becomes the fixed delay (±1s) and the converted floor below would
        // shed against the wrong timeline — disarm instead.
        if (userSyncOffsetMs != 0L) {
            pendingManualCatchupUs = 0L
            return
        }
        // Timeline churn: hold the budget and do not shed against a moving
        // reference — force-resync deliberately keeps a looser 150ms budget,
        // and a 40ms floor here would discard audio that path wants to keep.
        if (forceResyncMode || inDiscontinuityMode) return

        if (earlyUs >= -20_000L) {
            pendingManualCatchupUs = 0L
            return
        }

        // dropWhileLate measures lateness on the raw server clock, while the
        // arming lateness (earlyUs) is heard-domain — static delay and playout
        // offset shift the two apart. Convert the floor so the shed drops
        // exactly the chunks the armed measurement saw as late; with a
        // server-set static_delay the unconverted floor was a silent no-op.
        val keepWithinServerUs = EMERGENCY_CATCHUP_KEEP_WITHIN_US +
            effectivePlayoutOffsetUs() - timeFilter.getEffectiveDelayUs()
        val dropped = jitter.dropWhileLate(nowUs(), keepWithinServerUs)
        if (dropped > 0) {
            // Mask the timeline jump like runCatchUpDrop does.
            output.requestSpliceFade()
            audibleSyncCount++
        }
        // Drain the budget every pass, dropped or not. dropped == 0 means the
        // queued backlog is already inside the floor — the residual belongs to
        // the rate loop. Gating the drain on a hand-off window re-wedged the
        // budget whenever lateness parked past the window while the head sat
        // inside the floor (large chunks / starvation): armed forever, never
        // draining, and blocking re-arm.
        pendingManualCatchupUs = (pendingUs - 20_000L).coerceAtLeast(0L)
    }

    private fun currentAppliedOffsetMs(): Long {
        // For UI display: show actual applied offset
        return if (userSyncOffsetMs != 0L) {
            userSyncOffsetMs
        } else {
            ((autoPlayoutOffsetUs + peerAlignOffsetUs) / 1000.0).roundToLong()
        }
    }

    // ---- LAN peer differential alignment (playback beacons) ----

    private class AudibleNowSample(val serverTsUs: Long, val dacTrusted: Boolean)

    /**
     * Scheduled server timestamp of the sample leaving the audio port right
     * now: DAC interpolation minus the OS-reported path after
     * [AudioTrack.getTimestamp]. Null when the anchor is missing/stale or
     * the output is not in a readable state.
     */
    private fun audibleNowSample(): AudibleNowSample? {
        val anchor = audibleFrameAnchor ?: return null
        val snap = output.getPlaybackPositionSnapshot() ?: return null
        if (snap.generation != anchor.generation || snap.sampleRate <= 0) return null
        val framesSinceAnchor = snap.playedFrames - anchor.frames
        // The DAC trails the anchor by the queued depth (negative) or leads it
        // briefly after a splice; beyond ±30s the anchor is stale.
        if (kotlin.math.abs(framesSinceAnchor) > 30L * snap.sampleRate) return null
        val tsUs = anchor.serverTsUs + framesSinceAnchor * 1_000_000L / snap.sampleRate
        // getTimestamp is the HAL/DAC clock. Official 0 is the port, one
        // independently-measured outputLatency later (sendspin-js).
        val portTsUs = tsUs - output.getPostPresentationLatencyUs()
        return AudibleNowSample(portTsUs, snap.dacTrusted)
    }

    /** Scheduled server timestamp of the sample leaving the DAC right now. */
    fun audibleNowServerTsUs(): Long? = audibleNowSample()?.serverTsUs

    /** Whether [audibleNowServerTsUs] came from a trusted DAC timestamp. */
    fun audibleNowDacTrusted(): Boolean = audibleNowSample()?.dacTrusted == true

    /**
     * Track ms at the **speaker** (Snapcast presentation clock): mapper
     * origin plus DAC-now on the same server timeline. Write-clock
     * [lastAudibleTrackProgressMs] leads this by the jitter/AudioTrack
     * queue (the standing 1–2s vs music). Null while paused, seeking, or
     * before the first mapped write.
     */
    fun presentationTrackProgressMs(): Long? {
        val anchorProgress = audibleAnchorProgressMs ?: return notePresentationProgress(
            subtractPipelineLatency(lastAudibleTrackProgressMs()),
        )
        if (audibleAnchorSpeed <= 0) return anchorProgress.coerceAtLeast(0L)
        val anchorTs = audibleAnchorServerTsUs
        val dac = audibleNowSample()
        if (anchorTs != null && dac != null) {
            val speed = audibleAnchorSpeed.coerceAtLeast(1)
            val deltaMs = (dac.serverTsUs - anchorTs) * speed / 1_000_000L
            var progressMs = (anchorProgress + deltaMs).coerceAtLeast(0L)
            audibleDurationMs?.takeIf { it > 0L }?.let { progressMs = progressMs.coerceAtMost(it) }
            return notePresentationProgress(progressMs)
        }
        // DAC snapshot gone but the mapper origin is still anchored on the
        // server timeline — typical right after a pause/resume tap storm,
        // where each AudioTrack rebuild bumps the position generation and
        // invalidates the frame anchor. The sample sounding *now* is by
        // schedule the one stamped ~serverNow, so origin + server-now is
        // still the presentation clock (within the speed controller's tens
        // of ms). The write-clock fallback below is off by the whole
        // jitter/AudioTrack queue instead: reportedAudioLatencyMs() covers
        // only HAL latency, so freezing on it seated pause 1–2.5s ahead of
        // the music — the exact skew the MA bridge then kept rejecting.
        val lastWrittenTs = lastWrittenChunkServerTsUs
        if (anchorTs != null && lastWrittenTs != Long.MIN_VALUE) {
            serverNowUs()?.let { nowServer ->
                // Nothing can be sounding past the last chunk actually handed
                // to AudioTrack. This also keeps the clock honest while
                // buffering, when a metadata-calibrated origin exists but
                // playout has not caught up to the wall yet.
                val cappedNow = nowServer.coerceAtMost(lastWrittenTs)
                val speed = audibleAnchorSpeed.coerceAtLeast(1)
                val deltaMs = (cappedNow - anchorTs) * speed / 1_000_000L
                var progressMs = (anchorProgress + deltaMs).coerceAtLeast(0L)
                audibleDurationMs?.takeIf { it > 0L }?.let {
                    progressMs = progressMs.coerceAtMost(it)
                }
                return notePresentationProgress(progressMs)
            }
        }
        return notePresentationProgress(subtractPipelineLatency(lastAudibleTrackProgressMs()))
    }

    /**
     * Monotonic floor within one anchored timeline. During resume/seek the DAC
     * snapshot can be transiently unavailable, dropping us to the
     * write-clock-minus-latency fallback, which reads up to ~2s behind the
     * value just painted — a visible backward blip. Legitimate backward moves
     * (seek, recalibrate, track change) reset the floor at the anchor
     * mutation points, so only intra-timeline dips are clamped.
     */
    private fun notePresentationProgress(progressMs: Long?): Long? {
        val value = progressMs ?: return null
        val floor = presentationFloorMs
        val result = if (floor != Long.MIN_VALUE) value.coerceAtLeast(floor) else value
        presentationFloorMs = result
        return result
    }

    private fun resetPresentationFloor() {
        presentationFloorMs = Long.MIN_VALUE
    }

    private fun subtractPipelineLatency(writeProgressMs: Long?): Long? {
        val write = writeProgressMs ?: return null
        return (write - reportedAudioLatencyMs()).coerceAtLeast(0L)
    }

    /**
     * Server-clock "now", independent of playback.
     *
     * [audibleNowServerTsUs] is the *DAC* timeline and is null whenever nothing
     * is sounding — paused, buffering, mid-seek. The shared clock itself keeps
     * running through all of that, and it is the only time base every grouped
     * device agrees on, so anchoring a paused or pre-PCM playhead assertion on
     * it is what lets peers reconstruct the same position without knowing how
     * long the datagram took to arrive.
     *
     * Null until the filter converges: an unconverged offset is worse than
     * admitting we have no shared clock yet.
     */
    fun serverNowUs(): Long? {
        if (!isConnected.get()) return null
        if (!timeFilter.hasConverged()) return null
        return timeFilter.convertClientToServer(System.nanoTime() / 1000L)
    }

    /**
     * Stream key of the current stream, preferring the **group-stable** key
     * (hash of server+group id) over `play_at`.
     *
     * `play_at` rotates on every seek / track change (Sendspin restarts the
     * stream), and the two ends of a pair rotate at slightly different
     * instants — so the very playhead packets carrying a finger seek landed in
     * a key-mismatch window and were dropped on both sides. Group membership
     * is the actual timeline identity and survives every splice; since
     * [SendspinGroupIdentity] it is also user-sanctioned. `play_at` remains
     * only as a pre-first-`group/update` fallback.
     */
    fun currentStreamKey(): Long? =
        fallbackStreamKey.takeIf { it != Long.MIN_VALUE }
            ?: playAtServerUs.takeIf { it != Long.MIN_VALUE }

    private fun handleStreamStart(payload: JSONObject) {
        streamEnded = false
        flacEmptyDecodeStreak = 0
        syncedFlacPcmEncoding = Int.MIN_VALUE
        lastChunkServerTimestampUs = Long.MIN_VALUE
        decodeLatencyUs = 0L
        decodeLatencySamples.clear()
        lastMeasuredAutoLatencyUs = 0L
        lastAutoOffsetUpdateUs = 0L
        autoOffsetInitialized = false
        autoOffsetEarlyErrorEmaUs = 0.0
        autoOffsetServerErrorEmaUs = 0.0
        autoOffsetServerEmaInitialized = false
        autoOffsetBufferErrorEmaMs = 0.0
        lateAlignmentStrikeCount = 0
        aheadAlignmentStrikeCount = 0
        lastAlignmentMonitorUs = 0L
        audioScheduleDebugCount = 0
        playoutOffsetAdjustmentUs = 0L
        pendingManualCatchupUs = 0L
        emergencyLateStrikes = 0
        appliedManualOffsetUs = if (userSyncOffsetMs != 0L) userSyncOffsetMs * 1000L else 0L
        lastManualOffsetRampUs = 0L
        lastTimelineEarlyUs = Long.MIN_VALUE
        lastTimelinePipelineLatencyUs = 0L
        startupHardCorrectionArmed = true
        // Peer alignment: keep the learned offset (device-pair bias),
        // but drop boundary-tainted measurements and hold steering
        // until the new timeline is audibly settled.
        peerSteerHoldUntilMs =
            SystemClock.elapsedRealtime() + PEER_ALIGN_STREAM_START_HOLD_MS
        autoOffsetLearnHoldUntilUs = 0L
        synchronized(peerAlignLock) { peerAlignWindows.clear() }
        val player = payload.optJSONObject("player")
        if (player != null) {
            val nextCodec = normalizeStreamCodec(player.optString("codec", ""))
            if (!isSupportedStreamCodec(nextCodec)) {
                Log.w(TAG, "stream/start ignored: unsupported codec=${player.optString("codec")}")
                return
            }
            if (nextCodec == "opus" &&
                !SendspinFormatCatalog.isOpusManuallySelected(currentPreferredFormat())
            ) {
                Log.w(
                    TAG,
                    "Opus not allowed in automatic; requesting FLAC fallback"
                )
                output.stop()
                outputStartedAtUs = 0L
                jitter.clear()
                opusDecoder?.release()
                opusDecoder = null
                codec = ""
                streamFormatReady = false
                sendStreamRequestPreferredSmoothFormat("server_selected_opus", preferFlac = true)
                return
            }
            streamFormatReady = false
            val prevCodec = codec
            codec = nextCodec
            sampleRate = player.optInt("sample_rate", sampleRate)
            channels = player.optInt("channels", channels)
            bitDepth = player.optInt("bit_depth", bitDepth)
            playAtServerUs = if (player.has("play_at")) {
                player.optLong("play_at", Long.MIN_VALUE)
            } else {
                Long.MIN_VALUE
            }
            player.optString("codec_header", "").takeIf { it.isNotBlank() }?.let {
                pendingFlacCodecHeader = it
            }
            _currentCodec.value = codec
            patchUi {
                it.copy(
                    status = "stream/start",
                    streamDesc = "$codec ${sampleRate / 1000}kHz ${channels}ch ${bitDepth}bit"
                )
            }
            enterDiscontinuityMode()
            val codecChanged = prevCodec.isNotBlank() && prevCodec != nextCodec
            resetStreamPlaybackForTrackChange(codecChanged)
            jitter.clear()
            lastFormatRequestUs = 0L
            underrunCountAtLastRequest = underrunCount
            lateDropsAtLastRequest = 0L
            streamFormatReady = true
        }
        val artwork = payload.optJSONObject("artwork")
        if (artwork != null) {
            artworkStreamActive = true
        }
    }

    private fun noteGroupIdentity(
        groupId: String,
        playbackState: String,
    ): SendspinGroupIdentity.Decision {
        val wasBlocked = groupIdentity.blocked
        // Captured before the transition: leaving a user-made shared group is
        // the unpair signal — the protocol never says it in words, and the
        // stream/clear that follows is swallowed by foreign probation.
        val cameFromShared = !wasBlocked &&
            groupIdentity.soloGroupId != null &&
            groupIdentity.currentGroupId != null &&
            groupIdentity.currentGroupId != groupIdentity.soloGroupId
        // null = no arbiter on this device (MA not connected). Only a live
        // "not grouped" vetoes — a standalone box must stay servable.
        val maGrouped = isMassSyncGrouped?.invoke()
        val decision = groupIdentity.onGroupId(groupId, userSanctioned = maGrouped != false)
        when (decision) {
            SendspinGroupIdentity.Decision.FOREIGN -> {
                Log.w(
                    TAG,
                    "Foreign Sendspin group $groupId (solo=${groupIdentity.soloGroupId}) — probation",
                )
                silenceForeignGroup()
                foreignConfirmed = false
                foreignStreamActivity = playbackState == "playing"
                foreignLeftSharedGroup = cameFromShared
                startForeignProbation()
            }
            SendspinGroupIdentity.Decision.ADOPT_SHARED -> {
                Log.i(TAG, "Joining shared Sendspin group $groupId (user-made MA sync)")
                clearForeignTracking()
                foreignLeftSharedGroup = false
            }
            SendspinGroupIdentity.Decision.ADOPT_NEW_SOLO -> {
                clearForeignTracking()
                sendClientStateSynchronized()
                if (cameFromShared || foreignLeftSharedGroup) {
                    fireGroupDissolved("replacement solo after leave")
                }
                foreignLeftSharedGroup = false
            }
            SendspinGroupIdentity.Decision.SOLO -> {
                clearForeignTracking()
                // Either directly off the shared group, or via a brief FOREIGN
                // verdict on the handout id before landing back on our solo.
                if (cameFromShared || foreignLeftSharedGroup) {
                    fireGroupDissolved("re-homed to original solo")
                }
                foreignLeftSharedGroup = false
            }
            SendspinGroupIdentity.Decision.UNCHANGED -> {
                if (groupIdentity.blocked && playbackState == "playing") {
                    foreignStreamActivity = true
                    // Re-leave only once probation has ruled — never mid-window.
                    if (foreignConfirmed) scheduleLeaveForeignGroup()
                }
            }
        }
        if (wasBlocked != groupIdentity.blocked) {
            onForeignGroupBlocked?.invoke(groupIdentity.blocked)
        }
        return decision
    }

    /** A sanctioned pairing ended — tell the manager to wipe mirrored identity. */
    private fun fireGroupDissolved(reason: String) {
        Log.i(TAG, "Shared Sendspin group dissolved ($reason)")
        onGroupDissolved?.invoke()
    }

    /**
     * MA player state moved (player_updated rides its own socket). A blocked
     * group may now be provably user-made — adopt without waiting out the
     * probation window.
     */
    fun reevaluateForeignGroup() {
        if (!groupIdentity.blocked) return
        scope.launch {
            if (!groupIdentity.blocked) return@launch
            if (isMassSyncGrouped?.invoke() == true) {
                adoptForeignAsShared("ma-players-updated")
            }
        }
    }

    private fun startForeignProbation() {
        foreignProbationJob?.cancel()
        foreignProbationJob = scope.launch {
            delay(FOREIGN_GROUP_PROBATION_MS)
            resolveForeignProbation()
        }
    }

    private fun resolveForeignProbation() {
        if (!groupIdentity.blocked) return
        if (isMassSyncGrouped?.invoke() == true) {
            adoptForeignAsShared("ma-confirmed-late")
            return
        }
        if (foreignStreamActivity) {
            // A live fan-out nobody sanctioned: leave. Self-heal covers a
            // server that never hands us a replacement solo group.
            foreignConfirmed = true
            scheduleLeaveForeignGroup()
        } else {
            // Nothing was ever streamed at this id — it is the server
            // re-homing us (typical unsync handout), not somebody's room.
            adoptCurrentAsSoloAfterQuiet("quiet-probation")
        }
    }

    /** User-made pairing confirmed for the blocked id — join it for real. */
    private fun adoptForeignAsShared(reason: String) {
        if (!groupIdentity.adoptCurrentAsShared()) return
        // Rejoined, not dissolved — the shared session continues.
        foreignLeftSharedGroup = false
        clearForeignTracking(keepPendingStart = true)
        Log.i(
            TAG,
            "Adopting shared Sendspin group ${groupIdentity.currentGroupId} ($reason)",
        )
        updateFallbackStreamKey(
            groupId = groupIdentity.currentGroupId.orEmpty(),
            groupName = lastGroupUpdateName,
        )
        // Undo a possibly-sent external_source so the server keeps us fed.
        sendClientStateSynchronized()
        onForeignGroupBlocked?.invoke(false)
        val held = pendingBlockedStreamStart
        pendingBlockedStreamStart = null
        if (held != null) {
            Log.i(TAG, "Replaying stream/start held while blocked")
            handleStreamStart(held)
        }
    }

    private fun adoptCurrentAsSoloAfterQuiet(reason: String) {
        if (!groupIdentity.adoptCurrentAsSolo()) return
        clearForeignTracking()
        Log.i(
            TAG,
            "Adopting quiet group ${groupIdentity.currentGroupId} as replacement solo ($reason)",
        )
        updateFallbackStreamKey(
            groupId = groupIdentity.currentGroupId.orEmpty(),
            groupName = lastGroupUpdateName,
        )
        sendClientStateSynchronized()
        onForeignGroupBlocked?.invoke(false)
        // Typical MA unsync handout: the shared group we left resolved into a
        // quiet replacement solo — that *is* the unpair.
        if (foreignLeftSharedGroup) {
            foreignLeftSharedGroup = false
            fireGroupDissolved(reason)
        }
    }

    private fun clearForeignTracking(keepPendingStart: Boolean = false) {
        foreignProbationJob?.cancel()
        foreignProbationJob = null
        foreignSelfHealJob?.cancel()
        foreignSelfHealJob = null
        foreignLeaveJob?.cancel()
        foreignLeaveJob = null
        foreignConfirmed = false
        foreignStreamActivity = false
        if (!keepPendingStart) pendingBlockedStreamStart = null
    }

    private fun silenceForeignGroup() {
        streamFormatReady = false
        artworkStreamActive = false
        playAtServerUs = Long.MIN_VALUE
        output.stop()
        outputStartedAtUs = 0L
        jitter.clear()
        lastChunkServerTimestampUs = Long.MIN_VALUE
        enterDiscontinuityMode()
        breakAudibleTimeline(/* keepProgress = */ false)
    }

    private fun scheduleLeaveForeignGroup() {
        if (foreignLeaveJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (lastForeignLeaveElapsed > 0L &&
            now - lastForeignLeaveElapsed < FOREIGN_GROUP_LEAVE_MIN_GAP_MS
        ) {
            return
        }
        foreignLeaveJob = scope.launch {
            if (!groupIdentity.blocked) return@launch
            lastForeignLeaveElapsed = SystemClock.elapsedRealtime()
            groupIdentity.markLeavingForeign()
            sendJson(
                "client/state",
                buildClientStatePayload(
                    state = "external_source",
                    volume = currentVolume,
                    muted = currentMuted,
                ),
            )
            Log.i(
                TAG,
                "Leaving foreign Sendspin group ${groupIdentity.currentGroupId}",
            )
            // Fresh window: activity from here on means the server ignored us.
            foreignStreamActivity = false
            scheduleForeignSelfHeal()
        }
    }

    /**
     * The leave was sent but a server only re-homes clients it removed from a
     * *shared* group. If the blocked id was already our solo (typical unsync
     * handout that raced ahead of MA's player_updated), no replacement id will
     * ever arrive — without this the device stays deaf until reconnect.
     */
    private fun scheduleForeignSelfHeal() {
        foreignSelfHealJob?.cancel()
        foreignSelfHealJob = scope.launch {
            delay(FOREIGN_GROUP_SELF_HEAL_MS)
            if (!groupIdentity.blocked) return@launch
            if (isMassSyncGrouped?.invoke() == true) {
                adoptForeignAsShared("ma-confirmed-after-leave")
                return@launch
            }
            if (foreignStreamActivity) {
                // Still being fed: the fan-out is alive and ignoring the leave.
                // Ask again (min-gap limits the rate) and keep watching.
                scheduleLeaveForeignGroup()
            } else {
                adoptCurrentAsSoloAfterQuiet("quiet-after-leave")
            }
        }
    }

    /**
     * Derive the fallback stream key from server + group identity. FNV-1a so
     * every device computes the same value from the same strings; 0 and
     * MIN_VALUE are reserved as "unknown" sentinels on the wire.
     */
    private fun updateFallbackStreamKey(groupId: String, groupName: String) {
        val groupPart = groupId.ifBlank { groupName }
        if (serverId.isBlank() || groupPart.isBlank()) return
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (ch in "$serverId|$groupPart") {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        if (hash == 0L || hash == Long.MIN_VALUE) hash = 1L
        fallbackStreamKey = hash
    }

    /** Beacon payload for the 1Hz LAN broadcast; null = nothing worth advertising. */
    fun buildPeerBeaconSnapshot(): SendspinPeerBeacon? {
        if (!isConnected.get()) return null
        if (!isAudiblyPlaying(1_500L)) return null
        val streamKey = currentStreamKey() ?: return null
        val playAt = playAtServerUs
        val sample = audibleNowSample() ?: return null
        // Gapless boundary: the DAC can still be inside the previous stream's
        // tail while play_at already advanced — never advertise mixed timelines.
        // (Only checkable on legacy servers that still send play_at.)
        if (playAt != Long.MIN_VALUE && sample.serverTsUs < playAt) return null
        var flags = 0
        if (sample.dacTrusted) flags = flags or SendspinPeerBeacon.FLAG_DAC_TRUSTED
        val steady = timeFilter.hasConverged() &&
            !forceResyncMode &&
            !inDiscontinuityMode &&
            userSyncOffsetMs == 0L &&
            pendingManualCatchupUs == 0L
        if (steady) flags = flags or SendspinPeerBeacon.FLAG_STEADY
        return SendspinPeerBeacon(sample.serverTsUs, flags, streamKey)
    }

    /**
     * Inbound beacon from a grouped peer. Computes the true relative playback
     * offset (all common-mode sync errors cancel in the subtraction) and, when
     * this device is audibly behind, accelerates it via a bounded, slow-stepped
     * playout-offset correction. The leading side never slows down — its peer
     * runs the mirrored computation and catches up from its own end.
     *
     * Mixer-head fallback (no [SendspinPeerBeacon.FLAG_DAC_TRUSTED]): a trusted
     * DAC never chases that inflated clock; an untrusted local may still steer
     * toward a trusted peer, and two untrusted boxes may steer toward each
     * other, both with a wider deadband and a tighter total clamp.
     */
    fun onPeerPlaybackBeacon(
        peerId: String,
        peerAudibleServerTsUs: Long,
        peerFlags: Int,
        peerStreamKey: Long,
    ) {
        if (peerId.isBlank()) return
        if (userSyncOffsetMs != 0L) return
        if (peerFlags and SendspinPeerBeacon.FLAG_STEADY == 0) return
        val myKey = currentStreamKey()
        if (myKey == null || peerStreamKey != myKey) return
        if (streamEnded || forceResyncMode || inDiscontinuityMode) return
        if (!timeFilter.hasConverged()) return
        if (!isAudiblyPlaying(1_500L)) return
        val mine = audibleNowSample() ?: return
        val playAt = playAtServerUs
        if (playAt != Long.MIN_VALUE && mine.serverTsUs < playAt) return
        val peerTrusted = peerFlags and SendspinPeerBeacon.FLAG_DAC_TRUSTED != 0
        // Mixer head leads the DAC by an unknown HAL latency. A trusted DAC
        // chasing that reading would accelerate toward a clock that is ahead
        // of the actual speaker — wrong direction. Untrusted locals may still
        // catch up to a trusted peer (under-corrects, never over-chases).
        if (!peerTrusted && mine.dacTrusted) return
        // In-flight LAN delay only ages the peer's reported position, which
        // inflates delta upward ("I look ahead") — it can never fake a lag.
        val deltaUs = mine.serverTsUs - (peerAudibleServerTsUs + PEER_BEACON_OWD_COMP_US)
        if (kotlin.math.abs(deltaUs) > PEER_ALIGN_SANITY_US) return
        val coarse = !mine.dacTrusted || !peerTrusted
        val nowMs = SystemClock.elapsedRealtime()
        synchronized(peerAlignLock) {
            val window = peerAlignWindows.getOrPut(peerId) { ArrayDeque() }
            window.addLast(PeerAlignSample(nowMs, deltaUs, peerTrusted, coarse))
            prunePeerAlignWindowsLocked(nowMs)
            maybeSteerTowardPeerLocked(nowMs)
        }
    }

    private fun prunePeerAlignWindowsLocked(nowMs: Long) {
        val it = peerAlignWindows.entries.iterator()
        while (it.hasNext()) {
            val (_, window) = it.next()
            while (window.isNotEmpty() &&
                nowMs - window.first().atElapsedMs > PEER_ALIGN_WINDOW_MS
            ) {
                window.removeFirst()
            }
            while (window.size > PEER_ALIGN_WINDOW_MAX) {
                window.removeFirst()
            }
            if (window.isEmpty()) it.remove()
        }
    }

    /**
     * Total order for coarse steering: the higher beacon id yields (steers),
     * the lower one is the reference. Falls back to steering when our own id
     * is unknown — an un-named device would otherwise never align at all.
     */
    private fun yieldsCoarseAlignmentTo(peerId: String): Boolean {
        val localId = com.example.ava.voice.AvaVoiceDiscovery.localId()
        if (localId.isEmpty() || peerId.isEmpty()) return true
        return localId > peerId
    }

    private fun maybeSteerTowardPeerLocked(nowMs: Long) {
        if (nowMs < peerSteerHoldUntilMs) return
        if (lastPeerStepMs != 0L && nowMs - lastPeerStepMs < PEER_ALIGN_STEP_COOLDOWN_MS) return
        if (pendingManualCatchupUs != 0L) return
        // Only steer from a settled local timeline — during a rate ramp/resync
        // the DAC comparison is momentarily meaningless.
        val earlySample = lastTimelineEarlyUs
        val pipeSample = lastTimelinePipelineLatencyUs
        if (earlySample == Long.MIN_VALUE || pipeSample <= 0L) return
        if (kotlin.math.abs(earlySample - pipeSample) > PEER_ALIGN_STEADY_BAND_US) return
        // One robust Δ per peer (second-smallest survives one freak outlier),
        // then steer toward the most-ahead grouped peer. Prefer a DAC-trusted
        // reference when one exists so two mixer-head boxes cannot pull the
        // group toward an inflated clock.
        var bestTrustedId: String? = null
        var bestTrustedDelta = Long.MAX_VALUE
        var bestTrustedCoarse = false
        var bestCoarseId: String? = null
        var bestCoarseDelta = Long.MAX_VALUE
        for ((id, window) in peerAlignWindows) {
            if (window.size < PEER_ALIGN_MIN_SAMPLES) continue
            val sorted = window.map { it.deltaUs }.sorted()
            val deltaUs = sorted[1]
            val peerTrusted = window.any { it.peerTrusted }
            val sampleCoarse = window.any { it.coarse }
            if (peerTrusted) {
                if (deltaUs < bestTrustedDelta) {
                    bestTrustedDelta = deltaUs
                    bestTrustedId = id
                    bestTrustedCoarse = sampleCoarse
                }
            } else if (deltaUs < bestCoarseDelta) {
                bestCoarseDelta = deltaUs
                bestCoarseId = id
            }
        }
        val coarse = bestTrustedId == null || bestTrustedCoarse
        val peerId = (bestTrustedId ?: bestCoarseId) ?: return
        val bestDeltaUs = if (bestTrustedId != null) bestTrustedDelta else bestCoarseDelta
        val deadbandUs = if (coarse) PEER_ALIGN_COARSE_DEADBAND_US else PEER_ALIGN_DEADBAND_US
        val totalMinUs = if (coarse) PEER_ALIGN_COARSE_TOTAL_MIN_US else PEER_ALIGN_TOTAL_MIN_US
        // Release an accumulated offset once we measure *ahead* of the peer.
        // The ratchet used to be strictly one-way, so a single bad coarse
        // estimate stayed baked in for the rest of the session and the pair
        // could only ever drift further apart.
        if (bestDeltaUs > deadbandUs && peerAlignOffsetUs < 0L) {
            val releaseUs =
                kotlin.math.min(-peerAlignOffsetUs, PEER_ALIGN_STEP_MAX_US)
            peerAlignOffsetUs += releaseUs
            lastPeerStepMs = nowMs
            autoOffsetLearnHoldUntilUs = nowUs() + PEER_ALIGN_LEARN_HOLD_US
            peerAlignWindows.clear()
            Log.i(
                TAG,
                "peer-align: ${bestDeltaUs / 1000}ms ahead of $peerId; " +
                    "release ${releaseUs / 1000}ms, total ${peerAlignOffsetUs / 1000}ms"
            )
            return
        }
        if (bestDeltaUs >= -deadbandUs) return
        // Coarse readings come from the mixer head, so an unknown HAL latency
        // pollutes *both* ends and each can conclude "I am behind" — they then
        // chase each other and never converge (observed: 80ms behind, step,
        // then 159ms behind). A fixed order breaks it: only the higher beacon
        // id steers, the lower one holds still as the reference.
        if (coarse && !yieldsCoarseAlignmentTo(peerId)) return
        val stepUs = bestDeltaUs.coerceAtLeast(-PEER_ALIGN_STEP_MAX_US)
        val next = (peerAlignOffsetUs + stepUs).coerceIn(totalMinUs, 0L)
        // Accelerate only — never unwind a previous step via a tighter coarse clamp.
        if (next >= peerAlignOffsetUs) return
        Log.i(
            TAG,
            "peer-align: ${-bestDeltaUs / 1000}ms behind $peerId" +
                "${if (coarse) " (coarse)" else ""}; " +
                "step ${stepUs / 1000}ms, total ${next / 1000}ms"
        )
        peerAlignOffsetUs = next
        lastPeerStepMs = nowMs
        autoOffsetLearnHoldUntilUs = nowUs() + PEER_ALIGN_LEARN_HOLD_US
        peerAlignWindows.clear()
    }

    /**
     * How far the lyric clock should sit behind the UI playhead so text meets
     * the speaker (progress / write clock often leads audible output).
     *
     * Positive user sync delay always counts. With default lyric HUD lead at 0,
     * a modest positive auto playout offset is also applied (clamped) — previously
     * skipped because it fought a baked-in +200ms DISPLAY_LEAD.
     */
    fun estimatedLyricAudibleLagMs(): Long {
        if (!output.isStarted()) return 0L
        if (userSyncOffsetMs > 0L) {
            return userSyncOffsetMs.coerceIn(0L, LYRIC_AUDIBLE_LAG_MAX_MS)
        }
        val autoMs = (autoPlayoutOffsetUs / 1000.0).roundToLong()
        return autoMs.coerceIn(0L, LYRIC_AUDIBLE_LAG_MAX_MS)
    }

    /**
     * Read-only mirror of [SendspinClientStats.audioLatencyUs] (ms).
     * Same scheduling pipeline estimate already buried in getStats / MediaPlayer
     * stats sheet — does not alter playout, auto-offset, or protocol payloads.
     */
    fun reportedAudioLatencyMs(): Long {
        val us = output.getSchedulingPipelineLatencyUs()
        if (us <= 0L) return 0L
        return (us / 1000.0).roundToLong().coerceAtLeast(0L)
    }

    /**
     * Last track-relative progress derived from a successfully written chunk.
     * Null until the first audible write after a calibrate / pending arm.
     */
    fun lastAudibleTrackProgressMs(): Long? =
        audibleTrackProgressMs.takeIf { it >= 0L }

    /**
     * Seat the audible→track mapping from a trusted metadata / seek sample.
     * [metadataTimestampUs] null → arm on the next written chunk (resume / seek
     * without a server metadata clock).
     */
    fun calibrateAudibleProgress(
        progressMs: Long,
        metadataTimestampUs: Long?,
        playbackSpeed: Int = 1000,
        durationMs: Long? = null,
    ) {
        val clamped = progressMs.coerceAtLeast(0L)
        resetPresentationFloor()
        audibleAnchorProgressMs = clamped
        audibleAnchorServerTsUs = metadataTimestampUs
        // 0 = frozen (pause/stop). Never leave the mapper frozen when a positive
        // rate was intended but a seam packet sent speed=0 — that stalls UI progress.
        audibleAnchorSpeed = if (playbackSpeed <= 0) 0 else playbackSpeed.coerceAtLeast(1)
        durationMs?.takeIf { it > 0L }?.let { audibleDurationMs = it }
        // Optimistic UI seed before the next write (buffering / pause hold).
        audibleTrackProgressMs = clamped
        lastAudibleProgressEmitUs = 0L
        audibleNeedsAbsoluteSeat = false
        audibleEmitHoldUntilServerTsUs = Long.MIN_VALUE
    }

    /**
     * Track-boundary seat: [progressMs] applies at [boundaryServerTsUs] on the chunk
     * play timeline. Chunks written before the boundary (previous track's buffered
     * tail) keep the bar silent; the first chunk at/after it maps to the true
     * position into the new track. Also seats cold-start joins mid-track correctly:
     * progress@snapshot-ts + timeline delta = current position, replacing the old
     * auto-arm-at-0 that restarted every bar from zero.
     */
    fun calibrateAudibleProgressAtBoundary(
        progressMs: Long,
        boundaryServerTsUs: Long,
        playbackSpeed: Int = 1000,
        durationMs: Long? = null,
    ) {
        calibrateAudibleProgress(progressMs, boundaryServerTsUs, playbackSpeed, durationMs)
        audibleEmitHoldUntilServerTsUs = boundaryServerTsUs
    }

    /**
     * After the overlay decides the true audible origin (e.g. open-lead → 0),
     * pin the mapper to [progressMs] at the last written chunk's server time so
     * the next writes advance by timeline delta instead of pending-arm stamping.
     */
    fun reseatAudibleOrigin(progressMs: Long, playbackSpeed: Int = 1000) {
        val clamped = progressMs.coerceAtLeast(0L)
        resetPresentationFloor()
        audibleAnchorProgressMs = clamped
        audibleAnchorServerTsUs =
            lastWrittenChunkServerTsUs.takeIf { it != Long.MIN_VALUE }
        audibleAnchorSpeed = playbackSpeed.coerceAtLeast(1)
        audibleTrackProgressMs = clamped
        lastAudibleProgressEmitUs = 0L
        audibleNeedsAbsoluteSeat = false
        audibleEmitHoldUntilServerTsUs = Long.MIN_VALUE
    }

    /**
     * Pause/stop freezes the mapper at speed=0. PCM can resume (transport
     * audible) without a metadata recalibrate — those writes were dropped and
     * the bar stayed stuck. Resume from the held playhead at the last write
     * so the freeze gap is not added to track ms.
     */
    fun thawAudibleMapperIfFrozen() {
        if (audibleAnchorSpeed > 0) return
        val held = when {
            audibleTrackProgressMs >= 0L -> audibleTrackProgressMs
            else -> audibleAnchorProgressMs
        } ?: return
        resetPresentationFloor()
        audibleAnchorProgressMs = held
        audibleAnchorServerTsUs =
            lastWrittenChunkServerTsUs.takeIf { it != Long.MIN_VALUE }
        audibleAnchorSpeed = 1000
        audibleTrackProgressMs = held
        lastAudibleProgressEmitUs = 0L
        audibleNeedsAbsoluteSeat = false
        audibleEmitHoldUntilServerTsUs = Long.MIN_VALUE
    }

    /** Drop audible mapping (track change). Keeps nothing for the next title. */
    fun clearAudibleProgressAnchor() {
        resetPresentationFloor()
        audibleAnchorProgressMs = null
        audibleAnchorServerTsUs = null
        audibleDurationMs = null
        audibleTrackProgressMs = -1L
        lastAudibleProgressEmitUs = 0L
        lastWrittenChunkServerTsUs = Long.MIN_VALUE
        audibleNeedsAbsoluteSeat = false
        audibleEmitHoldUntilServerTsUs = Long.MIN_VALUE
    }

    /** True after stream/clear until an absolute track-ms seat arrives. */
    fun needsAbsoluteAudibleSeat(): Boolean = audibleNeedsAbsoluteSeat

    /**
     * Stream timeline break: optionally keep the last track ms, but require a new
     * server-ts seat (next write or [calibrateAudibleProgress]).
     */
    private fun breakAudibleTimeline(keepProgress: Boolean) {
        val held = when {
            keepProgress && audibleTrackProgressMs >= 0L -> audibleTrackProgressMs
            keepProgress -> audibleAnchorProgressMs
            else -> null
        }
        resetPresentationFloor()
        audibleAnchorServerTsUs = null
        if (held != null) {
            audibleAnchorProgressMs = held
            audibleTrackProgressMs = held
        } else if (!keepProgress) {
            clearAudibleProgressAnchor()
        }
        lastAudibleProgressEmitUs = 0L
    }

    /**
     * True while PCM has been written recently and the stream has not ended.
     * Primary authority for the overlay transport glyph (▶/❚❚).
     */
    fun isAudiblyPlaying(withinMs: Long = TRANSPORT_AUDIBLE_WITHIN_MS): Boolean {
        if (streamEnded) return false
        if (!output.isStarted()) return false
        val last = lastPcmWriteElapsedRealtimeMs
        if (last == 0L) return false
        return SystemClock.elapsedRealtime() - last <= withinMs
    }

    /** True after [stream/end] until the next stream/start. */
    fun isStreamEnded(): Boolean = streamEnded

    private fun notePcmWritten() {
        val nowMs = SystemClock.elapsedRealtime()
        lastPcmWriteElapsedRealtimeMs = nowMs
        if (nowMs - lastTransportAudibleNotifyMs < TRANSPORT_AUDIBLE_NOTIFY_MIN_MS) return
        lastTransportAudibleNotifyMs = nowMs
        onTransportAudible?.invoke()
    }

    /**
     * Map a written chunk's server play timestamp onto track-relative ms and
     * notify the manager (throttled). Primary UI progress source while playing.
     */
    private fun noteAudibleChunkWritten(chunkServerTsUs: Long) {
        lastWrittenChunkServerTsUs = chunkServerTsUs
        // Previous track's buffered tail: boundary metadata leads playout by the
        // buffer depth — chunks before the boundary must not drive the new bar.
        if (chunkServerTsUs < audibleEmitHoldUntilServerTsUs) return
        // Cold start: PCM can write before metadata calibrates the mapper.
        // Auto-arm at 0 so progress callbacks (and the UI clock) still start.
        // After stream/clear, absolute ms is unknown — do not invent 0 (lyrics).
        if (audibleAnchorProgressMs == null) {
            if (audibleNeedsAbsoluteSeat) {
                return
            }
            audibleAnchorProgressMs = 0L
            audibleAnchorServerTsUs = chunkServerTsUs
            if (audibleAnchorSpeed <= 0) audibleAnchorSpeed = 1000
        }
        val anchorProgress = audibleAnchorProgressMs ?: return
        // Frozen (pause): ignore late writes — do not advance the held playhead.
        if (audibleAnchorSpeed <= 0) {
            audibleTrackProgressMs = anchorProgress
            return
        }
        var anchorTs = audibleAnchorServerTsUs
        if (anchorTs == null) {
            // Pending arm: this written chunk defines the server-ts origin.
            audibleAnchorServerTsUs = chunkServerTsUs
            anchorTs = chunkServerTsUs
        }
        val speed = audibleAnchorSpeed.coerceAtLeast(1)
        val deltaMs = (chunkServerTsUs - anchorTs) * speed / 1_000_000L
        var progressMs = (anchorProgress + deltaMs).coerceAtLeast(0L)
        audibleDurationMs?.takeIf { it > 0L }?.let { progressMs = progressMs.coerceAtMost(it) }

        val now = nowUs()
        val deltaFromLast = kotlin.math.abs(progressMs - audibleTrackProgressMs)
        if (
            audibleTrackProgressMs >= 0L &&
            now - lastAudibleProgressEmitUs < AUDIBLE_PROGRESS_EMIT_MIN_US &&
            deltaFromLast < AUDIBLE_PROGRESS_EMIT_MIN_DELTA_MS
        ) {
            audibleTrackProgressMs = progressMs
            return
        }
        lastAudibleProgressEmitUs = now
        audibleTrackProgressMs = progressMs
        onAudibleProgress?.invoke(progressMs)
    }

    /**
     * Last server [track_progress] known to the playback sync controller.
     * While paused this is the frozen server position; while playing it is
     * lightly extrapolated from the last metadata update.
     */
    /** Last metadata play-state is paused. Stale / never-updated is not a pause. */
    fun upstreamPlayStatePaused(): Boolean {
        val state = syncController.syncState.value
        if (state.lastUpdateTimeMs <= 0L) return false
        return state.playState == SendspinPlaybackSyncController.PlayState.PAUSED
    }

    fun upstreamTrackProgressMs(): Long? {
        val state = syncController.syncState.value
        if (state.lastUpdateTimeMs <= 0L && state.serverPositionMs <= 0L) return null
        return when (state.playState) {
            SendspinPlaybackSyncController.PlayState.PLAYING ->
                syncController.getExtrapolatedServerPositionMs().coerceAtLeast(0L)
            SendspinPlaybackSyncController.PlayState.PAUSED ->
                state.serverPositionMs.coerceAtLeast(0L)
            else -> state.serverPositionMs.takeIf { it > 0L }
        }
    }

    private fun monitorTimelineAlignment(earlyUs: Long, bufferAheadMs: Long) {
        // Simplified: only handle severe LATE cases (audio falling behind)
        // Being early (ahead) is fine - just means we have buffer headroom
        // Don't trigger any resync for being ahead - it causes AudioTrack thrashing
        
        if (!timeFilter.hasConverged()) {
            return
        }
        
        val now = nowUs()
        if (lastAlignmentMonitorUs != 0L && now - lastAlignmentMonitorUs < 500_000L) {
            return  // Check every 500ms
        }
        lastAlignmentMonitorUs = now

        val severeLateUs = -(
            output.getSchedulingPipelineLatencyUs() +
                estimatedSafetyMarginUs() +
                timeFilter.getEstimatedNetworkJitterUs() +
                timeFilter.getAverageRttUs() / 4L
        ).coerceIn(LATE_DROP_FLOOR_US, 600_000L)
        
        if (earlyUs <= severeLateUs) {
            lateAlignmentStrikeCount++
        } else {
            lateAlignmentStrikeCount = 0
        }

        // 4 strikes × 500ms ≈ 2s — prefer play-through; force-resync drops hard and stutters.
        if (lateAlignmentStrikeCount >= 4) {
            Log.w(TAG, "Severely late for extended period, triggering resync: early=${earlyUs/1000}ms")
            triggerForceResync("alignment_monitor_late")
            lateAlignmentStrikeCount = 0
        }
        
        // Reset ahead counter - we don't care about being ahead
        aheadAlignmentStrikeCount = 0
    }

    /**
     * Spec sync error at the speaker: now − predicted heard time for the
     * sample leaving the port. [audibleNowSample] is already lifted from DAC
     * to the port by the OS-reported post-presentation path. Positive = late
     * vs the Sendspin server clock.
     *
     * Predicted time is the Kalman mapping only — protocol static_delay and
     * post-presentation are applied at schedule time
     * ([serverTimestampHeardUs]). Mixer-head (untrusted DAC) is skipped.
     */
    private fun dacServerAlignmentErrorUs(nowUs: Long): Long? {
        if (inDiscontinuityMode) return null
        if (outputStartedAtUs <= 0L ||
            nowUs - outputStartedAtUs < SERVER_ALIGN_MIN_OUTPUT_US
        ) {
            return null
        }
        val sample = audibleNowSample() ?: return null
        if (!sample.dacTrusted) return null
        val playAt = playAtServerUs
        if (playAt != Long.MIN_VALUE && sample.serverTsUs < playAt) return null
        val predictedHeardUs =
            timeFilter.convertServerToClient(sample.serverTsUs, nowUs)
        val errorUs = nowUs - predictedHeardUs
        if (kotlin.math.abs(errorUs) > 1_000_000L) return null
        return errorUs
    }

    private fun updateAutoOffsetModel(
        earlyUs: Long,
        bufferAheadMs: Long,
        pipelineLatencyUs: Long
    ) {
        // Don't auto-calibrate if user has set manual offset
        if (userSyncOffsetMs != 0L) {
            return
        }

        // Learn as soon as the filter is usable. Full convergence is preferred, but
        // waiting for it left cold-start desync uncorrected for a long time.
        if (!timeFilter.isReady) {
            return
        }
        val preConverged = !timeFilter.hasConverged()

        // Only learn from steady-state samples. During force-resync/discontinuity, or when the
        // timeline error is absurd (e.g. buffer churn pushing the head far into the future),
        // feeding the EMA would slam the auto offset into its clamp (-250ms) and poison it.
        if (forceResyncMode || kotlin.math.abs(earlyUs) > 1_000_000L) {
            return
        }

        val nowUs = nowUs()
        // A fresh peer-align step deliberately runs the timeline late while the
        // rate loop closes the gap — the EMA must not learn that transient away
        // (it would cancel the step and the two integrators would fight).
        if (nowUs < autoOffsetLearnHoldUntilUs) {
            return
        }
        // Rate limit: update every 50ms for precision sync
        if (lastAutoOffsetUpdateUs != 0L && nowUs - lastAutoOffsetUpdateUs < 50_000L) {
            return
        }
        lastAutoOffsetUpdateUs = nowUs

        val inSettleWindow =
            outputStartedAtUs > 0L &&
                (nowUs - outputStartedAtUs) < SETTLE_CATCHUP_WINDOW_US

        // Branch on measured alignment, not MA version: trusted DAC → lock
        // the speaker to the master clock (old and new servers). No DAC →
        // keep the original waterline learner that old MA was built on.
        val dacErrorUs = dacServerAlignmentErrorUs(nowUs)
        if (dacErrorUs != null) {
            val sampleErrorUs =
                if (kotlin.math.abs(dacErrorUs) < SERVER_ALIGN_DEADBAND_US) {
                    0L
                } else {
                    dacErrorUs.coerceIn(
                        -SERVER_ALIGN_SAMPLE_CLAMP_US,
                        SERVER_ALIGN_SAMPLE_CLAMP_US,
                    )
                }
            val alpha = when {
                preConverged -> 0.06
                inSettleWindow -> 0.10
                else -> 0.08
            }
            if (!autoOffsetServerEmaInitialized) {
                autoOffsetServerErrorEmaUs = sampleErrorUs.toDouble()
                autoOffsetServerEmaInitialized = true
            } else {
                autoOffsetServerErrorEmaUs =
                    autoOffsetServerErrorEmaUs * (1.0 - alpha) +
                        sampleErrorUs.toDouble() * alpha
            }
            // Late vs server → negative offset → hear sooner. Opposite of the
            // waterline learner, which delays when the write is late.
            val newOffset = (-autoOffsetServerErrorEmaUs).toLong()
                .coerceIn(AUTO_OFFSET_MIN_US, AUTO_OFFSET_MAX_US)
            if (kotlin.math.abs(newOffset - autoPlayoutOffsetUs) < 2_000L) {
                return
            }
            autoPlayoutOffsetUs = newOffset
            autoOffsetInitialized = true
            maybePersistAutoOffset()
            return
        }

        val targetEarlyUs = pipelineLatencyUs.coerceIn(40_000L, 2_000_000L)
        val earlyErrorUs = earlyUs - targetEarlyUs
        val deepQueue = jitter.size() > 30

        // Deep-queue + "late": severe lateness is usually write backpressure on a
        // full AudioTrack — learning from it slams offset to the -250ms clamp.
        // Mild under-target (still near the scheduling waterline) is real drift
        // behind the group; allow a slow, capped catch-up so auto-0ms does not
        // freeze permanently behind while the speed loop alone cannot recover.
        val sampleErrorUs: Long
        val alpha: Double
        when {
            earlyErrorUs < -80_000L && deepQueue -> return
            earlyErrorUs < 0L && deepQueue -> {
                sampleErrorUs = earlyErrorUs.coerceAtLeast(-45_000L)
                alpha = when {
                    preConverged -> 0.07
                    inSettleWindow -> 0.13
                    else -> 0.11
                }
            }
            preConverged -> {
                // Pre-converge: learn, but keep steps small.
                sampleErrorUs = earlyErrorUs.coerceIn(-50_000L, 50_000L)
                alpha = 0.10
            }
            else -> {
                sampleErrorUs = earlyErrorUs
                alpha = if (inSettleWindow) 0.35 else 0.3
            }
        }

        autoOffsetEarlyErrorEmaUs =
            autoOffsetEarlyErrorEmaUs * (1.0 - alpha) + sampleErrorUs.toDouble() * alpha

        val newOffset = (-autoOffsetEarlyErrorEmaUs).toLong().coerceIn(AUTO_OFFSET_MIN_US, AUTO_OFFSET_MAX_US)
        
        // Update if change is > 2ms
        if (kotlin.math.abs(newOffset - autoPlayoutOffsetUs) < 2_000L) {
            return
        }
        
        autoPlayoutOffsetUs = newOffset
        autoOffsetInitialized = true

        maybePersistAutoOffset()
    }

    private fun estimatedSafetyMarginUs(): Long {
        val networkMargin = when (timeFilter.getNetworkConditionQuality()) {
            SendspinNetworkQuality.GOOD -> 6_000L
            SendspinNetworkQuality.FAIR -> 12_000L
            SendspinNetworkQuality.POOR -> 20_000L
        }
        val stabilityMargin = when (timeFilter.getClockStability()) {
            SendspinClockStability.STABLE -> 4_000L
            SendspinClockStability.CONVERGING -> 9_000L
            SendspinClockStability.UNSTABLE -> 16_000L
        }
        val underrunMargin = underrunCount.coerceAtMost(8L) * 1_500L
        return networkMargin + stabilityMargin + underrunMargin
    }

    private fun loadPersistedAutoOffsetUs(): Long {
        // Always start with 0 offset - auto-calibration will adjust after Kalman converges
        // Don't load stale values that may have been saved with incorrect calibration
        return DEFAULT_AUTO_PLAYOUT_OFFSET_US
    }

    private fun maybePersistAutoOffset() {
        if (!timeFilter.hasConverged()) return
        if (kotlin.math.abs(autoPlayoutOffsetUs - lastPersistedAutoOffsetUs) < AUTO_OFFSET_SAVE_STEP_US) return
        runCatching {
            context.getSharedPreferences(AUTO_OFFSET_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(AUTO_OFFSET_KEY, autoPlayoutOffsetUs)
                .apply()
            lastPersistedAutoOffsetUs = autoPlayoutOffsetUs
        }
    }

    private fun triggerForceResync(reason: String) {
        forceResyncMode = true
        forceResyncUntilUs = nowUs() + 3_000_000L
        playAtServerUs = Long.MIN_VALUE
        // Use the same late budget as steady playout — 40ms was discarding still-usable audio.
        val dropped = jitter.dropWhileLate(nowUs(), LATE_DROP_FLOOR_US)
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastForceResyncLogMs >= 1_000L) {
            lastForceResyncLogMs = nowMs
            Log.w(TAG, "force-resync triggered ($reason), dropped=$dropped")
        }
    }

    private fun readInt64BE(buf: ByteArray, off: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (buf[off + i].toLong() and 0xFFL)
        }
        return value
    }

    private fun nowUs(): Long = System.nanoTime() / 1000L

    fun handleIncomingText(text: String) {
        handleText(text)
    }

    fun handleIncomingBinary(data: ByteArray) {
        handleBinary(data)
    }

    fun handleIncomingClosed(reason: String) {
        if (noiseHandshake != null && noiseTransport == null) {
            SendspinLegacyGate.armAfterFailedInit()
        }
        teardown("closed: $reason")
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun handleIncomingFailure(t: Throwable) {
        Log.e(TAG, "Inbound transport failure: ${t.message}", t)
        if (noiseHandshake != null && noiseTransport == null) {
            SendspinLegacyGate.armAfterFailedInit()
        }
        teardown("failure: ${t.message}")
        _connectionState.value = ConnectionState.ERROR
    }

    fun getStableDeviceName(): String {
        val global = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        if (!global.isNullOrBlank()) return global

        val product = android.os.Build.PRODUCT
        val model = android.os.Build.MODEL
        return if (product.isNotBlank() && product != model) "$model $product" else model
    }
}
