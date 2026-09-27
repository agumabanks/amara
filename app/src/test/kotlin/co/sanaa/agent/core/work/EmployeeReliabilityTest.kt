package co.sanaa.agent.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.ManagerConsultations
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmployeeReliabilityTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("amara_work_queue.db")
        context.getSharedPreferences("manager_consultations", 0).edit().clear().commit()
    }

    private fun inbound(key: String, message: String, at: Long, manager: Boolean = false) = WorkItem(
        key, Domain.WHATSAPP, WorkKind.WA_REPLY_INBOUND,
        JSONObject().put("inbound", true).put("conversation_identity", "origin-a")
            .put("conversation", "Same chat").put("message", message).put("inbound_message_at", at)
            .put("manager_command_candidate", manager),
        baseValueKes = 100.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 45)

    @Test fun twoManagerInstructionsSurviveNotificationReorderingAndRestart() {
        WorkQueue(context).use { queue ->
            assertEquals(WorkQueue.OfferResult.ACCEPTED, queue.offer(inbound("second", "Check orders", 200, true)))
            assertEquals(WorkQueue.OfferResult.ACCEPTED, queue.offer(inbound("first", "Review stock", 100, true)))
            assertEquals(WorkQueue.OfferResult.ACCEPTED, queue.offer(inbound("third", "Show the report", 300, true)))
        }
        WorkQueue(context).use { queue ->
            queue.compactPendingBacklog()
            assertEquals(setOf("first", "second", "third"), queue.allPending().map { it.dedupeKey }.toSet())
            assertEquals("first", queue.peekBest(System.currentTimeMillis())?.dedupeKey)
            assertEquals(WorkQueue.OfferResult.DEDUPED, queue.offer(inbound("first", "Review stock", 100, true)))
        }
    }

    @Test fun followupMessageDoesNotEraseAnUnansweredCustomerQuestion() {
        WorkQueue(context).use { queue ->
            queue.offer(inbound("price", "What is the price?", 100))
            queue.offer(inbound("delivery", "Can you deliver to Ntinda?", 200))
            assertEquals(setOf("price", "delivery"), queue.allPending().map { it.dedupeKey }.toSet())
        }
    }

    @Test fun doctorPreservesAValidQueuedOutboundReply() {
        WorkQueue(context).use { queue ->
            val reply = ManagerReportWork.from("+256700000001", "customer-reply", "Your item is ready")!!
                .copy(payload = JSONObject().put("target", "+256700000001").put("message", "Your item is ready"))
            queue.offer(reply)
            assertEquals(0, queue.compactPendingBacklog())
            assertEquals(reply.dedupeKey, queue.allPending().single().dedupeKey)
        }
    }

    @Test fun navigationRecoveryReservesRoomForFreshInboundWork() {
        WorkQueue(context).use { queue ->
            val held = ManagerReportWork.from("+256700000001","held","Held report")!!
            queue.offer(held)
            queue.requireReview(held,"Exact queued reply destination unavailable: contact information missing")
            repeat(20) { queue.offer(inbound("new-$it","Question $it",it.toLong())) }
            assertEquals(0,queue.recoverUnsentWhatsApp(hasReceipt={false}))
            assertEquals(20,queue.allPending().size)
            queue.complete("new-0")
            assertEquals(1,queue.recoverUnsentWhatsApp(hasReceipt={false}))
            assertEquals(20,queue.allPending().size)
        }
    }

    @Test fun navigationRecoveryNeverReplaysAReceiptAndRunsOnlyOncePerDraft() {
        WorkQueue(context).use { queue ->
            fun draft(key: String) = ManagerReportWork.from("+256700000001", key, "Report $key")!!
            val unsent=draft("unsent"); val uncertain=draft("uncertain")
            listOf(unsent,uncertain).forEach {
                queue.offer(it)
                queue.requireReview(it,"Exact queued reply destination unavailable: contact information missing")
            }
            assertEquals(1,queue.recoverUnsentWhatsApp(hasReceipt={it==uncertain.dedupeKey}))
            val recovered=queue.allPending().single()
            assertEquals(unsent.dedupeKey,recovered.dedupeKey)
            queue.requireReview(recovered,"Exact queued reply destination unavailable: contact information missing")
            assertEquals(0,queue.recoverUnsentWhatsApp(hasReceipt={it==uncertain.dedupeKey}))
        }
    }

    @Test fun lowercaseConsultationReferenceIsAcceptedWithoutChangingItsCustomer() {
        val consultations = ManagerConsultations(context)
        val ref = consultations.open("source", "shop-a", "customer-a", "Customer", "Question")
        assertEquals(ref, consultations.reference("${ref.lowercase()} Delivery is tomorrow"))
        assertEquals("customer-a", consultations.get(ref)!!.getString("contactId"))
    }

    @Test fun aDifferentShopsConsultationsDoNotInterceptManagerInstructions() {
        val consultations = ManagerConsultations(context)
        val a = consultations.open("source", "shop-a", "customer-a", "Customer A", "Question")
        val b = consultations.open("source", "shop-b", "customer-b", "Customer B", "Question")
        assertEquals(listOf(a), consultations.waitingReferences("shop-a"))
        assertEquals(listOf(b), consultations.waitingReferences("shop-b"))
        assertTrue(consultations.waitingReferences("").isEmpty())
        consultations.claim(a, "answer-a", "Answer")
        assertTrue(consultations.waitingReferences("shop-a").isEmpty())
    }

    @Test fun duplicateBlockIsSuccessfulOnlyWhenTheOriginalDeliveryWasVerified() {
        val item = inbound("reply", "Question", 100)
        for (state in co.sanaa.agent.core.SideEffectState.entries) {
            val result = WorkExecutor.duplicateWhatsAppResult(item,
                co.sanaa.agent.core.SideEffectOutcome.DuplicateBlocked(state))
            if (state == co.sanaa.agent.core.SideEffectState.VERIFIED) {
                assertEquals(WorkStatus.DONE, result.status)
                assertNull(result.failure)
            } else {
                assertEquals(state.name, WorkStatus.ESCALATED, result.status)
                assertEquals(false, result.failure?.recoverable)
            }
        }
    }

    @Test fun modelDeferralRequeuesCommunityWorkWithoutInventingAComment() {
        val item = TikTokCommunityWork.from("verified-post")!!
        val failure = co.sanaa.agent.modules.TikTokCommunityObservation.failure("MODEL_DEFERRED", "")!!
        val result = WorkResult(item, co.sanaa.agent.modules.TikTokCommunityObservation.status("MODEL_DEFERRED", ""), failure = failure)
        assertEquals(WorkStatus.SKIPPED, result.status)
        assertTrue(failure.recoverable)
        assertEquals(300_000L, failure.retryAfterMs)
        val governor = SafetyGovernor(context)
        repeat(4) { governor.recordExecution(result) }
        assertFalse(governor.isKindBreakerOpen(item.kind))
        governor.close()
        assertEquals(WorkStatus.ESCALATED, co.sanaa.agent.modules.TikTokCommunityObservation.status("UNCERTAIN", ""))
        assertEquals(false, co.sanaa.agent.modules.TikTokCommunityObservation.failure("UNCERTAIN", "")?.recoverable)
    }
}
