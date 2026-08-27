package co.sanaa.agent.core.commerce

import java.time.Instant
import java.time.ZoneId

/**
 * Owner-facing revenue dashboard builder (charter step 9). Every figure carries an
 * `evidence` field pointing at the durable rows behind it — the owner can always ask
 * "why this number?" and get record references, never vibes. Missing policy or missing
 * cost data renders as explicit UNKNOWN/NOT-CONFIGURED states, never as zeros that
 * masquerade as facts.
 */
class RevenueDashboard(
    private val store: RevenueStore,
    private val metrics: RevenueMetricEngine,
    private val policy: () -> CommercialPolicy?,
    /** Counts UNCERTAIN side-effect transactions (unproven effects awaiting review). */
    private val uncertainOutcomes: () -> Int = { 0 },
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    fun build(): Map<String, Any?> {
        val now = clockMs()
        val policyNow = policy()
        // Missing/invalid owner configuration is explicit and deterministic; it
        // never silently changes reporting periods with the device timezone.
        val configuredZone = policyNow?.ownerTimeZoneId?.takeIf(String::isNotBlank)
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val zone = configuredZone ?: ZoneId.of("UTC")
        val dayStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val weekStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(6)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val monthStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

        val inquiriesToday = store.inquiries(dayStart, now)
        val weeklyActiveSales = store.sales(weekStart, now, setOf(SaleEvidenceRecord.SaleState.ACTIVE))
        val monthlyProfit = metrics.profit(monthStart, now)

        // Attributed revenue over the week with per-sale evidence links.
        val attributed = weeklyActiveSales.mapNotNull { sale -> store.attributionForSale(sale.uniqueKey)?.let { sale to it } }
        val attributionBySale = attributed.associate { it.first.uniqueKey to it.second }
        val directRevenue = attributed.filter { it.second.label == AttributionLabel.DIRECT }.sumOf { it.first.amountUgx }
        val influencedRevenue = attributed.filter { it.second.label == AttributionLabel.INFLUENCED }.sumOf { it.first.amountUgx }
        // Explicit UNATTRIBUTED rows and sales with no attribution row both belong
        // here. Neither is allowed to disappear from the accounting split.
        val unattributedSales = weeklyActiveSales.filter { sale ->
            attributionBySale[sale.uniqueKey]?.label.let { it == null || it == AttributionLabel.UNATTRIBUTED }
        }
        val unattributedRevenue = unattributedSales.sumOf { it.amountUgx }
        val weeklyActiveSalesRevenue = weeklyActiveSales.sumOf { it.amountUgx }

        val funnelByStage = FunnelStage.ALL.associateWith { stage -> store.opportunities(setOf(stage)).size }
        val awaiting = store.commercialActions(state = "AWAITING_APPROVAL")
        val executed = store.commercialActions(state = "EXECUTED_VERIFIED")
        val blocked = store.commercialActions(state = "BLOCKED_POLICY")
        val experiments = store.experiments()
        val campaignTouches = store.campaignTouches(fromMs = monthStart, toMs = now)
        val deliveryObservations = store.deliveryObservations(fromMs = monthStart, toMs = now)

        return mapOf(
            // Targets (owner-configured; NOT-CONFIGURED when absent — never invented).
            "policyConfigured" to (policyNow != null),
            "reportingZone" to zone.id,
            "reportingZoneState" to if (configuredZone != null) "OWNER_CONFIGURED" else "UTC_FALLBACK_OWNER_TIMEZONE_MISSING",
            "dailyInquiryTarget" to policyNow?.dailyQualifiedInquiryTarget,
            "weeklySaleTarget" to policyNow?.weeklyVerifiedSaleTarget,

            // Figures + evidence links.
            "qualifiedInquiriesToday" to inquiriesToday.size,
            "inquiryEvidence" to inquiriesToday.map { mapOf("ref" to it.evidenceRef.take(120), "contact" to it.contactKey.take(8) + "…", "product" to it.productRef.take(60)) },
            "weeklyVerifiedSales" to weeklyActiveSales.size,
            "saleEvidence" to weeklyActiveSales.map { mapOf("ref" to it.saleRef.take(120), "kind" to it.evidenceKind.name, "amount" to it.amountUgx) },

            "attributedDirectRevenue" to directRevenue,
            "attributedInfluencedRevenue" to influencedRevenue,
            "unattributedRevenue" to unattributedRevenue,
            "weeklyActiveSalesRevenue" to weeklyActiveSalesRevenue,
            "revenueSplitReconciles" to
                (directRevenue + influencedRevenue + unattributedRevenue == weeklyActiveSalesRevenue),
            "unattributedEvidence" to unattributedSales.map { sale ->
                val attribution = attributionBySale[sale.uniqueKey]
                mapOf(
                    "sale" to sale.saleRef.take(120),
                    "amount" to sale.amountUgx,
                    "state" to (attribution?.label?.name ?: "NO_ATTRIBUTION_ROW"),
                    "rule" to (attribution?.ruleType ?: "NONE"),
                )
            },
            "attributionEvidence" to attributed.map { (sale, attr) ->
                mapOf("sale" to sale.saleRef.take(120), "label" to attr.label.name, "rule" to attr.ruleType, "confidence" to attr.confidence)
            },
            // Full durable attribution chain (bounded) for the owner drill-down.
            "attributionChain" to store.attributions(monthStart, now).map { attr ->
                mapOf("sale" to attr.saleUniqueKey.take(24), "label" to attr.label.name,
                    "rule" to attr.ruleType, "campaign" to attr.campaignRef.take(60),
                    "touch" to attr.touchRef.take(80), "windowDays" to attr.windowMs / 86_400_000)
            },
            "campaignTouchEvidence" to campaignTouches.take(100).map { touch ->
                mapOf(
                    "campaign" to touch.campaignRef.take(60),
                    "contact" to touch.contactKey.take(8) + "…",
                    "evidence" to touch.evidenceRef.take(120),
                    "occurredAt" to touch.occurredAtMs,
                )
            },
            "deliveryEvidence" to deliveryObservations.take(100).map { delivery ->
                mapOf(
                    "contact" to delivery.contactKey.take(8) + "…",
                    "contentHash" to delivery.contentHash.take(24),
                    "state" to delivery.deliveryState,
                    "observedAt" to delivery.observedAtMs,
                )
            },

            "monthlyGrossProfit" to when (monthlyProfit) {
                is RevenueMetricEngine.ProfitResult.Known -> monthlyProfit.grossProfitUgx
                is RevenueMetricEngine.ProfitResult.Unknown -> null // UNKNOWN is rendered honestly in UI
            },
            "profitState" to when (monthlyProfit) {
                is RevenueMetricEngine.ProfitResult.Known -> "KNOWN"
                is RevenueMetricEngine.ProfitResult.Unknown -> "UNKNOWN: missing ${monthlyProfit.missingComponents.joinToString(",")}"
            },
            "subscriptionAndOperatingCost" to store.costs(monthStart, now)
                .filter { it.component in setOf(CostEntry.CostComponent.SUBSCRIPTION, CostEntry.CostComponent.OPERATING_ALLOCATION) }
                .sumOf { it.amountUgx },

            "funnelByStage" to funnelByStage,
            "activeOpportunities" to store.opportunities(FunnelStage.PROGRESSION.toSet()).size,
            "plannedActions" to store.commercialActions(state = "PLANNED").size,
            "actionsAwaitingApproval" to awaiting.size,
            "awaitingApprovalEvidence" to awaiting.map { mapOf("capability" to it.capabilityId, "target" to it.target.take(40), "since" to it.createdAtMs) },
            "blockedActions" to blocked.size,
            "executedVerifiedActions" to executed.size,

            "experimentsRunning" to experiments.count { it.state == "RUNNING" },
            "experimentsStoppedLoss" to experiments.count { it.state == "STOPPED_LOSS" },
            "experimentEvidence" to experiments.take(10).map { mapOf("id" to it.id, "state" to it.state) },

            "suppressions" to store.suppressions().map { Triple(it.first.take(8) + "…", it.second.take(60), it.third) },
            "uncertainOutcomes" to uncertainOutcomes(),
        )
    }
}
