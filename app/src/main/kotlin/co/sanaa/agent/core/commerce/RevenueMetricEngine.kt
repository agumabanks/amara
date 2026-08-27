package co.sanaa.agent.core.commerce

import co.sanaa.agent.core.ContentHashing

/** Declared attribution rules. */
enum class AttributionType { DIRECT_REPLY_THREAD, CAMPAIGN_TOUCH_WINDOW }

/**
 * Machine-enforced metric definitions (Revenue Operator charter). These functions are
 * the ONLY admission path for qualified inquiries, completed sales, attributions, cost
 * entries, and profit figures. Every refusal names the failed definition clause.
 *
 * Qualified inquiry (all clauses mandatory): unique external customer + identifiable
 * available product/service + explicit commercial interest + not owner/test/spam/
 * duplicate + evidence from an authorized source.
 *
 * Completed sale: unique order/booking/POS/payment evidence or explicit owner
 * confirmation, in a completed state, never cancelled/refunded/duplicated.
 *
 * Revenue contribution: DIRECT / INFLUENCED / UNATTRIBUTED labels with retained linkage,
 * configurable window, one attribution row per sale forever.
 *
 * Profit: attributable revenue minus known product cost, discount, channel spend,
 * transaction fees, refunds and allocated operating cost; UNKNOWN whenever any required
 * component is unavailable; revenue is NEVER presented as profit.
 */
