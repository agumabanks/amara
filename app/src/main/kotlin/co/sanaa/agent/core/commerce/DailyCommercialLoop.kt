package co.sanaa.agent.core.commerce

import co.sanaa.agent.core.artifacts.ArtifactFormat
import co.sanaa.agent.core.artifacts.ArtifactSection
import co.sanaa.agent.core.artifacts.ArtifactSpec
import java.time.Instant
import java.time.ZoneId

/**
 * Owner-authorized bounded experiment (Revenue Operator charter). The type system makes
 * "one clearly identified change" structural: exactly one [ChangeDimension] exists per
 * spec, and volume/discount/pricing are NOT dimensions — compensating for weak
 * performance by increasing message volume or discounts cannot even be expressed.
 * Communication and spending caps must fit the owner policy passed at creation.
 */
data class ExperimentSpec(
    val id: String,
    val hypothesis: String,
    val baseline: String,
    val changeDimension: ChangeDimension,
    val changeDescription: String,
    val approvedAudience: String,
    val approvedChannel: String,
    val maxMessagesPerDay: Int,
    val maxSpendUgx: Long,
    val startCondition: String,
    val stopCondition: String,
    val successMetric: String,
    val safetyMetric: String,
    val attributionWindowMs: Long,
    val minimumEvidenceCount: Int,
    val rollbackRule: String,
) {
    enum class ChangeDimension { PRODUCT_SELECTION, TIMING, CHANNEL, CREATIVE, MESSAGE, AUDIENCE, FOLLOWUP_CADENCE }

    /** Owner policy bounds every experiment's communication and spending caps. */
    data class OwnerPolicy(val maxMessagesPerDay: Int, val maxExperimentSpendUgx: Long) {
        init {
            require(maxMessagesPerDay > 0 && maxExperimentSpendUgx >= 0) { "Policy caps must be non-trivial" }
        }
    }

    init {
        require(id.isNotBlank()) { "An experiment carries a stable id" }
        require(hypothesis.isNotBlank()) { "A hypothesis is mandatory" }
        require(baseline.isNotBlank()) { "A baseline is mandatory" }
        require(changeDescription.isNotBlank()) { "The single identified change must be described" }
        require(approvedAudience.isNotBlank() && approvedChannel.isNotBlank()) { "Audience and channel need owner approval" }
        require(maxMessagesPerDay > 0) { "A communication cap is mandatory" }
        require(maxSpendUgx >= 0) { "A spending cap is mandatory" }
        require(startCondition.isNotBlank() && stopCondition.isNotBlank()) { "Start and stop conditions are mandatory" }
        require(successMetric.isNotBlank() && safetyMetric.isNotBlank()) { "Success and safety metrics are mandatory" }
        require(attributionWindowMs > 0) { "An attribution window is mandatory" }
        require(minimumEvidenceCount >= 1) { "A minimum evidence threshold is mandatory" }
        require(rollbackRule.isNotBlank()) { "A rollback rule is mandatory" }
    }

    companion object {
        fun create(
            spec: ExperimentSpec,
            policy: OwnerPolicy,
        ): ExperimentSpec {
            require(spec.maxMessagesPerDay <= policy.maxMessagesPerDay) {
                "Communication cap exceeds owner policy (${spec.maxMessagesPerDay} > ${policy.maxMessagesPerDay})"
            }
            require(spec.maxSpendUgx <= policy.maxExperimentSpendUgx) {
                "Spending cap exceeds owner policy"
            }
            return spec
        }
    }

    fun toJson(): String = org.json.JSONObject().apply {
        put("id", id); put("hypothesis", hypothesis); put("baseline", baseline)
        put("changeDimension", changeDimension.name); put("changeDescription", changeDescription)
        put("approvedAudience", approvedAudience); put("approvedChannel", approvedChannel)
        put("maxMessagesPerDay", maxMessagesPerDay); put("maxSpendUgx", maxSpendUgx)
        put("startCondition", startCondition); put("stopCondition", stopCondition)
        put("successMetric", successMetric); put("safetyMetric", safetyMetric)
        put("attributionWindowMs", attributionWindowMs); put("minimumEvidenceCount", minimumEvidenceCount)
        put("rollbackRule", rollbackRule)
    }.toString()
}

/**
 * Daily commercial loop planner (steps 1–4, 9–10 of the charter). Observation ingestion
 * and external execution stay behind the existing device/transaction boundaries; this
 * component ranks opportunities, selects a BOUNDED set of actions, drafts content, and
 * renders the rubric-enforced end-of-day brief. It can never authorize an action by
 * itself — authorization remains the approval/standing-policy machinery's job.
 *
 * Reads exclusively from the canonical revenue ledger ([RevenueStore] + [RevenueMetricEngine]).
 */
