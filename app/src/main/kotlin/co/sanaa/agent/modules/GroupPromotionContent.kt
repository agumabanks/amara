package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing
import co.sanaa.agent.core.growth.MarketGrowthReview

/** Group ads are brief invitations; detailed explanations belong in a requested reply. */
data class GroupPromotionContent(val caption: String, val imageUrl: String) {
    companion object {
        fun from(listing: SokoListing, detailed: Boolean = false, variant: Int = 0): GroupPromotionContent? {
            val base = TikTokProductContent.from(listing) ?: return null
            val service = listing.raw.optString("offering_type") == "SERVICE"
            val plain = MarketGrowthReview.plainText(listing.description).replace(Regex("\\s+"), " ").trim()
            // One concrete fact, never a pasted catalogue paragraph. Longer copy is opt-in.
            val limit = if (detailed) 240 else 65
            val sentence = if (detailed) plain else plain.split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty()
            val fact = if (sentence.length <= limit) sentence else sentence.take(limit - 1).substringBeforeLast(' ').trimEnd('.', ',', ';') + "…"
            val angle=AudienceAdCopy.angle(listing,variant)
            val caption = buildString {
                appendLine(angle.opening)
                appendLine(listing.title.take(80))
                appendLine(AmaraAdSpec.price(listing))
                if (fact.isNotBlank()) appendLine(fact)
                appendLine(base.shoppingUrl)
                append(angle.question)
                if (!service) append(" Message us to confirm availability.")
            }
            return GroupPromotionContent(caption, base.imageUrl)
        }
    }
}
