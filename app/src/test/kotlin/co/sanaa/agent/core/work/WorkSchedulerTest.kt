package co.sanaa.agent.core.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase B scheduling/metering failure modes: cycles, unknown deps, overruns, missed/deferred. */
class WorkSchedulerTest {

    private fun contract(id: String, deps: List<String> = emptyList(), deadlineMs: Long? = null, budget: Long? = null) = WorkContract(
        id = id, objective = "obj $id", owner = "owner",
        deliverables = listOf("d"), successCriteria = listOf("s"),
        deadlineMs = deadlineMs, dependencies = deps, allowedSystems = setOf("device"),
        dataClassification = DataClassification.BUSINESS_INTERNAL, budgetUgx = budget,
        approvalPolicy = ApprovalPolicy(), verificationRules = emptyList(),
        escalationConditions = emptyList(), followUpObligations = emptyList(), createdAtMs = 0,
    )

    @Test fun dependenciesProduceTopologicalOrder() {
        val a = contract("a")
        val b = contract("b", deps = listOf("a"))
        val c = contract("c", deps = listOf("b", "a"))
        val result = WorkScheduler.order(listOf(c, b, a))
        val ordered = (result as WorkScheduler.OrderResult.Ordered).sequence
        assertEquals(listOf("a", "b", "c"), ordered.map { it.id })
    }

    @Test fun unknownDependencyFailsLoudly() {
        val orphaned = contract("x", deps = listOf("ghost"))
        assertTrue(runCatching { WorkScheduler.order(listOf(orphaned)) }.isFailure)
    }

    @Test fun cyclicDependencyIsReportedNotSilentlyOrdered() {
        val a = contract("a", deps = listOf("b"))
        val b = contract("b", deps = listOf("a"))
        val result = WorkScheduler.order(listOf(a, b))
        assertTrue(result is WorkScheduler.OrderResult.CyclicDependency)
    }

    @Test fun overdueUncompletedWorkIsMissed() {
        val late = contract("late", deadlineMs = 1_000)
        assertEquals(OccurrenceState.MISSED, WorkScheduler.occurrenceState(late, lastCompletedAtMs = null, deferredUntilMs = null, nowMs = 2_000))
        val onTime = contract("ok", deadlineMs = 5_000)
        assertEquals(OccurrenceState.PLANNED, WorkScheduler.occurrenceState(onTime, null, null, 2_000))
    }

    @Test fun deferralWindowSuppressesExecutionUntilItExpires() {
        val work = contract("work", deadlineMs = 10_000)
        assertEquals(OccurrenceState.DEFERRED, WorkScheduler.occurrenceState(work, null, deferredUntilMs = 3_000, nowMs = 1_000))
        assertEquals(OccurrenceState.PLANNED, WorkScheduler.occurrenceState(work, null, deferredUntilMs = 500, nowMs = 1_000))
    }

    @Test fun recentCompletionCountsAsVerifiedOccurrence() {
        val work = contract("done", deadlineMs = 100_000)
        assertEquals(OccurrenceState.EXECUTED_VERIFIED, WorkScheduler.occurrenceState(work, lastCompletedAtMs = 9_000, null, 10_000))
    }

    @Test fun budgetMeterHardStopsAtTheDeclaredCeiling() {
        val meter = BudgetMeter()
        val capped = contract("capped", budget = 100_000)
        assertTrue(meter.record(capped, 60_000, "first batch", 0).isSuccess)
        assertTrue(meter.record(capped, 40_000, "second batch", 1).isSuccess)
        val overrun = meter.record(capped, 1_000, "third batch", 2)
        assertTrue(overrun.isFailure)
        assertTrue(overrun.exceptionOrNull()!!.message!!.contains("Budget exceeded"))
        assertEquals(100_000, meter.spentOn("capped"))
        assertFalse(meter.withinBudget(capped.copy(budgetUgx = 50_000)))
        // Uncapped contracts never block.
        val open = contract("open")
        assertTrue(meter.record(open, 9_999_999, "big", 3).isSuccess)
        assertTrue(meter.withinBudget(open))
    }

    @Test fun deadlineMeteringReportsRemainingAndOverrun() {
        val meter = BudgetMeter()
        assertEquals(4_000L, meter.remainingMs(contract("x", deadlineMs = 5_000), nowMs = 1_000))
        assertEquals(-500L, meter.remainingMs(contract("y", deadlineMs = 500), nowMs = 1_000))
        // No-deadline contracts report no remaining time at all.
        assertNull("contracts without a deadline have no remaining budget of time", meter.remainingMs(contract("z"), nowMs = 0))
    }

    // ---------- freshness semantics (duration-based, never timestamp fractions) ----------

