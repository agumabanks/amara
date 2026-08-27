package co.sanaa.agent.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandScheduleParserTest {
    @Test fun parsesHourlyTaskAndRemovesOnlyTheSchedulePhrase() {
        val schedule = CommandScheduleParser.parse("Every hour send a random Soko service to Sanaa Office Airtel")!!
        assertEquals(RecurrenceKind.INTERVAL, schedule.kind)
        assertEquals(60L, schedule.intervalMinutes)
        assertEquals("send a random Soko service to Sanaa Office Airtel", schedule.taskText)
    }

    @Test fun parsesTimeBeforeEveryMorning() {
        val schedule = CommandScheduleParser.parse("At 07:00 every morning send the top product to Sanaa Office Airtel")!!
        assertEquals(7, schedule.localTime!!.hour)
        assertEquals("send the top product to Sanaa Office Airtel", schedule.taskText)
    }

    @Test fun rejectsIntervalsAndroidCannotScheduleReliably() {
        assertNull(CommandScheduleParser.parse("Every 5 minutes send an update"))
    }

    @Test fun skipsWeekends() {
        val zone = ZoneId.of("UTC")
        val friday = LocalDateTime.of(2026, 8, 21, 19, 0).atZone(zone).toInstant().toEpochMilli()
        val schedule = CommandScheduleParser.parse("Weekdays at 07:00 check Soko bookings")!!
        val next = java.time.Instant.ofEpochMilli(ScheduleCalculator.nextRun(schedule, friday, zone)).atZone(zone)
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(7, next.hour)
    }

    @Test fun scheduleJsonRoundTrips() {
        val schedule = CommandScheduleParser.parse("Every evening at 18:00 send a follow-up")!!
        val json = org.json.JSONObject().apply {
            put("kind", schedule.kind.name)
            put("localTime", schedule.localTime.toString())
            put("days", org.json.JSONArray())
        }.toString()
        val decoded = ScheduleCodec.decode(json, schedule.taskText)
        assertEquals(schedule, decoded)
        assertTrue(decoded.rule().contains("18:00"))
    }
}
