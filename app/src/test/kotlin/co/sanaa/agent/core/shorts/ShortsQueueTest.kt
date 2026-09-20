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
        s.set("youtubeDailyCap",99);s.set("youtubeIntervalMinutes",0)
        val restored=ShortsSettings(context)
        assertEquals("Private",restored.visibility);assertTrue(restored.madeForKids)
        assertEquals("@sanaa",restored.channel);assertEquals(24,restored.dailyCap);assertEquals(10,restored.intervalMinutes)
    }
}
