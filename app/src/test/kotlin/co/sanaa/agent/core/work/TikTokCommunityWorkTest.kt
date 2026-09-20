package co.sanaa.agent.core.work

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class TikTokCommunityWorkTest {
    @Test fun sessionEndsAtFourAndPreservesOriginalDeadline() {
        var item=TikTokCommunityWork.from("post-42",startedAt=1000)!!
        val keys=mutableSetOf(item.dedupeKey)
        repeat(3) { item=TikTokCommunityWork.next(item)!!;keys.add(item.dedupeKey);assertEquals(1501000L,item.deadline) }
        assertEquals(4,keys.size);assertNull(TikTokCommunityWork.next(item))
    }
    @Test fun unrelatedSocialWorkCannotSpawnSession() {
        assertNull(TikTokCommunityWork.from(""))
        val item=WorkItem("routine",Domain.TIKTOK,WorkKind.TIKTOK_COMMENT_REPLY,baseValueKes=1.0,urgencyHalfLifeHours=1.0,estimatedScreenSeconds=1)
        assertNull(TikTokCommunityWork.next(item))
    }
    @Test fun routineScansDoNotDeleteCustomerCommentsOrCommunitySteps() {
        val c=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        c.deleteDatabase("amara_work_queue.db")
        val queue=WorkQueue(c)
        try {
            val community=TikTokCommunityWork.from("post")!!
            val customer=community.copy(dedupeKey="notification-1",payload=org.json.JSONObject().put("notification_id","one"))
            queue.offer(customer);queue.offer(community)
            queue.offer(community.copy(dedupeKey="scan-1",payload=org.json.JSONObject()))
            queue.offer(community.copy(dedupeKey="scan-2",payload=org.json.JSONObject()))
            val keys=queue.allPending().map { it.dedupeKey }
            assertTrue(keys.contains(customer.dedupeKey));assertTrue(keys.contains(community.dedupeKey))
            assertFalse(keys.contains("scan-1"));assertTrue(keys.contains("scan-2"))
        } finally { queue.close() }
    }
}
