package com.example.ava.fleet

import android.content.Context
import android.media.MediaRecorder
import com.example.ava.audio.DeviceAudioProfile
import com.example.ava.detection.AudioEventSensitivity
import com.example.ava.microwakeword.WakeWordProviderFactory
import com.example.ava.settings.CameraMode
import com.example.ava.settings.CameraOrientation
import com.example.ava.settings.CameraPosition
import com.example.ava.settings.MediaOverlayStyle
import com.example.ava.settings.DreamClockFace
import com.example.ava.settings.DreamClockFlipFont
import com.example.ava.settings.DreamClockFlipStyle
import com.example.ava.settings.DreamClockSeason
import com.example.ava.settings.SimpleClockPortraitStyle
import com.example.ava.settings.MicrophoneSettings
import com.example.ava.settings.RecordingPath
import com.example.ava.settings.VoicePrintEnrollmentMode
import com.example.ava.settings.VolumeFollowRule
import com.example.ava.settings.WakeWordEngine
import com.example.ava.voice.VoiceCallVideoQuality
import org.json.JSONArray
import org.json.JSONObject

/**
 * Enumerates setting partitions exactly as [com.example.ava.backup.AvaBackupManager] exports
 * (format ava-backup v1) and annotates each group for the fleet console UI.
 *
 * HA-published switches are the subset of boolean flags whose enabled/disabled state
 * creates or removes an entity visible in Home Assistant (diagnostic sensors, buttons,
 * display switches, experimental toggles that publish ESPHome entities).
 */
object FleetSettingsCatalog {

    enum class Tier { BASIC, ADVANCED }

    data class Partition(
        val key: String,
        val tier: Tier,
    )

    /** Ordered list aligned with Ava settings home + backup export layout. */
    val PARTITIONS: List<Partition> = listOf(
        // Voice Config
        Partition("voice_satellite", Tier.BASIC),
        Partition("voice_channel", Tier.BASIC),
        Partition("microphone", Tier.BASIC),
        Partition("sendspin", Tier.BASIC),
        // Extensions
        Partition("player", Tier.BASIC),
        Partition("notification", Tier.BASIC),
        Partition("quick_entity", Tier.BASIC),
        // Device Controls
        Partition("sidebar", Tier.BASIC),
        Partition("home_lock", Tier.BASIC),
        // Bluetooth
        Partition("bluetooth", Tier.BASIC),
        // Screensaver / Browser
        Partition("screensaver", Tier.ADVANCED),
        Partition("browser", Tier.BASIC),
        Partition("ha", Tier.BASIC),
        Partition("mass_api", Tier.BASIC),
        Partition("update", Tier.ADVANCED),
        Partition("settings_style", Tier.ADVANCED),
        Partition("local_scenes", Tier.BASIC),
        // Advanced (camera / intent / cluster / sensors live here in DataStore)
        Partition("experimental", Tier.ADVANCED),
        // Mod Store — installed mods + config (bidirectional)
        Partition("mods", Tier.ADVANCED),
    )

    private val PARTITION_KEYS: Set<String> = PARTITIONS.map { it.key }.toSet()

    fun isKnownPartition(key: String): Boolean = key in PARTITION_KEYS

