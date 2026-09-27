package co.sanaa.agent.core.shorts

import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.core.SideEffectState
import co.sanaa.agent.core.SideEffectTransaction
import co.sanaa.agent.core.SideEffectOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ShortsCadenceTest {
    private fun receipt(at: Long, state: SideEffectState, target: String = "@shop") = SideEffectTransaction(
        "youtube:source:$at", CapabilityIds.POST_YOUTUBE_SHORT, target, "hash", null,
        state, at, at, "test",
    )

    @Test fun uncertainUploadConsumesIntervalAcrossRestartButPreflightFailureDoesNot() {
        val at = 1_800_000_000_000L
        val receipts = listOf(receipt(at - 60_000, SideEffectState.FAILED),
            receipt(at, SideEffectState.UNCERTAIN).copy(updatedAt = at + 120_000),
            receipt(at + 30_000, SideEffectState.VERIFIED, "@other"))
        assertEquals(at + 120_000 + 240 * 60_000L, ShortsCadence.nextAt(receipts, "@shop", 240, 0))
        assertEquals(at + 300 * 60_000L, ShortsCadence.nextAt(receipts, "@shop", 240, at + 300 * 60_000L))
        assertEquals(0L, ShortsCadence.nextAt(listOf(receipt(at, SideEffectState.FAILED)), "@shop", 240, 0))
    }

    @Test fun dailyCapUsesChannelAndLocalDayBoundaryIncludingDst() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = Instant.parse("2026-03-29T21:30:00Z").toEpochMilli()
        val today = Instant.parse("2026-03-28T23:00:00Z").toEpochMilli()
        val tomorrow = Instant.parse("2026-03-29T22:00:00Z").toEpochMilli()
        val receipts = listOf(
            receipt(today - 1, SideEffectState.VERIFIED),
            receipt(today, SideEffectState.UNCERTAIN),
            receipt(now, SideEffectState.VERIFIED),
            receipt(now, SideEffectState.FAILED),
            receipt(now, SideEffectState.VERIFIED, "@other"),
            receipt(tomorrow, SideEffectState.VERIFIED),
        )
        assertEquals(2, ShortsCadence.dailyDispatches(receipts, "@shop", now, zone))
    }

    @Test fun onlyPossibleExternalUploadsConsumeAnOpportunity() {
        assertFalse(ShortsCadence.consumes(SideEffectOutcome.Failed("preflight")))
        assertFalse(ShortsCadence.consumes(SideEffectOutcome.Rejected("scope")))
        assertFalse(ShortsCadence.consumes(SideEffectOutcome.DuplicateBlocked(SideEffectState.FAILED)))
        assertTrue(ShortsCadence.consumes(SideEffectOutcome.Uncertain("upload tap accepted")))
        assertTrue(ShortsCadence.consumes(SideEffectOutcome.DuplicateBlocked(SideEffectState.ACTING)))
    }
}
