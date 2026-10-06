package com.example.ava.settings

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.example.ava.detection.AudioEventCatalog
import com.example.ava.detection.AudioEventDetectionConfig
import com.example.ava.detection.AudioEventDisplayDuration
import com.example.ava.detection.AudioEventSensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException


enum class CameraPosition {
    BACK,   
    FRONT   
}


enum class CameraMode {
    SNAPSHOT,  
    VIDEO      
}

fun ExperimentalSettings.resolvedCameraMode(): CameraMode =
    try {
        CameraMode.valueOf(cameraMode)
    } catch (_: Exception) {
        CameraMode.SNAPSHOT
    }


enum class CameraOrientation {
    AUTO,              
    PORTRAIT,          
    PORTRAIT_FLIP,     
    LANDSCAPE,         
    LANDSCAPE_FLIP     
}


@Serializable
data class ExperimentalSettings(
    val cameraEnabled: Boolean = false,           
    val cameraMode: String = CameraMode.SNAPSHOT.name,  
    val cameraPosition: String = CameraPosition.FRONT.name,
    val cameraOrientation: String = CameraOrientation.AUTO.name,
    val imageSize: Int = 500,  
    val videoFps: Int = 2,        
    val videoResolution: Int = 240,
    val personDetectionEnabled: Boolean = false,
    val faceBoxEnabled: Boolean = false,
    
    val environmentSensorEnabled: Boolean = false,
    val sensorUpdateInterval: Int = 35,  
    val environmentLightSensorEnabled: Boolean = true,
    val environmentMagneticSensorEnabled: Boolean = false,
    
    val proximitySensorEnabled: Boolean = false,
    val proximitySendToHass: Boolean = false,  
    val proximityHassUpdateInterval: Int = 20,
    val proximityWakeScreen: Boolean = true,  
    val proximityAwayDelay: Int = 30,  
    val proximityAutoUnlock: Boolean = false,
    
    val screenPowerControlHaDisplayEnabled: Boolean = true,
    val screenBrightnessEnabled: Boolean = false,
    val screenTouchSensorEnabled: Boolean = false,
    val screenTouchAwayDelay: Int = com.example.ava.sensor.ScreenTouchSensor.DEFAULT_AWAY_DELAY_SECONDS,
    val screenGestureEnabled: Boolean = false,
    val screenGestureSpatialEnabled: Boolean = false,
    val screenGestureDigitsEnabled: Boolean = false,
    val screenGestureGeometryEnabled: Boolean = false,
    val screenGestureSpatialTokens: Set<String> = emptySet(),
    val screenGestureDigitTokens: Set<String> = emptySet(),
    val screenGestureGeometryTokens: Set<String> = emptySet(),
    
    val forceOrientationEnabled: Boolean = false,
    val forceOrientationMode: String = "portrait",  
    
    val displaySizeEnabled: Boolean = false,
    val displaySizeScale: Float = 1.0f,  
    
    val diagnosticSensorEnabled: Boolean = false,
    val diagnosticWifiEnabled: Boolean = false,
    val diagnosticIpEnabled: Boolean = false,
    val diagnosticStorageEnabled: Boolean = false,
    val diagnosticMemoryEnabled: Boolean = false,
    val diagnosticUptimeEnabled: Boolean = false,
    val diagnosticKillAppEnabled: Boolean = false,
    val diagnosticRebootEnabled: Boolean = false,
    val diagnosticBatteryLevelEnabled: Boolean = false,
    val diagnosticBatteryVoltageEnabled: Boolean = false,
    val diagnosticChargingStatusEnabled: Boolean = false,
    val diagnosticMusicActiveEnabled: Boolean = false,
    val diagnosticLastUsedAppEnabled: Boolean = false,
    val diagnosticBluetoothEnabled: Boolean = false,
    val diagnosticNetworkTypeEnabled: Boolean = false,
    /** Publish the system-bars select to Home Assistant. Default off. */
    val systemBarsHaEnabled: Boolean = false,
    
    val intentLauncherEnabled: Boolean = false,
    val intentLauncherHaDisplayEnabled: Boolean = false,
    /** Pause or control the current media session in another app. Default off. */
    val mediaKeyEnabled: Boolean = false,
    /** Publish pause_media / media_key to Home Assistant. Default off. */
    val mediaKeyHaDisplayEnabled: Boolean = false,
    /** Inbound ADB / local-broadcast control of Ava. Gecko host-bridge actions stay available. */
    val adbControlEnabled: Boolean = true,

    /**
     * Opt-in **cluster agent** (HTTP API :8888 + UDP `clusterPort`).
     * Enables remote screen/shell/settings without serving the website UI.
     * Default off. UDP Ava discovery stays independent.
     */
    val clusterManagementEnabled: Boolean = false,
    /**
     * Opt-in **website console** (static SPA on :8888). Default off — enable on the
     * management entry device only. Implies [clusterManagementEnabled] when turned on.
     */
    val webConsoleEnabled: Boolean = false,
    /**
     * Fleet **access password**. Users type this in the website console; the same string
     * is sent as `X-Ava-Fleet-Token`. Empty = open LAN (compat). Default on first enable: "1234".
     */
    val clusterAccessToken: String = "",

    /**
     * When on, the Logs screen and authenticated `GET /v1/logs` expose incident
     * records. Recording itself stays on (one append per event). Default off.
     */
    val deviceIncidentLogEnabled: Boolean = false,
    /**
     * Main-thread stall probe. Default off. A real restart still requires
     * [com.example.ava.settings.PlayerSettings.enableCrashSelfHeal] so the
     * heartbeat alarm remains as a second net.
     */
    val mainThreadStallWatchdogEnabled: Boolean = false,
    
    val microphoneVolume: Float = 1.0f,
    /** 一呼百应: LAN arbiter so only one nearby Ava answers the same wake. */
    val multiDeviceArbiterEnabled: Boolean = true,
    
    /** Sync dark mode state to Home Assistant as a switch entity. */
    val syncDarkModeToHass: Boolean = false,

    /** Microsoft Clarity install count. Default on; off from the main-settings handle. */
    val clarityEnabled: Boolean = true,

    val audioEventDetectionEnabled: Boolean = false,
    /** Subset of [AudioEventCatalog.ALL_LABELS]; fresh install defaults to speech only. */
    val audioEventMonitoredLabels: Set<String> = AudioEventCatalog.DEFAULT_MONITORED_LABELS,
    val audioEventSensitivity: String = AudioEventSensitivity.BALANCED.name,
    /** How long the HA audio_event sensor keeps a label before returning to idle. */
    val audioEventDisplaySeconds: Int = AudioEventDisplayDuration.DEFAULT_SECONDS,

    /** Bayesian occupancy fusion. Off by default; publishes occupancy entities when on. */
    val occupancyEnabled: Boolean = false,
    val occupancyUseFace: Boolean = true,
    val occupancyUseMotion: Boolean = true,
    val occupancyUseTouch: Boolean = true,
    val occupancyUseProximity: Boolean = true,
    val occupancyUseVoiceprint: Boolean = true,
    val occupancyUseVibration: Boolean = true,
    val occupancyThresholdPercent: Int = OCCUPANCY_THRESHOLD_DEFAULT,
    val occupancyLeaveSeconds: Int = OCCUPANCY_LEAVE_DEFAULT,
) {
    companion object {
        val DEFAULT = ExperimentalSettings()

        const val OCCUPANCY_THRESHOLD_MIN = 50
        const val OCCUPANCY_THRESHOLD_MAX = 95
        const val OCCUPANCY_THRESHOLD_STEP = 5
        const val OCCUPANCY_THRESHOLD_DEFAULT = 85

        /** Detented ladder: short holds need fine grain, long holds only need coarse jumps. */
        val OCCUPANCY_LEAVE_LADDER = listOf(3, 5, 10, 15, 30, 45, 60, 90, 120, 180)
        const val OCCUPANCY_LEAVE_DEFAULT = 15

        fun coerceOccupancyThresholdPercent(value: Int): Int {
            val clamped = value.coerceIn(OCCUPANCY_THRESHOLD_MIN, OCCUPANCY_THRESHOLD_MAX)
            val offset = clamped - OCCUPANCY_THRESHOLD_MIN
            val snapped = ((offset + OCCUPANCY_THRESHOLD_STEP / 2) / OCCUPANCY_THRESHOLD_STEP) *
                OCCUPANCY_THRESHOLD_STEP + OCCUPANCY_THRESHOLD_MIN
            return snapped.coerceIn(OCCUPANCY_THRESHOLD_MIN, OCCUPANCY_THRESHOLD_MAX)
        }

        fun coerceOccupancyLeaveSeconds(value: Int): Int =
            OCCUPANCY_LEAVE_LADDER.minByOrNull { kotlin.math.abs(it - value) }
                ?: OCCUPANCY_LEAVE_DEFAULT

        fun occupancyLeaveLadderIndex(seconds: Int): Int =
            OCCUPANCY_LEAVE_LADDER.indexOf(coerceOccupancyLeaveSeconds(seconds))
                .coerceAtLeast(0)
    }

    fun resolvedOccupancyThresholdPercent(): Int =
        coerceOccupancyThresholdPercent(occupancyThresholdPercent)

    fun resolvedOccupancyLeaveSeconds(): Int =
        coerceOccupancyLeaveSeconds(occupancyLeaveSeconds)

    fun resolvedAudioEventMonitoredLabels(): Set<String> =
        AudioEventCatalog.sanitizeMonitoredLabels(audioEventMonitoredLabels)

    fun resolvedAudioEventSensitivity(): AudioEventSensitivity =
        AudioEventSensitivity.fromStored(audioEventSensitivity)

    fun resolvedAudioEventDisplaySeconds(): Int =
        AudioEventDisplayDuration.coerceSeconds(audioEventDisplaySeconds)

    fun toAudioEventDetectionConfig(): AudioEventDetectionConfig = AudioEventDetectionConfig(
        enabled = audioEventDetectionEnabled,
        monitoredLabels = resolvedAudioEventMonitoredLabels(),
        sensitivity = resolvedAudioEventSensitivity(),
        eventDisplayMs = AudioEventDisplayDuration.toMillis(resolvedAudioEventDisplaySeconds()),
    )
}


