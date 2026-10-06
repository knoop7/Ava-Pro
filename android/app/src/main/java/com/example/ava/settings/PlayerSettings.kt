package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class SimpleClockStatusSlot(
    val entityId: String = "",
    val icon: String = "mdi:home-assistant",
    val label: String = ""
)

@Serializable
data class PlayerSettings(
    val volume: Float = 0.1f,
    val muted: Boolean = false,
    val enableWakeSound: Boolean = true,
    val enableScreenOff: Boolean = true,  
    val wakeSound: String = "asset:///sounds/wake_word_triggered.wav",
    val wakeSound2: String = "asset:///sounds/wake_word_triggered.wav",
    val timerFinishedSound: String = "asset:///sounds/timer_finished.wav",
    val enableTimerStopButton: Boolean = false,
    val stopSound: String = "asset:///stopWords/stop_sound.wav",
    val enableStopSound: Boolean = true,
    val continuousPromptSound: String = "asset:///sounds/continuous_prompt.wav",
    val enableContinuousConversation: Boolean = false,  
    val enableQuestionMarkContinue: Boolean = false,
    val enableExitKeywordStop: Boolean = true,
    /** Cloud model must call ava_turn; that call is the continue_conversation this mode follows. */
    val enableSmartContinue: Boolean = false,
    val enableFloatingWindow: Boolean = false,
    /** Word-by-word assistant subtitles from HA content deltas; off by default. */
    val enableStreamingTtsSubtitles: Boolean = false,
    /** @deprecated Legacy master switch; kept for backup restore. UI removed — use per-protocol toggles. */
    val enableVinylCover: Boolean = false,
    /** Register ESPHome media_player entity in Home Assistant (play_media on this device). */
    val exposeEsphomeMediaPlayerEntity: Boolean = true,
    val enableHaVinylCover: Boolean = true,
    val enableSendspinVinylCover: Boolean = true,
    /** HA media_player output equalizer (software 5-band). Default off. */
    val haMusicEqEnabled: Boolean = false,
    val haMusicEqBassDb: Float = 0f,
    val haMusicEqLowMidDb: Float = 0f,
    val haMusicEqMidDb: Float = 0f,
    val haMusicEqUpperMidDb: Float = 0f,
    val haMusicEqTrebleDb: Float = 0f,
    /** Level-driven low/high contour on top of the HA bands. Requires the EQ switch. */
    val haMusicEqAdaptive: Boolean = false,
    /** [MediaOverlayStyle.storageKey] — minimal or detailed metadata layout. */
    val mediaOverlayStyle: String = MediaOverlayStyle.DETAILED.storageKey,
    /**
     * @deprecated Karaoke lyrics are always on; runtime ignores this value.
     * Kept so old backups / fleet patches carrying the key still deserialize.
     */
    val enableKaraokeLyrics: Boolean = false,
    /**
     * Presentation only (not a master overlay switch): when true and HA/MA media
     * controls allow the window, use circular FAB + expand. Off = legacy full overlay.
     * After pause: 30s soft-collapse to FAB, then temporary hide if still idle.
     * Closing both media-control switches destroys the overlay regardless of this flag.
     */
    val enableEqMiniPlayer: Boolean = true,
    /**
     * When true, expose HA switch `vinyl_cover_display` for the expanded music overlay.
     * Default off — entity is not registered until enabled (same pattern as browser/weather).
     */
    val enableVinylCoverDisplay: Boolean = false,
    /**
     * HA `vinyl_cover_display` value: mirrors whether the **expanded** player is showing
     * (FAB alone does not count). Two-way with [VinylCoverService] chrome visibility.
     * Cleared to false on cold start / home power-on so HA does not restore expanded.
     */
    val enableVinylCoverVisible: Boolean = false,
    /**
     * Normalized FAB position in the current orientation's movable range [0, 1].
     * -1 = default bottom-end. Survives portrait↔landscape by remapping to px.
     * Cleared when [enableEqMiniPlayer] is turned off.
     */
    val eqMiniFabNormX: Float = -1f,
    val eqMiniFabNormY: Float = -1f,
    /**
     * Legacy absolute TOP|START px. Migrated into [eqMiniFabNormX]/[eqMiniFabNormY]
     * on next load, then cleared.
     */
    val eqMiniFabX: Int = -1,
    val eqMiniFabY: Int = -1,
    /** One-time migration from enableVinylCover master switch to split playback cards. */
    val playbackUiMigrated: Boolean = false,
    val enableDreamClock: Boolean = false,  
    val enableDreamClockDisplay: Boolean = false,  
    val enableDreamClockVisible: Boolean = false,
    /** Dream Clock face. Users switch [DreamClockFace.FILL] and [DreamClockFace.FLIP] only. */
    val dreamClockFace: String = DreamClockFace.FILL.storageKey,
    /** StandBy Retro Flip color theme. Used only when [dreamClockFace] is flip. */
    val dreamClockFlipStyle: String = DreamClockFlipStyle.BLACK.storageKey,
    val dreamClockFlipShowSeconds: Boolean = true,
    /** 12-hour flip clock with AM/PM on the hour card (FlipFlow hourFormat 0). Default 24h. */
    val dreamClockFlip12Hour: Boolean = false,
    /** Flip digit typeface. Mirrors StandBy's selectedRetroFlipFontName. */
    val dreamClockFlipFont: String = DreamClockFlipFont.LEAGUE_GOTHIC.storageKey,
    /** StandBy seasonal particle overlay. */
    val dreamClockSeason: String = DreamClockSeason.NONE.storageKey,
    /** When true, Dream Clock hides the on-face adjust control. */
    val dreamClockSettingsLocked: Boolean = false,
    /** HA `timer.*` entity bound to the flip clock countdown. Blank = local-only timer. */
    val dreamClockTimerEntityId: String = "",
    /** Ring [dreamClockTimerSound] when the flip countdown hits zero. */
    val dreamClockTimerSoundEnabled: Boolean = true,
    /** Flip-countdown alarm; independent of the Quick Entity [timerFinishedSound]. */
    val dreamClockTimerSound: String = "asset:///sounds/timer_finished.wav",
    /** Expose the flip color theme as an HA select entity (config category). Default off. */
    val dreamClockFlipStyleHaSelect: Boolean = false,
    /** Idle dark cover for the Dream Clock overlay — same model as Quick Entity smart AOD. */
    val dreamClockSmartAodEnabled: Boolean = false,
    val dreamClockSmartAodTimeoutSeconds: Int = 60,
    /** 5–100; at or below 85 taps pass through the cover, above it the first tap only wakes. */
    val dreamClockSmartAodMaskPercent: Int = 100,
    val enableScreensaver: Boolean = false,
    val enableScreensaverDisplay: Boolean = false,
    val enableScreensaverVisible: Boolean = false,  
    val screensaverDoubleTapToggleEnabled: Boolean = true,
    val screensaverWallpaperUrl: String = "",
    val screensaverWallpaperRefreshSeconds: Int = 30,
    val screensaverWallpaperDualPane: Boolean = false,
    val screensaverWallpaperDarkOverlayEnabled: Boolean = false,
    /**
     * Simple Clock weather chrome (icon + temperature). Default on.
     * Off → hide weather and free layout space.
     * On with empty [screensaverWeatherEntityId] / no data → sunny + "-" placeholder.
     * Independent of [enableWeatherOverlay] / [haWeatherEntity].
     */
    val enableScreensaverWeather: Boolean = true,
    /**
     * Dedicated HA weather entity for Simple Clock only (e.g. weather.home).
     * Empty keeps the sunny / - placeholder while the switch is on.
     * Does **not** reuse [haWeatherEntity].
     */
    val screensaverWeatherEntityId: String = "",
    /**
     * One-time: copy [haWeatherEntity] into [screensaverWeatherEntityId] when the latter
     * is still blank, so existing installs keep showing clock weather after decoupling.
     */
    val screensaverWeatherMigrated: Boolean = false,
    /**
     * Portrait Simple Clock layout. Default [SimpleClockPortraitStyle.SIMPLE] is the
     * original one-line clock. [SimpleClockPortraitStyle.MAGAZINE] is the stacked hero.
     */
    val screensaverPortraitStyle: String = SimpleClockPortraitStyle.SIMPLE.storageKey,
    /**
     * Simple Clock 12-hour display (e.g. 3:00 PM). Default off = current 24-hour (15:00).
     * Independent of Dawn magazine [ScreensaverSettings.dawnIsoTimeEnabled].
     */
    val screensaver12HourEnabled: Boolean = false,
    /** Simple Clock burn-in mitigation; independent of WebView screensaver pixel shift. Default off. */
    val screensaverPixelShiftEnabled: Boolean = false,
    /**
     * Simple Clock dark-off: turn the screen off in very low ambient light.
     * Independent of WebView screensaver [ScreensaverSettings.darkOffEnabled]. Default off.
     */
    val screensaverDarkOffEnabled: Boolean = false,
    /**
     * Smart power-saving AOD for Simple Clock only: after idle wait, fade in a dark cover
     * at [smartPowerSavingAodMaskPercent]; any interrupt fades it out and the wait restarts.
     * Independent of Web screensaver AOD (which is static). Default off.
     */
    val smartPowerSavingAodEnabled: Boolean = false,
    /** Idle seconds before entering (or re-entering) Simple Clock smart AOD. */
    val smartPowerSavingAodTimeoutSeconds: Int = 60,
    /**
     * Idle AOD cover strength: 100 = fully opaque (deepest), 5 = lightest.
     * Default 100. UI must not call this a "black mask".
     */
    val smartPowerSavingAodMaskPercent: Int = 100,
    /** When true, Simple Clock replaces the date row with up to 3 entity status chips. */
    val enableScreensaverStatusSlots: Boolean = false,
    /** When true, expose text.xxx_simple_clock_status_slot_N in Home Assistant for quick swaps. */
    val enableScreensaverStatusHaSlots: Boolean = false,
    val screensaverStatusSlots: List<SimpleClockStatusSlot> = List(3) { SimpleClockStatusSlot() },
    val enableWeatherOverlay: Boolean = false,  
    val enableWeatherOverlayDisplay: Boolean = false,  
    val enableWeatherOverlayVisible: Boolean = false,  
    val haWeatherEntity: String = "",  
    /** When true, BootReceiver starts VoiceSatelliteService after device boot. */
    val enableAutoRestart: Boolean = false,
    /** When true, MainActivity starts VoiceSatelliteService after the app opens (not boot). */
    val startServiceOnAppOpen: Boolean = false,
    /**
     * Countdown seconds before open-app auto-start. Clamped to
     * [MIN_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS]..[MAX_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS].
     */
    val startServiceOnAppOpenDelaySeconds: Int = DEFAULT_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS,
    /**
     * When true, relaunch Ava after an unexpected process death
     * ([com.example.ava.crash.CrashSelfHeal]). Field default stays off so an
     * existing `player_settings.json` that omitted this key stays off on upgrade.
     * Fresh installs use [NEW_USER_PLAYER_SETTINGS].
     */
    val enableCrashSelfHeal: Boolean = false,
    /**
     * Hold an untimed CPU wake lock while the screen is off so keepalive timers
     * (wake lock renewal, stale-client ping watchdog) fire on schedule instead of
     * freezing when the CPU suspends. Default ON: battery-powered tablets that
     * turn the screen off otherwise drop the HA link ~30 min after screen-off.
     */
    val keepCpuAwakeOnScreenOff: Boolean = true,
    val enableMinimalLauncher: Boolean = false,
    /**
     * Packages shown on the minimal-launcher home grid.
     * Empty = show all installed launcher apps (default).
     * Non-empty = whitelist; only these packages appear.
     */
    val minimalLauncherVisiblePackages: List<String> = emptyList(),
    /**
     * Expose the Home-grid whitelist as a Home Assistant select entity.
     * Only a true subset (user-picked, not empty and not every installed
     * launcher app) may stay on; the settings UI and satellite builder
     * both force this off otherwise.
     */
    val enableMinimalLauncherHaDisplay: Boolean = false,
    /**
     * Packages that open in a floating, resizable small window instead of
     * fullscreen. Two engines by version, both needing Shizuku (ADB mode):
     * Android 10 (API 29)+ mirrors via a scrcpy virtual display (best on
     * Android 13+); Android 7–9 (API 24–28) uses the system's native freeform
     * window ([com.example.ava.appwindow.FreeformAppWindow]). Empty = feature
     * idle, every app launches normally. There is no separate master switch.
     */
    val appWindowPackages: List<String> = emptyList(),
    /** Selected icon pack package name. Empty = system default icons. */
    val minimalLauncherIconPack: String = "",
    /**
     * Icon shape: "squircle" (default), "circle", "rounded_square", "system".
     * "system" = no masking, raw drawable as-is.
     */
    val minimalLauncherIconShape: String = "squircle",
    /** When true (default), desktop icons show app name labels under the icon. */
    val minimalLauncherShowDesktopLabels: Boolean = true,
    /** Persisted document URI for the Ava desktop wallpaper. Empty = solid theme background. */
    val minimalLauncherWallpaperUri: String = "",
    /** "", "image", or "none". Empty keeps pre-mode image settings compatible. */
    val minimalLauncherWallpaperMode: String = "",
    val minimalLauncherWallpaperScale: Float = 1f,
    val minimalLauncherWallpaperOffsetX: Float = 0f,
    val minimalLauncherWallpaperOffsetY: Float = 0f,
    val enableHaSwitchOverlay: Boolean = false,
    /** When true, expose a Manual Dismiss (Just stop) button entity in Home Assistant. */
    val enableManualDismissButton: Boolean = false,
    val enableVoiceMessageReceive: Boolean = true,
    val enableVoiceMessageOverlay: Boolean = false,
    val enableVoiceMessageOverlayDisplay: Boolean = false,
    val enableVoiceMessageOverlayVisible: Boolean = false,
    val enableVoiceOverlayIntercom: Boolean = true,
    val enableVoiceOverlayCall: Boolean = true,
    val voiceMessageDisplayName: String = "",
    val voiceMessageDelayMinutes: Int = 0,
    val voiceMessageReceiveMode: String = "auto",
    /** Incoming live calls ring and require tap-to-answer; off = auto-connect like before. */
    val enableVoiceCallAnswerRequired: Boolean = true,
    val voiceCallRingtone: String = DEFAULT_VOICE_CALL_RINGTONE,
    /** LAN voice-call video preset: smooth (default) / high / ultra. */
    val voiceCallVideoQuality: String = "smooth",
    /** Empty = default accent; preset name or #RRGGBB like quick-entity slots. */
    val voiceWakeWord1AccentColor: String = "",
    val voiceWakeWord2AccentColor: String = "",
    /** Wake-word center ripple overlay ([WakeRippleView]). Default on for existing installs. */
    val enableVoiceRippleEffect: Boolean = true,
    /** Listening / processing / speaking edge glow ([VoiceStateOverlayView]). Default on. */
    val enableVoiceEdgeGlow: Boolean = true,
    /**
     * Extra STT/TTS level swing on the edge glow. Legacy single value; used only
     * when a per-wake-word gain was never saved. Slider left = 1.
     */
    val voiceEdgeGlowLevelGain: Float = DEFAULT_EDGE_GLOW_LEVEL_GAIN,
    /** Wake word 1: extra amplitude of STT/TTS level swing. 1 = designed default. */
    val voiceWakeWord1EdgeGlowLevelGain: Float = DEFAULT_EDGE_GLOW_LEVEL_GAIN,
    /** Wake word 2: extra amplitude of STT/TTS level swing. 1 = designed default. */
    val voiceWakeWord2EdgeGlowLevelGain: Float = DEFAULT_EDGE_GLOW_LEVEL_GAIN,
    /** True after either per-wake-word slider has been saved. Legacy gain is then ignored. */
    val voiceEdgeGlowLevelGainSplit: Boolean = false,
    /** Wake word 1: 0 = designed opacity; 1 = more translucent (still visible). */
    val voiceWakeWord1EdgeGlowSheer: Float = DEFAULT_EDGE_GLOW_SHEER,
    /** Wake word 2: 0 = designed opacity; 1 = more translucent (still visible). */
    val voiceWakeWord2EdgeGlowSheer: Float = DEFAULT_EDGE_GLOW_SHEER,
    /** Unused. Overlay always uses [whisperResponseVolume]. Kept so backups still decode. */
    val enableWhisperResponse: Boolean = false,
    /** Unused. Kept so backups still decode. */
    val enableWhisperResponseAdaptive: Boolean = true,
    val whisperResponseVolume: Float = DEFAULT_WHISPER_RESPONSE_VOLUME,
    /**
     * Additive TTS auto-gain from ambient noise. Never lowers [whisperResponseVolume].
     * Playback-only — does not rewrite the slider or the HA entity.
     */
    val enableAmbientAutoGain: Boolean = false,
    /** When true, expose TTS volume as a Home Assistant config number entity. */
    val exposeWhisperResponseEntity: Boolean = false,
    /**
     * When on, keep HTTPS TTS / HA proxy URLs as HTTPS (do not rewrite to LAN HTTP).
     * Default off — existing installs keep the LAN HTTP rewrite for DuckDNS/unreachable hosts.
     * Turn on only when Home Assistant serves HTTPS only (ssl_certificate) and voice replies time out.
     */
    val preserveTtsHttps: Boolean = false,
) {
    companion object {
        const val DEFAULT_VOICE_CALL_RINGTONE = "asset:///sounds/voice_call_ringtone.wav"
        const val MIN_WHISPER_RESPONSE_VOLUME = 0.10f
        const val DEFAULT_WHISPER_RESPONSE_VOLUME = 0.3f
        /** Designed listening/speaking level response. Slider left stop. */
        const val MIN_EDGE_GLOW_LEVEL_GAIN = 1f
        const val DEFAULT_EDGE_GLOW_LEVEL_GAIN = MIN_EDGE_GLOW_LEVEL_GAIN
        /** Ceiling for extra level swing. Slider right stop. 1 extra unit = old max inward. */
        const val MAX_EDGE_GLOW_LEVEL_GAIN = 3.5f

        fun clampEdgeGlowLevelGain(gain: Float): Float =
            gain.coerceIn(MIN_EDGE_GLOW_LEVEL_GAIN, MAX_EDGE_GLOW_LEVEL_GAIN)

        /** Designed opacity. Slider left stop. */
        const val MIN_EDGE_GLOW_SHEER = 0f
        const val DEFAULT_EDGE_GLOW_SHEER = MIN_EDGE_GLOW_SHEER
        /** Slider right: more translucent, but the glow stays readable. */
        const val MAX_EDGE_GLOW_SHEER = 1f
        /** Floor of designed alpha at slider right — never fully clear. */
        const val MIN_EDGE_GLOW_OPACITY_MUL = 0.45f

        fun clampEdgeGlowSheer(sheer: Float): Float =
            sheer.coerceIn(MIN_EDGE_GLOW_SHEER, MAX_EDGE_GLOW_SHEER)

        fun edgeGlowOpacityMul(sheer: Float): Float {
            val t = clampEdgeGlowSheer(sheer)
            return 1f - t * (1f - MIN_EDGE_GLOW_OPACITY_MUL)
        }

        const val MIN_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS = 3
        const val MAX_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS = 30
        const val DEFAULT_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS = 7

        fun clampStartServiceOnAppOpenDelaySeconds(seconds: Int): Int =
            seconds.coerceIn(
                MIN_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS,
                MAX_START_SERVICE_ON_APP_OPEN_DELAY_SECONDS,
            )
    }

    fun edgeGlowLevelGainForWakeWord(wakeWordIndex: Int): Float {
        val useSplit = voiceEdgeGlowLevelGainSplit ||
            voiceWakeWord1EdgeGlowLevelGain != DEFAULT_EDGE_GLOW_LEVEL_GAIN ||
            voiceWakeWord2EdgeGlowLevelGain != DEFAULT_EDGE_GLOW_LEVEL_GAIN
        val raw = if (useSplit) {
            if (wakeWordIndex >= 1) {
                voiceWakeWord2EdgeGlowLevelGain
            } else {
                voiceWakeWord1EdgeGlowLevelGain
            }
        } else {
            voiceEdgeGlowLevelGain
        }
        return clampEdgeGlowLevelGain(raw)
    }

    fun edgeGlowSheerForWakeWord(wakeWordIndex: Int): Float {
        val raw = if (wakeWordIndex >= 1) {
            voiceWakeWord2EdgeGlowSheer
        } else {
            voiceWakeWord1EdgeGlowSheer
        }
        return clampEdgeGlowSheer(raw)
    }

    fun edgeGlowOpacityMulForWakeWord(wakeWordIndex: Int): Float =
        edgeGlowOpacityMul(edgeGlowSheerForWakeWord(wakeWordIndex))
}

