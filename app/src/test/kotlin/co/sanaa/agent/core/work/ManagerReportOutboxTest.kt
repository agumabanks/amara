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
class ManagerReportOutboxTest {
    @Test fun fullQueueAndRestartPreserveOriginalReportUntilAdmission() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("manager_report_outbox",0).edit().clear().commit()
        val item=ManagerReportWork.from("+256700000001","order:1","Confirmed order update")!!
        val outbox=ManagerReportOutbox(context)
        assertFalse(outbox.enqueue(item) { WorkQueue.OfferResult.REJECTED_CAP })
        assertFalse(outbox.enqueue(item.copy(payload=org.json.JSONObject(item.payload.toString()).put("message","Changed"))) { WorkQueue.OfferResult.REJECTED_CAP })
        val restarted=ManagerReportOutbox(context)
        assertEquals(1,restarted.pendingCount())
        assertEquals(1,restarted.flush {
            assertEquals(item.dedupeKey,it.dedupeKey)
            assertEquals("Confirmed order update",it.payload.optString("message"))
            WorkQueue.OfferResult.ACCEPTED
        })
        assertEquals(0,restarted.pendingCount())
        assertEquals(0,restarted.flush { error("Must not reoffer") })
    }
}
