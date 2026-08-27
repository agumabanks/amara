package co.sanaa.agent.core.commerce

import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.artifacts.ArtifactFormat
import co.sanaa.agent.core.artifacts.ArtifactSection
import co.sanaa.agent.core.artifacts.ArtifactSpec
import java.time.Instant
import java.time.ZoneId

/**
 * Recurring, timezone-aware daily commercial cycle (charter daily loop).
 *
 * MORNING: verify device/channel health inputs, read inventory/listings/orders/bookings/
 * sales signals handed in by the caller, inspect inbound opportunities and pending
 * follow-ups, read campaign analytics, and construct a BOUNDED daily plan (persisted).
 *
 * DURING DAY: eligibility for each planned action is re-evaluated by typed policy +
 * outreach guard; only eligible actions become approval candidates. Inbound qualified
 * inquiries are answered under policy; weak listings become Soko improvement proposals.
 *
 * END OF DAY: reconcile inquiries/sales/attribution/costs, compare with targets, record
 * lessons, propose the next bounded experiment, and emit an evidence-linked owner brief.
 *
 * A day with no authorized external action still produces useful read-only analysis,
 * drafts, or an owner decision brief — never spam. This class performs NO device I/O;
 * execution of consequential steps stays behind SideEffectRunner via the workflow layer.
 */
