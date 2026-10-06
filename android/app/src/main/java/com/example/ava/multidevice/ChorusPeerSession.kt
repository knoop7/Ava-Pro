package com.example.ava.multidevice

/**
 * Loser-side hold while a peer still owns the wake.
 * Mic stays open. Only wake detection should read [isActive] (stop detection is
 * deliberately outside the hold — a loser with a ringing alarm must still obey).
 * Local winner never enters the hold, so a competitor claim cannot mute the box that won.
 *
 * Lifetime is keepalive-sustained: every peer claim or AVA_WAKE_ALIVE beat re-arms
 * a short [PEER_SESSION_SUSTAIN_MS] window instead of one fixed 90 s grant. The
 * winner beats every few seconds while its session lives, so a hold outlasts any
 * conversation length — and a lost END broadcast deafens this device for seconds,
 * not a minute and a half.
 */
internal class ChorusPeerSession(
    private val timeoutMs: Long = PEER_SESSION_SUSTAIN_MS,
    private val nowMs: () -> Long,
) {
    private val lock = Any()
    private var active = false
    private var untilElapsed = 0L
    private var localWinner = false

    fun isActive(): Boolean = synchronized(lock) {
        active && nowMs() < untilElapsed
    }

    fun isLocalWinner(): Boolean = synchronized(lock) { localWinner }

    /** True once when the hold just ran out. Caller fades overlay / re-arms detect. */
    fun consumeExpired(): Boolean = synchronized(lock) {
        if (!active || nowMs() < untilElapsed) return false
        active = false
        untilElapsed = 0L
        true
    }

    fun setLocalWinner(won: Boolean) {
        synchronized(lock) {
            localWinner = won
            if (won) {
                active = false
                untilElapsed = 0L
            }
        }
    }

    /** Engage or extend the hold. True only when it was newly engaged (for logging). */
    fun markPeer(): Boolean = synchronized(lock) {
        if (localWinner) return false
        val fresh = !active || nowMs() >= untilElapsed
        active = true
        untilElapsed = nowMs() + timeoutMs
        fresh
    }

    fun endPeer(): Boolean = synchronized(lock) {
        val was = active
        active = false
        untilElapsed = 0L
        was
    }

    fun reset() {
        synchronized(lock) {
            localWinner = false
            active = false
            untilElapsed = 0L
        }
    }

    companion object {
        /**
         * Hold expiry counted from the last peer claim / keepalive beat. Sized to
         * survive a few lost beats ([WakeWordArbiter.ALIVE_INTERVAL_MS] apart) on a
         * DTIM-buffering AP, while capping the deafness a lost END broadcast can
         * cause. The old design was the inverse — a single 90 s grant — and one
         * lost END packet muted wake AND stop on the loser for the full window
         * ("stop only works once, restart fixes it").
         */
        const val PEER_SESSION_SUSTAIN_MS = 15_000L
    }
}