/**
 * Seed when `player_settings.json` does not exist yet (fresh install).
 *
 * [PlayerSettings] field defaults stay off for keys that older files omitted:
 * SettingsSerializer encodes with `encodeDefaults=false`, so an existing file
 * without `enableCrashSelfHeal` must keep it off on upgrade. Only the DataStore
 * empty-file default uses this seed.
 */
val NEW_USER_PLAYER_SETTINGS = PlayerSettings(
    enableCrashSelfHeal = true,
)

val Context.playerSettingsStore: DataStore<PlayerSettings> by dataStore(
    fileName = "player_settings.json",
    serializer = SettingsSerializer(PlayerSettings.serializer(), NEW_USER_PLAYER_SETTINGS),
    corruptionHandler = defaultCorruptionHandler(PlayerSettings())
)

/**
 * Dedicated Simple Clock weather entity only. Empty / non-weather.* → no fetch.
 * Never silently reuses [PlayerSettings.haWeatherEntity].
 */
fun resolveScreensaverWeatherEntityId(settings: PlayerSettings): String {
    val id = settings.screensaverWeatherEntityId.trim()
    return if (id.startsWith("weather.")) id else ""
}

/**
 * One-time: if the user already configured overlay weather, seed Simple Clock's
 * dedicated entity so existing installs keep clock weather after decoupling.
 */
