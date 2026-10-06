package com.example.ava.clock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Disk list of local alarms and reminders. The overlay and AlarmManager
 * both read from here; voice writes through [upsert] / [remove].
 */
object ClockAlertStore {
    private const val PREFS = "ava_clock_alerts"
    private const val KEY = "items"
    private const val KEY_SOUND = "sound_uri"
    const val MAX = ClockAlertSensor.SLOT_COUNT
    const val DEFAULT_SOUND = ClockAlert.DEFAULT_SOUND
    private val lock = Any()

    fun list(context: Context): List<ClockAlert> = synchronized(lock) {
        parse(prefs(context).getString(KEY, "[]").orEmpty())
    }

    fun get(context: Context, id: String): ClockAlert? =
        list(context).firstOrNull { it.id == id }

    fun soonest(context: Context): ClockAlert? =
        list(context)
            .filter { it.enabled && it.nextAtMillis > 0L }
            .minByOrNull { it.nextAtMillis }

    fun upsert(context: Context, item: ClockAlert): ClockAlert {
        val next = item.copy(
            hour = item.hour.coerceIn(0, 23),
            minute = item.minute.coerceIn(0, 59),
            nextAtMillis = if (item.enabled) {
                if (item.nextAtMillis > System.currentTimeMillis()) item.nextAtMillis
                else ClockAlert.nextFireMillis(item.hour, item.minute, System.currentTimeMillis())
            } else 0L,
        )
        synchronized(lock) {
            val items = parse(prefs(context).getString(KEY, "[]").orEmpty())
                .filterNot { it.id == next.id } + next
            write(context, items)
        }
        ClockAlertScheduler.scheduleAll(context)
        com.example.ava.services.ClockAlertOverlayService.sync(context)
        ClockAlertSensor.publish(context)
        return next
    }

    fun enabledCount(context: Context): Int = list(context).count { it.enabled }

    fun soundUri(context: Context): String =
        prefs(context).getString(KEY_SOUND, DEFAULT_SOUND) ?: DEFAULT_SOUND

    fun setSoundUri(context: Context, uri: String) {
        prefs(context).edit().putString(KEY_SOUND, uri).apply()
    }

    fun add(
        context: Context,
        kind: ClockAlert.Kind,
        hour: Int,
        minute: Int,
        label: String,
        now: Long = System.currentTimeMillis(),
        atMillis: Long? = null,
    ): ClockAlert? {
        if (enabledCount(context) >= MAX) return null
        val item = ClockAlert(
            id = UUID.randomUUID().toString(),
            kind = kind,
            hour = hour.coerceIn(0, 23),
            minute = minute.coerceIn(0, 59),
            label = label.trim(),
            enabled = true,
            nextAtMillis = atMillis?.takeIf { it > 0L } ?: ClockAlert.nextFireMillis(hour, minute, now),
        )
        return upsert(context, item)
    }

    fun reschedule(context: Context, id: String, hour: Int, minute: Int): ClockAlert? {
        val current = get(context, id) ?: return null
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = if (current.nextAtMillis > now) current.nextAtMillis else now
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        cal.set(java.util.Calendar.MINUTE, minute.coerceIn(0, 59))
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val at = if (cal.timeInMillis > now) cal.timeInMillis
        else ClockAlert.nextFireMillis(hour, minute, now)
        return upsert(
            context,
            current.copy(
                hour = hour,
                minute = minute,
                nextAtMillis = at,
            ),
        )
    }

    fun rescheduleTo(context: Context, id: String, atMillis: Long): ClockAlert? {
        val current = get(context, id) ?: return null
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = atMillis
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return upsert(
            context,
            current.copy(
                hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                minute = cal.get(java.util.Calendar.MINUTE),
                nextAtMillis = cal.timeInMillis,
            ),
        )
    }

    fun snooze(context: Context, id: String, now: Long = System.currentTimeMillis()): ClockAlert? {
        val current = get(context, id) ?: return null
        return upsert(context, current.snoozed(now))
    }

    /**
     * The ring was delivered and the user stopped it. Drop the item so the
     * overlay does not keep it for tomorrow. Snooze does not come through here.
     * The last remaining item is [disarm]ed instead of removed.
     */
    fun afterFire(context: Context, id: String) {
        if (get(context, id) == null) return
        remove(context, id)
    }

    /**
     * Leave the item on the overlay and drop its alarm. [upsert] would turn a
     * past [ClockAlert.nextAtMillis] into the next clock time, so this writes
     * nextAt = 0 directly. [ClockAlertScheduler.scheduleAll] then cancels it.
     */
    fun disarm(context: Context, id: String) {
        val current = get(context, id) ?: return
        synchronized(lock) {
            val items = parse(prefs(context).getString(KEY, "[]").orEmpty())
                .map { if (it.id == id) it.disarmed() else it }
            write(context, items)
        }
        ClockAlertScheduler.scheduleAll(context)
        com.example.ava.services.ClockAlertOverlayService.sync(context)
        ClockAlertSensor.publish(context)
    }

    fun remove(context: Context, id: String) {
        synchronized(lock) {
            val items = parse(prefs(context).getString(KEY, "[]").orEmpty()).filterNot { it.id == id }
            write(context, items)
        }
        ClockAlertScheduler.scheduleAll(context)
        com.example.ava.services.ClockAlertOverlayService.sync(context)
        ClockAlertSensor.publish(context)
    }

    internal fun parse(raw: String): List<ClockAlert> {
        val arr = try {
            JSONArray(raw.ifBlank { "[]" })
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<ClockAlert>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isEmpty()) continue
            val kind = when (o.optString("kind")) {
                "reminder" -> ClockAlert.Kind.REMINDER
                else -> ClockAlert.Kind.ALARM
            }
            out.add(
                ClockAlert(
                    id = id,
                    kind = kind,
                    hour = o.optInt("hour").coerceIn(0, 23),
                    minute = o.optInt("minute").coerceIn(0, 59),
                    label = o.optString("label"),
                    enabled = o.optBoolean("enabled", true),
                    nextAtMillis = o.optLong("nextAt", 0L),
                ),
            )
        }
        return out
    }

    internal fun toJson(items: List<ClockAlert>): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("kind", if (item.kind == ClockAlert.Kind.REMINDER) "reminder" else "alarm")
                    .put("hour", item.hour)
                    .put("minute", item.minute)
                    .put("label", item.label)
                    .put("enabled", item.enabled)
                    .put("nextAt", item.nextAtMillis),
            )
        }
        return arr.toString()
    }

    private fun write(context: Context, items: List<ClockAlert>) {
        prefs(context).edit().putString(KEY, toJson(items)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
