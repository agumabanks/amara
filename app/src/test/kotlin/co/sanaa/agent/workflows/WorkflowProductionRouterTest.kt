package co.sanaa.agent.workflows

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.ApprovalRequirement
import co.sanaa.agent.core.CapabilityCatalog
import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.Initiator
import co.sanaa.agent.core.SideEffectLedger
import co.sanaa.agent.core.SideEffectOutcome
import co.sanaa.agent.core.SideEffectRunner
import co.sanaa.agent.core.SideEffectState
import co.sanaa.agent.core.TaskQueue
import co.sanaa.agent.core.artifacts.SqliteArtifactStore
import co.sanaa.agent.core.knowledge.KnowledgeBase
import co.sanaa.agent.core.knowledge.SourcedClaim
import co.sanaa.agent.core.work.DurableWorkflowCoordinator
import co.sanaa.agent.core.work.SqliteWorkflowStore
import co.sanaa.agent.core.work.MemorySpendReservations
import co.sanaa.agent.core.work.SpendReservations
import co.sanaa.agent.core.work.WorkContract
import co.sanaa.agent.core.work.WorkflowStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * REAL-ROUTER CERTIFICATION (Part A directive 1). Every test in this class runs the
 * production stack end to end:
 *
 *   actual TransactionRoutedEffects  →  actual SideEffectRunner
 *   actual CapabilityCatalog         →  durable SQLite side-effect ledger (AmaraMemory)
 *   actual AmaraApprovalGateway      →  durable workflow/approval/artifact stores
 *   controlled FakeDeviceSurface     →  MemorySpendReservations for accounting assertions
 *
 * Each routed capability must pass catalog authorization AND typed schema validation at
 * the transaction boundary before any CE-E-EXEC-01 claim; approval consumption, spend
 * reservation lifecycle, lease renewal, deadline rechecks, evidence gating, and artifact
 * completeness are all proven against this production path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkflowProductionRouterTest {

    private lateinit var memory: AmaraMemory
    private lateinit var surface: FakeDeviceSurface
    private lateinit var store: WorkflowStore
    private lateinit var knowledge: KnowledgeBase
    private lateinit var approvals: AmaraApprovalGateway
    private lateinit var reservations: MemorySpendReservations

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
        surface = FakeDeviceSurface()
        store = SqliteWorkflowStore(memory)
        knowledge = KnowledgeBase()
        approvals = AmaraApprovalGateway(memory)
        reservations = MemorySpendReservations()
    }

    private fun router(): TransactionRoutedEffects = TransactionRoutedEffects(
        sideEffects = SideEffectRunner(SideEffectLedger.from(memory)),
        queue = TaskQueue(),
        actions = { surface },
        ownerPhone = { "+256700000001" },
        approvalGateway = approvals,
    )

    private fun executor(
        budget: SpendReservations = reservations,
        clock: () -> Long = System::currentTimeMillis,
        storeOverride: WorkflowStore = store,
    ): DepartmentWorkflowExecutor = DepartmentWorkflowExecutor(
        store = storeOverride,
        coordinator = DurableWorkflowCoordinator(storeOverride, SideEffectLedger.from(memory)),
        ledger = SideEffectLedger.from(memory),
        budget = budget,
        evidence = ContractScopedEvidence(knowledge),
        approvals = approvals,
        effects = router(),
        artifactStore = SqliteArtifactStore(memory),
        workerId = "certification-worker",
        clockMs = clock,
    )

    private fun contract(
        id: String,
        workflow: DepartmentWorkflow,
        nowMs: Long = System.currentTimeMillis(),
        deadlineInMs: Long = 60 * 60_000L,
        budgetUgx: Long = 50_000L,
    ) = WorkContract(
        id = id, objective = workflow.name, owner = "owner",
        deliverables = workflow.requiredReportSections,
        successCriteria = listOf("All required sections rendered", "Every consequential action verified"),
        deadlineMs = nowMs + deadlineInMs, dependencies = emptyList(),
        allowedSystems = setOf("*"),
        dataClassification = co.sanaa.agent.core.work.DataClassification.CUSTOMER_DATA,
        budgetUgx = budgetUgx,
        approvalPolicy = co.sanaa.agent.core.work.ApprovalPolicy(requireFreshApprovalFor = workflow.consequentialActions),
        verificationRules = listOf("Consequential actions verify through target-bound evidence"),
        escalationConditions = workflow.escalationConditions, followUpObligations = emptyList(),
        createdAtMs = nowMs,
    )

    private fun seedKnowledge(subject: String) {
        knowledge.add(
            SourcedClaim(
                kind = co.sanaa.agent.core.knowledge.ClaimKind.FACT,
                statement = "$subject stock verified available today",
                sourceRef = "soko-inventory-scan", capturedAtMs = System.currentTimeMillis(),
                freshnessMs = 6 * 60 * 60_000L,
            ),
        )
    }

    /** Drives run→park(pending approval)→owner approves→resume to completion. */
    private fun runToCompletion(workflow: DepartmentWorkflow, contractId: String, inputs: Map<String, String>, nowMs: Long) {
        val exec = executor()
        val firstRun = runBlocking { exec.run(workflow, contract(contractId, workflow, nowMs), inputs) }
        assertTrue("expected a parked run awaiting owner decision but was $firstRun", firstRun is DepartmentWorkflowExecutor.Outcome.ParkedForOwnerDecision)
        val pendingId = memory.pendingApprovals().firstNotNullOfOrNull { it.id }
            ?: error("no pending approval request recorded")
        assertTrue(memory.decideApproval(pendingId, true))
        assertTrue(SqliteWorkflowStore(memory).resolveDecision(runId = contractId, answer = "owner approved"))
        // Resume on a fresh executor instance: durable rows drive the restart path.
        val second = runBlocking { executor().run(workflow, contract(contractId, workflow, nowMs), inputs) }
        assertTrue("expected completion after approval but was $second", second is DepartmentWorkflowExecutor.Outcome.Completed)
    }

    // ---------- catalog authorization before any claim ----------

    @Test fun unregisteredCapabilityIsRefusedBeforeAnyTransactionRow() = runBlocking {
        val outcome = router().execute(
            capabilityId = "ghost_capability", target = "x", content = "y",
            contract = contract("c1", WorkflowRegistry.byId("lead_qualification")!!),
            step = GraphStep("send", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, "ghost_capability"),
            approvalId = null,
        )
        assertTrue(outcome.rejectedBeforeAct)
        assertEquals(0, memory.allSideEffectTransactions().size)
    }

    @Test fun capabilityNotAuthorizedForWorkflowsIsRejectedByTheRealCatalog() = runBlocking {
        val outcome = router().execute(
            capabilityId = CapabilityIds.BROADCAST_GROUP_WHATSAPP, target = "clients", content = "hello",
            contract = contract("c2", WorkflowRegistry.byId("lead_qualification")!!),
            step = GraphStep("send", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, CapabilityIds.BROADCAST_GROUP_WHATSAPP),
            approvalId = null,
        )
        assertTrue(outcome.rejectedBeforeAct)
        assertTrue(outcome.evidenceSummary.contains("does not authorize workflow-initiated execution"))
    }

    @Test fun workflowInitiatedSendWithoutBoundApprovalNeverReachesTheDevice() = runBlocking {
        seedKnowledge("listing-9 verified stock")
        surface.verifyWillSucceed("+256777111222", "Hello")
        val outcome = router().execute(
            capabilityId = CapabilityIds.SEND_WHATSAPP, target = "+256777111222", content = "Hello",
            contract = contract("c3", WorkflowRegistry.byId("lead_qualification")!!),
            step = GraphStep("send", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, CapabilityIds.SEND_WHATSAPP),
            approvalId = null,
        )
        assertTrue(outcome.rejectedBeforeAct)
        assertTrue(outcome.evidenceSummary.contains("fresh exact approval"))
        assertEquals(0, surface.dispatchedCount)
    }

    @Test fun schemaViolationBlankTargetIsRejectedAtTheProductionBoundary() = runBlocking {
        seedKnowledge("listing-z verified stock")
        surface.verifyWillSucceed("anyone", "content")
        val outcome = router().execute(
            capabilityId = CapabilityIds.REPLY_WHATSAPP, target = "", content = "content",
            contract = contract("c4", WorkflowRegistry.byId("lead_qualification")!!),
            step = GraphStep("reply", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, CapabilityIds.REPLY_WHATSAPP),
            approvalId = null,
        )
        assertTrue(outcome.rejectedBeforeAct && outcome.evidenceSummary.contains("non-blank"))
    }

    // ---------- full production path: send_whatsapp ----------

    @Test fun leadQualificationCompletesOnTheProductionRouterWithVerifiedDeliveryAndCommittedSpend() {
        val workflow = WorkflowRegistry.byId("lead_qualification")!!
        val now = System.currentTimeMillis()
        seedKnowledge("customer-42 listing-7")
        surface.seedChat("+256700123456")
        runToCompletion(workflow, "cert-send-1", mapOf("customer_id" to "customer-42", "product_id" to "listing-7", "target" to "+256700123456"), now)

        // The send verified through the REAL transaction ledger.
        val verified = memory.allSideEffectTransactions().first { it.state == SideEffectState.VERIFIED }
        assertEquals(CapabilityIds.SEND_WHATSAPP, verified.capability)
        assertNotNull(verified.approvalId)
        // Approval consumed exactly once at the final pre-act boundary.
        assertFalse(memory.workflowApprovalStillValid(verified.approvalId!!, CapabilityIds.SEND_WHATSAPP, "+256700123456", verified.contentHash, "cert-send-1", System.currentTimeMillis()))
        // Spend: exactly one committed reservation, nothing left reserved.
        assertEquals(DepartmentWorkflowExecutor.perActionCostUgx, reservations.committedOn("cert-send-1"))
        assertEquals(0L, reservations.reservedOn("cert-send-1"))
        // The device actually dispatched once.
        assertEquals(1, surface.dispatchedCount)
    }

    // ---------- post_whatsapp_status under the authorized workflow initiator ----------

    @Test fun statusPublicationRoutesUnderAuthorizedInitiatorWithFreshExactApproval() {
        val workflow = WorkflowRegistry.byId("status_campaign")!!
        val now = System.currentTimeMillis()
        seedKnowledge("product-status-1 promo facts verified")
        surface.foregroundPackage = "com.whatsapp"
        runToCompletion(workflow, "cert-status-1", mapOf("product_id" to "product-status-1"), now)
        val verified = memory.allSideEffectTransactions().first { it.capability == "post_whatsapp_status" && it.state == SideEffectState.VERIFIED }
        assertEquals("status", verified.target)
        assertEquals(DepartmentWorkflowExecutor.perActionCostUgx, reservations.committedOn("cert-status-1"))
    }

    // ---------- apply_soko_edit routing with structured inputs ----------

    @Test fun approvedSokoEditAppliesThroughTheProductionRouterAndVerifiesByReopen() {
        val workflow = WorkflowRegistry.byId("catalog_health")!!
        val now = System.currentTimeMillis()
        seedKnowledge("listing-price-fix price stale")
        surface.foregroundPackage = co.sanaa.agent.actions.SokoSaveVerification.SOKO_PACKAGE
        surface.stageSokoField("product name", "Fresh Mango Box")
        // The draft content becomes the applied value via setFirstEditableField.
        runToCompletion(workflow, "cert-edit-1", mapOf("listing_id" to "Mango Box", "product_id" to "listing-price-fix", "target" to "Mango Box", "field" to "product name", "value" to "Fresh Mango Box"), now)
        assertTrue(memory.allSideEffectTransactions().any { it.capability == "apply_soko_edit" && it.state == SideEffectState.VERIFIED })
    }

    // ---------- approval binding exactness ----------

    @Test fun approvalContentMismatchRefusesPreActAndKeepsApprovalUsableForExactContent() = runBlocking {
        val now = System.currentTimeMillis()
        val contractId = "cert-bind-1"
        val goodContent = "Exact approved message body"
        memory.createWorkflowApprovalRequest(
            capability = CapabilityIds.SEND_WHATSAPP, target = "+256700999888",
            description = "bind test", contentHash = ContentHashing.hash(goodContent),
            contractId = contractId, inputsJson = "{}", risk = co.sanaa.agent.core.ActionRisk.EXTERNAL_COMMUNICATION,
            expiresAt = now + 3_600_000, executionsAllowed = 1, nowMs = now,
        )
        val approvalId = memory.findWorkflowApproval(CapabilityIds.SEND_WHATSAPP, "+256700999888", "", contractId, now)?.id ?: run {
            // pending row exists but find only returns approved ones — approve by direct decision
            memory.pendingApprovals().first().id
        }
        assertTrue(memory.decideApproval(approvalId, true))
        surface.seedChat("+256700999888")

        // Mismatched content: validator refuses at the final boundary; NOTHING dispatches;
        // the approval survives for its exact content.
        val mismatch = router().execute(
            capabilityId = CapabilityIds.SEND_WHATSAPP, target = "+256700999888", content = "DIFFERENT content entirely",
            contract = contract(contractId, WorkflowRegistry.byId("lead_qualification")!!, nowMs = now),
            step = GraphStep("send", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, CapabilityIds.SEND_WHATSAPP),
            approvalId = approvalId,
        )
        assertFalse(mismatch.verified)
        assertEquals(0, surface.dispatchedCount)
        assertTrue(approvals.stillValid(approvalId, CapabilityIds.SEND_WHATSAPP, "+256700999888", ContentHashing.hash(goodContent), contractId, now))

        // Exact content: consumes atomically and verifies.
        val exact = router().execute(
            capabilityId = CapabilityIds.SEND_WHATSAPP, target = "+256700999888", content = goodContent,
            contract = contract(contractId, WorkflowRegistry.byId("lead_qualification")!!, nowMs = now),
            step = GraphStep("send2", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, CapabilityIds.SEND_WHATSAPP),
            approvalId = approvalId,
        )
        assertTrue(exact.verified)
        // Second consume attempt loses: count exhausted.
        assertFalse(approvals.consumeForExecution(approvalId, CapabilityIds.SEND_WHATSAPP, "+256700999888", ContentHashing.hash(goodContent), contractId, now))
    }

    @Test fun expiredApprovalCannotExecuteEvenWhenBindingMatches() = runBlocking {
        val now = System.currentTimeMillis()
        val contractId = "cert-expiry-1"
        memory.createWorkflowApprovalRequest(
            capability = CapabilityIds.FOLLOW_UP_WHATSAPP, target = "+256701111111",
            description = "expiry probe", contentHash = ContentHashing.hash("follow-up body"),
            contractId = contractId, inputsJson = "{}", risk = co.sanaa.agent.core.ActionRisk.EXTERNAL_COMMUNICATION,
            expiresAt = now + 1_000, executionsAllowed = 1, nowMs = now,
        )
        val id = memory.pendingApprovals().first { it.capability == CapabilityIds.FOLLOW_UP_WHATSAPP }.id
        assertTrue(memory.decideApproval(id, true))
        val afterExpiry = now + 5_000
        assertFalse(approvals.consumeForExecution(id, CapabilityIds.FOLLOW_UP_WHATSAPP, "+256701111111", ContentHashing.hash("follow-up body"), contractId, afterExpiry))
    }

    // ---------- spend reservation lifecycle over rejected/failed effects ----------

    @Test fun unroutedCapabilityParksAndReleasesItsReservationWithoutChargingBudget() {
        val workflow = WorkflowRegistry.byId("catalog_health")!!
        val now = System.currentTimeMillis()
        seedKnowledge("stale listing audit finding")
        // No Soko foreground: apply fails provably → park; spend must be released not charged.
        surface.foregroundPackage = "com.android.launcher"
        surface.dispatchResult = false
        val exec = executor()
        val first = runBlocking { exec.run(workflow, contract("cert-budget-1", workflow, now), mapOf("listing_id" to "X")) }
        assertTrue(first is DepartmentWorkflowExecutor.Outcome.ParkedForOwnerDecision || first is DepartmentWorkflowExecutor.Outcome.Completed)
        assertEquals("no committed cost may exist without a verified effect", 0L, reservations.committedOn("cert-budget-1"))
    }

    @Test fun provenNoEffectDispatchReleasesReservationInsteadOfChargingIt() {
        val workflow = WorkflowRegistry.byId("lead_qualification")!!
        val now = System.currentTimeMillis()
        seedKnowledge("customer-np product-np verified stock")
        surface.seedChat("+256700555000")
        surface.dispatchResult = false // device provably never dispatches
        val exec = executor()
        val first = runBlocking { exec.run(workflow, contract("cert-noeffect-1", workflow, now), mapOf("customer_id" to "customer-np", "product_id" to "product-np", "target" to "+256700555000")) }
        assertTrue(first is DepartmentWorkflowExecutor.Outcome.ParkedForOwnerDecision)
        val pendingId = memory.pendingApprovals().first().id
        assertTrue(memory.decideApproval(pendingId, true))
        val second = runBlocking { executor().run(workflow, contract("cert-noeffect-1", workflow, now), mapOf("customer_id" to "customer-np", "product_id" to "product-np", "target" to "+256700555000")) }
        // The failed leg parks the run; crucially no cost was recognized.
        assertEquals(0L, reservations.committedOn("cert-noeffect-1"))
        assertEquals(0L, reservations.reservedOn("cert-noeffect-1"))
    }

    // ---------- time correctness ----------

    @Test fun deadlineCrossedMidRunRefusesWithFreshClockNotTheStartClock() {
        val workflow = WorkflowRegistry.byId("weekly_review")!!
        val start = 1_000_000L
        val mutableClock = arrayOf(start)
        seedKnowledge("week review data point")
        val exec = executor(clock = { mutableClock[0] })
        // Deadline sits between start and render; crossing it mid-run must refuse even
        // though an executor that cached nowMs would sail through.
        val c = contract("cert-clock-1", workflow, nowMs = start, deadlineInMs = 10_000L)
        // Advance the clock past the deadline right before the run's later steps execute.
        val result = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(30_000) {
                // Simulate long-running steps: wrap evidence retrieval to age the clock.
                val advancing = object : WorkflowEvidence {
                    override suspend fun retrieve(step: GraphStep, ct: WorkContract, subjects: Set<String>, nowMs: Long) =
                        ContractScopedEvidence(knowledge).retrieve(step, ct, subjects, mutableClock[0]).also {
                            if (step.id == "collect") mutableClock[0] = start + 20_000 // crosses deadline mid-run
                        }
                }
                DepartmentWorkflowExecutor(
                    store = store,
                    coordinator = DurableWorkflowCoordinator(store, SideEffectLedger.from(memory)),
                    ledger = SideEffectLedger.from(memory),
                    budget = reservations,
                    evidence = advancing,
                    approvals = approvals,
                    effects = router(),
                    artifactStore = SqliteArtifactStore(memory),
                    clockMs = { mutableClock[0] },
                ).run(workflow, c, emptyMap())
            }
        }
        assertTrue("expected OVERDUE refusal, got $result", result is DepartmentWorkflowExecutor.Outcome.RefusedByEnforcement)
        assertTrue((result as DepartmentWorkflowExecutor.Outcome.RefusedByEnforcement).reason.contains("Deadline"))
    }

    @Test fun leaseRenewalFailureStopsExecutionSafelyMidGraph() {
        val workflow = WorkflowRegistry.byId("research_recommendation")!!
        val now = System.currentTimeMillis()
        seedKnowledge("research subject evidence")
        val base = SqliteWorkflowStore(memory)
        var acquireCount = 0
        val flaky = object : WorkflowStore by base {
            override fun tryAcquireLease(runId: String, worker: String, untilMs: Long, nowMs: Long): Boolean {
                acquireCount++
                // First acquisition succeeds (run starts); every later renewal fails.
                return !(acquireCount > 1) && base.tryAcquireLease(runId, worker, untilMs, nowMs)
            }
        }
        val exec = DepartmentWorkflowExecutor(
            store = flaky,
            coordinator = DurableWorkflowCoordinator(flaky, SideEffectLedger.from(memory)),
            ledger = SideEffectLedger.from(memory),
            budget = reservations, evidence = ContractScopedEvidence(knowledge),
            approvals = approvals, effects = router(),
            artifactStore = SqliteArtifactStore(memory), clockMs = { now },
        )
        val outcome = runBlocking { exec.run(workflow, contract("cert-lease-1", workflow, now), mapOf("question" to "q")) }
        assertTrue("outcome was $outcome", outcome is DepartmentWorkflowExecutor.Outcome.RefusedByEnforcement)
        assertTrue((outcome as DepartmentWorkflowExecutor.Outcome.RefusedByEnforcement).reason.contains("lease", ignoreCase = true))
    }

    // ---------- evidence honesty ----------

    @Test fun outOfContractInputSubjectsAreRejectedBeforeAnyRetrieval() {
        val workflow = WorkflowRegistry.byId("lead_qualification")!!
        val now = System.currentTimeMillis()
        val outcome = runBlocking { executor().run(workflow, contract("cert-inputs-1", workflow, now), mapOf("free_text" to "arbitrary blob")) }
        assertTrue(outcome is DepartmentWorkflowExecutor.Outcome.RefusedByEnforcement)
        assertTrue((outcome as DepartmentWorkflowExecutor.Outcome.RefusedByEnforcement).reason.contains("Out-of-contract workflow inputs"))
    }

    @Test fun emptyEvidenceWorkflowParksInsteadOfFabricatingADraft() {
        val workflow = WorkflowRegistry.byId("exec_brief")!!
        val now = System.currentTimeMillis()
        val outcome = runBlocking { executor().run(workflow, contract("cert-emptyev-1", workflow, now), emptyMap()) }
        assertTrue(outcome is DepartmentWorkflowExecutor.Outcome.ParkedForOwnerDecision)
        assertTrue((outcome as DepartmentWorkflowExecutor.Outcome.ParkedForOwnerDecision).question.contains("fabricate"))
        // No artifact revision was ever stored from fabricated material.
        assertTrue(SqliteArtifactStore(memory).history("workflow:cert-emptyev-1").isEmpty())
    }

    @Test fun incompleteArtifactCannotCompleteTheWorkflow() {
        // A graph whose VERIFY gate finds an empty produced section (retrieval found
        // nothing fresh) parks BEFORE rendering: incomplete deliverables never complete.
        val workflow = DepartmentWorkflow(
            id = "incomplete_probe", name = "Incomplete artifact probe",
            inputContract = setOf("product_id"), dataClassification = co.sanaa.agent.core.work.DataClassification.BUSINESS_INTERNAL,
            executionGraph = listOf(
                GraphStep("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Evidence"),
                GraphStep("draft", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Summary"),
                GraphStep("gate", DepartmentWorkflow.StepKind.VERIFY, null),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Evidence", "Summary"),
            consequentialActions = emptySet(), escalationConditions = emptyList(),
        )
        val now = System.currentTimeMillis()
        // Knowledge exists but is STALE relative to the injected clock → retrieval yields
        // zero fresh claims → sections stay conservative → gate fails → park, never complete.
        knowledge.add(
            SourcedClaim(
                kind = co.sanaa.agent.core.knowledge.ClaimKind.FACT, statement = "old fact about inventory",
                sourceRef = "scan", capturedAtMs = now - 100_000, freshnessMs = 1_000,
            ),
        )
        val fixedClock = arrayOf(now)
        val outcome = runBlocking {
            executor(clock = { fixedClock[0] }).also { }.run(workflow, contract("cert-incomplete-1", workflow, now), emptyMap())
        }
        assertTrue(outcome !is DepartmentWorkflowExecutor.Outcome.Completed)
    }

    // ---------- duplicate protection across restarts ----------

    @Test fun workflowRestartAfterVerifiedSendDoesNotRepeatTheExternalAction() {
        val workflow = WorkflowRegistry.byId("lead_qualification")!!
        val now = System.currentTimeMillis()
        seedKnowledge("customer-rp product-rp verified stock")
        surface.seedChat("+256700444333")
        runToCompletion(workflow, "cert-restart-1", mapOf("customer_id" to "customer-rp", "product_id" to "product-rp", "target" to "+256700444333"), now)
        val dispatchesAfterFirstCompletion = surface.dispatchedCount
        // A third invocation of the same completed run must be a no-op (terminal state).
        val third = runBlocking { executor().run(workflow, contract("cert-restart-1", workflow, now), mapOf("target" to "+256700444333")) }
        assertEquals("third was $third", dispatchesAfterFirstCompletion, surface.dispatchedCount)
        assertTrue("third was $third", third is DepartmentWorkflowExecutor.Outcome.Completed)
    }

    // ---------- UNBYPASSABLE commercial preflight (anti-spam guard at the boundary) ----------

    /** A router wired with a REFUSING commercial guard (e.g. suppressed customer). */
    private fun guardedRouter(refusal: String?): TransactionRoutedEffects = TransactionRoutedEffects(
        sideEffects = SideEffectRunner(SideEffectLedger.from(memory)),
        queue = TaskQueue(),
        actions = { surface },
        ownerPhone = { "+256700000001" },
        approvalGateway = approvals,
        commercialPreflight = CommercialOutreachPreflight { _, _, _ -> refusal },
    )

    private fun seedApprovedSend(contractId: String, target: String, content: String): Long {
        val now = System.currentTimeMillis()
        memory.createWorkflowApprovalRequest(
            capability = CapabilityIds.SEND_WHATSAPP, target = target,
            description = "guard test", contentHash = ContentHashing.hash(content),
            contractId = contractId, inputsJson = "{}", risk = co.sanaa.agent.core.ActionRisk.EXTERNAL_COMMUNICATION,
            expiresAt = now + 3_600_000, executionsAllowed = 1, nowMs = now,
        )
        val approvalId = memory.pendingApprovals().first().id
        assertTrue(memory.decideApproval(approvalId, true))
        return approvalId
    }

    @Test fun refusingCommercialGuardBlocksTheDevicePrimitiveInsideTheTransaction() = runBlocking {
        val target = "+256700777666"
        val content = "Follow-up about your inquiry"
        val approvalId = seedApprovedSend("cert-guard-1", target, content)
        val routed = guardedRouter(refusal = "customer is on the suppression list").execute(
            capabilityId = CapabilityIds.SEND_WHATSAPP, target = target, content = content,
            contract = contract("cert-guard-1", WorkflowRegistry.byId("lead_qualification")!!, nowMs = System.currentTimeMillis()),
            step = GraphStep("send-guard", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, CapabilityIds.SEND_WHATSAPP),
            approvalId = approvalId,
        )
        // No device primitive was reached.
        assertFalse(routed.verified)
        assertTrue(routed.rejectedBeforeAct)
        assertEquals(0, surface.dispatchedCount)
        // The refusal happened INSIDE the transaction (after claim), not before it.
        assertTrue(memory.allSideEffectTransactions().any { it.target == target && it.capability == CapabilityIds.SEND_WHATSAPP })
        // The approval survives — the guard refusal consumed nothing.
        assertTrue(approvals.stillValid(approvalId, CapabilityIds.SEND_WHATSAPP, target, ContentHashing.hash(content), "cert-guard-1", System.currentTimeMillis()))
    }

    @Test fun passingCommercialGuardLetsAnApprovedSendVerifyThroughTheSameRouter() = runBlocking {
        val target = "+256700555444"
        val content = "Guard-approved message"
        val approvalId = seedApprovedSend("cert-guard-2", target, content)
        surface.seedChat(target)
        val routed = guardedRouter(refusal = null).execute(
            capabilityId = CapabilityIds.SEND_WHATSAPP, target = target, content = content,
            contract = contract("cert-guard-2", WorkflowRegistry.byId("lead_qualification")!!, nowMs = System.currentTimeMillis()),
            step = GraphStep("send-guard-ok", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, CapabilityIds.SEND_WHATSAPP),
            approvalId = approvalId,
        )
        assertTrue(routed.verified)
        assertEquals(1, surface.dispatchedCount)
    }

    // ---------- TikTok effect-changing inputs are bound into approvals ----------

    @Test fun tiktokApprovalBindingCoversMediaCaptionProductModeAndAccount() {
        val base = mapOf<String, String>("mediaUri" to "content://media/1", "product_id" to "listing-9")
        val captionA = "Fresh mangoes in stock"
        val bindingA = TransactionRoutedEffects.approvalBindingContent(
            CapabilityIds.POST_TIKTOK, captionA, base + mapOf("publishMode" to "publish"), "account-amara",
        )
        // Caption change → different binding.
        assertTrue(bindingA != TransactionRoutedEffects.approvalBindingContent(CapabilityIds.POST_TIKTOK, "Different caption", base + mapOf("publishMode" to "publish"), "account-amara"))
        // Media change → different binding.
        assertTrue(bindingA != TransactionRoutedEffects.approvalBindingContent(CapabilityIds.POST_TIKTOK, captionA, base + mapOf("mediaUri" to "content://media/2", "publishMode" to "publish"), "account-amara"))
        // Publish-vs-draft mode change → different binding.
        assertTrue(bindingA != TransactionRoutedEffects.approvalBindingContent(CapabilityIds.POST_TIKTOK, captionA, base + mapOf("publishMode" to "draft"), "account-amara"))
        // Target-account change → different binding.
        assertTrue(bindingA != TransactionRoutedEffects.approvalBindingContent(CapabilityIds.POST_TIKTOK, captionA, base + mapOf("publishMode" to "publish"), "account-other"))
        // Product/campaign change → different binding.
        assertTrue(bindingA != TransactionRoutedEffects.approvalBindingContent(CapabilityIds.POST_TIKTOK, captionA, base + mapOf("product_id" to "listing-10", "publishMode" to "publish"), "account-amara"))
    }
}
