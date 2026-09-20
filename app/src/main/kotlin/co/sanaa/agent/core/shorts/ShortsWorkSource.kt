package co.sanaa.agent.core.shorts

import android.content.Context
import co.sanaa.agent.core.work.*

class ShortsWorkSource(private val context: Context) : WorkSource {
    override val domain = Domain.YOUTUBE
    override suspend fun propose(snapshot: WorldSnapshot): List<WorkItem> {
        val settings = ShortsSettings(context)
        if (!settings.enabled || !snapshot.canSendExternal) return emptyList()
        if (!ShortsMediaPolicy.validHandle(settings.channel) || !settings.audioCleared) {
            settings.status("Choose the destination channel and confirm soundtrack permission in Settings")
            return emptyList()
        }
        val prefs = context.getSharedPreferences("youtube_shorts", Context.MODE_PRIVATE)
        if(System.currentTimeMillis() < prefs.getLong("next_at",0)) return emptyList()
        val day = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val attempts = co.sanaa.agent.core.AgentRuntime.get(context).memory.allSideEffectTransactions().count {
            it.capability == co.sanaa.agent.core.CapabilityIds.POST_YOUTUBE_SHORT && it.createdAt >= day
        }
        if (attempts >= settings.dailyCap) { settings.status("Daily Shorts cap reached; waiting for tomorrow"); return emptyList() }
        val next = ShortsQueue(context).use { it.next() } ?: return emptyList()
        ShortsMediaPolicy.priorDispatch(next.first,co.sanaa.agent.core.AgentRuntime.get(context).memory.allSideEffectTransactions())?.let { prior ->
            ShortsQueue(context).use { it.update(next.first,
                if(prior.state==co.sanaa.agent.core.SideEffectState.VERIFIED) "VERIFIED" else "UNCERTAIN",
                "Prior upload receipt recovered; automatic replay blocked") }
            return emptyList()
        }
        return listOf(WorkItem("youtube:${next.first}:${settings.channel.lowercase()}", domain,
            WorkKind.YOUTUBE_SHORT_PUBLISH, next.second.put("source_post_key",next.first).put("youtube_channel",settings.channel),
            220.0, urgencyHalfLifeHours=8.0, estimatedScreenSeconds=180,
            requires=setOf(Capability.SCREEN,Capability.NETWORK,Capability.CONSENT_TIER_2),riskTier=RiskTier.MEDIUM))
    }
}