object ExperimentalSettingsSerializer : Serializer<ExperimentalSettings> {
    override val defaultValue = ExperimentalSettings.DEFAULT

    // encodeDefaults=true so webConsoleEnabled=false is persisted explicitly.
    // Otherwise agent-only writes omit the key and cold-start migration wrongly
    // treats "missing key + agent on" as website console on.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun readFrom(input: InputStream): ExperimentalSettings {
        val bytes = input.readBytes()
        return try {
            val text = bytes.decodeToString()
            val decoded = json.decodeFromString(ExperimentalSettings.serializer(), text)
            // Pre-split installs used clusterManagementEnabled for the full website.
            // Only migrate when the new key is truly absent (legacy file). New writes
            // always include webConsoleEnabled thanks to encodeDefaults.
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (obj != null && !obj.containsKey("webConsoleEnabled") &&
                obj["clusterManagementEnabled"]?.jsonPrimitive?.booleanOrNull == true
            ) {
                decoded.copy(webConsoleEnabled = true)
            } else {
                decoded
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Returning DEFAULT here silently masked corruption, and because this
            // serializer writes with encodeDefaults=true the next write clobbered
            // the user's file. Preserve the bytes and let the corruption handler
            // own the replacement instead.
            SettingsQuarantine.save("ExperimentalSettings", bytes, e)
            throw CorruptionException("Unable to read ExperimentalSettings", e)
        }
    }