    /**
     * Flags in ExperimentalSettings that, when enabled, cause a corresponding HA entity
     * (diagnostic sensor, switch, button, or service) to be published via ESPHome.
     *
     * The map key is the JSON field name inside the `experimental` partition; the value
     * is a short human-readable label for the console.
     */
    val HA_PUBLISHED_EXPERIMENTAL: Map<String, String> = linkedMapOf(
        "screenPowerControlHaDisplayEnabled" to "Screen Power (HA switch)",
        "screenBrightnessEnabled" to "Screen Brightness (HA number)",
        "screenTouchSensorEnabled" to "Screen Touch Event sensor",
        "screenGestureEnabled" to "Screen Gesture sensor",
        "syncDarkModeToHass" to "Dark Mode (HA switch)",
        "diagnosticSensorEnabled" to "Diagnostic Sensors (master)",
        "diagnosticWifiEnabled" to "WiFi Signal sensor",
        "diagnosticIpEnabled" to "Device IP sensor",
        "diagnosticStorageEnabled" to "Storage Free sensor",
        "diagnosticMemoryEnabled" to "Memory Usage sensor",
        "diagnosticUptimeEnabled" to "Device Uptime sensor",
        "diagnosticKillAppEnabled" to "Kill App button",
        "diagnosticRebootEnabled" to "Reboot Device button",
        "diagnosticBatteryLevelEnabled" to "Battery Level sensor",
        "diagnosticBatteryVoltageEnabled" to "Battery Voltage sensor",
        "diagnosticChargingStatusEnabled" to "Charging Status sensor",
        "diagnosticMusicActiveEnabled" to "Music Playing sensor",
        "diagnosticLastUsedAppEnabled" to "Last Used App sensor",
        "diagnosticBluetoothEnabled" to "Bluetooth sensor",
        "diagnosticNetworkTypeEnabled" to "Network Type sensor",
        "systemBarsHaEnabled" to "System bars (HA select)",
        "intentLauncherEnabled" to "Intent Launcher",
        "intentLauncherHaDisplayEnabled" to "Intent Launcher (HA service)",
        "mediaKeyEnabled" to "Pause other media",
        "mediaKeyHaDisplayEnabled" to "Pause other media (HA)",
        "cameraEnabled" to "Camera (snapshot/video)",
        "personDetectionEnabled" to "Person Detection",
        "occupancyEnabled" to "Bayesian Occupancy",
        "environmentSensorEnabled" to "Environment Sensors",
        "proximitySensorEnabled" to "Proximity Sensor",
        "proximitySendToHass" to "Proximity → HA",
        "audioEventDetectionEnabled" to "Audio Event Detection",
    )

    /**
     * Settings outside `experimental` that produce HA entities when enabled.
     * Format: partition.field → label.
     */
    val HA_PUBLISHED_OTHER: Map<String, String> = linkedMapOf(
        "browser.haRemoteUrlEnabled" to "Browser HA Remote URL",
        "browser.enableBrowserDisplay" to "Browser Display (HA switch)",
        "browser.keepScreenOnEnabled" to "Keep Screen On (while browser visible)",
        "browser.advancedControlEnabled" to "Webview Command (HA service)",
        "browser.showScaleSliderInHa" to "Browser Scale slider (HA number)",
        "browser.wsStewardEnabled" to "Browser WebSocket steward",
        "browser.wsStewardEntityTrimEnabled" to "Browser steward entity trim",
        "browser.wsStewardOpaqueCardLines" to "Browser steward opaque card types",
        "browser.wsStewardOpaqueCardLinesCustom" to "Browser steward opaque card types customized",
        "browser.wsStewardFreezeAnimationsEnabled" to "Browser steward freeze animations when dormant",
        "browser.wsStewardPauseMediaEnabled" to "Browser steward pause media when dormant",
        "browser.wsStewardLiteAlwaysEnabled" to "Browser steward always-on lite batching",
        "browser.wvProdMemoryFeaturesEnabled" to "Floating browser save memory in background",
        "browser.wvProdFrameThrottleFeaturesEnabled" to "Floating browser ease refresh load",
        "screensaver.enableHaDisplay" to "Screensaver Display (HA switch)",
        "screensaver.haSwitchTwoWayEnabled" to "Screensaver switch two-way keep",
        "screensaver.screensaverUrlVisible" to "Screensaver URL (HA text)",
        "screensaver.screensaverTimeoutVisible" to "Screensaver Timeout (HA number)",
        "screensaver.enableDawnEntitySlots" to "Dawn entity capsules",
        "screensaver.enableDawnEntityHaSlots" to "Dawn entity HA slots",
        "notification.notificationSceneEnabled" to "Notification Scene (HA select)",
        "quick_entity.enableQuickEntity" to "Quick Entity panel",
        "quick_entity.enableHaSlots" to "Quick Entity HA slots",
        "quick_entity.smartAodEnabled" to "Quick Entity smart AOD",
        "quick_entity.layoutLocked" to "Quick Entity layout lock",
        "player.enableTimerStopButton" to "Stop Alarm button",
        "player.enableManualDismissButton" to "End voice session manually",
        "player.enableWeatherOverlayDisplay" to "Weather Display (HA switch)",
        "player.enableDreamClockDisplay" to "Dream Clock Display (HA switch)",
        "player.enableScreensaverDisplay" to "Simple Clock Display (HA switch)",
        "player.enableVinylCoverDisplay" to "Vinyl Cover Display (HA switch)",
        "player.screensaverPixelShiftEnabled" to "Simple Clock pixel shift",
        "player.screensaverDarkOffEnabled" to "Simple Clock dark-off",
        "player.smartPowerSavingAodEnabled" to "Simple Clock smart AOD",
        "screensaver.pixelShiftEnabled" to "Screensaver pixel shift",
        "screensaver.keepOnOverlays" to "Smart screensaver exit",
        "screensaver.smartAodEnabled" to "Screensaver smart AOD",
        "player.enableScreensaverStatusSlots" to "Simple Clock status chips",
        "player.enableScreensaverStatusHaSlots" to "Simple Clock status HA slots",
        "player.enableVoiceMessageOverlayDisplay" to "Voice Message Display (HA switch)",
        "player.enableMinimalLauncherHaDisplay" to "Launcher apps (HA select)",
        "player.exposeEsphomeMediaPlayerEntity" to "Media Player (ESPHome entity)",
        "player.exposeWhisperResponseEntity" to "TTS volume (HA number)",
    )

