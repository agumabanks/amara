package co.sanaa.agent.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.SideEffectLedger
import co.sanaa.agent.core.SideEffectState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Production-SQLite workflow durability (Robolectric). Scope: real AmaraMemory storage;
 * the external effect itself is a stub — live device behavior stays device-gated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DurableWorkflowPersistenceTest {

    private lateinit var context: Context
    private lateinit var memory: AmaraMemory
    private lateinit var store: WorkflowStore
    private lateinit var ledger: SideEffectLedger
    private lateinit var coordinator: DurableWorkflowCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
        store = SqliteWorkflowStore(memory)
        ledger = SideEffectLedger.from(memory)
        coordinator = DurableWorkflowCoordinator(store, ledger)
    }

    private fun seedRun(id: String) {
        memory.upsertWorkflowRun(
            AmaraMemory.WorkflowRunRow(
                id = id, contractFingerprint = "fp", stepIndex = 0,
                phase = RunPhase.PENDING, leaseOwner = null, leaseExpiresAtMs = 0,
                decisionQuestion = "", checkpointJson = "{}", updatedAtMs = 0,
            ),
        )
    }

    @Test fun runsPersistAcrossDatabaseReopen() {
        seedRun("reopen")
        assertTrue(store.tryAcquireLease("reopen", "w1", nowMs = 100, newExpiryMs = 9_000))
        val reopened = SqliteWorkflowStore(AmaraMemory(context))
        val run = reopened.run("reopen")
        assertNotNull(run)
        assertEquals("w1", run!!.leaseOwner)
        assertEquals(0, run.stepIndex)
    }

    @Test fun crashWindowBetweenEffectAndCheckpointIsNeverReplayed() {
        seedRun("crash")
        assertTrue(store.tryAcquireLease("crash", "w", nowMs = 0, newExpiryMs = 10_000))
        // Step 3 of 6 dispatches; the external effect happens…
        val dispatch = coordinator.dispatchNext("crash", "w", 1_000, totalSteps = 6, capabilityId = "send_whatsapp", contentFingerprint = "c")
        assertTrue(dispatch is StepDispatch.Execute)
        // …the process dies BEFORE completeStep/checkpoint. Simulate the durable state a
        // restart would observe: the occurrence claim survives in CLAIMED/ACTING.
        val restartedCoordinator = DurableWorkflowCoordinator(SqliteWorkflowStore(AmaraMemory(context)), SideEffectLedger.from(AmaraMemory(context)))
        // Production startup sweep converts nonterminal claims to UNCERTAIN.
        memory.markOrphanedTransactionsUncertain()
        val afterRestart = restartedCoordinator.dispatchNext("crash", "w2", 20_000, 6, "send_whatsapp", "c")
        assertTrue(afterRestart is StepDispatch.UncertainCrashWindow)
        // And the run is parked for an owner decision — never silently replayed.
        assertEquals(
            "phase=${store.run("crash")?.phase} q=${memory.findWorkflowRun("crash")?.decisionQuestion}",
            RunPhase.AWAITING_DECISION, store.run("crash")!!.phase,
        )
        // Owner resolves by confirming the effect happened on-device.
        assertTrue(store.resolveDecision("crash", "owner confirmed step 0 completed"))
        val key = (dispatch as StepDispatch.Execute).occurrenceKey
        ledger.transition(key, SideEffectState.VERIFIED, "owner confirmed")
        store.recordCheckpoint("crash", 0, "{}", 3_000)
        assertEquals(1, store.run("crash")!!.stepIndex)
    }

    @Test fun concurrentWorkersProduceExactlyOneClaimOnProductionLedger() {
        seedRun("race")
        val executed = java.util.concurrent.atomic.AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        val jobs = (1..8).map { workerId ->
            pool.submit<Boolean> {
                val engineStore = SqliteWorkflowStore(memory)
                val engine = ResumableWorkflowEngine(engineStore)
                if (!engineStore.tryAcquireLease("race", "w$workerId", nowMs = 500, newExpiryMs = 50_000)) return@submit false
                val dispatch = coordinator.dispatchNext("race", "w$workerId", 600, 3, "read_screen", "c")
                when (dispatch) {
                    is StepDispatch.Execute -> {
                        executed.incrementAndGet()
                        coordinator.completeStep(dispatch.occurrenceKey, verified = true, evidence = "ok",
                            runId = "race", stepIndex = dispatch.stepIndex, checkpointJson = "{}", nowMs = 700)
                        true
                    }
                    else -> false
                }
            }
        }
        val results = jobs.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, executed.get())
        assertTrue(results.any { it })
    }

    @Test fun optimisticCheckpointsRejectDoubleAdvance() {
        seedRun("opt")
        assertTrue(memory.recordWorkflowCheckpoint("opt", expectedStepIndex = 0, checkpointJson = "{}", nowMs = 1))
        assertFalse("Same step cannot advance twice", memory.recordWorkflowCheckpoint("opt", expectedStepIndex = 0, checkpointJson = "{}", nowMs = 2))
        assertTrue(memory.recordWorkflowCheckpoint("opt", expectedStepIndex = 1, checkpointJson = "{}", nowMs = 3))
        assertEquals(2, memory.findWorkflowRun("opt")!!.stepIndex)
    }

    @Test fun leaseExpiryAllowsStealAfterTimeoutOnly() {
        seedRun("lease")
        assertTrue(memory.tryAcquireWorkflowLease("lease", "a", newExpiryMs = 5_000, nowMs = 0))
        assertFalse("live lease blocks others", memory.tryAcquireWorkflowLease("lease", "b", newExpiryMs = 5_000, nowMs = 1_000))
        assertTrue("expired lease is stealable", memory.tryAcquireWorkflowLease("lease", "b", newExpiryMs = 9_000, nowMs = 6_000))
        assertEquals("b", memory.findWorkflowRun("lease")!!.leaseOwner)
    }

    @Test fun activeCommitmentsListNonTerminalRunsForOwnerInspection() {
        seedRun("active-1")
        seedRun("active-2")
        memory.upsertWorkflowRun(
            AmaraMemory.WorkflowRunRow(id = "done", contractFingerprint = "f", stepIndex = 5,
                phase = RunPhase.COMPLETED, leaseOwner = null, leaseExpiresAtMs = 0,
                decisionQuestion = "", checkpointJson = "", updatedAtMs = 9),
        )
        memory.markWorkflowDecisionRequired("active-2", "Needs owner choice")
        val commitments = memory.activeCommitments()
        assertEquals(setOf("active-1", "active-2"), commitments.map { it.id }.toSet())
        assertEquals("Needs owner choice", commitments.first { it.id == "active-2" }.decisionQuestion)
    }
}
