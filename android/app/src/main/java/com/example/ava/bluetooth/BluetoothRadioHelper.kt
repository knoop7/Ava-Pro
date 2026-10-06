package com.example.ava.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.example.ava.utils.RootUtils
import com.example.ava.utils.ScreenControlUtils
import com.example.ava.utils.ShizukuUtils

/**
 * Shared helpers for keeping the system Bluetooth radio on while Ava Device Detection
 * / BLE proxy is active.
 *
 * Priority:
 * 1. Root shell (`svc bluetooth enable`)
 * 2. Shizuku elevated shell
 * 3. Device Owner — [BluetoothAdapter.enable] is still allowed for DO/PO on API 33+
 * 4. Otherwise callers should launch [createRequestEnableIntent] (system Allow dialog)
 *
 * Plain Device Admin cannot toggle Bluetooth; it is not a substitute for root/Shizuku/DO.
 */
object BluetoothRadioHelper {
    private const val TAG = "BluetoothRadioHelper"

    /** [Settings.Global] key read by Bluetooth [Config] at stack start. */
    private const val SETTING_DISABLED_PROFILES = "bluetooth_disabled_profiles"

    private const val PREFS_NAME = "bluetooth_radio_helper"
    /** Set once a bring-up failure proved this device needs the profile-disable workaround. */
    private const val KEY_PROFILE_WORKAROUND_NEEDED = "profile_disable_workaround_needed"

    private const val A2DP_SINK_SERVICE =
        "com.android.bluetooth.a2dpsink.A2dpSinkService"
    private const val AVRCP_CONTROLLER_SERVICE =
        "com.android.bluetooth.avrcpcontroller.AvrcpControllerService"

    /** AOSP package plus the Android 13+ mainline (APEX) Bluetooth package. */
    private val BLUETOOTH_STACK_PACKAGES = listOf(
        "com.android.bluetooth",
        "com.google.android.bluetooth",
    )

    // Hidden BluetoothProfile ids: A2DP_SINK=11, AVRCP_CONTROLLER=12.
    private const val PROFILE_A2DP_SINK = 11
    private const val PROFILE_AVRCP_CONTROLLER = 12
    private const val MISSING_PROFILE_MASK_A2DP_SINK = 1L shl PROFILE_A2DP_SINK
    private const val MISSING_PROFILE_MASK_AVRCP_CONTROLLER = 1L shl PROFILE_AVRCP_CONTROLLER
    /** The only bits Ava ever writes; reconcile must never touch anything else. */
    private const val AVA_PROFILE_DISABLE_MASK =
        MISSING_PROFILE_MASK_A2DP_SINK or MISSING_PROFILE_MASK_AVRCP_CONTROLLER

    fun adapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    fun isEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    fun createRequestEnableIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    /**
     * Privileged enable attempts since the adapter was last observed ON. The global
     * [SETTING_DISABLED_PROFILES] workaround is only written after a bring-up attempt has
     * demonstrably failed (count > 0 on re-entry) — healthy devices never get the write.
     */
    @Volatile
    private var enableAttemptsSinceLastOn = 0

    /**
     * Some ROMs advertise A2DP Sink / AVRCP Controller in Bluetooth config but omit the
     * services from the package. Classic bring-up then hits BREDR_START_TIMEOUT.
     * When those components are absent, OR their profile bits into
     * [SETTING_DISABLED_PROFILES] via Shizuku (preferred) or root so Config skips them.
     *
     * Do not call preemptively: this writes a persistent global setting. Callers gate it
     * behind an observed bring-up failure (see [tryEnablePrivileged]).
     */
    fun ensureMissingBluetoothProfilesDisabled(context: Context): Boolean {
        val needed = missingProfileDisableMask(context)
        if (needed == 0L) return true

        val current = runCatching {
            Settings.Global.getLong(context.contentResolver, SETTING_DISABLED_PROFILES, 0L)
        }.getOrDefault(0L)
        if (current and needed == needed) return true

        val next = current or needed
        return putBluetoothDisabledProfiles(next)
    }

    private fun missingProfileDisableMask(context: Context): Long {
        var mask = 0L
        if (!hasBluetoothService(context, A2DP_SINK_SERVICE)) {
            mask = mask or MISSING_PROFILE_MASK_A2DP_SINK
        }
        if (!hasBluetoothService(context, AVRCP_CONTROLLER_SERVICE)) {
            mask = mask or MISSING_PROFILE_MASK_AVRCP_CONTROLLER
        }
        return mask
    }

    private fun hasBluetoothService(context: Context, className: String): Boolean =
        BLUETOOTH_STACK_PACKAGES.any { packageName ->
            runCatching {
                context.packageManager.getServiceInfo(
                    ComponentName(packageName, className),
                    0,
                )
                true
            }.getOrDefault(false)
        }

    private fun isProfileWorkaroundMarkedNeeded(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROFILE_WORKAROUND_NEEDED, false)

