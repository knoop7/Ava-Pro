package com.example.ava.clock

/**
 * One local clock-time item. It fires once. Stopping the ring removes it
 * when another item is still on the list. The last one stays, disarmed.
 * Snooze keeps it for five more minutes.
 */
data class ClockAlert(
    val id: String,
    val kind: Kind,
    val hour: Int,
    val minute: Int,
    val label: String,
    val enabled: Boolean,
    val nextAtMillis: Long,
) {
    enum class Kind { ALARM, REMINDER }

    fun timeText(): String = formatClockTime(hour, minute)

    /**
     * Five minutes from [now]. The overlay prints [hour] and [minute], so those
     * move with [nextAtMillis]. The delay itself stays exact, including seconds.
     */
    fun snoozed(now: Long): ClockAlert {
        val at = snoozeMillis(now)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        return copy(
            hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            minute = cal.get(java.util.Calendar.MINUTE),
            nextAtMillis = at,
        )
    }

    /** Same clock face, no longer armed. Used when close must not delete the last item. */
    fun disarmed(): ClockAlert = copy(nextAtMillis = 0L)

    fun dateText(now: Long = System.currentTimeMillis()): String? {
        if (nextAtMillis <= 0L) return null
        val at = java.util.Calendar.getInstance().apply { timeInMillis = nextAtMillis }
        val today = java.util.Calendar.getInstance().apply { timeInMillis = now }
        if (at.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
            at.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
        ) {
            return null
        }
        return "%d-%02d-%02d".format(
            at.get(java.util.Calendar.YEAR),
            at.get(java.util.Calendar.MONTH) + 1,
            at.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    fun dateTimeText(now: Long = System.currentTimeMillis()): String {
        val date = dateText(now) ?: return timeText()
        return "$date ${timeText()}"
    }

    companion object {
        const val SNOOZE_MS = 5L * 60L * 1000L
        const val DEFAULT_SOUND = "asset:///sounds/timer_finished.wav"

        fun formatClockTime(hour: Int, minute: Int): String =
            "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

        /**
         * Next [hour]:[minute] strictly after [fromMillis]. Equal to [fromMillis]
         * is treated as already passed, so a just-fired alarm moves to tomorrow.
         */
        fun nextFireMillis(hour: Int, minute: Int, fromMillis: Long): Long {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = fromMillis
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            cal.set(java.util.Calendar.MINUTE, minute.coerceIn(0, 59))
            if (cal.timeInMillis <= fromMillis) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        /**
         * Any local date+time the user named. [month] is 1-12.
         * [daysFromToday] is used when no calendar date is given (0=today, 1=tomorrow, 2=day after).
         * Omit date and days for the next clock time today/tomorrow.
         */
        fun atMillis(
            hour: Int,
            minute: Int,
            year: Int? = null,
            month: Int? = null,
            day: Int? = null,
            daysFromToday: Int? = null,
            fromMillis: Long,
        ): Long {
            val h = hour.coerceIn(0, 23)
            val m = minute.coerceIn(0, 59)
            if (year == null && month == null && day == null && daysFromToday == null) {
                return nextFireMillis(h, m, fromMillis)
            }
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = fromMillis
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            if (daysFromToday != null && year == null && month == null && day == null) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, daysFromToday.coerceIn(0, 366))
            } else {
                if (year != null) cal.set(java.util.Calendar.YEAR, year)
                if (month != null) cal.set(java.util.Calendar.MONTH, (month - 1).coerceIn(0, 11))
                if (day != null) cal.set(java.util.Calendar.DAY_OF_MONTH, day.coerceIn(1, 31))
            }
            cal.set(java.util.Calendar.HOUR_OF_DAY, h)
            cal.set(java.util.Calendar.MINUTE, m)
            return cal.timeInMillis
        }

        fun snoozeMillis(fromMillis: Long): Long = fromMillis + SNOOZE_MS
    }
}