    private data class SelectOption(val value: String, val label: String)

    private data class FieldSpec(
        val partition: String,
        val field: String,
        val label: String,
        val options: List<SelectOption>,
        /** Stored JSON type: "string" (default) or "number". */
        val valueType: String = "string",
    )

    private data class NumberFieldSpec(
        val partition: String,
        val field: String,
        val label: String,
        val min: Int? = null,
        val max: Int? = null,
        val description: String? = null,
    )

    private val EXTRA_STRICTNESS_OPTIONS: List<SelectOption> = listOf(
        SelectOption("0", "Off"),
        SelectOption("1", "Strict+"),
        SelectOption("2", "Max"),
    )

    /** Static Sendspin format keys (matches [com.example.ava.sendspin.SendspinFormatCatalog]). */
    private val SENDSPIN_FORMAT_OPTIONS: List<SelectOption> = listOf(
        SelectOption("automatic", "Automatic"),
        SelectOption("flac_44_16", "FLAC 44.1 kHz 16-bit"),
        SelectOption("flac_48_16", "FLAC 48 kHz 16-bit"),
        SelectOption("flac_44_24", "FLAC 44.1 kHz 24-bit"),
        SelectOption("flac_48_24", "FLAC 48 kHz 24-bit"),
        SelectOption("pcm_44_16", "PCM 44.1 kHz 16-bit"),
        SelectOption("pcm_48_16", "PCM 48 kHz 16-bit"),
        SelectOption("pcm_44_24", "PCM 44.1 kHz 24-bit"),
        SelectOption("pcm_48_24", "PCM 48 kHz 24-bit"),
        SelectOption("pcm_44_32", "PCM 44.1 kHz 32-bit"),
        SelectOption("pcm_48_32", "PCM 48 kHz 32-bit"),
        SelectOption("opus_48_16", "Opus 48 kHz 16-bit"),
    )

