package com.example.ava.ui.screens.settings

import com.example.ava.R
import com.example.ava.ui.Screen
import com.example.ava.ui.screens.home.HomeSidebarActions

enum class SettingsSearchGate {
    Always,
    Browser,
    Screensaver,
    Experimental,
    Bluetooth,
    Camera,
    TouchPad,
}

data class SettingsSearchEntry(
    val titleRes: Int,
    val subtitleRes: Int? = null,
    val groupTitleRes: Int,
    val route: String,
    val gate: SettingsSearchGate = SettingsSearchGate.Always,
)

data class SettingsSearchHit(
    val title: String,
    val path: String,
    val route: String,
    val haystack: String,
)

object SettingsSearchCatalog {
    val entries: List<SettingsSearchEntry> = listOf(
        // Voice Config
        entry(
            R.string.settings_group_connection,
            R.string.settings_group_connection_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_CONNECTION,
        ),
        entry(
            R.string.settings_ha_page_title,
            R.string.settings_ha_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_HA,
        ),
        entry(
            R.string.local_llm_title,
            R.string.local_llm_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_HA_LOCAL_LLM,
        ),
        entry(
            R.string.settings_voice_wake_entry_title,
            R.string.settings_voice_wake_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_VOICE_WAKE,
        ),
        entry(
            R.string.wake_word_library_entry_title,
            R.string.wake_word_library_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_VOICE_WAKE_LIBRARY,
        ),
        entry(
            R.string.wake_learn_entry_title,
            R.string.wake_learn_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_VOICE_WAKE_LEARN,
        ),
        entry(
            R.string.settings_voice_feedback_accent_title,
            R.string.settings_voice_feedback_accent_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_VOICE_FEEDBACK_ACCENT,
        ),
        entry(
            R.string.settings_voice_microphone_entry_title,
            R.string.settings_voice_microphone_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_VOICE_MICROPHONE,
        ),
        entry(
            R.string.settings_voice_noise_suppression_entry_title,
            R.string.settings_voice_noise_suppression_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_VOICE_NOISE_SUPPRESSION,
        ),
        entry(
            R.string.settings_voice_echo_cancellation_entry_title,
            R.string.settings_voice_echo_cancellation_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_VOICE_ECHO_CANCELLATION,
        ),
        entry(
            R.string.settings_voice_print_entry_title,
            R.string.settings_voice_print_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_VOICE_PRINT,
        ),
        entry(
            R.string.settings_voice_stt_entry_title,
            R.string.settings_voice_stt_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_VOICE_STT,
        ),
        entry(
            R.string.settings_voice_audio_event_entry_title,
            R.string.settings_voice_audio_event_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_VOICE_AUDIO_EVENT,
        ),
        entry(
            R.string.settings_voice_tts_entry_title,
            R.string.settings_voice_tts_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_VOICE_TTS,
        ),
        entry(
            R.string.settings_voice_streaming_tts_entry_title,
            R.string.settings_voice_streaming_tts_entry_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_VOICE_STREAMING_TTS,
        ),
        entry(
            R.string.settings_ambient_auto_gain,
            R.string.settings_ambient_auto_gain_desc,
            R.string.settings_group_connection,
            Screen.SETTINGS_VOICE_STREAMING_TTS,
        ),

        // Device Controls
        entry(
            R.string.settings_group_service,
            R.string.settings_group_service_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SERVICE,
        ),
        entry(
            R.string.settings_device_control_title,
            R.string.settings_service_entry_device_control_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SERVICE_DEVICE_CONTROL,
        ),
        entry(
            R.string.settings_device_logs_title,
            R.string.settings_device_logs_entry_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SERVICE_DEVICE_LOGS,
        ),
        entry(
            R.string.settings_home_interface,
            R.string.settings_service_entry_home_interface_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_INTERACTION_INTERFACE,
        ),
        entry(
            R.string.settings_style,
            R.string.settings_service_entry_settings_style_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_STYLE,
        ),
        entry(
            R.string.settings_style_liquid_glass,
            R.string.settings_style_liquid_glass_intensity_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_STYLE,
        ),
        entry(
            R.string.settings_style_liquid_glass_blur,
            R.string.settings_style_liquid_glass_blur_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_STYLE,
        ),
        entry(
            R.string.settings_style_liquid_glass_press_glow,
            R.string.settings_style_liquid_glass_press_glow_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_STYLE,
        ),
        entry(
            R.string.settings_style_overlay_control,
            R.string.settings_style_overlay_control_entry_desc,
            R.string.settings_style,
            Screen.SETTINGS_STYLE_OVERLAY,
        ),
        entry(
            R.string.settings_system_bars,
            R.string.settings_system_bars_hide_nav_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_STYLE,
        ),
        entry(
            R.string.settings_system_bars_ha_display,
            R.string.settings_system_bars_ha_display_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_STYLE,
        ),
        entry(
            R.string.settings_sidebar,
            R.string.settings_service_entry_sidebar_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SIDEBAR,
        ),
        entry(
            R.string.home_sidebar_touch_pad,
            R.string.settings_sidebar_show_touch_pad_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SIDEBAR_TOUCH_PAD,
            SettingsSearchGate.TouchPad,
        ),
        entry(
            R.string.touch_pad_opacity,
            R.string.settings_sidebar_show_touch_pad_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SIDEBAR_TOUCH_PAD,
            SettingsSearchGate.TouchPad,
        ),
        entry(
            R.string.touch_pad_cursor_sensitivity,
            R.string.settings_sidebar_show_touch_pad_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SIDEBAR_TOUCH_PAD,
            SettingsSearchGate.TouchPad,
        ),
        entry(
            R.string.touch_pad_corner_summon,
            R.string.touch_pad_corner_summon_subtitle,
            R.string.settings_group_service,
            Screen.SETTINGS_SIDEBAR_TOUCH_PAD,
            SettingsSearchGate.TouchPad,
        ),
        entry(
            R.string.touch_pad_ha_select,
            R.string.touch_pad_ha_select_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SIDEBAR_TOUCH_PAD,
            SettingsSearchGate.TouchPad,
        ),
        entry(
            R.string.settings_home_pin_lock,
            R.string.settings_service_entry_home_lock_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_HOME_LOCK,
        ),
        entry(
            R.string.settings_minimal_launcher,
            R.string.settings_service_entry_minimal_launcher_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SERVICE_MINIMAL_LAUNCHER,
        ),
        entry(
            R.string.settings_minimal_launcher_apps,
            R.string.settings_minimal_launcher_apps_hint,
            R.string.settings_group_service,
            Screen.SETTINGS_SERVICE_MINIMAL_LAUNCHER_APPS,
        ),
        entry(
            R.string.settings_app_window,
            R.string.settings_app_window_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SERVICE_MINIMAL_LAUNCHER_APPS,
        ),
        entry(
            R.string.settings_service_keep_running,
            R.string.settings_service_entry_auto_restart_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SERVICE_AUTO_RESTART,
        ),
        entry(
            R.string.settings_software_update,
            R.string.settings_service_entry_software_update_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SOFTWARE_UPDATE,
        ),
        entry(
            R.string.settings_update_prefs_entry,
            R.string.settings_update_prefs_entry_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SOFTWARE_UPDATE_PREFS,
        ),
        entry(
            R.string.settings_screen_power_control,
            R.string.settings_service_entry_screen_power_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SERVICE_SCREEN_POWER,
        ),
        entry(
            R.string.settings_screen_brightness,
            R.string.settings_service_entry_screen_brightness_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SERVICE_SCREEN_BRIGHTNESS,
        ),
        entry(
            R.string.settings_screen_touch,
            R.string.settings_service_entry_screen_touch_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SERVICE_SCREEN_TOUCH,
        ),
        entry(
            R.string.settings_force_orientation,
            R.string.settings_service_entry_force_orientation_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SERVICE_FORCE_ORIENTATION,
        ),
        entry(
            R.string.settings_touch_sound,
            R.string.settings_service_entry_touch_sound_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SERVICE_TOUCH_SOUND,
        ),
        entry(
            R.string.settings_sensor_enabled,
            R.string.settings_service_entry_environment_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_ENVIRONMENT,
        ),
        entry(
            R.string.settings_proximity_sensor,
            R.string.settings_service_entry_proximity_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_SERVICE_PROXIMITY,
        ),
        entry(
            R.string.settings_diagnostic_sensor,
            R.string.settings_service_entry_diagnostic_desc,
            R.string.settings_group_service,
            Screen.SETTINGS_DIAGNOSTIC,
        ),

        // Extensions
        entry(
            R.string.settings_group_interaction,
            R.string.settings_group_interaction_desc,
            R.string.settings_group_interaction,
            Screen.SETTINGS_INTERACTION,
        ),
        entry(
            R.string.settings_interaction_group_playback_title,
            R.string.settings_interaction_group_playback_desc,
            R.string.settings_group_interaction,
            Screen.SETTINGS_INTERACTION_PLAYBACK,
        ),
        entry(
            R.string.settings_mass_api_title,
            R.string.settings_mass_api_entry_desc,
            R.string.settings_group_interaction,
            Screen.SETTINGS_INTERACTION_PLAYBACK_MASS_API,
        ),
        entry(
            R.string.settings_interaction_group_scene_title,
            R.string.settings_interaction_group_scene_desc,
            R.string.settings_group_interaction,
            Screen.SETTINGS_INTERACTION_SCENE,
        ),
        entry(
            R.string.settings_simple_clock_appearance_entry_title,
            R.string.settings_simple_clock_appearance_entry_desc,
            R.string.settings_group_interaction,
            Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK_APPEARANCE,
        ),
        entry(
            R.string.settings_screensaver_status_slots,
            R.string.settings_screensaver_status_slots_entry_desc,
            R.string.settings_group_interaction,
            Screen.SETTINGS_INTERACTION_SIMPLE_CLOCK_STATUS,
        ),
        entry(
            R.string.settings_interaction_group_voice_message_title,
            R.string.settings_interaction_group_voice_message_desc,
            R.string.settings_group_interaction,
            Screen.SETTINGS_INTERACTION_VOICE_MESSAGE,
        ),
        entry(
            R.string.settings_interaction_group_quick_entity_title,
            R.string.settings_interaction_group_quick_entity_desc,
            R.string.settings_group_interaction,
            Screen.SETTINGS_INTERACTION_QUICK_ENTITY,
        ),

        // Bluetooth
        entry(
            R.string.settings_group_bluetooth,
            R.string.settings_group_bluetooth_desc,
            R.string.settings_group_bluetooth,
            Screen.SETTINGS_BLUETOOTH,
            SettingsSearchGate.Bluetooth,
        ),

        // Screensaver
        entry(
            R.string.settings_group_screensaver,
            R.string.settings_group_screensaver_desc,
            R.string.settings_group_screensaver,
            Screen.SETTINGS_SCREENSAVER,
            SettingsSearchGate.Screensaver,
        ),
        entry(
            R.string.settings_screensaver_entry_content_title,
            R.string.settings_screensaver_entry_content_desc,
            R.string.settings_group_screensaver,
            Screen.SETTINGS_SCREENSAVER_CONTENT,
            SettingsSearchGate.Screensaver,
        ),
        entry(
            R.string.settings_screensaver_entry_behavior_title,
            R.string.settings_screensaver_entry_behavior_desc,
            R.string.settings_group_screensaver,
            Screen.SETTINGS_SCREENSAVER_BEHAVIOR,
            SettingsSearchGate.Screensaver,
        ),
        entry(
            R.string.settings_screensaver_keep_on_overlays,
            R.string.settings_screensaver_keep_on_overlays_desc,
            R.string.settings_group_screensaver,
            Screen.SETTINGS_SCREENSAVER_BEHAVIOR,
            SettingsSearchGate.Screensaver,
        ),
        entry(
            R.string.settings_screensaver_show_after_screen_on,
            R.string.settings_screensaver_show_after_screen_on_desc,
            R.string.settings_group_screensaver,
            Screen.SETTINGS_SCREENSAVER_BEHAVIOR,
            SettingsSearchGate.Screensaver,
        ),

        // Browser
        entry(
            R.string.settings_group_browser,
            R.string.settings_group_browser_desc,
            R.string.settings_group_browser,
            Screen.SETTINGS_BROWSER,
            SettingsSearchGate.Browser,
        ),
        entry(
            R.string.settings_browser_entry_ha_title,
            R.string.settings_browser_entry_ha_desc,
            R.string.settings_group_browser,
            Screen.SETTINGS_BROWSER_HA,
            SettingsSearchGate.Browser,
        ),
        entry(
            R.string.settings_browser_entry_display_title,
            R.string.settings_browser_entry_display_desc,
            R.string.settings_group_browser,
            Screen.SETTINGS_BROWSER_DISPLAY,
            SettingsSearchGate.Browser,
        ),
        entry(
            R.string.settings_browser_split_view,
            R.string.settings_browser_split_view_entry_desc,
            R.string.settings_group_browser,
            Screen.SETTINGS_BROWSER_SPLIT,
            SettingsSearchGate.Browser,
        ),
        entry(
            R.string.settings_browser_entry_touch_title,
            R.string.settings_browser_entry_touch_desc,
            R.string.settings_group_browser,
            Screen.SETTINGS_BROWSER_TOUCH,
            SettingsSearchGate.Browser,
        ),
        entry(
            R.string.settings_browser_entry_steward_title,
            R.string.settings_browser_entry_steward_desc,
            R.string.settings_group_browser,
            Screen.SETTINGS_BROWSER_STEWARD,
            SettingsSearchGate.Browser,
        ),
        entry(
            R.string.settings_browser_entry_sidebar_title,
            R.string.settings_browser_entry_sidebar_desc,
            R.string.settings_group_browser,
            Screen.SETTINGS_BROWSER_SIDEBAR,
            SettingsSearchGate.Browser,
        ),
        entry(
            R.string.settings_browser_entry_compat_title,
            R.string.settings_browser_entry_compat_desc,
            R.string.settings_group_browser,
            Screen.SETTINGS_BROWSER_COMPAT,
            SettingsSearchGate.Browser,
        ),
        entry(
            R.string.settings_browser_legacy_compat,
            R.string.settings_browser_legacy_compat_desc,
            R.string.settings_group_browser,
            Screen.SETTINGS_BROWSER_COMPAT,
            SettingsSearchGate.Browser,
        ),

        // Advanced
        entry(
            R.string.settings_group_experimental,
            R.string.settings_group_experimental_desc,
            R.string.settings_group_experimental,
            Screen.SETTINGS_EXPERIMENTAL,
            SettingsSearchGate.Experimental,
        ),
        entry(
            R.string.settings_mod_store,
            R.string.settings_mod_store_desc,
            R.string.settings_group_experimental,
            Screen.MOD_STORE,
            SettingsSearchGate.Experimental,
        ),
        entry(
            R.string.settings_camera_enabled,
            R.string.settings_camera_enabled_desc,
            R.string.settings_group_experimental,
            Screen.SETTINGS_CAMERA,
            SettingsSearchGate.Camera,
        ),
        entry(
            R.string.settings_occupancy,
            R.string.settings_occupancy_entry_desc,
            R.string.settings_group_experimental,
            Screen.SETTINGS_OCCUPANCY,
            SettingsSearchGate.Experimental,
        ),
        entry(
            R.string.settings_intent_launcher,
            R.string.settings_intent_launcher_entry_desc,
            R.string.settings_group_experimental,
            Screen.SETTINGS_INTENT_LAUNCHER,
            SettingsSearchGate.Experimental,
        ),
        entry(
            R.string.settings_media_key,
            R.string.settings_media_key_desc,
            R.string.settings_group_experimental,
            Screen.SETTINGS_INTENT_LAUNCHER,
            SettingsSearchGate.Experimental,
        ),
        entry(
            R.string.settings_cluster_management,
            R.string.settings_cluster_management_desc,
            R.string.settings_group_experimental,
            Screen.SETTINGS_CLUSTER_MANAGEMENT,
            SettingsSearchGate.Experimental,
        ),
        entry(
            R.string.settings_backup_restore,
            R.string.settings_backup_restore_desc,
            R.string.settings_group_experimental,
            Screen.SETTINGS_BACKUP_RESTORE,
            SettingsSearchGate.Experimental,
        ),

        // Permissions
        entry(
            R.string.settings_group_root,
            R.string.settings_group_root_desc,
            R.string.settings_group_root,
            Screen.SETTINGS_ROOT,
        ),
        entry(
            R.string.settings_permission_manager_title,
            R.string.settings_permission_manager_entry_desc,
            R.string.settings_group_root,
            Screen.SETTINGS_PERMISSION_MANAGER,
        ),
    )

