package com.example.ava.bluetooth

/**
 * Peer-to-peer BTHome slot claims over UDP.
 *
 * Wire format stays backward compatible with the original `AVA_CLAIM:<id>:<address>`:
 * extra fields are optional so older panels still reserve a slot, they just cannot
 * take part in RSSI takeover.
 */
internal enum class ClaimMessageKind { CLAIM, RELEASE }

internal data class ParsedClaimMessage(
    val kind: ClaimMessageKind,
    val senderId: String,
    val address: Long,
    val rssi: Int = UNKNOWN_CLAIM_RSSI,
    val heldMs: Long = 0L,
    val echoTag: String = "",
)

/** Old `AVA_CLAIM:id:address` packets have no RSSI; 0 dBm must not win every fight. */
internal const val UNKNOWN_CLAIM_RSSI = Int.MIN_VALUE

internal fun Int.hasClaimRssi(): Boolean = this != UNKNOWN_CLAIM_RSSI

internal object BluetoothClaimProtocol {
    const val CLAIM_PREFIX = "AVA_CLAIM:"
    const val RELEASE_PREFIX = "AVA_RELEASE:"
    const val UNKNOWN_RSSI = UNKNOWN_CLAIM_RSSI

    fun encodeClaim(
        localId: String,
        address: Long,
        rssi: Int,
        heldMs: Long,
        echoTag: String,
    ): String = "$CLAIM_PREFIX$localId:$address:$rssi:$heldMs:$echoTag"

    fun encodeRelease(localId: String, address: Long, echoTag: String): String =
        "$RELEASE_PREFIX$localId:$address:$echoTag"

    fun parse(raw: String): ParsedClaimMessage? {
        val kind = when {
            raw.startsWith(CLAIM_PREFIX) -> ClaimMessageKind.CLAIM
            raw.startsWith(RELEASE_PREFIX) -> ClaimMessageKind.RELEASE
            else -> return null
        }
        val payload = if (kind == ClaimMessageKind.CLAIM) {
            raw.removePrefix(CLAIM_PREFIX)
        } else {
            raw.removePrefix(RELEASE_PREFIX)
        }
        val parts = payload.split(":")
        if (parts.size < 2) return null
        val senderId = parts[0]
        if (senderId.isBlank()) return null
        val address = parts[1].toLongOrNull() ?: return null
        return ParsedClaimMessage(
            kind = kind,
            senderId = senderId,
            address = address,
            rssi = parts.getOrNull(2)?.toIntOrNull() ?: UNKNOWN_CLAIM_RSSI,
            heldMs = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
            echoTag = parts.getOrNull(4).orEmpty(),
        )
    }
}

internal data class ClaimRecord(
    val address: Long,
    val ownerId: String,
    val rssi: Int,
    val lastHeardMs: Long,
    val claimedAtMs: Long,
    val strongerStrikes: Int = 0,
)

internal enum class ClaimOutcome { NEW, ALREADY, REJECTED }

/**
 * In-memory claim book. UDP and BLE sightings feed this; it is the only place that
 * decides first-come ownership, expiry, and RSSI takeover.
 */
