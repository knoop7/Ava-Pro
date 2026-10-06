package com.example.ava.webcompat

import android.net.Uri

object HostSidebarSettingsContract {
    const val AUTHORITY = "com.example.ava.browser_sidebar_settings"
    val SNAPSHOT_URI: Uri = Uri.parse("content://$AUTHORITY/snapshot")

    const val COLUMN_JSON = "json"

    const val ACTION_PUSH_SIDEBAR_SETTINGS = "com.example.ava.action.PUSH_SIDEBAR_SETTINGS"
    const val ACTION_REQUEST_SIDEBAR_SETTINGS = "com.example.ava.action.REQUEST_SIDEBAR_SETTINGS"
    const val ACTION_SIDEBAR_COMMAND = "com.example.ava.action.SIDEBAR_COMMAND"
    const val EXTRA_SIDEBAR_SETTINGS_JSON = "sidebar_settings_json"
    const val EXTRA_SIDEBAR_COMMAND = "sidebar_command"
    const val EXTRA_SIDEBAR_COMMAND_PAYLOAD = "sidebar_command_payload"

    const val CMD_TOGGLE_VOICE_MESSAGE = "toggle_voice_message"
    const val CMD_OPEN_LAUNCHER = "open_launcher"
    const val CMD_TOGGLE_BROWSER = "toggle_browser"
    const val CMD_TOGGLE_WEATHER = "toggle_weather"
    const val CMD_TOGGLE_SIMPLE_CLOCK = "toggle_simple_clock"
    const val CMD_TOGGLE_DREAM_CLOCK = "toggle_dream_clock"
    const val CMD_TOGGLE_QUICK_ENTITY = "toggle_quick_entity"
    const val CMD_TOGGLE_VINYL_COVER_DISPLAY = "toggle_vinyl_cover_display"
    const val CMD_SET_VIDEO_RECORDING = "set_video_recording"
    const val CMD_SET_MIC_MUTE = "set_mic_mute"
    const val CMD_SET_DARK_MODE = "set_dark_mode"
    const val CMD_SET_USER_AGENT_MODE = "set_user_agent_mode"

    const val RECEIVER_CLASS = "com.example.ava.webcompat.GeckoSidebarSettingsReceiver"
}
