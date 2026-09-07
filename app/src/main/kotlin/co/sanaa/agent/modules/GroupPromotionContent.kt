package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing
import co.sanaa.agent.core.growth.MarketGrowthReview

/** Caption facts come from our own offering, never invented stock or competitor copy. */
data class GroupPromotionContent(val caption: String, val imageUrl: String) {
    companion object {
        fun from(listing: SokoListing): GroupPromotionContent? {
            val base = TikTokProductContent.from(listing) ?: return null
            val service = listing.raw.optString("offering_type") == "SERVICE"
            val description = MarketGrowthReview.plainText(listing.description).take(560).trim()
            val caption = buildString {
                appendLine(listing.title.take(140))
                if (description.isNotBlank()) { appendLine(); appendLine(description) }
                appendLine()
                appendLine("${if (service) "Listed price" else "Price"}: UGX ${listing.priceUgx}")
                appendLine("${if (service) "Service details" else "Product details"}: ${base.shoppingUrl}")
                append(if (service) "Tell us what you need and your preferred date; we’ll confirm the scope and quote."
                    else "Interested? Tell us the quantity and your delivery area; we’ll confirm availability and delivery.")
            }
            return GroupPromotionContent(caption, base.imageUrl)
        }
    }
}
