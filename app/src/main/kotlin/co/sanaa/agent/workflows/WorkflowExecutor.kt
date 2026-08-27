package co.sanaa.agent.workflows

import co.sanaa.agent.core.CapabilityCatalog
import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.SideEffectLedger
import co.sanaa.agent.core.artifacts.ArtifactFormat
import co.sanaa.agent.core.artifacts.ArtifactSection
import co.sanaa.agent.core.artifacts.ArtifactSpec
import co.sanaa.agent.core.artifacts.ArtifactStore
import co.sanaa.agent.core.knowledge.SourcedClaim
import co.sanaa.agent.core.work.OccurrenceState
import co.sanaa.agent.core.work.SpendReservations
import co.sanaa.agent.core.work.DurableSpendReservations
import co.sanaa.agent.core.work.StepDispatch
import co.sanaa.agent.core.work.WorkContract
import co.sanaa.agent.core.work.WorkScheduler
import co.sanaa.agent.core.work.WorkflowStore
import java.util.concurrent.ConcurrentHashMap

/**
 * PRODUCTION workflow executor (Complete-Employee Phase E, corrected). Executes a
 * registered department workflow graph through Amara's production systems:
 *
 *  - loads a [WorkContract], validates topology before touching anything;
 *  - reads the CLOCK at every enforcement boundary (never reuses the run's start time);
 *  - RENEWS the durable lease each step and stops safely when renewal fails;
 *  - rechecks deadline, budget, approval expiry, and revocation immediately before each
 *    consequential action;
 *  - retrieves task-scoped evidence using TYPED business subjects (product/customer/
 *    listing/order/campaign ids) — unknown or out-of-contract subjects are rejected;
 *  - refuses to fabricate a meaningful draft from zero evidence (the run parks);
 *  - binds approvals to capability + exact target + normalized content hash + structured
 *    inputs + contract/workflow id + expiry + allowed execution count, and CONSUMES the
 *    approval atomically at the final pre-act boundary inside the side-effect runner;
 *    a rejected preflight leaves the approval usable unless policy says otherwise;
 *  - reserves spend BEFORE acting and moves it RESERVED→COMMITTED only after verified
 *    effect; catalog rejection or proven non-effect RELEASES the reservation;
 *  - passes the COMPLETE workflow-required heading set to the rubric (no pre-filtering),
 *    so incomplete artifacts fail/park instead of completing the workflow.
 */
