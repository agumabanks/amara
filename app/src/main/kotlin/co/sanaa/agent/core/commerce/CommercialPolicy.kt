package co.sanaa.agent.core.commerce

import co.sanaa.agent.core.QuietHoursPolicy

/**
 * Owner-configurable commercial targets AND operating policies in one durable document.
 *
 * Fail-closed rule: every field here is a LIMIT or an ALLOW-LIST. Missing/blank entries
 * mean "not permitted" for outreach-affecting fields — an absent allowed-product list,
 * channel list, or audience scope mechanically blocks external commercial outreach until
 * the owner configures them. Defaults are deliberately conservative; nothing in this
 * class can be used to widen authority, only to bound it.
 */
data class CommercialPolicy(
    // Targets (objectives, never evidence). The weekly default is the charter's
    // three verified completed sales unless the owner explicitly changes it.
    val dailyQualifiedInquiryTarget: Int = 1,
    val weeklyVerifiedSaleTarget: Int = 3,
    /** Monthly floor: attributable gross profit must exceed this to count as met. */
    val monthlyProfitFloorUgx: Long = 0,

    // Allow-lists (blank = fail closed)
    val allowedProducts: Set<String> = emptySet(),
    val approvedChannels: Set<String> = emptySet(),
    val permittedAudience: String = "",

    // Communication limits
    val quietHours: QuietHoursPolicy? = null,
    /** Owner timezone id (e.g. "Africa/Kampala"); day boundaries and quiet hours are
     *  evaluated in THIS zone, never the device's. Blank = not configured → the cycle
     *  fails closed to read-only planning until the owner sets it. */
    val ownerTimeZoneId: String = "",
    val dailyGlobalMessageCap: Int = 4,
    val perCustomerDailyCap: Int = 1,
    val perCustomerFrequencyWindowMs: Long = 48 * 60 * 60 * 1_000L,
    val followUpLimitPerOpportunity: Int = 2,

    // Money limits
    val campaignBudgetUgx: Long = 0,
    val discountCeilingPercent: Int = 0,

    // Measurement
    val attributionWindowMs: Long = 7 * 24 * 60 * 60 * 1_000L,

    // Experiment governance
    val maxConcurrentExperiments: Int = 1,
    val maxExperimentSpendUgx: Long = 0,

    // Stop conditions (any hit pauses proactive outreach pending owner review)
    val stopOnComplaint: Boolean = true,
    val stopOnRefundSpike: Boolean = true,
    val stopOnNegativeReplySpike: Boolean = true,
) {
    init {
        require(dailyQualifiedInquiryTarget >= 0) { "daily target cannot be negative" }
        require(weeklyVerifiedSaleTarget >= 0) { "weekly target cannot be negative" }
        require(monthlyProfitFloorUgx >= 0) { "monthly floor cannot be negative" }
        require(dailyGlobalMessageCap >= 0 && perCustomerDailyCap >= 0) { "message caps cannot be negative" }
        require(perCustomerFrequencyWindowMs > 0) { "frequency window must be positive" }
        require(followUpLimitPerOpportunity >= 0) { "follow-up limit cannot be negative" }
        require(campaignBudgetUgx >= 0 && maxExperimentSpendUgx >= 0) { "budgets cannot be negative" }
        require(discountCeilingPercent in 0..100) { "discount ceiling within [0,100]" }
        require(attributionWindowMs > 0) { "attribution window must be positive" }
        require(maxConcurrentExperiments >= 1) { "at least one experiment slot must exist" }
    }

    fun toJson(): String = org.json.JSONObject().apply {
        put("dailyQualifiedInquiryTarget", dailyQualifiedInquiryTarget)
        put("weeklyVerifiedSaleTarget", weeklyVerifiedSaleTarget)
        put("monthlyProfitFloorUgx", monthlyProfitFloorUgx)
        put("allowedProducts", org.json.JSONArray(allowedProducts.sorted()))
        put("approvedChannels", org.json.JSONArray(approvedChannels.sorted()))
        put("permittedAudience", permittedAudience)
        quietHours?.let { put("quietHoursStart", it.start.toString()); put("quietHoursEnd", it.end.toString()) }
        put("ownerTimeZoneId", ownerTimeZoneId)
        put("dailyGlobalMessageCap", dailyGlobalMessageCap)
        put("perCustomerDailyCap", perCustomerDailyCap)
        put("perCustomerFrequencyWindowMs", perCustomerFrequencyWindowMs)
        put("followUpLimitPerOpportunity", followUpLimitPerOpportunity)
        put("campaignBudgetUgx", campaignBudgetUgx)
        put("discountCeilingPercent", discountCeilingPercent)
        put("attributionWindowMs", attributionWindowMs)
        put("maxConcurrentExperiments", maxConcurrentExperiments)
        put("maxExperimentSpendUgx", maxExperimentSpendUgx)
        put("stopOnComplaint", stopOnComplaint)
        put("stopOnRefundSpike", stopOnRefundSpike)
        put("stopOnNegativeReplySpike", stopOnNegativeReplySpike)
    }.toString()

    companion object {
        fun fromJson(raw: String): CommercialPolicy? = runCatching {
            val json = org.json.JSONObject(raw)
            fun stringSet(key: String): Set<String> =
                json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }?.toSet() ?: emptySet()
            CommercialPolicy(
                dailyQualifiedInquiryTarget = json.getInt("dailyQualifiedInquiryTarget"),
                weeklyVerifiedSaleTarget = json.getInt("weeklyVerifiedSaleTarget"),
                monthlyProfitFloorUgx = json.getLong("monthlyProfitFloorUgx"),
                allowedProducts = stringSet("allowedProducts"),
                approvedChannels = stringSet("approvedChannels"),
                permittedAudience = json.optString("permittedAudience", ""),
                quietHours = if (json.has("quietHoursStart") && json.has("quietHoursEnd"))
                    QuietHoursPolicy.parse(json.getString("quietHoursStart"), json.getString("quietHoursEnd")) else null,
                ownerTimeZoneId = runCatching {
                    val zone = json.optString("ownerTimeZoneId", "")
                    if (zone.isNotBlank()) java.time.ZoneId.of(zone).id else ""
                }.getOrDefault(""),
                dailyGlobalMessageCap = json.getInt("dailyGlobalMessageCap"),
                perCustomerDailyCap = json.getInt("perCustomerDailyCap"),
                perCustomerFrequencyWindowMs = json.getLong("perCustomerFrequencyWindowMs"),
                followUpLimitPerOpportunity = json.getInt("followUpLimitPerOpportunity"),
                campaignBudgetUgx = json.getLong("campaignBudgetUgx"),
                discountCeilingPercent = json.getInt("discountCeilingPercent"),
                attributionWindowMs = json.getLong("attributionWindowMs"),
                maxConcurrentExperiments = json.getInt("maxConcurrentExperiments"),
                maxExperimentSpendUgx = json.getLong("maxExperimentSpendUgx"),
                stopOnComplaint = json.optBoolean("stopOnComplaint", true),
                stopOnRefundSpike = json.optBoolean("stopOnRefundSpike", true),
                stopOnNegativeReplySpike = json.optBoolean("stopOnNegativeReplySpike", true),
            )
        }.getOrNull()

        private val DEFAULT = CommercialPolicy()
    }

    /** Resolved owner timezone; falls back to the device zone only as a last resort. */
    fun ownerZone(): java.time.ZoneId =
        runCatching { java.time.ZoneId.of(ownerTimeZoneId) }.getOrDefault(java.time.ZoneId.systemDefault())

    /**
     * Mechanical eligibility gate for ANY customer-directed commercial message. Returns
     * the first blocking reason; an empty allow-list blocks everything (fail closed).
     */
    fun evaluateOutreachEligibility(
        productRef: String?,
        channel: String,
        localTime: java.time.LocalTime,
        messagesToCustomerToday: Int,
        globalMessagesToday: Int,
        followUpsSentForThisOpportunity: Int,
    ): PolicyVerdict {
        if (approvedChannels.isEmpty()) return PolicyVerdict.Blocked("No channels are owner-approved; missing policy fails closed")
        if (channel !in approvedChannels) return PolicyVerdict.Blocked("Channel '$channel' is not owner-approved (${approvedChannels.sorted()})")
        if (allowedProducts.isEmpty()) return PolicyVerdict.Blocked("No products are owner-approved; missing policy fails closed")
        if (productRef.isNullOrBlank() || productRef !in allowedProducts) {
            return PolicyVerdict.Blocked("Product '${productRef ?: "?"}' is not on the owner-approved list")
        }
        if (permittedAudience.isBlank()) return PolicyVerdict.Blocked("No permitted audience configured; missing policy fails closed")
        quietHours?.let { if (it.contains(localTime)) return PolicyVerdict.Blocked("Quiet hours ${it.start}–${it.end} block outreach at $localTime") }
        if (messagesToCustomerToday >= perCustomerDailyCap) {
            return PolicyVerdict.Blocked("Per-customer daily cap reached ($messagesToCustomerToday/${perCustomerDailyCap})")
        }
        if (globalMessagesToday >= dailyGlobalMessageCap) {
            return PolicyVerdict.Blocked("Daily global message cap reached ($globalMessagesToday/${dailyGlobalMessageCap})")
        }
        if (followUpsSentForThisOpportunity >= followUpLimitPerOpportunity) {
            return PolicyVerdict.Blocked("Follow-up limit for this opportunity reached (${followUpLimitPerOpportunity})")
        }
        return PolicyVerdict.Allowed
    }

    sealed class PolicyVerdict {
        data object Allowed : PolicyVerdict()
        data class Blocked(val reason: String) : PolicyVerdict()
    }
}
