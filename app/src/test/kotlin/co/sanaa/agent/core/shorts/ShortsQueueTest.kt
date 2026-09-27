package co.sanaa.agent.core.shorts

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ShortsQueueTest {
    @Test fun pendingLegacySourcesUseFifoAndCatalogueRowsStayHeld() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("shorts_queue.db")
        val payload=JSONObject().put("shop_scope","1:2").put("caption","Exact ad")
        try {
            ShortsQueue(context).use { q ->
                q.offer("first",payload)
                q.offer("second",payload)
                q.offer("future",payload,"catalogue_variant")
                assertEquals("first",q.next()!!.first)
                q.update("first","HELD","Review")
                assertEquals("second",q.next()!!.first)
                q.update("second","VERIFIED","Receipt")
                assertNull(q.next())
                assertEquals(listOf("second","first"),q.retainedSources().map { it.first })
            }
        } finally { context.deleteDatabase("shorts_queue.db") }
    }

    @Test fun shopSwitchSelectsOnlyCurrentShopWithoutDeletingOtherShopsQueue() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("shorts_queue.db")
        try {
            ShortsQueue(context).use { q ->
                q.offer("shop-a-first",JSONObject().put("shop_scope","254:24").put("caption","A"))
                q.offer("shop-b-first",JSONObject().put("shop_scope","708:128").put("caption","B"))
                q.offer("shop-a-second",JSONObject().put("shop_scope","254:24").put("caption","A2"))
                assertEquals("shop-b-first",q.nextForShop("708:128")!!.first)
                assertEquals("shop-a-first",q.nextForShop("254:24")!!.first)
                q.update("shop-b-first","UNCERTAIN","Upload may have happened")
                assertNull(q.nextForShop("708:128"))
                assertEquals("shop-a-first",q.nextForShop("254:24")!!.first)
                q.update("shop-a-first","VERIFIED","Receipt")
                assertEquals("shop-a-second",q.nextForShop("254:24")!!.first)
                assertNull(q.nextForShop(""))
                assertNull(q.nextForShop("unverified"))
            }
        } finally { context.deleteDatabase("shorts_queue.db") }
    }

    @Test fun additiveMigrationKeepsLegacyVersionPayloadAndUncertainHold() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("shorts_queue.db")
        val payload=JSONObject().put("shop_scope","1:2").put("caption","Old caption")
        val legacy=context.openOrCreateDatabase("shorts_queue.db",android.content.Context.MODE_PRIVATE,null)
        legacy.execSQL("CREATE TABLE shorts (source TEXT PRIMARY KEY,payload TEXT NOT NULL,state TEXT NOT NULL,at INTEGER NOT NULL,detail TEXT NOT NULL)")
        legacy.execSQL("INSERT INTO shorts(source,payload,state,at,detail) VALUES(?,?,?,?,?)",
            arrayOf<Any>("old-pending",payload.toString(),"PENDING",1L,"Waiting for export"))
        legacy.execSQL("INSERT INTO shorts(source,payload,state,at,detail) VALUES(?,?,?,?,?)",
            arrayOf<Any>("old-uncertain",payload.toString(),"UNCERTAIN",2L,"Upload tapped"))
        legacy.version=1
        legacy.close()
        try {
            ShortsQueue(context).use { q ->
                assertEquals(1,q.readableDatabase.version)
                assertEquals("old-pending",q.next()!!.first)
                assertEquals("Old caption",q.sourcePayload("old-uncertain")!!.getString("caption"))
                q.update("old-uncertain","PENDING","Unsafe replay")
                assertEquals("old-pending",q.next()!!.first)
                q.readableDatabase.rawQuery("SELECT source_kind FROM shorts WHERE source='old-pending'",null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("tiktok_export",it.getString(0))
                }
            }
        } finally { context.deleteDatabase("shorts_queue.db") }
    }
    @Test fun sourceIsDurableDeduplicatedAndUncertainIsNotEligible() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val payload=JSONObject().put("shop_scope","1:2").put("caption","Exact ad")
        ShortsQueue(context).use { q -> q.offer("post",payload);q.offer("post",payload);assertEquals("post",q.next()!!.first) }
        ShortsQueue(context).use { q ->
            assertEquals("Exact ad",q.next()!!.second.getString("caption"))
            q.update("post","UNCERTAIN","Upload tapped")
            q.offer("post",payload)
            q.update("post","PENDING","Attempted replay")
            q.update("post","HELD","Later preparation failed")
            assertNull(q.next());assertEquals("1 uncertain",q.summary())
            assertEquals("Exact ad",q.sourcePayload("post")!!.getString("caption"))
        }
    }
    @Test fun defaultsAreOffAndControlsPersistWithBounds() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val s=ShortsSettings(context)
        assertFalse(s.enabled);assertFalse(s.audioCleared)
        assertEquals("Public",s.visibility);assertFalse(s.madeForKids)
        assertFalse(s.set("youtubeVisibility","Everyone"))
        assertTrue(s.set("youtubeVisibility","Private"))
        assertTrue(s.set("youtubeMadeForKids",true))
        assertFalse(s.set("youtubeChannel","not a handle"))
        assertTrue(s.set("youtubeChannel","@sanaa"))
        assertFalse(s.set("youtubeTimezone", "Not/AZone"))
        assertTrue(s.set("youtubeTimezone", "Africa/Kampala"))
        s.set("youtubeDailyCap",99);s.set("youtubeIntervalMinutes",0)
        val restored=ShortsSettings(context)
        assertEquals("Private",restored.visibility);assertTrue(restored.madeForKids)
        assertEquals("@sanaa",restored.channel);assertEquals(24,restored.dailyCap);assertEquals(10,restored.intervalMinutes)
        assertEquals("Africa/Kampala", restored.zone.id)
    }
}
