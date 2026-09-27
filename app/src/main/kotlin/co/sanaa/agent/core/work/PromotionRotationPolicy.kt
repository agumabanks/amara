package co.sanaa.agent.core.work

import kotlin.math.ceil

data class PromotionHistoryWindow(val sinceMillis: Long, val limit: Int, val catalogueSize: Int)

/** Sizes durable rotation memory from the actual shop catalogue and posting cadence. */
object PromotionRotationPolicy {
    fun historyWindow(
        catalogueSize: Int,
        intervalMinutes: Long,
        dailyCap: Int,
        now: Long = System.currentTimeMillis(),
    ): PromotionHistoryWindow {
        val size = catalogueSize.coerceAtLeast(1)
        val cadenceCapacity = (1_440L / intervalMinutes.coerceIn(10, 1_440)).toInt().coerceAtLeast(1)
        val postsPerDay = minOf(cadenceCapacity, dailyCap.coerceAtLeast(1))
        // Three complete catalogue cycles let the selector distinguish genuinely
        // old items from recent repeats. A small shop posting often therefore keeps
        // a short dense history; a large/slow catalogue keeps a longer one.
        val cycles = 3
        val days = ceil(size * cycles / postsPerDay.toDouble()).toLong().coerceIn(7, 730)
        val limit = (size * cycles + postsPerDay * 2).coerceIn(25, 5_000)
        return PromotionHistoryWindow(now - days * 86_400_000L, limit, size)
    }
}
