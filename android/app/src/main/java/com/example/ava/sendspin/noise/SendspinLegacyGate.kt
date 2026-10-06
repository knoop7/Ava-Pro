package com.example.ava.sendspin.noise

import android.os.Build
import android.os.SystemClock

/**
 * After a failed `client/init` (old MA closes), the next connection uses the
 * existing plaintext `client/hello` path. New MA always speaks `server/init`,
 * so this does not stick permanently.
 */
internal object SendspinLegacyGate {
    private const val HOLD_MS = 180_000L

    @Volatile
    private var untilElapsed = 0L

    fun preferLegacy(): Boolean {
        val until = untilElapsed
        if (until == 0L) return false
        val now = SystemClock.elapsedRealtime()
        if (now >= until) {
            untilElapsed = 0L
            return false
        }
        return true
    }

    fun armAfterFailedInit() {
        untilElapsed = SystemClock.elapsedRealtime() + HOLD_MS
    }

    /**
     * A noise frame we could not accept. On Android 5–7.1 that is a handshake
     * failure on our side, not an old server: the following plaintext
     * `client/hello` is what strict MA rejects. Leave the gate clear so the
     * next socket retries `client/init`. A server that simply closes still
     * arms via [armAfterFailedInit].
     */
    fun armAfterRejectedNoiseFrame() {
        val sdk = try {
            Build.VERSION.SDK_INT
        } catch (_: Throwable) {
            0
        }
        if (sdk in 1..Build.VERSION_CODES.N_MR1) return
        armAfterFailedInit()
    }

    fun clear() {
        untilElapsed = 0L
    }
}
