package com.example.ava.detection

import android.content.Context
import android.util.Log
import com.example.ava.audio.AudioEnergy
import com.example.ava.esphome.entities.TextSensorEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AudioEventManager"

class AudioEventManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val captureEnabled = AtomicBoolean(false)
    private val detectorInitialized = AtomicBoolean(false)
    private val inferenceScheduled = AtomicBoolean(false)
    @Volatile private var latestWindow: ByteBuffer? = null
    private val detectMutex = Mutex()

    private var windowsProcessed = 0L
    private var windowsReceived = 0L
    private var windowsSkipped = 0L
    private var windowsThrottled = 0L
    private var idleResetJob: Job? = null
    private var activeEventLabel: String? = null

    /** Last confirmed publish (diagnostic); survives idle reset of the HA entity. */
    @Volatile private var lastPublishedEventLabel: String? = null
    @Volatile private var lastPublishedAtMs: Long = 0L

    private var pendingLabel: String? = null
    private var pendingCount = 0
    private var pendingExpiresAtMs = 0L

    private var lastClassifyAtMs = 0L
    private var lastInferenceMs = 0L
    private var adaptiveIntervalMs = AudioEventDeviceProfile.timing(AudioEventDeviceProfile.Tier.STANDARD)
        .minClassifyIntervalMs

    /** Last inference top-line (diagnostic; updated even when score fails thresholds). */
    @Volatile private var lastTopLabel: String? = null
    @Volatile private var lastTopScore = 0f
    @Volatile private var lastFeatureMean = 0f
    @Volatile private var lastFeatureNz = 0f
    @Volatile private var lastPcmRms = 0f
    @Volatile private var lastPasses = false

    @Volatile private var detectionConfig: AudioEventDetectionConfig = AudioEventDetectionConfig.DEFAULT

    private var audioEventEntity: TextSensorEntity? = null

    fun bindEntity(audioEventEntity: TextSensorEntity) {
        this.audioEventEntity = audioEventEntity
    }

    fun setCaptureEnabled(enabled: Boolean) {
        captureEnabled.set(enabled)
        Log.i(TAG, "captureEnabled=$enabled")
        if (!enabled) {
            windowsProcessed = 0
            windowsReceived = 0
            windowsSkipped = 0
            windowsThrottled = 0
            pendingLabel = null
            pendingCount = 0
            pendingExpiresAtMs = 0L
            lastClassifyAtMs = 0L
            lastInferenceMs = 0L
            adaptiveIntervalMs = detectionConfig.timing.minClassifyIntervalMs
            latestWindow = null
            idleResetJob?.cancel()
            idleResetJob = null
            activeEventLabel = null
            audioEventEntity?.updateState("idle")
        }
    }

    fun applySettings(config: AudioEventDetectionConfig) {
        val tier = AudioEventDeviceProfile.resolveTier(context)
        val previousDisplayMs = detectionConfig.eventDisplayMs
        detectionConfig = config.copy(deviceTier = tier)
        adaptiveIntervalMs = detectionConfig.timing.minClassifyIntervalMs
        if (config.enabled) {
            ensureDetectorInitialized()
        }
        val shouldRescheduleIdle =
            config.enabled &&
                previousDisplayMs != detectionConfig.eventDisplayMs &&
                activeEventLabel != null
        setCaptureEnabled(config.enabled)
        if (shouldRescheduleIdle) {
            scheduleIdleReset()
        }
        Log.i(
            TAG,
            "config enabled=${config.enabled} sensitivity=${config.sensitivity} " +
                "displaySec=${detectionConfig.eventDisplayMs / 1000L} " +
                "deviceTier=$tier classifyFloor=${detectionConfig.timing.minClassifyIntervalMs}ms " +
                "monitored=${config.monitoredLabels.sorted()}",
        )
    }

    fun onWindowReady(pcm: ByteBuffer) {
        if (!captureEnabled.get()) {
            Log.w(TAG, "onWindowReady ignored: capture disabled")
            return
        }
        windowsReceived++
        latestWindow = pcm.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!inferenceScheduled.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Default) {
            try {
                drainLatestWindow()
            } catch (e: Exception) {
                Log.e(TAG, "processWindow failed", e)
            } finally {
                inferenceScheduled.set(false)
                if (latestWindow != null) scheduleDrain()
            }
        }
    }

    /** One window per drain — coalescing happens in [onWindowReady]; avoids burst inference on weak CPUs. */
    private suspend fun drainLatestWindow() {
        val pcm = latestWindow.also { latestWindow = null } ?: return
        processWindow(pcm)
    }

    fun close() {
        captureEnabled.set(false)
        idleResetJob?.cancel()
        idleResetJob = null
        if (detectorInitialized.compareAndSet(true, false)) {
            closeAudioEventDetector()
        }
    }

    private fun ensureDetectorInitialized() {
        if (detectorInitialized.get()) return
        if (initAudioEventDetector(context)) {
            detectorInitialized.set(true)
        } else {
            Log.e(TAG, "failed to initialize audio event detector")
        }
    }

    private fun hasPendingConfirm(now: Long): Boolean =
        pendingLabel != null &&
            pendingCount < detectionConfig.timing.confirmRequired &&
            now <= pendingExpiresAtMs

    private fun shouldClassify(now: Long): Boolean {
        if (hasPendingConfirm(now)) return true
        if (lastClassifyAtMs == 0L) return true
        return now - lastClassifyAtMs >= adaptiveIntervalMs
    }

    private fun updateAdaptiveInterval(inferenceMs: Long) {
        lastInferenceMs = inferenceMs
        adaptiveIntervalMs = AudioEventDeviceProfile.adaptiveIntervalMs(
            detectionConfig.deviceTier,
            inferenceMs,
        )
    }

    private suspend fun processWindow(pcm: ByteBuffer) {
        if (!captureEnabled.get()) return

        if (!AudioEventWindowQuality.pcmLooksValid(pcm)) {
            windowsSkipped++
            return
        }

        val now = System.currentTimeMillis()
        if (!shouldClassify(now)) {
            windowsThrottled++
            if (windowsThrottled == 1L || windowsThrottled % 20L == 0L) {
                Log.d(
                    TAG,
                    "throttle skip interval=${adaptiveIntervalMs}ms pending=${hasPendingConfirm(now)}",
                )
            }
            return
        }
        lastClassifyAtMs = now

        ensureDetectorInitialized()
        if (!detectorInitialized.get()) return

        val pcmRms = AudioEnergy.pcm16LeFloatRms(pcm.duplicate())
        val inferenceStart = System.nanoTime()

        val results = detectMutex.withLock {
            detectAudioEvent(pcm)
        } ?: run {
            Log.w(TAG, "detectAudioEvent returned null")
            return
        }

        updateAdaptiveInterval((System.nanoTime() - inferenceStart) / 1_000_000L)

        val top = results.firstOrNull() ?: return
        val thresholds = detectionConfig.thresholds
        if (!AudioEventWindowQuality.featuresLookValid(top, thresholds.featureNonZeroMin)) {
            windowsSkipped++
            if (windowsSkipped == 1L || windowsSkipped % 20L == 0L) {
                Log.i(
                    TAG,
                    "skip low-quality window rms=${"%.3f".format(pcmRms)} " +
                        "feat=${"%.3f".format(top.featureMean)} nz=${"%.2f".format(top.featureNonZeroRatio)} " +
                        "top=${top.label} ${"%.2f".format(top.score)}",
                )
            }
            return
        }

        windowsProcessed++
        val passes = AudioEventAutoThresholds.passes(top, results.getOrNull(1), thresholds)
        lastTopLabel = top.label
        lastTopScore = top.score
        lastFeatureMean = top.featureMean
        lastFeatureNz = top.featureNonZeroRatio
        lastPcmRms = pcmRms
        lastPasses = passes
        if (passes) {
            val second = results.getOrNull(1)
            Log.i(
                TAG,
                "inference #$windowsProcessed/$windowsReceived top=${top.label} ${"%.2f".format(top.score)} " +
                    "2nd=${second?.label ?: "-"} ${second?.let { "%.2f".format(it.score) } ?: "-"} " +
                    "rms=${"%.3f".format(pcmRms)} feat=${"%.3f".format(top.featureMean)} " +
                    "nz=${"%.2f".format(top.featureNonZeroRatio)} infer=${lastInferenceMs}ms " +
                    "interval=${adaptiveIntervalMs}ms passes=true",
            )
        } else if (windowsProcessed == 1L || windowsProcessed % 20L == 0L) {
            val second = results.getOrNull(1)
            Log.i(
                TAG,
                "inference #$windowsProcessed/$windowsReceived top=${top.label} ${"%.2f".format(top.score)} " +
                    "2nd=${second?.label ?: "-"} ${second?.let { "%.2f".format(it.score) } ?: "-"} " +
                    "rms=${"%.3f".format(pcmRms)} feat=${"%.3f".format(top.featureMean)} " +
                    "nz=${"%.2f".format(top.featureNonZeroRatio)} infer=${lastInferenceMs}ms " +
                    "interval=${adaptiveIntervalMs}ms passes=false",
            )
        }

        if (!passes) return

        if (top.label !in detectionConfig.monitoredLabels) {
            if (windowsProcessed == 1L || windowsProcessed % 20L == 0L) {
                Log.i(TAG, "inference suppressed: ${top.label} not in monitored set")
            }
            return
        }

        val confirmRequired = detectionConfig.timing.confirmRequired
        val confirmWindowMs = AudioEventDeviceProfile.confirmWindowMs(
            detectionConfig.deviceTier,
            adaptiveIntervalMs,
        )

        if (top.score < thresholds.confirmStrongMin) {
            if (pendingLabel == top.label && now <= pendingExpiresAtMs) {
                pendingCount++
            } else {
                pendingLabel = top.label
                pendingCount = 1
            }
            pendingExpiresAtMs = now + confirmWindowMs
            if (pendingCount < confirmRequired) {
                Log.i(
                    TAG,
                    "audio event pending confirm: ${top.label} ${"%.2f".format(top.score)} " +
                        "($pendingCount/$confirmRequired window=${confirmWindowMs}ms)",
                )
                return
            }
        }

        pendingLabel = null
        pendingCount = 0

        publishEvent(top.label)
        Log.i(
            TAG,
            "audio event confirmed: ${top.label} score=${"%.2f".format(top.score)} " +
                "feat=${"%.3f".format(top.featureMean)} rms=${"%.3f".format(pcmRms)} " +
                "infer=${lastInferenceMs}ms tier=${detectionConfig.deviceTier}",
        )
    }

    /** Read-only: last confirmed event label, or null if none this process. */
    fun lastPublishedLabel(): String? = lastPublishedEventLabel

    /** Read-only: age of last published label in ms, or null if never published. */
    fun lastPublishedAgeMs(): Long? {
        val at = lastPublishedAtMs
        if (at == 0L || lastPublishedEventLabel == null) return null
        return (System.currentTimeMillis() - at).coerceAtLeast(0L)
    }

    /** Read-only: device tier applied with the current detection config. */
    fun deviceTier(): AudioEventDeviceProfile.Tier = detectionConfig.deviceTier

    /** Read-only probe for Voice Stats (Audio Event focus). */
    fun diagnosticsSnapshot(): AudioEventProbeSnapshot {
        val now = System.currentTimeMillis()
        val pendingActive = pendingLabel != null && now <= pendingExpiresAtMs
        return AudioEventProbeSnapshot(
            captureEnabled = captureEnabled.get(),
            windowsReceived = windowsReceived,
            windowsProcessed = windowsProcessed,
            windowsSkipped = windowsSkipped,
            windowsThrottled = windowsThrottled,
            lastInferenceMs = lastInferenceMs,
            adaptiveIntervalMs = adaptiveIntervalMs,
            lastTopLabel = lastTopLabel,
            lastTopScore = lastTopScore,
            lastFeatureMean = lastFeatureMean,
            lastFeatureNz = lastFeatureNz,
            lastPcmRms = lastPcmRms,
            lastPasses = lastPasses,
            pendingLabel = if (pendingActive) pendingLabel else null,
            pendingCount = if (pendingActive) pendingCount else 0,
            confirmRequired = detectionConfig.timing.confirmRequired,
            activeLabel = activeEventLabel,
            displayMs = detectionConfig.eventDisplayMs,
            monitoredCount = detectionConfig.monitoredLabels.size,
            deviceTier = detectionConfig.deviceTier.name,
        )
    }

    private fun publishEvent(label: String) {
        val entity = audioEventEntity ?: run {
            Log.w(TAG, "audio event confirmed but entity not bound")
            return
        }
        activeEventLabel = label
        lastPublishedEventLabel = label
        lastPublishedAtMs = System.currentTimeMillis()
        entity.forceUpdateState(label)
        scheduleIdleReset()
    }

    private fun scheduleIdleReset() {
        if (activeEventLabel == null) return
        val entity = audioEventEntity ?: return
        val displayMs = detectionConfig.eventDisplayMs
        idleResetJob?.cancel()
        idleResetJob = scope.launch {
            delay(displayMs)
            entity.forceUpdateState("idle")
            activeEventLabel = null
            Log.i(TAG, "audio event sensor reset to idle after ${displayMs}ms")
        }
    }
}
