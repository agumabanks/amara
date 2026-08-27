package co.sanaa.agent.core

import java.time.LocalTime

data class QuietHoursPolicy(val start: LocalTime, val end: LocalTime) {
    fun contains(time: LocalTime): Boolean = when {
        start == end -> false
        start < end -> time >= start && time < end
        else -> time >= start || time < end
    }

    companion object {
        fun parse(start: String, end: String): QuietHoursPolicy? = runCatching {
            QuietHoursPolicy(LocalTime.parse(start), LocalTime.parse(end))
        }.getOrNull()
    }
}
