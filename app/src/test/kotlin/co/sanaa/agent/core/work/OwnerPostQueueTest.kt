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
class OwnerPostQueueTest {
    private fun item(key: String, payload: JSONObject = JSONObject(), attempt: Int = 0) = WorkItem(
        key, Domain.TIKTOK, WorkKind.TIKTOK_POST_PUBLISH, payload,
        baseValueKes = 100.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 120, attempt = attempt)

    @Test fun scheduledRefreshPreservesOwnerRequestsPreparedMediaAndRetriesAcrossRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkQueue(context).use { queue ->
            queue.offer(item("owner", JSONObject().put("owner_command", true)))
            queue.offer(item("prepared", JSONObject().put("listing_id", "42")))
            queue.offer(item("retry", attempt = 1))
            queue.offer(item("routine-old"))
            queue.offer(item("routine-new"))
        }
        WorkQueue(context).use { queue ->
            val keys = queue.readableDatabase.rawQuery("SELECT dedupe_key FROM work_items WHERE status='PENDING'", null).use {
                buildSet { while (it.moveToNext()) add(it.getString(0)) }
            }
            assertEquals(setOf("owner", "prepared", "retry", "routine-new"), keys)
            assertEquals(WorkQueue.OfferResult.DEDUPED, queue.offer(item("owner", JSONObject().put("owner_command", true))))
        }
    }
}
