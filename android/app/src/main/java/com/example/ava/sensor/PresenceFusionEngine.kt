package com.example.ava.sensor

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Presence fusion: Bayes + hive inertia + causal hysteresis.
 *
 * 1. Bayes — each source contributes ln(P(obs|occupied) / P(obs|empty)) to a
 *    log-odds sum while active. Correlated sources share a [Group] and only the
 *    strongest member of a group counts, so one utterance can't double-vote.
 *
 * 2. Hive inertia — sustained activity builds "capital" (capped at
 *    [CAPITAL_MAX_MS]) which stretches the leave hold up to [MAX_HOLD_MULTIPLIER]×.
 *    Someone who has been in the room for minutes is not declared gone after a
 *    few quiet seconds; a brief walk-through decays quickly. The configured
 *    leave seconds are the floor, not the whole story.
 *
 * 3. Causal hysteresis — occupancy is directional. Flipping ON requires
 *    the posterior to cross the user threshold; flipping OFF requires it to fall
 *    below a lower release line ([RELEASE_MARGIN_PERCENT] under the threshold),
 *    so wobbling evidence never makes the state flap.
 *
 * 4. House prior — the prior is not fixed. Peer Avas share their verdict
 *    as an `occupied=` field on the existing identity beacon (UDP 19848); when
 *    another room sees (or just saw) someone, a person is provably home and
 *    this room's prior rises, so its first weak signals are believed sooner.
 *    When every peer is quiet the prior drops and the same signals are
 *    doubted. Alone, the neutral prior applies.
 *
 * After a source goes quiet its weight holds for the (inertia-stretched) leave
 * window, then halves every [POST_HOLD_HALF_LIFE_MS].
 */
object PresenceFusionEngine {

    enum class Group { VISION, TOUCH, NEAR, VOICE }

    enum class Source(
        val group: Group,
        probGivenOccupied: Double,
        probGivenEmpty: Double,
        /** Rising edge recomputes immediately instead of waiting for the 1s ticker. */
        val instantAttack: Boolean = false,
    ) {
        FACE(Group.VISION, 0.70, 0.02),
        // Frame motion sees people whose face the detector can't (back turned, far).
        MOTION(Group.VISION, 0.55, 0.05),
        TOUCH(Group.TOUCH, 0.60, 0.01),
        // Accelerometer jolt: a bump, a pickup, footsteps through the same surface.
        // Vibration cannot cross walls, so it never blames the wrong room.
        // Shares the TOUCH group — tapping the screen also shakes the device,
        // and one gesture must not vote twice.
        VIBRATION(Group.TOUCH, 0.30, 0.03),
        // Something centimeters from the panel in an empty room is practically
        // impossible — near-proof, and it flips the state the instant it fires.
        // (Bluetooth device presence was removed: a phone charging at home
        // proves the device is in the house, not that a person is in this room,
        // and BLE crosses walls — chronic wrong-room misjudgment.)
        PROXIMITY(Group.NEAR, 0.50, 0.005, instantAttack = true),
        // An identified enrolled speaker all but rules out an empty room.
        // (Ambient light flux was considered and rejected: fast cloud edges,
        // sudden rain and automated smart lights all mimic a human, and the
        // signal is blind at night — weather-grade evidence has no place here.)
        VOICEPRINT(Group.VOICE, 0.65, 0.002);

        val logLikelihoodRatio: Double = ln(probGivenOccupied / probGivenEmpty)
    }

    data class Config(
        val enabledSources: Set<Source> = emptySet(),
        val thresholdPercent: Int = 85,
        val leaveSeconds: Int = 15,
    )

    /** No peers on the mesh: neutral single-device prior. */
    private const val PRIOR_ALONE = 0.2

    /**
     * A peer room has (or just had) someone — a person is home and mobile, so
     * P(walks in here) is real: frame motion alone can now cross an 85%
     * threshold, though vibration alone still cannot.
     */
    private const val PRIOR_HOUSE_ACTIVE = 0.35

    /**
     * Peers exist and all report empty. Weak evidence gets doubted, but a
     * detected face (LLR 3.56) still clears 85% — near-certain proof must win
     * even against an empty house.
     */
    private const val PRIOR_HOUSE_EMPTY = 0.15

    /** After the (stretched) leave window expires, evidence halves this fast. */
    private const val POST_HOLD_HALF_LIFE_MS = 2_000.0

    /** Contributions decayed below this fraction are treated as gone. */
    private const val DECAY_FLOOR = 0.02

    /** Hive inertia: at most 5 minutes of activity credit accrues. */
    private const val CAPITAL_MAX_MS = 300_000.0

    /** Every minute of accrued activity adds one extra leave-window of hold. */
    private const val CAPITAL_REF_MS = 60_000.0

    /** Idle time drains capital at half the rate activity builds it. */
    private const val CAPITAL_IDLE_DRAIN_RATIO = 0.5

    /** Leave hold never stretches beyond this multiple of the configured value. */
    private const val MAX_HOLD_MULTIPLIER = 6.0

    /** Causal hysteresis: release fires this far below the on-threshold... */
    private const val RELEASE_MARGIN_PERCENT = 15

    /** ...but never below this floor. */
    private const val RELEASE_FLOOR_PERCENT = 30

