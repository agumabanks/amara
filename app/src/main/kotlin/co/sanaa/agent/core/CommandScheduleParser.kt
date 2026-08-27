package co.sanaa.agent.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.json.JSONObject

enum class RecurrenceKind { INTERVAL, DAILY, WEEKDAYS }

data class ParsedSchedule(
    val taskText: String,
    val kind: RecurrenceKind,
    val intervalMinutes: Long? = null,
    val localTime: LocalTime? = null,
    val days: Set<DayOfWeek> = emptySet(),
) {
    fun rule(): String = when (kind) {
        RecurrenceKind.INTERVAL -> "every ${intervalMinutes}m"
        RecurrenceKind.DAILY -> "daily ${localTime}"
        RecurrenceKind.WEEKDAYS -> "weekdays ${localTime}"
    }
}

object CommandScheduleParser {
    private val interval = Regex("(?i)\\b(?:every|each)\\s+(?:(\\d+)\\s*)?(minute|minutes|hour|hours)\\b")
    private val everyMorning = Regex("(?i)\\b(?:every|each)\\s+morning(?:\\s+at)?\\s+(\\d{1,2})(?::(\\d{2}))?\\b")
    private val everyEvening = Regex("(?i)\\b(?:every|each)\\s+evening(?:\\s+at)?\\s+(\\d{1,2})(?::(\\d{2}))?\\b")
    private val timeEveryMorning = Regex("(?i)\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s+(?:every|each)\\s+morning\\b")
    private val timeEveryEvening = Regex("(?i)\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s+(?:every|each)\\s+evening\\b")
    private val everyDay = Regex("(?i)\\b(?:every|each)\\s+day(?:\\s+at)?\\s+(\\d{1,2})(?::(\\d{2}))?\\b")
    private val weekdays = Regex("(?i)\\b(?:every\\s+)?weekday(?:s)?(?:\\s+at)?\\s+(\\d{1,2})(?::(\\d{2}))?\\b")

    fun parse(command: String): ParsedSchedule? {
        interval.find(command)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: 1L
            val minutes = if (match.groupValues[2].lowercase().startsWith("hour")) amount * 60 else amount
            if (minutes < 15) return null // WorkManager cannot guarantee shorter periodic work.
            return ParsedSchedule(cleanTask(command, match.range), RecurrenceKind.INTERVAL, intervalMinutes = minutes.coerceAtMost(7 * 24 * 60))
        }
        fun timed(regex: Regex, kind: RecurrenceKind, defaultHour: Int? = null): ParsedSchedule? {
            val match = regex.find(command) ?: return null
            val hour = match.groupValues[1].toIntOrNull() ?: defaultHour ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return null
            val days = if (kind == RecurrenceKind.WEEKDAYS) setOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
            ) else emptySet()
            return ParsedSchedule(cleanTask(command, match.range), kind, localTime = LocalTime.of(hour, minute), days = days)
        }
        return timed(everyMorning, RecurrenceKind.DAILY, 7)
            ?: timed(everyEvening, RecurrenceKind.DAILY, 18)
            ?: timed(timeEveryMorning, RecurrenceKind.DAILY, 7)
            ?: timed(timeEveryEvening, RecurrenceKind.DAILY, 18)
            ?: timed(everyDay, RecurrenceKind.DAILY)
            ?: timed(weekdays, RecurrenceKind.WEEKDAYS)
    }

    private fun cleanTask(command: String, range: IntRange): String = command.removeRange(range)
        .replace(Regex("(?i)^\\s*(?:at|[-—,:])\\s*"), "")
        .replace(Regex("\\s+"), " ").trim(' ', '-', '—', ',', ':')
}

object ScheduleCalculator {
    fun nextRun(schedule: ParsedSchedule, afterEpochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val after = java.time.Instant.ofEpochMilli(afterEpochMillis).atZone(zone).toLocalDateTime()
        val next = when (schedule.kind) {
            RecurrenceKind.INTERVAL -> after.plusMinutes(schedule.intervalMinutes ?: 15)
            RecurrenceKind.DAILY -> nextTimed(after, schedule.localTime ?: LocalTime.of(7, 0), emptySet())
            RecurrenceKind.WEEKDAYS -> nextTimed(after, schedule.localTime ?: LocalTime.of(7, 0), schedule.days)
        }
        return next.atZone(zone).toInstant().toEpochMilli()
    }

    private fun nextTimed(after: LocalDateTime, time: LocalTime, days: Set<DayOfWeek>): LocalDateTime {
        var candidate = after.toLocalDate().atTime(time)
        if (!candidate.isAfter(after)) candidate = candidate.plusDays(1)
        while (days.isNotEmpty() && candidate.dayOfWeek !in days) candidate = candidate.plusDays(1)
        return candidate
    }

    fun delayMillis(nextEpochMillis: Long, nowEpochMillis: Long = System.currentTimeMillis()): Long =
        Duration.ofMillis((nextEpochMillis - nowEpochMillis).coerceAtLeast(0)).toMillis()
}

object ScheduleCodec {
    fun decode(json: String, taskText: String): ParsedSchedule {
        val value = JSONObject(json)
        val kind = RecurrenceKind.valueOf(value.getString("kind"))
        val daysArray = value.optJSONArray("days")
        val days = buildSet {
            if (daysArray != null) for (index in 0 until daysArray.length()) {
                runCatching { DayOfWeek.valueOf(daysArray.getString(index)) }.getOrNull()?.let(::add)
            }
        }
        return ParsedSchedule(
            taskText = taskText,
            kind = kind,
            intervalMinutes = value.optLong("intervalMinutes").takeIf { value.has("intervalMinutes") },
            localTime = value.optString("localTime").takeIf(String::isNotBlank)?.let(LocalTime::parse),
            days = days,
        )
    }
}
