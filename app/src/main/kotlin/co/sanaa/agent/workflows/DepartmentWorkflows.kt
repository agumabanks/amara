package co.sanaa.agent.workflows

import co.sanaa.agent.core.work.DataClassification

/**
 * Department workflows as complete, evaluated definitions (Complete-Employee Phase E).
 * A definition binds the input contract, policy references, execution graph, quality
 * rubric, verification rules, and escalation conditions — the simulation evaluator then
 * runs representative scenarios against those rules without touching a device.
 */
data class DepartmentWorkflow(
    val id: String,
    val name: String,
    val inputContract: Set<String>,
    val dataClassification: DataClassification,
    val executionGraph: List<GraphStep>,
    val requiredReportSections: List<String>,
    val consequentialActions: Set<String>,
    val escalationConditions: List<String>,
) {
    init {
        require(inputContract.isNotEmpty()) { "Workflow $id needs an input contract" }
        require(executionGraph.isNotEmpty()) { "Workflow $id needs an execution graph" }
        require(requiredReportSections.isNotEmpty()) { "Workflow $id defines its deliverable rubric" }
    }

    enum class StepKind { OBSERVE, RETRIEVE, DRAFT, VERIFY, REQUEST_APPROVAL, EXECUTE_VERIFIED, RENDER_ARTIFACT, ESCALATE }
}

data class GraphStep(
    val id: String,
    val kind: DepartmentWorkflow.StepKind,
    val capability: String?,
    /**
     * Explicit dependency edges by step id. Empty on every step means the graph is
     * sequential (declaration order); declaring any edge switches the whole graph to
     * DAG validation: unknown ids, cycles, and unreachable steps fail topology checks.
     */
    val after: List<String> = emptyList(),
    /**
     * The deliverable heading this step produces. Null steps contribute no section.
     * Topology validation proves every workflow-required heading is produced by some
     * step, so the artifact rubric can demand the COMPLETE set without pre-filtering.
     */
    val producesSection: String? = null,
)

/** The registry of all Phase E department workflows plus the Revenue Operator suite. */
object WorkflowRegistry {

