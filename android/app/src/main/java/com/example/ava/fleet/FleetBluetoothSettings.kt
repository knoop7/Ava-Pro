package com.example.ava.fleet

import android.content.Context
import android.util.Log
import com.example.ava.bluetooth.BluetoothPresenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fleet bridge for Ava Bluetooth settings.
 *
 * - `detect_enabled` lives in SharedPreferences `bluetooth_settings`
 *   (same as [com.example.ava.ui.screens.settings.BluetoothSettingsScreen]).
 * - RSSI / away delay / tracked devices / proxy scan live in
 *   `bluetooth_presence_prefs` via [BluetoothPresenceManager].
 */
object FleetBluetoothSettings {
    private const val TAG = "FleetBluetoothSettings"
    private const val DETECT_PREFS = "bluetooth_settings"
    private const val PRESENCE_PREFS = "bluetooth_presence_prefs"

    private const val KEY_DETECT = "detect_enabled"
    private const val KEY_RSSI = "rssi_threshold"
    private const val KEY_AWAY = "away_delay_seconds"
    private const val KEY_PROXY_MODE = "proxy_scan_mode"
    private const val KEY_PROXY_POWER = "proxy_scan_power"
    private const val KEY_ALERT_ADDRESS = "presence_alert_address"
    private const val KEY_TRACKED = "tracked_devices"

    fun snapshot(context: Context): JSONObject {
        val detect = context.getSharedPreferences(DETECT_PREFS, Context.MODE_PRIVATE)
        val presence = context.getSharedPreferences(PRESENCE_PREFS, Context.MODE_PRIVATE)
        val trackedRaw = presence.getString(KEY_TRACKED, null)
        val tracked = try {
            if (trackedRaw.isNullOrBlank()) JSONArray()
            else JSONArray(trackedRaw)
        } catch (_: Exception) {
            JSONArray()
        }
        return JSONObject()
            .put("detectEnabled", detect.getBoolean(KEY_DETECT, false))
            .put(
                "screenOffPersistence",
                detect.getBoolean(
                    BluetoothPresenceManager.KEY_SCREEN_OFF_PERSISTENCE,
                    BluetoothPresenceManager.SCREEN_OFF_PERSISTENCE_DEFAULT,
                ),
            )
            .put("rssiThreshold", presence.getInt(KEY_RSSI, -80))
            .put("awayDelaySeconds", presence.getInt(KEY_AWAY, 120))
            .put("proxyScanMode", presence.getString(KEY_PROXY_MODE, "auto") ?: "auto")
            .put("proxyScanPower", presence.getString(KEY_PROXY_POWER, "low") ?: "low")
            .put("presenceAlertAddress", presence.getString(KEY_ALERT_ADDRESS, "") ?: "")
            .put("trackedDevices", tracked)
    }

