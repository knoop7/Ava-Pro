package com.example.ava.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.ava.platform.PlatformCapabilities

object ClockAlertScheduler {
    private const val TAG = "ClockAlertSched"
    const val ACTION_FIRE = "com.example.ava.action.CLOCK_ALERT_FIRE"
    const val EXTRA_ID = "clock_alert_id"

    fun scheduleAll(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val items = ClockAlertStore.list(app)
        items.forEach { item ->
            val pending = pending(app, item.id)
            if (!item.enabled || item.nextAtMillis <= 0L) {
                alarm.cancel(pending)
                return@forEach
            }
            arm(alarm, item.nextAtMillis, pending)
        }
    }

    fun restore(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        ClockAlertStore.list(app).forEach { item ->
            if (!item.enabled) return@forEach
            if (item.nextAtMillis in 1 until now) {
                ClockAlertStore.remove(app, item.id)
            }
        }
        scheduleAll(app)
    }

    fun cancel(context: Context, id: String) {
        val app = context.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarm.cancel(pending(app, id))
    }

    private fun arm(alarm: AlarmManager, at: Long, pending: PendingIntent) {
        try {
            if (PlatformCapabilities.canScheduleExactAlarmsForSdk(Build.VERSION.SDK_INT) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarm.canScheduleExactAlarms() else true
                }
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                } else {
                    alarm.setExact(AlarmManager.RTC_WAKEUP, at, pending)
                }
            } else {
                alarm.setAndAllowWhileIdleCompat(at, pending)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to arm clock alert", e)
            alarm.setAndAllowWhileIdleCompat(at, pending)
        }
    }

    private fun AlarmManager.setAndAllowWhileIdleCompat(at: Long, pending: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            set(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun pending(context: Context, id: String): PendingIntent {
        val intent = Intent(context, ClockAlertReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PlatformCapabilities.pendingIntentFlags(),
        )
    }
}