    fun all(): List<DepartmentWorkflow> = listOf(
        DepartmentWorkflow(
            id = "exec_brief", name = "Executive daily/weekly brief",
            inputContract = setOf("period", "sections"),
            dataClassification = DataClassification.BUSINESS_INTERNAL,
            executionGraph = listOf(
                GraphStep("observe", DepartmentWorkflow.StepKind.OBSERVE, "read_soko_dashboard", producesSection = "Observation"),
                GraphStep("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Evidence"),
                GraphStep("draft", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Summary"),
                GraphStep("verify", DepartmentWorkflow.StepKind.VERIFY, null),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Observation", "Evidence", "Summary"),
            consequentialActions = emptySet(),
            escalationConditions = listOf("Any KPI contradicts stored facts"),
        ),
        DepartmentWorkflow(
            id = "meeting_prep", name = "Meeting preparation and follow-through",
            inputContract = setOf("meeting_id", "attendees"),
            dataClassification = DataClassification.BUSINESS_INTERNAL,
            executionGraph = listOf(
                GraphStep("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Background"),
                GraphStep("draft", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Agenda"),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
                GraphStep("approve_followup", DepartmentWorkflow.StepKind.REQUEST_APPROVAL, "send_whatsapp", producesSection = "Actions"),
                GraphStep("followup", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, "send_whatsapp"),
                GraphStep("verify_followup", DepartmentWorkflow.StepKind.VERIFY, null),
            ),
            requiredReportSections = listOf("Agenda", "Background", "Actions"),
            consequentialActions = setOf("send_whatsapp"),
            escalationConditions = listOf("Attendee contact missing"),
        ),
        DepartmentWorkflow(
            id = "lead_qualification", name = "Lead qualification and approved follow-up",
            inputContract = setOf("customer_id", "product_id", "target"),
            dataClassification = DataClassification.CUSTOMER_DATA,
            executionGraph = listOf(
                GraphStep("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Evidence"),
                GraphStep("classify", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Qualified Leads"),
                GraphStep("approval", DepartmentWorkflow.StepKind.REQUEST_APPROVAL, "send_whatsapp", producesSection = "Drafts Awaiting Approval"),
                GraphStep("send", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, "send_whatsapp", producesSection = "Delivery"),
                GraphStep("verify_send", DepartmentWorkflow.StepKind.VERIFY, null),
            ),
            requiredReportSections = listOf("Evidence", "Qualified Leads", "Drafts Awaiting Approval", "Delivery"),
            consequentialActions = setOf("send_whatsapp"),
            escalationConditions = listOf("Customer asks for refund or discount beyond policy"),
        ),
        DepartmentWorkflow(
            id = "crm_maintenance", name = "CRM/pipeline maintenance",
            inputContract = setOf("pipeline_scope"),
            dataClassification = DataClassification.CUSTOMER_DATA,
            executionGraph = listOf(
                GraphStep("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Pipeline Health"),
                GraphStep("dedupe", DepartmentWorkflow.StepKind.DRAFT, null),
                GraphStep("propose", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Proposed Updates"),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Pipeline Health", "Proposed Updates"),
            consequentialActions = emptySet(),
            escalationConditions = listOf("Duplicate customer identity ambiguity"),
        ),
        DepartmentWorkflow(
            id = "catalog_health", name = "Catalog health and approved listing improvement",
            inputContract = setOf("listing_id", "product_id", "target"),
            dataClassification = DataClassification.BUSINESS_INTERNAL,
            executionGraph = listOf(
                GraphStep("scan", DepartmentWorkflow.StepKind.OBSERVE, "scan_soko_inventory", producesSection = "Findings"),
                GraphStep("audit", DepartmentWorkflow.StepKind.OBSERVE, "audit_soko_services"),
                GraphStep("propose", DepartmentWorkflow.StepKind.DRAFT, "propose_soko_edit", producesSection = "Proposed Changes"),
                GraphStep("await_approval", DepartmentWorkflow.StepKind.REQUEST_APPROVAL, "apply_soko_edit", producesSection = "Approved Changes Applied"),
                GraphStep("verify_before_apply", DepartmentWorkflow.StepKind.VERIFY, null),
                GraphStep("apply", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, "apply_soko_edit"),
                GraphStep("verify_apply", DepartmentWorkflow.StepKind.VERIFY, null),
            ),
            requiredReportSections = listOf("Findings", "Proposed Changes", "Approved Changes Applied"),
            consequentialActions = setOf("apply_soko_edit"),
            escalationConditions = listOf("Save result uncertain"),
        ),
        DepartmentWorkflow(
            id = "booking_exceptions", name = "Booking/order exception handling",
            inputContract = setOf("order_id", "window"),
            dataClassification = DataClassification.CUSTOMER_DATA,
            executionGraph = listOf(
                GraphStep("observe", DepartmentWorkflow.StepKind.OBSERVE, "scan_soko_bookings", producesSection = "Exceptions"),
                GraphStep("triage", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Owner Actions Needed"),
                GraphStep("escalate", DepartmentWorkflow.StepKind.ESCALATE, "notify_owner_whatsapp"),
                GraphStep("approve_cancel", DepartmentWorkflow.StepKind.REQUEST_APPROVAL, "cancel_soko_booking"),
                GraphStep("cancel", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, "cancel_soko_booking"),
                GraphStep("verify_cancellation", DepartmentWorkflow.StepKind.VERIFY, null),
            ),
            requiredReportSections = listOf("Exceptions", "Owner Actions Needed"),
            consequentialActions = setOf("cancel_soko_booking"),
            escalationConditions = listOf("Cancellation requested", "Payment dispute"),
        ),
        DepartmentWorkflow(
            id = "campaign", name = "Campaign planning through monitoring",
            inputContract = setOf("campaign_id", "objective", "audience"),
            dataClassification = DataClassification.BUSINESS_INTERNAL,
            executionGraph = listOf(
                GraphStep("listen", DepartmentWorkflow.StepKind.OBSERVE, null, producesSection = "Signals"),
                GraphStep("plan", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Plan"),
                GraphStep("approve", DepartmentWorkflow.StepKind.REQUEST_APPROVAL, "post_whatsapp_status"),
                GraphStep("publish_status", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, "post_whatsapp_status", producesSection = "Published Items"),
                GraphStep("approve_tiktok", DepartmentWorkflow.StepKind.REQUEST_APPROVAL, "post_tiktok"),
                GraphStep("publish_tiktok", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, "post_tiktok"),
                GraphStep("verify_publications", DepartmentWorkflow.StepKind.VERIFY, null),
                GraphStep("monitor", DepartmentWorkflow.StepKind.OBSERVE, null, producesSection = "Early Signals"),
            ),
            requiredReportSections = listOf("Signals", "Plan", "Published Items", "Early Signals"),
            consequentialActions = setOf("post_whatsapp_status", "post_tiktok"),
            escalationConditions = listOf("Negative reply spike"),
        ),
        DepartmentWorkflow(
            id = "weekly_review", name = "Weekly operational review",
            inputContract = setOf("week"),
            dataClassification = DataClassification.BUSINESS_INTERNAL,
            executionGraph = listOf(
                GraphStep("collect", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Wins"),
                GraphStep("analyze", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Issues"),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Wins", "Issues"),
            consequentialActions = emptySet(),
            escalationConditions = listOf("Owner decision required beyond standing policy"),
        ),
        DepartmentWorkflow(
            id = "research_recommendation", name = "Research to recommendation",
            inputContract = setOf("question"),
            dataClassification = DataClassification.BUSINESS_INTERNAL,
            executionGraph = listOf(
                GraphStep("gather", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Evidence"),
                GraphStep("analyze", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Analysis"),
                GraphStep("recommend", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Recommendation"),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Evidence", "Analysis", "Recommendation"),
            consequentialActions = emptySet(),
            escalationConditions = listOf("Conflicting sources cannot be resolved"),
        ),
        DepartmentWorkflow(
            id = "document_reconciliation", name = "Document/data reconciliation",
            inputContract = setOf("order_id", "left_source", "right_source"),
            dataClassification = DataClassification.FINANCIAL,
            executionGraph = listOf(
                GraphStep("retrieve_left", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Left Source"),
                GraphStep("retrieve_right", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Right Source"),
                GraphStep("diff", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Matches And Discrepancies"),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Left Source", "Right Source", "Matches And Discrepancies"),
            consequentialActions = emptySet(),
            escalationConditions = listOf("Unexplained financial discrepancy above threshold"),
        ),

        // ---------- Revenue Operator workflows (charter daily loop, production-routed) ----------

        DepartmentWorkflow(
            id = "inquiry_qualification", name = "Inbound inquiry to qualification",
            inputContract = setOf("customer_id", "product_id"),
            dataClassification = DataClassification.CUSTOMER_DATA,
            executionGraph = listOf(
                GraphStep("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Conversation Evidence"),
                GraphStep("classify", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Qualification Decision"),
                GraphStep("verify_gate", DepartmentWorkflow.StepKind.VERIFY, null),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Conversation Evidence", "Qualification Decision"),
            consequentialActions = emptySet(),
            escalationConditions = listOf("Customer asks for discount beyond the approved ceiling"),
        ),
        DepartmentWorkflow(
            id = "approved_follow_up", name = "Qualified lead to approved follow-up",
            inputContract = setOf("customer_id", "product_id", "target"),
            dataClassification = DataClassification.CUSTOMER_DATA,
            executionGraph = listOf(
                GraphStep("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Evidence"),
                GraphStep("draft_message", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Draft Message"),
                GraphStep("approval", DepartmentWorkflow.StepKind.REQUEST_APPROVAL, "send_whatsapp", producesSection = "Approvals"),
                GraphStep("followup", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, "send_whatsapp", producesSection = "Delivery"),
                GraphStep("verify_delivery", DepartmentWorkflow.StepKind.VERIFY, null),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Evidence", "Draft Message", "Approvals", "Delivery"),
            consequentialActions = setOf("send_whatsapp"),
            escalationConditions = listOf("Follow-up limit reached for this opportunity"),
        ),
        DepartmentWorkflow(
            id = "re_engagement", name = "Abandoned interest to bounded re-engagement",
            inputContract = setOf("customer_id", "product_id", "target"),
            dataClassification = DataClassification.CUSTOMER_DATA,
            executionGraph = listOf(
                GraphStep("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Abandonment Evidence"),
                GraphStep("draft_message", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Re-engagement Draft"),
                GraphStep("approval", DepartmentWorkflow.StepKind.REQUEST_APPROVAL, "follow_up_whatsapp", producesSection = "Approvals"),
                GraphStep("followup", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, "follow_up_whatsapp", producesSection = "Delivery"),
                GraphStep("verify_delivery", DepartmentWorkflow.StepKind.VERIFY, null),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Abandonment Evidence", "Re-engagement Draft", "Approvals", "Delivery"),
            consequentialActions = setOf("follow_up_whatsapp"),
            escalationConditions = listOf("Customer previously opted out or complained"),
        ),
        DepartmentWorkflow(
            id = "status_campaign", name = "Product selection to WhatsApp Status campaign",
            inputContract = setOf("product_id", "campaign_id"),
            dataClassification = DataClassification.BUSINESS_INTERNAL,
            executionGraph = listOf(
                GraphStep("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Verified Product Facts"),
                GraphStep("plan_status", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Status Draft"),
                GraphStep("approval", DepartmentWorkflow.StepKind.REQUEST_APPROVAL, "post_whatsapp_status", producesSection = "Publication Approval"),
                GraphStep("publish", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, "post_whatsapp_status", producesSection = "Publication"),
                GraphStep("verify_publication", DepartmentWorkflow.StepKind.VERIFY, null),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Verified Product Facts", "Status Draft", "Publication Approval", "Publication"),
            consequentialActions = setOf("post_whatsapp_status"),
            escalationConditions = listOf("Publication cannot be verified on the Status surface"),
        ),
        DepartmentWorkflow(
            id = "tiktok_campaign", name = "Product selection to TikTok draft/publication",
            inputContract = setOf("product_id", "campaign_id"),
            dataClassification = DataClassification.BUSINESS_INTERNAL,
            executionGraph = listOf(
                GraphStep("retrieve", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Verified Product Facts"),
                GraphStep("plan_post", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "TikTok Draft"),
                GraphStep("approval", DepartmentWorkflow.StepKind.REQUEST_APPROVAL, "post_tiktok", producesSection = "Publication Approval"),
                GraphStep("publish", DepartmentWorkflow.StepKind.EXECUTE_VERIFIED, "post_tiktok", producesSection = "Publication"),
                GraphStep("verify_publication", DepartmentWorkflow.StepKind.VERIFY, null),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Verified Product Facts", "TikTok Draft", "Publication Approval", "Publication"),
            consequentialActions = setOf("post_tiktok"),
            escalationConditions = listOf("Publication cannot be verified on the TikTok surface"),
        ),
        DepartmentWorkflow(
            id = "sale_attribution", name = "Sale verification to attribution",
            inputContract = setOf("order_id", "customer_id", "product_id"),
            dataClassification = DataClassification.FINANCIAL,
            executionGraph = listOf(
                GraphStep("retrieve_sales", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Sale Records"),
                GraphStep("attribute", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Attribution"),
                GraphStep("verify_gate", DepartmentWorkflow.StepKind.VERIFY, null),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Sale Records", "Attribution"),
            consequentialActions = emptySet(),
            escalationConditions = listOf("Duplicate sale evidence detected during reconciliation"),
        ),
        DepartmentWorkflow(
            id = "daily_commercial_brief", name = "Daily commercial brief",
            inputContract = setOf("period"),
            dataClassification = DataClassification.BUSINESS_INTERNAL,
            executionGraph = listOf(
                GraphStep("observe", DepartmentWorkflow.StepKind.OBSERVE, "read_soko_dashboard", producesSection = "Shop Pulse"),
                GraphStep("retrieve_ledger", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Ledger Evidence"),
                GraphStep("compose", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Brief Body"),
                GraphStep("verify_gate", DepartmentWorkflow.StepKind.VERIFY, null),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Shop Pulse", "Ledger Evidence", "Brief Body"),
            consequentialActions = emptySet(),
            escalationConditions = listOf("Daily target missed with no authorized action available"),
        ),
        DepartmentWorkflow(
            id = "weekly_revenue_review", name = "Weekly revenue review",
            inputContract = setOf("week"),
            dataClassification = DataClassification.FINANCIAL,
            executionGraph = listOf(
                GraphStep("collect", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Weekly Sales"),
                GraphStep("analyze", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Revenue Analysis"),
                GraphStep("verify_gate", DepartmentWorkflow.StepKind.VERIFY, null),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Weekly Sales", "Revenue Analysis"),
            consequentialActions = emptySet(),
            escalationConditions = listOf("Weekly verified-sale target missed"),
        ),
        DepartmentWorkflow(
            id = "monthly_profitability_review", name = "Monthly profitability review",
            inputContract = setOf("month"),
            dataClassification = DataClassification.FINANCIAL,
            executionGraph = listOf(
                GraphStep("collect_costs", DepartmentWorkflow.StepKind.RETRIEVE, null, producesSection = "Cost Components"),
                GraphStep("compute", DepartmentWorkflow.StepKind.DRAFT, null, producesSection = "Profit Statement"),
                GraphStep("verify_gate", DepartmentWorkflow.StepKind.VERIFY, null),
                GraphStep("render", DepartmentWorkflow.StepKind.RENDER_ARTIFACT, null),
            ),
            requiredReportSections = listOf("Cost Components", "Profit Statement"),
            consequentialActions = emptySet(),
            escalationConditions = listOf("Required cost components missing: profit stays UNKNOWN"),
        ),
    )

    fun byId(id: String): DepartmentWorkflow? = all().firstOrNull { it.id == id }

    fun revenueWorkflows(): List<DepartmentWorkflow> =
        all().filter { it.id in setOf(
            "inquiry_qualification", "approved_follow_up", "re_engagement", "status_campaign",
            "tiktok_campaign", "sale_attribution", "daily_commercial_brief",
            "weekly_revenue_review", "monthly_profitability_review",
        ) }
}

/** One simulated scenario run against a workflow's rules. */
data class ScenarioResult(
    val workflowId: String,
    val scenarioId: String,
    val deliverableAccepted: Boolean,
    val rubricFailures: List<String>,
    val consequentialActionVerified: Boolean,
    val criticalSafetyEvent: String?,
)

/**
 * Simulation evaluator: runs generated scenarios through the workflow's graph order,
 * deliverable rubric, and consequential-action verification rule. This is a local
 * simulation harness — it never substitutes for device evidence.
 */
object WorkflowSimulator {


    fun evaluate(workflow: DepartmentWorkflow, scenarioId: String, reportSections: List<String>, actionsVerified: Map<String, Boolean>, safetyEvent: String? = null): ScenarioResult {
        // Graph topology is validated on every evaluation so drift cannot hide.
        val topologyFailures = validateTopology(workflow)
        // Deliverable rubric: required sections present with content.
        val failures = mutableListOf<String>()
        workflow.requiredReportSections.forEach { required ->
            val body = reportSections.firstOrNull { it.startsWith(required, ignoreCase = true) }
            if (body == null) failures += "Missing deliverable section: $required"
            else if (body.length <= required.length + 1) failures += "Section '$required' has no content"
        }
        if (reportSections.any { it.isBlank() }) failures += "Blank section in deliverable"
        // Consequential actions must be verified 100% of the time.
        var verified = true
        workflow.consequentialActions.forEach { action ->
            val ok = actionsVerified[action]
            if (ok != true) { verified = false; failures += "Consequential action '$action' not verified" }
        }
        failures += topologyFailures.map { "topology: $it" }
        return ScenarioResult(
            workflowId = workflow.id,
            scenarioId = scenarioId,
            deliverableAccepted = failures.isEmpty(),
            rubricFailures = failures,
            consequentialActionVerified = verified || workflow.consequentialActions.isEmpty(),
            criticalSafetyEvent = safetyEvent ?: (if (!verified && workflow.consequentialActions.isNotEmpty()) "unverified_consequential_action" else null),
        )
    }

    /**
     * Executable-graph validation enforced before any run: every step's capability exists
     * in the catalog, consequential actions appear as EXECUTE_VERIFIED steps, approval
     * precedes approval-gated effects, a verification gate covers workflows with effects,
     * observation/retrieval precedes drafting, ids are unique, and any explicit `after`
     * dependency edges form an acyclic, fully reachable graph.
     */
    fun validateTopology(workflow: DepartmentWorkflow): List<String> {
        val failures = mutableListOf<String>()
        val positions = workflow.executionGraph.withIndex().associate { (index, step) -> step.id to index }
        if (positions.size != workflow.executionGraph.size) failures += "Duplicate step ids in ${workflow.id}"
        // Unknown capabilities anywhere in the graph fail closed.
        workflow.executionGraph.forEach { step ->
            if (step.capability != null && co.sanaa.agent.core.CapabilityCatalog.get(step.capability) == null) {
                failures += "${workflow.id} references unknown capability '${step.capability}' at step '${step.id}'"
            }
        }
        // Workflows with consequential actions must carry an explicit verification gate.
        if (workflow.consequentialActions.isNotEmpty() &&
            workflow.executionGraph.none { it.kind == DepartmentWorkflow.StepKind.VERIFY }
        ) {
            failures += "${workflow.id}: consequential actions without any VERIFY step"
        }
        workflow.consequentialActions.forEach { capabilityId ->
            val spec = co.sanaa.agent.core.CapabilityCatalog.get(capabilityId)
                ?: run { failures += "Unknown consequential capability $capabilityId"; return@forEach }
            val effectStep = workflow.executionGraph.firstOrNull {
                it.capability == capabilityId && it.kind == DepartmentWorkflow.StepKind.EXECUTE_VERIFIED
            }
            if (effectStep == null) {
                failures += "${workflow.id} has no EXECUTE_VERIFIED step for $capabilityId"
                return@forEach
            }
            val effectIndex = positions.getValue(effectStep.id)
            // Approval must precede effects that demand any authorization. There is NO
            // initiator bypass: workflow-initiated runs of even internal capabilities
            // require an explicit REQUEST_APPROVAL step so every send stays owner-bound.
            if (spec.approvalRequirement != co.sanaa.agent.core.ApprovalRequirement.NONE) {
                val approvalBefore = workflow.executionGraph.take(effectIndex).any {
                    it.kind == DepartmentWorkflow.StepKind.REQUEST_APPROVAL
                }
                if (!approvalBefore) failures += "${workflow.id}: $capabilityId executes without a preceding approval step"
            }
        }
        // Observe/retrieve must precede draft steps.
        val firstDraft = workflow.executionGraph.indexOfFirst {
            it.kind in setOf(DepartmentWorkflow.StepKind.DRAFT, DepartmentWorkflow.StepKind.EXECUTE_VERIFIED)
        }
        if (firstDraft >= 0 && workflow.executionGraph.take(firstDraft).none {
                it.kind in setOf(DepartmentWorkflow.StepKind.OBSERVE, DepartmentWorkflow.StepKind.RETRIEVE)
            }) {
            failures += "${workflow.id}: drafting begins without any observation/retrieval step"
        }
        failures += dependencyFailures(workflow)
        // Deliverable alignment: every required heading must be produced by a step, so
        // the rubric can demand the complete set and incomplete artifacts fail loudly.
        if (workflow.executionGraph.any { it.kind == DepartmentWorkflow.StepKind.RENDER_ARTIFACT }) {
            val produced = workflow.executionGraph.mapNotNull { it.producesSection }
            workflow.requiredReportSections.forEach { required ->
                if (required !in produced) {
                    failures += "${workflow.id}: required section '$required' is never produced by any step"
                }
            }
        }
        return failures.distinct()
    }

    /** DAG checks for explicit `after` edges: unknown ids, cycles, unreachable steps. */
    private fun dependencyFailures(workflow: DepartmentWorkflow): List<String> {
        val steps = workflow.executionGraph
        if (steps.none { it.after.isNotEmpty() }) return emptyList()
        val failures = mutableListOf<String>()
        val byId = steps.associateBy { it.id }
        steps.forEach { step ->
            step.after.forEach { dep ->
                if (byId[dep] == null) failures += "${workflow.id}: step '${step.id}' depends on unknown step '$dep'"
            }
        }
        // Kahn cycle detection + reachability from roots.
        val indegree = HashMap<String, Int>()
        val dependents = HashMap<String, MutableList<String>>()
        steps.forEach { s -> indegree.putIfAbsent(s.id, 0); dependents.putIfAbsent(s.id, mutableListOf()) }
        steps.forEach { s ->
            s.after.filter { it in byId }.forEach { dep ->
                indegree[s.id] = (indegree[s.id] ?: 0) + 1
                dependents.getValue(dep).add(s.id)
            }
        }
        val queue = ArrayDeque(indegree.filterValues { it == 0 }.keys)
        val reached = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            reached += id
            dependents.getValue(id).forEach { next ->
                indegree[next] = (indegree[next] ?: 1) - 1
                if (indegree.getValue(next) == 0) queue.add(next)
            }
        }
        val unreached = steps.map { it.id }.toSet() - reached
        val cyclic = unreached.filter { id -> id in byId.getValue(id).after || reachesSelf(id, byId) }
        if (cyclic.isNotEmpty()) failures += "${workflow.id}: dependency cycle among ${cyclic.sorted()}"
        (unreached - cyclic.toSet()).sorted().forEach {
            failures += "${workflow.id}: step '$it' is unreachable from graph roots"
        }
        return failures.distinct()
    }

    private fun reachesSelf(id: String, byId: Map<String, GraphStep>): Boolean {
        val stack = ArrayDeque(byId.getValue(id).after)
        val seen = mutableSetOf<String>()
        while (stack.isNotEmpty()) {
            val current = stack.removeFirst()
            if (current == id) return true
            if (seen.add(current)) byId[current]?.let { stack.addAll(it.after) }
        }
        return false
    }

    /** Generates 20 representative scenarios per workflow and evaluates them. */
    fun runSuite(workflow: DepartmentWorkflow): List<ScenarioResult> {
        val results = mutableListOf<ScenarioResult>()
        for (index in 0 until 20) {
            val happyPath = index % 4 != 3
            val sections = workflow.requiredReportSections.mapIndexed { position, section ->
                if (happyPath || position > 0) "$section: filled for case $index" else ""
            }
            val actionsVerified = workflow.consequentialActions.associateWith { happyPath }
            results += evaluate(workflow, "${workflow.id}-case-$index", sections, actionsVerified)
        }
        return results
    }
}
