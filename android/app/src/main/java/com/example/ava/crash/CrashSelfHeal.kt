package com.example.ava.crash

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.ava.platform.PlatformCapabilities

/**
 * Brings Ava back after a whole-process death it did not ask for.
 *
 * Native crashes (WebView / GPU / signal) take the process down below anything
 * Java can catch. Recovery is arranged with the OS in advance:
 *
 *  - [CrashGuardService], a do-nothing START_STICKY service. Android restarts
 *    a sticky service after its process dies; that restart relaunches the UI.
 *  - a heartbeat alarm held by the system, because some OEMs restart sticky
 *    background services lazily. Delivery recreates the process.
 *
 * Both funnel into [maybeRelaunch]. Gates: the setting is on, Ava was on
 * screen when it died, no Activity is up yet, and the last self-heal was
 * over two minutes ago (no startup crash loop).
 *
 * Android 10+ only honors the background Activity start with the
 * "Display over other apps" grant.
 */
object CrashSelfHeal {
    private const val TAG = "CrashSelfHeal"
    private const val PREFS = "ava_crash_self_heal"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WAS_FOREGROUND = "was_foreground"
    private const val KEY_LAST_SELF_HEAL = "last_self_heal"
    private const val HEARTBEAT_MS = 3 * 60_000L
    private const val THROTTLE_MS = 120_000L

    @Volatile
    var activityResumed: Boolean = false

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun applyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()
        if (enabled) {
            // Only arm from the foreground. Starting the guard service from
            // Application on a sticky-restart process can swallow the null
            // intent that maybeRelaunch depends on.
            if (activityResumed) {
                setWasForeground(context, true)
                arm(context)
            }
        } else {
            disarm(context)
        }
    }

    fun arm(context: Context) {
        if (!isEnabled(context)) {
            disarm(context)
            return
        }
        CrashGuardService.start(context)
        scheduleHeartbeat(context)
    }

    fun disarm(context: Context) {
        cancelHeartbeat(context)
        CrashGuardService.stop(context)
    }

    /** Home / another app / a deliberate kill: do not yank the screen back. */
    fun noteDeliberateExit(context: Context) {
        setWasForeground(context, false)
        disarm(context)
    }

    fun setWasForeground(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAS_FOREGROUND, value).commit()
    }

    fun maybeRelaunch(context: Context) {
        if (activityResumed) return
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        if (!prefs.getBoolean(KEY_WAS_FOREGROUND, false)) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_SELF_HEAL, 0L)
        if (now - last < THROTTLE_MS) return
        prefs.edit().putLong(KEY_LAST_SELF_HEAL, now).commit()
        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (!PlatformCapabilities.canDrawOverlays(context)) {
                Log.w(TAG, "relaunching without the overlay grant; Android may drop it")
            }
            context.startActivity(launch)
            Log.i(TAG, "relaunched Ava after a crash")
        } catch (e: Exception) {
            Log.w(TAG, "crash self-heal failed: $e")
        }
    }

    fun scheduleHeartbeat(context: Context) {
        try {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + HEARTBEAT_MS
            val pending = heartbeatIntent(context)
            // setAndAllowWhileIdle is API 23+. Calling it on 21–22 is a
            // NoSuchMethodError (not Exception), which tore down MainActivity
            // onResume on Android 5.1.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarms.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pending,
                )
            } else {
                alarms.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pending,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "heartbeat schedule failed: $e")
        }
    }

    private fun cancelHeartbeat(context: Context) {
        try {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarms.cancel(heartbeatIntent(context))
        } catch (_: Exception) {
        }
    }

    private fun heartbeatIntent(context: Context): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CrashHeartbeatReceiver::class.java),
            flags,
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Heartbeat landing point. Delivery recreates the process when a crash killed
 * it. Re-arms only while the setting is on and Ava was last seen on screen.
 */
class CrashHeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!CrashSelfHeal.isEnabled(context)) return
        val prefs = context.applicationContext.getSharedPreferences(
            "ava_crash_self_heal",
            Context.MODE_PRIVATE,
        )
        if (!prefs.getBoolean("was_foreground", false)) return
        CrashSelfHeal.scheduleHeartbeat(context)
        CrashSelfHeal.maybeRelaunch(context)
    }
}
