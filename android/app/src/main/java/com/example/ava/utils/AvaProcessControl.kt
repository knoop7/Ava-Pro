package com.example.ava.utils

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.ava.crash.AvaIncidentLog
import com.example.ava.crash.CrashSelfHeal
import com.example.ava.receiver.DeviceAdminReceiver
import com.example.ava.services.VoiceSatelliteService

/**
 * On-device "restart / exit / pin" controls for Ava itself.
 *
 * Killing our own process needs no privilege. The real pain point is that a
 * device-admin app is usually exempt from the ROM's "clear recents / task
 * killer", so users had to reach for `adb shell am force-stop`. These helpers
 * give an in-app escape:
 *
 *  - No privilege: tear down the respawn hooks (user-stopped flag, sticky
 *    foreground service, crash self-heal) and then kill the process. Best
 *    effort — a few OEM ROMs still relaunch a sticky service.
 *  - Root / Shizuku: a real `am force-stop`, identical to adb, never respawns.
 *
 * "Pin to wall" is Lock Task: a true, non-escapable kiosk only when Ava is a
 * device owner (Root/Shizuku can provision that); otherwise it falls back to
 * the escapable system screen-pinning.
 */
object AvaProcessControl {
    private const val TAG = "AvaProcessControl"
    private const val PREFS = "ava_prefs"
    private const val KEY_USER_STOPPED = "service_user_stopped"
    private const val RESTART_REQUEST_CODE = 0xA5A
    private const val RESTART_DELAY_MS = 400L

    enum class Tier { ROOT, SHIZUKU, DEVICE_OWNER, DEVICE_ADMIN, NONE }

    /**
     * Highest privilege currently usable. May block on the root probe, so call
     * this off the main thread.
     */
    fun currentTier(context: Context): Tier = when {
        RootUtils.isRootAvailable() -> Tier.ROOT
        ShizukuUtils.isShizukuPermissionGranted() -> Tier.SHIZUKU
        ScreenControlUtils.isDeviceOwner(context) -> Tier.DEVICE_OWNER
        ScreenControlUtils.isDeviceAdminActive(context) -> Tier.DEVICE_ADMIN
        else -> Tier.NONE
    }

    /** A real force-stop (stays dead on every ROM) is only possible with Root/Shizuku. */
    fun canForceStop(): Boolean =
        RootUtils.isRootAvailable() || ShizukuUtils.isShizukuPermissionGranted()

    /**
     * Close Ava and bring it straight back. Uniform across tiers: schedule the
     * relaunch with the system, then kill the process. The post-death Activity
     * start relies on the "display over other apps" grant, the same hook the
     * crash self-heal already depends on.
     */
    fun restartAva(context: Context, reason: String = "user", recordIncident: Boolean = true) {
        val app = context.applicationContext
        setUserStopped(app, false)
        if (recordIncident) {
            AvaIncidentLog.record(app, AvaIncidentLog.KIND_RESTART_USER, reason)
        }
        // We arrange our own relaunch; stand the crash watchdog down so it does not double-fire.
        CrashSelfHeal.noteDeliberateExit(app)

        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (launch == null) {
            Log.w(TAG, "no launch intent; cannot restart")
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT
        }
        val pending = PendingIntent.getActivity(app, RESTART_REQUEST_CODE, launch, flags)
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarm?.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
            pending,
        )
        killSelf()
    }

    /**
     * Exit Ava for good. Root/Shizuku do a true `am force-stop`; without either,
     * remove the sticky-restart source first, then kill (best effort).
     */
    fun exitAva(context: Context, reason: String = "user") {
        val app = context.applicationContext
        setUserStopped(app, true)
        AvaIncidentLog.record(app, AvaIncidentLog.KIND_EXIT_USER, reason)
        CrashSelfHeal.noteDeliberateExit(app)
        val pkg = app.packageName
        when {
            RootUtils.isRootAvailable() -> {
                forceStopViaRoot(pkg)
                return
            }
            ShizukuUtils.isShizukuPermissionGranted() -> {
                Thread { runCatching { ShizukuUtils.executeCommand("am force-stop $pkg") } }.start()
                return
            }
        }
        // No privilege: an explicitly stopped service is not sticky-restarted, so drop it first.
        stopStickyServices(app)
        killSelf()
    }

    fun isPinned(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            am.isInLockTaskMode
        }
    }

    /**
     * Enter Lock Task. Device owner → non-escapable kiosk (self is whitelisted);
     * otherwise the system screen-pinning, which the user can leave.
     * Must be called on the main thread with a live Activity.
     */
    fun pinToWall(activity: Activity): Boolean {
        val app = activity.applicationContext
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm != null && dpm.isDeviceOwnerApp(app.packageName)) {
            runCatching {
                dpm.setLockTaskPackages(
                    ComponentName(app, DeviceAdminReceiver::class.java),
                    arrayOf(app.packageName),
                )
            }.onFailure { Log.w(TAG, "setLockTaskPackages failed", it) }
        }
        return runCatching { activity.startLockTask(); true }
            .onFailure { Log.w(TAG, "startLockTask failed", it) }
            .getOrDefault(false)
    }

    fun unpinFromWall(activity: Activity): Boolean =
        runCatching { activity.stopLockTask(); true }
            .onFailure { Log.w(TAG, "stopLockTask failed", it) }
            .getOrDefault(false)

    /**
     * Promote Ava to device owner via Root/Shizuku so pinning becomes a true
     * kiosk. Only succeeds on a device with no accounts/other users. Blocking —
     * call off the main thread.
     */
    fun tryBecomeDeviceOwner(context: Context): Boolean {
        val app = context.applicationContext
        if (ScreenControlUtils.isDeviceOwner(app)) return true
        val component = ComponentName(app, DeviceAdminReceiver::class.java).flattenToString()
        val cmd = "dpm set-device-owner $component"
        val ran = when {
            RootUtils.isRootAvailable() -> runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() == 0
            }.getOrDefault(false)
            ShizukuUtils.isShizukuPermissionGranted() -> ShizukuUtils.executeCommand(cmd).first == 0
            else -> false
        }
        return ran && ScreenControlUtils.isDeviceOwner(app)
    }

    private fun stopStickyServices(app: Context) {
        runCatching {
            app.stopService(Intent(app, VoiceSatelliteService::class.java))
        }.onFailure { Log.w(TAG, "stopService failed", it) }
    }

    private fun forceStopViaRoot(pkg: String) {
        // The command kills us mid-exec, so run detached to avoid blocking the caller.
        Thread {
            runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $pkg")) }
        }.start()
    }

    private fun setUserStopped(app: Context, value: Boolean) {
        runCatching {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USER_STOPPED, value).commit()
        }
    }

    private fun killSelf() {
        Process.killProcess(Process.myPid())
    }
}
