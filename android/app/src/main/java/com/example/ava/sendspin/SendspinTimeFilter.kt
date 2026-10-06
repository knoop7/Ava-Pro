package com.example.ava.sendspin

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Network condition quality levels.
 */
enum class SendspinNetworkQuality {
    GOOD,       // Low latency, low jitter
    FAIR,       // Moderate latency or jitter
    POOR        // High latency or jitter
}

/**
 * Clock stability states.
 */
enum class SendspinClockStability {
    UNSTABLE,   // High drift uncertainty
    CONVERGING, // Drift uncertainty decreasing
    STABLE      // Drift well-estimated
}

/**
 * Snapshot of time transformation parameters for lock-free reading.
 * Mirrors the reference TimeElement dataclass from time_sync.py.
 */
private data class TimeElement(
    val lastUpdate: Long = 0,
    val offset: Double = 0.0,
    val drift: Double = 0.0,
    /**
     * Baseline bundled into the same immutable snapshot so a single volatile
     * write publishes baseline + offset + drift atomically to the playout thread
     * (previously baseline was a separate non-volatile field → torn/stale reads).
     */
    val baselineOffsetUs: Long = 0
)

/**
 * Two-dimensional Kalman filter for NTP-style time synchronization.
 *
 * Tracks clock offset and drift between client and server using NTP-style
 * 4-timestamp message exchanges. Both client and server timestamps are
 * monotonic microseconds -- the server uses its own monotonic clock and the
 * client uses System.nanoTime() / 1000.
 *
 * The offset between the two monotonic clocks can be very large (e.g. days
 * worth of microseconds) and this is perfectly normal -- no epoch or unit
 * conversion is needed.
 *
 * Port of the reference Python implementation:
 *   aiosendspin/client/time_sync.py - SendspinTimeFilter
 *
 * State vector: [offset, drift] where:
 *   - offset: timestamp offset server - client (us)
 *   - drift:  rate of change of offset (dimensionless, us/us)
 */
