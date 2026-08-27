package co.sanaa.agent.core.commerce

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Revenue Operator charter enforcement through the ONE canonical revenue system
 * (RevenueStore + RevenueMetricEngine + CommercialPolicy). Evidence-only target
 * evaluation, strict admission boundaries, declared-rule attribution, honest profit,
 * and owner-configurable targets that persist. The charter text lives in
 * REVENUE_OPERATOR_CHARTER.md; there is no second commercial implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RevenueOperatorCharterTest {

    private lateinit var context: Context
    private lateinit var memory: AmaraMemory
    private lateinit var store: RevenueStore
    private lateinit var engine: RevenueMetricEngine

    /** Owner policy with targets configured; allow-lists stay open for admission tests. */
    private val policy = CommercialPolicy(
        dailyQualifiedInquiryTarget = 1,
        weeklyVerifiedSaleTarget = 3,
        monthlyProfitFloorUgx = 0,
        allowedProducts = setOf("soko-listing-42"),
        approvedChannels = setOf("whatsapp"),
        permittedAudience = "consented customers",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
        store = RevenueStore(memory)
        engine = RevenueMetricEngine(store, policy = { policy })
    }

    private fun inquiry(
        contact: String = "256770000001", product: String = "soko-listing-42",
        at: Long = 1_000L, id: String = "conv-$contact-$at",
        kind: RevenueMetricEngine.ContactKind = RevenueMetricEngine.ContactKind.CUSTOMER,
    ) = engine.recordQualifiedInquiry(
        channel = "whatsapp_chat", contactKey = contact, contactKind = kind,
        productRef = product, interactionId = id, messageText = "how much is this? bei gani?",
        atMs = at, evidenceKind = "whatsapp_chat", evidenceRef = "wa://chat/$contact/$at",
    )

    // ---------- inquiry admission ----------

    @Test fun qualifiedCustomerInquiryIsAcceptedAndCountedOnce() {
        assertTrue(inquiry() is RevenueMetricEngine.Admission.Accepted)
        val duplicate = engine.recordQualifiedInquiry(
            "whatsapp_chat", "256770000001", RevenueMetricEngine.ContactKind.CUSTOMER,
            "soko-listing-42", "conv-256770000001-1000", "price?", 2_000,
            "whatsapp_chat", "wa://chat/x/2",
        )
        assertTrue(duplicate is RevenueMetricEngine.Admission.Refused)
        assertTrue(inquiry(contact = "256770000002", id = "conv-other") is RevenueMetricEngine.Admission.Accepted)
        assertEquals(2, store.inquiries(0, 10_000).size)
    }

    @Test fun nonCustomerOrUnverifiableOrInterestlessInteractionsNeverCount() {
        val refused = listOf(
            engine.recordQualifiedInquiry("whatsapp_chat", "c3", RevenueMetricEngine.ContactKind.OWNER, "soko-listing-42", "c1", "buy?", 1, "whatsapp_chat", "r1"),
            engine.recordQualifiedInquiry("whatsapp_chat", "c4", RevenueMetricEngine.ContactKind.AUTOMATED, "soko-listing-42", "c2", "buy?", 1, "whatsapp_chat", "r2"),
            engine.recordQualifiedInquiry("whatsapp_chat", "c5", RevenueMetricEngine.ContactKind.TEST, "soko-listing-42", "c3", "buy?", 1, "whatsapp_chat", "r3"),
            engine.recordQualifiedInquiry("whatsapp_chat", null, RevenueMetricEngine.ContactKind.CUSTOMER, "soko-listing-42", "c4", "buy?", 1, "whatsapp_chat", "r4"),
            engine.recordQualifiedInquiry("whatsapp_chat", "c6", RevenueMetricEngine.ContactKind.CUSTOMER, null, "c5", "buy?", 1, "whatsapp_chat", "r5"),
            engine.recordQualifiedInquiry("whatsapp_chat", "c7", RevenueMetricEngine.ContactKind.CUSTOMER, "soko-listing-42", "c6", "nice weather today", 1, "whatsapp_chat", "r6"),
            engine.recordQualifiedInquiry("whatsapp_chat", "c8", RevenueMetricEngine.ContactKind.CUSTOMER, "unapproved-product", "c7", "buy?", 1, "whatsapp_chat", "r7"),
        )
        assertTrue(refused.all { it is RevenueMetricEngine.Admission.Refused })
        assertEquals(0, store.inquiries(0, 10_000).size)
    }

    // ---------- sale admission ----------

    @Test fun completedSaleKindsAreAcceptedAndDuplicatesRefused() {
        SaleEvidenceRecord.SaleEvidenceKind.entries.mapIndexed { index, kind ->
            engine.recordSale(kind, "src-$index", "c", "soko-listing-42", amountUgx = 50_000L + index, atMs = index.toLong())
        }.forEach { assertTrue(it is RevenueMetricEngine.Admission.Accepted) }
        assertEquals(
            "only admitted sales reach the ledger", SaleEvidenceRecord.SaleEvidenceKind.entries.size,
            store.sales(0, 1_000).size,
        )
    }

    @Test fun duplicateSaleRecordsCannotDoubleCount() {
        assertTrue(engine.recordSale(SaleEvidenceRecord.SaleEvidenceKind.SOKO_ORDER, "order-9", "c", "soko-listing-42", 80_000, 5) is RevenueMetricEngine.Admission.Accepted)
        assertTrue(engine.recordSale(SaleEvidenceRecord.SaleEvidenceKind.SOKO_ORDER, "order-9", "c", "soko-listing-42", 80_000, 5) is RevenueMetricEngine.Admission.Refused)
        assertEquals(1, weeklySales())
    }

    private fun weeklySales(nowMs: Long = 60_000): Int =
        store.sales(nowMs - 7L * 86_400_000, nowMs, setOf(SaleEvidenceRecord.SaleState.ACTIVE)).size

    // ---------- funnel follows admitted evidence only ----------

    @Test fun admittedInquiryAdvancesTheCanonicalFunnelExactlyOneStep() {
        assertTrue(inquiry() is RevenueMetricEngine.Admission.Accepted)
        val oppId = store.opportunities().single().id
        assertEquals(FunnelStage.QUALIFIED_INQUIRY, store.findOpportunity(oppId)?.stage)
        assertFalse(store.recordTransition(
            FunnelTransition(oppId, FunnelStage.QUALIFIED_INQUIRY, FunnelStage.ORDER_CREATED,
                20L, "test", "evidence", "ref-x", 0.9, "stage skip refused"),
        ))
        assertTrue(store.recordTransition(
            FunnelTransition(oppId, FunnelStage.QUALIFIED_INQUIRY, FunnelStage.ORDER_INTENT,
                20L, "test", "message_sent", "ref-a", 0.9, "one evidenced step"),
        ))
        assertEquals(FunnelStage.ORDER_INTENT, store.findOpportunity(oppId)?.stage)
    }

    // ---------- attribution ----------

    @Test fun declaredRuleSeparatesDirectFromInfluencedRevenue() {
        assertTrue(inquiry(contact = "+256700000009") is RevenueMetricEngine.Admission.Accepted)
        assertTrue(engine.recordSale(SaleEvidenceRecord.SaleEvidenceKind.POS_RECEIPT, "pos-direct", "+256700000009", "soko-listing-42", 120_000, 1_000_000) is RevenueMetricEngine.Admission.Accepted)
        val sale = store.findSaleByRef("pos-direct")!!
        val direct = engine.attributeSale(sale, AttributionType.DIRECT_REPLY_THREAD,
            repliedWithin = { _, after, before -> if (after <= 900_000 && before >= 900_000) 900_000L to "thread-77" else null },
            nowMs = 1_100_000)
        assertTrue(direct is RevenueMetricEngine.Admission.Accepted)
        assertEquals(AttributionLabel.DIRECT, store.attributionForSale(sale.uniqueKey)?.label)

        // A second attribution attempt can never double count.
        val again = engine.attributeSale(sale, AttributionType.CAMPAIGN_TOUCH_WINDOW, campaignRef = "camp-1", nowMs = 1_200_000)
        assertTrue(again is RevenueMetricEngine.Admission.Refused)

        // Influenced path stays a separate label on its own sale.
        assertTrue(engine.recordSale(SaleEvidenceRecord.SaleEvidenceKind.POS_RECEIPT, "pos-infl", "+256700000010", "soko-listing-42", 90_000, 1_000_000) is RevenueMetricEngine.Admission.Accepted)
        val sale2 = store.findSaleByRef("pos-infl")!!
        assertTrue(engine.attributeSale(sale2, AttributionType.CAMPAIGN_TOUCH_WINDOW, campaignRef = "camp-1",
            touchedWithin = { _, _, after, _ -> if (after <= 500_000) 500_000L to "touch-1" else null },
            nowMs = 1_100_000) is RevenueMetricEngine.Admission.Accepted)
        assertEquals(AttributionLabel.INFLUENCED, store.attributionForSale(sale2.uniqueKey)?.label)

        // Campaign rule without declared campaign ref cannot attribute.
        assertTrue(engine.recordSale(SaleEvidenceRecord.SaleEvidenceKind.POS_RECEIPT, "pos-noc", "+256700000011", "soko-listing-42", 70_000, 1_000_000) is RevenueMetricEngine.Admission.Accepted)
        val sale3 = store.findSaleByRef("pos-noc")!!
        val noCampaign = engine.attributeSale(sale3, AttributionType.CAMPAIGN_TOUCH_WINDOW, campaignRef = null, touchedWithin = { _, _, _, _ -> 1L to "x" }, nowMs = 1_100_000)
        assertTrue(noCampaign is RevenueMetricEngine.Admission.Refused)
    }

    // ---------- profit ----------

    @Test fun profitIsUnknownWhileAnyCostComponentIsUnavailable() {
        assertTrue(inquiry(contact = "pc") is RevenueMetricEngine.Admission.Accepted)
        assertTrue(engine.recordSale(SaleEvidenceRecord.SaleEvidenceKind.POS_RECEIPT, "pos-p", "pc", "soko-listing-42", 500_000, 1_000_000) is RevenueMetricEngine.Admission.Accepted)
        val partial = engine.profit(0, 2_000_000)
        assertTrue(partial is RevenueMetricEngine.ProfitResult.Unknown)
        assertTrue((partial as RevenueMetricEngine.ProfitResult.Unknown).missingComponents.isNotEmpty())

        // Record every component; known costs keep gross profit strictly below revenue.
        val t = 1_500_000L
        listOf(
            CostEntry.CostComponent.PRODUCT_COST to 150_000L,
            CostEntry.CostComponent.DISCOUNTS to 0L,
            CostEntry.CostComponent.CAMPAIGN_SPEND to 5_000L,
            CostEntry.CostComponent.TRANSACTION_FEES to 2_000L,
            CostEntry.CostComponent.REFUNDS to 0L,
            CostEntry.CostComponent.OPERATING_ALLOCATION to 40_000L,
            CostEntry.CostComponent.SUBSCRIPTION to 50_000L,
        ).forEachIndexed { i, (component, amount) ->
            assertTrue(store.insertCost(CostEntry(
                uniqueKey = "cost-$i-${component.name}", component = component, productRef = "p",
                ref = "cost-src-$i", amountUgx = amount, occurredAtMs = t), t))
        }
        val full = engine.profit(0, 2_000_000)
        assertEquals(253_000L, (full as RevenueMetricEngine.ProfitResult.Known).grossProfitUgx)
    }

    // ---------- refund accounting: method B (reverse revenue, no double deduction) ----------

    @Test fun fullRefundReversesRevenueWithoutDoubleDeductingTheRefund() {
        assertTrue(inquiry(contact = "rf") is RevenueMetricEngine.Admission.Accepted)
        assertTrue(engine.recordSale(SaleEvidenceRecord.SaleEvidenceKind.POS_RECEIPT, "pos-rf", "rf", "soko-listing-42", 100_000, 1_000_000) is RevenueMetricEngine.Admission.Accepted)
        val t = 1_500_000L
        listOf(
            CostEntry.CostComponent.PRODUCT_COST to 30_000L,
            CostEntry.CostComponent.DISCOUNTS to 0L,
            CostEntry.CostComponent.CAMPAIGN_SPEND to 0L,
            CostEntry.CostComponent.TRANSACTION_FEES to 0L,
            CostEntry.CostComponent.REFUNDS to 0L,
            CostEntry.CostComponent.OPERATING_ALLOCATION to 0L,
            CostEntry.CostComponent.SUBSCRIPTION to 0L,
        ).forEachIndexed { i, (component, amount) ->
            store.insertCost(CostEntry("rc-$i", component, "p", "rc-src-$i", amount, t), t)
        }
        assertEquals(70_000L, (engine.profit(0, 2_000_000) as RevenueMetricEngine.ProfitResult.Known).grossProfitUgx)

        // Full refund under DOCUMENTED METHOD B: the sale leaves ACTIVE revenue and the
        // same amount is NEVER deducted again as a REFUNDS cost — one movement per shilling.
        val sale = store.findSaleByRef("pos-rf")!!
        assertTrue(engine.correctSale(sale.uniqueKey, SaleEvidenceRecord.SaleState.REFUNDED, "customer refund", 1_600_000) is RevenueMetricEngine.Admission.Accepted)
        val after = engine.profit(0, 2_000_000)
        val known = after as RevenueMetricEngine.ProfitResult.Known
        assertEquals("revenue fully reversed by the correction", 0L, known.components.directRevenueUgx + known.components.influencedRevenueUgx)
        assertEquals("no REFUNDS cost row duplicates the reversal", 0L, known.components.refundsUgx ?: -1L)
        // Exact numeric proof: profit = 0 revenue − 30_000 product cost = −30_000.
        assertEquals(-30_000L, known.grossProfitUgx)
        assertEquals("corrected sale no longer counts toward the weekly target", 0, weeklySales(2_000_000))
    }

    // ---------- targets: durable evidence only, owner-configurable ----------

    @Test fun weeklyTargetDefaultsToThreeVerifiedSalesUntilOwnerChangesIt() {
        assertEquals("charter default", 3, CommercialPolicy().weeklyVerifiedSaleTarget)
        assertEquals("configured instance honors the same default", 3, policy.weeklyVerifiedSaleTarget)
    }

    @Test fun dailyAndWeeklyTargetsAreMetOnlyByDurableLedgerEvents() {
        val planner = DailyCommercialPlanner(store, engine, policy = { policy })
        assertFalse(planner.dailyStatus(50_000).met)
        assertFalse(planner.weeklyStatus(50_000).met)
        assertTrue(inquiry() is RevenueMetricEngine.Admission.Accepted)
        assertTrue(planner.dailyStatus(50_000).met)
        assertTrue(engine.recordSale(SaleEvidenceRecord.SaleEvidenceKind.SOKO_ORDER, "o-1", "256770000001", "soko-listing-42", 80_000, 5) is RevenueMetricEngine.Admission.Accepted)
        assertTrue(engine.recordSale(SaleEvidenceRecord.SaleEvidenceKind.BOOKING, "b-1", "256770000002", "soko-listing-42", 60_000, 6) is RevenueMetricEngine.Admission.Accepted)
        assertTrue(engine.recordSale(SaleEvidenceRecord.SaleEvidenceKind.PAYMENT_RECORD, "pay-1", "256770000003", "soko-listing-42", 40_000, 7) is RevenueMetricEngine.Admission.Accepted)
        assertTrue("three verified sales meet the charter target", planner.weeklyStatus(60_000).met)
    }

    @Test fun ownerTargetsPersistAcrossReopenAndValidateThemselves() {
        val custom = CommercialPolicy(
            dailyQualifiedInquiryTarget = 2, weeklyVerifiedSaleTarget = 5, monthlyProfitFloorUgx = 300_000,
            allowedProducts = setOf("x"), approvedChannels = setOf("whatsapp"), permittedAudience = "a",
        )
        assertTrue(store.savePolicy(custom, nowMs = 1))
        assertEquals(custom, RevenueStore(AmaraMemory(context)).loadPolicy())
        assertTrue(runCatching { custom.copy(dailyQualifiedInquiryTarget = -1) }.isFailure)
        assertTrue(runCatching { custom.copy(monthlyProfitFloorUgx = -1) }.isFailure)
        assertTrue(runCatching { custom.copy(discountCeilingPercent = 101) }.isFailure)
    }
}
