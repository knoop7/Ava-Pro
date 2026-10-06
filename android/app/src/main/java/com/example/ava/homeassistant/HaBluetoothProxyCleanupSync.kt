package com.example.ava.homeassistant

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.ava.settings.HaSettingsStore
import com.example.ava.settings.voiceSatelliteSettingsStore
import com.example.ava.ui.AvaToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * When the Bluetooth master switch falls off and More → auto-clean is on,
 * delete this Ava's leftover Bluetooth remote-scanner config entry.
 *
 * Runs once per off-period. Process start, HA reconnect, and a master
 * switch that is already off do not call the async delete.
 */
class HaBluetoothProxyCleanupSync(
    context: Context,
    private val scope: CoroutineScope,
    private val client: HaWsClient,
    private val settingsStore: HaSettingsStore,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @Volatile private var cleanedThisOffPeriod = false

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            detectEnabledFlow()
                .distinctUntilChanged()
                .debounce(OFF_DEBOUNCE_MS)
                .drop(1)
                .collect { enabled ->
                    if (enabled) {
                        cleanedThisOffPeriod = false
                        return@collect
                    }
                    if (cleanedThisOffPeriod) return@collect
                    if (!settingsStore.btProxyCleanupEnabled.get()) return@collect
                    if (currentDetectEnabled()) return@collect
                    mutex.withLock {
                        if (cleanedThisOffPeriod) return@withLock
                        if (currentDetectEnabled()) return@withLock
                        onMasterOff()
                    }
                }
        }
    }

    private suspend fun onMasterOff() {
        if (client.connectionState.value !is HaWsClient.ConnectionState.Connected) {
            Log.i(TAG, "skip cleanup: websocket not connected")
            return
        }
        cleanedThisOffPeriod = true
        val settings = appContext.voiceSatelliteSettingsStore.data.first()
        val result = HaBluetoothProxyCleanup.run(client, settings, detectEnabled = false)
        Log.i(TAG, "auto cleanup removed=${result.removed} failed=${result.failed} reason=${result.reason}")
        val message = toastMessage(result) ?: return
        AvaToast.show(appContext, message, durationMs = AvaToast.LONG_MS)
    }

    private fun toastMessage(result: HaBluetoothProxyCleanup.Result): String? {
        return when (result.reason) {
            null -> when {
                result.failed > 0 -> appContext.getString(
                    com.example.ava.R.string.settings_ha_bt_cleanup_partial,
                    result.removed,
                    result.failed,
                )
                result.removed > 0 -> appContext.getString(
                    com.example.ava.R.string.settings_ha_bt_cleanup_done,
                    result.removed,
                )
                else -> null
            }
            else -> null
        }
    }

    private fun currentDetectEnabled(): Boolean {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DETECT, false)
    }

    private fun detectEnabledFlow() = callbackFlow {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        trySend(prefs.getBoolean(KEY_DETECT, false))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key == KEY_DETECT) {
                trySend(shared.getBoolean(KEY_DETECT, false))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val TAG = "HaBtCleanupSync"
        private const val PREFS = "bluetooth_settings"
        private const val KEY_DETECT = "detect_enabled"
        private const val OFF_DEBOUNCE_MS = 800L
    }
}
