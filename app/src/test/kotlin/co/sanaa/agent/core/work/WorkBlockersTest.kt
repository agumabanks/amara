package co.sanaa.agent.core.work

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkBlockersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun item(kind: WorkKind = WorkKind.TIKTOK_POST_PUBLISH) = WorkItem("task", Domain.TIKTOK, kind,
        baseValueKes = 1.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 1)
    @Before fun reset() { context.getSharedPreferences("work_blockers", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun failureSurvivesRestartAndUnrelatedSuccessUntilItsOwnSuccess() {
        val store = WorkBlockers(context)
        store.outcome(WorkResult(item(), WorkStatus.FAILED, failure = FailureInfo(FailureClass.UI_MISMATCH, "TikTok caption editor missing")))
        WorkBlockers(context).outcome(WorkResult(item(WorkKind.MARKET_ANALYSIS), WorkStatus.DONE))
        assertEquals("TikTok caption editor missing", WorkBlockers(context).rows().single()["reason"])
        assertEquals(false, WorkBlockers(context).rows().single()["global"])
        WorkBlockers(context).outcome(WorkResult(item(), WorkStatus.DONE))
        assertTrue(store.rows().isEmpty())
    }

    @Test fun ownerBlockerAlertsOnceAndDisappearsOnlyAboveChargeThreshold() {
        fun battery(level: Int) = context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level).putExtra(BatteryManager.EXTRA_SCALE, 100))
        val store = WorkBlockers(context)
        battery(7); store.refreshBattery()
        assertEquals(true, store.rows().single()["ownerAction"])
        assertTrue(store.rows().single()["reason"].toString().contains("7%"))
        val manager = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
        val count = manager.allNotifications.size
        store.refreshBattery(); assertEquals(count, manager.allNotifications.size)
        battery(15); store.refreshBattery(); assertEquals(1, store.rows().size)
        battery(16); store.refreshBattery(); assertTrue(store.rows().isEmpty())
        assertEquals(count - 1, manager.allNotifications.size)
    }

    @Test fun admissionDoesNotEraseUnconfirmedFailureAndRedactsSecrets() {
        val store = WorkBlockers(context)
        store.outcome(WorkResult(item(), WorkStatus.ESCALATED, failure = FailureInfo(FailureClass.UNKNOWN, "Outcome unproven; terminal PIN 1234", false)))
        store.deferred(item(), "Daily screen budget exhausted")
        store.admitted(item())
        assertEquals(1, store.rows().size)
        assertEquals(true, store.rows().single()["ownerAction"])
        assertFalse(store.rows().single()["reason"].toString().contains("1234"))
    }

    @Test fun anotherDestinationCannotClearFailureAndMissingReasonsAreExplicit() {
        val store = WorkBlockers(context)
        val failed = item(WorkKind.WA_BROADCAST).copy(payload = org.json.JSONObject().put("group_target", "Group A"))
        store.outcome(WorkResult(failed, WorkStatus.FAILED))
        store.outcome(WorkResult(failed.copy(payload = org.json.JSONObject().put("group_target", "Group B")), WorkStatus.DONE))
        assertTrue(store.rows().single()["reason"].toString().contains("without a failure reason"))
        assertEquals(true, store.rows().single()["ownerAction"])
    }

    @Test fun managerSuccessDoesNotClearCustomerConversationBlocker() {
        val store = WorkBlockers(context)
        val customer = item(WorkKind.WA_REPLY_INBOUND).copy(payload=org.json.JSONObject().put("conversation_identity","wa-origin:customer"))
        store.outcome(WorkResult(customer,WorkStatus.FAILED,failure=FailureInfo(FailureClass.UI_MISMATCH,"Original message unavailable")))
        store.outcome(WorkResult(ManagerReportWork.from("+256700123456","status","Update")!!,WorkStatus.DONE))
        assertEquals("Original message unavailable",store.rows().single()["reason"])
    }

    @Test fun legacyGroupAlertClearsOnlyAfterLaterSuccessAndLeavesEvidence() {
        val store=WorkBlockers(context)
        val target="Exact group"
        val oldKey="failure:WA_BROADCAST:${co.sanaa.agent.core.ContentHashing.hash("$target::")}"
        store.flag(oldKey,"Group","Old rejection",now=1000)
        store.reconcileGroups(listOf(target to 500L))
        assertTrue(store.rows().any { it["reason"]=="Old rejection" })
        store.reconcileGroups(listOf(target to 2000L))
        assertFalse(store.rows().any { it["reason"]=="Old rejection" })
        assertTrue(store.resolved().any { it["evidence"].toString().contains("later group promotion") })
        store.flag(oldKey,"Group","New failure",now=3000)
        store.reconcileGroups(listOf(target to 2000L))
        assertTrue(store.rows().any { it["reason"]=="New failure" })
    }

    @Test fun normalScheduleWaitDoesNotCreateDeliveryFailure() {
        val store=WorkBlockers(context)
        store.outcome(WorkResult(item(WorkKind.WA_BROADCAST),WorkStatus.SKIPPED,
            failure=FailureInfo(FailureClass.POLICY_BLOCKED,"Group schedule is paused or not due",false)))
        assertTrue(store.rows().isEmpty())
    }

    @Test fun droppedTaskDoesNotPromiseAutomaticRetry() {
        val store = WorkBlockers(context)
        val result = WorkResult(item(), WorkStatus.FAILED, failure = FailureInfo(FailureClass.UI_MISMATCH, "Caption unavailable"))
        store.outcome(result, RecoveryDecision.Drop)
        assertEquals(true, store.rows().single()["ownerAction"])
        assertTrue(store.rows().single()["action"].toString().contains("Confirm the outcome"))
    }
}
