package co.sanaa.agent.core.shorts

import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.core.SideEffectState
import co.sanaa.agent.core.SideEffectTransaction
import co.sanaa.agent.core.SideEffectOutcome
import java.time.Instant
import java.time.ZoneId

/** Rebuild publishing limits from durable effect receipts after a process restart. */
internal object ShortsCadence {
    private val consumed = setOf(
        SideEffectState.ACTING, SideEffectState.VERIFICATION_PENDING,
        SideEffectState.UNCERTAIN, SideEffectState.VERIFIED,
    )

    private fun dispatched(receipts: List<SideEffectTransaction>, channel: String) = receipts.filter {
        it.capability == CapabilityIds.POST_YOUTUBE_SHORT && it.target.equals(channel, ignoreCase = true) &&
            it.state in consumed
    }

    fun consumes(outcome: SideEffectOutcome): Boolean = when (outcome) {
        is SideEffectOutcome.Verified, is SideEffectOutcome.Uncertain -> true
        is SideEffectOutcome.DuplicateBlocked -> outcome.existingState.noAutoRetry
        is SideEffectOutcome.Failed, is SideEffectOutcome.Rejected -> false
    }

    fun nextAt(receipts: List<SideEffectTransaction>, channel: String, intervalMinutes: Int, legacyNextAt: Long): Long {
        // updatedAt is at or after the attempted external trigger; createdAt can
        // precede media preparation by minutes and would shorten the interval.
        val latest = dispatched(receipts, channel).maxOfOrNull { it.updatedAt } ?: 0L
        val fromReceipt = if (latest > 0) latest + intervalMinutes.coerceIn(10, 1440) * 60_000L else 0L
        return maxOf(legacyNextAt, fromReceipt)
    }

    fun dailyDispatches(receipts: List<SideEffectTransaction>, channel: String, now: Long,
                        zone: ZoneId = ZoneId.systemDefault()): Int {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dispatched(receipts, channel).count { it.createdAt >= start && it.createdAt < end }
    }
}
