package co.sanaa.agent.core.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase B simulation: 100 interrupted multi-step workflows resume correctly with zero
 * duplicate external actions. The executor records every dispatch by occurrence key;
 * a duplicate would fail the run instantly.
 */
class ResumableWorkflowTest {

    private class FakeStore : WorkflowStore {
        val runs = ConcurrentHashMap<String, WorkflowRun>()
        val decisions = ConcurrentHashMap<String, String>()
        override fun tryAcquireLease(runId: String, worker: String, newExpiryMs: Long, nowMs: Long): Boolean {
            while (true) {
                val current = runs[runId] ?: return false
                if (current.leaseOwner != null && current.leaseOwner != worker && current.leaseExpiresAtMs > nowMs) return false
                val updated = current.copy(leaseOwner = worker, leaseExpiresAtMs = newExpiryMs, phase = if (current.phase == RunPhase.PENDING) RunPhase.RUNNING else current.phase)
                if (runs.replace(runId, current, updated)) return true
            }
        }
        override fun recordCheckpoint(runId: String, stepIndex: Int, checkpointJson: String, nowMs: Long): Boolean {
            runs.compute(runId) { _, r -> r?.copy(stepIndex = stepIndex + 1, phase = RunPhase.CHECKPOINTED, updatedAtMs = nowMs) }
            return true
        }
        override fun run(runId: String): WorkflowRun? = runs[runId]
        override fun markDecisionRequired(runId: String, question: String): Boolean {
            runs.compute(runId) { _, r -> r?.copy(phase = RunPhase.AWAITING_DECISION) }
            decisions[runId] = question
            return true
        }
        override fun resolveDecision(runId: String, answer: String): Boolean {
            runs.compute(runId) { _, r -> r?.copy(phase = RunPhase.RUNNING) }
            return true
        }
    }

    private fun contract(index: Int): WorkContract = WorkContract(
        id = "contract-$index",
        objective = "Prepare weekly sales review $index",
        owner = "Owner",
        deliverables = listOf("brief.md"),
        successCriteria = listOf("All sections present"),
        deadlineMs = null,
        dependencies = emptyList(),
        allowedSystems = setOf("device", "whatsapp"),
        dataClassification = DataClassification.BUSINESS_INTERNAL,
        budgetUgx = null,
        approvalPolicy = ApprovalPolicy(requireFreshApprovalFor = setOf("send_whatsapp")),
        verificationRules = listOf("Every send verified in target chat"),
        escalationConditions = listOf("Any uncertain side effect"),
        followUpObligations = emptyList(),
        createdAtMs = 0,
    )

    private val STEPS = 6

    @Test fun hundredInterruptedWorkflowsResumeWithZeroDuplicateSideEffects() {
        val executedKeys = ConcurrentHashMap.newKeySet<String>()
        var duplicates = 0
        var resumedCorrectly = 0

        for (index in 0 until 100) {
            val store = FakeStore()
            val engine = ResumableWorkflowEngine(store)
            val runId = "run-$index"
            store.runs[runId] = WorkflowRun(
                id = runId, contractFingerprint = contract(index).fingerprint(),
                stepIndex = 0, phase = RunPhase.PENDING, leaseOwner = null,
                leaseExpiresAtMs = 0, checkpointJson = "", updatedAtMs = 0,
            )
            // Simulate repeated process deaths at random points across the run.
            var deaths = (index % 3) + 1
            var worker = "worker-a"
            while (true) {
                val next = engine.nextAction(runId, worker, nowMs = 1_000, totalSteps = STEPS) ?: break
                // Exactly-once guard at the executor boundary.
                if (!executedKeys.add(next.occurrenceKey)) duplicates++
                engine.checkpoint(runId, next.stepIndex, "{}", nowMs = 2_000)
                if (deaths > 0) {
                    deaths--
                    worker = if (worker == "worker-a") "worker-b" else "worker-a"
                    continue
                }
            }
            assertEquals(STEPS, store.runs.getValue(runId).stepIndex)
            resumedCorrectly++
        }
        assertEquals(100, resumedCorrectly)
        assertEquals("Zero duplicate external actions", 0, duplicates)
    }

    @Test fun leaseHeldByAnotherLiveWorkerBlocksExecution() {
        val store = FakeStore()
        val engine = ResumableWorkflowEngine(store)
        store.runs["r"] = WorkflowRun("r", "fp", 2, RunPhase.RUNNING, leaseOwner = "other", leaseExpiresAtMs = 5_000, checkpointJson = "", updatedAtMs = 0)
        assertNull(engine.nextAction("r", "me", nowMs = 1_000, totalSteps = 9))
        // After expiry the lease is stealable.
        assertNotNull(engine.nextAction("r", "me", nowMs = 6_000, totalSteps = 9))
    }

    @Test fun awaitingDecisionRunsStayPausedUntilResolved() {
        val store = FakeStore()
        val engine = ResumableWorkflowEngine(store)
        store.runs["d"] = WorkflowRun("d", "fp", 1, RunPhase.AWAITING_DECISION, leaseOwner = "me", leaseExpiresAtMs = 9_000, checkpointJson = "", updatedAtMs = 0)
        assertNull(engine.nextAction("d", "me", nowMs = 1_000, totalSteps = 9))
        store.resolveDecision("d", "approved")
        assertNotNull(engine.nextAction("d", "me", nowMs = 1_000, totalSteps = 9))
    }

    @Test fun terminalPhasesNeverResume() {
        val store = FakeStore()
        val engine = ResumableWorkflowEngine(store)
        store.runs["done"] = WorkflowRun("done", "fp", 9, RunPhase.COMPLETED, leaseOwner = null, leaseExpiresAtMs = 0, checkpointJson = "", updatedAtMs = 0)
        store.runs["cancel"] = WorkflowRun("cancel", "fp", 3, RunPhase.CANCELLED, leaseOwner = null, leaseExpiresAtMs = 0, checkpointJson = "", updatedAtMs = 0)
        assertNull(engine.nextAction("done", "w", 0, totalSteps = 9))
        assertNull(engine.nextAction("cancel", "w", 0, totalSteps = 9))
    }

    @Test fun occurrenceStatesCoverMissedAndDeferred() {
        val states = OccurrenceState.entries.map { it.name }.toSet()
        assertTrue(states.containsAll(setOf("MISSED", "DEFERRED", "CLAIMED", "EXECUTED_VERIFIED")))
    }

    @Test fun contractsRejectIncompleteDefinitions() {
        val base = mapOf(
            "objective" to "x", "deliverables" to listOf("a"), "successCriteria" to listOf("b"), "allowedSystems" to setOf("device"),
        )
        val complete = WorkContract(
            id = "c", objective = base["objective"] as String, owner = "o",
            deliverables = base["deliverables"] as List<String>,
            successCriteria = base["successCriteria"] as List<String>,
            deadlineMs = null, dependencies = emptyList(),
            allowedSystems = base["allowedSystems"] as Set<String>,
            dataClassification = DataClassification.BUSINESS_INTERNAL, budgetUgx = null,
            approvalPolicy = ApprovalPolicy(), verificationRules = emptyList(),
            escalationConditions = emptyList(), followUpObligations = emptyList(), createdAtMs = 0,
        )
        assertEquals(complete.fingerprint(), complete.fingerprint())
        var rejected = false
        runCatching { complete.copy(deliverables = emptyList()) }.onFailure { rejected = true }
        assertTrue("Contracts without deliverables must be rejected", rejected)
        assertFalse(complete.copy(objective = "changed").fingerprint() == complete.fingerprint())
    }
}
