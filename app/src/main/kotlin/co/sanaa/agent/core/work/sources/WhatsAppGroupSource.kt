package co.sanaa.agent.core.work.sources

import co.sanaa.agent.core.work.*
import org.json.JSONObject

/** Durable daily group work also catches up after a missed morning wake. */
data class GroupDestination(val id: String, val name: String)

/** A saved schedule cannot move to a different group when labels are renamed or reused. */
object GroupDestinationBinding {
    fun matches(savedId: String, savedName: String, current: List<GroupDestination>): Boolean =
        savedId.isNotBlank() && savedName.isNotBlank() &&
            current.filter { it.name == savedName }.singleOrNull()?.id == savedId
}

class WhatsAppGroupSource(
    private val enabled: () -> Boolean,
    private val groups: () -> List<GroupDestination>,
    private val dayKey: () -> String = { java.time.LocalDate.now().toString() },
    private val intervalFor: (GroupDestination) -> Int = { 1440 },
    private val nextDue: ((GroupDestination) -> Long)? = null,
) : WorkSource {
    override val domain = Domain.WHATSAPP
    override suspend fun propose(snapshot: WorldSnapshot): List<WorkItem> {
        if (!enabled() || !snapshot.canSendExternal || snapshot.quietHours) return emptyList()
        return groups().filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }.mapNotNull { group ->
            val due=nextDue?.invoke(group)
            if(due!=null && due>System.currentTimeMillis()) return@mapNotNull null
            WorkItem(
                dedupeKey = "wa-group-period:${due ?: System.currentTimeMillis()/(intervalFor(group).coerceIn(60,10080)*60_000L)}:${co.sanaa.agent.core.ContentHashing.hash(group.id + ":" + group.name)}",
                domain = domain, kind = WorkKind.WA_BROADCAST,
                payload = JSONObject().put("group_id", group.id).put("group_target", group.name),
                baseValueKes = 240.0, urgencyHalfLifeHours = 8.0, estimatedScreenSeconds = 60,
                requires = setOf(Capability.SCREEN, Capability.NETWORK, Capability.CONSENT_TIER_2),
                riskTier = RiskTier.MEDIUM,
            )
        }
    }
}
