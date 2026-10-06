package com.example.ava.esphome.voicesatellite

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.ava.R
import com.example.ava.esphome.EspHomeDevice
import com.example.ava.esphome.entities.BinarySensorEntity
import com.example.ava.multidevice.PresenceMesh
import com.example.ava.sensor.PresenceFusionEngine
import com.example.ava.voice.AvaVoiceDiscovery
import com.example.ava.sensor.ScreenTouchSensor
import com.example.ava.services.ScreensaverFrameMotionProbe
import com.example.ava.settings.ExperimentalSettings
import com.example.ava.settings.ExperimentalSettingsStore
import com.example.esphomeproto.api.EntityCategory
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bayesian occupancy: exposes exactly one diagnostic-category binary_sensor
 * (device_class occupancy) backed by [PresenceFusionEngine]. Push-style sources
 * (face, frame motion, voiceprint, proximity) report from their own hot paths;
 * the flow-backed touch source is collected here. Fused state is shared
 * between Avas as an `occupied=` field on the existing 19848 identity beacon
 * (via [AvaVoiceDiscovery]); [PresenceMesh] distills those peer beacons into
 * the house context that shapes each room's Bayesian prior.
 */
class VoiceSatelliteOccupancy(
    private val context: Context,
    private val scope: CoroutineScope,
    private val device: EspHomeDevice,
    private val experimentalSettingsStore: ExperimentalSettingsStore,
) {
    private var entity: BinarySensorEntity? = null
    private val jobs = mutableListOf<Job>()
    private var vibrationListener: SensorEventListener? = null

    companion object {
        private const val OBJECT_ID = "bayesian_occupancy"
        private const val TICK_MS = 1_000L

        /** One hard knock this strong (m/s², vs. per-axis baseline) fires alone. */
        private const val VIBRATION_JOLT_STRONG_MS2 = 0.35f

        /** Gentler waves — a hand shake, a wobbling table — count toward a burst. */
        private const val VIBRATION_JOLT_GENTLE_MS2 = 0.12f

        /** Gentle hits needed inside the window to count as a deliberate shake. */
        private const val VIBRATION_SHAKE_HITS = 3

        /** Sliding window for the shake test. */
        private const val VIBRATION_SHAKE_WINDOW_MS = 900L

        /** One bump sampled twice must not count twice: minimum hit spacing. */
        private const val VIBRATION_SHAKE_GAP_MS = 80L

        /** Slow EMA: gravity, mounting angle and sensor drift get absorbed. */
        private const val VIBRATION_BASELINE_ALPHA = 0.05f
    }

    fun init(settings: ExperimentalSettings) {
        if (!settings.occupancyEnabled) return
        if (entity == null) {
            entity = BinarySensorEntity(
                key = OBJECT_ID.hashCode(),
                name = context.getString(R.string.entity_bayesian_occupancy),
                objectId = OBJECT_ID,
                deviceClass = "occupancy",
                icon = "mdi:motion-sensor",
                getState = PresenceFusionEngine.occupied,
                entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC,
            )
            device.addEntity(entity!!)
        }
        applyConfig(settings)

        jobs += scope.launch {
            experimentalSettingsStore.getFlow().collect { applyConfig(it) }
        }
        jobs += scope.launch {
            ScreenTouchSensor.touched.collect { touched ->
                PresenceFusionEngine.report(PresenceFusionEngine.Source.TOUCH, touched)
            }
        }
        // Sole recompute driver: source reports are bare timestamp marks, so the
        // wake/audio/camera hot paths never pay for the Bayesian math. The same
        // tick refreshes the house prior from peer beacons; our own verdict rides
        // the regular 5s identity beacon, with an immediate push on every flip.
        jobs += scope.launch {
            var lastAdvertised: Boolean? = null
            while (isActive) {
                delay(TICK_MS)
                PresenceFusionEngine.setHouseContext(PresenceMesh.houseActive())
                PresenceFusionEngine.recompute()
                val occupied = PresenceFusionEngine.occupied.value
                if (occupied != lastAdvertised) {
                    lastAdvertised = occupied
                    AvaVoiceDiscovery.setAdvertisedOccupancy(occupied)
                    AvaVoiceDiscovery.refreshBeaconNow()
                }
            }
        }
    }

    private fun applyConfig(settings: ExperimentalSettings) {
        val sources = buildSet {
            if (settings.occupancyUseFace) add(PresenceFusionEngine.Source.FACE)
            if (settings.occupancyUseMotion) add(PresenceFusionEngine.Source.MOTION)
            if (settings.occupancyUseTouch) add(PresenceFusionEngine.Source.TOUCH)
            if (settings.occupancyUseProximity) add(PresenceFusionEngine.Source.PROXIMITY)
            if (settings.occupancyUseVoiceprint) add(PresenceFusionEngine.Source.VOICEPRINT)
            if (settings.occupancyUseVibration) add(PresenceFusionEngine.Source.VIBRATION)
        }
        ScreensaverFrameMotionProbe.setOccupancyConsumer(
            settings.occupancyEnabled && settings.occupancyUseMotion,
        )
        if (settings.occupancyEnabled && settings.occupancyUseVibration) {
            startVibrationProbe()
        } else {
            stopVibrationProbe()
        }
        PresenceFusionEngine.configure(
            PresenceFusionEngine.Config(
                enabledSources = sources,
                thresholdPercent = settings.resolvedOccupancyThresholdPercent(),
                leaveSeconds = settings.resolvedOccupancyLeaveSeconds(),
            ),
        )
    }

    /**
     * Accelerometer jolt probe, two-tier. Deviation is measured per axis
     * against a slow baseline — unlike the magnitude, per-axis deltas also see
     * rotation (gravity re-projects between axes), so a gentle shake or tilt
     * registers, not just a hard knock. A strong jolt fires alone; gentle ones
     * must repeat [VIBRATION_SHAKE_HITS] times inside the window, like a shake
     * test, so a single sensor blip stays silent. SENSOR_DELAY_GAME (~20ms)
     * samples hand-shake oscillation (2–5 Hz) without aliasing; the callback
     * only marks a timestamp and accelerometers draw microamps.
     */
    private fun startVibrationProbe() {
        if (vibrationListener != null) return
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val listener = object : SensorEventListener {
            private var baseX = Float.NaN
            private var baseY = 0f
            private var baseZ = 0f
            private var shakeHits = 0
            private var shakeWindowStartMs = 0L
            private var lastHitMs = 0L

            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                if (baseX.isNaN()) {
                    baseX = x
                    baseY = y
                    baseZ = z
                    return
                }
                val dx = x - baseX
                val dy = y - baseY
                val dz = z - baseZ
                val jolt = sqrt(dx * dx + dy * dy + dz * dz)
                baseX += dx * VIBRATION_BASELINE_ALPHA
                baseY += dy * VIBRATION_BASELINE_ALPHA
                baseZ += dz * VIBRATION_BASELINE_ALPHA
                when {
                    jolt >= VIBRATION_JOLT_STRONG_MS2 -> {
                        shakeHits = 0
                        PresenceFusionEngine.reportEvent(PresenceFusionEngine.Source.VIBRATION)
                    }
                    jolt >= VIBRATION_JOLT_GENTLE_MS2 -> {
                        val nowMs = event.timestamp / 1_000_000
                        if (nowMs - shakeWindowStartMs > VIBRATION_SHAKE_WINDOW_MS) {
                            shakeWindowStartMs = nowMs
                            shakeHits = 0
                        }
                        if (nowMs - lastHitMs >= VIBRATION_SHAKE_GAP_MS) {
                            lastHitMs = nowMs
                            shakeHits++
                            if (shakeHits >= VIBRATION_SHAKE_HITS) {
                                shakeHits = 0
                                PresenceFusionEngine.reportEvent(PresenceFusionEngine.Source.VIBRATION)
                            }
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        vibrationListener = listener
    }

    private fun stopVibrationProbe() {
        val listener = vibrationListener ?: return
        vibrationListener = null
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.unregisterListener(listener)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        entity?.let { device.removeEntity(it) }
        entity = null
        ScreensaverFrameMotionProbe.setOccupancyConsumer(false)
        stopVibrationProbe()
        // Drop the occupied= field from our beacons so peers stop counting us.
        AvaVoiceDiscovery.setAdvertisedOccupancy(null)
        AvaVoiceDiscovery.refreshBeaconNow()
        PresenceMesh.reset()
        PresenceFusionEngine.reset()
    }
}