    override suspend fun writeTo(t: ExperimentalSettings, output: OutputStream) {
        output.write(json.encodeToString(ExperimentalSettings.serializer(), t).encodeToByteArray())
    }
}

val Context.experimentalSettingsDataStore: DataStore<ExperimentalSettings> by dataStore(
    fileName = "experimental_settings.json",
    serializer = ExperimentalSettingsSerializer,
    corruptionHandler = defaultCorruptionHandler(ExperimentalSettings.DEFAULT),
)


class ExperimentalSettingsStore(context: Context) : SettingsStoreImpl<ExperimentalSettings>(
    context.experimentalSettingsDataStore,
    ExperimentalSettings.DEFAULT
) {
    private val appContext = context.applicationContext
    
    
    fun hasCamera(): Boolean = com.example.ava.utils.DeviceCapabilities.hasCamera(appContext)
    
    
    fun hasBackCamera(): Boolean = com.example.ava.utils.DeviceCapabilities.hasBackCamera(appContext)
    
    
    fun hasFrontCamera(): Boolean = com.example.ava.utils.DeviceCapabilities.hasFrontCamera(appContext)
    
    
    val cameraEnabled: Flow<Boolean> = getFlow().map { it.cameraEnabled }
    val cameraMode: Flow<CameraMode> = getFlow().map {
        try {
            CameraMode.valueOf(it.cameraMode)
        } catch (e: Exception) {
            CameraMode.SNAPSHOT
        }
    }
    val cameraPosition: Flow<CameraPosition> = getFlow().map { 
        try {
            CameraPosition.valueOf(it.cameraPosition)
        } catch (e: Exception) {
            CameraPosition.FRONT
        }
    }
    val cameraOrientation: Flow<CameraOrientation> = getFlow().map { 
        try {
            CameraOrientation.valueOf(it.cameraOrientation)
        } catch (e: Exception) {
            CameraOrientation.AUTO
        }
    }
    val imageSize: Flow<Int> = getFlow().map { it.imageSize }
    val videoFps: Flow<Int> = getFlow().map { it.videoFps }
    val videoResolution: Flow<Int> = getFlow().map { it.videoResolution }
    
    suspend fun setCameraEnabled(enabled: Boolean) {
        if (enabled && com.example.ava.mods.ModCameraStreamBridge.isActive(appContext)) {
            android.util.Log.i(
                "ExperimentalSettings",
                "Ignoring camera enable — camera-stream mod owns the camera",
            )
            update { it.copy(cameraEnabled = false) }
            return
        }
        update { it.copy(cameraEnabled = enabled) }
    }
    
    suspend fun setCameraMode(mode: CameraMode) {
        update { it.copy(cameraMode = mode.name) }
    }
    
    suspend fun setCameraPosition(position: CameraPosition) {
        update { it.copy(cameraPosition = position.name) }
    }
    
    suspend fun setCameraOrientation(orientation: CameraOrientation) {
        update { it.copy(cameraOrientation = orientation.name) }
    }
    
    suspend fun setImageSize(size: Int) {
        
        update { it.copy(imageSize = if (size == 0) 0 else size.coerceIn(100, 2000)) }
    }
    
    suspend fun setVideoFps(fps: Int) {
        update { it.copy(videoFps = fps.coerceIn(1, 15)) }
    }
    
    suspend fun setVideoResolution(resolution: Int) {
        update { it.copy(videoResolution = resolution.coerceIn(240, 720)) }
    }
    
    val personDetectionEnabled: Flow<Boolean> = getFlow().map { it.personDetectionEnabled }
    
    suspend fun setPersonDetectionEnabled(enabled: Boolean) {
        update { it.copy(personDetectionEnabled = enabled) }
    }
    
    val faceBoxEnabled: Flow<Boolean> = getFlow().map { it.faceBoxEnabled }
    
    suspend fun setFaceBoxEnabled(enabled: Boolean) {
        update { it.copy(faceBoxEnabled = enabled) }
    }
    
    val environmentSensorEnabled: Flow<Boolean> = getFlow().map { it.environmentSensorEnabled }
    val sensorUpdateInterval: Flow<Int> = getFlow().map { it.sensorUpdateInterval }
    val environmentLightSensorEnabled: Flow<Boolean> = getFlow().map { it.environmentLightSensorEnabled }
    val environmentMagneticSensorEnabled: Flow<Boolean> = getFlow().map { it.environmentMagneticSensorEnabled }
    
    suspend fun setEnvironmentSensorEnabled(enabled: Boolean) {
        update { it.copy(environmentSensorEnabled = enabled) }
    }
    
    suspend fun setSensorUpdateInterval(interval: Int) {
        update { it.copy(sensorUpdateInterval = interval.coerceIn(5, 60)) }
    }

    suspend fun setEnvironmentLightSensorEnabled(enabled: Boolean) {
        update { it.copy(environmentLightSensorEnabled = enabled) }
    }

    suspend fun setEnvironmentMagneticSensorEnabled(enabled: Boolean) {
        update { it.copy(environmentMagneticSensorEnabled = enabled) }
    }
    
    
    val proximitySensorEnabled: Flow<Boolean> = getFlow().map { it.proximitySensorEnabled }
    val proximitySendToHass: Flow<Boolean> = getFlow().map { it.proximitySendToHass }
    val proximityHassUpdateInterval: Flow<Int> = getFlow().map { it.proximityHassUpdateInterval }
    val proximityWakeScreen: Flow<Boolean> = getFlow().map { it.proximityWakeScreen }
    val proximityAwayDelay: Flow<Int> = getFlow().map { it.proximityAwayDelay }
    val proximityAutoUnlock: Flow<Boolean> = getFlow().map { it.proximityAutoUnlock }
    
    suspend fun setProximitySensorEnabled(enabled: Boolean) {
        update { it.copy(proximitySensorEnabled = enabled) }
    }
    
    suspend fun setProximitySendToHass(enabled: Boolean) {
        update { it.copy(proximitySendToHass = enabled) }
    }

    suspend fun setProximityHassUpdateInterval(interval: Int) {
        update { it.copy(proximityHassUpdateInterval = interval.coerceIn(5, 120)) }
    }
    
    suspend fun setProximityWakeScreen(enabled: Boolean) {
        update { it.copy(proximityWakeScreen = enabled) }
    }
    
    suspend fun setProximityAwayDelay(delay: Int) {
        update { it.copy(proximityAwayDelay = delay.coerceIn(10, 120)) }
    }
    
    suspend fun setProximityAutoUnlock(enabled: Boolean) {
        update { it.copy(proximityAutoUnlock = enabled) }
    }
    
    
    val screenPowerControlHaDisplayEnabled: Flow<Boolean> = getFlow().map { it.screenPowerControlHaDisplayEnabled }
    val screenBrightnessEnabled: Flow<Boolean> = getFlow().map { it.screenBrightnessEnabled }
    val screenTouchSensorEnabled: Flow<Boolean> = getFlow().map { it.screenTouchSensorEnabled }
    val screenTouchAwayDelay: Flow<Int> = getFlow().map { it.screenTouchAwayDelay }
    val screenGestureEnabled: Flow<Boolean> = getFlow().map { it.screenGestureEnabled }

    suspend fun setScreenPowerControlHaDisplayEnabled(enabled: Boolean) {
        update { it.copy(screenPowerControlHaDisplayEnabled = enabled) }
    }

    suspend fun setScreenBrightnessEnabled(enabled: Boolean) {
        update { it.copy(screenBrightnessEnabled = enabled) }
    }

    suspend fun setScreenTouchSensorEnabled(enabled: Boolean) {
        update { it.copy(screenTouchSensorEnabled = enabled) }
    }

    suspend fun setScreenTouchAwayDelay(delay: Int) {
        update {
            it.copy(
                screenTouchAwayDelay = com.example.ava.sensor.ScreenTouchSensor.clampAwayDelaySeconds(delay),
            )
        }
    }

    suspend fun setScreenGestureEnabled(enabled: Boolean) {
        update { current ->
            current.copy(
                screenGestureEnabled = enabled,
                screenGestureSpatialEnabled = if (enabled) current.screenGestureSpatialEnabled else false,
                screenGestureDigitsEnabled = if (enabled) current.screenGestureDigitsEnabled else false,
                screenGestureGeometryEnabled = if (enabled) current.screenGestureGeometryEnabled else false,
            )
        }
    }

    suspend fun setScreenGestureSpatialEnabled(enabled: Boolean) {
        update { current ->
            current.copy(
                screenGestureSpatialEnabled = enabled,
                screenGestureSpatialTokens = if (enabled && current.screenGestureSpatialTokens.isEmpty()) {
                    com.example.ava.sensor.ScreenGestureCatalog.DEFAULT_SPATIAL_IDS
                } else {
                    current.screenGestureSpatialTokens
                },
            )
        }
    }

    suspend fun setScreenGestureDigitsEnabled(enabled: Boolean) {
        update { current ->
            current.copy(
                screenGestureDigitsEnabled = enabled,
                screenGestureDigitTokens = if (enabled && current.screenGestureDigitTokens.isEmpty()) {
                    com.example.ava.sensor.ScreenGestureCatalog.ALL_DIGIT_IDS
                } else {
                    current.screenGestureDigitTokens
                },
            )
        }
    }

    suspend fun setScreenGestureGeometryEnabled(enabled: Boolean) {
        update { current ->
            current.copy(
                screenGestureGeometryEnabled = enabled,
                screenGestureGeometryTokens = if (enabled && current.screenGestureGeometryTokens.isEmpty()) {
                    com.example.ava.sensor.ScreenGestureCatalog.ALL_GEOMETRY_IDS
                } else {
                    current.screenGestureGeometryTokens
                },
            )
        }
    }

    suspend fun setScreenGestureCategoryTokens(
        category: com.example.ava.sensor.ScreenGestureCategory,
        tokens: Set<String>,
    ) {
        val clean = com.example.ava.sensor.ScreenGestureCatalog.sanitize(tokens, category)
        update { current ->
            when (category) {
                com.example.ava.sensor.ScreenGestureCategory.Spatial -> current.copy(
                    screenGestureSpatialTokens = clean,
                    screenGestureSpatialEnabled = clean.isNotEmpty(),
                )
                com.example.ava.sensor.ScreenGestureCategory.Digits -> current.copy(
                    screenGestureDigitTokens = clean,
                    screenGestureDigitsEnabled = clean.isNotEmpty(),
                )
                com.example.ava.sensor.ScreenGestureCategory.Geometry -> current.copy(
                    screenGestureGeometryTokens = clean,
                    screenGestureGeometryEnabled = clean.isNotEmpty(),
                )
            }
        }
    }
    
    
    val forceOrientationEnabled: Flow<Boolean> = getFlow().map { it.forceOrientationEnabled }
    val forceOrientationMode: Flow<String> = getFlow().map { it.forceOrientationMode }
    
    suspend fun setForceOrientationEnabled(enabled: Boolean) {
        update { it.copy(forceOrientationEnabled = enabled) }
    }
    
    suspend fun setForceOrientationMode(mode: String) {
        update { it.copy(forceOrientationMode = mode) }
    }
    
    
    val displaySizeEnabled: Flow<Boolean> = getFlow().map { it.displaySizeEnabled }
    val displaySizeScale: Flow<Float> = getFlow().map { it.displaySizeScale }
    
    suspend fun setDisplaySizeEnabled(enabled: Boolean) {
        update { it.copy(displaySizeEnabled = enabled) }
    }
    
    suspend fun setDisplaySizeScale(scale: Float) {
        update { it.copy(displaySizeScale = scale.coerceIn(0.5f, 1.0f)) }
    }
    
    
    val diagnosticSensorEnabled: Flow<Boolean> = getFlow().map { it.diagnosticSensorEnabled }
    val diagnosticWifiEnabled: Flow<Boolean> = getFlow().map { it.diagnosticWifiEnabled }
    val diagnosticIpEnabled: Flow<Boolean> = getFlow().map { it.diagnosticIpEnabled }
    val diagnosticStorageEnabled: Flow<Boolean> = getFlow().map { it.diagnosticStorageEnabled }
    val diagnosticMemoryEnabled: Flow<Boolean> = getFlow().map { it.diagnosticMemoryEnabled }
    val diagnosticUptimeEnabled: Flow<Boolean> = getFlow().map { it.diagnosticUptimeEnabled }
    
    suspend fun setDiagnosticSensorEnabled(enabled: Boolean) {
        update { it.copy(diagnosticSensorEnabled = enabled) }
    }
    
    suspend fun setDiagnosticWifiEnabled(enabled: Boolean) {
        update { it.copy(diagnosticWifiEnabled = enabled) }
    }
    
    suspend fun setDiagnosticIpEnabled(enabled: Boolean) {
        update { it.copy(diagnosticIpEnabled = enabled) }
    }
    
    suspend fun setDiagnosticStorageEnabled(enabled: Boolean) {
        update { it.copy(diagnosticStorageEnabled = enabled) }
    }
    
    suspend fun setDiagnosticMemoryEnabled(enabled: Boolean) {
        update { it.copy(diagnosticMemoryEnabled = enabled) }
    }
    
    suspend fun setDiagnosticUptimeEnabled(enabled: Boolean) {
        update { it.copy(diagnosticUptimeEnabled = enabled) }
    }
    
    val diagnosticKillAppEnabled: Flow<Boolean> = getFlow().map { it.diagnosticKillAppEnabled }
    val diagnosticRebootEnabled: Flow<Boolean> = getFlow().map { it.diagnosticRebootEnabled }
    
    suspend fun setDiagnosticKillAppEnabled(enabled: Boolean) {
        update { it.copy(diagnosticKillAppEnabled = enabled) }
    }
    
    suspend fun setDiagnosticRebootEnabled(enabled: Boolean) {
        update { it.copy(diagnosticRebootEnabled = enabled) }
    }
    
    val diagnosticBatteryLevelEnabled: Flow<Boolean> = getFlow().map { it.diagnosticBatteryLevelEnabled }
    val diagnosticBatteryVoltageEnabled: Flow<Boolean> = getFlow().map { it.diagnosticBatteryVoltageEnabled }
    
    suspend fun setDiagnosticBatteryLevelEnabled(enabled: Boolean) {
        update { it.copy(diagnosticBatteryLevelEnabled = enabled) }
    }
    
    suspend fun setDiagnosticBatteryVoltageEnabled(enabled: Boolean) {
        update { it.copy(diagnosticBatteryVoltageEnabled = enabled) }
    }
    
    val diagnosticChargingStatusEnabled: Flow<Boolean> = getFlow().map { it.diagnosticChargingStatusEnabled }
    
    suspend fun setDiagnosticChargingStatusEnabled(enabled: Boolean) {
        update { it.copy(diagnosticChargingStatusEnabled = enabled) }
    }

    val diagnosticMusicActiveEnabled: Flow<Boolean> = getFlow().map { it.diagnosticMusicActiveEnabled }
    val diagnosticLastUsedAppEnabled: Flow<Boolean> = getFlow().map { it.diagnosticLastUsedAppEnabled }
    val diagnosticBluetoothEnabled: Flow<Boolean> = getFlow().map { it.diagnosticBluetoothEnabled }
    val diagnosticNetworkTypeEnabled: Flow<Boolean> = getFlow().map { it.diagnosticNetworkTypeEnabled }

    suspend fun setDiagnosticMusicActiveEnabled(enabled: Boolean) {
        update { it.copy(diagnosticMusicActiveEnabled = enabled) }
    }

    suspend fun setDiagnosticLastUsedAppEnabled(enabled: Boolean) {
        update { it.copy(diagnosticLastUsedAppEnabled = enabled) }
    }

    suspend fun setDiagnosticBluetoothEnabled(enabled: Boolean) {
        update { it.copy(diagnosticBluetoothEnabled = enabled) }
    }

    suspend fun setDiagnosticNetworkTypeEnabled(enabled: Boolean) {
        update { it.copy(diagnosticNetworkTypeEnabled = enabled) }
    }

    val systemBarsHaEnabled: Flow<Boolean> = getFlow().map { it.systemBarsHaEnabled }

    suspend fun setSystemBarsHaEnabled(enabled: Boolean) {
        update { it.copy(systemBarsHaEnabled = enabled) }
    }
    
    val intentLauncherEnabled: Flow<Boolean> = getFlow().map { it.intentLauncherEnabled }
    val intentLauncherHaDisplayEnabled: Flow<Boolean> = getFlow().map { it.intentLauncherHaDisplayEnabled }
    val mediaKeyEnabled: Flow<Boolean> = getFlow().map { it.mediaKeyEnabled }
    val mediaKeyHaDisplayEnabled: Flow<Boolean> = getFlow().map { it.mediaKeyHaDisplayEnabled }
    val adbControlEnabled: Flow<Boolean> = getFlow().map { it.adbControlEnabled }
    
    suspend fun setIntentLauncherEnabled(enabled: Boolean) {
        update { it.copy(intentLauncherEnabled = enabled) }
    }
    
    suspend fun setIntentLauncherHaDisplayEnabled(enabled: Boolean) {
        update { it.copy(intentLauncherHaDisplayEnabled = enabled) }
    }

    suspend fun setMediaKeyEnabled(enabled: Boolean) {
        update { it.copy(mediaKeyEnabled = enabled) }
    }

    suspend fun setMediaKeyHaDisplayEnabled(enabled: Boolean) {
        update { it.copy(mediaKeyHaDisplayEnabled = enabled) }
    }

    suspend fun setAdbControlEnabled(enabled: Boolean) {
        update { it.copy(adbControlEnabled = enabled) }
    }

    val clusterManagementEnabled: Flow<Boolean> = getFlow().map { it.clusterManagementEnabled }

    suspend fun setClusterManagementEnabled(enabled: Boolean) {
        update {
            if (enabled) {
                val token = normalizeClusterWireToken(it.clusterAccessToken)
                it.copy(clusterManagementEnabled = true, clusterAccessToken = token)
            } else {
                // Agent off ⇒ website off (website needs the API).
                it.copy(clusterManagementEnabled = false, webConsoleEnabled = false)
            }
        }
    }

    val webConsoleEnabled: Flow<Boolean> = getFlow().map { it.webConsoleEnabled }

    suspend fun setWebConsoleEnabled(enabled: Boolean) {
        update {
            if (enabled) {
                val token = normalizeClusterWireToken(it.clusterAccessToken)
                // Website implies agent.
                it.copy(
                    webConsoleEnabled = true,
                    clusterManagementEnabled = true,
                    clusterAccessToken = token,
                )
            } else {
                it.copy(webConsoleEnabled = false)
            }
        }
    }

    val clusterAccessToken: Flow<String> = getFlow().map { it.clusterAccessToken }

    suspend fun setClusterAccessToken(token: String) {
        update { it.copy(clusterAccessToken = normalizeClusterWireToken(token)) }
    }

    suspend fun setDeviceIncidentLogEnabled(enabled: Boolean) {
        update { it.copy(deviceIncidentLogEnabled = enabled) }
    }

    suspend fun setMainThreadStallWatchdogEnabled(enabled: Boolean) {
        update { it.copy(mainThreadStallWatchdogEnabled = enabled) }
    }

    companion object {
        /** Plain password users type in the browser console. */
        const val DEFAULT_CLUSTER_PASSWORD = "1234"

        /**
         * Fixed 16-char Base64 wire token for [DEFAULT_CLUSTER_PASSWORD].
         * `1234` → `MTIzNAAAAAAAAAAA` (see FleetPasswordCodec).
         */
        const val DEFAULT_CLUSTER_TOKEN = "MTIzNAAAAAAAAAAA"

        @Deprecated("Prefer DEFAULT_CLUSTER_TOKEN")
        fun newClusterAccessToken(): String = DEFAULT_CLUSTER_TOKEN

        private fun normalizeClusterWireToken(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) return DEFAULT_CLUSTER_TOKEN
            // Accept plain password or already-encoded 16-char wire token.
            return com.example.ava.fleet.FleetPasswordCodec.toWireToken(t)
        }
    }
    
    val microphoneVolume: Flow<Float> = getFlow().map { it.microphoneVolume }
    
    suspend fun setMicrophoneVolume(volume: Float) {
        update { it.copy(microphoneVolume = volume.coerceIn(0.0f, 2.0f)) }
    }
    
    val multiDeviceArbiterEnabled: Flow<Boolean> = getFlow().map { it.multiDeviceArbiterEnabled }
    
    suspend fun setMultiDeviceArbiterEnabled(enabled: Boolean) {
        update { it.copy(multiDeviceArbiterEnabled = enabled) }
    }

    val syncDarkModeToHass: Flow<Boolean> = getFlow().map { it.syncDarkModeToHass }
    
    suspend fun setSyncDarkModeToHass(enabled: Boolean) {
        update { it.copy(syncDarkModeToHass = enabled) }
    }

    val clarityEnabled: Flow<Boolean> = getFlow().map { it.clarityEnabled }

    suspend fun setClarityEnabled(enabled: Boolean) {
        update { it.copy(clarityEnabled = enabled) }
    }

    val audioEventDetectionEnabled: Flow<Boolean> = getFlow().map { it.audioEventDetectionEnabled }

    suspend fun setAudioEventDetectionEnabled(enabled: Boolean) {
        update { it.copy(audioEventDetectionEnabled = enabled) }
    }

    suspend fun setAudioEventMonitoredLabels(labels: Set<String>) {
        update { it.copy(audioEventMonitoredLabels = AudioEventCatalog.sanitizeMonitoredLabels(labels)) }
    }

    suspend fun setAudioEventSensitivity(sensitivity: AudioEventSensitivity) {
        update { it.copy(audioEventSensitivity = sensitivity.name) }
    }

    suspend fun setAudioEventDisplaySeconds(seconds: Int) {
        update {
            it.copy(audioEventDisplaySeconds = AudioEventDisplayDuration.coerceSeconds(seconds))
        }
    }

    val occupancyEnabled: Flow<Boolean> = getFlow().map { it.occupancyEnabled }

    suspend fun setOccupancyEnabled(enabled: Boolean) {
        update { it.copy(occupancyEnabled = enabled) }
    }

    suspend fun setOccupancyUseFace(enabled: Boolean) {
        update { it.copy(occupancyUseFace = enabled) }
    }

    suspend fun setOccupancyUseTouch(enabled: Boolean) {
        update { it.copy(occupancyUseTouch = enabled) }
    }

    suspend fun setOccupancyUseProximity(enabled: Boolean) {
        update { it.copy(occupancyUseProximity = enabled) }
    }

    suspend fun setOccupancyUseVoiceprint(enabled: Boolean) {
        update { it.copy(occupancyUseVoiceprint = enabled) }
    }

    suspend fun setOccupancyUseMotion(enabled: Boolean) {
        update { it.copy(occupancyUseMotion = enabled) }
    }

    suspend fun setOccupancyUseVibration(enabled: Boolean) {
        update { it.copy(occupancyUseVibration = enabled) }
    }

    suspend fun setOccupancyThresholdPercent(percent: Int) {
        update {
            it.copy(occupancyThresholdPercent = ExperimentalSettings.coerceOccupancyThresholdPercent(percent))
        }
    }

    suspend fun setOccupancyLeaveSeconds(seconds: Int) {
        update {
            it.copy(occupancyLeaveSeconds = ExperimentalSettings.coerceOccupancyLeaveSeconds(seconds))
        }
    }
}