suspend fun migrateScreensaverWeatherIfNeeded(dataStore: DataStore<PlayerSettings>) {
    dataStore.updateData { settings ->
        if (settings.screensaverWeatherMigrated) return@updateData settings
        val legacy = settings.haWeatherEntity.trim()
        val seed = if (
            settings.screensaverWeatherEntityId.isBlank() &&
            legacy.startsWith("weather.")
        ) {
            legacy
        } else {
            settings.screensaverWeatherEntityId.trim().let {
                if (it.startsWith("weather.")) it else ""
            }
        }
        settings.copy(
            screensaverWeatherEntityId = seed,
            screensaverWeatherMigrated = true
        )
    }
}

class PlayerSettingsStore(dataStore: DataStore<PlayerSettings>) :
    SettingsStoreImpl<PlayerSettings>(dataStore, PlayerSettings()) {
    val volume =
        SettingState(getFlow().map { it.volume }) { value -> update { it.copy(volume = value) } }
    val muted =
        SettingState(getFlow().map { it.muted }) { value -> update { it.copy(muted = value) } }
    val enableWakeSound = SettingState(getFlow().map { it.enableWakeSound }) { value ->
        update {
            it.copy(enableWakeSound = value)
        }
    }
    val enableScreenOff = SettingState(getFlow().map { it.enableScreenOff }) { value ->
        update {
            it.copy(enableScreenOff = value)
        }
    }
    val wakeSound =
        SettingState(getFlow().map { it.wakeSound }) { value -> update { it.copy(wakeSound = value) } }
    val wakeSound2 =
        SettingState(getFlow().map { it.wakeSound2 }) { value -> update { it.copy(wakeSound2 = value) } }
    val timerFinishedSound =
        SettingState(getFlow().map { it.timerFinishedSound }) { value ->
            update {
                it.copy(
                    timerFinishedSound = value
                )
            }
        }
    val enableTimerStopButton = SettingState(getFlow().map { it.enableTimerStopButton }) { value ->
        update { it.copy(enableTimerStopButton = value) }
    }
    val stopSound =
        SettingState(getFlow().map { it.stopSound }) { value -> update { it.copy(stopSound = value) } }
    val enableStopSound = SettingState(getFlow().map { it.enableStopSound }) { value ->
        update { it.copy(enableStopSound = value) }
    }
    val continuousPromptSound = SettingState(getFlow().map { it.continuousPromptSound }) { value ->
        update { it.copy(continuousPromptSound = value) }
    }
    val enableContinuousConversation = SettingState(getFlow().map { it.enableContinuousConversation }) { value ->
        update { it.copy(enableContinuousConversation = value) }
    }
    val enableQuestionMarkContinue = SettingState(getFlow().map { it.enableQuestionMarkContinue }) { value ->
        update { it.copy(enableQuestionMarkContinue = value) }
    }
    val enableExitKeywordStop = SettingState(getFlow().map { it.enableExitKeywordStop }) { value ->
        update { it.copy(enableExitKeywordStop = value) }
    }
    val enableSmartContinue = SettingState(getFlow().map { it.enableSmartContinue }) { value ->
        update { it.copy(enableSmartContinue = value) }
    }
    val enableFloatingWindow = SettingState(getFlow().map { it.enableFloatingWindow }) { value ->
        update { it.copy(enableFloatingWindow = value) }
    }
    val enableStreamingTtsSubtitles = SettingState(getFlow().map { it.enableStreamingTtsSubtitles }) { value ->
        update { it.copy(enableStreamingTtsSubtitles = value) }
    }
    val enableVinylCover = SettingState(getFlow().map { it.enableVinylCover }) { value ->
        update { it.copy(enableVinylCover = value) }
    }
    val exposeEsphomeMediaPlayerEntity = SettingState(getFlow().map { it.exposeEsphomeMediaPlayerEntity }) { value ->
        update { it.copy(exposeEsphomeMediaPlayerEntity = value) }
    }
    val enableHaVinylCover = SettingState(getFlow().map { it.enableHaVinylCover }) { value ->
        update {
            val next = it.copy(enableHaVinylCover = value)
            // Both media-control masters off → also drop HA vinyl_cover_display exposure.
            if (!next.enableHaVinylCover &&
                !next.enableSendspinVinylCover &&
                next.enableVinylCoverDisplay
            ) {
                next.copy(enableVinylCoverDisplay = false)
            } else {
                next
            }
        }
    }
    val enableSendspinVinylCover = SettingState(getFlow().map { it.enableSendspinVinylCover }) { value ->
        update {
            val next = it.copy(enableSendspinVinylCover = value)
            if (!next.enableHaVinylCover &&
                !next.enableSendspinVinylCover &&
                next.enableVinylCoverDisplay
            ) {
                next.copy(enableVinylCoverDisplay = false)
            } else {
                next
            }
        }
    }
    val haMusicEqEnabled = SettingState(getFlow().map { it.haMusicEqEnabled }) { value ->
        update { it.copy(haMusicEqEnabled = value) }
    }
    val haMusicEqBassDb = SettingState(getFlow().map { it.haMusicEqBassDb }) { value ->
        update { it.copy(haMusicEqBassDb = com.example.ava.audio.eq.MusicEqGains.clampDb(value)) }
    }
    val haMusicEqLowMidDb = SettingState(getFlow().map { it.haMusicEqLowMidDb }) { value ->
        update { it.copy(haMusicEqLowMidDb = com.example.ava.audio.eq.MusicEqGains.clampDb(value)) }
    }
    val haMusicEqMidDb = SettingState(getFlow().map { it.haMusicEqMidDb }) { value ->
        update { it.copy(haMusicEqMidDb = com.example.ava.audio.eq.MusicEqGains.clampDb(value)) }
    }
    val haMusicEqUpperMidDb = SettingState(getFlow().map { it.haMusicEqUpperMidDb }) { value ->
        update { it.copy(haMusicEqUpperMidDb = com.example.ava.audio.eq.MusicEqGains.clampDb(value)) }
    }
    val haMusicEqTrebleDb = SettingState(getFlow().map { it.haMusicEqTrebleDb }) { value ->
        update { it.copy(haMusicEqTrebleDb = com.example.ava.audio.eq.MusicEqGains.clampDb(value)) }
    }
    val haMusicEqAdaptive = SettingState(getFlow().map { it.haMusicEqAdaptive }) { value ->
        update { it.copy(haMusicEqAdaptive = value) }
    }
    suspend fun setHaMusicEq(gains: com.example.ava.audio.eq.MusicEqGains) {
        val n = gains.normalized()
        update {
            it.copy(
                haMusicEqEnabled = n.enabled,
                haMusicEqBassDb = n.bassDb,
                haMusicEqLowMidDb = n.lowMidDb,
                haMusicEqMidDb = n.midDb,
                haMusicEqUpperMidDb = n.upperMidDb,
                haMusicEqTrebleDb = n.trebleDb,
                haMusicEqAdaptive = n.adaptiveEnabled,
            )
        }
    }
    val mediaOverlayStyle = SettingState(getFlow().map { it.mediaOverlayStyle }) { value ->
        update { it.copy(mediaOverlayStyle = value.ifBlank { MediaOverlayStyle.MINIMAL.storageKey }) }
    }
    /** @deprecated Karaoke is always on; accessor kept for restore-path parity. */
    val enableKaraokeLyrics = SettingState(getFlow().map { it.enableKaraokeLyrics }) { value ->
        update { it.copy(enableKaraokeLyrics = value) }
    }
    val enableEqMiniPlayer = SettingState(getFlow().map { it.enableEqMiniPlayer }) { value ->
        update {
            if (value) {
                it.copy(enableEqMiniPlayer = true)
            } else {
                // Turning off restores default bottom-end placement next time.
                it.copy(
                    enableEqMiniPlayer = false,
                    eqMiniFabNormX = -1f,
                    eqMiniFabNormY = -1f,
                    eqMiniFabX = -1,
                    eqMiniFabY = -1,
                )
            }
        }
    }
    val enableVinylCoverDisplay = SettingState(getFlow().map { it.enableVinylCoverDisplay }) { value ->
        update { it.copy(enableVinylCoverDisplay = value) }
    }
    val enableVinylCoverVisible = SettingState(getFlow().map { it.enableVinylCoverVisible }) { value ->
        update { it.copy(enableVinylCoverVisible = value) }
    }
    val eqMiniFabNormX = SettingState(getFlow().map { it.eqMiniFabNormX }) { value ->
        update { it.copy(eqMiniFabNormX = value) }
    }
    val eqMiniFabNormY = SettingState(getFlow().map { it.eqMiniFabNormY }) { value ->
        update { it.copy(eqMiniFabNormY = value) }
    }
    val enableDreamClock = SettingState(getFlow().map { it.enableDreamClock }) { value ->
        update { it.copy(enableDreamClock = value) }
    }
    val enableDreamClockDisplay = SettingState(getFlow().map { it.enableDreamClockDisplay }) { value ->
        update { it.copy(enableDreamClockDisplay = value) }
    }
    val enableDreamClockVisible = SettingState(getFlow().map { it.enableDreamClockVisible }) { value ->
        update { it.copy(enableDreamClockVisible = value) }
    }
    val dreamClockFace = SettingState(getFlow().map { it.dreamClockFace }) { value ->
        update { it.copy(dreamClockFace = DreamClockFace.fromStored(value).storageKey) }
    }
    val dreamClockFlipStyle = SettingState(getFlow().map { it.dreamClockFlipStyle }) { value ->
        update { it.copy(dreamClockFlipStyle = DreamClockFlipStyle.fromStored(value).storageKey) }
    }
    val dreamClockSeason = SettingState(getFlow().map { it.dreamClockSeason }) { value ->
        update { it.copy(dreamClockSeason = DreamClockSeason.fromStored(value).storageKey) }
    }
    val dreamClockFlipShowSeconds = SettingState(getFlow().map { it.dreamClockFlipShowSeconds }) { value ->
        update { it.copy(dreamClockFlipShowSeconds = value) }
    }
    val dreamClockFlip12Hour = SettingState(getFlow().map { it.dreamClockFlip12Hour }) { value ->
        update { it.copy(dreamClockFlip12Hour = value) }
    }
    val dreamClockFlipFont = SettingState(getFlow().map { it.dreamClockFlipFont }) { value ->
        update { it.copy(dreamClockFlipFont = DreamClockFlipFont.fromStored(value).storageKey) }
    }
    val dreamClockSettingsLocked = SettingState(getFlow().map { it.dreamClockSettingsLocked }) { value ->
        update { it.copy(dreamClockSettingsLocked = value) }
    }
    val dreamClockTimerEntityId = SettingState(getFlow().map { it.dreamClockTimerEntityId }) { value ->
        update { it.copy(dreamClockTimerEntityId = value.trim()) }
    }
    val dreamClockTimerSoundEnabled = SettingState(getFlow().map { it.dreamClockTimerSoundEnabled }) { value ->
        update { it.copy(dreamClockTimerSoundEnabled = value) }
    }
    val dreamClockTimerSound = SettingState(getFlow().map { it.dreamClockTimerSound }) { value ->
        update { it.copy(dreamClockTimerSound = value) }
    }
    val dreamClockFlipStyleHaSelect = SettingState(getFlow().map { it.dreamClockFlipStyleHaSelect }) { value ->
        update { it.copy(dreamClockFlipStyleHaSelect = value) }
    }
    val dreamClockSmartAodEnabled = SettingState(getFlow().map { it.dreamClockSmartAodEnabled }) { value ->
        update { it.copy(dreamClockSmartAodEnabled = value) }
    }
    val dreamClockSmartAodTimeoutSeconds = SettingState(getFlow().map { it.dreamClockSmartAodTimeoutSeconds }) { value ->
        update { it.copy(dreamClockSmartAodTimeoutSeconds = value.coerceIn(10, 3600)) }
    }
    val dreamClockSmartAodMaskPercent = SettingState(getFlow().map { it.dreamClockSmartAodMaskPercent }) { value ->
        update { it.copy(dreamClockSmartAodMaskPercent = value.coerceIn(5, 100)) }
    }
    val enableScreensaver = SettingState(getFlow().map { it.enableScreensaver }) { value ->
        update { it.copy(enableScreensaver = value) }
    }
    val enableScreensaverDisplay = SettingState(getFlow().map { it.enableScreensaverDisplay }) { value ->
        update { it.copy(enableScreensaverDisplay = value) }
    }
    val enableScreensaverVisible = SettingState(getFlow().map { it.enableScreensaverVisible }) { value ->
        update { it.copy(enableScreensaverVisible = value) }
    }
    val screensaverDoubleTapToggleEnabled = SettingState(getFlow().map { it.screensaverDoubleTapToggleEnabled }) { value ->
        update { it.copy(screensaverDoubleTapToggleEnabled = value) }
    }
    val screensaverWallpaperUrl = SettingState(getFlow().map { it.screensaverWallpaperUrl }) { value ->
        update { it.copy(screensaverWallpaperUrl = value.trim()) }
    }
    val screensaverWallpaperRefreshSeconds = SettingState(getFlow().map { it.screensaverWallpaperRefreshSeconds }) { value ->
        update { it.copy(screensaverWallpaperRefreshSeconds = value) }
    }
    val screensaverWallpaperDualPane = SettingState(getFlow().map { it.screensaverWallpaperDualPane }) { value ->
        update { it.copy(screensaverWallpaperDualPane = value) }
    }
    val screensaverWallpaperDarkOverlayEnabled = SettingState(getFlow().map { it.screensaverWallpaperDarkOverlayEnabled }) { value ->
        update { it.copy(screensaverWallpaperDarkOverlayEnabled = value) }
    }
    val enableScreensaverWeather = SettingState(getFlow().map { it.enableScreensaverWeather }) { value ->
        update { it.copy(enableScreensaverWeather = value) }
    }
    val screensaverWeatherEntityId = SettingState(getFlow().map { it.screensaverWeatherEntityId }) { value ->
        update {
            val trimmed = value.trim()
            it.copy(
                screensaverWeatherEntityId = if (
                    trimmed.isEmpty() || trimmed.startsWith("weather.")
                ) trimmed else ""
            )
        }
    }
    val screensaverPortraitStyle = SettingState(getFlow().map { it.screensaverPortraitStyle }) { value ->
        update { it.copy(screensaverPortraitStyle = SimpleClockPortraitStyle.fromStored(value).storageKey) }
    }
    val screensaver12HourEnabled = SettingState(getFlow().map { it.screensaver12HourEnabled }) { value ->
        update { it.copy(screensaver12HourEnabled = value) }
    }
    val screensaverPixelShiftEnabled = SettingState(getFlow().map { it.screensaverPixelShiftEnabled }) { value ->
        update { it.copy(screensaverPixelShiftEnabled = value) }
    }
    val screensaverDarkOffEnabled = SettingState(getFlow().map { it.screensaverDarkOffEnabled }) { value ->
        update { it.copy(screensaverDarkOffEnabled = value) }
    }
    val smartPowerSavingAodEnabled = SettingState(getFlow().map { it.smartPowerSavingAodEnabled }) { value ->
        update { it.copy(smartPowerSavingAodEnabled = value) }
    }
    val smartPowerSavingAodTimeoutSeconds =
        SettingState(getFlow().map { it.smartPowerSavingAodTimeoutSeconds }) { value ->
            update { it.copy(smartPowerSavingAodTimeoutSeconds = value.coerceIn(10, 3600)) }
        }
    val smartPowerSavingAodMaskPercent =
        SettingState(getFlow().map { it.smartPowerSavingAodMaskPercent }) { value ->
            update { it.copy(smartPowerSavingAodMaskPercent = value.coerceIn(5, 100)) }
        }
    val enableScreensaverStatusSlots = SettingState(getFlow().map { it.enableScreensaverStatusSlots }) { value ->
        update { it.copy(enableScreensaverStatusSlots = value) }
    }
    val enableScreensaverStatusHaSlots = SettingState(getFlow().map { it.enableScreensaverStatusHaSlots }) { value ->
        update { it.copy(enableScreensaverStatusHaSlots = value) }
    }
    val screensaverStatusSlots = SettingState(getFlow().map { it.screensaverStatusSlots }) { value ->
        update { it.copy(screensaverStatusSlots = value) }
    }

    /**
     * HA text-entity binding for one Simple Clock status slot (same idea as Quick Entity
     * [QuickEntitySettingsStore.slotEntityId]): writing an entity ID auto-fills icon/label.
     */
    fun statusSlotEntityId(index: Int) = SettingState(
        getFlow().map { it.screensaverStatusSlots.getOrNull(index)?.entityId ?: "" }
    ) { entityId ->
        update { settings ->
            val newSlots = settings.screensaverStatusSlots.toMutableList()
            while (newSlots.size < 3) newSlots.add(SimpleClockStatusSlot())
            if (index !in newSlots.indices) return@update settings
            val trimmed = entityId.trim()
            newSlots[index] = if (trimmed.isEmpty()) {
                SimpleClockStatusSlot()
            } else {
                // Leave label empty so the clock can auto-truncate English to 2 words;
                // only a user-typed display name in settings is treated as custom.
                // Same careful rules as Dawn capsules (temp/humidity before "light").
                val icon = guessDawnSlotIcon(trimmed)
                val previous = newSlots[index]
                SimpleClockStatusSlot(
                    entityId = trimmed,
                    icon = if (previous.icon.isNotBlank() &&
                        previous.icon != "mdi:home-assistant" &&
                        previous.entityId == trimmed
                    ) previous.icon else icon,
                    label = ""
                )
            }
            settings.copy(screensaverStatusSlots = newSlots.take(3))
        }
    }
    val enableWeatherOverlay = SettingState(getFlow().map { it.enableWeatherOverlay }) { value ->
        update { it.copy(enableWeatherOverlay = value) }
    }
    val enableWeatherOverlayDisplay = SettingState(getFlow().map { it.enableWeatherOverlayDisplay }) { value ->
        update { it.copy(enableWeatherOverlayDisplay = value) }
    }
    val enableWeatherOverlayVisible = SettingState(getFlow().map { it.enableWeatherOverlayVisible }) { value ->
        update { it.copy(enableWeatherOverlayVisible = value) }
    }
    val haWeatherEntity = SettingState(getFlow().map { it.haWeatherEntity }) { value ->
        update { it.copy(haWeatherEntity = value) }
    }
    val enableAutoRestart = SettingState(getFlow().map { it.enableAutoRestart }) { value ->
        update { it.copy(enableAutoRestart = value) }
    }
    val startServiceOnAppOpen = SettingState(getFlow().map { it.startServiceOnAppOpen }) { value ->
        update { it.copy(startServiceOnAppOpen = value) }
    }
    val startServiceOnAppOpenDelaySeconds =
        SettingState(getFlow().map { it.startServiceOnAppOpenDelaySeconds }) { value ->
            update {
                it.copy(
                    startServiceOnAppOpenDelaySeconds =
                        PlayerSettings.clampStartServiceOnAppOpenDelaySeconds(value),
                )
            }
        }
    val enableCrashSelfHeal = SettingState(getFlow().map { it.enableCrashSelfHeal }) { value ->
        update { it.copy(enableCrashSelfHeal = value) }
    }
    val keepCpuAwakeOnScreenOff = SettingState(getFlow().map { it.keepCpuAwakeOnScreenOff }) { value ->
        update { it.copy(keepCpuAwakeOnScreenOff = value) }
    }
    val enableMinimalLauncher = SettingState(getFlow().map { it.enableMinimalLauncher }) { value ->
        update { it.copy(enableMinimalLauncher = value) }
    }
    val minimalLauncherVisiblePackages =
        SettingState(getFlow().map { it.minimalLauncherVisiblePackages }) { value ->
            update { it.copy(minimalLauncherVisiblePackages = value.distinct().sorted()) }
        }
    val enableMinimalLauncherHaDisplay =
        SettingState(getFlow().map { it.enableMinimalLauncherHaDisplay }) { value ->
            update { it.copy(enableMinimalLauncherHaDisplay = value) }
        }
    val appWindowPackages =
        SettingState(getFlow().map { it.appWindowPackages }) { value ->
            update { it.copy(appWindowPackages = value.distinct().sorted()) }
        }
    val minimalLauncherIconPack =
        SettingState(getFlow().map { it.minimalLauncherIconPack }) { value ->
            update { it.copy(minimalLauncherIconPack = value) }
        }
    val minimalLauncherIconShape =
        SettingState(getFlow().map { it.minimalLauncherIconShape }) { value ->
            update { it.copy(minimalLauncherIconShape = value) }
        }
    val minimalLauncherShowDesktopLabels =
        SettingState(getFlow().map { it.minimalLauncherShowDesktopLabels }) { value ->
            update { it.copy(minimalLauncherShowDesktopLabels = value) }
        }
    val minimalLauncherWallpaperUri =
        SettingState(getFlow().map { it.minimalLauncherWallpaperUri }) { value ->
            update { it.copy(minimalLauncherWallpaperUri = value.trim()) }
        }
    val minimalLauncherWallpaperMode =
        SettingState(getFlow().map { it.minimalLauncherWallpaperMode }) { value ->
            update {
                it.copy(
                    minimalLauncherWallpaperMode =
                        value.takeIf { mode -> mode in setOf("", "image", "none") } ?: ""
                )
            }
        }
    val enableHaSwitchOverlay = SettingState(getFlow().map { it.enableHaSwitchOverlay }) { value ->
        update { it.copy(enableHaSwitchOverlay = value) }
    }
    val enableManualDismissButton = SettingState(getFlow().map { it.enableManualDismissButton }) { value ->
        update { it.copy(enableManualDismissButton = value) }
    }
    val enableVoiceMessageReceive = SettingState(getFlow().map { it.enableVoiceMessageReceive }) { value ->
        update { it.copy(enableVoiceMessageReceive = value) }
    }
    val enableVoiceMessageOverlay = SettingState(getFlow().map { it.enableVoiceMessageOverlay }) { value ->
        update { it.copy(enableVoiceMessageOverlay = value) }
    }
    val enableVoiceMessageOverlayDisplay = SettingState(getFlow().map { it.enableVoiceMessageOverlayDisplay }) { value ->
        update { it.copy(enableVoiceMessageOverlayDisplay = value) }
    }
    val enableVoiceMessageOverlayVisible = SettingState(getFlow().map { it.enableVoiceMessageOverlayVisible }) { value ->
        update { it.copy(enableVoiceMessageOverlayVisible = value) }
    }
    val enableVoiceOverlayIntercom = SettingState(getFlow().map { it.enableVoiceOverlayIntercom }) { value ->
        update { it.copy(enableVoiceOverlayIntercom = value) }
    }
    val enableVoiceOverlayCall = SettingState(getFlow().map { it.enableVoiceOverlayCall }) { value ->
        update { it.copy(enableVoiceOverlayCall = value) }
    }
    val voiceMessageDisplayName = SettingState(getFlow().map { it.voiceMessageDisplayName }) { value ->
        update { it.copy(voiceMessageDisplayName = value.trim()) }
    }
    val voiceMessageDelayMinutes = SettingState(getFlow().map { it.voiceMessageDelayMinutes }) { value ->
        update { it.copy(voiceMessageDelayMinutes = value.coerceIn(0, 1440)) }
    }
    val voiceMessageReceiveMode = SettingState(getFlow().map { it.voiceMessageReceiveMode }) { value ->
        update { it.copy(voiceMessageReceiveMode = value.ifBlank { "auto" }) }
    }
    val enableVoiceCallAnswerRequired = SettingState(getFlow().map { it.enableVoiceCallAnswerRequired }) { value ->
        update { it.copy(enableVoiceCallAnswerRequired = value) }
    }
    val voiceCallRingtone = SettingState(getFlow().map { it.voiceCallRingtone }) { value ->
        update {
            val normalized = when {
                value.isBlank() -> PlayerSettings.DEFAULT_VOICE_CALL_RINGTONE
                value.endsWith("/voice_call_ringtone.mp3") -> PlayerSettings.DEFAULT_VOICE_CALL_RINGTONE
                else -> value
            }
            it.copy(voiceCallRingtone = normalized)
        }
    }
    val voiceCallVideoQuality = SettingState(getFlow().map { it.voiceCallVideoQuality }) { value ->
        update { it.copy(voiceCallVideoQuality = value.ifBlank { "smooth" }) }
    }
    val voiceWakeWord1AccentColor = SettingState(getFlow().map { it.voiceWakeWord1AccentColor }) { value ->
        update { it.copy(voiceWakeWord1AccentColor = value.trim()) }
    }
    val voiceWakeWord2AccentColor = SettingState(getFlow().map { it.voiceWakeWord2AccentColor }) { value ->
        update { it.copy(voiceWakeWord2AccentColor = value.trim()) }
    }
    val enableVoiceRippleEffect = SettingState(getFlow().map { it.enableVoiceRippleEffect }) { value ->
        update { it.copy(enableVoiceRippleEffect = value) }
    }
    val enableVoiceEdgeGlow = SettingState(getFlow().map { it.enableVoiceEdgeGlow }) { value ->
        update { it.copy(enableVoiceEdgeGlow = value) }
    }
    val voiceEdgeGlowLevelGain = SettingState(getFlow().map { it.voiceEdgeGlowLevelGain }) { value ->
        update { it.copy(voiceEdgeGlowLevelGain = PlayerSettings.clampEdgeGlowLevelGain(value)) }
    }
    val voiceWakeWord1EdgeGlowLevelGain = SettingState(getFlow().map { it.voiceWakeWord1EdgeGlowLevelGain }) { value ->
        update { current ->
            val clamped = PlayerSettings.clampEdgeGlowLevelGain(value)
            val ww2 = if (current.voiceEdgeGlowLevelGainSplit) {
                current.voiceWakeWord2EdgeGlowLevelGain
            } else {
                current.voiceEdgeGlowLevelGain
            }
            current.copy(
                voiceWakeWord1EdgeGlowLevelGain = clamped,
                voiceWakeWord2EdgeGlowLevelGain = PlayerSettings.clampEdgeGlowLevelGain(ww2),
                voiceEdgeGlowLevelGainSplit = true,
            )
        }
    }
    val voiceWakeWord2EdgeGlowLevelGain = SettingState(getFlow().map { it.voiceWakeWord2EdgeGlowLevelGain }) { value ->
        update { current ->
            val clamped = PlayerSettings.clampEdgeGlowLevelGain(value)
            val ww1 = if (current.voiceEdgeGlowLevelGainSplit) {
                current.voiceWakeWord1EdgeGlowLevelGain
            } else {
                current.voiceEdgeGlowLevelGain
            }
            current.copy(
                voiceWakeWord1EdgeGlowLevelGain = PlayerSettings.clampEdgeGlowLevelGain(ww1),
                voiceWakeWord2EdgeGlowLevelGain = clamped,
                voiceEdgeGlowLevelGainSplit = true,
            )
        }
    }
    val voiceWakeWord1EdgeGlowSheer = SettingState(getFlow().map { it.voiceWakeWord1EdgeGlowSheer }) { value ->
        update { it.copy(voiceWakeWord1EdgeGlowSheer = PlayerSettings.clampEdgeGlowSheer(value)) }
    }
    val voiceWakeWord2EdgeGlowSheer = SettingState(getFlow().map { it.voiceWakeWord2EdgeGlowSheer }) { value ->
        update { it.copy(voiceWakeWord2EdgeGlowSheer = PlayerSettings.clampEdgeGlowSheer(value)) }
    }
    val enableWhisperResponse = SettingState(getFlow().map { it.enableWhisperResponse }) { value ->
        update { it.copy(enableWhisperResponse = value) }
    }
    val enableWhisperResponseAdaptive = SettingState(getFlow().map { it.enableWhisperResponseAdaptive }) { value ->
        update { it.copy(enableWhisperResponseAdaptive = value) }
    }
    val whisperResponseVolume = SettingState(getFlow().map { it.whisperResponseVolume }) { value ->
        update { it.copy(whisperResponseVolume = value.coerceIn(PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME, 1f)) }
    }
    val enableAmbientAutoGain = SettingState(getFlow().map { it.enableAmbientAutoGain }) { value ->
        update { it.copy(enableAmbientAutoGain = value) }
    }
    val exposeWhisperResponseEntity = SettingState(getFlow().map { it.exposeWhisperResponseEntity }) { value ->
        update { it.copy(exposeWhisperResponseEntity = value) }
    }
    val preserveTtsHttps = SettingState(getFlow().map { it.preserveTtsHttps }) { value ->
        update { it.copy(preserveTtsHttps = value) }
    }
}
