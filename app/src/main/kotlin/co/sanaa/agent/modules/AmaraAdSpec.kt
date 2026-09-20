package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing
import org.json.JSONObject
import java.util.Locale

/** Public catalogue facts only. Never infer a seller contact from the device owner's number. */
data class AmaraAdSpec(val headline: String, val price: String, val whatsapp: String?, val brand: String, val gallery: List<String> = emptyList(), val template: String = "editorial", val background: Int = 0, val format: String = "video") {
    fun toJson(): JSONObject = JSONObject().put("version", VERSION).put("headline", headline)
        .put("background", background).put("format", format).put("gallery", org.json.JSONArray(gallery)).put("template", template).put("price", price).put("whatsapp", whatsapp ?: "").put("brand", brand)

    companion object {
        const val VERSION = "amara-creative-v5"
        fun phone(raw: String): String? {
            if (!raw.trim().matches(Regex("\\+?[0-9 ()-]+"))) return null
            val digits = raw.filter(Char::isDigit)
            val international = when {
                digits.startsWith("00") -> digits.drop(2)
                digits.length == 10 && digits.startsWith("0") -> "256" + digits.drop(1)
                else -> digits
            }
            return international.takeIf { it.matches(Regex("[1-9][0-9]{7,14}")) }?.let { "+$it" }
        }
        fun price(listing: SokoListing): String {
            if (listing.priceUgx <= 0) return "Ask for a quote"
            val amount = String.format(Locale.US, "%,d", listing.priceUgx)
            val service = listing.raw.optString("offering_type") == "SERVICE"
            val prefix = if (service && listing.raw.optString("pricing_type") != "fixed") "From " else ""
            val unit = listing.raw.optString("unit").trim().takeIf { it != "null" && it.length in 1..20 }
            return "${prefix}UGX $amount" + if (!service && unit != null) " / $unit" else ""
        }
        fun fromJson(json: JSONObject) = AmaraAdSpec(json.getString("headline"),json.getString("price"),
            phone(json.optString("whatsapp")),json.getString("brand"),
            json.optJSONArray("gallery")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            json.optString("template","editorial"),json.optInt("background",0),json.optString("format","video"))
        fun from(listing: SokoListing, publicWhatsApp: String = "", businessName: String = "Sanaa Media"): AmaraAdSpec {
            val title = listing.title.replace(Regex("\\s+"), " ").trim()
            val short = listing.raw.optString("ad_short_name").trim().takeIf { candidate ->
                candidate.isNotBlank() && candidate.split(Regex("\\s+")).size <= 2 && title.contains(candidate, true)
            } ?: listOf("business cards", "date stamp", "rubber stamp", "company stamp", "logo design", "graphic design",
                "web design", "website design", "banner printing", "flyer printing", "poster printing", "receipt books",
                "invoice books", "t-shirt printing", "office chair", "face masks", "company flag", "social media", "event badges", "name tags").firstOrNull { phrase ->
                    Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(phrase) + "(?![\\p{L}\\p{N}])").containsMatchIn(title)
                }?.split(" ")?.joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
                ?: title.split(" ").take(2).joinToString(" ")
            val contact = phone(listing.raw.optString("whatsapp_number")) ?: phone(publicWhatsApp)
            val images = buildList {
                listing.imageUrl?.let(::add)
                listing.raw.optJSONArray("gallery_urls")?.let { rows ->
                    for (i in 0 until rows.length()) add(rows.optString(i))
                }
            }.map(String::trim).filter { url -> runCatching { java.net.URI(url).let {
                it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null
            } }.getOrDefault(false) }.distinct().take(4)
            val styles = listOf("editorial", "showcase", "poster")
            val template = styles[Math.floorMod(listing.id.hashCode(), styles.size)]
            val shop = listing.raw.optString("shop_name").trim().takeIf { it.isNotBlank() && it != "null" } ?: businessName
            val format = if(images.size > 1 || listing.raw.optString("offering_type")=="SERVICE") "video" else "photo"
            return AmaraAdSpec(short, price(listing), contact, shop.replace(Regex("\\s+"), " ").trim().take(60), images, template, Math.floorMod((listing.id+title).hashCode(),3), format)
        }
    }
}
