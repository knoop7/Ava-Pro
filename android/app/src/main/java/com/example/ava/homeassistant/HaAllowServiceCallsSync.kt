package com.example.ava.homeassistant

import android.content.Context
import android.util.Log
import com.example.ava.settings.HaSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps Ava's `allowServiceCalls` pref in lockstep with the ESPHome
 * config-entry option `allow_service_calls`.
 *
 * New HA ESPHome entries default this to off, which silently breaks
 * weather, media, scenes, and quick-entity actions.
 */
class HaAllowServiceCallsSync(
    context: Context,
    private val scope: CoroutineScope,
    private val client: HaWsClient,
    private val settingsStore: HaSettingsStore,
) {
    private val satelliteStore = context.applicationContext.voiceSatelliteSettingsStore
    private val mutex = Mutex()

    @Volatile private var cachedEntryId: String? = null
    @Volatile private var suppressAvaPushUntilMs = 0L
    @Volatile private var suppressHaPullUntilMs = 0L
    @Volatile private var lastPulled: Boolean? = null
    @Volatile private var loggedMissingEntry = false

    fun start() {
        scope.launch {
            client.connectionState
                .map { it is HaWsClient.ConnectionState.Connected }
                .distinctUntilChanged()
                .collectLatest { active ->
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
            settingsStore.allowServiceCalls
                .drop(1)
                .distinctUntilChanged()
                .debounce(AVA_PUSH_DEBOUNCE_MS)
                .collect { enabled ->
                    mutex.withLock { pushAvaToHa(enabled) }
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
            if (item.optString("type") != "updated") continue
            val entry = item.optJSONObject("entry") ?: continue
            if (entry.optString("entry_id") == ours) updated = true
        }
        if (!updated) return
        if (System.currentTimeMillis() < suppressHaPullUntilMs) return
        mutex.withLock { pullHaToAva() }
    }

    private suspend fun pullHaToAva() {
        HaEsphomeOptionsGate.withLock {
            val form = openForm() ?: return@withLock
            val haValue = parseBool(form.defaults[OPTION])
            client.abortOptionsFlow(form.flowId)
            if (haValue == null) return@withLock
            val avaValue = settingsStore.allowServiceCalls.get()
            if (haValue == avaValue) return@withLock
            lastPulled = haValue
            suppressAvaPushUntilMs = System.currentTimeMillis() + AVA_ECHO_GUARD_MS
            settingsStore.allowServiceCalls.set(haValue)
            Log.i(TAG, "Pulled HA allow_service_calls $haValue into Ava settings")
        }
    }

    private suspend fun pushAvaToHa(enabled: Boolean) {
        val now = System.currentTimeMillis()
        if (now < suppressAvaPushUntilMs && enabled == lastPulled) return
        HaEsphomeOptionsGate.withLock {
            val form = openForm() ?: return@withLock
            val haValue = parseBool(form.defaults[OPTION])
            if (haValue == enabled) {
                client.abortOptionsFlow(form.flowId)
                return@withLock
            }
            val values = form.defaults.toMutableMap()
            values[OPTION] = enabled
            val ok = client.submitOptionsFlow(form, values)
            if (ok) {
                suppressHaPullUntilMs = System.currentTimeMillis() + HA_ECHO_GUARD_MS
                Log.i(TAG, "Pushed Ava allow_service_calls $enabled to HA options")
            }
        }
    }

    private suspend fun openForm(): HaOptionsFlowForm? {
        val entryId = cachedEntryId ?: resolveEntryId()?.also { cachedEntryId = it }
        if (entryId == null) {
            if (!loggedMissingEntry) {
                loggedMissingEntry = true
                Log.w(TAG, "No unique ESPHome entry for this device; allow_service_calls sync idle")
            }
            return null
        }
        val form = client.startOptionsFlow(entryId) ?: return null
        if (!form.defaults.containsKey(OPTION)) {
            client.abortOptionsFlow(form.flowId)
            Log.w(TAG, "ESPHome options have no $OPTION; skip")
            return null
        }
        return form
    }

    private suspend fun resolveEntryId(): String? {
        return HaEsphomeEntryResolver.resolve(client, satelliteStore.data.first())
    }

    companion object {
        private const val TAG = "HaServiceCallsSync"
        private const val OPTION = "allow_service_calls"
        private const val AVA_PUSH_DEBOUNCE_MS = 800L
        private const val AVA_ECHO_GUARD_MS = 2_000L
        private const val HA_ECHO_GUARD_MS = 15_000L

        private fun parseBool(raw: Any?): Boolean? = when (raw) {
            is Boolean -> raw
            is String -> raw.lowercase().toBooleanStrictOrNull()
            else -> null
        }
    }
}
