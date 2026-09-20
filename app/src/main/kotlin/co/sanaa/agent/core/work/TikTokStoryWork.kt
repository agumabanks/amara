package co.sanaa.agent.core.work

import org.json.JSONObject

/** One Story opportunity per published ad; never regenerate or republish its feed post. */
object TikTokStoryWork {
    fun from(postKey: String, payload: JSONObject): WorkItem? {
        if (payload.optString("caption").isBlank() || payload.optString("listing_id").isBlank()) return null
        return WorkItem(
            dedupeKey = "tiktok-story:$postKey", domain = Domain.TIKTOK, kind = WorkKind.TIKTOK_STORY_PUBLISH,
            payload = JSONObject(payload.toString()).put("source_post_key", postKey),
            baseValueKes = 245.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 90,
            deadline = System.currentTimeMillis() + 2 * 3_600_000L,
            requires = setOf(Capability.SCREEN, Capability.NETWORK, Capability.CONSENT_TIER_2),
            riskTier = RiskTier.MEDIUM,
        )
    }
}
