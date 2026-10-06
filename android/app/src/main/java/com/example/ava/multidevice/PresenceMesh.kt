package com.example.ava.multidevice

import com.example.ava.voice.AvaVoiceDiscovery
import com.example.ava.voice.AvaVoiceProtocol

/**
 * House context for the Bayesian occupancy *prior*, distilled from the
 * existing Ava identity beacons (UDP 19848).
 *
 * Deliberately owns no socket: peer occupancy rides [AvaVoiceDiscovery]'s
 * beacon as an optional `occupied=0|1` field — same rule as clusterPort /
 * voiceMessaging ("no second UDP protocol"). That listener is held by the
 * Voice Satellite ([AvaVoiceDiscovery.HOLDER_PRESENCE]) with wedge-recovery
 * a hand-rolled socket here would have to re-learn.
 *
 * Peers that do not run the occupancy sensor omit the field and cannot
 * testify about the house either way. The only local state is the handoff
 * memory: a peer that just cleared likely saw the person start walking to
 * another room, so the house stays "active" for [HANDOFF_WINDOW_MS].
 */
object PresenceMesh {

    /**
     * After a peer clears, the person it saw is likely walking through the
     * house for this long — the room they enter next should believe its
     * first weak signals.
     */
    private const val HANDOFF_WINDOW_MS = 45_000L

    /** Wall-clock ms of the last occupied=1 beacon per peer id (ticker + reset only). */
    private val lastOccupiedAtMs = HashMap<String, Long>()

    /**
     * `null` — no occupancy-capable peer heard lately (single device, keep the
     * neutral prior); `true` — some peer sees or recently saw someone (a person
     * is home and mobile); `false` — such peers exist and every one reports empty.
     */
    fun houseActive(): Boolean? {
        val now = System.currentTimeMillis()
        val peers = AvaVoiceDiscovery.devices.value.filter { peer ->
            peer.occupied != null && now - peer.lastSeenMs <= AvaVoiceProtocol.DEVICE_STALE_MS
        }
        synchronized(lastOccupiedAtMs) {
            for (peer in peers) {
                if (peer.occupied == true) lastOccupiedAtMs[peer.id] = now
            }
            lastOccupiedAtMs.entries.removeAll { now - it.value > HANDOFF_WINDOW_MS }
            if (peers.isEmpty()) return null
            return peers.any { it.occupied == true } || lastOccupiedAtMs.isNotEmpty()
        }
    }

    fun reset() {
        synchronized(lastOccupiedAtMs) { lastOccupiedAtMs.clear() }
    }
}
