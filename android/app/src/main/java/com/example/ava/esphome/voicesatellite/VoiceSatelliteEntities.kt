package com.example.ava.esphome.voicesatellite

import android.content.Context
import android.content.SharedPreferences
import com.example.ava.R
import com.example.ava.audio.AmbientAutoGain
import com.example.ava.esphome.entities.ButtonEntity
import com.example.ava.esphome.entities.LockEntity
import com.example.ava.esphome.entities.MediaPlayerEntity
import com.example.ava.esphome.entities.NumberEntity
import com.example.ava.esphome.entities.SelectEntity
import com.example.ava.esphome.entities.ServiceArg
import com.example.ava.esphome.entities.ServiceEntity
import com.example.ava.esphome.entities.SwitchEntity
import com.example.ava.clock.ClockAlertSensor
import com.example.ava.esphome.entities.TextEntity
import com.example.ava.notifications.NotificationScenes
import com.example.ava.notifications.SceneReset
import com.example.ava.settings.BrowserPowerMode
import com.example.ava.settings.BrowserSettings
import com.example.ava.settings.DarkModeManager
import com.example.ava.settings.ExperimentalSettings
import com.example.ava.settings.NotificationSettingsStore
import com.example.ava.settings.PlayerSettings
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.ScreensaverSettings
import com.example.ava.settings.ScreensaverSettingsStore
import com.example.ava.settings.WakeWordEngine
import com.example.ava.settings.normalizeScreensaverTimeoutSeconds
import com.example.ava.settings.screensaverSettingsStore
import com.example.ava.settings.quickEntitySettingsStore
import com.example.ava.settings.QuickEntitySettingsStore
import com.example.ava.settings.wakeWordEngineFromHaOption
import com.example.ava.services.AppWindowService
import com.example.ava.touchpad.TouchPadHa
import com.example.ava.touchpad.TouchPadPrefs
import com.example.ava.ui.AvaSystemChrome
import com.example.ava.ui.SystemBarsMode
import com.example.ava.ui.screens.home.HaLauncherAction
import com.example.ava.ui.screens.home.PREFS_NAME
import com.example.ava.ui.screens.home.bringAvaDesktopToFront
import com.example.ava.ui.screens.home.closeCurrentForegroundIfNotAva
import com.example.ava.ui.screens.home.haLauncherSelectOptions
import com.example.ava.ui.screens.home.haMinimalLauncherApps
import com.example.ava.ui.screens.home.launchMinimalLauncherApp
import com.example.ava.ui.screens.home.maybeLaunchInAppWindow
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.voice.AvaVoiceHaController
import com.example.esphomeproto.api.EntityCategory
import com.example.esphomeproto.api.NumberMode
import com.example.esphomeproto.api.ServiceArgType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlin.math.roundToInt

object VoiceSatelliteEntities {
    
