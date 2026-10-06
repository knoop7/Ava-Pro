package com.example.ava.homeassistant.state

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a HA entity state push came from. */
enum class HaStateSource {
    /** Direct `subscribe_entities` over the authenticated WebSocket (Ava is the client). */
    WEBSOCKET,

    /** ESPHome reverse channel — HA pushes into Ava's native API server. */
    ESPHOME,
}

/**
 * Decides which channel currently owns HA entity state.
 *
 * Both channels can be connected at the same time, and both carry the same entities, so
 * letting them both write would mean two writers racing over one cache. Exactly one source
 * is authoritative at any moment; pushes from the other are dropped at the ingress gate
 * ([accepts]) before they touch any cache.
 *
 * WebSocket wins whenever it is available because it delivers full attribute dictionaries
 * and does not depend on HA being able to reach back into this device.
 */
object HaStateArbiter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeSource = MutableStateFlow(HaStateSource.ESPHOME)
    val activeSource: StateFlow<HaStateSource> = _activeSource.asStateFlow()

    /**
     * Read on every state push from the API reader threads, written from the WS listener,
     * so it is kept as a plain volatile rather than going through the flow.
     */
    @Volatile
    private var active: HaStateSource = HaStateSource.ESPHOME

    @Volatile
    private var websocketLive = false

    private var esphomeResync: (suspend () -> Unit)? = null
    private var demoteJob: Job? = null

    /**
     * Ingress gate. Returns false when [source] is not the authoritative channel, in which
     * case the caller must drop the push without touching any cache.
     */
    fun accepts(source: HaStateSource): Boolean = active == source

    /**
     * Called when the direct WebSocket `subscribe_entities` subscription is established or lost.
     *
     * Promotion is immediate: the subscription opens with a full snapshot, so WebSocket can
     * take over without a gap. Demotion is delayed slightly so a brief reconnect does not
     * trigger an ESPHome resubscribe storm.
     */
    fun setWebsocketLive(live: Boolean) {
        if (websocketLive == live) return
        websocketLive = live
        demoteJob?.cancel()
        if (live) {
            switchTo(HaStateSource.WEBSOCKET)
        } else {
            demoteJob = scope.launch {
                delay(DEMOTE_GRACE_MS)
                if (!websocketLive) switchTo(HaStateSource.ESPHOME)
            }
        }
    }

    /**
     * Registers the ESPHome resubscribe action, invoked when ESPHome regains ownership.
     * HA replies to a state subscription with the current value, so resubscribing is what
     * refills caches that went stale while the WebSocket owned them.
     */
    fun setEsphomeResync(block: suspend () -> Unit) {
        esphomeResync = block
    }

    fun clearEsphomeResync() {
        esphomeResync = null
    }

    private fun switchTo(source: HaStateSource) {
        if (active == source) return
        active = source
        _activeSource.value = source
        Log.i(TAG, "HA entity state source -> $source")
        if (source == HaStateSource.ESPHOME) {
            val resync = esphomeResync ?: return
            scope.launch {
                try {
                    resync()
                } catch (e: Exception) {
                    Log.w(TAG, "ESPHome resync after source switch failed: ${e.message}")
                }
            }
        }
    }

    private const val TAG = "HaStateArbiter"
    private const val DEMOTE_GRACE_MS = 2_000L
}