    /**
     * Enum / preset fields that the Ava app renders as dropdowns.
     * Keys match backup partition.field names; options mirror in-app settings UI.
     */
    private val SELECT_FIELDS: List<FieldSpec> = listOf(
        FieldSpec(
            partition = "microphone",
            field = "wakeWordEngine",
            label = "Wake word engine",
            options = WakeWordEngine.entries.map {
                SelectOption(it.name, when (it) {
                    WakeWordEngine.MICRO_WAKE_WORD -> "Micro Wake Word"
                    WakeWordEngine.OPEN_WAKE_WORD -> "openWakeWord"
                })
            },
        ),
        FieldSpec(
            partition = "microphone",
            field = "wakeWordExtraStrictness1",
            label = "Wake word 1 extra strictness",
            valueType = "number",
            options = EXTRA_STRICTNESS_OPTIONS,
        ),
        FieldSpec(
            partition = "microphone",
            field = "wakeWordExtraStrictness2",
            label = "Wake word 2 extra strictness",
            valueType = "number",
            options = EXTRA_STRICTNESS_OPTIONS,
        ),
        FieldSpec(
            partition = "microphone",
            field = "voicePrintEnrollmentMode",
            label = "Voiceprint enrollment",
            options = VoicePrintEnrollmentMode.entries.map {
                SelectOption(it.name, when (it) {
                    VoicePrintEnrollmentMode.AUTO -> "Automatic"
                    VoicePrintEnrollmentMode.MANUAL -> "Manual (5 samples)"
                })
            },
        ),
        FieldSpec(
            partition = "microphone",
            field = "audioProfileId",
            label = "Audio profile",
            options = listOf(SelectOption("", "Default (16 kHz mono)")) +
                DeviceAudioProfile.ALL_PROFILES.map { profile ->
                    SelectOption(profile.id, profile.id.replace('_', ' '))
                },
        ),
        FieldSpec(
            partition = "microphone",
            field = "audioSource",
            label = "System recording mode",
            valueType = "number",
            options = listOf(
                SelectOption(MediaRecorder.AudioSource.MIC.toString(), "Microphone"),
                SelectOption(MediaRecorder.AudioSource.VOICE_RECOGNITION.toString(), "Voice recognition"),
                SelectOption(MediaRecorder.AudioSource.VOICE_COMMUNICATION.toString(), "Call"),
                SelectOption(MediaRecorder.AudioSource.UNPROCESSED.toString(), "Unprocessed"),
            ),
        ),
        FieldSpec(
            partition = "microphone",
            field = "recordingPath",
            label = "Recording path",
            options = listOf(
                SelectOption(RecordingPath.AUTO.name, "Auto"),
                SelectOption(RecordingPath.BUILTIN.name, "Built-in"),
                SelectOption(RecordingPath.USB.name, "USB"),
            ),
        ),
        FieldSpec(
            partition = "player",
            field = "screensaverPortraitStyle",
            label = "Simple Clock portrait style",
            options = SimpleClockPortraitStyle.entries.map {
                SelectOption(it.storageKey, when (it) {
                    SimpleClockPortraitStyle.MAGAZINE -> "Portrait magazine"
                    SimpleClockPortraitStyle.SIMPLE -> "Simple portrait"
                })
            },
        ),
        FieldSpec(
            partition = "player",
            field = "dreamClockFace",
            label = "Dream Clock face",
            options = DreamClockFace.switchable.map {
                SelectOption(it.storageKey, when (it) {
                    DreamClockFace.FILL -> "Full-bleed analog"
                    DreamClockFace.FLIP -> "Flip clock"
                    DreamClockFace.MECHANICAL -> "Full-bleed analog"
                })
            },
        ),
        FieldSpec(
            partition = "player",
            field = "dreamClockFlipStyle",
            label = "Dream Clock flip style",
            options = DreamClockFlipStyle.entries.map {
                SelectOption(it.storageKey, it.storageKey)
            },
        ),
        FieldSpec(
            partition = "player",
            field = "dreamClockFlipFont",
            label = "Dream Clock flip font",
            options = DreamClockFlipFont.entries.map {
                SelectOption(it.storageKey, it.storageKey)
            },
        ),
        FieldSpec(
            partition = "player",
            field = "dreamClockFlipShowSeconds",
            label = "Dream Clock flip seconds",
            options = listOf(
                SelectOption("true", "Show seconds"),
                SelectOption("false", "Hide seconds"),
            ),
        ),
        FieldSpec(
            partition = "player",
            field = "dreamClockSeason",
            label = "Dream Clock season effect",
            options = DreamClockSeason.entries.map {
                SelectOption(it.storageKey, when (it) {
                    DreamClockSeason.NONE -> "Off"
                    DreamClockSeason.SPRING -> "Spring petals"
                    DreamClockSeason.SUMMER -> "Summer motes"
                    DreamClockSeason.AUTUMN -> "Autumn leaves"
                    DreamClockSeason.WINTER -> "Winter snow"
                })
            },
        ),
        FieldSpec(
            partition = "player",
            field = "mediaOverlayStyle",
            label = "Media overlay style",
            options = MediaOverlayStyle.entries.map {
                SelectOption(it.storageKey, when (it) {
                    MediaOverlayStyle.MINIMAL -> "Minimal"
                    MediaOverlayStyle.DETAILED -> "Detailed metadata"
                })
            },
        ),
        FieldSpec(
            partition = "player",
            field = "voiceMessageReceiveMode",
            label = "Voice message receive mode",
            options = listOf(
                SelectOption("auto", "Auto"),
                SelectOption("board", "Board only"),
            ),
        ),
        FieldSpec(
            partition = "player",
            field = "voiceCallVideoQuality",
            label = "Voice call video quality",
            options = VoiceCallVideoQuality.entries.map {
                SelectOption(it.storageKey, when (it) {
                    VoiceCallVideoQuality.SMOOTH -> "Smooth (480p @ 8fps)"
                    VoiceCallVideoQuality.HIGH -> "High (640p @ 10fps)"
                    VoiceCallVideoQuality.ULTRA -> "Ultra (720p @ 12fps)"
                })
            },
        ),
        FieldSpec(
            partition = "player",
            field = "minimalLauncherIconShape",
            label = "Launcher icon shape",
            options = listOf(
                SelectOption("squircle", "Squircle"),
                SelectOption("circle", "Circle"),
                SelectOption("rounded_square", "Rounded square"),
                SelectOption("system", "System default"),
            ),
        ),
        FieldSpec(
            partition = "player",
            field = "minimalLauncherWallpaperMode",
            label = "Launcher wallpaper mode",
            options = listOf(
                SelectOption("", "Default"),
                SelectOption("image", "Image"),
                SelectOption("none", "None (solid)"),
            ),
        ),
        FieldSpec(
            partition = "browser",
            field = "userAgentMode",
            label = "User agent",
            valueType = "number",
            options = listOf(
                SelectOption("0", "Default"),
                SelectOption("1", "Desktop"),
                SelectOption("2", "macOS"),
                SelectOption("3", "iOS"),
            ),
        ),
        FieldSpec(
            partition = "browser",
            field = "browserEngine",
            label = "Browser engine",
            valueType = "number",
            options = listOf(
                SelectOption("0", "System WebView"),
                SelectOption("1", "GeckoView"),
            ),
        ),
        FieldSpec(
            partition = "sendspin",
            field = "preferredFormat",
            label = "Preferred audio format",
            options = SENDSPIN_FORMAT_OPTIONS,
        ),
        FieldSpec(
            partition = "sendspin",
            field = "volumeFollowRule",
            label = "Volume follow rule",
            options = VolumeFollowRule.entries.map {
                SelectOption(
                    it.storageKey,
                    when (it) {
                        VolumeFollowRule.INDEPENDENT -> "Independent"
                        VolumeFollowRule.FOLLOW_DEVICE -> "Follow device"
                        VolumeFollowRule.FOLLOW_HA -> "Follow Home Assistant"
                    },
                )
            },
        ),
        FieldSpec(
            partition = "sidebar",
            field = "sidebarPosition",
            label = "Sidebar position",
            options = listOf(
                SelectOption("LEFT", "Left"),
                SelectOption("RIGHT", "Right"),
            ),
        ),
        FieldSpec(
            partition = "experimental",
            field = "cameraMode",
            label = "Camera mode",
            options = CameraMode.entries.map {
                SelectOption(it.name, when (it) {
                    CameraMode.SNAPSHOT -> "Snapshot"
                    CameraMode.VIDEO -> "Video"
                })
            },
        ),
        FieldSpec(
            partition = "experimental",
            field = "cameraPosition",
            label = "Camera position",
            options = CameraPosition.entries.map {
                SelectOption(it.name, when (it) {
                    CameraPosition.BACK -> "Back"
                    CameraPosition.FRONT -> "Front"
                })
            },
        ),
        FieldSpec(
            partition = "experimental",
            field = "cameraOrientation",
            label = "Camera orientation",
            options = CameraOrientation.entries.map {
                SelectOption(it.name, it.name.lowercase().replace('_', ' ').replaceFirstChar { c -> c.titlecase() })
            },
        ),
        FieldSpec(
            partition = "experimental",
            field = "forceOrientationMode",
            label = "Forced orientation",
            options = listOf(
                SelectOption("portrait", "Portrait"),
                SelectOption("landscape", "Landscape"),
                SelectOption("auto", "Auto"),
            ),
        ),
        FieldSpec(
            partition = "experimental",
            field = "audioEventSensitivity",
            label = "Audio event sensitivity",
            options = AudioEventSensitivity.entries.map {
                SelectOption(it.name, it.name.lowercase().replaceFirstChar { c -> c.titlecase() })
            },
        ),
        FieldSpec(
            partition = "experimental",
            field = "imageSize",
            label = "Snapshot image size",
            valueType = "number",
            options = listOf(
                SelectOption("0", "Original"),
                SelectOption("500", "500 px"),
                SelectOption("720", "720 px"),
                SelectOption("1080", "1080 px"),
            ),
        ),
        FieldSpec(
            partition = "experimental",
            field = "videoFps",
            label = "Video FPS",
            valueType = "number",
            options = listOf("1", "2", "3", "5", "8", "10", "15").map {
                SelectOption(it, "$it fps")
            },
        ),
        FieldSpec(
            partition = "experimental",
            field = "videoResolution",
            label = "Video resolution",
            valueType = "number",
            options = listOf(
                SelectOption("240", "240p"),
                SelectOption("360", "360p"),
                SelectOption("480", "480p"),
                SelectOption("720", "720p"),
            ),
        ),
        FieldSpec(
            partition = "experimental",
            field = "clusterAccessToken",
            label = "Access password",
            valueType = "string",
            options = emptyList(),
        ),
        FieldSpec(
            partition = "bluetooth",
            field = "proxyScanMode",
            label = "Proxy scan mode",
            options = listOf(
                SelectOption("auto", "Auto"),
                SelectOption("active", "Active"),
                SelectOption("passive", "Passive"),
            ),
        ),
        FieldSpec(
            partition = "bluetooth",
            field = "proxyScanPower",
            label = "Proxy scan power",
            options = listOf(
                SelectOption("high", "High"),
                SelectOption("balanced", "Balanced"),
                SelectOption("low", "Low"),
            ),
        ),
        FieldSpec(
            partition = "home_lock",
            field = "lockTarget",
            label = "Passcode protects",
            options = listOf(
                SelectOption("home", "Home"),
                SelectOption("settings", "Settings"),
            ),
        ),
        FieldSpec(
            partition = "home_lock",
            field = "pinLength",
            label = "PIN length",
            valueType = "number",
            options = listOf(
                SelectOption("4", "4 digits"),
                SelectOption("6", "6 digits"),
            ),
        ),
    )