    fun buildEntities(
        audioInput: VoiceSatelliteAudioInput,
        player: VoiceSatellitePlayer,
        voiceChannelEnabled: Boolean,
        notificationSettingsStore: NotificationSettingsStore,
        onRestartService: (() -> Unit)?,
        context: Context,
        experimentalSettingsData: ExperimentalSettings,
        browserSettingsData: BrowserSettings,
        screensaverSettingsData: ScreensaverSettings,
        playerSettingsStore: PlayerSettingsStore? = null,
        playerSettingsData: PlayerSettings? = null,
        wakeWordEngine: Flow<WakeWordEngine>? = null,
        onWakeWordEngineChanged: ((WakeWordEngine) -> Unit)? = null,
        onMicrophoneVolumeChanged: ((Float) -> Unit)? = null
    ) = buildList {
        ScreenControlUtils.syncScreenState(context)

        if (onRestartService != null) {
            add(ButtonEntity(
                key = 4,
                name = context.getString(R.string.entity_restart_service),
                objectId = "restart_service",
                icon = "mdi:restart",
                entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC,
                onPress = { onRestartService() }
            ))
        }

        if (experimentalSettingsData.systemBarsHaEnabled) {
            add(systemBarsSelect(context))
        }

        if (voiceChannelEnabled) {
            add(SwitchEntity(
                1,
                context.getString(R.string.entity_mute_microphone),
                "mute_microphone",
                "mdi:microphone-off",
                audioInput.muted,
                EntityCategory.ENTITY_CATEGORY_NONE
            ) { audioInput.setMuted(it) })

            add(NumberEntity(
                key = "microphone_volume".hashCode(),
                name = context.getString(R.string.entity_microphone_volume),
                objectId = "microphone_volume",
                icon = "mdi:microphone",
                minValue = 0.0f,
                maxValue = 2.0f,
                step = 0.1f,
                getState = audioInput.microphoneVolume,
                setState = { volume ->
                    audioInput.setMicrophoneVolume(volume)
                    onMicrophoneVolumeChanged?.invoke(volume)
                },
                entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
            ))
        }

        if (experimentalSettingsData.screenPowerControlHaDisplayEnabled) {
            add(SwitchEntity(
                3,
                context.getString(R.string.entity_screen_toggle),
                "screen_toggle",
                "mdi:monitor",
                ScreenControlUtils.panelOnState,
                EntityCategory.ENTITY_CATEGORY_NONE,
                acceptedState = { ScreenControlUtils.panelOnState.value },
            ) {
                ScreenControlUtils.setScreenOn(context, it)
            })

            add(LockEntity(
                key = 29,
                name = context.getString(R.string.entity_lock_screen),
                objectId = "lock_screen",
                icon = "mdi:monitor-lock",
                getState = ScreenControlUtils.lockState,
                entityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
                setState = { command, _ ->
                    ScreenControlUtils.handleLockCommand(context, command, null)
                }
            ))
        }

        // Dark mode HA entity — only when the experimental "sync to Home Assistant" toggle is on.
        // Browser "follow dark mode" syncs the dashboard via JS injection only; it does not expose this entity.
        if (experimentalSettingsData.syncDarkModeToHass) {
            val darkModeManager = DarkModeManager.getInstance(context)
            add(SwitchEntity(
                40,
                context.getString(R.string.settings_dark_mode),
                "dark_mode",
                "mdi:theme-light-dark",
                darkModeManager.darkMode,
                EntityCategory.ENTITY_CATEGORY_NONE
            ) { enabled ->
                darkModeManager.setDarkMode(enabled)
            })
        }

        val exposeNativeMediaPlayer = playerSettingsData?.exposeEsphomeMediaPlayerEntity != false
        if (exposeNativeMediaPlayer) {
            add(MediaPlayerEntity(0, context.getString(R.string.entity_media_player), "media_player", player))
        }
        
        if (voiceChannelEnabled) {
            add(SwitchEntity(
                2,
                context.getString(R.string.entity_wake_sound),
                "play_wake_sound",
                "mdi:bell-ring",
                player.enableWakeSound,
                EntityCategory.ENTITY_CATEGORY_NONE
            ) { player.enableWakeSound.set(it) })

            if (wakeWordEngine != null && onWakeWordEngineChanged != null) {
                val microWakeWordLabel = context.getString(R.string.option_wake_word_engine_micro)
                val vsWakeWordLabel = context.getString(R.string.option_wake_word_engine_vs)
                add(SelectEntity(
                    key = "wake_word_engine".hashCode(),
                    name = context.getString(R.string.label_voice_satellite_wake_word_engine),
                    objectId = "wake_word_engine",
                    icon = "mdi:account-voice",
                    options = listOf(microWakeWordLabel, vsWakeWordLabel),
                    getState = wakeWordEngine.map { engine ->
                        when (engine) {
                            WakeWordEngine.MICRO_WAKE_WORD -> microWakeWordLabel
                            WakeWordEngine.OPEN_WAKE_WORD -> vsWakeWordLabel
                        }
                    },
                    setState = { option ->
                        val engine = when (option) {
                            microWakeWordLabel -> WakeWordEngine.MICRO_WAKE_WORD
                            vsWakeWordLabel -> WakeWordEngine.OPEN_WAKE_WORD
                            else -> wakeWordEngineFromHaOption(option)
                        }
                        if (engine != null) onWakeWordEngineChanged(engine)
                    },
                    getOptions = { listOf(microWakeWordLabel, vsWakeWordLabel) },
                    entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
                ))
            }

        }

        val browserSettings = com.example.ava.settings.BrowserSettingsStore(context)
        if (browserSettingsData.haRemoteUrlEnabled) {
            // Always keep the original ha_remote_url entity (left / single pane).
            add(TextEntity(
                6,
                context.getString(R.string.entity_ha_remote_url),
                "ha_remote_url",
                "mdi:web",
                player.haRemoteUrl,
                { url -> player.setHaRemoteUrl(url) }
            ))
            val split =
                browserSettingsData.splitViewEnabled &&
                    browserSettingsData.browserEngine != com.example.ava.webcompat.BrowserEngine.GECKO
            if (split) {
                add(TextEntity(
                    "ha_remote_url_right".hashCode(),
                    context.getString(R.string.entity_ha_remote_url_right),
                    "ha_remote_url_right",
                    "mdi:web",
                    player.haRemoteUrlRight,
                    { url -> player.setHaRemoteUrlRight(url) }
                ))
            }
        }

        val screensaverSettings = ScreensaverSettingsStore(context.screensaverSettingsStore)
        if (screensaverSettingsData.enabled && screensaverSettingsData.enableHaDisplay) {
            add(SwitchEntity(
                24,
                context.getString(R.string.entity_screensaver_display),
                "screensaver_display",
                "mdi:monitor-eye",
                screensaverSettings.visible,
                EntityCategory.ENTITY_CATEGORY_NONE
            ) { enabled -> screensaverSettings.visible.set(enabled) })
        }
        
        if (screensaverSettingsData.enabled && screensaverSettingsData.screensaverUrlVisible) {
            add(TextEntity(
                25,
                context.getString(R.string.entity_screensaver_url),
                "screensaver_url",
                "mdi:web",
                screensaverSettings.screensaverUrl,
                { url -> screensaverSettings.screensaverUrl.set(url) },
                entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
            ))
        }

        // Opt-in: idle timeout before screensaver (same clamp as settings UI). Config category.
        // 0 = never auto-show on idle (overrides enabled; HA switch force-show still works).
        if (screensaverSettingsData.enabled && screensaverSettingsData.screensaverTimeoutVisible) {
            add(NumberEntity(
                key = "screensaver_timeout".hashCode(),
                name = context.getString(R.string.entity_screensaver_timeout),
                objectId = "screensaver_timeout",
                icon = "mdi:timer-outline",
                minValue = 0f,
                maxValue = 3600f,
                step = 1f,
                unitOfMeasurement = "s",
                getState = screensaverSettings.timeoutSeconds.map { it.toFloat() },
                setState = { seconds ->
                    screensaverSettings.timeoutSeconds.set(
                        normalizeScreensaverTimeoutSeconds(seconds.toInt())
                    )
                },
                entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG,
            ))
        }

        // Dawn capsules: optional HA text slots for quick entity swaps (4 max).
        // Gated by snapshot at entity-list build — toggle requires Voice Satellite restart
        // (entities do not appear/disappear while the service is already running).
        if (screensaverSettingsData.enabled &&
            screensaverSettingsData.dawnWallpaperEnabled &&
            screensaverSettingsData.enableDawnEntitySlots &&
            screensaverSettingsData.enableDawnEntityHaSlots
        ) {
            for (i in 0 until 4) {
                val slotState = screensaverSettings.dawnSlotEntityId(i)
                add(TextEntity(
                    // Keep wire object_id stable so existing HA entities/automations survive the rebrand.
                    key = "xiaomi_entity_slot_${i + 1}".hashCode(),
                    name = context.getString(R.string.entity_dawn_entity_slot, i + 1),
                    objectId = "xiaomi_entity_slot_${i + 1}",
                    icon = "mdi:card-text",
                    getState = slotState,
                    setState = { entityId ->
                        slotState.set(entityId)
                        com.example.ava.services.VoiceSatelliteService
                            .getInstance()
                            ?.resubscribeDawnEntitySlots()
                        com.example.ava.services.ScreensaverWebViewService
                            .pushDawnSlotsFromSettings(context)
                    },
                    entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
                ))
            }
        }

        val advancedControlEnabled = browserSettingsData.advancedControlEnabled
        if (advancedControlEnabled) {
            add(ServiceEntity(
                key = 23,
                name = "webview_command",
                args = listOf(ServiceArg("command")),
                onExecute = { args ->
                    val command = args["command"] as? String ?: ""
                    com.example.ava.services.WebViewService.executeCommand(context, command)
                }
            ))
        }

        if (notificationSettingsStore.getCached().notificationSceneEnabled) {
            add(SelectEntity(
                7,
                context.getString(R.string.entity_notification_scene),
                "notification_scene",
                "mdi:bell-badge",
                SceneReset.selectOptions(NotificationScenes.ALL_SCENE_TITLES),
                player.notificationScene,
                { sceneTitle -> player.setNotificationScene(sceneTitle) },
                { SceneReset.selectOptions(NotificationScenes.ALL_SCENE_TITLES) },
            ))
            
            add(NumberEntity(
                8,
                context.getString(R.string.entity_scene_display_duration),
                "scene_display_duration",
                "mdi:timer",
                minValue = 5f,
                maxValue = 999999f,
                step = 1f,
                unitOfMeasurement = "s",
                getState = notificationSettingsStore.sceneDisplayDuration.map { (it / 1000).toFloat() },
                setState = { seconds -> 
                    notificationSettingsStore.sceneDisplayDuration.set(seconds.toInt().coerceAtLeast(5) * 1000)
                },
                entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
            ))
        }
        
        playerSettingsStore?.let { store ->
            val playerSettingsDataResolved = playerSettingsData ?: return@let
            if (playerSettingsDataResolved.exposeWhisperResponseEntity) {
                add(NumberEntity(
                    key = "tts_volume".hashCode(),
                    name = context.getString(R.string.entity_tts_volume),
                    objectId = "tts_volume",
                    icon = "mdi:volume-high",
                    minValue = (PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME * 100f),
                    maxValue = 100f,
                    step = 1f,
                    unitOfMeasurement = "%",
                    getState = combine(
                        store.whisperResponseVolume,
                        store.enableAmbientAutoGain,
                        audioInput.previewAmbientFlow(),
                    ) { slider, autoGain, rms ->
                        val level = if (autoGain) {
                            AmbientAutoGain.output(slider, rms)
                        } else {
                            AmbientAutoGain.snapPercent(slider)
                        }
                        (level * 100f).roundToInt().toFloat()
                    }.distinctUntilChanged { old, new -> old.toInt() == new.toInt() },
                    setState = { percent ->
                        val volume = (percent / 100f).coerceIn(
                            PlayerSettings.MIN_WHISPER_RESPONSE_VOLUME,
                            1f,
                        )
                        store.whisperResponseVolume.set(volume)
                        com.example.ava.services.VoiceSatelliteService.applyVoiceReplyVolumeLive(volume)
                    },
                    entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG,
                    mode = NumberMode.NUMBER_MODE_BOX,
                ))
            }
            if (playerSettingsDataResolved.enableTimerStopButton) {
                add(ButtonEntity(
                    key = 38,
                    name = context.getString(R.string.entity_stop_alarm),
                    objectId = "stop_alarm",
                    icon = "mdi:alarm-off",
                    entityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
                    onPress = {
                        com.example.ava.services.VoiceSatelliteService.getInstance()?.stopTimerSound()
                    }
                ))
            }
            // Like stop_alarm / weather HA switches: entity list is a start-time snapshot.
            // Toggling enableManualDismissButton restarts the satellite so HA rediscovers it.
            if (voiceChannelEnabled && playerSettingsDataResolved.enableManualDismissButton) {
                add(ButtonEntity(
                    key = "manual_dismiss".hashCode(),
                    name = context.getString(R.string.entity_manual_dismiss),
                    objectId = "manual_dismiss",
                    icon = "mdi:comment-off-outline",
                    entityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
                    onPress = {
                        com.example.ava.services.VoiceSatelliteService.stopVoiceSession()
                    }
                ))
            }
            if (playerSettingsDataResolved.enableWeatherOverlay && playerSettingsDataResolved.enableWeatherOverlayDisplay) {
                add(SwitchEntity(
                    26,
                    context.getString(R.string.entity_weather_display),
                    "weather_display",
                    "mdi:weather-partly-cloudy",
                    store.enableWeatherOverlayVisible,
                    EntityCategory.ENTITY_CATEGORY_NONE
                ) { enabled ->
                    store.enableWeatherOverlayVisible.set(enabled)
                })
            }

            if (playerSettingsDataResolved.enableVinylCoverDisplay) {
                add(SwitchEntity(
                    key = "vinyl_cover_display".hashCode(),
                    name = context.getString(R.string.entity_vinyl_cover_display),
                    objectId = "vinyl_cover_display",
                    icon = "mdi:album",
                    getState = store.enableVinylCoverVisible,
                    entityCategory = EntityCategory.ENTITY_CATEGORY_NONE
                ) { enabled ->
                    store.enableVinylCoverVisible.set(enabled)
                })
            }
            
            if (playerSettingsDataResolved.enableDreamClock && playerSettingsDataResolved.enableDreamClockDisplay) {
                add(SwitchEntity(
                    27,
                    context.getString(R.string.entity_dream_clock_display),
                    "dream_clock_display",
                    "mdi:clock-outline",
                    store.enableDreamClockVisible,
                    EntityCategory.ENTITY_CATEGORY_NONE
                ) { enabled ->
                    store.enableDreamClockVisible.set(enabled)
                })
                add(TextEntity(
                    key = 43,
                    name = context.getString(R.string.entity_dream_clock_timer),
                    objectId = "dream_clock_timer",
                    icon = "mdi:timer-outline",
                    getState = store.dreamClockTimerEntityId,
                    setState = { entityId ->
                        store.dreamClockTimerEntityId.set(entityId)
                        com.example.ava.services.VoiceSatelliteService.getInstance()?.resubscribeDreamClockTimer()
                    },
                    entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
                ))
                if (playerSettingsDataResolved.dreamClockFlipStyleHaSelect) {
                    val styleLabels = com.example.ava.settings.DreamClockFlipStyle.entries
                        .associateWith { it.displayName(context) }
                    add(SelectEntity(
                        key = 44,
                        name = context.getString(R.string.entity_dream_clock_flip_style),
                        objectId = "dream_clock_flip_style",
                        icon = "mdi:palette",
                        options = styleLabels.values.toList(),
                        getState = store.dreamClockFlipStyle.map { stored ->
                            styleLabels.getValue(com.example.ava.settings.DreamClockFlipStyle.fromStored(stored))
                        },
                        setState = { label ->
                            styleLabels.entries.firstOrNull { it.value == label }?.let { entry ->
                                store.dreamClockFlipStyle.set(entry.key.storageKey)
                            }
                        },
                        entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
                    ))
                }
            }
            
            if (playerSettingsDataResolved.enableScreensaver && playerSettingsDataResolved.enableScreensaverDisplay) {
                add(SwitchEntity(
                    38,
                    context.getString(R.string.entity_simple_clock_display),
                    "simple_clock_display",
                    "mdi:clock-digital",
                    store.enableScreensaverVisible,
                    EntityCategory.ENTITY_CATEGORY_NONE
                ) { enabled ->
                    store.enableScreensaverVisible.set(enabled)
                })
            }

            // Simple Clock status chips: optional HA text slots for quick entity swaps (3 max).
            if (playerSettingsDataResolved.enableScreensaver &&
                playerSettingsDataResolved.enableScreensaverStatusSlots &&
                playerSettingsDataResolved.enableScreensaverStatusHaSlots
            ) {
                for (i in 0 until 3) {
                    val slotState = store.statusSlotEntityId(i)
                    add(TextEntity(
                        key = "simple_clock_status_slot_${i + 1}".hashCode(),
                        name = context.getString(R.string.entity_simple_clock_status_slot, i + 1),
                        objectId = "simple_clock_status_slot_${i + 1}",
                        icon = "mdi:card-text",
                        getState = slotState,
                        setState = { entityId ->
                            slotState.set(entityId)
                            com.example.ava.services.VoiceSatelliteService
                                .getInstance()
                                ?.resubscribeScreensaverStatusSlots()
                        },
                        entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
                    ))
                }
            }

            if (playerSettingsDataResolved.enableVoiceMessageOverlay && playerSettingsDataResolved.enableVoiceMessageOverlayDisplay) {
                add(SwitchEntity(
                    39,
                    context.getString(R.string.entity_voice_message_display),
                    "voice_message_display",
                    "mdi:record-rec",
                    store.enableVoiceMessageOverlayVisible,
                    EntityCategory.ENTITY_CATEGORY_NONE
                ) { enabled ->
                    store.enableVoiceMessageOverlayVisible.set(enabled)
                })
            }

            if (playerSettingsDataResolved.enableVoiceMessageOverlay) {
                add(NumberEntity(
                    key = "voice_message_delay_minutes".hashCode(),
                    name = context.getString(R.string.entity_voice_message_delay_minutes),
                    objectId = "voice_message_delay_minutes",
                    icon = "mdi:timer-outline",
                    minValue = 0f,
                    maxValue = 1440f,
                    step = 1f,
                    unitOfMeasurement = "min",
                    getState = store.voiceMessageDelayMinutes.map { it.toFloat() },
                    setState = { minutes ->
                        store.voiceMessageDelayMinutes.set(minutes.toInt().coerceIn(0, 1440))
                    },
                    entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
                ))
            }

            if (playerSettingsDataResolved.enableMinimalLauncher &&
                playerSettingsDataResolved.enableMinimalLauncherHaDisplay
            ) {
                val idle = context.getString(R.string.entity_minimal_launcher_app_idle)
                val close = context.getString(R.string.entity_minimal_launcher_app_close)
                // Reuse the launcher app name verbatim so the row reads as "our
                // own app", not some mystery third-party entry.
                val ava = context.getString(R.string.app_name)
                val reserved = listOf(idle, close, ava)
                val visiblePackages = playerSettingsDataResolved.minimalLauncherVisiblePackages
                val windowedPackages = playerSettingsDataResolved.appWindowPackages
                val haApps = haMinimalLauncherApps(context, visiblePackages)
                if (haApps.isNotEmpty()) {
                    // Floating-window apps get two static rows (open/close window);
                    // everything else keeps its single fullscreen-jump row. Editing
                    // either list flows through the settings-driven HA rediscover.
                    fun currentOptions() = haLauncherSelectOptions(
                        context,
                        haMinimalLauncherApps(context, visiblePackages),
                        windowedPackages,
                        reserved,
                    )
                    val labeled = haLauncherSelectOptions(context, haApps, windowedPackages, reserved)
                    val options = listOf(idle, close, ava) + labeled.map { it.option }
                    val state = MutableStateFlow(idle)
                    add(SelectEntity(
                        key = "minimal_launcher_app".hashCode(),
                        name = context.getString(R.string.entity_minimal_launcher_app),
                        objectId = "minimal_launcher_app",
                        icon = "mdi:apps",
                        options = options,
                        getState = state,
                        setState = { option ->
                            val appCtx = context.applicationContext
                            when (option) {
                                idle -> Unit
                                close -> closeCurrentForegroundIfNotAva(appCtx)
                                ava -> bringAvaDesktopToFront(appCtx)
                                else -> currentOptions()
                                    .firstOrNull { it.option == option }
                                    ?.let { sel ->
                                        when (sel.action) {
                                            HaLauncherAction.LAUNCH ->
                                                launchMinimalLauncherApp(appCtx, sel.app)
                                            HaLauncherAction.WINDOW_OPEN -> {
                                                // Fresh settings: requirements or the list
                                                // may have changed since entity build; on
                                                // failure fall back to a normal launch.
                                                val settingsNow = store.get()
                                                if (!maybeLaunchInAppWindow(
                                                        appCtx,
                                                        settingsNow,
                                                        sel.app.packageName,
                                                    )
                                                ) {
                                                    launchMinimalLauncherApp(appCtx, sel.app)
                                                }
                                            }
                                            HaLauncherAction.WINDOW_CLOSE ->
                                                AppWindowService.closeWindow(sel.app.packageName)
                                        }
                                    }
                            }
                            state.value = idle
                        },
                        getOptions = {
                            listOf(idle, close, ava) + currentOptions().map { it.option }
                        },
                    ))
                }
            }

            if (playerSettingsDataResolved.enableVoiceMessageOverlayDisplay) {
                add(SelectEntity(
                    key = 41,
                    name = context.getString(R.string.entity_voice_target),
                    objectId = "voice_target",
                    icon = "mdi:lan-connect",
                    options = AvaVoiceHaController.deviceNameOptions(),
                    getState = AvaVoiceHaController.nearbyDevicesDisplay,
                    setState = { /* diagnostic view only */ },
                    getOptions = { AvaVoiceHaController.deviceNameOptions() },
                    entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC
                ))

                add(ServiceEntity(
                    key = 42,
                    name = "voice_action",
                    description = context.getString(R.string.entity_voice_action_desc),
                    args = listOf(
                        ServiceArg("action", ServiceArgType.SERVICE_ARG_TYPE_STRING),
                        ServiceArg("mode", ServiceArgType.SERVICE_ARG_TYPE_STRING),
                        ServiceArg("targets", ServiceArgType.SERVICE_ARG_TYPE_STRING_ARRAY),
                        ServiceArg("message_seconds", ServiceArgType.SERVICE_ARG_TYPE_INT),
                    ),
                    onExecute = { args ->
                        AvaVoiceHaController.enqueue(context.applicationContext, args)
                    }
                ))
            }
        }
        
        if (browserSettingsData.haRemoteUrlEnabled && browserSettingsData.enableBrowserDisplay) {
            add(SwitchEntity(
                28,
                context.getString(R.string.entity_browser_display),
                "browser_display",
                "mdi:web-check",
                browserSettings.enableBrowserVisible,
                EntityCategory.ENTITY_CATEGORY_NONE
            ) { enabled ->
                browserSettings.enableBrowserVisible.set(enabled)
            })

            add(ButtonEntity(
                key = 37,
                name = context.getString(R.string.entity_browser_refresh),
                objectId = "browser_refresh",
                icon = "mdi:refresh",
                entityCategory = EntityCategory.ENTITY_CATEGORY_NONE,
                onPress = {
                    if (com.example.ava.services.ScreensaverController.isScreensaverVisible()) {
                        com.example.ava.services.ScreensaverWebViewService.forceRefresh(context)
                    } else {
                        // Split: refresh every pane that has a URL. Device pull-to-refresh stays per-pane.
                        com.example.ava.services.WebViewService.forceRefresh(
                            context,
                            allActivePanes = true,
                        )
                    }
                }
            ))

            // Same tiers as the in-app browser setting; WebViewService watches the
            // store and applies the mode live, so writing the setting is enough.
            val powerModeLabels = linkedMapOf(
                BrowserPowerMode.HIGH to context.getString(R.string.settings_browser_power_mode_high),
                BrowserPowerMode.ADAPTIVE to context.getString(R.string.settings_browser_power_mode_adaptive),
                BrowserPowerMode.LOW to context.getString(R.string.settings_browser_power_mode_low),
            )
            add(SelectEntity(
                key = "browser_power_mode".hashCode(),
                name = context.getString(R.string.entity_browser_power_mode),
                objectId = "browser_power_mode",
                icon = "mdi:speedometer",
                options = powerModeLabels.values.toList(),
                getState = browserSettings.browserPowerMode
                    .mapNotNull { powerModeLabels[it] }
                    .distinctUntilChanged(),
                setState = { label ->
                    powerModeLabels.entries.firstOrNull { it.value == label }?.let { entry ->
                        browserSettings.setBrowserPowerMode(entry.key)
                    }
                },
                entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC,
            ))
        }

        if (browserSettingsData.showScaleSliderInHa) {
            add(NumberEntity(
                key = 36,
                name = context.getString(R.string.entity_browser_scale),
                objectId = "browser_scale",
                icon = "mdi:magnify-expand",
                minValue = 0f,
                maxValue = 500f,
                step = 1f,
                unitOfMeasurement = "%",
                getState = browserSettings.initialScale.map { it.toFloat() },
                setState = { scale ->
                    browserSettings.setInitialScale(scale.toInt())
                },
                entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG,
                mode = NumberMode.NUMBER_MODE_SLIDER
            ))
        }
        
        val quickEntitySettings = QuickEntitySettingsStore(context.quickEntitySettingsStore)
        run {
            val quickEntityData = quickEntitySettings.getCached()
            if (quickEntityData.enableQuickEntity) {
                // Keep enableQuickEntityDisplay across satellite restarts — same role as
                // browser enableBrowserVisible / simple-clock enableScreensaverVisible.
                // VoiceSatelliteService restores the overlay from this flag on cold start.
                add(SwitchEntity(
                    29,
                    context.getString(R.string.entity_quick_entity_display),
                    "quick_entity_display",
                    "mdi:view-grid",
                    quickEntitySettings.enableQuickEntityDisplay,
                    EntityCategory.ENTITY_CATEGORY_NONE
                ) { enabled ->
                    quickEntitySettings.enableQuickEntityDisplay.set(enabled)
                    if (enabled) {
                        com.example.ava.services.QuickEntityOverlayService.show(context)
                    } else {
                        com.example.ava.services.QuickEntityOverlayService.hide(context)
                    }
                })
                
                if (quickEntityData.enableHaSlots) {
                    for (i in 0 until 6) {
                        val slotState = quickEntitySettings.slotEntityId(i)
                        add(TextEntity(
                            key = 30 + i,
                            name = context.getString(R.string.entity_quick_entity_slot, i + 1),
                            objectId = "quick_entity_slot_${i + 1}",
                            icon = "mdi:card-text",
                            getState = slotState,
                            setState = { entityId ->
                                slotState.set(entityId)
                                com.example.ava.services.QuickEntityOverlayService.getInstance()?.reloadSlots()
                            },
                            entityCategory = EntityCategory.ENTITY_CATEGORY_CONFIG
                        ))
                    }
                }
            }
        }

        ClockAlertSensor.prime(context)
        addAll(ClockAlertSensor.dateTimeEntities(context))

        if (TouchPadPrefs(context).haSelectEnabled) {
            val idle = TouchPadHa.idleLabel(context)
            val state = MutableStateFlow(idle)
            add(SelectEntity(
                key = TouchPadHa.OBJECT_ID.hashCode(),
                name = context.getString(R.string.entity_touch_pad_take),
                objectId = TouchPadHa.OBJECT_ID,
                icon = "mdi:gesture-tap-hold",
                options = TouchPadHa.options(context),
                getState = state,
                setState = { option ->
                    TouchPadHa.play(context, option)
                    state.value = idle
                },
                getOptions = { TouchPadHa.options(context) },
                entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC,
            ))
        }
    }