    fun visible(
        showBrowser: Boolean,
        showScreensaver: Boolean,
        showExperimental: Boolean,
        showBluetooth: Boolean,
        showCamera: Boolean,
    ): List<SettingsSearchEntry> = entries.filter { entry ->
        when (entry.gate) {
            SettingsSearchGate.Always -> true
            SettingsSearchGate.Browser -> showBrowser
            SettingsSearchGate.Screensaver -> showScreensaver
            SettingsSearchGate.Experimental -> showExperimental
            SettingsSearchGate.Bluetooth -> showBluetooth
            SettingsSearchGate.Camera -> showCamera && showExperimental
            SettingsSearchGate.TouchPad -> HomeSidebarActions.isTouchPadAvailable()
        }
    }

    private fun entry(
        titleRes: Int,
        subtitleRes: Int?,
        groupTitleRes: Int,
        route: String,
        gate: SettingsSearchGate = SettingsSearchGate.Always,
    ) = SettingsSearchEntry(
        titleRes = titleRes,
        subtitleRes = subtitleRes,
        groupTitleRes = groupTitleRes,
        route = route,
        gate = gate,
    )
}

fun resolveSettingsSearchHit(
    title: String,
    path: String,
    subtitle: String?,
    route: String,
): SettingsSearchHit {
    val haystack = buildString {
        append(title)
        append(' ')
        append(path)
        if (!subtitle.isNullOrBlank()) {
            append(' ')
            append(subtitle)
        }
    }
    return SettingsSearchHit(
        title = title,
        path = path,
        route = route,
        haystack = haystack,
    )
}

fun filterSettingsSearch(
    query: String,
    hits: List<SettingsSearchHit>,
): List<SettingsSearchHit> {
    val tokens = settingsSearchTokens(query)
    if (tokens.isEmpty()) return emptyList()
    return hits.filter { hit ->
        val hay = hit.haystack.lowercase()
        tokens.all { hay.contains(it) }
    }
}

internal fun settingsSearchTokens(query: String): List<String> =
    query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

private val WHITESPACE = Regex("\\s+")
