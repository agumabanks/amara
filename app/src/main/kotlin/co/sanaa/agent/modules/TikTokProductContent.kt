package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing
import java.net.URI

/** One catalogue row supplies all public content. Never reuse a free-standing caption. */
data class TikTokProductContent(val imageUrl: String, val shoppingUrl: String, val caption: String, val fingerprint: String) {
    companion object {
        fun from(listing: SokoListing): TikTokProductContent? {
            val title = listing.title.replace(Regex("\\s+"), " ").trim()
            val media = listing.imageUrl?.trim()?.takeIf { url ->
                runCatching { URI(url).let { it.scheme in setOf("https", "http") && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
            } ?: return null
            // Soko's web route is /product/{slug}; IDs and generic shop URLs are not substitutes.
            val slug = listing.raw.optString("slug").trim()
            if (listing.id.isBlank() || title.isBlank() || title.length > 300 ||
                slug.isBlank() || slug == "null" || !slug.matches(Regex("[A-Za-z0-9_-]+"))) return null
            val isService = listing.raw.optString("offering_type") == "SERVICE"
            val shopping = "https://soko24.co/${if (isService) "service" else "product"}/$slug"
            // Factual copy avoids a generated description inventing a different product or offer.
            val caption = buildString {
                append(title)
                if (listing.priceUgx > 0) append(" — UGX ${listing.priceUgx}")
                append("\n${if (isService) "Book" else "Shop"}: $shopping\n#soko24 #sokoug")
            }
            val fingerprint = co.sanaa.agent.actions.BoundTikTokMedia.sha256(
                listOf(listing.id, title, listing.description, media, shopping, caption).joinToString("\u0000").toByteArray())
            return TikTokProductContent(media, shopping, caption, fingerprint)
        }
    }
}