    /**
     * Numeric fields with the same clamp ranges as [ScreensaverSettingsStore] /
     * [SendspinSettingsStore] SettingState setters — keeps console forms aligned.
     */
    private val NUMBER_FIELDS: List<NumberFieldSpec> = listOf(
        NumberFieldSpec(
            partition = "microphone",
            field = "micGainDb",
            label = "Software mic gain (dB)",
            min = MicrophoneSettings.MIC_GAIN_DB_MIN,
            max = MicrophoneSettings.MIC_GAIN_DB_MAX,
            description = "−24–24 dB; 0 = unchanged, positive boosts quiet mics, " +
                "negative attenuates hot mics (e.g. Portal try −6 to −12). " +
                "Applied before wake-word detection and HA streaming",
        ),
        NumberFieldSpec(
            partition = "screensaver",
            field = "timeoutSeconds",
            label = "Idle timeout (seconds)",
            min = 0,
            max = 3600,
            description = "0 or 10–3600; 0 = never auto-show on idle " +
                "(manual HA screensaver switch still works); 1–9 clamps to 10",
        ),
        NumberFieldSpec(
            partition = "player",
            field = "smartPowerSavingAodTimeoutSeconds",
            label = "Simple Clock smart AOD wait (seconds)",
            min = 10,
            max = 3600,
            description = "10–3600; idle seconds before Simple Clock smart AOD fade-in",
        ),
        NumberFieldSpec(
            partition = "player",
            field = "smartPowerSavingAodMaskPercent",
            label = "Simple Clock AOD Pixel Depth (%)",
            min = 5,
            max = 100,
            description = "5–100; dark cover strength for Simple Clock idle AOD",
        ),
        NumberFieldSpec(
            partition = "screensaver",
            field = "smartAodMaskPercent",
            label = "Screensaver AOD Pixel Depth (%)",
            min = 5,
            max = 100,
            description = "5–100; dark cover strength when screensaver smart AOD is on",
        ),
        NumberFieldSpec(
            partition = "quick_entity",
            field = "smartAodTimeoutSeconds",
            label = "Quick Entity smart AOD wait (seconds)",
            min = 10,
            max = 3600,
            description = "10–3600; idle seconds before Quick Entity smart AOD fade-in",
        ),
        NumberFieldSpec(
            partition = "quick_entity",
            field = "smartAodMaskPercent",
            label = "Quick Entity AOD Pixel Depth (%)",
            min = 5,
            max = 100,
            description = "5–100; dark cover strength for Quick Entity idle AOD",
        ),
        NumberFieldSpec(
            partition = "sendspin",
            field = "syncOffsetMs",
            label = "Sync offset (ms)",
            min = -5000,
            max = 5000,
            description = "−5000–5000; same clamp as the Ava Sendspin settings screen",
        ),
        NumberFieldSpec(
            partition = "sendspin",
            field = "volume",
            label = "Volume",
            min = 0,
            max = 100,
        ),
        NumberFieldSpec(
            partition = "bluetooth",
            field = "rssiThreshold",
            label = "RSSI threshold (dBm)",
            min = -100,
            max = -40,
            description = "Same range as Ava Bluetooth presence settings",
        ),
        NumberFieldSpec(
            partition = "bluetooth",
            field = "awayDelaySeconds",
            label = "Away delay (seconds)",
            min = 5,
            max = 3600,
        ),
        NumberFieldSpec(
            partition = "home_lock",
            field = "idleTimeoutSeconds",
            label = "Idle lock timeout (seconds)",
            min = 5,
            max = 3600,
        ),
    )