class SendspinTimeFilter(
    processStdDev: Double = 0.0,
    driftProcessStdDev: Double = 1e-11,
    private val forgetFactor: Double = 2.0,
    private val adaptiveCutoffFraction: Double = 3.0,
    private val minSamplesBeforeForgetting: Int = 100,
    private val maxErrorScale: Double = 0.5,
    private val driftSignificanceThreshold: Double = 2.0
) {
    // Derived process variance (matches reference: self._process_variance = process_std_dev ** 2)
    private val processVariance: Double = processStdDev * processStdDev
    private val driftProcessVariancePerUs: Double = driftProcessStdDev * driftProcessStdDev
    private var useDrift: Boolean = false

    // Kalman filter state -- mirrors reference fields exactly
    private var lastUpdateUs: Long = 0          // _last_update
    private var updateCount: Int = 0            // _count
    private var offset: Double = 0.0            // _offset
    private var drift: Double = 0.0             // _drift
    
    // Baseline offset: the first measurement used to normalize all subsequent offsets
    // This converts the huge monotonic clock difference into small millisecond sync values
    private var baselineOffsetUs: Long? = null  // Captured on first measurement
    
    // Covariance matrix P (2x2, stored as 3 independent entries)
    private var offsetCovariance: Double = Double.POSITIVE_INFINITY      // _offset_covariance
    private var offsetDriftCovariance: Double = 0.0                      // _offset_drift_covariance
    private var driftCovariance: Double = 0.0                            // _drift_covariance

    // Snapshot for lock-free reads by convertServerToClient / convertClientToServer
    @Volatile
    private var currentTimeElement = TimeElement()

    // Diagnostic history
    private var lastMeasurementUs: Double = 0.0
    private var lastMaxErrorUs: Long = 0
    private var lastResidualUs: Double = 0.0
    private var kalmanErrorCount: Long = 0  // Count of large residuals indicating filter anomalies

    fun getKalmanErrorCount(): Long = kalmanErrorCount

    // Network metrics for adaptive sync frequency
    private val networkMetricsLock = Any()
    private val rttHistory = ArrayDeque<Long>(20)
    private val maxErrorHistory = ArrayDeque<Long>(20)

    @Volatile
    private var estimatedNetworkJitterUs: Long = 0

    @Volatile
    private var averageRttUs: Long = 0

    @Volatile
    private var cachedNetworkQuality: SendspinNetworkQuality = SendspinNetworkQuality.FAIR

    // Frequency recommendation with hysteresis
    private var lastRecommendedFrequencyMs: Long = 0
    private var lastFrequencyChangeTimeMs: Long = 0

    // Time-based stability tracking
    private var convergedAtTimeMs: Long = 0
    private val stabilizationTimeMs = 3000L
    @Volatile
    private var frozen = false

    /**
     * Process a server/time response.
     *
     * All four timestamps are raw monotonic microseconds -- no conversion
     * is needed. The NTP offset and delay are computed here and forwarded
     * to the Kalman update.
     *
     * @param clientTransmittedUs  T1 - client clock when request was sent
     * @param clientReceivedUs     T4 - client clock when response arrived
     * @param serverReceivedUs     T2 - server clock when request arrived
     * @param serverTransmittedUs  T3 - server clock when response was sent
     */
    fun onServerTime(
        clientTransmittedUs: Long,
        clientReceivedUs: Long,
        serverReceivedUs: Long,
        serverTransmittedUs: Long
    ) {
        // NTP offset (client - server, not server - client):
        // This gives the SMALL clock difference we need for sync, not the huge monotonic difference
        val measurement = (
            (clientTransmittedUs - serverReceivedUs)
            + (clientReceivedUs - serverTransmittedUs)
        ) / 2

        // Half round-trip (max error): ((T4 - T1) - (T3 - T2)) / 2
        val maxError = (
            (clientReceivedUs - clientTransmittedUs)
            - (serverTransmittedUs - serverReceivedUs)
        ) / 2

        // time_added = T4 (client timestamp when measurement was taken)
        update(measurement, maxError, clientReceivedUs)
    }

    /**
     * Process a new measurement through the Kalman filter.
     *
     * Matches the reference signature:
     *   update(measurement, max_error, time_added)
     *
     * Uses multi-sample initialization (5-10 measurements) before computing drift
     * to avoid locking onto a bad offset from noisy early measurements.
     */
    fun update(measurement: Long, maxError: Long, timeAddedUs: Long) {
        if (frozen) return
        if (timeAddedUs == lastUpdateUs) return   // skip duplicate timestamps

        val rtt = 2 * maxError
        if (hasConverged()) {
            val rttSnapshot = synchronized(networkMetricsLock) {
                if (rttHistory.size >= 10) rttHistory.toList() else emptyList()
            }
            if (rttSnapshot.isNotEmpty()) {
                val sorted = rttSnapshot.sorted()
                val medianRtt = if (sorted.size % 2 == 0) {
                    (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
                } else {
                    sorted[sorted.size / 2]
                }
                if (medianRtt > 0L && rtt > 4L * medianRtt) {
                    return
                }
            }
        }

        synchronized(networkMetricsLock) {
            rttHistory.addLast(rtt)
            if (rttHistory.size > 20) rttHistory.removeFirst()
            maxErrorHistory.addLast(maxError)
            if (maxErrorHistory.size > 20) maxErrorHistory.removeFirst()

            averageRttUs = if (rttHistory.isEmpty()) 0L else rttHistory.average().toLong()

            if (rttHistory.size >= 5) {
                val mean = rttHistory.average()
                val variance = rttHistory.map { (it - mean) * (it - mean) }.average()
                estimatedNetworkJitterUs = sqrt(variance).toLong()
            }

            cachedNetworkQuality = if (maxErrorHistory.isEmpty()) {
                SendspinNetworkQuality.FAIR
            } else {
                val avgMaxError = maxErrorHistory.average().toLong()
                val maxJitter = if (rttHistory.size >= 2) {
                    (rttHistory.maxOrNull() ?: 0L) - (rttHistory.minOrNull() ?: 0L)
                } else {
                    0L
                }
                when {
                    avgMaxError < 20_000 && maxJitter < 20_000 -> SendspinNetworkQuality.GOOD
                    avgMaxError > 100_000 || maxJitter > 50_000 -> SendspinNetworkQuality.POOR
                    else -> SendspinNetworkQuality.FAIR
                }
            }
        }

        val dt: Double = (timeAddedUs - lastUpdateUs).toDouble()
        lastUpdateUs = timeAddedUs

        val updateStdDev: Double = maxError.toDouble() * maxErrorScale
        val measurementVariance: Double = updateStdDev * updateStdDev

        // --- Multi-sample initialization phase (first 8 measurements) ---
        // Accumulate measurements to find a stable offset baseline before computing drift.
        // This prevents locking onto a noisy measurement and then being unable to recover.
        if (updateCount < 8) {
            updateCount++
            
            // Normalize measurement: subtract baseline to get small sync error
            // First measurement sets the baseline (will be near-zero after normalization)
            if (baselineOffsetUs == null) {
                baselineOffsetUs = measurement
            }
            val normalizedMeasurement = measurement - baselineOffsetUs!!
            
            if (updateCount == 1) {
                // First measurement: initialize with normalized value (will be ~0)
                offset = normalizedMeasurement.toDouble()
                offsetCovariance = measurementVariance
                drift = 0.0
                driftCovariance = 0.0
                offsetDriftCovariance = 0.0
                lastMeasurementUs = normalizedMeasurement.toDouble()
                lastMaxErrorUs = maxError
                publishTimeElement(0.0)
                return
            }
            
            // Measurements 2-8: average the normalized measurements to get stable baseline offset
            // This acts as a low-pass filter rejecting noisy early RTT spikes
            val alpha = 1.0 / updateCount  // Exponential moving average weight
            offset = offset * (1.0 - alpha) + normalizedMeasurement.toDouble() * alpha
            
            // Update uncertainty to be variance of measurements
            offsetCovariance = offsetCovariance * (1.0 - alpha) + measurementVariance * alpha
            
            // Keep drift at zero during initialization
            drift = 0.0
            driftCovariance = 0.0
            offsetDriftCovariance = 0.0
            
            lastMeasurementUs = normalizedMeasurement.toDouble()
            lastMaxErrorUs = maxError
            
            if (updateCount == 8) {
                // After 8 measurements, we have a stable offset baseline
                // Initialize Kalman covariance matrix for full filter
                offsetCovariance = offsetCovariance.coerceAtLeast(100.0)  // Measurement uncertainty
                driftCovariance = 1e-10  // Start learning drift
                offsetDriftCovariance = 0.0
                drift = 0.0  // Start drift estimation from zero
            }
            // Publish every init measurement too (baseline is set from count 1), so a
            // concurrent playout read never sees a stale/torn baseline during init.
            publishTimeElement(0.0)
            return
        }

        // --- Full Kalman filter (count >= 9) ---
        // Continuously track both offset AND drift using standard Kalman predict-correct
        updateCount++
        
        // Normalize measurement relative to baseline for consistent sync values
        val normalizedMeasurement = measurement - baselineOffsetUs!!

        // == Prediction step ==
        val predictedOffset: Double = offset + drift * dt

        val dtSquared: Double = dt * dt

        // Covariance prediction: P_k|k-1 = F P F^T + Q
        val driftProcessVariance: Double = dt * driftProcessVariancePerUs
        val newDriftCovariance: Double = driftCovariance + driftProcessVariance

        val offsetDriftProcessVariance = 0.0
        val newOffsetDriftCovariance: Double =
            offsetDriftCovariance + driftCovariance * dt + offsetDriftProcessVariance

        val offsetProcessVariance: Double = dt * processVariance
        val newOffsetCovariance: Double =
            offsetCovariance +
            2.0 * offsetDriftCovariance * dt +
            driftCovariance * dtSquared +
            offsetProcessVariance

        // == Innovation & adaptive forgetting ==
        val residual: Double = normalizedMeasurement.toDouble() - predictedOffset
        lastResidualUs = residual

        // Track Kalman anomalies: large residuals indicate filter is struggling
        // (residual should be small if filter is well-calibrated and network is stable)
        val dynamicResidualThresholdUs = maxOf(50_000.0, maxError.toDouble() * 3.0)
        if (updateCount >= 30 && hasConverged() && maxError <= 120_000L &&
            abs(residual) > dynamicResidualThresholdUs
        ) {
            kalmanErrorCount++
        }

        var covOff = newOffsetCovariance
        var covDrift = newDriftCovariance
        var covOffDrift = newOffsetDriftCovariance

        if (updateCount >= minSamplesBeforeForgetting) {
            val forgetThreshold = adaptiveCutoffFraction * maxError.toDouble()
            if (abs(residual) > forgetThreshold) {
                val f2 = forgetFactor * forgetFactor
                covOff *= f2
                covDrift *= f2
                covOffDrift *= f2
            }
        }

        // == Measurement update ==
        val uncertainty: Double = 1.0 / (covOff + measurementVariance)

        val offsetGain: Double = covOff * uncertainty
        val driftGain: Double = covOffDrift * uncertainty

        // State update
        offset = predictedOffset + offsetGain * residual
        drift = (drift + driftGain * residual).coerceIn(-0.001, 0.001)

        // Covariance update
        driftCovariance = covDrift - driftGain * covOffDrift
        offsetDriftCovariance = covOffDrift - driftGain * covOff
        offsetCovariance = covOff - offsetGain * covOff

        // Only extrapolate drift once it is statistically significant
        val driftSquared = drift * drift
        useDrift = driftSquared >
            (driftSignificanceThreshold * driftSignificanceThreshold) * driftCovariance.coerceAtLeast(0.0)
        val effectiveDrift = if (useDrift) drift else 0.0

        // Publish snapshot for lock-free reads (baseline bundled in for atomicity)
        publishTimeElement(effectiveDrift)

        lastMeasurementUs = measurement.toDouble()
        lastMaxErrorUs = maxError
    }

    /**
     * Publish the current filter state as one immutable snapshot. All source fields
     * are written by [update] on the WS/IO thread; bundling the baseline in here
     * lets [convertClientToServer] / [convertServerToClient] read a consistent tuple
     * with a single volatile load. [publishedDrift] is 0 during init and the
     * drift-significant value in the full Kalman phase.
     */
    private fun publishTimeElement(publishedDrift: Double) {
        currentTimeElement = TimeElement(
            lastUpdate = lastUpdateUs,
            offset = offset,
            drift = publishedDrift,
            baselineOffsetUs = baselineOffsetUs ?: 0L
        )
    }

    /**
     * Convert a client timestamp to the equivalent server timestamp.
     * server_time = client_time - baseline - offset
     *
     * Pure clock conversion: static delay is applied at the scheduling layer only.
     */
    fun convertClientToServer(clientTimeUs: Long): Long {
        val te = currentTimeElement
        val dt = (clientTimeUs - te.lastUpdate).toDouble()
        val offsetWithDrift = te.offset + te.drift * dt
        val fullOffsetUs = te.baselineOffsetUs + offsetWithDrift.toLong()
        return clientTimeUs - fullOffsetUs
    }

    /**
     * Convert a server timestamp to the equivalent client timestamp.
     * client_time = server_time + baseline + offset(with drift)
     *
     * [atClientTimeUs] is the client monotonic instant used to extrapolate drift
     * (same role as clientTimeUs in [convertClientToServer]).
     */
    fun convertServerToClient(
        serverTimeUs: Long,
        atClientTimeUs: Long = monotonicNowUs()
    ): Long {
        val te = currentTimeElement
        val dt = (atClientTimeUs - te.lastUpdate).toDouble()
        val offsetWithDrift = te.offset + te.drift * dt
        val fullOffsetUs = te.baselineOffsetUs + offsetWithDrift.toLong()
        return serverTimeUs + fullOffsetUs
    }

    private fun monotonicNowUs(): Long = System.nanoTime() / 1000L

    /** Estimated offset in microseconds (server - client). */
    fun estimatedOffsetUs(): Long = offset.toLong()

    /** Estimated drift in parts per million. */
    fun estimatedDriftPpm(): Double = drift * 1_000_000.0

    /** Standard deviation of offset estimate in microseconds. */
    fun getOffsetUncertaintyUs(): Long = sqrt(offsetCovariance.coerceAtLeast(0.0)).toLong()

    /** Standard deviation of drift estimate in parts per million. */
    fun getDriftUncertaintyPpm(): Double = sqrt(driftCovariance.coerceAtLeast(0.0)) * 1_000_000.0

    /** True after at least 2 measurements with finite covariance (matches reference is_synchronized). */
    fun hasConverged(): Boolean = updateCount >= 15 && offsetCovariance.isFinite()

    /** Number of Kalman updates processed. */
    fun getUpdateCount(): Int = updateCount

    /** Last innovation / residual from filter update (us). */
    fun getLastResidualUs(): Double = lastResidualUs

    /** Drift signal-to-noise ratio. */
    fun getDriftSnr(): Double {
        if (!hasConverged()) return 0.0
        val std = getDriftUncertaintyPpm()
        return if (std > 0) abs(estimatedDriftPpm()) / std else Double.POSITIVE_INFINITY
    }

    fun getNetworkConditionQuality(): SendspinNetworkQuality = cachedNetworkQuality

    fun getClockStability(): SendspinClockStability {
        if (!hasConverged()) {
            convergedAtTimeMs = 0
            return SendspinClockStability.UNSTABLE
        }
        if (convergedAtTimeMs == 0L) convergedAtTimeMs = System.currentTimeMillis()
        val elapsed = System.currentTimeMillis() - convergedAtTimeMs
        return when {
            elapsed >= stabilizationTimeMs -> SendspinClockStability.STABLE
            elapsed >= 1000L -> SendspinClockStability.CONVERGING
            else -> SendspinClockStability.UNSTABLE
        }
    }

    fun getRecommendedSyncFrequencyMs(): Long {
        var intervalMs: Long
        if (!hasConverged()) {
            intervalMs = 50L
        } else {
            intervalMs = when (getNetworkConditionQuality()) {
                SendspinNetworkQuality.POOR -> 150L
                SendspinNetworkQuality.GOOD -> 500L
                SendspinNetworkQuality.FAIR -> 250L
            }
            intervalMs = when (getClockStability()) {
                SendspinClockStability.UNSTABLE -> (intervalMs * 0.8).toLong()
                SendspinClockStability.CONVERGING -> intervalMs
                SendspinClockStability.STABLE -> (intervalMs * 1.2).toLong()
            }
        }
        intervalMs = intervalMs.coerceIn(25L, 2000L)

        // Hysteresis
        val now = System.currentTimeMillis()
        val prev = lastRecommendedFrequencyMs
        if (prev > 0) {
            val elapsed = now - lastFrequencyChangeTimeMs
            val change = abs(intervalMs.toDouble() - prev.toDouble()) / prev.toDouble()
            if (elapsed < 2000L || change < 0.1) {
                intervalMs = prev
            } else {
                lastFrequencyChangeTimeMs = now
            }
        } else {
            lastFrequencyChangeTimeMs = now
        }
        lastRecommendedFrequencyMs = intervalMs
        return intervalMs
    }

    fun getLastRecommendedFrequencyMs(): Long = lastRecommendedFrequencyMs
    fun getEstimatedNetworkJitterUs(): Long = estimatedNetworkJitterUs

    /** Get the average RTT from recent measurements. Returns 0 if no data. */
    fun getAverageRttUs(): Long = averageRttUs

    fun reset() {
        offset = 0.0
        drift = 0.0
        lastUpdateUs = 0
        updateCount = 0
        baselineOffsetUs = null
        offsetCovariance = Double.POSITIVE_INFINITY
        offsetDriftCovariance = 0.0
        driftCovariance = 0.0
        currentTimeElement = TimeElement()
        lastMeasurementUs = 0.0
        lastMaxErrorUs = 0
        lastResidualUs = 0.0
        kalmanErrorCount = 0
        synchronized(networkMetricsLock) {
            rttHistory.clear()
            maxErrorHistory.clear()
        }
        estimatedNetworkJitterUs = 0
        averageRttUs = 0
        cachedNetworkQuality = SendspinNetworkQuality.FAIR
        useDrift = false
        lastRecommendedFrequencyMs = 0
        lastFrequencyChangeTimeMs = 0
        convergedAtTimeMs = 0
        frozen = false
    }

    val isReady: Boolean get() = updateCount >= 2 && offsetCovariance.isFinite()
    val isConverged: Boolean get() = updateCount >= 15 && offsetCovariance.isFinite()
    val measurementCountValue: Int get() = updateCount
    val isFrozen: Boolean get() = frozen
    fun freeze() {
        frozen = true
    }
    fun thaw() {
        frozen = false
    }

    var staticDelayMs: Double = 0.0

    fun setStaticDelayMsUserAdjusted(value: Double) {
        staticDelayMs = value
    }

    fun getClockDriftPpm(): Double = estimatedDriftPpm()
    fun getEffectiveDelayUs(): Long = (staticDelayMs * 1000.0).toLong()
    fun getBaselineOffsetUs(): Long = currentTimeElement.baselineOffsetUs
    fun getOffsetUs(): Long = currentTimeElement.offset.toLong()

    fun addMeasurement(measurementOffset: Long, maxError: Long, clientTimeMicros: Long, rtt: Long = 0L): Boolean {
        update(
            measurement = measurementOffset,
            maxError = maxError.coerceAtLeast(1L),
            timeAddedUs = clientTimeMicros
        )
        return true
    }
}
