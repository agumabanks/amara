package co.sanaa.agent.core.work

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class TikTokStoryWorkTest {
    @Test fun feedAndStoryHaveSeparateStableKeysAndImmutableFacts() {
        val payload=JSONObject().put("caption","Bamboo Clock UGX 100000").put("listing_id","service:24").put("product_fingerprint","exact")
        val story=TikTokStoryWork.from("post-1",payload)!!
        assertEquals("tiktok-story:post-1",story.dedupeKey)
        assertEquals(WorkKind.TIKTOK_STORY_PUBLISH,story.kind)
        assertEquals("exact",story.payload.getString("product_fingerprint"))
        assertFalse(payload.has("source_post_key"))
        val queue=WorkQueue(ApplicationProvider.getApplicationContext())
        try {
            assertEquals(WorkQueue.OfferResult.ACCEPTED,queue.offer(story))
            assertEquals(WorkQueue.OfferResult.DEDUPED,queue.offer(story))
        } finally { queue.close() }
    }
    @Test fun missingCatalogueIdentityCannotBecomeStoryWork() {
        assertNull(TikTokStoryWork.from("post",JSONObject().put("caption","hello")))
    }
}