    private fun fieldSpecJson(spec: FieldSpec): JSONObject {
        val options = JSONArray()
        val optionLabels = JSONObject()
        for (opt in spec.options) {
            val raw = if (spec.valueType == "number") {
                opt.value.toIntOrNull() ?: opt.value
            } else {
                opt.value
            }
            options.put(raw)
            optionLabels.put(opt.value, opt.label)
        }
        return JSONObject()
            .put("partition", spec.partition)
            .put("field", spec.field)
            .put("type", "select")
            .put("label", spec.label)
            .put("valueType", spec.valueType)
            .put("options", options)
            .put("optionLabels", optionLabels)
    }

    private fun numberFieldSpecJson(spec: NumberFieldSpec): JSONObject {
        val obj = JSONObject()
            .put("partition", spec.partition)
            .put("field", spec.field)
            .put("type", "number")
            .put("label", spec.label)
            .put("valueType", "number")
        if (spec.min != null) obj.put("min", spec.min)
        if (spec.max != null) obj.put("max", spec.max)
        if (!spec.description.isNullOrBlank()) obj.put("description", spec.description)
        return obj
    }

    /**
     * Wake-word model IDs available on this device (built-in assets + imported library).
     * Used so the console can offer a dropdown instead of free-text model IDs.
     */
    private fun wakeWordSelectFields(context: Context): List<FieldSpec> {
        // Micro-only for the shared wakeWord field. Do NOT merge VS catalog ids —
        // hey_jarvis/alexa/… share names but are different files; a merged dropdown
        // lets a VS pick overwrite Micro selections via backup/fleet apply.
        val microOptions = linkedMapOf<String, String>()
        runCatching {
            for (ww in WakeWordProviderFactory.microWakeWordProvider(context).getWakeWords()) {
                val phrase = ww.wakeWord.wake_word?.takeIf { it.isNotBlank() } ?: ww.id
                microOptions[ww.id] = phrase
            }
        }
        if (microOptions.isEmpty()) return emptyList()
        return listOf(
            FieldSpec(
                partition = "microphone",
                field = "wakeWord",
                label = "Wake word model (Micro)",
                options = microOptions.map { (id, label) -> SelectOption(id, label) },
            ),
        )
    }

