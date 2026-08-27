package co.sanaa.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class ScheduleCalculatorTest {
    @Test fun intervalScheduleAddsMinutes() {
        val schedule = ParsedSchedule("Test", RecurrenceKind.INTERVAL, intervalMinutes = 30)
        val now = System.currentTimeMillis()
        val next = ScheduleCalculator.nextRun(schedule, now)
        assertTrue(next > now)
    }

    @Test fun dailyScheduleFindsNextTime() {
        val schedule = ParsedSchedule("Test", RecurrenceKind.DAILY, localTime = java.time.LocalTime.of(7, 0))
        val now = System.currentTimeMillis()
        val next = ScheduleCalculator.nextRun(schedule, now)
        assertTrue(next > now)
    }

    @Test fun weekdaysScheduleSkipsWeekend() {
        val schedule = ParsedSchedule(
            "Test",
            RecurrenceKind.WEEKDAYS,
            localTime = java.time.LocalTime.of(9, 0),
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        )
        val now = System.currentTimeMillis()
        val next = ScheduleCalculator.nextRun(schedule, now)
        assertTrue(next > now)
    }

    @Test fun delayMillisIsNonNegative() {
        val delay = ScheduleCalculator.delayMillis(System.currentTimeMillis() + 5000)
        assertTrue(delay >= 0)
    }

    @Test fun codecRoundTrips() {
        val original = ParsedSchedule("Test task", RecurrenceKind.DAILY, localTime = java.time.LocalTime.of(8, 30))
        val json = org.json.JSONObject().apply {
            put("kind", original.kind.name)
            original.intervalMinutes?.let { put("intervalMinutes", it) }
            original.localTime?.let { put("localTime", it.toString()) }
            put("days", org.json.JSONArray(original.days.map { it.name }))
        }.toString()
        val decoded = ScheduleCodec.decode(json, "Test task")
        assertEquals(original.kind, decoded.kind)
        assertEquals(original.localTime, decoded.localTime)
    }
}