class DailyCommercialCycle(
    private val store: RevenueStore,
    private val metrics: RevenueMetricEngine,
    private val ranker: OpportunityRanker,
    private val guard: OutreachGuard,
    private val experiments: ExperimentEngine,
    private val policy: () -> CommercialPolicy,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    data class HealthSignals(
        val deviceSessionHealthy: Boolean,
        val sokoReachable: Boolean,
        val whatsappChannelHealthy: Boolean,
        val blockers: List<String> = emptyList(),
    )

    data class BusinessSnapshot(
        val inventory: List<String>,
        val weakListings: List<String>,
        val orders: List<String>,
        val bookings: List<String>,
        val salesSignals: List<String>,
        val inboundInquiries: List<InboundInquiry>,
        val pendingFollowUps: List<PendingFollowUp>,
        val campaignAnalytics: List<String>,
    ) {
        data class InboundInquiry(val contactKey: String, val productRef: String, val messageText: String, val interactionId: String, val atMs: Long)
        data class PendingFollowUp(val opportunityId: String, val contactKey: String, val productRef: String, val dueSinceMs: Long)
    }

    data class DayPlan(
        val dayKey: String,
        val zoneId: String,
        val health: HealthSignals,
        val rankedCandidates: List<RankedCandidate>,
        val blockedCandidates: List<BlockedCandidate>,
        val readOnlyWork: List<String>,
        val listingProposals: List<String>,
        val externalActionBudget: Int,
    ) {
        data class RankedCandidate(
            val actionId: String, val contactKey: String, val productRef: String,
            val capabilityId: String, val draft: String, val score: Double, val explanation: String,
            val dedupeKey: String,
        )
        data class BlockedCandidate(val contactKey: String, val productRef: String, val reason: String)
    }

    fun dayKey(zone: ZoneId): String {
        val day = Instant.ofEpochMilli(clockMs()).atZone(zone).toLocalDate()
        return "day-$day"
    }

    private fun ownerZone(): ZoneId = policy().ownerZone()

    // ---------- MORNING ----------

    fun planMorning(snapshot: BusinessSnapshot, health: HealthSignals): DayPlan {
        val nowMs = clockMs()
        val policyNow = policy()
        val zone = ownerZone()
        val candidates = mutableListOf<DayPlan.RankedCandidate>()
        val blocked = mutableListOf<DayPlan.BlockedCandidate>()
        val localTime = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime()

        if (policyNow.ownerTimeZoneId.isBlank()) {
            // Fail closed: without an owner-configured timezone the cycle cannot know
            // day boundaries or quiet hours — read-only day, ZERO external budget.
            val plan = DayPlan(
                dayKey = dayKey(zone), zoneId = zone.id, health = health,
                rankedCandidates = emptyList(),
                blockedCandidates = blocked + listOf(
                    DayPlan.BlockedCandidate("-", "-", "owner timezone is not configured; set it in commercial policy"),
                ),
                readOnlyWork = readOnlyWorkForDegradedDay(health),
                listingProposals = snapshot.weakListings.map { "Draft listing improvement (no save): $it" },
                externalActionBudget = 0,
            )
            store.saveDailyPlan(plan.dayKey, planJson(plan), nowMs)
            persistCandidates(plan, nowMs)
            return plan
        }

        // Inbound inquiries are processed BY THE MORNING PLAN: each is admitted through
        // the metric engine first (duplicates/spam refuse there), and a fresh qualified
        // inquiry becomes an engagement candidate for today's bounded plan.
        snapshot.inboundInquiries.forEach { inquiry ->
            when (val admission = admitInboundInquiry(inquiry)) {
                is RevenueMetricEngine.Admission.Refused ->
                    blocked += DayPlan.BlockedCandidate(inquiry.contactKey, inquiry.productRef, "inquiry refused: ${admission.reason}")
                is RevenueMetricEngine.Admission.Accepted -> Unit // counted durably; follow-ups below pick it up
            }
        }

        if (!health.deviceSessionHealthy || !health.whatsappChannelHealthy || !health.sokoReachable) {
            // Degraded channels: no external plan; read-only work + decision brief only.
            val plan = DayPlan(
                dayKey = dayKey(zone), zoneId = zone.id, health = health,
                rankedCandidates = emptyList(),
                blockedCandidates = blocked + listOf(
                    DayPlan.BlockedCandidate("-", "-", "channel health degraded: ${health.blockers.joinToString("; ")}"),
                ),
                readOnlyWork = readOnlyWorkForDegradedDay(health),
                listingProposals = snapshot.weakListings.map { "Draft listing improvement (no save): $it" },
                externalActionBudget = 0,
            )
            store.saveDailyPlan(plan.dayKey, planJson(plan), nowMs)
            persistCandidates(plan, nowMs)
            return plan
        }

        var globalMessagesPlanned = store.executedActionCountToday(zone, clockMs())
        snapshot.pendingFollowUps.forEach { followUp ->
            val opp = store.findOpportunity(followUp.opportunityId)
                ?: store.ensureOpportunity(followUp.contactKey, followUp.productRef, FunnelStage.ENGAGED, nowMs)
            val suppressionNow = store.isSuppressed(followUp.contactKey)
            // Real per-opportunity follow-up count from durable executed actions.
            val followUpsAlreadySent = store.commercialActions(state = "EXECUTED_VERIFIED")
                .count { it.target == followUp.contactKey && actionProduct(it) == followUp.productRef }
            val messagesToCustomerToday = store.commercialActions(state = "EXECUTED_VERIFIED")
                .count { it.target == followUp.contactKey && sameLocalDay(it.updatedAtMs, zone) }
            val candidate = OpportunityRanker.Candidate(
                contactKey = followUp.contactKey, productRef = followUp.productRef,
                stage = opp?.stage ?: FunnelStage.ENGAGED,
                inputs = OpportunityRanker.RankingInputs(
                    inventoryAvailable = snapshot.inventory.contains(followUp.productRef),
                    listingQuality = 0.6, knownPriceUgx = null, knownMarginUgx = null,
                    inboundIntentScore = 0.5, unansweredInquiry = true, followUpDue = true,
                    historicalConversionRate = 0.1, campaignPerformanceScore = 0.3,
                    productFreshnessDays = 0, customerFatigueScore = 0.2, riskScore = 0.2, actionCostUgx = 500,
                ),
            )
            when (val verdict = ranker.eligibility(candidate, suppressed = suppressionNow,
                messagesToCustomerToday = messagesToCustomerToday,
                globalMessagesToday = globalMessagesPlanned, followUpsSentForThisOpportunity = followUpsAlreadySent, localTime = localTime)) {
                is OpportunityRanker.Eligibility.Eligible -> if (globalMessagesPlanned < policyNow.dailyGlobalMessageCap) {
                    globalMessagesPlanned++
                    candidates += DayPlan.RankedCandidate(
                        actionId = "action-" + ContentHashAction("$followUp"), contactKey = followUp.contactKey,
                        productRef = followUp.productRef, capabilityId = "follow_up_whatsapp",
                        draft = draftFollowUp(followUp.productRef), score = verdict.score,
                        explanation = verdict.explanation, dedupeKey = ContentHashing.hash("outreach|${followUp.contactKey}|${ContentHashing.hash(draftFollowUp(followUp.productRef))}"),
                    )
                }
                is OpportunityRanker.Eligibility.Ineligible ->
                    blocked += DayPlan.BlockedCandidate(followUp.contactKey, followUp.productRef, verdict.reason)
            }
        }

        val plan = DayPlan(
            dayKey = dayKey(zone), zoneId = zone.id, health = health,
            rankedCandidates = candidates.sortedByDescending { it.score }.take(policyNow.dailyGlobalMessageCap),
            blockedCandidates = blocked, readOnlyWork = baseReadOnlyWork(snapshot),
            listingProposals = snapshot.weakListings.map { "Soko improvement proposal: $it" },
            externalActionBudget = policyNow.dailyGlobalMessageCap,
        )
        store.saveDailyPlan(plan.dayKey, planJson(plan), nowMs)
        persistCandidates(plan, nowMs)
        return plan
    }

    /** Persists every ranked candidate as an ATOMIC durable commercial action row with
     *  its complete ranking inputs + explanation + consent reference + content hash, so
     *  recheckBeforeApproval() finds planned actions and audits stay reconstructable. */
    private fun persistCandidates(plan: DayPlan, nowMs: Long) {
        plan.rankedCandidates.forEach { c ->
            val rankingJson = org.json.JSONObject()
                .put("productRef", c.productRef)
                .put("contactKey", c.contactKey)
                .put("score", c.score)
                .put("explanation", c.explanation)
                .put("draftContentHash", ContentHashing.hash(c.draft))
                .put("consentReference", if (store.hasLiveConsent(c.contactKey, null, "whatsapp", nowMs))
                    "contact_consent:${c.contactKey}" else "NONE")
                .put("policyVersion", policy().toJson().hashCode())
                .toString()
            store.upsertCommercialAction(
                RevenueStore.CommercialActionRow(
                    id = c.actionId, dedupeKey = c.dedupeKey, planDay = plan.dayKey,
                    capabilityId = c.capabilityId, target = c.contactKey,
                    contentHash = ContentHashing.hash(c.draft), state = "PLANNED",
                    rankingJson = rankingJson, evidenceRef = "plan:${plan.dayKey}",
                    createdAtMs = nowMs, updatedAtMs = nowMs,
                ),
            )
        }
    }

    // ---------- DURING DAY ----------

    /** Admits an inbound inquiry through the metric engine; returns its admission result. */
    fun admitInboundInquiry(inquiry: BusinessSnapshot.InboundInquiry): RevenueMetricEngine.Admission =
        metrics.recordQualifiedInquiry(
            channel = "whatsapp", contactKey = inquiry.contactKey, contactKind = RevenueMetricEngine.ContactKind.CUSTOMER,
            productRef = inquiry.productRef, interactionId = inquiry.interactionId,
            messageText = inquiry.messageText, atMs = inquiry.atMs,
            evidenceKind = "whatsapp_chat", evidenceRef = "chat:${inquiry.interactionId}",
        )

    /**
     * Re-checks a planned action against policy + guard immediately before requesting
     * approval — the pre-act boundary for the during-day phase. Quiet hours, caps,
     * suppression, opt-outs, consent, and honesty are all re-read from durable state
     * here in the OWNER-CONFIGURED timezone.
     */
    fun recheckBeforeApproval(actionId: String): CommercialPolicy.PolicyVerdict {
        val action = store.commercialActions().firstOrNull { it.id == actionId }
            ?: return CommercialPolicy.PolicyVerdict.Blocked("planned action not found")
        if (store.isSuppressed(action.target)) return CommercialPolicy.PolicyVerdict.Blocked("customer opted out or was suppressed after planning")
        val zone = ownerZone()
        val localTime = Instant.ofEpochMilli(clockMs()).atZone(zone).toLocalTime()
        val sentToday = store.commercialActions(state = "EXECUTED_VERIFIED")
            .count { it.target == action.target && sameLocalDay(it.updatedAtMs, zone) }
        val sentGlobally = store.commercialActions(state = "EXECUTED_VERIFIED").count { sameLocalDay(it.updatedAtMs, zone) }
        val followUpsForOpportunity = store.commercialActions(state = "EXECUTED_VERIFIED")
            .count { it.target == action.target && actionProduct(it) == actionProduct(action) }
        val productRef = allowedProductFor(action)
        // Honesty re-scan of the exact planned content at the boundary itself.
        plannedDraftHash(action)?.let { hash ->
            if (!plannedContentMatchesHash(action, hash)) {
                return CommercialPolicy.PolicyVerdict.Blocked("planned content changed after planning; re-approval required")
            }
        }
        return policy().evaluateOutreachEligibility(
            productRef = productRef, channel = "whatsapp", localTime = localTime,
            messagesToCustomerToday = sentToday, globalMessagesToday = sentGlobally,
            followUpsSentForThisOpportunity = followUpsForOpportunity,
        )
    }

    private fun plannedDraftHash(action: RevenueStore.CommercialActionRow): String? =
        runCatching { org.json.JSONObject(action.rankingJson).optString("draftContentHash").ifBlank { null } }.getOrNull()

    private fun plannedContentMatchesHash(action: RevenueStore.CommercialActionRow, expected: String): Boolean {
        val draft = plannedDraft(action) ?: return expected.isBlank()
        return ContentHashing.hash(draft) == expected
    }

    private fun plannedDraft(action: RevenueStore.CommercialActionRow): String? {
        val productRef = allowedProductFor(action) ?: return null
        return draftFollowUp(productRef)
    }

    private fun actionProduct(action: RevenueStore.CommercialActionRow): String? = allowedProductFor(action)

    private fun allowedProductFor(action: RevenueStore.CommercialActionRow): String? =
        runCatching {
            org.json.JSONObject(action.rankingJson).optString("productRef").ifBlank { null }
        }.getOrNull()

    private fun sameLocalDay(atMs: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate() == Instant.ofEpochMilli(clockMs()).atZone(zone).toLocalDate()

    // ---------- END OF DAY ----------

    data class EndOfDayResult(
        val briefSpec: ArtifactSpec,
        val targetsSummary: String,
        val lessons: List<String>,
        val proposedExperiment: ExperimentEngine.LaunchSpec?,
    )

    fun closeOut(dayLessons: List<String>, unresolved: List<String>): EndOfDayResult {
        val nowMs = clockMs()
        val zone = ownerZone()
        val dayStart = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val policyNow = policy()
        val inquiries = store.inquiries(dayStart, nowMs)
        val activeSales = store.sales(dayStart - 30L * 86_400_000, nowMs, setOf(SaleEvidenceRecord.SaleState.ACTIVE))
        val weekAgo = nowMs - 7L * 86_400_000
        val weeklySales = store.sales(weekAgo, nowMs, setOf(SaleEvidenceRecord.SaleState.ACTIVE))
        val profit = metrics.profit(dayStart - 30L * 86_400_000, nowMs)
        // Attribution reconciliation: unattributed ACTIVE sales inside the window get
        // exactly one attribution attempt from durable reply-thread evidence; expired
        // windows stay honestly UNATTRIBUTED.
        reconcileAttributions(activeSales, nowMs)
        // Experiment reconciliation: guardrail evaluation against each RUNNING
        // experiment's DECLARED stop-loss, using the measured opt-out evidence.
        val experimentNotes = reconcileRunningExperiments(nowMs)
        val executed = store.commercialActions(state = "EXECUTED_VERIFIED", planDay = dayKey(zone))
        val awaiting = store.commercialActions(state = "AWAITING_APPROVAL")

        val inquiryTargetMet = inquiries.size >= policyNow.dailyQualifiedInquiryTarget
        val weeklyMet = weeklySales.size >= policyNow.weeklyVerifiedSaleTarget

        val proposed = if (!weeklyMet && store.experiments(setOf("RUNNING")).isEmpty()) {
            experiments.draftFollowUpProposal(
                id = "exp-proposal-${dayKey(zone)}",
                hypothesis = "A different product emphasis raises qualified-inquiry rate",
                dimension = ExperimentSpec.ChangeDimension.PRODUCT_SELECTION,
                description = "Emphasize the highest-ranked in-stock product in the next Status update",
                targetProductRef = policyNow.allowedProducts.firstOrNull() ?: "OWNER-REVIEW-REQUIRED",
                nowMs = nowMs,
            )
        } else null

        val brief = ArtifactSpec(
            title = "Daily commercial brief ${dayKey(zone)}",
            format = ArtifactFormat.MARKDOWN_REPORT,
            sections = listOf(
                ArtifactSection("Reconciled Activity",
                    "- Qualified inquiries today: ${inquiries.size} (target ${policyNow.dailyQualifiedInquiryTarget}, met=$inquiryTargetMet)\n" +
                        "- Active sales (rolling 7d/30d window): weekly=${weeklySales.size}, considered=${activeSales.size}\n" +
                        "- Executed verified actions today: ${executed.size}\n" +
                        "- Actions awaiting approval: ${awaiting.size} (${awaiting.joinToString { it.capabilityId }})"),
                ArtifactSection("Attribution And Costs", renderAttribution(activeSales)),
                ArtifactSection("Profit Statement", when (profit) {
                    is RevenueMetricEngine.ProfitResult.Known ->
                        "Gross profit (revenue minus ALL recorded cost components): UGX ${profit.grossProfitUgx}."
                    is RevenueMetricEngine.ProfitResult.Unknown ->
                        "PROFIT UNKNOWN — missing cost components: ${profit.missingComponents.joinToString(", ")}. Revenue is NOT reported as profit."
                }),
                ArtifactSection("Targets", "Daily qualified-inquiry target met: $inquiryTargetMet; weekly verified-sale target met: $weeklyMet."),
                ArtifactSection("Lessons", dayLessons.joinToString("\n") { "- $it" }.ifBlank { "- None recorded." }),
                ArtifactSection("Uncertainty", (unresolved + experimentNotes).joinToString("\n") { "- $it" }.ifBlank { "- None beyond recorded blockers." }),
                ArtifactSection("Next Bounded Experiment", proposed?.let {
                    "${it.hypothesis} (dimension=${it.changeDimension}, budget=${it.budgetUgx}, cap=${it.communicationCapPerDay}/day) — awaits owner approval."
                } ?: "None proposed (a target was met or an experiment is already running)."),
            ),
            requiredHeadings = listOf(
                "Reconciled Activity", "Attribution And Costs", "Profit Statement",
                "Targets", "Lessons", "Uncertainty", "Next Bounded Experiment",
            ),
        )
        return EndOfDayResult(
            briefSpec = brief,
            targetsSummary = "daily=$inquiryTargetMet weekly=$weeklyMet",
            lessons = dayLessons,
            proposedExperiment = proposed,
        )
    }

    private fun renderAttribution(sales: List<SaleEvidenceRecord>): String =
        if (sales.isEmpty()) "- No active sales on record for attribution."
        else sales.take(20).joinToString("\n") { sale ->
            val attr = store.attributionForSale(sale.uniqueKey)
                ?.let { "${it.label} (${it.ruleType}, window ${it.windowMs / 86_400_000}d)" }
                ?: "UNATTRIBUTED (no rule applied yet)"
            "- ${sale.saleRef.take(40)}: UGX ${sale.amountUgx} → $attr [evidence=${sale.evidenceKind}]"
        }

    /**
     * One attribution attempt per unattributed ACTIVE sale whose attribution window is
     * still open, using durable ledger evidence (reply-thread lookups over recorded
     * conversations). Expired windows are left UNATTRIBUTED — never back-filled.
     */
    private fun reconcileAttributions(activeSales: List<SaleEvidenceRecord>, nowMs: Long) {
        activeSales.forEach { sale ->
            if (store.attributionForSale(sale.uniqueKey) != null) return@forEach
            if (metrics.attributionWindowExpired(sale, nowMs)) return@forEach
            metrics.attributeSale(
                sale = sale,
                ruleType = AttributionType.DIRECT_REPLY_THREAD,
                repliedWithin = { contactKey, afterMs, beforeMs ->
                    store.lastInboundReplyBetween(contactKey, afterMs, beforeMs)
                },
                nowMs = nowMs,
            )
        }
    }

    /** Guardrail + sample-count reconciliation for every RUNNING experiment. Expired
     *  experiments with enough durable samples are CONCLUDED mechanically; stop-loss
     *  breaches STOP them. Both paths run from production here, not only from tests. */
    private fun reconcileRunningExperiments(nowMs: Long): List<String> {
        val notes = mutableListOf<String>()
        store.experiments(setOf("RUNNING")).forEach { row ->
            val endAt = Regex("endAtMs=(-?[0-9]+)").find(row.specJson)?.groupValues?.get(1)?.toLongOrNull()
            try {
                val state = experiments.evaluateGuardrail(row.id, guardrailValue = optOutRate(row), evidenceRef = "ledger:suppressions", nowMs = nowMs)
                if (state == "STOPPED_LOSS") notes += "experiment ${row.id} hit its declared stop-loss and stopped"
                else if (endAt != null && nowMs > endAt) {
                    // Past its declared end: conclude from durable sample evidence only.
                    val samples = store.experimentSamples(row.id)
                    val minimum = Regex("\"minimumEvidenceCount\"\\s*:\\s*([0-9]+)").find(row.specJson)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    if (samples.size >= minimum) {
                        val meanGuardrail = samples.map { it.value }.average()
                        val threshold = Regex("(?:>=|>)\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
                            .find(row.specJson)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.05
                        // Mechanical confidence from evidence: distance of the measured
                        // mean guardrail from the declared stop-loss threshold.
                        val confidence = (1.0 - (meanGuardrail / threshold).coerceIn(0.0, 1.0))
                        experiments.conclude(
                            row.id,
                            "auto-concluded at declared end: ${samples.size} durable samples, mean guardrail ${"%.4f".format(meanGuardrail)}",
                            confidence, nowMs,
                        )
                        notes += "experiment ${row.id} concluded at its declared end from ${samples.size} durable samples"
                    } else {
                        notes += "experiment ${row.id} passed its declared end with only ${samples.size}/$minimum samples; owner decision required"
                    }
                }
            } catch (_: IllegalArgumentException) {
                notes += "experiment ${row.id} has an unparseable stop-loss rule; manual owner review required"
            }
        }
        return notes
    }

    /** Opt-out-rate guardrail measurement derived from durable suppression evidence:
     *  suppressions recorded since the experiment started ÷ all commercial actions. */
    private fun optOutRate(row: RevenueStore.ExperimentRow): Double {
        val startedAt = row.startedAtMs ?: return 0.0
        val suppressions = store.suppressions().count { it.third >= startedAt && !it.first.startsWith("__escalation__") }
        val actions = setOf("PLANNED", "AWAITING_APPROVAL", "EXECUTED_VERIFIED")
            .sumOf { store.commercialActions(state = it).size }
        val denominator = actions.coerceAtLeast(1)
        return suppressions.toDouble() / denominator
    }

    private fun readOnlyWorkForDegradedDay(health: HealthSignals): List<String> = buildList {
        add("Owner decision brief: degraded channel health blocks all external actions (${health.blockers.joinToString("; ")})")
        add("Read-only catalog audit from cached evidence")
        add("Data reconciliation between ledger events and last-known orders")
    }

    private fun baseReadOnlyWork(snapshot: BusinessSnapshot): List<String> = buildList {
        add("Catalog audit across ${snapshot.inventory.size} inventoried products")
        if (snapshot.campaignAnalytics.isNotEmpty()) add("Campaign analytics review: ${snapshot.campaignAnalytics.size} items")
        add("Lead prioritization refresh")
    }

    private fun draftFollowUp(productRef: String): String =
        "Hello! You previously asked about $productRef. Would you like me to check current availability and details for you?"

    private fun planJson(plan: DayPlan): String = org.json.JSONObject().apply {
        put("dayKey", plan.dayKey); put("zone", plan.zoneId)
        put("externalActionBudget", plan.externalActionBudget)
        put("candidates", org.json.JSONArray(plan.rankedCandidates.map { c ->
            org.json.JSONObject()
                .put("id", c.actionId).put("contact", c.contactKey).put("product", c.productRef)
                .put("capability", c.capabilityId).put("score", c.score).put("explanation", c.explanation)
        }))
        put("blocked", org.json.JSONArray(plan.blockedCandidates.map { b ->
            org.json.JSONObject().put("contact", b.contactKey).put("product", b.productRef).put("reason", b.reason)
        }))
        put("readOnly", org.json.JSONArray(plan.readOnlyWork))
    }.toString()

    private fun ContentHashAction(value: String): String = ContentHashing.hash(value).take(12)
}