    private fun markProfileWorkaroundNeeded(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PROFILE_WORKAROUND_NEEDED, true).apply()
    }

    private fun clearProfileWorkaroundNeeded(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_PROFILE_WORKAROUND_NEEDED).apply()
    }

    /**
     * Health check for stale profile-disable bits. Ava only ever sets the A2DP Sink /
     * AVRCP Controller bits, and only because those service classes were missing from the
     * Bluetooth package. If the services are present now (ROM update, or an old Ava
     * version wrote the bits on a package-name false negative), the disable is stale:
     * clear exactly our bits, preserve everything else in the global value.
     *
     * Safe by construction: bits are only cleared when the corresponding service is
     * confirmed present, and if that judgement is ever wrong the failure-gated path in
     * [tryEnablePrivileged] re-applies and re-marks on the next bring-up failure.
     * Takes effect at the next Bluetooth stack start; no restart is forced here.
     *
     * @return true if a stale bit was cleared.
     */
    fun reconcileStaleProfileDisable(context: Context): Boolean {
        val current = runCatching {
            Settings.Global.getLong(context.contentResolver, SETTING_DISABLED_PROFILES, 0L)
        }.getOrDefault(0L)
        val ourBits = current and AVA_PROFILE_DISABLE_MASK
        if (ourBits == 0L) return false

        val stillMissing = missingProfileDisableMask(context)
        val staleBits = ourBits and stillMissing.inv()
        if (staleBits == 0L) return false

        val next = current and staleBits.inv()
        val cleared = putBluetoothDisabledProfiles(next)
        if (cleared) {
            Log.i(
                TAG,
                "Cleared stale profile-disable bits ${java.lang.Long.toBinaryString(staleBits)} " +
                    "($SETTING_DISABLED_PROFILES $current -> $next); services are present now",
            )
            if (next and AVA_PROFILE_DISABLE_MASK == 0L) {
                clearProfileWorkaroundNeeded(context)
            }
        }
        return cleared
    }

    private fun putBluetoothDisabledProfiles(value: Long): Boolean {
        val cmd = "settings put global $SETTING_DISABLED_PROFILES $value"
        if (ShizukuUtils.isShizukuPermissionGranted()) {
            val ok = ShizukuUtils.executeCommand(cmd).first == 0
            if (ok) {
                Log.i(TAG, "Set $SETTING_DISABLED_PROFILES=$value via Shizuku")
                return true
            }
        }
        if (RootUtils.isRootAvailable()) {
            val ok = runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() == 0
            }.getOrDefault(false)
            if (ok) {
                Log.i(TAG, "Set $SETTING_DISABLED_PROFILES=$value via root")
                return true
            }
        }
        return false
    }

    /**
     * Attempt a silent / privileged re-enable. Returns true if a privileged path was started
     * (caller should wait for [BluetoothAdapter.ACTION_STATE_CHANGED] → ON).
     */
    @SuppressLint("MissingPermission")
    fun tryEnablePrivileged(context: Context): Boolean {
        if (isEnabled(context)) {
            enableAttemptsSinceLastOn = 0
            return true
        }
        // Crown-class ROMs miss the A2DP Sink / AVRCP Controller services and hit
        // BREDR_START_TIMEOUT on bring-up. Apply the profile-disable workaround only
        // after a previous enable attempt failed to bring the adapter ON — never
        // preemptively on healthy devices. Once a failure has proven the device needs
        // it, remember that persistently so every later bring-up (including after app
        // restart or factory settings wipe of the global value) applies it up front
        // instead of failing once per session.
        val provenNeeded = isProfileWorkaroundMarkedNeeded(context)
        if (provenNeeded || enableAttemptsSinceLastOn > 0) {
            val hadMissingProfiles = missingProfileDisableMask(context) != 0L
            ensureMissingBluetoothProfilesDisabled(context)
            if (!provenNeeded && hadMissingProfiles) {
                markProfileWorkaroundNeeded(context)
            }
        }
        enableAttemptsSinceLastOn++

        if (RootUtils.isRootAvailable()) {
            Log.i(TAG, "Enabling Bluetooth via root")
            return RootUtils.enableBluetooth()
        }
        if (ShizukuUtils.isShizukuPermissionGranted()) {
            Log.i(TAG, "Enabling Bluetooth via Shizuku")
            return ShizukuUtils.enableBluetooth()
        }
        if (ScreenControlUtils.isDeviceOwner(context)) {
            val adapter = adapter(context) ?: return false
            return runCatching {
                @Suppress("DEPRECATION")
                val accepted = adapter.enable()
                Log.i(TAG, "Device Owner BluetoothAdapter.enable() accepted=$accepted")
                accepted
            }.getOrDefault(false)
        }
        return false
    }

    /**
     * Best-effort enable used by UI and service keep-alive.
     * @return true if already on or a privileged enable was accepted; false if the caller
     * must show [createRequestEnableIntent].
     */
    fun ensureEnabledOrNeedsUserPrompt(context: Context): Boolean {
        if (isEnabled(context)) {
            enableAttemptsSinceLastOn = 0
            return true
        }
        return tryEnablePrivileged(context)
    }
}
