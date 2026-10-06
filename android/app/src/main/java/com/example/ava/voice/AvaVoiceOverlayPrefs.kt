package com.example.ava.voice

import android.content.Context

/** Local UX prefs for the voice message / call device picker overlay. */
object AvaVoiceOverlayPrefs {
    private const val PREFS_NAME = "ava_voice_overlay"
    private const val KEY_LAST_MODE = "last_mode"
    private const val KEY_PINNED_DEVICE_ID = "pinned_device_id"

    fun loadMode(context: Context): AvaVoiceMode {
        val wire = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_MODE, null)
        return AvaVoiceMode.fromWire(wire)
    }

    fun saveMode(context: Context, mode: AvaVoiceMode) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_MODE, mode.wireValue)
            .apply()
    }

    fun loadPinnedDeviceId(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PINNED_DEVICE_ID, null)
            ?.takeIf { it.isNotBlank() }
    }

    /** Remember a single recently used target for list ordering only (not auto-select). */
    fun savePinnedDeviceId(context: Context, deviceId: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PINNED_DEVICE_ID, deviceId)
            .apply()
    }
}

fun List<AvaVoiceDevice>.withPinnedDeviceFirst(pinnedDeviceId: String?): List<AvaVoiceDevice> {
    if (pinnedDeviceId.isNullOrBlank()) return this
    val pinned = firstOrNull { it.id == pinnedDeviceId } ?: return this
    return listOf(pinned) + filter { it.id != pinnedDeviceId }
}

fun resolveVoiceOverlayMode(
    preferred: AvaVoiceMode,
    intercomEnabled: Boolean,
    callEnabled: Boolean
): AvaVoiceMode = when {
    intercomEnabled && callEnabled -> when (preferred) {
        AvaVoiceMode.Call -> AvaVoiceMode.Call
        AvaVoiceMode.Intercom -> AvaVoiceMode.Intercom
    }
    callEnabled -> AvaVoiceMode.Call
    intercomEnabled -> AvaVoiceMode.Intercom
    else -> AvaVoiceMode.Intercom
}

fun showVoiceOverlayModeSwitch(intercomEnabled: Boolean, callEnabled: Boolean): Boolean =
    intercomEnabled && callEnabled