    /** Apply a partial patch. Returns true if any value changed. */
    fun apply(context: Context, patch: JSONObject): Boolean {
        if (patch.length() == 0) return false
        var changed = false

        if (patch.has("detectEnabled")) {
            val detect = context.getSharedPreferences(DETECT_PREFS, Context.MODE_PRIVATE)
            val v = patch.optBoolean("detectEnabled", false)
            if (detect.getBoolean(KEY_DETECT, false) != v) {
                detect.edit().putBoolean(KEY_DETECT, v).apply()
                changed = true
            }
        }
        if (patch.has("screenOffPersistence")) {
            val detect = context.getSharedPreferences(DETECT_PREFS, Context.MODE_PRIVATE)
            val v = patch.optBoolean(
                "screenOffPersistence",
                BluetoothPresenceManager.SCREEN_OFF_PERSISTENCE_DEFAULT,
            )
            val cur = detect.getBoolean(
                BluetoothPresenceManager.KEY_SCREEN_OFF_PERSISTENCE,
                BluetoothPresenceManager.SCREEN_OFF_PERSISTENCE_DEFAULT,
            )
            if (cur != v) {
                detect.edit()
                    .putBoolean(BluetoothPresenceManager.KEY_SCREEN_OFF_PERSISTENCE, v)
                    .apply()
                changed = true
            }
        }

        // Prefer the live manager so in-memory StateFlows stay in sync with prefs.
        val mgr = runCatching { BluetoothPresenceManager.getInstance(context) }.getOrNull()
        if (mgr != null) {
            if (patch.has("rssiThreshold")) {
                val v = patch.optInt("rssiThreshold", -80).coerceIn(-100, -40)
                if (mgr.rssiThreshold != v) {
                    mgr.rssiThreshold = v
                    changed = true
                }
            }
            if (patch.has("awayDelaySeconds")) {
                val v = patch.optInt("awayDelaySeconds", 120).coerceIn(5, 3600)
                if (mgr.awayDelaySeconds != v) {
                    mgr.awayDelaySeconds = v
                    changed = true
                }
            }
            if (patch.has("proxyScanMode")) {
                val raw = patch.optString("proxyScanMode")
                val v = if (raw == "passive" || raw == "active") raw else "auto"
                if (mgr.proxyScanMode != v) {
                    mgr.proxyScanMode = v
                    changed = true
                }
            }
            if (patch.has("proxyScanPower")) {
                val raw = patch.optString("proxyScanPower", "low")
                val v = if (raw == "balanced" || raw == "high") raw else "low"
                if (mgr.proxyScanPower != v) {
                    mgr.proxyScanPower = v
                    changed = true
                }
            }
            if (patch.has("presenceAlertAddress")) {
                val v = patch.optString("presenceAlertAddress", "").trim().ifEmpty { null }
                val cur = mgr.presenceAlertAddress
                if (cur != v) {
                    if (v == null) {
                        // No public clear-all helper — write prefs + mirror via empty toggle path.
                        context.getSharedPreferences(PRESENCE_PREFS, Context.MODE_PRIVATE)
                            .edit().remove(KEY_ALERT_ADDRESS).apply()
                        if (cur != null) mgr.clearPresenceAlertAddressIfMatches(cur)
                    } else {
                        mgr.setPresenceAlertAddress(v)
                    }
                    changed = true
                }
            }
        } else {
            Log.w(TAG, "BluetoothPresenceManager unavailable; writing presence prefs directly")
            changed = writePresencePrefsDirect(context, patch) || changed
        }

        if (patch.has("trackedDevices")) {
            val presence = context.getSharedPreferences(PRESENCE_PREFS, Context.MODE_PRIVATE)
            val arr = patch.optJSONArray("trackedDevices") ?: JSONArray()
            val next = arr.toString()
            val cur = presence.getString(KEY_TRACKED, "[]") ?: "[]"
            if (cur != next) {
                presence.edit().putString(KEY_TRACKED, next).apply()
                changed = true
            }
        }

        return changed
    }

    private fun writePresencePrefsDirect(context: Context, patch: JSONObject): Boolean {
        val presence = context.getSharedPreferences(PRESENCE_PREFS, Context.MODE_PRIVATE)
        val ed = presence.edit()
        var changed = false
        if (patch.has("rssiThreshold")) {
            val v = patch.optInt("rssiThreshold", -80).coerceIn(-100, -40)
            if (presence.getInt(KEY_RSSI, -80) != v) {
                ed.putInt(KEY_RSSI, v)
                changed = true
            }
        }
        if (patch.has("awayDelaySeconds")) {
            val v = patch.optInt("awayDelaySeconds", 120).coerceIn(5, 3600)
            if (presence.getInt(KEY_AWAY, 120) != v) {
                ed.putInt(KEY_AWAY, v)
                changed = true
            }
        }
        if (patch.has("proxyScanMode")) {
            val raw = patch.optString("proxyScanMode")
            val v = if (raw == "passive" || raw == "active") raw else "auto"
            if ((presence.getString(KEY_PROXY_MODE, "auto") ?: "auto") != v) {
                ed.putString(KEY_PROXY_MODE, v)
                changed = true
            }
        }
        if (patch.has("proxyScanPower")) {
                val raw = patch.optString("proxyScanPower", "low")
                val v = if (raw == "balanced" || raw == "high") raw else "low"
            if ((presence.getString(KEY_PROXY_POWER, "low") ?: "low") != v) {
                ed.putString(KEY_PROXY_POWER, v)
                changed = true
            }
        }
        if (patch.has("presenceAlertAddress")) {
            val v = patch.optString("presenceAlertAddress", "").trim()
            val cur = presence.getString(KEY_ALERT_ADDRESS, "") ?: ""
            if (cur != v) {
                if (v.isEmpty()) ed.remove(KEY_ALERT_ADDRESS) else ed.putString(KEY_ALERT_ADDRESS, v)
                changed = true
            }
        }
        if (changed) ed.apply()
        return changed
    }
}
