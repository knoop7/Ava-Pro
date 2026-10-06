package com.example.ava.settings

import android.content.Context
import android.util.Log
import com.example.ava.crash.AvaIncidentLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Preserves the raw bytes of a settings file that failed to deserialize.
 *
 * Every settings DataStore recovers from corruption by replacing the file with
 * factory defaults. Recovery itself is right — a store that cannot be read
 * would wedge the app — but the old bytes are the only copy of the user's HA
 * credentials, wake words, satellite identity, and so on. A single
 * incompatible field after an upgrade used to destroy all of it with one log
 * line. Quarantining first makes the reset recoverable.
 *
 * Snapshots land in files/settings_quarantine/<store>-<timestamp>.json with
 * the newest [MAX_FILES_PER_STORE] per store kept.
 */
object SettingsQuarantine {
    private const val TAG = "SettingsQuarantine"
    private const val DIR_NAME = "settings_quarantine"

    /** A corruption event produces one snapshot; a few cover repeat upgrades. */
    private const val MAX_FILES_PER_STORE = 3

    @Volatile
    private var appContext: Context? = null

    /** Called from AvaApplication.attachBaseContext — before any DataStore read. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Saves [corruptBytes] and records an incident so the Logs screen shows
     * why settings reset. Never throws: this runs on a read path that is
     * already recovering from corruption, and failing to preserve the bytes
     * must not break that recovery.
     */
    fun save(storeName: String, corruptBytes: ByteArray, cause: Throwable) {
        val context = appContext
        if (context == null) {
            Log.e(TAG, "Not initialized; corrupt $storeName could not be quarantined", cause)
            return
        }
        runCatching {
            val dir = File(context.filesDir, DIR_NAME)
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            File(dir, "$storeName-$stamp.json").writeBytes(corruptBytes)
            prune(dir, storeName)
            Log.e(
                TAG,
                "Quarantined corrupt $storeName (${corruptBytes.size} bytes) before defaults replace it",
                cause,
            )
        }.onFailure { Log.e(TAG, "Failed to quarantine corrupt $storeName", it) }
        runCatching {
            AvaIncidentLog.record(
                context,
                kind = AvaIncidentLog.KIND_SETTINGS_CORRUPT,
                reason = storeName,
                detail = cause.toString(),
            )
        }
    }

    private fun prune(dir: File, storeName: String) {
        val snapshots = dir.listFiles { file -> file.name.startsWith("$storeName-") } ?: return
        snapshots.sortedByDescending { it.name }
            .drop(MAX_FILES_PER_STORE)
            .forEach { stale -> runCatching { stale.delete() } }
    }
}
