package com.example.ava.wakelocks

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.PowerManager
import android.util.Log

class BluetoothWakeLock {
    private var wakeLock: PowerManager.WakeLock? = null
    private var isAcquired = false
    
    fun create(context: Context, tag: String) {
        if (wakeLock != null) {
            Log.d(TAG, "BluetoothWakeLock already initialized")
            return
        }
        
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$tag::BluetoothWakeLock"
        ).apply {
            // Repeated acquire() must extend the timeout instead of stacking a refcount.
            setReferenceCounted(false)
        }
    }
    
    private val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L
    
    fun acquire() {
        if (isAcquired) return
        
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(WAKELOCK_TIMEOUT_MS)
                isAcquired = true
                Log.d(TAG, "Bluetooth wake lock acquired with ${WAKELOCK_TIMEOUT_MS}ms timeout")
            }
        }
    }
    
    /**
     * Extend the timeout BEFORE it expires. Renewing only after expiry never works: the
     * moment the lock lapses the CPU suspends and the uptime-based renewal handler
     * freezes, so nothing is left running to re-acquire it (scan dies ~30 min after
     * screen off). While the lock is held the CPU stays awake and this runs on time.
     */
    fun renewIfNeeded() {
        wakeLock?.let {
            if (isAcquired) {
                it.acquire(WAKELOCK_TIMEOUT_MS)
                Log.d(TAG, "Bluetooth wake lock timeout extended")
            }
        }
    }
    
    fun release() {
        isAcquired = false
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Bluetooth wake lock released")
            }
        }
    }
    
    companion object {
        private const val TAG = "BluetoothWakeLock"
        
        fun keepBluetoothAlive(context: Context) {
            try {
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bluetoothManager?.adapter
                if (adapter != null && !adapter.isEnabled) {
                    Log.w(TAG, "Bluetooth is disabled")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "No bluetooth permission", e)
            }
        }
    }
}
