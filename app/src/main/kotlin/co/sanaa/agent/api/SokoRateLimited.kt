package co.sanaa.agent.api

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A read was refused before any catalogue data could authorize an action. */
class SokoRateLimited(val retryAfterMs: Long) : Exception("Soko catalogue rate limited; retry after ${retryAfterMs / 1000} seconds") {
    companion object {
        internal fun delayMs(header: String?, now: Long = System.currentTimeMillis()): Long {
            val seconds = header?.trim()?.toLongOrNull()
            val requested = if (seconds != null) seconds.coerceIn(0, 86400) * 1000 else
                runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now }.getOrNull()
            return (requested ?: 60_000L).coerceIn(60_000L, 86_400_000L)
        }
    }
}