    private class Track {
        @Volatile var active = false
        @Volatile var lastActiveAtMs = 0L
    }

    private val tracks = Source.entries.associateWith { Track() }

    @Volatile private var config = Config()

    /** Mesh verdict fed by the occupancy ticker: null = alone, true/false = house active/empty. */
    @Volatile private var houseActive: Boolean? = null

    /** Only mutated inside [recompute], which is synchronized. */
    private var capitalMs = 0.0
    private var lastTickAtMs = 0L

    private val _occupied = MutableStateFlow(false)
    val occupied: StateFlow<Boolean> = _occupied.asStateFlow()

    private val _probability = MutableStateFlow(0f)
    val probability: StateFlow<Float> = _probability.asStateFlow()

    fun configure(config: Config) {
        this.config = config
        recompute()
    }

    /** House context from the presence mesh; only sets a volatile, hot-path safe. */
    fun setHouseContext(active: Boolean?) {
        houseActive = active
    }

    /**
     * Level evidence: stays at full weight for as long as [active] is true.
     *
     * Hot-path safe: reports only mark the track (two volatile writes) — all math
     * runs on the occupancy module's 1s ticker, so callers in the camera/audio
     * paths pay nothing perceptible. The one exception is an [Source.instantAttack]
     * rising edge (e.g. proximity): someone is physically at the panel, so the
     * verdict must not wait out the ticker — recompute right here. Those sources
     * report from low-rate sensor callbacks, never from the voice pipeline.
     */
    fun report(source: Source, active: Boolean) {
        val track = tracks.getValue(source)
        val wasActive = track.active
        if (active || wasActive) {
            track.lastActiveAtMs = System.currentTimeMillis()
        }
        track.active = active
        if (active && !wasActive && source.instantAttack && source in config.enabledSources) {
            recompute()
        }
    }

    /** Instantaneous evidence (e.g. a voiceprint match): full weight now, then hold + decay. */
    fun reportEvent(source: Source) {
        val track = tracks.getValue(source)
        track.active = false
        track.lastActiveAtMs = System.currentTimeMillis()
    }

    fun reset() {
        tracks.values.forEach {
            it.active = false
            it.lastActiveAtMs = 0L
        }
        capitalMs = 0.0
        lastTickAtMs = 0L
        houseActive = null
        recompute()
    }

    /**
     * Driven by the occupancy module's 1s ticker (plus [configure] and instant-attack
     * rising edges from sensor callbacks) — synchronized so out-of-band calls can't
     * interleave with the ticker. The math is a handful of flops; contention is nil.
     */
    @Synchronized
    fun recompute() {
        val cfg = config
        if (cfg.enabledSources.isEmpty()) {
            capitalMs = 0.0
            lastTickAtMs = 0L
            _probability.value = 0f
            _occupied.value = false
            return
        }
        val now = System.currentTimeMillis()

        // Hive inertia: activity accrues capital, idleness slowly drains it.
        val anyActive = cfg.enabledSources.any { tracks.getValue(it).active }
        val elapsedMs =
            if (lastTickAtMs == 0L) 0.0 else (now - lastTickAtMs).coerceAtLeast(0L).toDouble()
        lastTickAtMs = now
        capitalMs = if (anyActive) {
            min(capitalMs + elapsedMs, CAPITAL_MAX_MS)
        } else {
            max(0.0, capitalMs - elapsedMs * CAPITAL_IDLE_DRAIN_RATIO)
        }
        val holdMultiplier = min(1.0 + capitalMs / CAPITAL_REF_MS, MAX_HOLD_MULTIPLIER)
        val holdMs = cfg.leaveSeconds * 1000.0 * holdMultiplier

        val prior = when (houseActive) {
            null -> PRIOR_ALONE
            true -> PRIOR_HOUSE_ACTIVE
            false -> PRIOR_HOUSE_EMPTY
        }
        var logOdds = ln(prior / (1 - prior))
        for (group in Group.entries) {
            var strongest = 0.0
            for (source in Source.entries) {
                if (source.group != group || source !in cfg.enabledSources) continue
                val track = tracks.getValue(source)
                val factor = when {
                    track.active -> 1.0
                    track.lastActiveAtMs == 0L -> 0.0
                    else -> {
                        val pastHoldMs = (now - track.lastActiveAtMs) - holdMs
                        if (pastHoldMs <= 0) 1.0
                        else 2.0.pow(-pastHoldMs / POST_HOLD_HALF_LIFE_MS)
                    }
                }
                if (factor < DECAY_FLOOR) continue
                strongest = max(strongest, source.logLikelihoodRatio * factor)
            }
            logOdds += strongest
        }
        val posterior = 1.0 / (1.0 + exp(-logOdds))
        _probability.value = posterior.toFloat()

        // Causal hysteresis: on and off use different lines, so the state can't flap.
        val posteriorPercent = posterior * 100.0
        val releaseAt =
            max(cfg.thresholdPercent - RELEASE_MARGIN_PERCENT, RELEASE_FLOOR_PERCENT).toDouble()
        _occupied.value = if (_occupied.value) {
            posteriorPercent >= releaseAt
        } else {
            posteriorPercent >= cfg.thresholdPercent
        }
    }
}
