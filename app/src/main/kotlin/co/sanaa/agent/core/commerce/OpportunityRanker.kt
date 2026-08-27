package co.sanaa.agent.core.commerce

/**
 * Deterministic revenue-opportunity ranking layer (charter step 2). The model may
 * propose candidates; THIS typed policy code decides eligibility. Every candidate keeps
 * its full ranking inputs and a human-auditable explanation so the owner can inspect why
 * Amara selected an action — stored durably alongside the resulting commercial action.
 */
class OpportunityRanker(private val policy: () -> CommercialPolicy) {

    /** All inputs that fed the score. Persisted with the action for auditability. */
    data class RankingInputs(
        val inventoryAvailable: Boolean,
        val listingQuality: Double,          // 0..1
        val knownPriceUgx: Long?,
        val knownMarginUgx: Long?,           // null when cost unknown
        val inboundIntentScore: Double,      // 0..1 (explicit interest strength)
        val unansweredInquiry: Boolean,
        val followUpDue: Boolean,
        val historicalConversionRate: Double, // 0..1
        val campaignPerformanceScore: Double, // 0..1
        val productFreshnessDays: Int,
        val customerFatigueScore: Double,     // 0..1 (recent contact pressure)
        val riskScore: Double,                // 0..1
        val actionCostUgx: Long,
    ) {
        init {
            require(listingQuality in 0.0..1.0 && inboundIntentScore in 0.0..1.0) { "quality/intent within [0,1]" }
            require(historicalConversionRate in 0.0..1.0 && campaignPerformanceScore in 0.0..1.0) { "conversion/campaign within [0,1]" }
            require(customerFatigueScore in 0.0..1.0 && riskScore in 0.0..1.0) { "fatigue/risk within [0,1]" }
            require(productFreshnessDays >= 0 && actionCostUgx >= 0) { "freshness/cost non-negative" }
            require(knownPriceUgx == null || knownPriceUgx >= 0) { "price non-negative when known" }
            require(knownMarginUgx == null || knownMarginUgx >= 0) { "margin non-negative when known" }
        }
    }

    data class Candidate(
        val contactKey: String,
        val productRef: String,
        val stage: String,
        val inputs: RankingInputs,
    ) {
        enum class ValueBasis { KNOWN_MARGIN, UNKNOWN }

        /** How the expected value was derived. Only KNOWN_MARGIN is a financial figure;
         *  an unknown margin is recorded as UNKNOWN and never guessed from the price
         *  (no assumed-percentage margins, no synthetic fallback amounts). */
        val valueBasis: ValueBasis
            get() = if (inputs.knownMarginUgx != null) ValueBasis.KNOWN_MARGIN else ValueBasis.UNKNOWN

        /** Financial expected value in UGX, or null when the margin is UNKNOWN.
         *  A null value NEVER appears as evidence, revenue, or profit — it only means
         *  the financial dimension of prioritization is unavailable. */
        val expectedValueUgx: Double?
            get() = inputs.knownMarginUgx?.toDouble()?.coerceAtLeast(0.0)

        /**
         * Deterministic score — same inputs always produce the same ordering. When the
         * margin is UNKNOWN the score is purely NONFINANCIAL (intent, responsiveness,
         * freshness, fatigue, risk, direct cost); no invented monetary value enters it.
         */
        val score: Double
            get() {
                val conversionFactor = (0.5 + 0.5 * inputs.historicalConversionRate) *
                    (0.7 + 0.3 * inputs.campaignPerformanceScore)
                val urgencyBoost = (if (inputs.unansweredInquiry) 25_000.0 else 0.0) + (if (inputs.followUpDue) 10_000.0 else 0.0)
                val freshnessDecay = 1.0 / (1.0 + inputs.productFreshnessDays / 30.0)
                val fatiguePenalty = 1.0 - 0.6 * inputs.customerFatigueScore
                val net = when (valueBasis) {
                    ValueBasis.KNOWN_MARGIN -> expectedValueUgx!! * conversionFactor
                    // Nonfinancial priority basis for unknown-margin candidates.
                    ValueBasis.UNKNOWN -> 20_000.0 * conversionFactor
                } * freshnessDecay * fatiguePenalty * (1.0 - 0.5 * inputs.riskScore)
                return net + inputs.inboundIntentScore * 15_000.0 + urgencyBoost - inputs.actionCostUgx
            }

        fun explanation(): String = buildString {
            append("stage=$stage; inventory=${inputs.inventoryAvailable}; ")
            append("listingQuality=${"%.2f".format(inputs.listingQuality)}; ")
            append("intent=${"%.2f".format(inputs.inboundIntentScore)}; ")
            append("unanswered=${inputs.unansweredInquiry}; followUpDue=${inputs.followUpDue}; ")
            append("histConversion=${"%.2f".format(inputs.historicalConversionRate)}; ")
            append("campaignPerf=${"%.2f".format(inputs.campaignPerformanceScore)}; ")
            append("freshnessDays=${inputs.productFreshnessDays}; fatigue=${"%.2f".format(inputs.customerFatigueScore)}; ")
            append("risk=${"%.2f".format(inputs.riskScore)}; cost=${inputs.actionCostUgx}Ugx; ")
            append("valueBasis=$valueBasis; ")
            append("expectedValue=" + (expectedValueUgx?.toLong()?.toString() ?: "UNKNOWN") + (if (valueBasis == ValueBasis.UNKNOWN) "Ugx(nonfinancial ranking)" else "Ugx"))
        }
    }

    sealed class Eligibility {
        data class Eligible(val score: Double, val explanation: String) : Eligibility()
        data class Ineligible(val reason: String) : Eligibility()
    }

    /**
     * Typed policy eligibility — independent of any generated language. Suppressed or
     * policy-blocked customers never become outreach candidates.
     */
    fun eligibility(
        candidate: Candidate,
        suppressed: Boolean,
        messagesToCustomerToday: Int,
        globalMessagesToday: Int,
        followUpsSentForThisOpportunity: Int,
        localTime: java.time.LocalTime,
    ): Eligibility {
        if (!candidate.inputs.inventoryAvailable) return Eligibility.Ineligible("inventory unavailable for ${candidate.productRef}")
        if (suppressed) return Eligibility.Ineligible("customer is on the suppression list")
        val verdict = policy().evaluateOutreachEligibility(
            productRef = candidate.productRef, channel = "whatsapp", localTime = localTime,
            messagesToCustomerToday = messagesToCustomerToday, globalMessagesToday = globalMessagesToday,
            followUpsSentForThisOpportunity = followUpsSentForThisOpportunity,
        )
        return when (verdict) {
            is CommercialPolicy.PolicyVerdict.Blocked -> Eligibility.Ineligible(verdict.reason)
            CommercialPolicy.PolicyVerdict.Allowed -> Eligibility.Eligible(candidate.score, candidate.explanation())
        }
    }

    /** Rank eligible candidates deterministically (score desc, then stable key order). */
    fun rank(eligible: List<Pair<Candidate, Eligibility.Eligible>>): List<Pair<Candidate, Eligibility.Eligible>> =
        eligible.sortedWith(compareByDescending<Pair<Candidate, Eligibility.Eligible>> { it.second.score }
            .thenBy { it.first.contactKey }.thenBy { it.first.productRef })
}
