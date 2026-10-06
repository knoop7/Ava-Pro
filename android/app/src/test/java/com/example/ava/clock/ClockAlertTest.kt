package com.example.ava.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ClockAlertTest {
    @Test
    fun nextFireMovesToTomorrowWhenTheClockTimeHasPassed() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.SEPTEMBER, 21, 8, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val from = cal.timeInMillis
        val next = ClockAlert.nextFireMillis(7, 0, from)
        val out = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(22, out.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, out.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, out.get(Calendar.MINUTE))
    }

    @Test
    fun nextFireStaysTodayWhenTheClockTimeIsStillAhead() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.SEPTEMBER, 21, 6, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val next = ClockAlert.nextFireMillis(7, 0, cal.timeInMillis)
        val out = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(21, out.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, out.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun equalClockTimeIsAlreadyPassed() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.SEPTEMBER, 21, 7, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val next = ClockAlert.nextFireMillis(7, 0, cal.timeInMillis)
        assertTrue(next > cal.timeInMillis)
    }

    @Test
    fun jsonRoundTripKeepsKindAndTime() {
        val raw = ClockAlertStore.toJson(
            listOf(
                ClockAlert("a", ClockAlert.Kind.ALARM, 7, 0, "叫醒", true, 1L),
                ClockAlert("b", ClockAlert.Kind.REMINDER, 14, 0, "吃药", true, 2L),
            ),
        )
        val items = ClockAlertStore.parse(raw)
        assertEquals(2, items.size)
        assertEquals(ClockAlert.Kind.ALARM, items[0].kind)
        assertEquals("吃药", items[1].label)
        assertEquals("07:00", items[0].timeText())
    }

    @Test
    fun badJsonIsEmpty() {
        assertTrue(ClockAlertStore.parse("not-json").isEmpty())
        assertTrue(ClockAlertStore.parse("").isEmpty())
    }

    @Test
    fun emptyStoreHasNoHaSlots() {
        assertTrue(ClockAlertSensor.ordered(emptyList()).isEmpty())
        assertEquals("", ClockAlertSensor.signature(emptyList()))
        assertEquals("task_event", ClockAlertSensor.objectId(0))
        assertEquals("task_event_1", ClockAlertSensor.objectId(1))
    }

    @Test
    fun occupiedSlotsKeepClockTimeAndStableIds() {
        val item = ClockAlert("a", ClockAlert.Kind.ALARM, 7, 0, "", true, 1L)
        val ordered = ClockAlertSensor.ordered(listOf(item))
        assertEquals(1, ordered.size)
        assertEquals("07:00", ordered[0].timeText())
        assertEquals("a:a", ClockAlertSensor.signature(listOf(item)))
    }

    @Test
    fun extraAlarmsCapAtTwentyAndKeepOrder() {
        val items = (0 until 21).map { i ->
            ClockAlert("$i", ClockAlert.Kind.ALARM, 7, i % 60, "", true, (i + 1).toLong())
        }
        val ordered = ClockAlertSensor.ordered(items)
        assertEquals(20, ordered.size)
        assertEquals("07:00", ordered[0].timeText())
        assertEquals("07:19", ordered[19].timeText())
        assertTrue(ClockAlertSensor.signature(items).contains("19:a"))
        assertFalse(ClockAlertSensor.signature(items).contains("20:a"))
    }

    @Test
    fun reminderKindChangesSignatureNotTime() {
        val item = ClockAlert("b", ClockAlert.Kind.REMINDER, 14, 0, "medicine", true, 2L)
        val ordered = ClockAlertSensor.ordered(listOf(item))
        assertEquals("14:00", ordered[0].timeText())
        assertEquals("b:r", ClockAlertSensor.signature(listOf(item)))
    }

    @Test
    fun haDateTimeStateIsNextFireEpoch() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.SEPTEMBER, 21, 10, 6, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val item = ClockAlert("a", ClockAlert.Kind.ALARM, 10, 6, "", true, cal.timeInMillis)
        assertEquals(cal.timeInMillis / 1000L, ClockAlertSensor.epochSeconds(item))
    }

    @Test
    fun atMillisDaysFromTodaySkipsAhead() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.SEPTEMBER, 21, 22, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val next = ClockAlert.atMillis(7, 0, daysFromToday = 2, fromMillis = cal.timeInMillis)
        val out = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(23, out.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.SEPTEMBER, out.get(Calendar.MONTH))
        assertEquals(7, out.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, out.get(Calendar.MINUTE))
    }

    @Test
    fun atMillisCalendarDateKeepsThatDay() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.SEPTEMBER, 21, 22, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val next = ClockAlert.atMillis(
            8, 30,
            year = 2026, month = 9, day = 25,
            fromMillis = cal.timeInMillis,
        )
        val out = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(2026, out.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, out.get(Calendar.MONTH))
        assertEquals(25, out.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, out.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, out.get(Calendar.MINUTE))
    }

    @Test
    fun atMillisWithoutDateFallsBackToNextClockTime() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.SEPTEMBER, 21, 8, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val next = ClockAlert.atMillis(7, 0, fromMillis = cal.timeInMillis)
        val out = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(22, out.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, out.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun dateTimeTextShowsDateWhenNotToday() {
        val today = Calendar.getInstance()
        today.set(2026, Calendar.SEPTEMBER, 21, 8, 0, 0)
        today.set(Calendar.MILLISECOND, 0)
        val later = Calendar.getInstance()
        later.set(2026, Calendar.SEPTEMBER, 23, 7, 0, 0)
        later.set(Calendar.MILLISECOND, 0)
        val item = ClockAlert("a", ClockAlert.Kind.ALARM, 7, 0, "", true, later.timeInMillis)
        assertEquals("2026-09-23", item.dateText(today.timeInMillis))
        assertEquals(null, item.dateText(later.timeInMillis))
        assertEquals("2026-09-23 07:00", item.dateTimeText(today.timeInMillis))
        assertEquals("07:00", item.dateTimeText(later.timeInMillis))
    }

    @Test
    fun snoozeShowsFiveMinutesLaterNotTheOldClockTime() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.SEPTEMBER, 21, 8, 0, 40)
        cal.set(Calendar.MILLISECOND, 0)
        val item = ClockAlert("a", ClockAlert.Kind.ALARM, 8, 0, "起床", true, cal.timeInMillis)
        val snoozed = item.snoozed(cal.timeInMillis)
        assertEquals("08:05", snoozed.timeText())
        assertEquals(cal.timeInMillis + ClockAlert.SNOOZE_MS, snoozed.nextAtMillis)
        assertEquals("起床", snoozed.label)
        assertEquals(item.id, snoozed.id)
    }

    @Test
    fun snoozeAcrossMidnightShowsTheNextDay() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.SEPTEMBER, 21, 23, 58, 10)
        cal.set(Calendar.MILLISECOND, 0)
        val snoozed = ClockAlert("a", ClockAlert.Kind.ALARM, 23, 58, "", true, cal.timeInMillis)
            .snoozed(cal.timeInMillis)
        assertEquals("00:03", snoozed.timeText())
        assertEquals("2026-09-22", snoozed.dateText(cal.timeInMillis))
        assertEquals(cal.timeInMillis + ClockAlert.SNOOZE_MS, snoozed.nextAtMillis)
    }

    @Test
    fun disarmKeepsTheClockFace() {
        val item = ClockAlert("a", ClockAlert.Kind.ALARM, 8, 5, "起床", true, 1_700_000_000_000L)
        val kept = item.disarmed()
        assertEquals("08:05", kept.timeText())
        assertEquals(0L, kept.nextAtMillis)
        assertEquals("起床", kept.label)
        assertTrue(kept.enabled)
        assertEquals(item.id, kept.id)
    }
}