internal class BluetoothClaimLedger(
    private val localId: String,
    private val echoTag: String,
    private val maxClaims: Int = 5,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val HEARTBEAT_MS = 8_000L
        /**
         * Compatibility scan is 5s on + 25s rest. Local lastHeard is BLE-only, so this
         * must clear a full rest plus a missed cycle or we RELEASE during the quiet gap
         * and the other panel steals the slot.
         */
        const val LOCAL_EXPIRE_MS = 90_000L
        /** UDP heartbeat is 8s; a quiet peer is gone, not resting the radio. */
        const val REMOTE_EXPIRE_MS = 20_000L
        const val RSSI_HYSTERESIS_DB = 8
        const val MIN_HOLD_MS = 12_000L
        const val TAKEOVER_STRIKES = 3
    }

    private val local = LinkedHashMap<Long, ClaimRecord>()
    private val remote = LinkedHashMap<Long, ClaimRecord>()

    @Synchronized
    fun localAddresses(): List<Long> = local.keys.toList()

    @Synchronized
    fun localRecords(): List<ClaimRecord> = local.values.toList()

    @Synchronized
    fun isClaimed(address: Long): Boolean = address in local || address in remote

    @Synchronized
    fun claim(address: Long, rssi: Int): ClaimOutcome {
        val now = nowMs()
        local[address]?.let {
            local[address] = it.copy(rssi = rssi, lastHeardMs = now)
            return ClaimOutcome.ALREADY
        }
        val remoteRec = remote[address]
        if (remoteRec != null &&
            !isRemoteStale(remoteRec, now) &&
            !canTakeOver(rssi, remoteRec, now, remoteRec.strongerStrikes)
        ) {
            return ClaimOutcome.REJECTED
        }
        if (local.size >= maxClaims) return ClaimOutcome.REJECTED
        remote.remove(address)
        local[address] = ClaimRecord(address, localId, rssi, now, now)
        return ClaimOutcome.NEW
    }

    /**
     * A local BTHome sighting. Refreshes our own claim, or maybe takes a remote one
     * after hysteresis + min-hold + consecutive stronger strikes.
     * @return true when local ownership changed
     */
    @Synchronized
    fun noteObservation(address: Long, rssi: Int): Boolean {
        val now = nowMs()
        local[address]?.let {
            local[address] = it.copy(rssi = rssi, lastHeardMs = now)
            return false
        }
        val remoteRec = remote[address] ?: return false
        if (isRemoteStale(remoteRec, now)) {
            remote.remove(address)
            return false
        }
        val stronger = rssi.hasClaimRssi() && remoteRec.rssi.hasClaimRssi() &&
            rssi >= remoteRec.rssi + RSSI_HYSTERESIS_DB
        val strikes = if (stronger) remoteRec.strongerStrikes + 1 else 0
        if (canTakeOver(rssi, remoteRec, now, strikes) && local.size < maxClaims) {
            remote.remove(address)
            local[address] = ClaimRecord(address, localId, rssi, now, now)
            return true
        }
        remote[address] = remoteRec.copy(strongerStrikes = strikes)
        return false
    }

    /**
     * Apply a packet from the LAN. Self-echo (same id or same session tag) is ignored.
     * @return true when *local* ownership changed (we yielded)
     */
    @Synchronized
    fun applyRemote(msg: ParsedClaimMessage): Boolean {
        if (msg.senderId == localId || (msg.echoTag.isNotEmpty() && msg.echoTag == echoTag)) {
            return false
        }
        val now = nowMs()
        return when (msg.kind) {
            ClaimMessageKind.RELEASE -> {
                remote.remove(msg.address)
                false
            }
            ClaimMessageKind.CLAIM -> {
                val ours = local[msg.address]
                if (ours != null) {
                    val theyStronger = msg.rssi.hasClaimRssi() && ours.rssi.hasClaimRssi() &&
                        msg.rssi >= ours.rssi + RSSI_HYSTERESIS_DB
                    val theyHeld = msg.heldMs >= MIN_HOLD_MS
                    val weHeld = now - ours.claimedAtMs >= MIN_HOLD_MS
                    val strikes = if (theyStronger) ours.strongerStrikes + 1 else 0
                    if (theyStronger && theyHeld && weHeld && strikes >= TAKEOVER_STRIKES) {
                        local.remove(msg.address)
                        remote[msg.address] = ClaimRecord(
                            address = msg.address,
                            ownerId = msg.senderId,
                            rssi = msg.rssi,
                            lastHeardMs = now,
                            claimedAtMs = now - msg.heldMs,
                        )
                        return true
                    }
                    local[msg.address] = ours.copy(strongerStrikes = strikes)
                    return false
                }
                val prev = remote[msg.address]
                remote[msg.address] = ClaimRecord(
                    address = msg.address,
                    ownerId = msg.senderId,
                    rssi = msg.rssi,
                    lastHeardMs = now,
                    claimedAtMs = if (msg.heldMs > 0) now - msg.heldMs else (prev?.claimedAtMs ?: now),
                )
                false
            }
        }
    }

    @Synchronized
    fun expire(): List<Long> {
        val now = nowMs()
        val released = mutableListOf<Long>()
        val localIt = local.entries.iterator()
        while (localIt.hasNext()) {
            val rec = localIt.next().value
            if (now - rec.lastHeardMs >= LOCAL_EXPIRE_MS) {
                released += rec.address
                localIt.remove()
            }
        }
        val remoteIt = remote.entries.iterator()
        while (remoteIt.hasNext()) {
            if (isRemoteStale(remoteIt.next().value, now)) {
                remoteIt.remove()
            }
        }
        return released
    }

    @Synchronized
    fun clearLocal(): List<Long> {
        val addresses = local.keys.toList()
        local.clear()
        return addresses
    }

    private fun isRemoteStale(rec: ClaimRecord, now: Long): Boolean =
        now - rec.lastHeardMs >= REMOTE_EXPIRE_MS

    private fun canTakeOver(ourRssi: Int, remoteRec: ClaimRecord, now: Long, strikes: Int): Boolean {
        if (isRemoteStale(remoteRec, now)) return true
        if (!ourRssi.hasClaimRssi() || !remoteRec.rssi.hasClaimRssi()) return false
        val held = now - remoteRec.claimedAtMs
        return ourRssi >= remoteRec.rssi + RSSI_HYSTERESIS_DB &&
            held >= MIN_HOLD_MS &&
            strikes >= TAKEOVER_STRIKES
    }
}
