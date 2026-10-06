package com.example.ava.mods

import java.util.concurrent.atomic.AtomicLong

/**
 * Token / generation / lease for a conversation-engine wake claim.
 *
 * Pure state — no Android, no ClassLoader. The host bridge owns binding and
 * microphone yield; this only answers "is this token still the seat?"
 */
internal class ConversationEngineSeat(
    private val nowMs: () -> Long,
    private val leaseMs: Long = DEFAULT_LEASE_MS,
    private val tokenFactory: () -> String = { "ce-${nextToken.getAndIncrement()}" },
) {
    data class Lease(
        val token: String,
        val modId: String,
        val generation: Long,
        val expiresAtMs: Long,
    )

    @Volatile
    var current: Lease? = null
        private set

    private val generationSeq = AtomicLong(0)

    fun isHeld(): Boolean {
        val lease = current ?: return false
        return nowMs() < lease.expiresAtMs
    }

    fun isCurrent(token: String): Boolean {
        if (token.isBlank()) return false
        val lease = current ?: return false
        return lease.token == token && nowMs() < lease.expiresAtMs
    }

    fun isExpired(): Boolean {
        val lease = current ?: return false
        return nowMs() >= lease.expiresAtMs
    }

    /** Latter-wins. Caller must have already notified the previous holder. */
    fun issue(modId: String): Lease {
        val lease = Lease(
            token = tokenFactory(),
            modId = modId,
            generation = generationSeq.incrementAndGet(),
            expiresAtMs = nowMs() + leaseMs,
        )
        current = lease
        return lease
    }

    fun release(token: String): Boolean {
        val lease = current ?: return false
        if (lease.token != token) return false
        current = null
        return true
    }

    fun renew(token: String): Boolean {
        val lease = current ?: return false
        if (lease.token != token) return false
        if (nowMs() >= lease.expiresAtMs) return false
        current = lease.copy(expiresAtMs = nowMs() + leaseMs)
        return true
    }

    fun clear(): Lease? {
        val lease = current
        current = null
        return lease
    }

    companion object {
        const val DEFAULT_LEASE_MS = 10L * 60L * 1000L
        private val nextToken = AtomicLong(1)
    }
}
