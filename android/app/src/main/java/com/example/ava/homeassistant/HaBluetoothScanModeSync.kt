package com.example.ava.homeassistant

import android.content.Context
import android.util.Log
import com.example.ava.bluetooth.BluetoothPresenceManager
import com.example.ava.settings.HaSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps Ava's Bluetooth scan-mode pref (`auto`/`active`/`passive`) in lockstep
 * with the ESPHome config-entry option `bluetooth_scanning_mode`.
 *
 * Reads and writes the HA option string via the options flow. Never mirrors
 * BluetoothScannerSetModeRequest — that pin collapses Auto to PASSIVE on
 * the wire and must stay a runtime echo.
 */
class HaBluetoothScanModeSync(
    context: Context,
    private val scope: CoroutineScope,
    private val client: HaWsClient,
    private val settingsStore: HaSettingsStore,
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = BluetoothPresenceManager.getInstance(appContext)
    private val satelliteStore = appContext.voiceSatelliteSettingsStore
    private val mutex = Mutex()

    @Volatile private var cachedEntryId: String? = null
    @Volatile private var suppressAvaPushUntilMs = 0L
    @Volatile private var suppressHaPullUntilMs = 0L
    @Volatile private var lastPulledMode: String? = null
    @Volatile private var loggedMissingEntry = false

    fun start() {
        scope.launch {
            combine(
                client.connectionState,
                settingsStore.scanModeSyncEnabled,
            ) { state, enabled ->
                state is HaWsClient.ConnectionState.Connected && enabled
            }.distinctUntilChanged().collectLatest { active ->
                cachedEntryId = null
                loggedMissingEntry = false
                if (active) runSession()
            }
        }
    }

    private suspend fun runSession() {
        mutex.withLock { pullHaToAva() }
        val subId = client.subscribeConfigEntries { event ->
            scope.launch { onConfigEntryEvent(event) }
        }
        try {
            bluetoothManager.proxyScanModeFlow
                .drop(1)
                .distinctUntilChanged()
                .debounce(AVA_PUSH_DEBOUNCE_MS)
                .collect { mode ->
                    mutex.withLock { pushAvaToHa(mode) }
                }
        } finally {
            subId?.let { client.unsubscribe(it) }
        }
    }

    private suspend fun onConfigEntryEvent(event: JSONObject) {
        val payload = event.opt("event") ?: return
        val items = when (payload) {
            is JSONArray -> payload
            else -> return
        }
        val ours = cachedEntryId ?: return
        var updated = false
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val type = item.optString("type")
            if (type != "updated") continue
            val entry = item.optJSONObject("entry") ?: continue
            if (entry.optString("entry_id") == ours) updated = true
        }
        if (!updated) return
        if (System.currentTimeMillis() < suppressHaPullUntilMs) return
        mutex.withLock { pullHaToAva() }
    }

    private suspend fun pullHaToAva() {
        HaEsphomeOptionsGate.withLock {
            val form = openScanModeForm() ?: return@withLock
            val haMode = normalizeMode(form.defaults[OPTION_SCAN_MODE] as? String)
            client.abortOptionsFlow(form.flowId)
            if (haMode == null) return@withLock
            val avaMode = normalizeMode(bluetoothManager.proxyScanMode) ?: "auto"
            if (haMode == avaMode) return@withLock
            lastPulledMode = haMode
            suppressAvaPushUntilMs = System.currentTimeMillis() + AVA_ECHO_GUARD_MS
            bluetoothManager.proxyScanMode = haMode
            Log.i(TAG, "Pulled HA scan mode $haMode into Ava settings")
        }
    }

    private suspend fun pushAvaToHa(rawMode: String) {
        val avaMode = normalizeMode(rawMode) ?: return
        val now = System.currentTimeMillis()
        if (now < suppressAvaPushUntilMs && avaMode == lastPulledMode) return
        HaEsphomeOptionsGate.withLock {
            val form = openScanModeForm() ?: return@withLock
            val haMode = normalizeMode(form.defaults[OPTION_SCAN_MODE] as? String)
            if (haMode == avaMode) {
                client.abortOptionsFlow(form.flowId)
                return@withLock
            }
            val values = form.defaults.toMutableMap()
            values[OPTION_SCAN_MODE] = avaMode
            val ok = client.submitOptionsFlow(form, values)
            if (ok) {
                suppressHaPullUntilMs = System.currentTimeMillis() + HA_ECHO_GUARD_MS
                Log.i(TAG, "Pushed Ava scan mode $avaMode to HA options")
            }
        }
    }

    private suspend fun openScanModeForm(): HaOptionsFlowForm? {
        val entryId = cachedEntryId ?: resolveEntryId()?.also { cachedEntryId = it }
        if (entryId == null) {
            if (!loggedMissingEntry) {
                loggedMissingEntry = true
                Log.w(TAG, "No unique ESPHome entry for this device; scan-mode sync idle")
            }
            return null
        }
        val form = client.startOptionsFlow(entryId)
        if (form == null) return null
        if (!form.defaults.containsKey(OPTION_SCAN_MODE)) {
            client.abortOptionsFlow(form.flowId)
            Log.w(TAG, "ESPHome options have no $OPTION_SCAN_MODE; skip")
            return null
        }
        return form
    }

    private suspend fun resolveEntryId(): String? {
        return HaEsphomeEntryResolver.resolve(client, satelliteStore.data.first())
    }

    companion object {
        private const val TAG = "HaBtScanSync"
        private const val OPTION_SCAN_MODE = "bluetooth_scanning_mode"
        private const val AVA_PUSH_DEBOUNCE_MS = 800L
        private const val AVA_ECHO_GUARD_MS = 2_000L
        private const val HA_ECHO_GUARD_MS = 15_000L

        private fun normalizeMode(raw: String?): String? = when (raw?.lowercase()) {
            "auto", "active", "passive" -> raw.lowercase()
            else -> null
        }
    }
}
