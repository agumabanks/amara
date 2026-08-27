package co.sanaa.agent.core.commerce

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Durable Revenue Operator domain tests over production SQLite (Robolectric): typed
 * records, funnel-transition legality with full metadata, dedup keys, sale corrections,
 * per-sale attribution uniqueness, and suppression persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RevenueStoreTest {

    private lateinit var memory: AmaraMemory
    private lateinit var store: RevenueStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
        store = RevenueStore(memory)
    }

    @Test fun opportunityDedupeKeyIsStablePerContactAndProduct() {
        assertEquals(store.opportunityDedupeKey("c1", "p1"), store.opportunityDedupeKey("c1", "p1"))
        assertTrue(store.opportunityDedupeKey("c1", "p1") != store.opportunityDedupeKey("c1", "p2"))
    }

    @Test fun ensureOpportunityIsIdempotentOnTheSameContactProductPair() {
        val a = store.ensureOpportunity("cust", "prod", FunnelStage.OBSERVED, 1000L)
        val b = store.ensureOpportunity("cust", "prod", FunnelStage.OBSERVED, 2000L)
        assertNotNull(a); assertNotNull(b); assertEquals(a!!.id, b!!.id)
        assertEquals(1, store.opportunities().size)
    }

    @Test fun funnelTransitionsMustCarrySourceEvidenceAndReason() {
        val opp = store.ensureOpportunity("c", "p", FunnelStage.OBSERVED, 1000L)!!
        // Missing evidence → refused loudly, nothing recorded.
        assertTrue(
            runCatching {
                store.recordTransition(
                    FunnelTransition(opp.id, FunnelStage.OBSERVED, FunnelStage.CONTACTABLE, 1000L,
                        source = "test", evidenceKind = "", evidenceRef = "", confidence = 0.9, reason = "r"),
                )
            }.isFailure,
        )
        assertEquals(0, store.transitions(opp.id).size)
        assertTrue(
            store.recordTransition(
                FunnelTransition(opp.id, FunnelStage.OBSERVED, FunnelStage.CONTACTABLE, 1000L,
                    source = "chat", evidenceKind = "whatsapp_chat", evidenceRef = "chat:1",
                    confidence = 0.9, reason = "first contact made"),
            ),
        )
        assertEquals(FunnelStage.CONTACTABLE, store.findOpportunity(opp.id)!!.stage)
        val history = store.transitions(opp.id)
        assertEquals(1, history.size)
        assertEquals("chat", history[0].source)
        assertEquals("chat:1", history[0].evidenceRef)
    }

    @Test fun funnelOnlyMovesOneForwardStepOrToATerminalNegativeExit() {
        val opp = store.ensureOpportunity("c2", "p2", FunnelStage.OBSERVED, 1000L)!!
        // Two-step jump refused.
        assertFalse(
            store.recordTransition(
                FunnelTransition(opp.id, FunnelStage.OBSERVED, FunnelStage.ORDER_INTENT, 1000L,
                    "s", "e", "r1", 0.9, "skip ahead"),
            ),
        )
        // Legal single steps.
        assertTrue(
            store.recordTransition(
                FunnelTransition(opp.id, FunnelStage.OBSERVED, FunnelStage.CONTACTABLE, 1000L,
                    "s", "e", "r2", 0.9, "first contact"),
            ),
        )
        assertTrue(
            store.recordTransition(
                FunnelTransition(opp.id, FunnelStage.CONTACTABLE, FunnelStage.ENGAGED, 1000L,
                    "s", "e", "r2b", 0.9, "engaged"),
            ),
        )
        // Terminal exit from a progression stage is legal.
        assertTrue(
            store.recordTransition(
                FunnelTransition(opp.id, FunnelStage.ENGAGED, FunnelStage.DISQUALIFIED, 1000L,
                    "s", "e", "r3", 0.9, "spam seller"),
            ),
        )
        assertEquals(FunnelStage.DISQUALIFIED, store.findOpportunity(opp.id)!!.stage)
    }

    @Test fun saleCorrectionsMutateStateAndKeepHistoryAuditable() {
        val now = System.currentTimeMillis()
        val admitted = RevenueMetricEngineForTest.engine(store).recordSale(
            SaleEvidenceRecord.SaleEvidenceKind.SOKO_ORDER, "order-77", "c3", "p3", 120_000, now, now,
        )
        assertTrue(admitted is RevenueMetricEngine.Admission.Accepted)
        val sale = store.findSaleByRef("order-77")!!
        assertEquals(SaleEvidenceRecord.SaleState.ACTIVE, sale.state)
        val engine = RevenueMetricEngineForTest.engine(store)
        assertTrue(engine.correctSale(sale.uniqueKey, SaleEvidenceRecord.SaleState.CANCELLED, "buyer cancelled", now)
            is RevenueMetricEngine.Admission.Accepted)
        assertEquals(SaleEvidenceRecord.SaleState.CANCELLED, store.findSaleByRef("order-77")!!.state)
        // Double correction refused.
        assertTrue(engine.correctSale(sale.uniqueKey, SaleEvidenceRecord.SaleState.REFUNDED, "again", now)
            is RevenueMetricEngine.Admission.Refused)
    }

    @Test fun onlyOneAttributionRowPerSaleEverExists() {
        val now = System.currentTimeMillis()
        val engine = RevenueMetricEngineForTest.engine(store)
        engine.recordSale(SaleEvidenceRecord.SaleEvidenceKind.POS_RECEIPT, "pos-1", "c4", "p4", 50_000, now, now)
        val sale = store.findSaleByRef("pos-1")!!
        val rule: (String, Long, Long) -> Pair<Long, String>? = { _, after, before -> if (before >= now - 1000) now - 500 to "touch" else null }
        assertTrue(engine.attributeSale(sale, AttributionType.DIRECT_REPLY_THREAD, repliedWithin = rule) is RevenueMetricEngine.Admission.Accepted)
        // Second attempt refuses instead of double counting.
        assertTrue(engine.attributeSale(sale, AttributionType.DIRECT_REPLY_THREAD, repliedWithin = rule)
            is RevenueMetricEngine.Admission.Refused)
        assertEquals(1, store.attributions(0, Long.MAX_VALUE).size)
    }

    @Test fun suppressionSurvivesReopenThroughTheDurableStore() {
        val now = System.currentTimeMillis()
        store.suppressContact("+256700111222", "opt-out", now)
        assertTrue(store.isSuppressed("+256700111222"))
        // Fresh instance over the same DB still sees the suppression.
        val fresh = RevenueStore(AmaraMemory(ApplicationProvider.getApplicationContext<Context>()))
        assertTrue(fresh.isSuppressed("+256700111222"))
        assertNull(fresh.dailyPlan("missing-day"))
    }
}

/** Test seam building an engine whose policy is fixed for the scenario. */
object RevenueMetricEngineForTest {
    fun engine(store: RevenueStore, policy: CommercialPolicy = CommercialPolicy()): RevenueMetricEngine =
        RevenueMetricEngine(store, { policy })
}
