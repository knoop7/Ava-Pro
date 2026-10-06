package com.example.ava.homeassistant.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Signals that the set of HA entities some consumer cares about has changed.
 *
 * The WebSocket feed is filtered against that set as it arrives, so a newly watched entity
 * would otherwise sit at its placeholder until it happened to change state on its own. The
 * `subscribe_entities` session watches this and restarts, which replays a full snapshot
 * under the new filter.
 */
object HaEntityInterest {

    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** Call only when the entity set actually changed, since each bump costs a snapshot. */
    fun bump() {
        _generation.value += 1
    }
}
