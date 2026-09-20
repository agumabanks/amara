package co.sanaa.agent.modules

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class HealthIssueHistoryTest {
    @Test fun repairRequestDoesNotResolveAndRecurrenceSurvivesRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("health_issue_history",0).edit().clear().commit()
        val store = HealthIssueHistory(context)
        store.observe(listOf("Autonomous loop stalled"),100,true)
        assertEquals(0L,(store.rows().single()["resolvedAt"] as Number).toLong())
        assertEquals(1,(store.rows().single()["recoveryAttempts"] as Number).toInt())
        store.observe(emptyList(),200)
        val restarted = HealthIssueHistory(context)
        assertEquals(200L,(restarted.rows().single()["resolvedAt"] as Number).toLong())
        restarted.observe(listOf("Autonomous loop stalled"),300)
        assertEquals(2,(restarted.rows().single()["episodes"] as Number).toInt())
        assertEquals(0L,(restarted.rows().single()["resolvedAt"] as Number).toLong())
    }
    @Test fun managerMessageIncludesNextStepWithoutInventingRecovery() {
        val message = co.sanaa.agent.core.work.ManagerReportWork.blockerMessage(listOf("Battery is 7%; screen work pauses at 15% or lower"))
        assertTrue(message.contains("connect the phone to power"))
        assertFalse(message.contains("I requested"))
        assertTrue(message.contains("not confirmed deliveries"))
    }
}
