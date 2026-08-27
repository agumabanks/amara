package co.sanaa.agent.core.commerce

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RevenueObservationPersistenceTest {
    private lateinit var context: Context
    private lateinit var memory: AmaraMemory
    private lateinit var store: RevenueStore
    private lateinit var ingestion: RevenueIngestion

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
        store = RevenueStore(memory)
        val policy = CommercialPolicy(
            allowedProducts = setOf("product-1"),
            approvedChannels = setOf("whatsapp"),
            permittedAudience = "consented customers",
        )
        val metrics = RevenueMetricEngine(store, policy = { policy })
        val guard = OutreachGuard(store, { policy }) { true }
        ingestion = RevenueIngestion(store, metrics, guard)
    }

    @Test fun campaignTouchIsDurableQueryableAndIdempotent() {
        val first = ingestion.recordCampaignTouch("campaign-1", "contact-1", 1_000, "status://evidence/1")
        assertTrue(first is RevenueIngestion.IngestionResult.Recorded)
        assertTrue(ingestion.recordCampaignTouch("campaign-1", "contact-1", 1_000, "status://evidence/1")
            is RevenueIngestion.IngestionResult.Refused)

        val reopened = RevenueStore(AmaraMemory(context))
        val rows = reopened.campaignTouches("campaign-1", "contact-1", 900, 1_100)
        assertEquals(1, rows.size)
        assertEquals((first as RevenueIngestion.IngestionResult.Recorded).uniqueKey, rows.single().uniqueKey)
        assertEquals("status://evidence/1", rows.single().evidenceRef)
    }

    @Test fun deliveryObservationIsDurableNormalizedAndIdempotent() {
        val first = ingestion.recordDeliveryObservation("contact-2", "content-sha", " delivered ", 2_000)
        assertTrue(first is RevenueIngestion.IngestionResult.Recorded)
        assertTrue(ingestion.recordDeliveryObservation("contact-2", "content-sha", "DELIVERED", 2_000)
            is RevenueIngestion.IngestionResult.Refused)

        val reopened = RevenueStore(AmaraMemory(context))
        val rows = reopened.deliveryObservations("contact-2", "content-sha", 1_900, 2_100)
        assertEquals(1, rows.size)
        assertEquals("DELIVERED", rows.single().deliveryState)
        assertEquals((first as RevenueIngestion.IngestionResult.Recorded).uniqueKey, rows.single().uniqueKey)
    }

    @Test fun invalidObservationsNeverCreateRows() {
        assertTrue(ingestion.recordCampaignTouch("", "contact", 1, "evidence") is RevenueIngestion.IngestionResult.Refused)
        assertTrue(ingestion.recordDeliveryObservation("contact", "hash", "", 1) is RevenueIngestion.IngestionResult.Refused)
        assertTrue(store.campaignTouches().isEmpty())
        assertTrue(store.deliveryObservations().isEmpty())
    }

    @Test fun v14UpgradeCreatesObservationTablesWithoutLosingExistingRevenue() {
        assertTrue(store.insertCost(
            CostEntry("cost-existing", CostEntry.CostComponent.SUBSCRIPTION, "", "invoice-1", 10_000, 500),
            nowMs = 600,
        ))
        memory.close()

        val path = context.getDatabasePath(AmaraMemory.DATABASE_NAME).path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE revenue_campaign_touches")
            db.execSQL("DROP TABLE revenue_delivery_observations")
            db.version = 14
        }

        val upgraded = RevenueStore(AmaraMemory(context))
        assertEquals(1, upgraded.costs(0, Long.MAX_VALUE).size)
        assertTrue(upgraded.campaignTouches().isEmpty())
        assertTrue(upgraded.deliveryObservations().isEmpty())
        assertTrue(upgraded.insertCampaignTouch(
            RevenueStore.CampaignTouch("touch-upgraded", "campaign", "contact", "evidence", 700, 701),
        ))
        assertTrue(upgraded.insertDeliveryObservation(
            RevenueStore.DeliveryObservation("delivery-upgraded", "contact", "hash", "READ", 800, 801),
        ))
    }

    @Test fun ownerDeletionClearsBothObservationLedgers() {
        assertTrue(ingestion.recordCampaignTouch("campaign", "contact", 1, "evidence") is RevenueIngestion.IngestionResult.Recorded)
        assertTrue(ingestion.recordDeliveryObservation("contact", "hash", "SENT", 2) is RevenueIngestion.IngestionResult.Recorded)
        memory.deleteOwnerBusinessData()
        assertTrue(store.campaignTouches().isEmpty())
        assertTrue(store.deliveryObservations().isEmpty())
    }
}
