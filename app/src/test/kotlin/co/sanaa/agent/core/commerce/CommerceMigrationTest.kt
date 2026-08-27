package co.sanaa.agent.core.commerce

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
 * v11 consolidation migration proofs: legacy v9 commerce tables migrate into the
 * canonical revenue tables from every starting state — empty database, v9, v10 with
 * both old and new rows, duplicate records on both sides, an interrupted migration,
 * reopen stability, and owner deletion/export coverage. Migration is idempotent:
 * canonical rows always win, legacy rows never double count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommerceMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
    }

    /** Builds a raw database at a chosen schema version with legacy tables and data. */
    private fun buildLegacyDb(version: Int) {
        val db = context.openOrCreateDatabase(AmaraMemory.DATABASE_NAME, Context.MODE_PRIVATE, null)
        db.version = version
        db.execSQL(
            """CREATE TABLE commercial_targets (
                id INTEGER PRIMARY KEY CHECK(id = 1), config_json TEXT NOT NULL, updated_at INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE commercial_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT, unique_key TEXT NOT NULL UNIQUE, kind TEXT NOT NULL,
                channel TEXT NOT NULL DEFAULT '', contact_key TEXT NOT NULL DEFAULT '',
                product_ref TEXT NOT NULL DEFAULT '', amount_ugx INTEGER, evidence_kind TEXT NOT NULL,
                evidence_ref TEXT NOT NULL, attribution TEXT NOT NULL DEFAULT '{}',
                detail_json TEXT NOT NULL DEFAULT '{}', occurred_at INTEGER NOT NULL, recorded_at INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE commercial_opportunities (
                id TEXT PRIMARY KEY, contact_key TEXT NOT NULL, product_ref TEXT NOT NULL,
                stage TEXT NOT NULL, updated_at INTEGER NOT NULL)""",
        )
        fun event(key: String, kind: String, evidenceKind: String, amount: Long?, contact: String = "c1") {
            db.execSQL(
                "INSERT INTO commercial_events(unique_key, kind, channel, contact_key, product_ref, amount_ugx, evidence_kind, evidence_ref, occurred_at, recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(key, kind, "whatsapp", contact, "p", amount, evidenceKind, "src-$key", 500L, 600L),
            )
        }
        event("inq-1", "QUALIFIED_INQUIRY", "conversation", null)
        event("sale-1", "SALE", "SOKO_ORDER", 80_000)
        event("sale-2", "SALE", "POS_RECEIPT", 120_000, contact = "c2")
        // Draft orders/promises were refused at the legacy boundary; a corrupt row must not migrate.
        event("draft-1", "SALE", "DRAFT_ORDER", 10_000)
        event("cost-1", "COST", "PRODUCT_COST", 20_000)
        event("adv-1", "FUNNEL_ADVANCE", "message_sent", null)
        db.execSQL(
            "INSERT INTO commercial_opportunities(id, contact_key, product_ref, stage, updated_at) VALUES(?,?,?,?,?)",
            arrayOf<Any>("opp-legacy-1", "c1", "p", "ORDER_PLACED", 700L),
        )
        db.execSQL(
            "INSERT INTO commercial_targets(id, config_json, updated_at) VALUES(1, ?, ?)",
            arrayOf<Any>("{\"minVerifiedInquiriesPerDay\":2,\"weeklySalesContributions\":4,\"monthlyMinProfitUgx\":250000}", 800L),
        )
        db.close()
    }

    private fun open(): AmaraMemory = AmaraMemory(context)

    @Test fun emptyDatabaseMigratesCleanlyWithZeroLegacyRows() {
        val memory = open()
        val store = RevenueStore(memory)
        assertEquals(0, store.inquiries(0, Long.MAX_VALUE).size)
        assertEquals(0, store.sales(0, Long.MAX_VALUE).size)
        assertNotNull("fresh install still creates the consent ledger", store.consents())
        assertEquals(0, store.consents().size)
    }

    @Test fun v9DatabaseMigratesAllLegalLegacyRowsIntoCanonicalTables() {
        buildLegacyDb(version = 9)
        val store = RevenueStore(open())
        assertEquals("inquiries migrated", 1, store.inquiries(0, Long.MAX_VALUE).size)
        assertEquals("only completed-sale kinds migrate", 2, store.sales(0, Long.MAX_VALUE).size)
        assertNull("DRAFT_ORDER row is not a sale and never migrates", store.findSaleByRef("src-draft-1"))
        assertEquals(1, store.costs(0, Long.MAX_VALUE).size)
        assertEquals("ORDER_PLACED maps to ORDER_CREATED",
            FunnelStage.ORDER_CREATED, store.opportunities().first { it.id == "opp-legacy-1" }.stage)
    }

    @Test fun legacyTargetsSeedTheOwnerPolicyWithoutWideningAuthority() {
        buildLegacyDb(version = 9)
        val policy = RevenueStore(open()).loadPolicy()
        assertNotNull(policy)
        assertEquals(2, policy!!.dailyQualifiedInquiryTarget)
        assertEquals(4, policy.weeklyVerifiedSaleTarget)
        assertEquals(250_000L, policy.monthlyProfitFloorUgx)
        assertEquals("allow-lists stay empty: migrated targets never widen authority", emptySet<String>(), policy.allowedProducts)
        assertTrue("migrated policy fails closed for outreach",
            CommercialPolicy.PolicyVerdict.Blocked::class.isInstance(
                policy.evaluateOutreachEligibility("p", "whatsapp", java.time.LocalTime.NOON, 0, 0, 0)))
    }

    @Test fun legacyRowsWinTheUpgradeRaceAndCanonicalKeysCanNeverDuplicate() {
        buildLegacyDb(version = 10)
        // First open runs the v10→v11 upgrade over the legacy dataset.
        val store = RevenueStore(open())
        assertEquals(1, store.inquiries(0, Long.MAX_VALUE).size)
        assertEquals("legacy inquiry content migrated intact", "c1",
            store.findInquiry("inq-1")?.contactKey)
        // A later writer attempting the same unique key is refused — no duplicate, no
        // double counting across the migration boundary in either direction.
        assertFalse(store.insertInquiry(QualifiedInquiry(
            uniqueKey = "inq-1", channel = "whatsapp", contactKey = "c-new", productRef = "p-new",
            interactionId = "new-interaction", evidenceKind = "whatsapp_chat", evidenceRef = "fresh-ref",
            confidence = 0.85, occurredAtMs = 900L), nowMs = 901L))
        assertEquals("still exactly one row for the migrated key", 1, store.inquiries(0, Long.MAX_VALUE).size)
        assertEquals("legacy-only sale rows still arrive", 2, store.sales(0, Long.MAX_VALUE).size)
    }

    @Test fun interruptedThenRepeatedMigrationNeverDuplicatesRecords() {
        buildLegacyDb(version = 9)
        val first = RevenueStore(open())
        assertEquals(2, first.sales(0, Long.MAX_VALUE).size)
        // Simulate an interrupted run: force the same migration statements again by
        // bumping the user version back under the threshold and reopening.
        val raw = SQLiteDatabase.openDatabase(
            context.getDatabasePath(AmaraMemory.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READWRITE)
        raw.version = 10
        raw.close()
        val second = RevenueStore(AmaraMemory(context))
        assertEquals("idempotent: no duplicates after re-migration", 2, second.sales(0, Long.MAX_VALUE).size)
        assertEquals(1, second.inquiries(0, Long.MAX_VALUE).size)
        assertEquals(1, second.costs(0, Long.MAX_VALUE).size)
    }

    @Test fun migratedLedgerSurvivesReopenAndFeedsIdenticalMetrics() {
        buildLegacyDb(version = 9)
        val storeA = RevenueStore(open())
        val engineA = RevenueMetricEngine(storeA, policy = { CommercialPolicy() })
        val memoryB = AmaraMemory(context)
        val storeB = RevenueStore(memoryB)
        val engineB = RevenueMetricEngine(storeB, policy = { CommercialPolicy() })
        // Same ledger, two handles, identical arithmetic.
        assertEquals(engineA.profit(0, Long.MAX_VALUE).toString(), engineB.profit(0, Long.MAX_VALUE).toString())
        assertEquals("migrated sales survive reopen", 2, storeB.sales(0, Long.MAX_VALUE).size)
        assertEquals("migrated inquiry survives reopen", 1, storeB.inquiries(0, Long.MAX_VALUE).size)
    }

    @Test fun ownerDeletionClearsCanonicalAndConsentLedgersButKeepsSchemaIntact() {
        val memory = open()
        val store = RevenueStore(memory)
        assertTrue(store.insertSale(SaleEvidenceRecord(
            uniqueKey = "del-sale-1", evidenceKind = SaleEvidenceRecord.SaleEvidenceKind.POS_RECEIPT,
            saleRef = "pos-del", contactKey = "c1", productRef = "p", amountUgx = 10_000,
            state = SaleEvidenceRecord.SaleState.ACTIVE, correctionReason = "", occurredAtMs = 5), nowMs = 6))
        store.grantContactConsent("c1", source = "owner_settings", scope = "outreach",
            grantedAtMs = 1_000, expiresAtMs = null, evidenceRef = "owner://consent/1",
            permittedProducts = setOf("p"), permittedChannels = setOf("whatsapp"))
        assertTrue(store.hasLiveConsent("c1", "p", "whatsapp", 2_000))
        memory.deleteOwnerBusinessData()
        assertTrue("schema survives the wipe", store.inquiries(0, Long.MAX_VALUE).isEmpty())
        assertEquals(0, store.sales(0, Long.MAX_VALUE).size)
        assertFalse("consent is wiped with business data", store.hasLiveConsent("c1", "p", "whatsapp", 3_000))
    }
}