class DepartmentWorkflowExecutor(
    private val store: WorkflowStore,
    private val coordinator: co.sanaa.agent.core.work.DurableWorkflowCoordinator,
    private val ledger: SideEffectLedger,
    private val budget: SpendReservations,
    private val evidence: WorkflowEvidence,
    private val approvals: WorkflowApprovals,
    private val effects: WorkflowEffectRouter,
    private val artifactStore: ArtifactStore,
    private val workerId: String = "workflow-executor",
    /** Live clock; read at every enforcement boundary — never cached across steps. */
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    sealed class Outcome {
        data class Completed(
            val runId: String,
            val sections: List<String>,
            val artifactRevisionId: Long?,
            val verifiedActions: Int,
        ) : Outcome()

        data class ParkedForOwnerDecision(val runId: String, val question: String) : Outcome()
        data class RefusedByEnforcement(val runId: String, val reason: String) : Outcome()
    }

    class EnforcementRefused(runId: String, reason: String) :
        Exception("run $runId refused by enforcement: $reason")

    fun nowMs(): Long = clockMs()

    suspend fun run(
        workflow: DepartmentWorkflow,
        contract: WorkContract,
        inputs: Map<String, String> = emptyMap(),
    ): Outcome {
        // Fail loudly on graph drift before any enforcement or external surface is touched.
        val topologyFailures = WorkflowSimulator.validateTopology(workflow)
        require(topologyFailures.isEmpty()) { "Workflow ${workflow.id} has invalid topology: ${topologyFailures.joinToString("; ")}" }

        var now = nowMs()
        if (!store.ensureRun(contract.id, contract.fingerprint(), workflow.executionGraph.size, now)) {
            return Outcome.RefusedByEnforcement(contract.id, "Durable run row could not be created")
        }
        if (!store.tryAcquireLease(contract.id, workerId, now + LEASE_MS, now)) {
            return Outcome.RefusedByEnforcement(contract.id, "Another worker holds a live lease for this run")
        }
        now = nowMs()
        // Deadline enforcement from the interval-derived scheduler semantics.
        val overdue = contract.deadlineMs != null && now > contract.deadlineMs
        if (overdue || WorkScheduler.occurrenceState(contract, null, null, now) == OccurrenceState.MISSED) {
            return refuse(contract.id, "OVERDUE", "Deadline already overrun at ${now}ms", now)
        }
        if (!budget.withinBudget(contract, now)) {
            return refuse(contract.id, "BUDGET_EXCEEDED", "Declared ceiling reached before start", now)
        }
        // Typed input validation up front: out-of-contract subject values never enter retrieval.
        val typedInputs = try {
            TypedWorkflowInputs.parse(workflow.inputContract, inputs)
        } catch (error: IllegalArgumentException) {
            return refuse(contract.id, "INVALID_INPUT", error.message ?: "invalid typed inputs", nowMs())
        }

        val sections = LinkedHashMap<String, String>()
        val claims = mutableListOf<SourcedClaim>()
        val runState = RunState()
        var verifiedActions = 0

        // Restore accumulated work from the durable checkpoint so resumed runs bind
        // approvals against IDENTICAL content (a fresh REQUEST_APPROVAL after restart
        // must match the request the owner actually decided on).
        store.run(contract.id)?.checkpointJson?.takeIf { it.isNotBlank() && it != "{}" }?.let { saved ->
            runCatching {
                val json = org.json.JSONObject(saved)
                json.optJSONObject("sections")?.let { secs ->
                    secs.keys().forEach { key -> sections[key] = secs.getString(key) }
                }
                json.optJSONArray("claims")?.let { arr ->
                    (0 until arr.length()).forEach { i ->
                        val c = arr.getJSONObject(i)
                        claims += SourcedClaim(
                            kind = co.sanaa.agent.core.knowledge.ClaimKind.valueOf(c.getString("kind")),
                            statement = c.getString("statement"),
                            sourceRef = c.getString("sourceRef"),
                            capturedAtMs = c.getLong("capturedAtMs"),
                            freshnessMs = c.getLong("freshnessMs"),
                        )
                    }
                }
            }
        }

        while (true) {
            now = nowMs() // fresh clock every step: long runs cannot ride stale time
            // Lease renewal each step; failure stops safely instead of racing another worker.
            if (!store.tryAcquireLease(contract.id, workerId, now + LEASE_MS, now)) {
                store.markDecisionRequired(contract.id, "Lease renewal failed for '$workerId'; execution stopped safely.")
                return Outcome.RefusedByEnforcement(contract.id, "Lease could not be renewed; stopped safely")
            }
            if (contract.deadlineMs != null && now > contract.deadlineMs) {
                coordinator.completeStep(
                    "run:$contract.id:deadline", false, "Deadline crossed mid-run at $now",
                    contract.id, maxOf(0, store.run(contract.id)?.stepIndex ?: 0), "{}", now,
                )
                return refuse(contract.id, "OVERDUE", "Deadline crossed during execution at ${now}ms", now)
            }
            if (!budget.withinBudget(contract, now)) {
                return refuse(contract.id, "BUDGET_EXCEEDED", "Budget ceiling reached mid-run", now)
            }
            val runRow = store.run(contract.id) ?: return Outcome.RefusedByEnforcement(contract.id, "Run row vanished")
            val stepIndex = runRow.stepIndex
            if (stepIndex >= workflow.executionGraph.size) break
            val step = workflow.executionGraph[stepIndex]
            val capabilityKey = step.capability ?: "internal:${workflow.id}:${step.id}"
            val dispatch = coordinator.dispatchNext(
                contract.id, workerId, nowMs(), workflow.executionGraph.size, capabilityKey, contract.fingerprint(),
            )
            when (dispatch) {
                is StepDispatch.UncertainCrashWindow ->
                    return Outcome.ParkedForOwnerDecision(
                        contract.id,
                        "A previous attempt of '${step.id}' reached an unprovable state; owner confirmation required.",
                    )
                StepDispatch.NothingToDo -> break
                is StepDispatch.Execute -> {
                    val stepResult = executeStep(workflow, contract, step, typedInputs, claims, sections, runState)
                    // Post-step snapshot: the checkpoint must reflect work DONE so a resumed
                    // run binds approvals against identical accumulated content.
                    val checkpointJson = checkpoint(sections, claims)
                    if (stepResult.parkedWithoutFailure) {
                        // Owner decision outstanding (e.g. pending approval): the occurrence
                        // claim stays pre-acting so the run resumes cleanly after the owner decides.
                        store.markDecisionRequired(contract.id, stepResult.evidence)
                        return Outcome.ParkedForOwnerDecision(contract.id, stepResult.evidence)
                    }
                    if (!stepResult.verified) {
                        // Reservation lifecycle was already resolved inside executeStep
                        // (released / failed-nonbillable, or left RESERVED when the
                        // effect is UNCERTAIN). Releasing again here would double-release.
                        if (!stepResult.parkedWithoutFailure) runState.failedEffects += 1
                        coordinator.completeStep(
                            dispatch.occurrenceKey, verified = false,
                            evidence = stepResult.evidence, runId = contract.id,
                            stepIndex = dispatch.stepIndex, checkpointJson = checkpointJson, nowMs = nowMs(),
                        )
                        return Outcome.ParkedForOwnerDecision(contract.id, stepResult.evidence)
                    }
                    coordinator.completeStep(
                        dispatch.occurrenceKey, verified = true,
                        evidence = stepResult.evidence, runId = contract.id,
                        stepIndex = dispatch.stepIndex, checkpointJson = checkpointJson, nowMs = nowMs(),
                    )
                    if (step.kind == DepartmentWorkflow.StepKind.EXECUTE_VERIFIED && stepResult.verified) {
                        verifiedActions++
                        runState.verifiedEffects += 1
                    }
                }
            }
        }
        // A run that still owes an owner decision is NOT complete: report the outstanding
        // question instead of claiming success over an awaiting-decision phase.
        val finalRow = store.run(contract.id)
        if (finalRow?.phase == co.sanaa.agent.core.work.RunPhase.AWAITING_DECISION) {
            return Outcome.ParkedForOwnerDecision(contract.id, finalRow.decisionQuestion.ifBlank { "Owner decision outstanding" })
        }
        if (finalRow?.phase == co.sanaa.agent.core.work.RunPhase.COMPLETED) {
            // Idempotent re-invocation of a terminal completed run: a verified no-op.
            return Outcome.Completed(contract.id, sections.keys.toList(), lastArtifactRevisionId.remove(contract.id), 0)
        }
        val completed = store.completeRun(
            contract.id,
            org.json.JSONObject(mapOf("workflow" to workflow.id, "sections" to org.json.JSONArray(sections.keys.toList()))).toString(),
            nowMs(),
        )
        if (!completed) {
            return Outcome.ParkedForOwnerDecision(contract.id, "The run could not be marked complete from phase ${finalRow?.phase}; owner review required.")
        }
        return Outcome.Completed(contract.id, sections.keys.toList(), lastArtifactRevisionId.remove(contract.id), verifiedActions)
    }

    /** Durable checkpoint: sections plus the structured claims behind them. */
    private fun checkpoint(sections: LinkedHashMap<String, String>, claims: List<SourcedClaim>): String =
        org.json.JSONObject().apply {
            put("sections", org.json.JSONObject(sections.toMap()))
            put("claims", org.json.JSONArray(claims.map { claim ->
                org.json.JSONObject().apply {
                    put("kind", claim.kind.name); put("statement", claim.statement.take(500))
                    put("sourceRef", claim.sourceRef.take(200)); put("capturedAtMs", claim.capturedAtMs)
                    put("freshnessMs", claim.freshnessMs)
                }
            }))
        }.toString().take(16_000)

    private val lastArtifactRevisionId = ConcurrentHashMap<String, Long>()

    private fun refuse(runId: String, state: String, reason: String, nowMs: Long): Outcome =
        try {
            store.markDecisionRequired(runId, "$state: $reason")
            Outcome.RefusedByEnforcement(runId, reason)
        } finally {
            recordEnforcement(runId, state, reason, nowMs)
        }

    private fun recordEnforcement(runId: String, state: String, reason: String, nowMs: Long) {
        (budget as? DurableSpendReservations)?.let { meter ->
            meter.memory.recordEnforcementState(runId, state, reason, nowMs)
        }
    }

    private data class StepOutcome(
        val verified: Boolean,
        val evidence: String,
        val approvalId: Long?,
        val parkedWithoutFailure: Boolean = false,
        val reservation: SpendReservations.Reservation? = null,
    ) {
        companion object {
            fun ok(evidence: String, approvalId: Long? = null, reservation: SpendReservations.Reservation? = null) =
                StepOutcome(true, evidence, approvalId, false, reservation)
            fun park(evidence: String) = StepOutcome(false, evidence, null, parkedWithoutFailure = true)
            fun failed(evidence: String, approvalId: Long? = null, reservation: SpendReservations.Reservation? = null) =
                StepOutcome(false, evidence, approvalId, false, reservation)
        }
    }

    /** Per-run mutable state threaded through the graph. */
    private class RunState {
        var lastApprovalId: Long? = null
        var lastApprovalContent: String? = null
        var verifiedEffects: Int = 0
        var failedEffects: Int = 0
    }

    private suspend fun executeStep(
        workflow: DepartmentWorkflow,
        contract: WorkContract,
        step: GraphStep,
        inputs: TypedWorkflowInputs,
        claims: MutableList<SourcedClaim>,
        sections: LinkedHashMap<String, String>,
        runState: RunState,
    ): StepOutcome {
        val sectionName = step.producesSection ?: step.id.replaceFirstChar { it.uppercase() }
        return when (step.kind) {
        DepartmentWorkflow.StepKind.OBSERVE, DepartmentWorkflow.StepKind.RETRIEVE -> {
            // Business-value subjects from TYPED inputs — never bare input keys.
            val subjects = inputs.subjectValues()
            runCatching { evidence.retrieve(step, contract, subjects, nowMs()) }.fold(
                onSuccess = { found ->
                    claims += found
                    sections[sectionName] =
                        if (found.isEmpty()) "No fresh evidence available; proceeding conservatively."
                        else found.joinToString("\n") { "- [${it.kind}] ${it.statement.take(160)} (source: ${it.sourceRef})" }
                    StepOutcome.ok("${step.kind}: ${found.size} scoped claims")
                },
                onFailure = { StepOutcome.failed("Scoped retrieval refused: ${it.message}") },
            )
        }
        DepartmentWorkflow.StepKind.DRAFT -> {
            // Empty-evidence runs may not fabricate meaningful drafts: they park honestly.
            if (claims.isEmpty()) {
                StepOutcome.park("No scoped evidence was retrieved; drafting would fabricate content. Owner decision required.")
            } else {
                val body = buildString {
                    appendLine("Drafted for '${contract.objective}' from ${claims.size} scoped claims.")
                    appendLine(claims.take(5).joinToString("\n") { "- ${it.statement.take(140)} (source: ${it.sourceRef})" })
                }.trim()
                sections[sectionName] = body
                StepOutcome.ok("draft composed from ${claims.size} sourced claims")
            }
        }
        DepartmentWorkflow.StepKind.VERIFY -> {
            // The gate proves the WORK so far: no failed/uncertain effects, accumulated
            // evidence/draft content exists, and every section produced by PRIOR steps
            // carries material (provenance-carrying) text. Gates that run BEFORE the
            // workflow's single effect (e.g. pre-apply catalog checks) require a clean
            // slate instead of an already-verified effect.
            val stepPosition = workflow.executionGraph.indexOfFirst { it.id == step.id }
            val effectStepsRemaining = workflow.executionGraph.drop(stepPosition.coerceAtLeast(0) + 1)
                .any { it.kind == DepartmentWorkflow.StepKind.EXECUTE_VERIFIED }
            val effectsOk = when {
                runState.failedEffects > 0 -> false
                effectStepsRemaining -> true // effects still ahead; demand only cleanliness so far
                else -> runState.verifiedEffects > 0 || workflow.consequentialActions.isEmpty()
            }
            val expectedSoFar = workflow.executionGraph.take(stepPosition.coerceAtLeast(0))
                .mapNotNull { prior -> prior.producesSection?.takeIf { prior.kind != DepartmentWorkflow.StepKind.VERIFY } }
            val missingSections = expectedSoFar.filter { required ->
                sections.entries.none { it.key.equals(required, true) && it.value.isNotBlank() && !it.value.startsWith("No fresh evidence") }
            }
            if (sections.isNotEmpty() && effectsOk && missingSections.isEmpty()) {
                StepOutcome.ok("verification gate passed (${runState.verifiedEffects} verified effects, ${sections.size} sections)")
            } else {
                StepOutcome.failed(
                    "Verification gate failed: sections=${sections.size}, " +
                        "verifiedEffects=${runState.verifiedEffects}, failedEffects=${runState.failedEffects}, " +
                        "missingOrEmpty=${missingSections.joinToString(",")}",
                )
            }
        }
        DepartmentWorkflow.StepKind.REQUEST_APPROVAL -> {
            val capability = requireNotNull(step.capability) { "approval step names its capability" }
            val spec = CapabilityCatalog.get(capability)
            if (spec == null) {
                StepOutcome.failed("Unknown capability '$capability' in approval step")
            } else {
                val target = inputs.target ?: contract.id
                val content = draftContentFor(workflow, contract, sections)
                val executionInputs = inputs.toExecutionInputs()
                try {
                    // Fresh approval bound to capability+target+binding content hash
                    // (content plus effect-changing structured inputs incl. TikTok
                    // media/mode/account)+inputs+workflow id.
                    val bindingHash = ContentHashing.hash(
                        TransactionRoutedEffects.approvalBindingContent(capability, content, executionInputs, target),
                    )
                    val id = approvals.requireFreshApproval(
                        capabilityId = capability, target = target, description = "${workflow.name}: ${spec.label}",
                        contentHash = bindingHash, contractId = contract.id,
                        inputsJson = org.json.JSONObject(executionInputs).toString(),
                        risk = spec.risk, nowMs = nowMs(),
                    )
                    runState.lastApprovalId = id
                    runState.lastApprovalContent = content
                    if (step.producesSection != null) {
                        sections[sectionName] =
                            "Fresh exact-bound approval #$id prepared for '$capability' targeting '$target' " +
                                "(binding hash ${bindingHash.take(16)}, contract ${contract.id})."
                    }
                    StepOutcome.ok("fresh exact-bound approval prepared for $capability", id)
                } catch (pending: ApprovalPendingException) {
                    StepOutcome.park("Owner approval pending for '$capability' (request ${pending.approvalId}); the run parks for a decision.")
                }
            }
        }
        DepartmentWorkflow.StepKind.EXECUTE_VERIFIED -> executeConsequential(workflow, contract, step, inputs, sections, runState, sectionName)
        DepartmentWorkflow.StepKind.RENDER_ARTIFACT -> {
            // The COMPLETE required heading set goes to the rubric — no pre-filtering to
            // whatever happens to be present. Missing material fails here and parks the run.
            // Section bodies pass through the Redactor so contact identifiers never enter
            // stored artifacts; approval bindings above use the raw content, not this view.
            val spec = ArtifactSpec(
                title = "${workflow.name} — ${contract.objective}".take(120),
                format = ArtifactFormat.MARKDOWN_REPORT,
                sections = sections.map { ArtifactSection(it.key, co.sanaa.agent.core.Redactor.redact(it.value)) },
                requiredHeadings = workflow.requiredReportSections.toList(),
            )
            try {
                val revision = artifactStore.commit("workflow:${contract.id}", spec, nowMs())
                lastArtifactRevisionId[contract.id] = revision.id
                sections["Deliverable"] = "Rendered artifact revision ${revision.id} (sha256 ${revision.renderedBytesHash.take(16)}…)"
                StepOutcome.ok("artifact revision ${revision.id} stored")
            } catch (error: IllegalArgumentException) {
                StepOutcome.failed("Artifact rejected by rubric (incomplete deliverable): ${error.message}")
            }
        }
        DepartmentWorkflow.StepKind.ESCALATE -> StepOutcome.park(
            "Escalation condition met at '${step.id}': ${(workflow.escalationConditions.firstOrNull() ?: "owner decision required")}",
        )
        }
    }

    /**
     * Consequential execution with correct ordering:
     *   recheck enforcement → reserve → hand approval binding to the transaction runner
     *   (which consumes the approval ATOMICALLY right before ACTING and validates target +
     *   normalized-content hash + count + expiry) → COMMIT on verified, RELEASE otherwise.
     */
    private suspend fun executeConsequential(
        workflow: DepartmentWorkflow,
        contract: WorkContract,
        step: GraphStep,
        inputs: TypedWorkflowInputs,
        sections: LinkedHashMap<String, String>,
        runState: RunState,
        sectionName: String,
    ): StepOutcome {
        val capability = requireNotNull(step.capability) { "consequential step names its capability" }
        val now = nowMs()
        val spec = CapabilityCatalog.get(capability)
            ?: return StepOutcome.park("Unknown capability '$capability'; refusing to act")
        // Immediate pre-act enforcement rechecks (fresh clock).
        if (!budget.withinBudget(contract, now)) {
            return StepOutcome.park("Budget enforcement blocked '${step.id}' before any action (recheck at $now)")
        }
        val boundApproval = runState.lastApprovalId
        if ((spec.approvalRequirement != co.sanaa.agent.core.ApprovalRequirement.NONE ||
                spec.allowedInitiators.contains(co.sanaa.agent.core.Initiator.AUTHORIZED_WORKFLOW)) && boundApproval == null
        ) {
            return StepOutcome.park("'$capability' executes only under a fresh exact-bound approval; none is bound.")
        }
        val content = runState.lastApprovalContent ?: draftContentFor(null, contract, sections)
        val target = inputs.target ?: contract.id

        // Reserve BEFORE touching any external surface.
        val reservation = budget.reserve(contract, perActionCostUgx, "consequential action ${step.id}", now)
        if (reservation == null) {
            return StepOutcome.park("Budget ceiling refused reservation for '${step.id}'; nothing reserved, nothing done.")
        }

        val outcome = effects.execute(
            capabilityId = capability,
            target = target,
            content = content,
            contract = contract,
            step = step,
            approvalId = boundApproval,
            inputs = inputs.toExecutionInputs(),
        )
        return when {
            outcome.verified -> {
                budget.commit(reservation, nowMs())
                if (step.producesSection != null) {
                    sections[sectionName] = (sections[sectionName]?.plus("\n") ?: "").plus(outcome.evidenceSummary)
                }
                StepOutcome.ok(outcome.evidenceSummary, boundApproval, reservation)
            }
            outcome.provenNoEffect || outcome.rejectedBeforeAct -> {
                // Catalog/schema rejection or proof that nothing happened: release, keep approval usable.
                budget.release(reservation, nowMs())
                StepOutcome.failed(outcome.evidenceSummary, boundApproval, reservation)
            }
            else -> {
                // UNCERTAIN outcome: an external effect may or may not have happened.
                // The reservation stays RESERVED (unresolved) so it keeps counting
                // against every budget ceiling until the owner reconciles it — it is
                // NEVER silently released as if it were a proven non-effect.
                StepOutcome.failed(outcome.evidenceSummary, boundApproval, reservation)
            }
        }
    }

    private fun draftContentFor(workflow: DepartmentWorkflow?, contract: WorkContract, sections: LinkedHashMap<String, String>): String =
        sections.values.lastOrNull()?.take(1_200)?.ifBlank { null }
            ?: "Planned action for ${workflow?.name ?: contract.objective}"

    companion object {
        const val LEASE_MS = 5 * 60_000L
        const val perActionCostUgx = 500L
    }
}

