package co.sanaa.agent.workflows

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.core.SideEffectLedger
import co.sanaa.agent.core.artifacts.SqliteArtifactStore
import co.sanaa.agent.core.knowledge.ClaimKind
import co.sanaa.agent.core.knowledge.KnowledgeBase
import co.sanaa.agent.core.knowledge.SourcedClaim
import co.sanaa.agent.core.work.DurableWorkflowCoordinator
import co.sanaa.agent.core.work.MemorySpendReservations
import co.sanaa.agent.core.work.SpendReservations
import co.sanaa.agent.core.work.SqliteWorkflowStore
import co.sanaa.agent.core.work.WorkContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Executor-semantics tests over REAL SQLite stores (runs, checkpoints, ledger,
 * approvals, artifacts) with a RECORDING effect router. The production router itself is
 * certified separately in [WorkflowProductionRouterTest] against the real device seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkflowExecutorTest {

    private lateinit var memory: AmaraMemory
    private lateinit var knowledge: KnowledgeBase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
        knowledge = KnowledgeBase()
    }

    private class RecordingEffects : WorkflowEffectRouter {
        val executed = mutableListOf<Pair<String, String>>()
        var nextOutcome: ExecutedEffect = ExecutedEffect(true, "recorded verified effect")
        val approvalIds = mutableListOf<Long?>()

        override suspend fun execute(
            capabilityId: String, target: String, content: String,
            contract: co.sanaa.agent.core.work.WorkContract, step: GraphStep,
            approvalId: Long?, inputs: Map<String, String>,
        ): ExecutedEffect {
            executed += capabilityId to target
            approvalIds += approvalId
            return nextOutcome
        }
    }

    private fun executor(effects: RecordingEffects, budget: SpendReservations = MemorySpendReservations(), workerId: String = "workflow-executor"): DepartmentWorkflowExecutor =
        DepartmentWorkflowExecutor(
            store = SqliteWorkflowStore(memory),
            coordinator = DurableWorkflowCoordinator(SqliteWorkflowStore(memory), SideEffectLedger.from(memory)),
            ledger = SideEffectLedger.from(memory),
            budget = budget,
            evidence = ContractScopedEvidence(knowledge),
            approvals = AmaraApprovalGateway(memory),
            effects = effects,
            artifactStore = SqliteArtifactStore(memory),
            workerId = workerId,
        )

    private fun workflowWithSend() = DepartmentWorkflow(
        id = "send_probe", name = "Send probe",
        inputContract = setOf("target"),
        dataClassification = co.sanaa.agent.core.work.DataClassification.CUSTOMER_DATA,
        executionGraph = listOf(
            GraphStep("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Evidence"),
            GraphStep("draft", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Draft"),
            GraphStep("approval", DepartmentWorkflow.StepKind.REQUEST_APPROVAL, CapabilityIds.SEND_WHATSAPP, producesSection = "Approvals"),
            GraphStep("send", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, CapabilityIds.SEND_WHATSAPP),
            GraphStep("verify_send", DepartmentWorkflow.StepKind.VERIFY, null),
            GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
        ),
        requiredReportSections = listOf("Evidence", "Draft", "Approvals"),
        consequentialActions = setOf(CapabilityIds.SEND_WHATSAPP),
        escalationConditions = emptyList(),
    )

    private fun contract(workflow: DepartmentWorkflow, nowMs: Long) = WorkContract(
        id = "wf-${workflow.id}-$nowMs", objective = workflow.name, owner = "owner",
        deliverables = workflow.requiredReportSections,
        successCriteria = listOf("sections"), deadlineMs = nowMs + 3_600_000,
        dependencies = emptyList(), allowedSystems = setOf("*"),
        dataClassification = co.sanaa.agent.core.work.DataClassification.CUSTOMER_DATA,
        budgetUgx = 10_000L,
        approvalPolicy = co.sanaa.agent.core.work.ApprovalPolicy(requireFreshApprovalFor = workflow.consequentialActions),
        verificationRules = listOf("verify"), escalationConditions = emptyList(),
        followUpObligations = emptyList(), createdAtMs = nowMs,
    )

    private fun seedEvidence(subject: String) {
        knowledge.add(
            SourcedClaim(
                kind = ClaimKind.FACT, statement = "$subject has fresh stock evidence",
                sourceRef = "inventory-scan", capturedAtMs = System.currentTimeMillis(),
                freshnessMs = 3_600_000L,
            ),
        )
    }

    @Test fun approvedThenVerifiedEffectCompletesAndConsumesApprovalExactlyOnce() {
        val workflow = workflowWithSend()
        val now = System.currentTimeMillis()
        seedEvidence("customer-1 product-1")
        val contract = contract(workflow, now)

        // First run parks at the approval step with a durable pending request.
        val first = runBlocking { executor(RecordingEffects()).run(workflow, contract, mapOf("customer_id" to "customer-1", "product_id" to "product-1", "target" to "+256700000000")) }
        assertTrue(first is DepartmentWorkflowExecutor.Outcome.ParkedForOwnerDecision)
        val pending = memory.pendingApprovals(now).first { it.capability == CapabilityIds.SEND_WHATSAPP }
        assertEquals("+256700000000", pending.target)

        // Owner approves; the resumed run executes through the recording router.
        val effects = RecordingEffects()
        assertTrue("decide failed", memory.decideApproval(pending.id, true))
        assertTrue("resolve failed", SqliteWorkflowStore(memory).resolveDecision(contract.id, "owner approved"))
        val second = runBlocking { executor(effects).run(workflow, contract, mapOf("customer_id" to "customer-1", "product_id" to "product-1", "target" to "+256700000000")) }
        assertTrue("second was $second", second is DepartmentWorkflowExecutor.Outcome.Completed)
        val completed = second as DepartmentWorkflowExecutor.Outcome.Completed
        assertEquals(1, completed.verifiedActions)
        // The bound approval id flowed into the router (and thus the transaction runner).
        assertEquals(listOf<Long?>(pending.id), effects.approvalIds)
        // The recording router bypasses the transaction boundary, so atomic pre-act
        // consumption is certified in WorkflowProductionRouterTest; here we prove the
        // durable BINDING matches exactly (one successful consume, then exhausted).
        val bindingHash = co.sanaa.agent.core.ContentHashing.hash(
            TransactionRoutedEffects.approvalBindingContent(CapabilityIds.SEND_WHATSAPP, lastApprovalContentFor(contract.id, now), emptyMap()),
        )
        assertTrue(
            "exact binding must be consumable",
            memory.consumeWorkflowApprovalForExecution(
                pending.id, CapabilityIds.SEND_WHATSAPP, "+256700000000", bindingHash, contract.id, now + 1_000,
            ),
        )
        assertFalse(
            "execution count must be exhausted after one use",
            memory.consumeWorkflowApprovalForExecution(
                pending.id, CapabilityIds.SEND_WHATSAPP, "+256700000000", bindingHash, contract.id, now + 1_000,
            ),
        )
    }

    /**
     * Rebuilds the deterministic draft content the executor binds approvals against:
     * at the approval step the bound content is the latest produced section — the
     * Draft section body composed from the seeded claims.
     */
    private fun lastApprovalContentFor(runId: String, nowMs: Long): String {
        val statement = "customer-1 product-1 has fresh stock evidence"
        return buildString {
            appendLine("Drafted for 'Send probe' from 1 scoped claims.")
            appendLine("- $statement (source: inventory-scan)")
        }.trim()
    }

    @Test fun rejectedBeforeActOutcomeReleasesTheReservationAndParksTheRun() {
        val workflow = workflowWithSend()
        val now = System.currentTimeMillis()
        seedEvidence("customer-2 product-2")
        val contract = contract(workflow, now)
        val reservations = MemorySpendReservations()
        val first = runBlocking { executor(RecordingEffects(), reservations).run(workflow, contract, mapOf("customer_id" to "customer-2", "product_id" to "product-2", "target" to "+256700000001")) }
        assertTrue(first is DepartmentWorkflowExecutor.Outcome.ParkedForOwnerDecision)
        val pending = memory.pendingApprovals(now).first { it.capability == CapabilityIds.SEND_WHATSAPP }
        assertTrue("decide failed", memory.decideApproval(pending.id, true))
        assertTrue("resolve failed", SqliteWorkflowStore(memory).resolveDecision(contract.id, "owner approved"))
        val effects = RecordingEffects()
        effects.nextOutcome = ExecutedEffect(false, "rejected: policy refusal", rejectedBeforeAct = true)
        val second = runBlocking { executor(effects, reservations).run(workflow, contract, mapOf("customer_id" to "customer-2", "product_id" to "product-2", "target" to "+256700000001")) }
        assertTrue(second is DepartmentWorkflowExecutor.Outcome.ParkedForOwnerDecision)
        assertEquals("catalog rejection must release spend", 0L, reservations.committedOn(contract.id))
        assertEquals(0L, reservations.reservedOn(contract.id))
    }

    @Test fun uncertainOutcomeIsNotCountedAsVerifiedAndChargesNothing() {
        val workflow = workflowWithSend()
        val now = System.currentTimeMillis()
        seedEvidence("customer-3 product-3")
        val contract = contract(workflow, now)
        val reservations = MemorySpendReservations()
        val firstRun = runBlocking { executor(RecordingEffects(), reservations).run(workflow, contract, mapOf("customer_id" to "customer-3", "product_id" to "product-3", "target" to "+256700000002")) }
        assertTrue(firstRun is DepartmentWorkflowExecutor.Outcome.ParkedForOwnerDecision)
        val pending = memory.pendingApprovals(now).first { it.capability == CapabilityIds.SEND_WHATSAPP }
        assertTrue("decide failed", memory.decideApproval(pending.id, true))
        assertTrue("resolve failed", SqliteWorkflowStore(memory).resolveDecision(contract.id, "owner approved"))
        val effects = RecordingEffects()
        effects.nextOutcome = ExecutedEffect(false, "uncertain: verifier timed out")
        val second = runBlocking { executor(effects, reservations).run(workflow, contract, mapOf("customer_id" to "customer-3", "product_id" to "product-3", "target" to "+256700000002")) }
        assertTrue(second is DepartmentWorkflowExecutor.Outcome.ParkedForOwnerDecision)
        assertEquals(0L, reservations.committedOn(contract.id))
    }

    @Test fun duplicateApprovedBindingDoesNotCreateSecondRequest() {
        val workflow = workflowWithSend()
        val now = System.currentTimeMillis()
        seedEvidence("customer-4 product-4")
        val exec = executor(RecordingEffects())
        val contract = contract(workflow, now)
        runBlocking { exec.run(workflow, contract, mapOf("customer_id" to "customer-4", "product_id" to "product-4", "target" to "+256700000003")) }
        val firstPending = memory.pendingApprovals(now).single { it.capability == CapabilityIds.SEND_WHATSAPP }
        // A second identical park must reuse the same durable request id.
        val store2 = SqliteWorkflowStore(memory)
        val secondExec = DepartmentWorkflowExecutor(
            store = store2, coordinator = DurableWorkflowCoordinator(store2, SideEffectLedger.from(memory)),
            ledger = SideEffectLedger.from(memory), budget = MemorySpendReservations(),
            evidence = ContractScopedEvidence(knowledge), approvals = AmaraApprovalGateway(memory),
            effects = RecordingEffects(), artifactStore = SqliteArtifactStore(memory),
        )
        runBlocking { secondExec.run(workflow, contract, mapOf("customer_id" to "customer-4", "product_id" to "product-4", "target" to "+256700000003")) }
        val pendings = memory.pendingApprovals(now).filter { it.capability == CapabilityIds.SEND_WHATSAPP && it.target == "+256700000003" }
        assertEquals(1, pendings.size)
        assertEquals(firstPending.id, pendings.single().id)
    }

    @Test fun concurrentWorkerCannotStealTheLeaseMidRun() {
        val workflow = workflowWithSend()
        val now = System.currentTimeMillis()
        seedEvidence("customer-5 product-5")
        val contract = contract(workflow, now)
        val workerA = executor(RecordingEffects())
        val parked = runBlocking { workerA.run(workflow, contract, mapOf("customer_id" to "customer-5", "product_id" to "product-5", "target" to "+256700000004")) }
        assertTrue(parked is DepartmentWorkflowExecutor.Outcome.ParkedForOwnerDecision)
        // Worker B cannot acquire the lease while A's is live.
        val workerB = executor(RecordingEffects(), workerId = "worker-b")
        val stolen = runBlocking { workerB.run(workflow, contract, mapOf("customer_id" to "customer-5", "product_id" to "product-5", "target" to "+256700000004")) }
        assertTrue("stolen was $stolen", stolen is DepartmentWorkflowExecutor.Outcome.RefusedByEnforcement)
    }
}
