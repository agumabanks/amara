package co.sanaa.agent.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ArchivePendingTest {
    @Test fun archiveRetainsReceiptAndCannotCancelClaimedWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("amara_work_queue.db")
        WorkQueue(context).use { queue ->
            val pending = ManagerReportWork.from("+256700000001", "pending", "Update")!!
            val running = ManagerReportWork.from("+256700000001", "running", "Update")!!
            queue.offer(pending); queue.offer(running)
            assertTrue(queue.markInFlight(running.dedupeKey))
            assertFalse(queue.archivePending(running.dedupeKey))
            assertTrue(queue.archivePending(pending.dedupeKey))
            assertFalse(queue.archivePending(pending.dedupeKey))
            assertEquals(WorkQueue.OfferResult.DEDUPED, queue.offer(pending))
            val records = queue.evaluationSnapshot()
            val closed = (0 until records.length()).map { records.getJSONObject(it) }
                .single { it.optString("key") == co.sanaa.agent.core.ContentHashing.hash(pending.dedupeKey) }
            assertEquals("OWNER_CLOSED", closed.optString("queue_status"))
        }
    }
}