/**
 * Typed workflow inputs (corrective directive 5): business VALUES with explicit kinds —
 * product ids, customer ids, listing ids, order ids, campaign ids, plus the optional
 * free-form target. Unknown keys and blank values are rejected at parse time; retrieval
 * uses these actual subject values instead of bare input key strings.
 */
data class TypedWorkflowInputs(
    val productId: String?,
    val customerId: String?,
    val listingId: String?,
    val orderId: String?,
    val campaignId: String?,
    val target: String?,
    val extras: Map<String, String>,
) {
    companion object {
        private val KNOWN_KEYS = setOf("product_id", "customer_id", "listing_id", "order_id", "campaign_id", "target")

        /**
         * Parses raw inputs against the workflow's DECLARED input contract. Typed subject
         * keys are always recognized; any other key is legal only when the workflow
         * declares it. Undeclared keys are rejected — out-of-contract subjects and blobs
         * never reach retrieval or execution.
         */
        fun parse(inputContract: Set<String>, raw: Map<String, String>): TypedWorkflowInputs {
            val unknown = raw.keys - KNOWN_KEYS - inputContract - "product" - "field" - "value"
            require(unknown.isEmpty()) {
                "Out-of-contract workflow inputs: ${unknown.sorted()}; declared contract: ${inputContract.sorted()}"
            }
            fun value(key: String, aliases: Set<String> = emptySet()): String? {
                val found = (listOf(key) + aliases).firstOrNull { raw.containsKey(it) } ?: return null
                return raw[found]?.trim()?.ifBlank { null }
            }
            return TypedWorkflowInputs(
                productId = value("product_id", setOf("product")),
                customerId = value("customer_id"),
                listingId = value("listing_id"),
                orderId = value("order_id"),
                campaignId = value("campaign_id"),
                target = value("target"),
                extras = raw.filterKeys { it !in KNOWN_KEYS },
            )
        }
    }

    /** Actual subject values used as authorized retrieval filters. */
    fun subjectValues(): Set<String> =
        listOfNotNull(productId, customerId, listingId, orderId, campaignId).toSet().filter { it.isNotBlank() }.toSet()

    fun toBindingJson(): Map<String, String> = buildMap {
        productId?.let { put("product_id", it) }
        customerId?.let { put("customer_id", it) }
        listingId?.let { put("listing_id", it) }
        orderId?.let { put("order_id", it) }
        campaignId?.let { put("campaign_id", it) }
        target?.let { put("target", it) }
        putAll(extras)
    }

    fun toExecutionInputs(): Map<String, String> = toBindingJson()
}