    private fun recurring(id: String, createdAt: Long, deadline: Long?, freshness: Long? = null) =
        contract(id, deadlineMs = deadline).copy(createdAtMs = createdAt, freshnessWindowMs = freshness)

    @Test fun recentCompletionInsideFreshnessWindowStaysVerified() {
        val work = recurring("r1", createdAt = 0, deadline = 100_000, freshness = 10_000)
        assertEquals(
            OccurrenceState.EXECUTED_VERIFIED,
            WorkScheduler.occurrenceState(work, lastCompletedAtMs = 9_999, deferredUntilMs = null, nowMs = 19_998),
        )
        // Exactly at the window edge is still fresh.
        assertEquals(OccurrenceState.EXECUTED_VERIFIED, WorkScheduler.occurrenceState(work, 0, null, 10_000))
    }

    @Test fun staleCompletionFallsBackToDeadlineState() {
        val work = recurring("r2", createdAt = 0, deadline = 100_000, freshness = 10_000)
        // One ms past the window the occurrence must leave EXECUTED_VERIFIED…
        assertEquals(OccurrenceState.PLANNED, WorkScheduler.occurrenceState(work, lastCompletedAtMs = 0, null, nowMs = 10_001))
        // …and once the deadline passes it is MISSED, not silently fresh again.
        assertEquals(
            OccurrenceState.MISSED,
            WorkScheduler.occurrenceState(work, lastCompletedAtMs = 0, deferredUntilMs = null, nowMs = 100_001),
        )
    }

    @Test fun futureDeadlineWithNoCompletionIsPlanned() {
        val work = recurring("r3", createdAt = 1_000, deadline = 50_000, freshness = 5_000)
        assertEquals(OccurrenceState.PLANNED, WorkScheduler.occurrenceState(work, null, null, nowMs = 49_999))
        assertEquals(OccurrenceState.PLANNED, WorkScheduler.occurrenceState(work, null, null, nowMs = 50_000))
    }

    @Test fun overdueUncompletedWorkIsMissedEvenWithoutFreshnessOverride() {
        val work = recurring("r4", createdAt = 500, deadline = 2_000)
        assertEquals(OccurrenceState.MISSED, WorkScheduler.occurrenceState(work, null, null, nowMs = 2_001))
    }

    @Test fun noDeadlineContractUsesDefaultDayWindow() {
        val work = recurring("r5", createdAt = 0, deadline = null)
        assertEquals(OccurrenceState.EXECUTED_VERIFIED, WorkScheduler.occurrenceState(work, lastCompletedAtMs = 1_000, null, nowMs = 1_000 + 86_400_000L))
        assertEquals(OccurrenceState.PLANNED, WorkScheduler.occurrenceState(work, lastCompletedAtMs = 1_000, null, nowMs = 1_000 + 86_400_001L + 60_000L))
        // And with no completion and no deadline there is nothing missed — just planned.
        assertEquals(OccurrenceState.PLANNED, WorkScheduler.occurrenceState(work, null, null, nowMs = Long.MAX_VALUE / 2))
    }

    @Test fun freshnessNeverDerivesFromHalfAnAbsoluteTimestamp() {
        // Regression: an absolute epoch deadline divided by two classified very old
        // completions as fresh. A contract created near the epoch with a modern deadline
        // gets a huge derived interval; a completion from 1970-era timestamps must still
        // be stale when measured as elapsed duration against that interval.
        val work = recurring("epoch", createdAt = 1_000, deadline = 1_700_000_000_000L)
        val window = WorkScheduler.freshnessWindow(work)
        assertTrue("derived window is half the active interval", window == (1_700_000_000_000L - 1_000) / 2 || window >= WorkContract.MIN_FRESHNESS_MS)
        // A completion far older than the window is never fresh regardless of magnitudes.
        assertEquals(OccurrenceState.MISSED, WorkScheduler.occurrenceState(work, lastCompletedAtMs = 2_000, null, nowMs = 1_700_000_000_001L))
    }

    @Test fun explicitFreshnessOverrideWinsAndMustBePositive() {
        val override = recurring("ovr", createdAt = 0, deadline = 1_000_000, freshness = 3_000)
        assertEquals(3_000, WorkScheduler.freshnessWindow(override))
        assertEquals(OccurrenceState.EXECUTED_VERIFIED, WorkScheduler.occurrenceState(override, 1_000, null, 4_000))
        assertTrue(runCatching { recurring("bad", 0, null, freshness = 0) }.isFailure)
    }

    @Test fun deferralBeatsBothFreshnessAndMissedStates() {
        val work = recurring("def", createdAt = 0, deadline = 5_000, freshness = 1_000)
        assertEquals(OccurrenceState.DEFERRED, WorkScheduler.occurrenceState(work, null, deferredUntilMs = 6_000, nowMs = 4_000))
    }
}
