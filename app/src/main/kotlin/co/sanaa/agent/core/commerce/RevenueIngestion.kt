package co.sanaa.agent.core.commerce

import co.sanaa.agent.core.ContentHashing

/**
 * The ONE authorized ingestion path from real business signals into the canonical
 * revenue ledger (charter evidence rule). Every method retains a SOURCE REFERENCE and
 * an EVIDENCE HASH; nothing without durable evidence can enter the ledger, and nothing
 * here infers a sale from optimistic language.
 *
 * Wired by [RevenueOperatorRuntime] and called from the ConversationEngine (inbound
 * customer messages), notification monitoring, the owner channels (sale confirmation,
 * opt-outs, refunds), and screen-evidence observers (Soko order/booking states).
 *
 * Completion rule: an order or booking becomes a counted sale ONLY when its observed
 * state is explicitly completed/paid/fulfilled or the owner confirms it. A message SEND
 * is never an inquiry; optimistic buyer language is never a completed sale.
 */
class RevenueIngestion(
    private val store: RevenueStore,
    private val metrics: RevenueMetricEngine,
    private val guard: OutreachGuard,
) {

    sealed class IngestionResult {
        data class Recorded(val uniqueKey: String, val detail: String) : IngestionResult()
        data class Refused(val reason: String) : IngestionResult()
    }

    /**
     * A customer-directed message was OBSERVED on a live channel. This is only an
     * inquiry CANDIDATE: the metric engine's qualified-inquiry definition decides.
     */
    fun observeInboundCustomerMessage(
        channel: String,
        contactKey: String?,
        productRef: String?,
        messageText: String?,
        interactionId: String,
        atMs: Long,
        sourcePackage: String,
    ): IngestionResult {
        if (interactionId.isBlank()) return IngestionResult.Refused("an observation needs its source interaction id")
        val evidenceKind = when {
            channel == "whatsapp" && sourcePackage == "com.whatsapp" -> "whatsapp_chat"
            channel == "soko" -> "soko_order_message"
            else -> return IngestionResult.Refused("channel '$channel' from '$sourcePackage' is not an authorized inquiry source")
        }
        val evidenceRef = "$evidenceKind:${ContentHashing.hash("$sourcePackage|$interactionId|$messageText")}" 
        val resolvedProduct = metrics.resolveObservedProduct(productRef, messageText)
        return when (val admission = metrics.recordQualifiedInquiry(
            channel = channel, contactKey = contactKey, contactKind = RevenueMetricEngine.ContactKind.CUSTOMER,
            productRef = resolvedProduct, interactionId = interactionId, messageText = messageText,
            atMs = atMs, evidenceKind = evidenceKind, evidenceRef = evidenceRef,
        )) {
            is RevenueMetricEngine.Admission.Accepted -> IngestionResult.Recorded(admission.uniqueKey, "qualified inquiry admitted")
            is RevenueMetricEngine.Admission.Refused -> IngestionResult.Refused(admission.reason)
        }
    }

    /** Observed Soko ORDER screen states that legally count as completed sales. */
    private val completedOrderStates = setOf("completed", "paid", "fulfilled", "delivered")

    /** Observed Soko BOOKING states that legally count as completed sales. */
    private val completedBookingStates = setOf("completed", "paid", "fulfilled")

    /**
     * A Soko order/booking screen state was OBSERVED. Draft/pending/new states are
     * REFUSED — an enum sighting is not evidence of completion.
     */
    fun observeSokoOrderOrBooking(
        kind: SaleEvidenceRecord.SaleEvidenceKind,
        sourceRecordId: String,
        observedState: String,
        contactKey: String,
        productRef: String,
        amountUgx: Long,
        observedAtMs: Long,
        sourcePackage: String = "co.sanaa.agent.soko",
    ): IngestionResult {
        if (kind !in setOf(SaleEvidenceRecord.SaleEvidenceKind.SOKO_ORDER, SaleEvidenceRecord.SaleEvidenceKind.BOOKING)) {
            return IngestionResult.Refused("this path accepts only SOKO_ORDER/BOOKING evidence kinds")
        }
        if (sourceRecordId.isBlank()) return IngestionResult.Refused("completed sale requires its unique source record reference")
        val allowedStates = if (kind == SaleEvidenceRecord.SaleEvidenceKind.SOKO_ORDER) completedOrderStates else completedBookingStates
        if (observedState.trim().lowercase() !in allowedStates) {
            return IngestionResult.Refused(
                "order/booking state '$observedState' is not observed completion (completed/paid/fulfilled required); it is not counted",
            )
        }
        return admitSale(kind, sourceRecordId, contactKey, productRef, amountUgx, observedAtMs, sourcePackage)
    }

    /** Explicit owner confirmation of a sale through the owner UI/channel. */
    fun confirmSaleByOwner(
        saleRef: String,
        contactKey: String,
        productRef: String,
        amountUgx: Long,
        atMs: Long,
        confirmedVia: String = "owner_ui",
    ): IngestionResult =
        admitSale(SaleEvidenceRecord.SaleEvidenceKind.OWNER_CONFIRMED, saleRef, contactKey, productRef, amountUgx, atMs, confirmedVia)

    /** POS/receipt evidence capture when a receipt surface is available. */
    fun observePosReceipt(
        receiptId: String,
        contactKey: String,
        productRef: String,
        amountUgx: Long,
        observedAtMs: Long,
        sourcePackage: String,
    ): IngestionResult =
        admitSale(SaleEvidenceRecord.SaleEvidenceKind.POS_RECEIPT, receiptId, contactKey, productRef, amountUgx, observedAtMs, sourcePackage)

    /** Payment-record evidence (mobile-money statement, bank reference). */
    fun observePaymentRecord(
        paymentRef: String,
        contactKey: String,
        productRef: String,
        amountUgx: Long,
        observedAtMs: Long,
        sourcePackage: String,
    ): IngestionResult =
        admitSale(SaleEvidenceRecord.SaleEvidenceKind.PAYMENT_RECORD, paymentRef, contactKey, productRef, amountUgx, observedAtMs, sourcePackage)

    private fun admitSale(
        kind: SaleEvidenceRecord.SaleEvidenceKind,
        saleRef: String,
        contactKey: String,
        productRef: String,
        amountUgx: Long,
        atMs: Long,
        sourcePackage: String,
    ): IngestionResult {
        if (contactKey.isBlank() || productRef.isBlank()) {
            return IngestionResult.Refused("a recorded sale names its customer and product")
        }
        // Evidence hash binds source package + record id + amount so later audits can
        // re-derive exactly what was observed; the durable sale row carries both.
        val evidenceHash = ContentHashing.hash("sale|$kind|$sourcePackage|$saleRef|$amountUgx|$atMs")
        val durableRef = "$sourcePackage:$saleRef#${evidenceHash.take(16)}"
        return when (val admission = metrics.recordSale(
            evidenceKind = kind, saleRef = durableRef,
            contactKey = contactKey, productRef = productRef, amountUgx = amountUgx, atMs = atMs,
        )) {
            is RevenueMetricEngine.Admission.Accepted -> IngestionResult.Recorded(admission.uniqueKey, "evidence=$evidenceHash")
            is RevenueMetricEngine.Admission.Refused -> IngestionResult.Refused(admission.reason)
        }
    }

    /** An observed cancellation/refund corrects the ledger (revenue reversal, once). */
    fun observeCancellationOrRefund(saleUniqueKey: String, toState: SaleEvidenceRecord.SaleState, reason: String, nowMs: Long): IngestionResult =
        when (val r = metrics.correctSale(saleUniqueKey, toState, reason, nowMs)) {
            is RevenueMetricEngine.Admission.Accepted -> IngestionResult.Recorded(r.uniqueKey, "corrected to ${toState.name}")
            is RevenueMetricEngine.Admission.Refused -> IngestionResult.Refused(r.reason)
        }

    /** Partial refund of an ACTIVE sale (method B: revenue reduced once, no double cost). */
    fun observePartialRefund(saleUniqueKey: String, refundedUgx: Long, reason: String, nowMs: Long): IngestionResult =
        when (val r = metrics.refundPartOfSale(saleUniqueKey, refundedUgx, reason, nowMs)) {
            is RevenueMetricEngine.Admission.Accepted -> IngestionResult.Recorded(r.uniqueKey, "partial refund ${refundedUgx}Ugx recognized once")
            is RevenueMetricEngine.Admission.Refused -> IngestionResult.Refused(r.reason)
        }

    /**
     * Records an ACTUAL, EVIDENCED cost into the canonical ledger (product cost,
     * channel spend, transaction fees, subscription, operating allocation). Estimated
     * planning costs and reserved budgets never enter here — only observed amounts
     * with a source reference.
     */
    fun recordActualCost(
        component: CostEntry.CostComponent,
        productRef: String?,
        ref: String,
        amountUgx: Long,
        occurredAtMs: Long,
    ): IngestionResult {
        if (amountUgx < 0) return IngestionResult.Refused("a recorded cost carries its actual non-negative amount")
        if (ref.isBlank()) return IngestionResult.Refused("an actual cost names its source record reference")
        val uniqueKey = ContentHashing.hash("cost|${component.name}|$ref|$occurredAtMs")
        return if (store.insertCost(
                CostEntry(uniqueKey = uniqueKey, component = component, productRef = productRef.orEmpty(),
                    ref = ref.take(300), amountUgx = amountUgx, occurredAtMs = occurredAtMs),
                System.currentTimeMillis(),
            )
        ) IngestionResult.Recorded(uniqueKey, "actual ${component.name} cost ${amountUgx}Ugx recorded")
        else IngestionResult.Refused("duplicate cost record for source '$ref'")
    }

    /** Opt-outs/complaints suppress IMMEDIATELY through the guard. */
    fun recordOptOut(contactKey: String, reason: String, nowMs: Long): Boolean = guard.recordOptOut(contactKey, reason, nowMs)

    /**
     * Campaign/customer touch evidence for INFLUENCED attribution. Returns the durable
     * touch reference or refuses blank evidence.
     */
    fun recordCampaignTouch(campaignRef: String, contactKey: String, atMs: Long, evidenceRef: String): IngestionResult {
        if (campaignRef.isBlank() || contactKey.isBlank() || evidenceRef.isBlank()) {
            return IngestionResult.Refused("a campaign touch records campaign, customer, and evidence")
        }
        val hash = ContentHashing.hash("touch|$campaignRef|$contactKey|$atMs|$evidenceRef")
        val recorded = store.insertCampaignTouch(
            RevenueStore.CampaignTouch(
                uniqueKey = hash,
                campaignRef = campaignRef,
                contactKey = contactKey,
                evidenceRef = evidenceRef,
                occurredAtMs = atMs,
                recordedAtMs = System.currentTimeMillis(),
            ),
        )
        return if (recorded) IngestionResult.Recorded(hash, "touch recorded for campaign $campaignRef")
        else IngestionResult.Refused("duplicate campaign-touch evidence")
    }

    /** Verified message-delivery evidence (delivery ticks observed on device). */
    fun recordDeliveryObservation(contactKey: String, contentHash: String, deliveryState: String, atMs: Long): IngestionResult {
        if (contactKey.isBlank() || contentHash.isBlank() || deliveryState.isBlank()) {
            return IngestionResult.Refused("delivery evidence names recipient, content, and observed state")
        }
        val normalizedState = deliveryState.trim().uppercase()
        val hash = ContentHashing.hash("delivery|$contactKey|$contentHash|$normalizedState|$atMs")
        val recorded = store.insertDeliveryObservation(
            RevenueStore.DeliveryObservation(
                uniqueKey = hash,
                contactKey = contactKey,
                contentHash = contentHash,
                deliveryState = normalizedState,
                observedAtMs = atMs,
                recordedAtMs = System.currentTimeMillis(),
            ),
        )
        return if (recorded) IngestionResult.Recorded(hash, "delivery=$normalizedState")
        else IngestionResult.Refused("duplicate delivery observation")
    }
}