/** Scoped evidence source for retrieval/drafting steps. */
interface WorkflowEvidence {
    suspend fun retrieve(step: GraphStep, contract: WorkContract, subjectPrefixes: Set<String>, nowMs: Long): List<SourcedClaim>
}

/** Production adapter: contract-scoped view over the runtime knowledge base. */
class ContractScopedEvidence(private val base: co.sanaa.agent.core.knowledge.KnowledgeBase) : WorkflowEvidence {
    override suspend fun retrieve(step: GraphStep, contract: WorkContract, subjectPrefixes: Set<String>, nowMs: Long): List<SourcedClaim> =
        co.sanaa.agent.core.knowledge.ScopedRetrieval(base, contract.allowedSystems).retrieve(subjectPrefixes, nowMs)
}

/**
 * Fresh-approval gateway used before any consequential execution. Approvals bind
 * capability + exact target + normalized content hash + relevant structured inputs +
 * contract/workflow id + expiry + allowed execution count.
 */
interface WorkflowApprovals {
    @Throws(ApprovalPendingException::class)
    fun requireFreshApproval(
        capabilityId: String,
        target: String,
        description: String,
        contentHash: String,
        contractId: String,
        inputsJson: String,
        risk: co.sanaa.agent.core.ActionRisk,
        nowMs: Long,
    ): Long

