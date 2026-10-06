package com.example.ava.homeassistant

import android.util.Log
import com.example.ava.homeassistant.state.HaEntityInterest
import com.example.ava.homeassistant.state.HaStateArbiter
import com.example.ava.homeassistant.state.HaStateSource
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.settings.HaSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps a `subscribe_entities` session open on the direct WebSocket so HA entity state
 * arrives without depending on HA being able to reach back into this device's ESPHome API.
 *
 * The session is only meaningful while the satellite is up, because its caches and fan-out
 * are what every consumer reads. Ownership of entity state is handed to the WebSocket for
 * as long as the session lives and returned to the ESPHome channel when it ends — see
 * [HaStateArbiter], which guarantees only one of the two ever writes.
 */
class HaEntityStateSync(
    private val scope: CoroutineScope,
    private val client: HaWsClient,
    private val settingsStore: HaSettingsStore,
) {
    fun start() {
        scope.launch {
            combine(
                client.connectionState,
                settingsStore.liveStateEnabled,
                VoiceSatelliteService.satelliteStarted,
                // Restarting on an interest change replays the snapshot under the new
                // filter, so a newly watched entity is populated immediately instead of
                // waiting for its next state change.
                HaEntityInterest.generation,
            ) { state, enabled, satelliteUp, interestGeneration ->
                val active = state is HaWsClient.ConnectionState.Connected && enabled && satelliteUp
                if (active) interestGeneration else null
            }.distinctUntilChanged().collectLatest { generation ->
                if (generation != null) runSession() else HaStateArbiter.setWebsocketLive(false)
            }
        }
    }

    private suspend fun runSession() {
        // Both of these must happen before subscribing. HA starts writing the snapshot to
        // the socket as soon as the request lands, and those pushes are read on the socket
        // thread — anything arriving before promotion would be dropped by the ingress gate.
        VoiceSatelliteService.getInstance()?.refreshHaEntityInterest()
        HaStateArbiter.setWebsocketLive(true)

        val subscriptionId = client.subscribeEntities { entityId, attribute, state ->
            VoiceSatelliteService.applyHaEntityState(
                source = HaStateSource.WEBSOCKET,
                entityId = entityId,
                attribute = attribute,
                state = state,
            )
        }
        if (subscriptionId == null) {
            HaStateArbiter.setWebsocketLive(false)
            Log.w(TAG, "subscribe_entities unavailable; entity state stays on the ESPHome channel")
            return
        }

        Log.i(TAG, "Live HA entity state over WebSocket (subscription $subscriptionId)")
        try {
            awaitCancellation()
        } finally {
            HaStateArbiter.setWebsocketLive(false)
            client.unsubscribe(subscriptionId)
        }
    }

    companion object {
        private const val TAG = "HaEntityStateSync"
    }
}