class DailyCommercialPlanner(
    private val store: RevenueStore,
    private val metrics: RevenueMetricEngine,
    private val policy: () -> CommercialPolicy,
    private val maxDailyExternalActions: Int = 3,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    data class OpportunitySignal(
        val contactKey: String,
        val productRef: String,
        val stage: String,
        val expectedValueUgx: Double,
        val confidence: Double,
        val urgency: Int,
        val effortHours: Double,
        val costUgx: Long,
        val riskLevel: Int,
        val lastTouchAtMs: Long,
        val awaitingOwnerReply: Boolean = false,
    ) {
        init {
            require(confidence in 0.0..1.0) { "confidence within [0,1]" }
            require(expectedValueUgx >= 0 && costUgx >= 0) { "values non-negative" }
        }

        val score: Double
            get() = (expectedValueUgx * confidence + urgency * 1_000.0) /
                ((effortHours.coerceAtLeast(0.5)) * (1 + riskLevel) ) - costUgx / 10_000.0
    }

    sealed class PlannedAction {
        abstract val label: String

        /** Ready to run through the side-effect transaction once authorized. */
        data class AuthorizedCandidate(
            override val label: String,
            val capabilityId: String,
            val target: String,
            val draft: String,
            val opportunityId: String?,
            val score: Double,
        ) : PlannedAction()

        /** Needs an explicit owner decision first. */
        data class NeedsApproval(override val label: String, val question: String, val score: Double) : PlannedAction()

        /** Always allowed: read-only or pure-draft work. */
        data class ReadOnlyWork(override val label: String, val detail: String) : PlannedAction()
    }

    data class DailyPlan(val ranked: List<PlannedAction>, val externalActionBudget: Int)

    /** Steps 2–3: rank by expected value × confidence ÷ (effort × risk), then bound. */
    fun plan(signals: List<OpportunitySignal>): DailyPlan {
        val rankedSignals = signals.sortedByDescending { it.score }
        val actions = mutableListOf<PlannedAction>()
        var externalUsed = 0
        rankedSignals.forEach { signal ->
            val id = "opp-" + store.opportunityDedupeKey(signal.contactKey, signal.productRef).take(20)
            if (signal.awaitingOwnerReply) {
                actions += PlannedAction.NeedsApproval(
                    label = "Owner decision needed for ${signal.productRef} with ${signal.contactKey}",
                    question = "Approve next step on ${signal.productRef} for ${signal.contactKey}?",
                    score = signal.score,
                )
            } else if (
                externalUsed < maxDailyExternalActions &&
                signal.stage in setOf(FunnelStage.QUALIFIED_INQUIRY, FunnelStage.ENGAGED)
            ) {
                externalUsed++
                actions += PlannedAction.AuthorizedCandidate(
                    label = "Follow up on ${signal.productRef}",
                    capabilityId = "send_whatsapp",
                    target = signal.contactKey,
                    draft = draftFor(signal),
                    opportunityId = id,
                    score = signal.score,
                )
            } else {
                actions += PlannedAction.ReadOnlyWork(
                    label = "Prepare (no send): ${signal.productRef} for ${signal.contactKey}",
                    detail = "Stage ${signal.stage}; draft ready once authorized.",
                )
            }
        }
        // Safe non-idle floor: there is always legitimate read-only/draft work available.
        safeIdleWork().forEach { idle ->
            if (actions.none { it.label == idle.label }) actions += idle
        }
        return DailyPlan(ranked = actions, externalActionBudget = maxDailyExternalActions)
    }

    /**
     * Charter-mandated productive fallback when no external action is authorized:
     * catalog auditing, lead prioritization, follow-up preparation, reconciliation,
     * content drafting, experiment analysis, artifact improvement, decision briefs.
     */
    fun safeIdleWork(): List<PlannedAction.ReadOnlyWork> = listOf(
        PlannedAction.ReadOnlyWork("Catalog audit", "Compare listings against verified product facts; flag stale prices."),
        PlannedAction.ReadOnlyWork("Lead prioritization", "Re-rank qualified opportunities by expected value and staleness."),
        PlannedAction.ReadOnlyWork("Follow-up preparation", "Draft replies for unanswered qualified conversations."),
        PlannedAction.ReadOnlyWork("Data reconciliation", "Match recorded orders/bookings against ledger events."),
        PlannedAction.ReadOnlyWork("Content drafting", "Draft listing improvements from verified facts only."),
        PlannedAction.ReadOnlyWork("Experiment analysis", "Check running experiments against success/safety metrics."),
        PlannedAction.ReadOnlyWork("Artifact improvement", "Refresh briefs/reports with latest verified evidence."),
        PlannedAction.ReadOnlyWork("Owner decision brief", "Summarize decisions awaiting the owner with evidence."),
    )

    /** Drafts use only the facts handed in — never invented claims (rubric re-checks anyway). */
    private fun draftFor(signal: OpportunitySignal): String =
        buildString {
            append("Hello! Regarding ${signal.productRef}: ")
            append("would you like me to share the currently verified details?")
        }

    /** Step 10: end-of-day commercial brief as a rubric-enforced artifact spec. */
    fun endOfDayBrief(
        nowMs: Long,
        observedFacts: List<String>,
        diagnosis: List<String>,
        executedOutcomes: List<String>,
        costsAndAttribution: List<String>,
        lessons: List<String>,
        uncertainties: List<String>,
        tomorrowAdjustments: List<String>,
    ): ArtifactSpec {
        val status = dailyStatus(nowMs)
        val weekly = weeklyStatus(nowMs)
        return ArtifactSpec(
            title = "End-of-day commercial brief",
            format = ArtifactFormat.MARKDOWN_REPORT,
            sections = listOf(
                ArtifactSection("Observed", observedFacts.joinToString("\n") { "- $it" }.ifBlank { "- No new signals." }),
                ArtifactSection("Diagnosed", diagnosis.joinToString("\n") { "- $it" }.ifBlank { "- No bottlenecks identified." }),
                ArtifactSection("Actions Taken", executedOutcomes.joinToString("\n") { "- $it" }.ifBlank { "- No external actions were authorized today; safe read-only work was performed." }),
                ArtifactSection("Verified Outcomes",
                    "Daily target met: ${status.met} (${status.basis}; inquiries=${status.verifiedInquiries}, advances=${status.funnelAdvances})\n" +
                        "Weekly sales contributed: ${weekly.salesContributed} (target met: ${weekly.met})"),
                ArtifactSection("Costs And Attribution", costsAndAttribution.joinToString("\n") { "- $it" }.ifBlank { "- No costs recorded today." }),
                ArtifactSection("Lessons", lessons.joinToString("\n") { "- $it" }.ifBlank { "- None recorded." }),
                ArtifactSection("Uncertainty", uncertainties.joinToString("\n") { "- $it" }.ifBlank { "- None outstanding beyond recorded blockers." }),
                ArtifactSection("Tomorrow", tomorrowAdjustments.joinToString("\n") { "- $it" }.ifBlank { "- Continue highest-ranked plan items." }),
            ),
            requiredHeadings = listOf(
                "Observed", "Diagnosed", "Actions Taken", "Verified Outcomes",
                "Costs And Attribution", "Lessons", "Uncertainty", "Tomorrow",
            ),
        )
    }

    /** Canonical daily target status: durable inquiries + funnel evidence only. */
    fun dailyStatus(nowMs: Long): DailyStatus {
        val policyNow = policy()
        val zone = reportingZone(policyNow)
        val dayStart = Instant.ofEpochMilli(nowMs).atZone(zone)
            .toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val inquiries = store.inquiries(dayStart, nowMs).size
        var advances = 0
        store.opportunities().forEach { opp ->
            store.transitions(opp.id).forEach { t -> if (t.occurredAtMs in dayStart..nowMs) advances++ }
        }
        val met = inquiries >= policyNow.dailyQualifiedInquiryTarget || advances >= 1
        return DailyStatus(dayStart, inquiries, advances, met, "computed from the canonical revenue ledger in ${zone.id}")
    }

    /** Canonical weekly verified-sales status: ACTIVE corrected sales in the rolling 7 days. */
    fun weeklyStatus(nowMs: Long): WeeklyStatus {
        val policyNow = policy()
        val zone = reportingZone(policyNow)
        val weekStart = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val sales = store.sales(weekStart, nowMs, setOf(SaleEvidenceRecord.SaleState.ACTIVE)).size
        return WeeklyStatus(salesContributed = sales, met = sales >= policyNow.weeklyVerifiedSaleTarget)
    }

    /** Missing/invalid owner timezone never silently adopts the phone's timezone. */
    private fun reportingZone(policyNow: CommercialPolicy): ZoneId = policyNow.ownerTimeZoneId
        .takeIf(String::isNotBlank)
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.of("UTC")

    data class DailyStatus(
        val dayStartMs: Long,
        val verifiedInquiries: Int,
        val funnelAdvances: Int,
        val met: Boolean,
        val basis: String,
    )

    data class WeeklyStatus(val salesContributed: Int, val met: Boolean)

    companion object {
        fun startOfUtcDay(ms: Long): Long = ms - ms % (24 * 60 * 60 * 1_000L)
    }
}
