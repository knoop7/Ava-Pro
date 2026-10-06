package com.example.ava.wakelocks

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * PARTIAL_WAKE_LOCK held only while the screen is off, deliberately with NO timeout.
 *
 * [WifiWakeLock] and [BluetoothWakeLock] carry a 30-minute safety timeout and depend on a
 * Handler-based renewal every 25 minutes. That renewal is uptime-based: if the CPU ever
 * suspends (vendor power policy revoking timed locks in deep sleep), the handler freezes
 * and can never re-acquire — the renewal chain needs an awake CPU, but keeping the CPU
 * awake needs the renewal chain. This lock breaks that circular dependency: while the
 * screen is off it holds the CPU unconditionally so every keepalive timer (stale-client
 * watchdog pings, wake lock renewals) fires on schedule instead of waiting for the next
 * interrupt.
 *
 * The missing timeout is safe: wake locks are bound to a Binder token, so the system
 * releases it automatically if the process dies. Screen-on and service destruction
 * release it explicitly; while the screen is on Android keeps the CPU awake anyway.
 */
class CpuScreenOffWakeLock {
    private var wakeLock: PowerManager.WakeLock? = null

    fun create(context: Context, tag: String) {
        if (wakeLock != null) {
            Log.d(TAG, "CpuScreenOffWakeLock already initialized")
            return
        }
        wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$tag::CpuScreenOffWakeLock")
            .apply {
                // Repeated acquire() must stay idempotent instead of stacking a refcount.
                setReferenceCounted(false)
            }
    }

    @SuppressLint("WakelockTimeout")
    fun acquire() {
        val lock = wakeLock ?: return
        if (lock.isHeld) return
        lock.acquire()
        Log.i(TAG, "CPU wake lock acquired for screen-off (no timeout)")
    }

    fun release() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            lock.release()
            Log.i(TAG, "CPU wake lock released")
        }
    }

    companion object {
        private const val TAG = "CpuScreenOffWakeLock"
    }
}
