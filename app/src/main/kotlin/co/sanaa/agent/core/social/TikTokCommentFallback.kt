package co.sanaa.agent.core.social

/**
 * High-precision fallback when the configured model provider is unavailable.
 * It only asks a neutral, useful question for explicit business-service terms;
 * all ordinary side-effect policy, reservation, caps and device verification
 * still run after this decision.
 */
object TikTokCommentFallback {
    data class Decision(val response: String, val evidence: String, val relevance: Double = 0.85)

    private val excluded = Regex(
        "(?i)\\b(politic(?:s|al)?|election|medical|medicine|diagnos(?:is|ed)|death|funeral|" +
            "crypto|forex|investment|investing|loan|wealth|money|child|children|kid|kids)\\b",
    )
    private val rules = listOf(
        Regex("(?i)\\b(packaging|product labels?|label printing)\\b") to
            "Which material or finish worked best for this packaging?",
        Regex("(?i)\\b(printing|print finish|business cards?|flyers?|banners?)\\b") to
            "Which print finish gave you the best result here?",
        Regex("(?i)\\b(graphic design|brand identity|branding|logo design)\\b") to
            "Which design choice made the biggest difference for this brand?",
        Regex("(?i)\\b(web design|website design|e-commerce|ecommerce)\\b") to
            "Which page or feature made the biggest difference for this business?",
        Regex("(?i)\\b(available at|whatsapp to order|in stock|retail products?|product range|power tools?)\\b") to
            "Which option gets the most questions from buyers?",
        Regex("(?i)\\b(shopping (?:ground|centre|center|mall)|retail shop|online shop|our shop)\\b") to
            "What do buyers usually ask about first?",
        Regex("(?i)\\b(small business|retail business|repeat customers?)\\b") to
            "What has helped you bring repeat customers back most?",
    )

    fun decide(caption: String): Decision? {
        if (!TikTokSocialPolicy.safeContext(caption) || excluded.containsMatchIn(caption)) return null
        val (match, response) = rules.firstNotNullOfOrNull { (pattern, response) ->
            pattern.find(caption)?.let { it to response }
        } ?: return null
        return Decision(response, match.value)
    }
}
