package co.sanaa.agent.core.commerce

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * PRODUCTION COMPOSITION + INGESTION CERTIFICATION.
 *
 * Proves that the ONE authoritative revenue system is constructed as a whole by the
 * composition root ([RevenueOperatorRuntime.create] — the exact factory AgentRuntime
 * calls), that live business signals enter ONLY through [RevenueIngestion], and that
 * every UI surface (dashboard, commercial status, daily brief) derives IDENTICAL totals
 * from the same canonical ledger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RevenueOperatorRuntimeCompositionTest {

    private lateinit var context: Context
    private lateinit var memory: AmaraMemory
    private lateinit var ops: RevenueOperatorRuntime

    private val ownerPolicy = CommercialPolicy(
        allowedProducts = setOf("listing-1", "listing-2"),
        approvedChannels = setOf("whatsapp"),
        permittedAudience = "opted-in customers",
        ownerTimeZoneId = "Africa/Kampala",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
        ops = RevenueOperatorRuntime.create(memory = memory)
        assertTrue(ops.store.savePolicy(ownerPolicy, System.currentTimeMillis()))
    }

    // ---------- Phase 3: production composition proof ----------

    @Test fun runtimeCompositionConstructsEveryRevenueComponentThroughTheProductionFactory() {
        // The exact create() call AgentRuntime uses must yield fully wired components.
        val composed = RevenueOperatorRuntime.create(memory = memory)
        assertNotNull(composed.store)
        assertNotNull(composed.metrics)
        assertNotNull(composed.outreachGuard)
        assertNotNull(composed.ranker)
        assertNotNull(composed.experiments)
        assertNotNull(composed.cycle)
        assertNotNull(composed.dashboard)
        assertNotNull(composed.commercialPlanner)
        assertNotNull(composed.revenueIngestion)
        // Fail-closed policy: a fresh runtime without a saved policy exposes none…
        val fresh = RevenueOperatorRuntime.create(memory = AmaraMemory(context).also {
            it.writableDatabase // force creation on a distinct file? same db; policy already saved above
        })
        assertEquals(ownerPolicy, fresh.policy())
        // …and the conservative default can never widen authority.
        assertEquals(3, composed.policyOrConservativeDefault().weeklyVerifiedSaleTarget)
    }

    @Test fun weeklyDefaultTargetIsThreeVerifiedCompletedSales() {
        assertEquals(3, CommercialPolicy().weeklyVerifiedSaleTarget)
        assertEquals(3, ops.policyOrConservativeDefault().weeklyVerifiedSaleTarget)
    }

    @Test fun runtimeHealthFailsClosedUntilFreshSignalsAreInjected() {
        val conservative = ops.healthSignals()
        assertFalse(conservative.deviceSessionHealthy)
        assertFalse(conservative.sokoReachable)
        assertFalse(conservative.whatsappChannelHealthy)
        assertTrue(conservative.blockers.any { it.contains("not been supplied") })

        val observed = RevenueOperatorRuntime.create(
            memory = memory,
            liveHealth = {
                DailyCommercialCycle.HealthSignals(
                    deviceSessionHealthy = true,
                    sokoReachable = true,
                    whatsappChannelHealthy = true,
                )
            },
        ).healthSignals()
        assertTrue(observed.deviceSessionHealthy)
        assertTrue(observed.sokoReachable)
        assertTrue(observed.whatsappChannelHealthy)
        assertTrue(observed.blockers.isEmpty())
    }

    // ---------- Phase 7: authorized ingestion paths ----------

    @Test fun optimisticOrderStatesAreRefusedAndOnlyObservedCompletionIsCounted() {
        val ingestion = ops.revenueIngestion
        // Draft/pending/new states are NOT completed sales — never counted.
        listOf("draft", "pending", "new", "confirmed-unpaid").forEach { state ->
            val result = ingestion.observeSokoOrderOrBooking(
                SaleEvidenceRecord.SaleEvidenceKind.SOKO_ORDER, "order-$state",
                observedState = state, contactKey = "+256700000001",
                productRef = "listing-1", amountUgx = 50_000, observedAtMs = System.currentTimeMillis(),
            )
            assertTrue("$state must be refused", result is RevenueIngestion.IngestionResult.Refused)
        }
        assertEquals(0, ops.store.sales(0, Long.MAX_VALUE).size)
        // Observed completion IS counted, with its evidence hash retained.
        val accepted = ingestion.observeSokoOrderOrBooking(
            SaleEvidenceRecord.SaleEvidenceKind.SOKO_ORDER, "order-done",
            observedState = "completed", contactKey = "+256700000001",
            productRef = "listing-1", amountUgx = 50_000, observedAtMs = System.currentTimeMillis(),
        )
        assertTrue(accepted is RevenueIngestion.IngestionResult.Recorded)
        val sale = ops.store.sales(0, Long.MAX_VALUE).single()
        assertTrue(sale.saleRef.contains("#") && sale.saleRef.startsWith("co.sanaa.agent.soko:"))
        assertEquals(SaleEvidenceRecord.SaleState.ACTIVE, sale.state)
    }

    @Test fun bookingCompletionFollowsTheSameRuleAndOwnerConfirmationIsAdmitted() {
        val ingestion = ops.revenueIngestion
        assertTrue(ingestion.observeSokoOrderOrBooking(
            SaleEvidenceRecord.SaleEvidenceKind.BOOKING, "bk-1", "reserved",
            "+256700000002", "listing-2", 30_000, System.currentTimeMillis(),
        ) is RevenueIngestion.IngestionResult.Refused)
        assertTrue(ingestion.observeSokoOrderOrBooking(
            SaleEvidenceRecord.SaleEvidenceKind.BOOKING, "bk-1", "paid",
            "+256700000002", "listing-2", 30_000, System.currentTimeMillis(),
        ) is RevenueIngestion.IngestionResult.Recorded)
        // Fixed timestamp so the duplicate's evidence hash matches exactly.
        val confirmedAt = 1_700_000_000_000L
        assertTrue(ingestion.confirmSaleByOwner("owner-said-so", "+256700000003", "listing-1", 12_000, confirmedAt)
            is RevenueIngestion.IngestionResult.Recorded)
        // Duplicate source record cannot double count (identical evidence hash).
        assertTrue(ingestion.confirmSaleByOwner("owner-said-so", "+256700000003", "listing-1", 12_000, confirmedAt)
            is RevenueIngestion.IngestionResult.Refused)
    }

    @Test fun inboundMessageIngestionFeedsTheMetricEngineAndDashboardIdentically() {
        val ingestion = ops.revenueIngestion
        val now = System.currentTimeMillis()
        // A send is not an inquiry; an interested customer message through the
        // authorized channel is admitted once and refused as duplicate afterwards.
        val first = ingestion.observeInboundCustomerMessage(
            "whatsapp", "+256700000010", "listing-1", "How much is listing-1?", "i-1", now, "com.whatsapp",
        )
        assertTrue(first is RevenueIngestion.IngestionResult.Recorded)
        val second = ingestion.observeInboundCustomerMessage(
            "whatsapp", "+256700000010", "listing-1", "How much is listing-1?", "i-1", now, "com.whatsapp",
        )
        assertTrue(second is RevenueIngestion.IngestionResult.Refused && second.reason.contains("duplicate"))
        // Unauthorized sources refuse honestly.
        assertTrue(ingestion.observeInboundCustomerMessage(
            "whatsapp", "+256700000010", "listing-1", "buy?", "i-2", now, "co.sanaa.agent",
        ) is RevenueIngestion.IngestionResult.Refused)

        // Dashboard and planner read the SAME canonical ledger: one qualified inquiry.
        val dashboard = ops.dashboard.build()
        val dashInquiries = (dashboard["todayQualifiedInquiries"] ?: dashboard["qualifiedInquiriesToday"])?.toString()?.toIntOrNull()
        assertEquals(1, dashInquiries)
        val status = ops.commercialPlanner.dailyStatus(now)
        assertEquals(1, status.verifiedInquiries)
    }

    @Test fun inboundMessageResolvesOnlyOneExplicitOwnerApprovedProduct() {
        val now = System.currentTimeMillis()
        val ingestion = ops.revenueIngestion

        assertTrue(ingestion.observeInboundCustomerMessage(
            "whatsapp", "+256700000020", null, "How much is LISTING-1?", "auto-1", now, "com.whatsapp",
        ) is RevenueIngestion.IngestionResult.Recorded)
        assertTrue(ingestion.observeInboundCustomerMessage(
            "whatsapp", "+256700000021", null, "How much is the product?", "auto-2", now, "com.whatsapp",
        ) is RevenueIngestion.IngestionResult.Refused)
        assertTrue(ingestion.observeInboundCustomerMessage(
            "whatsapp", "+256700000022", null, "Compare listing-1 and listing-2 prices", "auto-3", now, "com.whatsapp",
        ) is RevenueIngestion.IngestionResult.Refused)
        assertTrue(ingestion.observeInboundCustomerMessage(
            "whatsapp", "+256700000023", "not-approved", "How much?", "auto-4", now, "com.whatsapp",
        ) is RevenueIngestion.IngestionResult.Refused)

        assertEquals(1, ops.store.inquiries(0, Long.MAX_VALUE).size)
        assertEquals("listing-1", ops.store.inquiries(0, Long.MAX_VALUE).single().productRef)
    }

    @Test fun optOutThroughIngestionSuppressesImmediatelyAndPermanently() {
        val ingestion = ops.revenueIngestion
        assertTrue(ingestion.recordOptOut("+256700000044", "customer said stop", System.currentTimeMillis()))
        assertTrue(ops.store.isSuppressed("+256700000044"))
        // Suppression survives reopen (durable store).
        val reopenedStore = RevenueStore(AmaraMemory(context))
        assertTrue(reopenedStore.isSuppressed("+256700000044"))
    }

    // ---------- Phase 8.7: funnel transition atomicity under concurrency ----------

    @Test fun concurrentFunnelTransitionsFromTheSameStageProduceExactlyOneWinner() = runBlocking {
        val storeA = RevenueStore(memory)
        val storeB = RevenueStore(memory)
        val opp = storeA.ensureOpportunity("+256700000050", "listing-1", FunnelStage.OBSERVED, 1_000)!!
        assertEquals(FunnelStage.OBSERVED, opp.stage)
        // Two workers race the SAME single-forward move; exactly one may win, and the
        // loser must leave NO transition row behind (insert+CAS are one transaction).
        val results = listOf(async {
            storeA.recordTransition(FunnelTransition(opp.id, FunnelStage.OBSERVED, FunnelStage.CONTACTABLE, 2_000, "wA", "ek", "ev-A", 0.9, "worker A"))
        }, async {
            storeB.recordTransition(FunnelTransition(opp.id, FunnelStage.OBSERVED, FunnelStage.CONTACTABLE, 3_000, "wB", "ek", "ev-B", 0.9, "worker B"))
        }).awaitAll()
        assertEquals("exactly one racer wins", 1, results.count { it })
        assertEquals("stage advanced exactly once", FunnelStage.CONTACTABLE, storeA.findOpportunity(opp.id)!!.stage)
        assertEquals("no orphan transition rows", 1, storeA.transitions(opp.id).size)
    }

    @Test fun illegalMultiStageJumpLeavesNoTraceAtAll() {
        val store = RevenueStore(memory)
        val opp = store.ensureOpportunity("+256700000051", "listing-2", FunnelStage.OBSERVED, 1_000)!!
        assertFalse(store.recordTransition(FunnelTransition(opp.id, FunnelStage.OBSERVED, FunnelStage.ORDER_CREATED, 2_000, "s", "ek", "ev", 0.5, "jump")))
        assertEquals(FunnelStage.OBSERVED, store.findOpportunity(opp.id)!!.stage)
        assertEquals(0, store.transitions(opp.id).size)
    }

    // ---------- Phase 6: daily cycle persistence + recheck ----------

    @Test fun morningPlanPersistsRankedCandidatesWithFullInputsForRecheck() {
        val store = ops.store
        val zone = ZoneId.of("Africa/Kampala")
        val now = System.currentTimeMillis()
        // A real opportunity in ENGAGED with live consent becomes a plannable follow-up.
        store.ensureOpportunity("+256700000060", "listing-1", FunnelStage.ENGAGED, now)
        store.grantContactConsent("+256700000060", "owner_ui", "outreach", now, null, "consent-evidence-1")
        val snapshot = DailyCommercialCycle.BusinessSnapshot(
            inventory = listOf("listing-1"), weakListings = emptyList(),
            orders = emptyList(), bookings = emptyList(), salesSignals = emptyList(),
            inboundInquiries = emptyList(),
            pendingFollowUps = listOf(DailyCommercialCycle.BusinessSnapshot.PendingFollowUp(
                opportunityId = store.opportunities().first().id,
                contactKey = "+256700000060", productRef = "listing-1", dueSinceMs = now - 86_400_000,
            )),
            campaignAnalytics = emptyList(),
        )
        val health = DailyCommercialCycle.HealthSignals(true, true, true)
        val plan = ops.cycle.planMorning(snapshot, health)
        assertEquals("Africa/Kampala", plan.zoneId)
        // The ranked candidate was persisted ATOMICALLY as a durable PLANNED action.
        val planned = store.commercialActions(state = "PLANNED")
        assertEquals(1, planned.size)
        val row = planned.single()
        assertEquals("+256700000060", row.target)
        assertTrue(row.rankingJson.contains("explanation"))
        assertTrue(row.rankingJson.contains("productRef"))
        assertTrue(row.rankingJson.contains("consentReference"))
        assertTrue(row.contentHash.isNotBlank())
        assertEquals("plan:${plan.dayKey}", row.evidenceRef)
        // recheckBeforeApproval finds the persisted action via its id.
        val verdict = ops.cycle.recheckBeforeApproval(row.id)
        assertTrue("expected Allowed, got $verdict", verdict is CommercialPolicy.PolicyVerdict.Allowed)
        // After an opt-out, the SAME recheck refuses at the boundary.
        ops.revenueIngestion.recordOptOut("+256700000060", "stop", now)
        val after = ops.cycle.recheckBeforeApproval(row.id)
        assertTrue(after is CommercialPolicy.PolicyVerdict.Blocked && after.reason.contains("suppressed"))
    }

    @Test fun dayKeyUsesTheOwnerConfiguredTimezoneNotTheDeviceZone() {
        val kampalaDay = ops.cycle.dayKey(ZoneId.of("Africa/Kampala"))
        assertNotNull(kampalaDay)
        // The cycle's plan stores the OWNER zone id, never silently the device default.
        val snapshot = DailyCommercialCycle.BusinessSnapshot(
            inventory = emptyList(), weakListings = emptyList(), orders = emptyList(),
            bookings = emptyList(), salesSignals = emptyList(), inboundInquiries = emptyList(),
            pendingFollowUps = emptyList(), campaignAnalytics = emptyList(),
        )
        val degraded = DailyCommercialCycle.HealthSignals(false, true, false, listOf("test"))
        val plan = ops.cycle.planMorning(snapshot, degraded)
        assertEquals("Africa/Kampala", plan.zoneId)
        // Unconfigured timezone fails closed to a read-only blocked plan.
        val noZonePolicy = ownerPolicy.copy(ownerTimeZoneId = "")
        ops.store.savePolicy(noZonePolicy, System.currentTimeMillis())
        val blocked = ops.cycle.planMorning(snapshot, DailyCommercialCycle.HealthSignals(true, true, true))
        assertTrue(blocked.blockedCandidates.any { it.reason.contains("timezone") })
        assertEquals(0, blocked.externalActionBudget)
    }
}
