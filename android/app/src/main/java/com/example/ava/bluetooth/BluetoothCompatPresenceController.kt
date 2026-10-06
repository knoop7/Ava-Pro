package com.example.ava.bluetooth

import android.util.Log
import com.example.ava.utils.RootUtils
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Compatibility-mode presence helpers (low-end BLE chips only).
 *
 * Duty-cycle timing + scan-failure medic. Does not run on full/balanced tiers —
 * callers must gate with [BluetoothPresenceManager.isLowEndBleChip].
 */
object BluetoothCompatPresenceController {
    private const val TAG = "BtCompatPresence"

    /** Active LE scan window on low-end chips (ms). */
    const val SCAN_WINDOW_MS = 5_000L
    /** Idle gap between windows (ms). Keeps WiFi/BT stack breathing room. */
    const val SCAN_REST_MS = 25_000L

    private const val FAIL_THRESHOLD = 3
    private const val MEDIC_COOLDOWN_MS = 120_000L

    private val consecutiveFailures = AtomicInteger(0)
    private val lastMedicAt = AtomicLong(0L)

    fun resetFailureCount() {
        consecutiveFailures.set(0)
    }

    fun noteScanSuccess() {
        consecutiveFailures.set(0)
    }

    /**
     * @param allowMedic when false, the failure is recorded for logging but never escalates to
     *   an adapter reset. Use for registration/policy failures that look like a wedged stack
     *   from the outside but are caused by this app's scan setup.
     * @return true if medic attempted a stack recovery (caller should wait before next scan).
     */
    fun noteScanFailure(
        presenceManager: BluetoothPresenceManager,
        reason: String,
        allowMedic: Boolean = true,
    ): Boolean {
        if (!allowMedic) {
            Log.w(TAG, "compat scan failure (medic suppressed): $reason")
            return false
        }

        val fails = consecutiveFailures.incrementAndGet()
        Log.w(TAG, "compat scan failure #$fails: $reason")
        if (fails < FAIL_THRESHOLD) return false
        if (presenceManager.killingBluetoothWouldDropWifi()) {
            Log.w(TAG, "medic skipped: Bluetooth adapter reset would drop Wi-Fi")
            return false
        }

        val now = System.currentTimeMillis()
        val last = lastMedicAt.get()
        if (now - last < MEDIC_COOLDOWN_MS) {
            Log.i(TAG, "medic skipped (cooldown)")
            return false
        }
        if (!lastMedicAt.compareAndSet(last, now)) return false

        consecutiveFailures.set(0)
        Log.w(TAG, "medic: attempting Bluetooth adapter recovery")
        val reset = when {
            RootUtils.isRootAvailable() -> RootUtils.resetBluetoothAdapter()
            else -> presenceManager.resetBluetoothAdapter()
        }
        Log.i(TAG, "medic reset result=$reset")
        return reset
    }
}