    /** Schema-only JSON for the console: partition list with tiers and HA-published flags. */
    fun schemaJson(context: Context? = null): JSONObject {
        val partitions = JSONArray()
        for (p in PARTITIONS) {
            val obj = JSONObject()
                .put("key", p.key)
                .put("tier", p.tier.name.lowercase())
            partitions.put(obj)
        }

        val haPublished = JSONArray()
        for ((field, label) in HA_PUBLISHED_EXPERIMENTAL) {
            haPublished.put(
                JSONObject()
                    .put("partition", "experimental")
                    .put("field", field)
                    .put("label", label)
                    .put("type", "boolean")
            )
        }
        for ((path, label) in HA_PUBLISHED_OTHER) {
            val (partition, field) = path.split('.', limit = 2)
            haPublished.put(
                JSONObject()
                    .put("partition", partition)
                    .put("field", field)
                    .put("label", label)
                    .put("type", "boolean")
            )
        }

        val fields = JSONArray()
        for (spec in SELECT_FIELDS) {
            fields.put(fieldSpecJson(spec))
        }
        for (spec in NUMBER_FIELDS) {
            fields.put(numberFieldSpecJson(spec))
        }
        if (context != null) {
            for (spec in wakeWordSelectFields(context)) {
                fields.put(fieldSpecJson(spec))
            }
        }

        return JSONObject()
            .put("partitions", partitions)
            .put("haPublished", haPublished)
            .put("fields", fields)
    }