    /** Non-consuming validity probe (used by tests and preflight diagnostics). */
    fun stillValid(approvalId: Long, capabilityId: String, target: String, contentHash: String, contractId: String, nowMs: Long): Boolean

    /**
     * ATOMIC consume at the final pre-act boundary. Returns true exactly once per allowed
     * execution; concurrent callers can never both win.
     */
    fun consumeForExecution(approvalId: Long, capabilityId: String, target: String, contentHash: String, contractId: String, nowMs: Long): Boolean
}

/** Effect executed for one consequential step. Implementations route through the transaction runner. */
interface WorkflowEffectRouter {
    suspend fun execute(
        capabilityId: String,
        target: String,
        content: String,
        contract: WorkContract,
        step: GraphStep,
        approvalId: Long?,
        inputs: Map<String, String> = emptyMap(),
    ): ExecutedEffect
}

data class ExecutedEffect(
    val verified: Boolean,
    val evidenceSummary: String,
    /** True when the router proved nothing was dispatched (catalog/schema/preflight refusal). */
    val provenNoEffect: Boolean = false,
    /** True when authorization/validation refused before any claim or dispatch. */
    val rejectedBeforeAct: Boolean = false,
)

/** Production approval gateway over AmaraMemory's durable approval_requests table. */
class ApprovalPendingException(val approvalId: Long) :
    Exception("Fresh owner approval is still pending ($approvalId); the run parks until the owner decides.")

