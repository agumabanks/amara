package co.sanaa.agent.core.work.sources

import android.content.Context
import co.sanaa.agent.core.CommitmentStore
import co.sanaa.agent.core.work.*
import org.json.JSONObject

/** Confirmed, explicitly addressed commitments only. Proposing never sends. */
class MeetingReminderSource(
    private val context: Context,
    private val enabled: () -> Boolean,
) : WorkSource {
    override val domain = Domain.WHATSAPP

    override suspend fun propose(snapshot: WorldSnapshot): List<WorkItem> {
        if (!enabled() || !snapshot.canSendExternal) return emptyList()
        val now = System.currentTimeMillis()
        return CommitmentStore(context).useStore { store ->
            store.dueReminders(now).mapNotNull { commitment ->
                val target = commitment.participants.optJSONObject(0)?.optString("target").orEmpty()
                if (target.isBlank() || commitment.reminderRecipients != "customer" ||
                    commitment.timezone.isBlank() || commitment.agreedAt <= now ||
                    now > commitment.agreedAt - commitment.reminderLeadMinutes * 60_000L + 5 * 60_000L) return@mapNotNull null
                val dueAt = commitment.agreedAt - commitment.reminderLeadMinutes * 60_000L
                WorkItem(
                    dedupeKey = "meeting-reminder:${commitment.id}:${commitment.revision}:1",
                    domain = Domain.WHATSAPP, kind = WorkKind.WA_MEETING_REMINDER,
                    payload = JSONObject().put("commitment_id", commitment.id)
                        .put("revision", commitment.revision).put("due_at", dueAt)
                        .put("target", target).put("conversation_key", commitment.conversationKey),
                    baseValueKes = 300.0, deadline = commitment.agreedAt,
                    urgencyHalfLifeHours = 0.1, estimatedScreenSeconds = 40,
                    requires = setOf(Capability.SCREEN, Capability.NETWORK, Capability.CONSENT_TIER_2),
                    riskTier = RiskTier.MEDIUM,
                )
            }
        }
    }
}

private inline fun <T> CommitmentStore.useStore(block: (CommitmentStore) -> T): T =
    try { block(this) } finally { close() }
