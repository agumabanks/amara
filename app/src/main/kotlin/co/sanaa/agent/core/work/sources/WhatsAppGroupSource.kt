package co.sanaa.agent.core.work.sources

import co.sanaa.agent.core.work.*
import org.json.JSONObject

/** Durable daily group work also catches up after a missed morning wake. */
class WhatsAppGroupSource(
    private val enabled: () -> Boolean,
    private val groups: () -> List<String>,
    private val dayKey: () -> String = { java.time.LocalDate.now().toString() },
    private val intervalFor: (String) -> Int = { 1440 },
    private val nextDue: ((String) -> Long)? = null,
) : WorkSource {
    override val domain = Domain.WHATSAPP
    override suspend fun propose(snapshot: WorldSnapshot): List<WorkItem> {
        if (!enabled() || !snapshot.canSendExternal || snapshot.currentHour !in 8..20) return emptyList()
        return groups().distinct().mapNotNull { group ->
            val due=nextDue?.invoke(group)
            if(due!=null && due>System.currentTimeMillis()) return@mapNotNull null
            WorkItem(
                dedupeKey = "wa-group-period:${due ?: System.currentTimeMillis()/(intervalFor(group).coerceIn(60,10080)*60_000L)}:${co.sanaa.agent.core.ContentHashing.hash(group)}",
                domain = domain, kind = WorkKind.WA_BROADCAST,
                payload = JSONObject().put("group_target", group),
                baseValueKes = 240.0, urgencyHalfLifeHours = 8.0, estimatedScreenSeconds = 60,
                requires = setOf(Capability.SCREEN, Capability.NETWORK, Capability.CONSENT_TIER_2),
                riskTier = RiskTier.MEDIUM,
            )
        }
    }
}