    /**
     * Same four choices as the diagnostics settings dropdown. Diagnostic category
     * so Home Assistant files it with the other device diagnostics. Writes the
     * shared preference the in-app control already uses.
     */
    private fun systemBarsSelect(context: Context): SelectEntity {
        val labels = linkedMapOf(
            SystemBarsMode.HIDE_NAV to context.getString(R.string.settings_system_bars_hide_nav),
            SystemBarsMode.SHOW to context.getString(R.string.settings_system_bars_show),
            SystemBarsMode.HIDE_STATUS to context.getString(R.string.settings_system_bars_hide_status),
            SystemBarsMode.HIDE_BOTH to context.getString(R.string.settings_system_bars_hide_both),
        )
        val options = labels.values.toList()
        return SelectEntity(
            key = "system_bars".hashCode(),
            name = context.getString(R.string.settings_system_bars),
            objectId = "system_bars",
            icon = "mdi:fullscreen",
            options = options,
            getState = systemBarsLabelFlow(context, labels),
            setState = { option ->
                val mode = labels.entries.firstOrNull { it.value == option }?.key
                    ?: when (option) {
                        SystemBarsMode.HIDE_NAV.storage -> SystemBarsMode.HIDE_NAV
                        SystemBarsMode.SHOW.storage -> SystemBarsMode.SHOW
                        SystemBarsMode.HIDE_STATUS.storage -> SystemBarsMode.HIDE_STATUS
                        SystemBarsMode.HIDE_BOTH.storage -> SystemBarsMode.HIDE_BOTH
                        else -> null
                    }
                if (mode != null) {
                    context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(SystemBarsMode.PREF_KEY, mode.storage)
                        .apply()
                    AvaSystemChrome.reapplyImmersiveMode()
                }
            },
            getOptions = { options },
            entityCategory = EntityCategory.ENTITY_CATEGORY_DIAGNOSTIC,
        )
    }

    private fun systemBarsLabelFlow(
        context: Context,
        labels: Map<SystemBarsMode, String>,
    ): Flow<String> = callbackFlow {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        fun current(): String {
            val mode = SystemBarsMode.read(app)
            return labels[mode] ?: labels.getValue(SystemBarsMode.HIDE_NAV)
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SystemBarsMode.PREF_KEY) trySend(current())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(current())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}
