package co.sanaa.agent.core.work

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class GroupBreakerIsolationTest {
    @Test fun managerAlertDoesNotOutrankDueTikTokButCustomerStillDoes() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("amara_work_queue.db")
        WorkQueue(context).use { queue ->
            val report=ManagerReportWork.from("+256700123456","health","Update")!!
            val post=report.copy(dedupeKey="post",kind=WorkKind.TIKTOK_POST_PUBLISH,domain=Domain.TIKTOK,payload=org.json.JSONObject())
            queue.offer(report);queue.offer(post)
            assertEquals("post",queue.peekBest(System.currentTimeMillis())?.dedupeKey)
            val customer=report.copy(dedupeKey="customer",payload=org.json.JSONObject())
            queue.offer(customer)
            assertEquals("customer",queue.peekBest(System.currentTimeMillis())?.dedupeKey)
        }
    }

    @Test fun bulkClosureKeepsNewAndRunningWork() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("amara_work_queue.db")
        WorkQueue(context).use { queue ->
            val first=ManagerReportWork.from("+256700123456","first","Update")!!
            val second=ManagerReportWork.from("+256700123456","second","Update")!!
            val running=ManagerReportWork.from("+256700123456","running","Update")!!
            listOf(first,second,running).forEach { queue.offer(it) }
            queue.requireReview(first,"Uncertain");queue.requireReview(second,"Uncertain")
            queue.markInFlight(running.dedupeKey)
            assertEquals(1,queue.closeReviews(listOf(first.dedupeKey,running.dedupeKey),"handled_elsewhere"))
            assertEquals(1,(queue.dashboard()["needsReview"] as List<*>).size)
            assertFalse(queue.archivePending(running.dedupeKey))
        }
    }

    @Test fun managerReportsDoNotConsumeCustomerEmergencyReserve() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("amara_safety.db")
        val governor=SafetyGovernor(context)
        try {
            val before=governor.inboundReserveSecondsToday()
            val report=ManagerReportWork.from("+256700123456","health","Issue needs review")!!
            governor.recordExecution(WorkResult(report,WorkStatus.DONE,screenSecondsUsed=45))
            assertEquals(before,governor.inboundReserveSecondsToday())
            val customer=report.copy(payload=org.json.JSONObject())
            governor.recordExecution(WorkResult(customer,WorkStatus.DONE,screenSecondsUsed=20))
            assertEquals(before+20,governor.inboundReserveSecondsToday())
        } finally { governor.close() }
    }

    @Test fun customerBreakerCannotSilenceManagerButManagerFailuresStillBackOff() {
        val governor=SafetyGovernor(ApplicationProvider.getApplicationContext<Context>())
        try {
            val now=System.currentTimeMillis()
            governor.writableDatabase.execSQL("INSERT INTO kind_breakers(kind,last_trip_at,cooldown_until) VALUES (?,?,?)",
                arrayOf<Any>(WorkKind.WA_REPLY_INBOUND.name,now,now+3600000))
            val report=ManagerReportWork.from("+256700123456","blocker","Charging required")!!
            assertTrue(governor.isAllowed(report,0,0).allowed)
            assertFalse(governor.isAllowed(report.copy(payload=org.json.JSONObject()),0,0).allowed)
            repeat(3) { governor.recordExecution(WorkResult(report,WorkStatus.FAILED)) }
            assertFalse(governor.isAllowed(report,0,0).allowed)
        } finally { governor.close() }
    }

    @Test fun incomingRepliesCannotExhaustScheduledGroupCadence() {
        val governor=SafetyGovernor(ApplicationProvider.getApplicationContext<Context>())
        try {
            val reply=WorkItem("reply",Domain.WHATSAPP,WorkKind.WA_REPLY_INBOUND,
                baseValueKes=1.0,urgencyHalfLifeHours=1.0,estimatedScreenSeconds=1)
            repeat(40) { governor.recordExecution(WorkResult(reply,WorkStatus.DONE)) }
            val group=reply.copy(kind=WorkKind.WA_BROADCAST,payload=org.json.JSONObject().put("group_target","Approved group"),requires=setOf(Capability.CONSENT_TIER_2))
            assertTrue(governor.isAllowed(group,0,0).allowed)
            assertFalse(governor.isAllowed(group.copy(payload=org.json.JSONObject()),0,0).allowed)
            assertFalse(governor.isAllowed(group,0,3).allowed)
        } finally { governor.close() }
    }
    @Test fun historicalGroupFailuresDoNotBlockEveryDestination() {
        val governor=SafetyGovernor(ApplicationProvider.getApplicationContext<Context>())
        try {
            val now=System.currentTimeMillis()
            for(kind in listOf(WorkKind.WA_BROADCAST,WorkKind.TIKTOK_POST_PUBLISH)) {
                governor.writableDatabase.execSQL("INSERT INTO kind_breakers(kind,last_trip_at,cooldown_until) VALUES (?,?,?)",
                    arrayOf<Any>(kind.name,now,now+3600000))
            }
            val group=WorkItem("group",Domain.WHATSAPP,WorkKind.WA_BROADCAST,
                org.json.JSONObject().put("group_target","Approved group"),1.0,
                urgencyHalfLifeHours=1.0,estimatedScreenSeconds=60)
            assertTrue(governor.isAllowed(group,0,0).allowed)
            assertFalse(governor.isAllowed(group.copy(payload=org.json.JSONObject()),0,0).allowed)
            governor.transitionTo(SafetyGovernor.GovernorState.HALTED,"Owner pause")
            assertFalse(governor.isAllowed(group,0,0).allowed)
            assertTrue(governor.isKindBreakerOpen(WorkKind.TIKTOK_POST_PUBLISH))
        } finally { governor.close() }
    }
}
