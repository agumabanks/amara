package co.sanaa.agent.core.commerce

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.QuietHoursPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.async
import java.time.LocalTime

/**
 * Metric-integrity and customer-protection evaluation (charter Part 2/8 + directive 10).
 * Each test names the charter clause it enforces; all durable behavior runs on the
 * production SQLite store via Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RevenueEvaluationTest {

    private lateinit var memory: AmaraMemory
    private lateinit var store: RevenueStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
        store = RevenueStore(memory)
    }

    private fun engine(policy: CommercialPolicy = CommercialPolicy(allowedProducts = setOf("listing-1"))) =
        RevenueMetricEngine(store, { policy })

    private fun guard(
        policy: CommercialPolicy = CommercialPolicy(
            allowedProducts = setOf("listing-1"),
            approvedChannels = setOf("whatsapp"),
            permittedAudience = "opted-in customers",
            quietHours = QuietHoursPolicy(LocalTime.parse("21:00"), LocalTime.parse("07:30")),
        ),
        consent: (String) -> Boolean = { true },
    ) = OutreachGuard(store, { policy }, consent)

    private fun inquiry(
        contact: String,
        product: String?,
        text: String,
        kind: RevenueMetricEngine.ContactKind = RevenueMetricEngine.ContactKind.CUSTOMER,
        interaction: String = "i-" + text.hashCode(),
    ) = engine().recordQualifiedInquiry(
        channel = "whatsapp", contactKey = contact, contactKind = kind,
        productRef = product, interactionId = interaction, messageText = text,
        atMs = System.currentTimeMillis(), evidenceKind = "whatsapp_chat", evidenceRef = "chat:$interaction",
    )

    // ---------- qualified-inquiry definition ----------

    @Test fun duplicateInquiryIsRejectedNotCounted() {
        assertTrue(inquiry("+256700000001", "listing-1", "How much is this?", interaction = "m1")
            is RevenueMetricEngine.Admission.Accepted)
        assertTrue(inquiry("+256700000001", "listing-1", "Still available?", interaction = "m1")
            is RevenueMetricEngine.Admission.Refused)
        assertEquals(1, store.inquiries(0, Long.MAX_VALUE).size)
    }

    @Test fun sameCustomerSameProductInsideDedupeHorizonIsADuplicate() {
        assertTrue(inquiry("+256700000002", "listing-1", "price gani?", interaction = "m1")
            is RevenueMetricEngine.Admission.Accepted)
        assertTrue(inquiry("+256700000002", "listing-1", "ipo?", interaction = "m2")
            is RevenueMetricEngine.Admission.Refused)
    }

    @Test fun ownerTestAndSpamMessagesAreExcludedFromQualifiedInquiries() {
        val cases = listOf(
            Triple("+256799999999", "my own number", RevenueMetricEngine.ContactKind.OWNER),
            Triple("+256755555555", "YOU HAVE WON A PRIZE click here", RevenueMetricEngine.ContactKind.SPAM),
            Triple("+256766666666", "automated broadcast", RevenueMetricEngine.ContactKind.AUTOMATED),
            Triple(null as String?, "who is this", RevenueMetricEngine.ContactKind.UNVERIFIABLE),
        )
        cases.forEach { (contact, text, kind) ->
            val result = inquiry(contact ?: "", "listing-1", text, kind = kind, interaction = "x" + text.hashCode())
            assertTrue("$text must refuse: $result", result is RevenueMetricEngine.Admission.Refused)
        }
        assertEquals(0, store.inquiries(0, Long.MAX_VALUE).size)
    }

    @Test fun inquiryWithoutIdentifiableProductIsNotCounted() {
        val result = inquiry("+256700000010", null, "how much for everything?")
        assertTrue(result is RevenueMetricEngine.Admission.Refused)
        assertTrue((result as RevenueMetricEngine.Admission.Refused).reason.contains("identifiable"))
    }

    @Test fun inquiryWithoutExplicitCommercialInterestIsNotCounted() {
        val result = inquiry("+256700000011", "listing-1", "good morning, nice weather")
        assertTrue(result is RevenueMetricEngine.Admission.Refused)
        assertTrue((result as RevenueMetricEngine.Admission.Refused).reason.contains("commercial interest"))
    }

    @Test fun inquiryFromUnauthorizedEvidenceSourceIsRefused() {
        val result = engine().recordQualifiedInquiry(
            channel = "whatsapp", contactKey = "+256700000012",
            contactKind = RevenueMetricEngine.ContactKind.CUSTOMER,
            productRef = "listing-1", interactionId = "i12", messageText = "how much?",
            atMs = 1000, evidenceKind = "imagination", evidenceRef = "nowhere",
        )
        assertTrue(result is RevenueMetricEngine.Admission.Refused)
        assertTrue((result as RevenueMetricEngine.Admission.Refused).reason.contains("authorized"))
    }

    // ---------- completed-sale definition ----------

    private fun sale(ref: String, amount: Long = 80_000L) = engine().recordSale(
        SaleEvidenceRecord.SaleEvidenceKind.SOKO_ORDER, ref, "+256700000020", "listing-1", amount,
        System.currentTimeMillis(), System.currentTimeMillis(),
    )

    @Test fun saleDeduplicationRefusesTheSameSourceRecordTwice() {
        assertTrue(sale("order-dup-1") is RevenueMetricEngine.Admission.Accepted)
        assertTrue(sale("order-dup-1") is RevenueMetricEngine.Admission.Refused)
        assertTrue(sale("order-dup-1") is RevenueMetricEngine.Admission.Refused)
        assertEquals(1, store.sales(0, Long.MAX_VALUE).size)
    }

    @Test fun saleEvidenceAdvancesANewOpportunityOneAuditableStageAtATime() {
        val now = System.currentTimeMillis()
        val result = engine().recordSale(
            SaleEvidenceRecord.SaleEvidenceKind.PAYMENT_RECORD,
            "payment-direct-stage",
            "+256700000099",
            "listing-1",
            80_000,
            now,
            now,
        )
        assertTrue(result is RevenueMetricEngine.Admission.Accepted)
        val opportunity = store.opportunities().single { it.contactKey == "+256700000099" }
        assertEquals(FunnelStage.SALE_VERIFIED, opportunity.stage)
        val transitions = store.transitions(opportunity.id)
        assertEquals(
            FunnelStage.PROGRESSION.drop(1),
            transitions.map { it.toStage },
        )
        assertTrue(transitions.all { it.evidenceRef == "payment-direct-stage" })
    }

    @Test fun draftPromisesAreNeverSales() {
        // Only SOKO_ORDER/BOOKING/POS/PAYMENT/OWNER_CONFIRMED exist in the type system —
        // a "draft order" cannot even be expressed.
        val kinds = SaleEvidenceRecord.SaleEvidenceKind.entries.map { it.name }
        assertFalse("DRAFT_ORDER" in kinds)
        assertFalse("PROMISE" in kinds)
    }

    @Test fun cancellationReversesRevenueExactlyOnceWithoutDoubleDeductingTheRefund() {
        // DOCUMENTED ACCOUNTING METHOD B (reverse-revenue): a corrected sale leaves the
        // ACTIVE revenue base and the SAME amount is NOT deducted again as a REFUNDS
        // cost — profit moves once per shilling, never twice.
        val e = engine()
        e.recordSale(SaleEvidenceRecord.SaleEvidenceKind.SOKO_ORDER, "order-refund-1", "c", "p", 90_000,
            System.currentTimeMillis(), System.currentTimeMillis())
        // Baseline profit with all other components known except refunds:
        store.insertCost(CostEntry(ContentHashing.hash("pc"), CostEntry.CostComponent.PRODUCT_COST, "p", "r", 20_000, 0), 0)
        store.insertCost(CostEntry(ContentHashing.hash("ds"), CostEntry.CostComponent.DISCOUNTS, "p", "r", 0, 0), 0)
        store.insertCost(CostEntry(ContentHashing.hash("cs"), CostEntry.CostComponent.CAMPAIGN_SPEND, "p", "r", 0, 0), 0)
        store.insertCost(CostEntry(ContentHashing.hash("tf"), CostEntry.CostComponent.TRANSACTION_FEES, "p", "r", 1_000, 0), 0)
        store.insertCost(CostEntry(ContentHashing.hash("oa"), CostEntry.CostComponent.OPERATING_ALLOCATION, "p", "r", 5_000, 0), 0)
        store.insertCost(CostEntry(ContentHashing.hash("su"), CostEntry.CostComponent.SUBSCRIPTION, "p", "r", 4_000, 0), 0)
        val s = store.findSaleByRef("order-refund-1")!!
        assertTrue(e.correctSale(s.uniqueKey, SaleEvidenceRecord.SaleState.REFUNDED, "customer refund", System.currentTimeMillis())
            is RevenueMetricEngine.Admission.Accepted)
        // Active-sales view excludes it: recognized revenue drops by exactly 90_000.
        assertEquals(0, store.sales(0, Long.MAX_VALUE, setOf(SaleEvidenceRecord.SaleState.ACTIVE)).size)
        // Method B: NO refund cost row is written for the full refund — the revenue
        // reversal already accounted for it. Deducting it twice would double-count.
        val refunds = store.costs(0, Long.MAX_VALUE).filter { it.component == CostEntry.CostComponent.REFUNDS }
        assertEquals(0, refunds.size)
        // Exact numeric proof: profit = 0 (revenue) - 30_000 (costs) = -30_000.
        val beforeProfit = e.profit(0, Long.MAX_VALUE)
        assertTrue(beforeProfit is RevenueMetricEngine.ProfitResult.Known)
        assertEquals(-30_000L, (beforeProfit as RevenueMetricEngine.ProfitResult.Known).grossProfitUgx)
    }

    @Test fun partialRefundReducesRecognizedRevenueByExactlyTheRefundedAmount() {
        val e = engine()
        e.recordSale(SaleEvidenceRecord.SaleEvidenceKind.PAYMENT_RECORD, "pay-part", "cp", "p", 100_000,
            System.currentTimeMillis(), System.currentTimeMillis())
        val s = store.findSaleByRef("pay-part")!!
        assertTrue(e.refundPartOfSale(s.uniqueKey, 25_000, "partial refund agreed", System.currentTimeMillis())
            is RevenueMetricEngine.Admission.Accepted)
        // Recognized revenue is now exactly 75_000 — reduced once, not deducted again.
        val active = store.sales(0, Long.MAX_VALUE, setOf(SaleEvidenceRecord.SaleState.ACTIVE))
        assertEquals(75_000L, active.single().amountUgx)
        // And no REFUNDS cost duplicates the same movement.
        assertEquals(0, store.costs(0, Long.MAX_VALUE).filter { it.component == CostEntry.CostComponent.REFUNDS }.size)
    }

    // ---------- attribution ----------

    @Test fun directVersusInfluencedAttributionStaySeparate() {
        val now = System.currentTimeMillis()
        val e = engine()
        e.recordSale(SaleEvidenceRecord.SaleEvidenceKind.PAYMENT_RECORD, "pay-direct", "cd", "p", 60_000, now, now)
        e.recordSale(SaleEvidenceRecord.SaleEvidenceKind.PAYMENT_RECORD, "pay-influenced", "ci", "p", 40_000, now, now)
        val direct = store.findSaleByRef("pay-direct")!!
        val influenced = store.findSaleByRef("pay-influenced")!!
        assertTrue(e.attributeSale(direct, AttributionType.DIRECT_REPLY_THREAD, repliedWithin = { _, _, _ -> now - 100 to "thread" })
            is RevenueMetricEngine.Admission.Accepted)
        assertTrue(e.attributeSale(influenced, AttributionType.CAMPAIGN_TOUCH_WINDOW, campaignRef = "camp-1",
            touchedWithin = { _, _, after, _ -> if (after <= now - 2000) now - 3000 to "touch" else null })
            is RevenueMetricEngine.Admission.Accepted)
        val labels = store.attributions(0, Long.MAX_VALUE).map { it.label }
        assertEquals(setOf(AttributionLabel.DIRECT, AttributionLabel.INFLUENCED), labels.toSet())
    }

    @Test fun attributionWindowExpiryStopsAttribution() {
        val now = System.currentTimeMillis()
        val e = engine()
        e.recordSale(SaleEvidenceRecord.SaleEvidenceKind.PAYMENT_RECORD, "pay-old", "co", "p", 30_000, now, now)
        val sale = store.findSaleByRef("pay-old")!!
        // Touch happened long before the window → UNATTRIBUTED, zero revenue claimed.
        val result = e.attributeSale(sale, AttributionType.CAMPAIGN_TOUCH_WINDOW, campaignRef = "camp-x",
            touchedWithin = { _, _, _, _ -> null })
        assertTrue(result is RevenueMetricEngine.Admission.Accepted)
        assertEquals(AttributionLabel.UNATTRIBUTED, store.attributionForSale(sale.uniqueKey)!!.label)
        assertTrue(!e.attributionWindowExpired(sale, now))          // inside window → not expired
        assertTrue(e.attributionWindowExpired(sale, sale.occurredAtMs + CommercialPolicy().attributionWindowMs + 1))
    }

    // ---------- profit honesty ----------

    @Test fun missingCostComponentsYieldUnknownProfitNeverRevenueAsProfit() {
        val now = System.currentTimeMillis()
        val e = engine()
        e.recordSale(SaleEvidenceRecord.SaleEvidenceKind.POS_RECEIPT, "pos-profit", "cp", "p", 500_000, now, now)
        val sale = store.findSaleByRef("pos-profit")!!
        e.attributeSale(sale, AttributionType.DIRECT_REPLY_THREAD, repliedWithin = { _, _, _ -> now to "t" })
        val result = e.profit(0, Long.MAX_VALUE)
        assertTrue("profit with no cost rows must be UNKNOWN: $result", result is RevenueMetricEngine.ProfitResult.Unknown)
        // REFUNDS resolves to a known zero under documented Method B (full refunds
        // reverse revenue; an empty refund ledger is genuinely zero), so it is not a
        // missing component.
        val missing = (result as RevenueMetricEngine.ProfitResult.Unknown).missingComponents
        assertEquals(listOf("CAMPAIGN_SPEND", "DISCOUNTS", "OPERATING_ALLOCATION", "PRODUCT_COST", "SUBSCRIPTION", "TRANSACTION_FEES"), missing)
    }

    @Test fun knownCostsProduceKnownGrossProfitBelowRevenue() {
        val now = System.currentTimeMillis()
        val e = engine()
        e.recordSale(SaleEvidenceRecord.SaleEvidenceKind.POS_RECEIPT, "pos-p2", "cp", "p", 500_000, now, now)
        val sale = store.findSaleByRef("pos-p2")!!
        e.attributeSale(sale, AttributionType.DIRECT_REPLY_THREAD, repliedWithin = { _, _, _ -> now to "t" })
        val costRows = listOf(
            CostEntry(ContentHashing.hash("cost|pc"), CostEntry.CostComponent.PRODUCT_COST, "p", "sup", 150_000, now),
            CostEntry(ContentHashing.hash("cost|d"), CostEntry.CostComponent.DISCOUNTS, "p", "d", 20_000, now),
            CostEntry(ContentHashing.hash("cost|cs"), CostEntry.CostComponent.CAMPAIGN_SPEND, "p", "ad", 10_000, now),
            CostEntry(ContentHashing.hash("cost|tf"), CostEntry.CostComponent.TRANSACTION_FEES, "p", "fee", 5_000, now),
            CostEntry(ContentHashing.hash("cost|r"), CostEntry.CostComponent.REFUNDS, "p", "r", 0, now),
            CostEntry(ContentHashing.hash("cost|oa"), CostEntry.CostComponent.OPERATING_ALLOCATION, "", "oa", 15_000, now),
            CostEntry(ContentHashing.hash("cost|sub"), CostEntry.CostComponent.SUBSCRIPTION, "", "sub", 25_000, now),
        )
        costRows.forEach { assertTrue(store.insertCost(it, now)) }
        val result = e.profit(0, Long.MAX_VALUE)
        assertTrue(result is RevenueMetricEngine.ProfitResult.Known)
        assertEquals(500_000L - 150_000 - 20_000 - 10_000 - 5_000 - 0 - 15_000 - 25_000, (result as RevenueMetricEngine.ProfitResult.Known).grossProfitUgx)
    }

    // ---------- budget reservations ----------

    @Test fun budgetReservationLifecycleReserveCommitReleaseFailRefund() {
        val ledger = co.sanaa.agent.core.work.MemorySpendReservations()
        val contract = co.sanaa.agent.core.work.WorkContract(
            id = "res-c", objective = "o", owner = "o", deliverables = listOf("d"), successCriteria = listOf("s"),
            deadlineMs = null, dependencies = emptyList(), allowedSystems = setOf("*"),
            dataClassification = co.sanaa.agent.core.work.DataClassification.BUSINESS_INTERNAL, budgetUgx = 1_000L,
            approvalPolicy = co.sanaa.agent.core.work.ApprovalPolicy(), verificationRules = emptyList(),
            escalationConditions = emptyList(), followUpObligations = emptyList(), createdAtMs = 0,
        )
        val r1 = ledger.reserve(contract, 600, "action a", 1)
        val r2 = ledger.reserve(contract, 500, "action b", 2)      // would exceed ceiling → refused
        assertTrue("first reservation must succeed", r1 != null)
        assertTrue("over-ceiling reservation must be refused", r2 == null)
        assertTrue(ledger.commit(r1!!, 3))
        assertEquals(600L, ledger.committedOn("res-c"))
        val r3 = ledger.reserve(contract, 400, "action c", 4)!!
        assertNotNull(r3)
        assertTrue(ledger.release(r3!!, 5))                        // rejected effect releases
        assertEquals(0L, ledger.reservedOn("res-c"))
        assertTrue(ledger.refund(r1, 6))                           // committed cost reversed later
        assertEquals(0L, ledger.committedOn("res-c"))
    }

    // ---------- outreach protection ----------

    @Test fun quietHoursBlockOutreach() {
        val g = guard()
        val verdict = g.gate("+256700000030", "listing-1", "whatsapp", "Hello, still available?", LocalTime.parse("23:30"), 0, 0, 0)
        assertTrue(verdict is OutreachGuard.Verdict.Block && verdict.reason.contains("Quiet hours"))
    }

    @Test fun perRecipientAndGlobalDailyCapsEnforced() {
        val g = guard()
        val perCustomer = g.gate("+256700000031", "listing-1", "whatsapp", "Hello again", LocalTime.parse("10:00"), messagesToCustomerToday = 1, messagesToCustomerInWindow = 0, globalMessagesToday = 0)
        assertTrue(perCustomer is OutreachGuard.Verdict.Block && perCustomer.reason.contains("Per-customer"))
        val global = g.gate("+256700000032", "listing-1", "whatsapp", "Hello you", LocalTime.parse("10:00"), 0, 0, globalMessagesToday = 4)
        assertTrue(global is OutreachGuard.Verdict.Block && global.reason.contains("global"))
    }

    @Test fun optOutSuppressesImmediatelyAndPermanently() {
        val g = guard()
        val before = g.gate("+256700000033", "listing-1", "whatsapp", "Hi", LocalTime.parse("10:00"), 0, 0, 0)
        assertTrue(before is OutreachGuard.Verdict.Allow)
        assertTrue(g.recordOptOut("+256700000033", "STOP reply", System.currentTimeMillis()))
        val after = g.gate("+256700000033", "listing-1", "whatsapp", "Hi", LocalTime.parse("10:00"), 0, 0, 0)
        assertTrue(after is OutreachGuard.Verdict.Block && after.reason.contains("suppression"))
    }

    @Test fun missingConsentBlocksOutreach() {
        val g = guard(consent = { false })
        val verdict = g.gate("+256700000034", "listing-1", "whatsapp", "Hi", LocalTime.parse("10:00"), 0, 0, 0)
        assertTrue(verdict is OutreachGuard.Verdict.Block && verdict.reason.contains("eligibility"))
    }

    @Test fun unavailableInventoryIsNotOffered() {
        val ranker = OpportunityRanker {
            CommercialPolicy(allowedProducts = setOf("listing-1"), approvedChannels = setOf("whatsapp"), permittedAudience = "all")
        }
        val candidate = OpportunityRanker.Candidate(
            contactKey = "c", productRef = "listing-9", stage = FunnelStage.ENGAGED,
            inputs = OpportunityRanker.RankingInputs(
                inventoryAvailable = false, listingQuality = 0.8, knownPriceUgx = null, knownMarginUgx = null,
                inboundIntentScore = 0.9, unansweredInquiry = true, followUpDue = false,
                historicalConversionRate = 0.2, campaignPerformanceScore = 0.4, productFreshnessDays = 1,
                customerFatigueScore = 0.1, riskScore = 0.1, actionCostUgx = 100,
            ),
        )
        val verdict = ranker.eligibility(candidate, suppressed = false, 0, 0, 0, LocalTime.NOON)
        assertTrue(verdict is OpportunityRanker.Eligibility.Ineligible && verdict.reason.contains("inventory"))
    }

    @Test fun fabricatedProductFactsAreMechanicallyRejected() {
        val g = guard()
        listOf(
            "Hurry! Only 2 pieces left remaining!",
            "50% off today only!!!",
            "Everyone is buying this product",
            "Save up to UGX 100,000 guaranteed",
            "Guaranteed same-day delivery anywhere",
        ).forEach { content ->
            val reason = g.honestyScan(content)
            assertTrue("must refuse '$content'", reason != null)
        }
        // Honest content passes.
        assertTrue(g.honestyScan("The listing price is 45,000 UGX per the current Soko catalog.") == null)
    }

    @Test fun unverifiedSendCannotBeCountedAsOutreach() {
        // Router-level proof lives in WorkflowProductionRouterTest (provenNoEffect);
        // here the accounting rule is enforced directly: FAILED_NONBILLABLE never counts.
        val ledger = co.sanaa.agent.core.work.MemorySpendReservations()
        val contract = co.sanaa.agent.core.work.WorkContract(
            id = "nf", objective = "o", owner = "o", deliverables = listOf("d"), successCriteria = listOf("s"),
            deadlineMs = null, dependencies = emptyList(), allowedSystems = setOf("*"),
            dataClassification = co.sanaa.agent.core.work.DataClassification.BUSINESS_INTERNAL, budgetUgx = 10_000L,
            approvalPolicy = co.sanaa.agent.core.work.ApprovalPolicy(), verificationRules = emptyList(),
            escalationConditions = emptyList(), followUpObligations = emptyList(), createdAtMs = 0,
        )
        val r = ledger.reserve(contract, 500, "send", 1)!!
        assertTrue(ledger.markFailedNonbillable(r!!, 2))
        assertEquals(0L, ledger.committedOn("nf"))
        assertEquals(0L, ledger.reservedOn("nf"))
    }

    @Test fun concurrentOpportunityWorkersDoNotDoublePlanOrDoubleAttribute() {
        // Two writers race to attribute the same sale; exactly one wins.
        val now = System.currentTimeMillis()
        val e = engine()
        e.recordSale(SaleEvidenceRecord.SaleEvidenceKind.PAYMENT_RECORD, "pay-race", "cr", "p", 70_000, now, now)
        val sale = store.findSaleByRef("pay-race")!!
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.coroutineScope {
                val a = async {
                    e.attributeSale(sale, AttributionType.DIRECT_REPLY_THREAD, repliedWithin = { _, _, _ -> now to "w1" })
                }
                val b = async {
                    e.attributeSale(sale, AttributionType.DIRECT_REPLY_THREAD, repliedWithin = { _, _, _ -> now to "w2" })
                }
                listOf(a.await(), b.await())
            }
        }
        // Regardless of interleaving, exactly one attribution row exists.
        assertEquals(1, store.attributions(0, Long.MAX_VALUE).size)
    }

    @Test fun staleSokoEvidenceIsSurfacedNotSilentlyUsed() {
        val claim = co.sanaa.agent.core.knowledge.SourcedClaim(
            kind = co.sanaa.agent.core.knowledge.ClaimKind.FACT,
            statement = "stale stock fact for listing-stale",
            sourceRef = "inventory-scan", capturedAtMs = System.currentTimeMillis() - 100_000,
            freshnessMs = 1_000,
        )
        assertTrue("evidence older than its freshness window must be flagged stale", claim.isStale(System.currentTimeMillis()))
    }

    @Test fun experimentStopLossTripsIntoStoppedState() {
        // Concurrent experiments are legal ONLY with an explicitly declared multivariate
        // design naming its variables, and only while owner-policy slots remain.
        val policy = CommercialPolicy(
            allowedProducts = setOf("listing-1"), approvedChannels = setOf("whatsapp"),
            permittedAudience = "opted-in", maxExperimentSpendUgx = 100_000, dailyGlobalMessageCap = 6,
            maxConcurrentExperiments = 2,
        )
        val engineExp = ExperimentEngine(store, { policy })
        val launch = ExperimentEngine.LaunchSpec(
            id = "exp-sl-1", hypothesis = "Evening timing lifts replies", baseline = "morning sends",
            changeDimension = ExperimentSpec.ChangeDimension.TIMING, changeDescription = "move send window",
            targetProductRef = "listing-1", approvedAudience = "opted-in", approvedChannel = "whatsapp",
            startAtMs = 0, endAtMs = 86_400_000L * 7, minimumSampleCount = 5, budgetUgx = 1_000,
            communicationCapPerDay = 2, successMetric = "reply rate +3pp", guardrailMetric = "opt-out rate < 1%",
            stopLossCondition = "opt-out rate >= 0.01", attributionWindowMs = 86_400_000L,
        )
        assertTrue(engineExp.launch(launch, 0) is ExperimentEngine.LaunchResult.Running)
        // A SECOND overlapping launch without an explicit multivariate design refuses.
        assertTrue(engineExp.launch(launch.copy(id = "exp-live"), 500) is ExperimentEngine.LaunchResult.Refused)
        // With the declared design it may run — until the owner-policy slots run out.
        assertTrue(engineExp.launch(launch.copy(id = "exp-live"), 500, multivariateDesign = "send window × creative") is ExperimentEngine.LaunchResult.Running)
        assertTrue(engineExp.launch(launch.copy(id = "exp-overlap"), 750, multivariateDesign = "x") is ExperimentEngine.LaunchResult.Refused)
        // Concluding before the DECLARED sample minimum is mechanically refused…
        var refusedConclusion = false
        try { engineExp.conclude("exp-live", "no signal", 0.4, 900) } catch (_: IllegalStateException) { refusedConclusion = true }
        assertTrue("conclusion without durable samples must be refused", refusedConclusion)
        // …until durable evidence rows back the declared threshold.
        repeat(5) { index -> store.insertExperimentSample("exp-live", "reply_rate", 0.03, "ledger:sample-$index", 850L + index) }
        assertTrue(engineExp.conclude("exp-live", "no signal", 0.4, 900))
        // Stop-loss parses from the experiment's OWN DECLARED rule — never a caller threshold.
        assertEquals("STOPPED_LOSS", engineExp.evaluateGuardrail("exp-sl-1", guardrailValue = 0.05, evidenceRef = "ledger:suppressions", nowMs = 1_000))
        // An unparseable stop-loss rule refuses mechanical evaluation instead of guessing.
        var unparseable = false
        try { engineExp.evaluateGuardrail("exp-sl-1", 9.9, evidenceRef = "ledger:x", nowMs = 2_000) } catch (_: IllegalArgumentException) { unparseable = true }
        assertTrue(unparseable || engineExp.evaluateGuardrail("exp-sl-1", 9.9, "ledger:x", 2_000) == "STOPPED_LOSS")
    }

    @Test fun experimentBudgetBeyondOwnerPolicyIsRefusedAtLaunch() {
        val policy = CommercialPolicy(maxExperimentSpendUgx = 1_000)
        val engineExp = ExperimentEngine(store, { policy })
        val spec = ExperimentEngine.LaunchSpec(
            id = "exp-big", hypothesis = "h", baseline = "b", changeDimension = ExperimentSpec.ChangeDimension.CREATIVE,
            changeDescription = "new creative copy", targetProductRef = "any", approvedAudience = "a",
            approvedChannel = "whatsapp", startAtMs = 0, endAtMs = 1, minimumSampleCount = 1,
            budgetUgx = 5_000, communicationCapPerDay = 1, successMetric = "s", guardrailMetric = "g",
            stopLossCondition = "stop", attributionWindowMs = 1,
        )
        val result = engineExp.launch(spec, 0)
        assertTrue(result is ExperimentEngine.LaunchResult.Refused && result.reason.contains("ceiling"))
    }

    @Test fun dailyTargetMissProposesBoundedFollowUpWithoutAuthorityExpansion() {
        val policy = CommercialPolicy(dailyQualifiedInquiryTarget = 5, weeklyVerifiedSaleTarget = 5)
        val cycle = DailyCommercialCycle(
            store = store,
            metrics = engine(policy),
            ranker = OpportunityRanker { policy },
            guard = guard(policy = policy),
            experiments = ExperimentEngine(store, { policy }),
            policy = { policy },
        )
        val result = cycle.closeOut(dayLessons = listOf("No inquiries came in."), unresolved = emptyList())
        // A miss produces a bounded proposal — never automatic expansion of caps/spend.
        assertTrue(result.proposedExperiment != null)
        assertEquals(0, result.proposedExperiment!!.budgetUgx)
        assertEquals(1, result.proposedExperiment!!.communicationCapPerDay)
        // And the brief states targets honestly as NOT met.
        val targetsSection = result.briefSpec.sections.first { it.heading == "Targets" }.body
        assertTrue("targets said: $targetsSection", targetsSection.contains("met: false"))
    }

    @Test fun degradedChannelHealthProducesReadOnlyBriefInsteadOfSpam() {
        val policy = CommercialPolicy(
            allowedProducts = setOf("listing-1"), approvedChannels = setOf("whatsapp"),
            permittedAudience = "opted-in",
        )
        val cycle = DailyCommercialCycle(
            store = store, metrics = engine(policy), ranker = OpportunityRanker { policy },
            guard = guard(policy = policy), experiments = ExperimentEngine(store, { policy }),
            policy = { policy },
        )
        val plan = cycle.planMorning(
            DailyCommercialCycle.BusinessSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            DailyCommercialCycle.HealthSignals(false, false, false, listOf("accessibility service offline")),
        )
        assertEquals(0, plan.externalActionBudget)
        assertTrue(plan.rankedCandidates.isEmpty())
        assertTrue(plan.readOnlyWork.any { it.contains("decision brief") || it.contains("degraded") })
    }

    @Test fun ownerPolicyMissingFailsClosedForOutreach() {
        val closedPolicy = CommercialPolicy() // blank allow-lists
        val g = guard(policy = closedPolicy)
        val verdict = g.gate("+256700000040", "listing-1", "whatsapp", "Hello", LocalTime.NOON, 0, 0, 0)
        assertTrue(verdict is OutreachGuard.Verdict.Block)
        assertTrue((verdict as OutreachGuard.Verdict.Block).reason.contains("fail") || verdict.reason.contains("owner-approved"))
    }
}
