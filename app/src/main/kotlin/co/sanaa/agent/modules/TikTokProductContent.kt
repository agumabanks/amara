package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing
import java.net.URI

/** One catalogue row supplies all public content. Never reuse a free-standing caption. */
data class TikTokProductContent(val imageUrl: String, val shoppingUrl: String, val caption: String, val fingerprint: String, val ad: AmaraAdSpec) {
    companion object {
        fun hashtags(ad: AmaraAdSpec,address:String):List<String> {
            fun tag(value:String)=value.split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotBlank() }.joinToString("") { it.replaceFirstChar(Char::titlecase) }.take(40)
            return (listOf("#soko24")+listOf(ad.headline,ad.brand).map(::tag).filter { it.isNotBlank() }.map { "#$it" }+
                ShopLocationHashtags.fromAddress(address).take(2)).distinctBy { it.lowercase() }.take(5)
        }
        fun from(listing: SokoListing, publicWhatsApp: String = "", businessName: String = "Sanaa Media"): TikTokProductContent? {
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
            val ad = AmaraAdSpec.from(listing, publicWhatsApp, businessName)
            val angle = AudienceAdCopy.angle(listing, listing.id.hashCode())
            val caption = buildString {
                appendLine(angle.opening)
                append(title)
                append(" — ${ad.price}")
                AudienceAdCopy.fact(listing).takeIf { it.isNotBlank() }?.let { append("\n$it") }
                append("\n${angle.question}")
                ad.whatsapp?.let { append("\nWhatsApp: $it") }
                append("\n${if (isService) "Book" else "Shop"}: $shopping\n")
                append(hashtags(ad,listing.raw.optString("shop_address")).joinToString(" "))
            }
            val fingerprint = co.sanaa.agent.actions.BoundTikTokMedia.sha256(
                listOf(listing.id, title, listing.description, media, shopping, caption, ad.toJson().toString()).joinToString("\u0000").toByteArray())
            return TikTokProductContent(media, shopping, caption, fingerprint, ad)
        }
    }
}
