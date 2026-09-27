package co.sanaa.agent.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.TerminalShopIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentEventOutboxTest {
    @Test fun skippedPartialAndEscalatedRemainDistinctWithoutPrivateReasons() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(AgentEventOutbox.DATABASE_NAME)
        context.getSharedPreferences("amara_event_binding", 0).edit().clear().commit()
        val outbox = AgentEventOutbox(context) { TerminalShopIdentity(254, 24, "A", Long.MAX_VALUE, "signed") }
        outbox.bindShop("254:24", 1)
        val item = WorkItem("private-contact:private-body", Domain.WHATSAPP, WorkKind.WA_REPLY_INBOUND,
            baseValueKes = 1.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 1)
        listOf(WorkStatus.SKIPPED, WorkStatus.PARTIAL, WorkStatus.ESCALATED).forEachIndexed { index, status ->
            assertTrue(outbox.enqueueOutcome(WorkResult(item.copy(attempt = index), status,
                outcomeFacts = listOf("private-body"))))
        }
        val events = outbox.pending().map { it.second }
        assertEquals(listOf("skipped", "partial", "escalated"), events.map { it.getString("status") })
        assertFalse(events.joinToString().contains("private-body"))
        outbox.close()
    }

    @Test fun effectTransitionsLinkToWorkWithoutExportingTarget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(AgentEventOutbox.DATABASE_NAME)
        context.getSharedPreferences("amara_event_binding", 0).edit().clear().commit()
        val outbox = AgentEventOutbox(context) { TerminalShopIdentity(254, 24, "A", Long.MAX_VALUE, "signed") }
        outbox.bindShop("254:24", 1)
        val privateKey = "customer:+256700000001:private-body"
        assertTrue(outbox.enqueueEffect("CLAIMED", "post_tiktok", privateKey))
        assertTrue(outbox.enqueueEffect("VERIFICATION_PENDING", "post_tiktok", privateKey))
        assertTrue(outbox.enqueueEffect("VERIFIED", "post_tiktok", privateKey))
        val events = outbox.pending().map { it.second }
        assertEquals(listOf("effect.claimed", "effect.dispatched", "effect.verified"), events.map { it.getString("event_type") })
        assertEquals(1, events.map { it.getString("effect_id") }.distinct().size)
        assertEquals(1, events.map { it.getString("job_id") }.distinct().size)
        assertEquals("device_verifier", events.last().getJSONObject("facts").getString("verification_method"))
        assertFalse(events.joinToString().contains("+256700000001"))
        val work = WorkResult(WorkItem(privateKey, Domain.TIKTOK, WorkKind.TIKTOK_POST_PUBLISH,
            baseValueKes = 1.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 1), WorkStatus.DONE)
        assertTrue(outbox.enqueueOutcome(work))
        assertEquals(events[0].getString("job_id"), outbox.pending().last().second.getString("job_id"))
        assertEquals(events[0].getString("attempt_id"), outbox.pending().last().second.getString("attempt_id"))
        outbox.close()
    }

    @Test fun replyEffectUsesInboundJobAndItsOwnStableEffectIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(AgentEventOutbox.DATABASE_NAME)
        context.getSharedPreferences("amara_event_binding", 0).edit().clear().commit()
        val outbox = AgentEventOutbox(context) { TerminalShopIdentity(254, 24, "A", Long.MAX_VALUE, "signed") }
        outbox.bindShop("254:24", 1)
        val workKey = "inbound:private-contact:private-message"
        val effectKey = "human-reply:private-contact:private-message:hash"
        assertTrue(outbox.enqueueEffect("CLAIMED", "reply_whatsapp", effectKey, 2, workKey))
        assertTrue(outbox.enqueueEffect("VERIFIED", "reply_whatsapp", effectKey, 2, workKey))
        val item = WorkItem(workKey, Domain.WHATSAPP, WorkKind.WA_REPLY_INBOUND,
            baseValueKes = 1.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 1, attempt = 2)
        assertTrue(outbox.enqueueOutcome(WorkResult(item, WorkStatus.DONE)))
        val events = outbox.pending().map { it.second }
        assertEquals(1, events.map { it.getString("job_id") }.distinct().size)
        assertEquals(1, events.map { it.getString("attempt_id") }.distinct().size)
        assertEquals(events[0].getString("effect_id"), events[1].getString("effect_id"))
        assertFalse(events.joinToString().contains("private-contact"))
        assertFalse(events.joinToString().contains("private-message"))
        outbox.close()
    }

    @Test fun signedBindingAndRestartKeepPrivateWorkOutcomesScoped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(AgentEventOutbox.DATABASE_NAME)
        context.getSharedPreferences("amara_event_binding", 0).edit().clear().commit()
        var shop = TerminalShopIdentity(254, 24, "A", Long.MAX_VALUE, "signed")
        val outbox = AgentEventOutbox(context) { shop }
        val item = WorkItem("customer:+256700000001:private-body", Domain.WHATSAPP, WorkKind.WA_REPLY_INBOUND,
            baseValueKes = 1.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 1)
        val outcome = WorkResult(item, WorkStatus.DONE, outcomeFacts = listOf("private customer message"))
        assertFalse(outbox.enqueueOutcome(outcome))
        outbox.bindShop("254:24", 1)
        assertTrue(outbox.enqueueOutcome(outcome))
        val first = outbox.pending().single().second
        assertEquals(1, first.getLong("binding_revision"))
        assertEquals("completed", first.getString("status"))
        assertFalse(first.toString().contains("+256700000001"))
        assertFalse(first.toString().contains("private customer message"))

        shop = TerminalShopIdentity(319, 37, "B", Long.MAX_VALUE, "signed")
        assertFalse(outbox.enqueueOutcome(outcome))
        outbox.bindShop("319:37", 2)
        assertTrue(outbox.enqueueOutcome(outcome.copy(item = item.copy(attempt = 1))))
        val restarted = AgentEventOutbox(context) { shop }
        val pending = restarted.pending()
        assertEquals(2, pending.size)
        assertEquals(2, pending[1].second.getLong("binding_revision"))
        assertEquals(first.getString("job_id"), pending[1].second.getString("job_id"))
        assertNotEquals(first.getString("attempt_id"), pending[1].second.getString("attempt_id"))
        restarted.acknowledge(listOf(pending[0].first))
        assertEquals(1, restarted.pendingCount())
        assertTrue(restarted.enqueueOutcome(outcome.copy(item = item.copy(attempt = 2))))
        assertTrue(restarted.pending().last().second.getLong("sequence") > pending[1].second.getLong("sequence"))
        assertEquals("media_dimensions_invalid", restarted.safeReasonCode("Invalid or oversized TikTok image dimensions for private customer data"))
        assertNull(restarted.safeReasonCode("private customer data"))
        restarted.close()
        outbox.close()
    }
}
