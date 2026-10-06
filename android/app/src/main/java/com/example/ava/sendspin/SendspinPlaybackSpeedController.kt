package com.example.ava.sendspin

/**
 * Playback-rate helper.
 *
 * Multi-room rules:
 * - Never chase jitter **buffer depth** (that crawls 0.998–1.002 and drifts rooms).
 * - Soft-correct from **timeline lateness** only (`earlyUs - pipelineLatency`).
 * - Prefer a tight ~4–10ms late band: nudge rate up when late, then return to
 *   drift/1.0 and wait. Larger gaps may ramp to [lateSpeedMax] (1.2x) so
 *   multi-room desync can close without waiting on soft frame drops alone.
 * - Discrete drop/hard-cut stay in [SendspinClient]; this class only nudges rate.
 */
class SendspinPlaybackSpeedController(
    private val output: SendspinPcmAudioOutput,
    private val jitter: SendspinAudioJitterBuffer
) {
    private var currentSpeed = 1.0f
    private var emaBufferAheadMs = Double.NaN
    private var emaTimelineErrorMs = Double.NaN
    private var lastSuccessfulSpeedUs = 0L
    private var outputStartedAtUs = 0L
    /** One-shot: only armed for true cold start after stream/start or stream/clear. */
    private var hardCorrectionArmed = false

    private val startupBoostDurationUs = 4_000_000L
    private val hardCorrectionThresholdMs = 80.0
    /**
     * Steady-state late band: react once past ~3ms late; deadband clears the
     * nudge once back inside ~3.5ms so we do not chatter around zero.
     */
    private val lateDeadbandMs = 3.5
    private val lateThresholdUs = -3_000L

    /**
     * Ceiling while timeline-late. Spec continuous trim is ±0.5%, but a stuck
     * multi-room gap needs a harder catch-up; AudioTrack allows up to 2.0x —
     * 1.2x is the aggressive-but-listenable ceiling we use here.
     */
    private val lateSpeedMax = 1.20

    /**
     * @param enableHardCorrection true only for the first start of a stream/seek.
     * Mid-play restarts (write hiccup / force-resync recovery) must pass false —
     * otherwise a buffer blip pauses output and the progress UI snaps to intro.
     */
    fun notifyOutputStarted(nowUs: Long, enableHardCorrection: Boolean = false) {
        outputStartedAtUs = nowUs
        emaBufferAheadMs = Double.NaN
        emaTimelineErrorMs = Double.NaN
        lastSuccessfulSpeedUs = 0L
        hardCorrectionArmed = enableHardCorrection
    }

    /**
     * @param driftPpm Kalman client-clock drift (ppm). Negative = client slow vs
     *   server. Feed-forward only; magnitude is typically tens of ppm (≪ 0.1%).
     * @param timelineEarlyUs [heardPlayUs - nowUs] from the playout path, or
     *   [Long.MIN_VALUE] when no fresh sample is available.
     * @param pipelineLatencyUs scheduling write-lead (heard − write).
     * @return unused (always false). Startup buffer fill used to request a
     *   playback restart; that pause is what dropped paired audio for seconds.
     */
    fun adjustSpeed(
        nowUs: Long,
        driftPpm: Double = 0.0,
        timelineEarlyUs: Long = Long.MIN_VALUE,
        pipelineLatencyUs: Long = 0L
    ): Boolean {
        if (!output.isStarted()) {
            reset()
            return false
        }
        if (outputStartedAtUs == 0L) return false

        val targetAheadMs = output.getSchedulingPipelineLatencyUs() / 1000.0
        val rawAheadMs = jitter.snapshot().bufferAheadMs.toDouble()
        emaBufferAheadMs =
            if (emaBufferAheadMs.isNaN()) {
                rawAheadMs
            } else {
                0.3 * rawAheadMs + 0.7 * emaBufferAheadMs
            }

        val bufferErrorMs = emaBufferAheadMs - targetAheadMs
        val sinceStartUs = nowUs - outputStartedAtUs
        val inStartupPhase = outputStartedAtUs > 0L && sinceStartUs < startupBoostDurationUs

        if (!inStartupPhase) {
            hardCorrectionArmed = false
        }

        val activeHardCorrectionThresholdMs = if (targetAheadMs > 200.0) {
            kotlin.math.max(hardCorrectionThresholdMs, targetAheadMs * 0.4)
        } else {
            hardCorrectionThresholdMs
        }
        if (
            hardCorrectionArmed &&
            inStartupPhase &&
            sinceStartUs > 1_500_000L &&
            kotlin.math.abs(bufferErrorMs) > activeHardCorrectionThresholdMs
        ) {
            // Send-ahead is still filling (open-lead 1–4s). An 80ms waterline
            // miss here is normal — pausing to "hard-correct" is the paired
            // multi-second dropout. Disarm and keep rate-nudging.
            hardCorrectionArmed = false
        }

        // Drift feed-forward (tens of ppm). Wider so ~30–80ppm crystals do not
        // sit permanently behind before timeline catch-up has to fight them.
        val driftCorrection = (-driftPpm / 1_000_000.0).coerceIn(-0.0005, 0.0005)
        val driftSpeed = 1.0 + driftCorrection

        val timelineLate = isTimelineLate(timelineEarlyUs, pipelineLatencyUs)
        val timelineSpeed = lateOnlySpeed(
            driftSpeed = driftSpeed,
            timelineEarlyUs = timelineEarlyUs,
            pipelineLatencyUs = pipelineLatencyUs,
            // Late catch-up may go to 1.2x — ±0.5% alone cannot close multi-room
            // tens–hundreds-of-ms gaps before the next soft-drop.
            speedMin = 0.996,
            speedMax = lateSpeedMax,
        )

        val desiredSpeed: Double
        val rateLimitUs: Long
        if (inStartupPhase && !timelineLate) {
            // Startup waterline only when not timeline-late — deep send-ahead must
            // not force a slowdown while we are already behind schedule.
            // Gentler when buffer-ahead (avoids parking at ~0.995x); firmer when
            // the waterline is shallow. Keep this mild — 1.2x is for late catch-up.
            val kP = if (bufferErrorMs > 0.0) 0.00015 else 0.00035
            val bufferDeadbandMs = 12.0
            val speedMin = 0.988
            val speedMax = 1.015
            var buffered = (driftSpeed - (kP * bufferErrorMs)).coerceIn(speedMin, speedMax)
            if (kotlin.math.abs(bufferErrorMs) < bufferDeadbandMs) {
                buffered = driftSpeed
            }
            desiredSpeed = kotlin.math.round(buffered * 1000.0) / 1000.0
            rateLimitUs = 200_000L
        } else {
            desiredSpeed = timelineSpeed
            // Late: refresh sooner so catch-up can ramp toward 1.2x promptly.
            rateLimitUs = if (timelineLate) 50_000L else 260_000L
        }

        val desiredSpeedF = desiredSpeed.toFloat()
        // Slew-limit each step so PlaybackParams does not click; faster while late
        // so 1.0→1.2 can finish in ~0.5–1s instead of stalling near +1%.
        val maxStep = if (timelineLate) 0.020f else 0.0015f
        val stepped =
            if (kotlin.math.abs(desiredSpeedF - currentSpeed) <= maxStep) {
                desiredSpeedF
            } else {
                currentSpeed + kotlin.math.sign(desiredSpeedF - currentSpeed) * maxStep
            }
        if (kotlin.math.abs(stepped - currentSpeed) > 0.00005f &&
            nowUs - lastSuccessfulSpeedUs >= rateLimitUs
        ) {
            output.setPlaybackSpeed(stepped)
            currentSpeed = stepped
            lastSuccessfulSpeedUs = nowUs
        }
        return false
    }

    private fun isTimelineLate(timelineEarlyUs: Long, pipelineLatencyUs: Long): Boolean {
        if (timelineEarlyUs == Long.MIN_VALUE || pipelineLatencyUs <= 0L) return false
        return (timelineEarlyUs - pipelineLatencyUs) < lateThresholdUs
    }

    /**
     * scheduleError = earlyUs − pipelineLatency:
     *   ~0 on-time, negative = late, large positive = buffered headroom.
     * Late-only: never slow down from deep-buffer earliness.
     * Once back inside the 5ms deadband, return to driftSpeed and wait.
     */
    private fun lateOnlySpeed(
        driftSpeed: Double,
        timelineEarlyUs: Long,
        pipelineLatencyUs: Long,
        speedMin: Double,
        speedMax: Double
    ): Double {
        if (timelineEarlyUs == Long.MIN_VALUE || pipelineLatencyUs <= 0L) {
            emaTimelineErrorMs = Double.NaN
            return roundSpeed(driftSpeed)
        }

        val rawErrorMs =
            (timelineEarlyUs - pipelineLatencyUs).toDouble() / 1000.0
        // Ignore absurd samples (seek / force-resync churn) — do not yank rate.
        // Allow deeper late samples so 1.2x catch-up can close large room gaps.
        if (rawErrorMs < -500.0 || rawErrorMs > 400.0) {
            return roundSpeed(driftSpeed)
        }

        // Faster EMA when late so 10–20ms gaps are noticed within ~0.5s.
        val alpha = if (rawErrorMs < -lateDeadbandMs) 0.60 else 0.25
        emaTimelineErrorMs =
            if (emaTimelineErrorMs.isNaN()) {
                rawErrorMs
            } else {
                alpha * rawErrorMs + (1.0 - alpha) * emaTimelineErrorMs
            }

        val lateErrorMs =
            if (emaTimelineErrorMs < -lateDeadbandMs) {
                // ~150ms late → ~1.2x with kP below; larger blips stay capped.
                emaTimelineErrorMs.coerceAtLeast(-160.0)
            } else {
                0.0
            }

        // 10ms late → ~+1.3%; 50ms → ~+6.7%; ~150ms → +20% (1.2x clamp).
        val kP = 0.00133
        return roundSpeed((driftSpeed - (kP * lateErrorMs)).coerceIn(speedMin, speedMax))
    }

    private fun roundSpeed(speed: Double): Double =
        kotlin.math.round(speed * 1_000_000.0) / 1_000_000.0

    fun reset() {
        currentSpeed = 1.0f
        emaBufferAheadMs = Double.NaN
        emaTimelineErrorMs = Double.NaN
        lastSuccessfulSpeedUs = 0L
        outputStartedAtUs = 0L
        hardCorrectionArmed = false
    }
}