    /**
     * Read current on/off state of every HA-published boolean switch.
     * Returns a flat array of `{ partition, field, label, enabled }`.
     */
    fun haPublishedSwitchesSnapshot(context: Context): JSONArray {
        val settings = runCatching {
            FleetSettingsService.getSettingsSnapshot(context).optJSONObject("settings")
        }.getOrNull() ?: JSONObject()

        val result = JSONArray()

        for ((field, label) in HA_PUBLISHED_EXPERIMENTAL) {
            val enabled = settings.optJSONObject("experimental")?.optBoolean(field, false) == true
            result.put(
                JSONObject()
                    .put("partition", "experimental")
                    .put("field", field)
                    .put("label", label)
                    .put("enabled", enabled)
                    .put("key", "experimental.$field")
                    .put("path", "experimental.$field"),
            )
        }
        for ((path, label) in HA_PUBLISHED_OTHER) {
            val parts = path.split('.', limit = 2)
            if (parts.size != 2) continue
            val partition = parts[0]
            val field = parts[1]
            val enabled = settings.optJSONObject(partition)?.optBoolean(field, false) == true
            result.put(
                JSONObject()
                    .put("partition", partition)
                    .put("field", field)
                    .put("label", label)
                    .put("enabled", enabled)
                    .put("key", path)
                    .put("path", path),
            )
        }

        return result
    }
}
