package co.sanaa.agent.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShopAdQuarantineTest {
    @Test fun maintenancePreservesDistinctLegacyQuestionsAndPendingAds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("amara_work_queue.db")
        WorkQueue(context).use { queue ->
            val db = queue.writableDatabase
            for (key in listOf("question-1", "question-2")) {
                db.execSQL("INSERT INTO work_items(dedupe_key,domain,kind,payload,base_value_kes,urgency_half_life_hours,estimated_screen_seconds,created_at,status) VALUES(?,?,?,?,1,1,10,1,'PENDING')",
                    arrayOf(key,"WHATSAPP","WA_REPLY_INBOUND",JSONObject().put("conversation","Customer").put("conversation_identity","same-origin").put("message",key).toString()))
            }
            assertEquals(0,queue.compactPendingBacklog())
            assertEquals(2,queue.allPending().size)
        }
    }
    @Test fun holdsOldAdsWithoutDeletingPayloadOrCustomerWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("amara_work_queue.db")
        WorkQueue(context).use { queue ->
            fun offer(key: String, scope: String, kind: WorkKind = WorkKind.WA_BROADCAST) {
                queue.offer(WorkItem(key, Domain.WHATSAPP, kind,
                    JSONObject().put("shop_scope",scope).put("message","retained draft"),
                    10.0, urgencyHalfLifeHours=1.0, estimatedScreenSeconds=10))
            }
            offer("old-ad","1:2")
            offer("current-ad","3:4")
            offer("customer","1:2",WorkKind.WA_REPLY_INBOUND)
            assertEquals(1,queue.quarantineOtherShopAds("3:4"))
            assertEquals(0,queue.quarantineOtherShopAds("3:4"))
            queue.readableDatabase.rawQuery("SELECT status,payload FROM work_items WHERE dedupe_key='old-ad'",null).use {
                assertTrue(it.moveToFirst());assertEquals("NEEDS_REVIEW",it.getString(0))
                assertEquals("retained draft",JSONObject(it.getString(1)).getString("message"))
            }
            queue.readableDatabase.rawQuery("SELECT COUNT(*) FROM work_items WHERE status='PENDING'",null).use {
                it.moveToFirst();assertEquals(2,it.getInt(0))
            }
        }
    }
}
