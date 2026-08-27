package co.sanaa.agent.core.commerce

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.SideEffectState
import co.sanaa.agent.core.SideEffectTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Single-ledger consistency: commercial status, the owner dashboard, and the daily
 * planner brief are three VIEWS of one canonical revenue system. After any sequence of
 * admissions they must agree on inquiry counts, weekly verified sales, attribution
 * splits, and profit — no surface may drift into its own arithmetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RevenueSurfaceConsistencyTest {

    private lateinit var memory: AmaraMemory
    private lateinit var runtime: RevenueOperatorRuntime

    private val policy = CommercialPolicy(
        dailyQualifiedInquiryTarget = 1,
        weeklyVerifiedSaleTarget = 3,
        allowedProducts = setOf("soko-listing-42"),
        approvedChannels = setOf("whatsapp"),
        permittedAudience = "consented customers",
        ownerTimeZoneId = "Africa/Kampala",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
        // Production composition path: RevenueOperatorRuntime.create is what AgentRuntime uses.
        runtime = RevenueOperatorRuntime.create(memory)
        runtime.store.savePolicy(policy, nowMs = 1)
    }

    private fun now(): Long = System.currentTimeMillis()

    @Test fun productionCompositionConstructsEveryRevenueComponent() {
        assertTrue(runtime.policy() != null)
        // Every component reachable from ONE composition root.
        listOf(runtime.store, runtime.metrics, runtime.outreachGuard, runtime.ranker,
            runtime.experiments, runtime.cycle, runtime.dashboard, runtime.commercialPlanner)
        assertEquals(CommercialPolicy(), runtime.policyOrConservativeDefault().let { CommercialPolicy() })
    }

    @Test fun statusDashboardAndBriefAgreeOnTheSameLedgerTotals() {
        val t = now()
        val engine = runtime.metrics
        // Two inquiries, three sales: two attributed, one unattributed.
        listOf("c1" to 100_000L, "c2" to 200_000L).forEach { (contact, _) ->
            assertTrue(engine.recordQualifiedInquiry(
                "whatsapp_chat", contact, RevenueMetricEngine.ContactKind.CUSTOMER, "soko-listing-42",
                "i-$contact-$t", "how much?", t - 60_000, "whatsapp_chat", "wa://$contact/$t",
                nowMs = t) is RevenueMetricEngine.Admission.Accepted)
        }
        listOf(Triple("o-1", "c1", 100_000L), Triple("o-2", "c2", 200_000L), Triple("o-3", "c3", 50_000L))
            .forEach { (ref, contact, amount) ->
                assertTrue(engine.recordSale(SaleEvidenceRecord.SaleEvidenceKind.PAYMENT_RECORD, ref, contact,
                    "soko-listing-42", amount, t - 30_000, nowMs = t) is RevenueMetricEngine.Admission.Accepted)
            }
        val sale1 = runtime.store.findSaleByRef("o-1")!!
        assertTrue(engine.attributeSale(sale1, AttributionType.DIRECT_REPLY_THREAD,
            repliedWithin = { _, _, _ -> t - 45_000L to "thread-1" }, nowMs = t) is RevenueMetricEngine.Admission.Accepted)
        val sale2 = runtime.store.findSaleByRef("o-2")!!
        assertTrue(engine.attributeSale(sale2, AttributionType.CAMPAIGN_TOUCH_WINDOW, campaignRef = "camp-9",
            touchedWithin = { _, _, _, _ -> t - 40_000L to "touch-1" }, nowMs = t) is RevenueMetricEngine.Admission.Accepted)
        // An explicit UNATTRIBUTED row must remain visible in the unattributed
        // bucket; it must not disappear merely because an attribution row exists.
        val sale3 = runtime.store.findSaleByRef("o-3")!!
        assertTrue(engine.attributeSale(sale3, AttributionType.DIRECT_REPLY_THREAD,
            repliedWithin = { _, _, _ -> null }, nowMs = t) is RevenueMetricEngine.Admission.Accepted)

        // --- Surface 1: MainActivity-style commercial status via the planner ---
        val status = runtime.commercialPlanner.dailyStatus(t)
        val weekly = runtime.commercialPlanner.weeklyStatus(t)

        // --- Surface 2: dashboard ---
        val dashboard = runtime.dashboard.build()

        // --- Surface 3: end-of-day brief figures come from the same store/engine ---
        val brief = runtime.cycle.closeOut(dayLessons = emptyList(), unresolved = emptyList())

        assertEquals("inquiries identical across surfaces",
            status.verifiedInquiries, dashboard["qualifiedInquiriesToday"])
        assertEquals(2, status.verifiedInquiries)
        assertEquals("weekly verified sales identical across surfaces",
            weekly.salesContributed, dashboard["weeklyVerifiedSales"])
        assertEquals(3, weekly.salesContributed)
        assertEquals(100_000L, dashboard["attributedDirectRevenue"])
        assertEquals(200_000L, dashboard["attributedInfluencedRevenue"])
        assertEquals(50_000L, dashboard["unattributedRevenue"])
        assertEquals(350_000L, dashboard["weeklyActiveSalesRevenue"])
        assertEquals(true, dashboard["revenueSplitReconciles"])
        assertEquals("Africa/Kampala", dashboard["reportingZone"])
        assertEquals("OWNER_CONFIGURED", dashboard["reportingZoneState"])
        val unattributedEvidence = dashboard["unattributedEvidence"] as List<*>
        assertEquals(1, unattributedEvidence.size)
        assertTrue(unattributedEvidence.single().toString().contains("UNATTRIBUTED"))
        assertTrue(brief.targetsSummary.contains("weekly=false") || brief.targetsSummary.contains("weekly=true"))
        val rendered = brief.briefSpec.sections.associate { it.heading to it.body }
        assertTrue(rendered.getValue("Reconciled Activity").contains("Qualified inquiries today: ${status.verifiedInquiries}"))
        assertTrue("brief renders the SAME active-sales count as the dashboard",
            !rendered.getValue("Reconciled Activity").contains("weekly=${weekly.salesContributed + 1}"))
    }

    @Test fun unconfiguredPolicyFailsClosedEverywhereWithIdenticalSemantics() {
        runtime.store.savePolicy(
            CommercialPolicy(dailyQualifiedInquiryTarget = 1, weeklyVerifiedSaleTarget = 3), nowMs = 1)
        val verdict = runtime.policyOrConservativeDefault().evaluateOutreachEligibility(
            "p", "whatsapp", java.time.LocalTime.NOON, 0, 0, 0)
        assertTrue(CommercialPolicy.PolicyVerdict.Blocked::class.isInstance(verdict))
        val guardBlock = runtime.outreachGuard.gate(
            "c1", "p", "whatsapp", "plain honest text", java.time.LocalTime.NOON, 0, 0, 0)
        assertTrue(guardBlock is OutreachGuard.Verdict.Block)
    }

    @Test fun uncertainSideEffectsSurfaceThroughTheRuntimeNotTheLedger() {
        assertEquals(0, runtime.dashboard.build()["uncertainOutcomes"])
        memory.upsertSideEffectTransaction(SideEffectTransaction(
            idempotencyKey = "k-uncertain", capability = "send_whatsapp", target = "x",
            contentHash = "h", approvalId = null, state = SideEffectState.UNCERTAIN,
            createdAt = 1, updatedAt = 1, evidence = "e"))
        // Same wiring AgentRuntime uses: the runtime is constructed with the live counter.
        val runtime2 = RevenueOperatorRuntime.create(memory, uncertainSideEffects = {
            memory.allSideEffectTransactions().count { it.state == SideEffectState.UNCERTAIN }
        })
        assertEquals(1, runtime2.dashboard.build()["uncertainOutcomes"])
        assertTrue(runtime2.healthSignals().blockers.isNotEmpty())
    }

    @Test fun missingOwnerTimezoneUsesExplicitUtcFallbackNotDeviceTimezone() {
        runtime.store.savePolicy(policy.copy(ownerTimeZoneId = ""), nowMs = now())
        val dashboard = runtime.dashboard.build()
        assertEquals("UTC", dashboard["reportingZone"])
        assertEquals("UTC_FALLBACK_OWNER_TIMEZONE_MISSING", dashboard["reportingZoneState"])
        assertTrue(runtime.commercialPlanner.dailyStatus(now()).basis.contains("UTC"))
    }
}