class AmaraApprovalGateway(private val memory: co.sanaa.agent.core.AmaraMemory) : WorkflowApprovals {

    override fun requireFreshApproval(
        capabilityId: String,
        target: String,
        description: String,
        contentHash: String,
        contractId: String,
        inputsJson: String,
        risk: co.sanaa.agent.core.ActionRisk,
        nowMs: Long,
    ): Long {
        CapabilityCatalog.get(capabilityId)
            ?: throw IllegalArgumentException("Unknown capability '$capabilityId'")
        // Exact-bound live approval?
        memory.findWorkflowApproval(capabilityId, target, contentHash, contractId, nowMs)?.let { return it.id }
        // No live approval: create a durable pending request and park the run — never self-approve.
        val id = memory.createWorkflowApprovalRequest(
            capability = capabilityId, target = target, description = description,
            contentHash = contentHash, contractId = contractId, inputsJson = inputsJson,
            risk = risk, expiresAt = nowMs + DEFAULT_APPROVAL_TTL_MS,
            executionsAllowed = 1, nowMs = nowMs,
        )
        throw ApprovalPendingException(id)
    }

    override fun stillValid(approvalId: Long, capabilityId: String, target: String, contentHash: String, contractId: String, nowMs: Long): Boolean =
        memory.workflowApprovalStillValid(approvalId, capabilityId, target, contentHash, contractId, nowMs)

    override fun consumeForExecution(approvalId: Long, capabilityId: String, target: String, contentHash: String, contractId: String, nowMs: Long): Boolean =
        memory.consumeWorkflowApprovalForExecution(approvalId, capabilityId, target, contentHash, contractId, nowMs)

    companion object {
        const val DEFAULT_APPROVAL_TTL_MS = 24 * 60 * 60 * 1_000L
    }
}
