package com.example.ava.bluetooth

import android.content.Context
import android.media.RingtoneManager
import com.example.ava.settings.BluetoothPresenceAlertTrigger

object BluetoothPresenceAlertSound {
    /** Empty stored value = no alert sound. */
    const val NONE_URI = ""

    private const val PREFS_NAME = "bluetooth_presence_prefs"
    private const val KEY_SAVED_CUSTOM_URIS = "saved_custom_alert_sound_uris"
    private const val MAX_SAVED_CUSTOM_URIS = 24

    fun isBuiltInRingtoneUri(uri: String, systemRingtones: Collection<String>): Boolean {
        return uri.isBlank() || uri in systemRingtones
    }

    fun loadSavedCustomAlertSoundUris(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_SAVED_CUSTOM_URIS, null) ?: return emptyList()
        return stored.filter { it.isNotBlank() }
    }

    /** Adds assigned custom URIs from devices into the saved picker list (no removals). */
    fun mergeAssignedCustomAlertSoundUris(
        context: Context,
        assignedUris: Collection<String>,
        systemRingtones: Collection<String>,
    ) {
        assignedUris.forEach { uri ->
            if (!isBuiltInRingtoneUri(uri, systemRingtones)) {
                rememberCustomAlertSoundUri(context, uri)
            }
        }
    }

    fun rememberCustomAlertSoundUri(context: Context, uri: String) {
        if (uri.isBlank() || uri == NONE_URI) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = LinkedHashSet(loadSavedCustomAlertSoundUris(context))
        updated.remove(uri)
        updated.add(uri)
        while (updated.size > MAX_SAVED_CUSTOM_URIS) {
            val oldest = updated.first()
            updated.remove(oldest)
        }
        prefs.edit().putStringSet(KEY_SAVED_CUSTOM_URIS, updated).apply()
    }

    fun removeSavedCustomAlertSoundUri(context: Context, uri: String) {
        if (uri.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = LinkedHashSet(loadSavedCustomAlertSoundUris(context))
        if (!updated.remove(uri)) return
        prefs.edit().putStringSet(KEY_SAVED_CUSTOM_URIS, updated).apply()
    }

    fun resolvePlayUri(storedUri: String): String {
        return storedUri.takeIf { it.isNotBlank() }.orEmpty()
    }

    fun buildRingtoneOptions(
        context: Context,
        noneLabel: String,
        unknownLabel: String,
    ): List<Pair<String, String>> {
        return runCatching {
            val list = mutableListOf<Pair<String, String>>()
            list.add(noneLabel to NONE_URI)
            val manager = RingtoneManager(context)
            manager.setType(RingtoneManager.TYPE_NOTIFICATION)
            manager.cursor.use { cursor ->
                while (cursor.moveToNext()) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: unknownLabel
                    val uri = manager.getRingtoneUri(cursor.position).toString()
                    list.add(title to uri)
                }
            }
            list
        }.getOrElse {
            android.util.Log.w("BluetoothPresenceAlertSound", "buildRingtoneOptions failed", it)
            listOf(noneLabel to NONE_URI)
        }
    }

    fun normalizeTrigger(trigger: String?): String {
        return when (trigger) {
            BluetoothPresenceAlertTrigger.NEARBY -> BluetoothPresenceAlertTrigger.NEARBY
            BluetoothPresenceAlertTrigger.NOT_NEARBY -> BluetoothPresenceAlertTrigger.NOT_NEARBY
            else -> BluetoothPresenceAlertTrigger.NEARBY
        }
    }
}
