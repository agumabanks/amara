package co.sanaa.agent.core.work

import org.json.JSONObject

/** Four independent opportunities after a verified post, with stable replay identities. */
object TikTokCommunityWork {
    fun from(postKey: String, step: Int = 0, startedAt: Long = System.currentTimeMillis()): WorkItem? {
        if (postKey.isBlank() || step !in 0..3) return null
        return WorkItem(
            dedupeKey = "tiktok-community:$postKey:$step", domain = Domain.TIKTOK,
            kind = WorkKind.TIKTOK_COMMENT_REPLY,
            payload = JSONObject().put("community_post", postKey).put("community_step", step).put("community_started", startedAt)
                .put("community_not_before", System.currentTimeMillis() + if(step==0) 0L else 60_000L),
            baseValueKes = 240.0, deadline = startedAt + 25 * 60_000L,
            urgencyHalfLifeHours = 0.25, estimatedScreenSeconds = 90,
            requires = setOf(Capability.SCREEN, Capability.NETWORK, Capability.GROQ, Capability.CONSENT_TIER_2),
            riskTier = RiskTier.MEDIUM,
        )
    }
    fun next(item: WorkItem): WorkItem? = from(item.payload.optString("community_post"),
        item.payload.optInt("community_step") + 1, item.payload.optLong("community_started"))
}