class RevenueMetricEngine(
    private val store: RevenueStore,
    private val policy: () -> CommercialPolicy,
    /** Authorized evidence sources for inquiries (channel→source kinds). Blank = none authorized. */
    private val authorizedInquirySources: Set<String> = setOf("whatsapp_chat", "soko_order_message", "owner_forward"),
) {

    sealed class Admission {
        data class Accepted(val uniqueKey: String) : Admission()
        data class Refused(val reason: String) : Admission()
    }

    enum class ContactKind { CUSTOMER, OWNER, AUTOMATED, TEST, SPAM, DUPLICATE, UNVERIFIABLE }

    /**
     * Resolve a product for an observed customer message without guessing. An explicit
     * source-provided reference must be owner-approved. When the source cannot provide
     * one (for example a WhatsApp notification), the message must name exactly one
     * owner-approved product as a complete token/phrase. Zero or multiple matches fail
     * closed so an inquiry is never credited to an invented product.
     */
    fun resolveObservedProduct(productRef: String?, messageText: String?): String? {
        val allowed = policy().allowedProducts
        if (allowed.isEmpty()) return null
        productRef?.trim()?.takeIf { it in allowed }?.let { return it }
        if (!productRef.isNullOrBlank()) return null
        val message = messageText.orEmpty()
        val matches = allowed.filter { candidate ->
            candidate.isNotBlank() && Regex(
                "(?<![\\p{L}\\p{N}])${Regex.escape(candidate)}(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(message)
        }
        return matches.singleOrNull()
    }

    // ---------- qualified inquiry ----------

    fun recordQualifiedInquiry(
        channel: String,
        contactKey: String?,
        contactKind: ContactKind,
        productRef: String?,
        interactionId: String,
        messageText: String?,
        atMs: Long,
        evidenceKind: String,
        evidenceRef: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Admission {
        if (contactKind != ContactKind.CUSTOMER) {
            return Admission.Refused("definition requires an external customer; got ${contactKind.name}")
        }
        if (contactKind == ContactKind.DUPLICATE || contactKey.isNullOrBlank()) {
            return Admission.Refused("unique external customer identity required")
        }
        if (productRef.isNullOrBlank()) {
            return Admission.Refused("identifiable available product/service required")
        }
        val policyNow = policy()
        // Fail closed: a missing or EMPTY allowed-products list refuses every inquiry —
        // an unconfigured allow-list is never read as "every product is allowed".
        if (policyNow.allowedProducts.isEmpty() || productRef !in policyNow.allowedProducts) {
            return Admission.Refused("product '$productRef' is not owner-approved/available (allowed products must be configured first)")
        }
        val interest = messageText?.lowercase().orEmpty()
        val interestWords = listOf("price", "how much", "available", "order", "buy", "book", "deliver",
            "bei", "gani", "ipo", "nahitaji", "nitumie", "offer", "discount", "purchase")
        if (interestWords.none(interest::contains)) {
            return Admission.Refused("explicit commercial interest not present in the interaction")
        }
        if (evidenceKind.isBlank() || evidenceRef.isBlank()) {
            return Admission.Refused("durable evidence from an authorized source is mandatory")
        }
        if (evidenceKind !in authorizedInquirySources) {
            return Admission.Refused("evidence source '$evidenceKind' is not an authorized inquiry source")
        }
        // Duplicate detection: exact interaction id OR same contact+product within the
        // dedupe horizon both refuse — one inquiry per customer per product.
        val uniqueKey = ContentHashing.hash("rq-inquiry|$channel|$contactKey|$productRef|$interactionId")
        if (store.findInquiry(uniqueKey) != null) {
            return Admission.Refused("duplicate inquiry already on record")
        }
        val priorSameContactProduct = store.inquiries(atMs - DEDUPE_HORIZON_MS, atMs + 1)
            .any { it.contactKey == contactKey && it.productRef == productRef && it.interactionId != interactionId }
        if (priorSameContactProduct) {
            return Admission.Refused("duplicate interest from the same customer for the same product inside the dedupe horizon")
        }
        if (!store.insertInquiry(
                QualifiedInquiry(
                    uniqueKey = uniqueKey, channel = channel, contactKey = contactKey, productRef = productRef,
                    interactionId = interactionId, evidenceKind = evidenceKind, evidenceRef = evidenceRef,
                    confidence = 0.85, occurredAtMs = atMs,
                ),
                nowMs,
            )
        ) {
            return Admission.Refused("duplicate inquiry key raced onto the ledger")
        }
        val opp = store.ensureOpportunity(contactKey, productRef, FunnelStage.OBSERVED, atMs)
        if (opp != null) {
            // Walk exactly one legal step at a time toward QUALIFIED_INQUIRY — never a
            // multi-stage jump; every intermediate move carries its own evidence row.
            var stage = opp.stage
            while (stage != FunnelStage.QUALIFIED_INQUIRY &&
                stage != FunnelStage.LOST && stage != FunnelStage.DISQUALIFIED &&
                FunnelStage.PROGRESSION.indexOf(stage) < FunnelStage.PROGRESSION.indexOf(FunnelStage.QUALIFIED_INQUIRY)
            ) {
                val next = FunnelStage.PROGRESSION[FunnelStage.PROGRESSION.indexOf(stage) + 1]
                if (!store.recordTransition(
                        FunnelTransition(
                            opportunityId = opp.id, fromStage = stage, toStage = next,
                            occurredAtMs = atMs, source = "metric-engine", evidenceKind = evidenceKind,
                            evidenceRef = evidenceRef, confidence = 0.85,
                            reason = if (next == FunnelStage.QUALIFIED_INQUIRY)
                                "qualified inquiry admitted under the charter definition"
                            else "inquiry admission advances the funnel one step",
                        ),
                    )
                ) break
                stage = next
            }
        }
        return Admission.Accepted(uniqueKey)
    }

    // ---------- completed sale ----------

    fun recordSale(
        evidenceKind: SaleEvidenceRecord.SaleEvidenceKind,
        saleRef: String,
        contactKey: String,
        productRef: String,
        amountUgx: Long,
        atMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Admission {
        if (saleRef.isBlank()) return Admission.Refused("completed sale requires its unique source record reference")
        if (amountUgx <= 0) return Admission.Refused("a recorded sale carries its verified positive amount")
        val existingByRef = store.findSaleByRef(saleRef)
        if (existingByRef != null) return Admission.Refused("duplicate sale: source record '${saleRef.take(60)}' is already attributed")
        val uniqueKey = ContentHashing.hash("rq-sale|$evidenceKind|$saleRef")
        if (!store.insertSale(
                SaleEvidenceRecord(
                    uniqueKey = uniqueKey, evidenceKind = evidenceKind, saleRef = saleRef,
                    contactKey = contactKey, productRef = productRef, amountUgx = amountUgx,
                    state = SaleEvidenceRecord.SaleState.ACTIVE, correctionReason = "", occurredAtMs = atMs,
                ),
                nowMs,
            )
        ) {
            return Admission.Refused("duplicate sale key raced onto the ledger")
        }
        val targetStage = when (evidenceKind) {
            SaleEvidenceRecord.SaleEvidenceKind.SOKO_ORDER, SaleEvidenceRecord.SaleEvidenceKind.BOOKING -> FunnelStage.ORDER_CREATED
            SaleEvidenceRecord.SaleEvidenceKind.POS_RECEIPT,
            SaleEvidenceRecord.SaleEvidenceKind.PAYMENT_RECORD,
            SaleEvidenceRecord.SaleEvidenceKind.OWNER_CONFIRMED -> FunnelStage.SALE_VERIFIED
        }
        val opp = store.ensureOpportunity(contactKey, productRef, FunnelStage.OBSERVED, atMs)
        if (opp != null && opp.stage != targetStage && opp.stage !in setOf(FunnelStage.LOST, FunnelStage.DISQUALIFIED)) {
            var stage = opp.stage
            // A sale may legitimately arrive before intermediate funnel observations
            // (for example a POS receipt for a new walk-in customer). Advance one
            // auditable step at a time until the evidence-specific target is reached;
            // checking isForward(stage, targetStage) here would only work when the
            // target happened to be the immediately adjacent stage.
            while (
                FunnelStage.PROGRESSION.indexOf(stage) >= 0 &&
                FunnelStage.PROGRESSION.indexOf(stage) < FunnelStage.PROGRESSION.indexOf(targetStage)
            ) {
                val next = FunnelStage.PROGRESSION[FunnelStage.PROGRESSION.indexOf(stage) + 1]
                if (!store.recordTransition(
                        FunnelTransition(
                            opportunityId = opp.id, fromStage = stage, toStage = next,
                            occurredAtMs = atMs, source = "metric-engine",
                            evidenceKind = "sale:${evidenceKind.name}", evidenceRef = saleRef,
                            confidence = 0.9, reason = "sale evidence advances the funnel to $next",
                        ),
                    )
                ) break
                stage = next
            }
        }
        return Admission.Accepted(uniqueKey)
    }

    /**
     * Cancellation/refund correction: ACTIVE sales only, reason mandatory, history kept.
     *
     * ACCOUNTING METHOD (documented, single authority): REVERSE-REVENUE. A corrected
     * (CANCELLED/REFUNDED) sale leaves the ACTIVE revenue base, and the SAME amount is
     * NOT deducted again as a REFUNDS cost — that would double-count the loss. Refund
     * COST rows exist only for amounts refunded out of a sale that stays ACTIVE
     * (partial refunds), where the recognized amount is reduced by exactly the refunded
     * portion instead. Profit therefore moves once per shilling, never twice.
     */
    fun correctSale(saleUniqueKey: String, toState: SaleEvidenceRecord.SaleState, reason: String, nowMs: Long): Admission {
        val sale = store.sales(0, Long.MAX_VALUE).firstOrNull { it.uniqueKey == saleUniqueKey }
            ?: return Admission.Refused("no such sale on the ledger")
        if (toState == SaleEvidenceRecord.SaleState.ACTIVE) return Admission.Refused("corrections cannot resurrect a sale")
        if (sale.state != SaleEvidenceRecord.SaleState.ACTIVE) {
            return Admission.Refused("sale already corrected to ${sale.state.name}")
        }
        if (!store.correctSaleState(saleUniqueKey, toState, reason, nowMs)) {
            return Admission.Refused("correction lost a race; the sale state changed concurrently")
        }
        // Method B: revenue reversal only. No REFUNDS cost row is written for a
        // full cancellation/refund — the amount already left recognized revenue.
        return Admission.Accepted(saleUniqueKey)
    }

    /**
     * Partial refund of an ACTIVE sale: recognized revenue drops by EXACTLY
     * [refundedUgx] (the sale stays ACTIVE with its reduced amount). The same partial
     * amount is never also inserted as a REFUNDS cost — one movement, one effect.
     */
    fun refundPartOfSale(saleUniqueKey: String, refundedUgx: Long, reason: String, nowMs: Long): Admission {
        if (refundedUgx <= 0) return Admission.Refused("a partial refund names its positive refunded amount")
        val sale = store.sales(0, Long.MAX_VALUE).firstOrNull { it.uniqueKey == saleUniqueKey }
            ?: return Admission.Refused("no such sale on the ledger")
        if (sale.state != SaleEvidenceRecord.SaleState.ACTIVE) {
            return Admission.Refused("only ACTIVE sales take partial refunds; this sale is ${sale.state.name}")
        }
        if (refundedUgx >= sale.amountUgx) {
            return Admission.Refused("partial refund must stay below the sale amount; use correctSale for a full refund")
        }
        if (!store.reduceActiveSaleAmount(saleUniqueKey, refundedUgx, "$reason (partial refund ${refundedUgx}Ugx)", nowMs)) {
            return Admission.Refused("partial refund lost a race; the sale state changed concurrently")
        }
        return Admission.Accepted(saleUniqueKey)
    }

    // ---------- attribution ----------

    data class AttributionOutcome(val label: AttributionLabel, val revenueUgx: Long, val detail: String)

    /**
     * Attributes exactly one sale using the declared rule. One attribution row per sale:
     * re-attribution after a window expiry refuses instead of double counting.
     */
    fun attributeSale(
        sale: SaleEvidenceRecord,
        ruleType: AttributionType,
        campaignRef: String? = null,
        repliedWithin: ((contactKey: String, afterMs: Long, beforeMs: Long) -> Pair<Long, String>?)? = null,
        touchedWithin: ((campaignRef: String, contactKey: String, afterMs: Long, beforeMs: Long) -> Pair<Long, String>?)? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Admission {
        if (sale.state != SaleEvidenceRecord.SaleState.ACTIVE) {
            return Admission.Refused("only ACTIVE sales attribute; this sale is ${sale.state.name}")
        }
        if (store.attributionForSale(sale.uniqueKey) != null) {
            return Admission.Refused("sale already carries an attribution row; never counted twice")
        }
        val window = policy().attributionWindowMs
        val start = sale.occurredAtMs - window
        val outcome = when (ruleType) {
            AttributionType.DIRECT_REPLY_THREAD -> {
                val hit = repliedWithin?.invoke(sale.contactKey, start, sale.occurredAtMs)
                if (hit != null) AttributionOutcome(AttributionLabel.DIRECT, sale.amountUgx, "reply thread ${hit.second} at ${hit.first}")
                else AttributionOutcome(AttributionLabel.UNATTRIBUTED, 0, "no qualifying reply thread inside the window")
            }
            AttributionType.CAMPAIGN_TOUCH_WINDOW -> {
                val campaign = campaignRef
                    ?: return Admission.Refused("campaign rule without a declared campaign reference cannot attribute")
                val hit = touchedWithin?.invoke(campaign, sale.contactKey, start, sale.occurredAtMs)
                if (hit != null) AttributionOutcome(AttributionLabel.INFLUENCED, sale.amountUgx, "campaign touch $campaign at ${hit.first}")
                else AttributionOutcome(AttributionLabel.UNATTRIBUTED, 0, "no campaign touch inside the window")
            }
        }
        if (!store.insertAttribution(
                RevenueAttribution(
                    saleUniqueKey = sale.uniqueKey, label = outcome.label, ruleType = ruleType.name,
                    windowMs = window, campaignRef = campaignRef.orEmpty(),
                    touchRef = outcome.detail.take(300), confidence = if (outcome.label == AttributionLabel.UNATTRIBUTED) 0.3 else 0.8,
                    recordedAtMs = nowMs,
                ),
            )
        ) {
            return Admission.Refused("attribution raced with another writer; sale keeps its first row")
        }
        return Admission.Accepted(sale.uniqueKey)
    }

    /** Window-expiry probe used by reviews: true when the sale no longer attributes. */
    fun attributionWindowExpired(sale: SaleEvidenceRecord, nowMs: Long): Boolean =
        nowMs - sale.occurredAtMs > policy().attributionWindowMs

    // ---------- profit ----------

    sealed class ProfitResult {
        data class Known(val grossProfitUgx: Long, val components: Components) : ProfitResult()
        data class Unknown(val missingComponents: List<String>) : ProfitResult()
    }

    data class Components(
        val directRevenueUgx: Long,
        val influencedRevenueUgx: Long,
        val unattributedRevenueUgx: Long,
        val productCostUgx: Long?,
        val discountsUgx: Long?,
        val campaignSpendUgx: Long?,
        val transactionFeesUgx: Long?,
        val refundsUgx: Long?,
        val operatingAllocationUgx: Long?,
        val subscriptionUgx: Long?,
    )

    /**
     * Profit over a period. Only ACTIVE sales count (cancels/refunds corrected out);
     * recognized costs come from durable COST rows; UNKNOWN wins whenever any mandatory
     * component has no durable record. Revenue alone is never reported as profit.
     *
     * REFUNDS component: resolves to a known ZERO when the ledger holds no refund-cost
     * rows — under documented accounting Method B full refunds reverse revenue directly,
     * so an empty refund ledger is genuinely zero, not unknown.
     */
    fun profit(fromMs: Long, toMs: Long): ProfitResult {
        val sales = store.sales(fromMs, toMs, setOf(SaleEvidenceRecord.SaleState.ACTIVE))
        var direct = 0L
        var influenced = 0L
        var unattributed = 0L
        sales.forEach { sale ->
            when (store.attributionForSale(sale.uniqueKey)?.label) {
                AttributionLabel.DIRECT -> direct += sale.amountUgx
                AttributionLabel.INFLUENCED -> influenced += sale.amountUgx
                AttributionLabel.UNATTRIBUTED, null -> unattributed += sale.amountUgx
            }
        }
        val costs = store.costs(fromMs, toMs).groupBy { it.component }.mapValues { e -> e.value.sumOf { it.amountUgx } }
        fun known(component: CostEntry.CostComponent): Long? = if (component in costs) costs[component] else null
        val components = Components(
            directRevenueUgx = direct, influencedRevenueUgx = influenced, unattributedRevenueUgx = unattributed,
            productCostUgx = known(CostEntry.CostComponent.PRODUCT_COST),
            discountsUgx = known(CostEntry.CostComponent.DISCOUNTS),
            campaignSpendUgx = known(CostEntry.CostComponent.CAMPAIGN_SPEND),
            transactionFeesUgx = known(CostEntry.CostComponent.TRANSACTION_FEES),
            refundsUgx = known(CostEntry.CostComponent.REFUNDS),
            operatingAllocationUgx = known(CostEntry.CostComponent.OPERATING_ALLOCATION),
            subscriptionUgx = known(CostEntry.CostComponent.SUBSCRIPTION),
        )
        val missing = buildList {
            if (components.productCostUgx == null) add("PRODUCT_COST")
            if (components.discountsUgx == null) add("DISCOUNTS")
            if (components.campaignSpendUgx == null) add("CAMPAIGN_SPEND")
            if (components.transactionFeesUgx == null) add("TRANSACTION_FEES")
            if (components.operatingAllocationUgx == null) add("OPERATING_ALLOCATION")
            if (components.subscriptionUgx == null) add("SUBSCRIPTION")
        }
        if (missing.isNotEmpty()) return ProfitResult.Unknown(missing.sorted())
        // REFUNDS resolves to a known ZERO when the ledger holds no refund-cost rows:
        // under documented accounting Method B full refunds REVERSE recognized revenue
        // instead of adding a cost, so an empty refund ledger is genuinely zero, not
        // unknown. Externally observed refund events may still be recorded as explicit
        // REFUNDS cost rows and then enter the sum below.
        val refundsKnown = components.refundsUgx ?: 0L
        val gross = components.directRevenueUgx + components.influencedRevenueUgx +
            components.unattributedRevenueUgx -
            components.productCostUgx!! - components.discountsUgx!! - components.campaignSpendUgx!! -
            components.transactionFeesUgx!! - refundsKnown -
            components.operatingAllocationUgx!! - components.subscriptionUgx!!
        return ProfitResult.Known(gross, components.copy(refundsUgx = refundsKnown))
    }

    companion object {
        /** Same contact+product inside this horizon counts as duplicate interest. */
        const val DEDUPE_HORIZON_MS = 24 * 60 * 60 * 1_000L
    }
}
