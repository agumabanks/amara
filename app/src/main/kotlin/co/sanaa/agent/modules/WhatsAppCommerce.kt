package co.sanaa.agent.modules

import org.json.JSONObject
import kotlin.math.*

/** Quotes are calculated from Terminal facts, never from model-estimated geography or rates. */
object WhatsAppCommerce {
    private val coordinates = Regex("(?i)(?:[?&](?:q|query)=|@|location[: ]+|coordinates[: ]+)(-?\\d{1,2}(?:\\.\\d+)?),\\s*(-?\\d{1,3}(?:\\.\\d+)?)")
    fun location(text: String): Pair<Double, Double>? {
        val match = coordinates.findAll(text).lastOrNull() ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        return (lat to lng).takeIf { lat in -90.0..90.0 && lng in -180.0..180.0 }
    }
    fun deliveryLocation(message: String, history: String): Pair<Double, Double>? {
        location(message)?.let { return it }
        // Never quote the old pin after a new address or an invalid replacement pin.
        val replacesLocation = Regex("(?i)\\b(instead|actually|different|new address|changed|change|not there|(?:deliver|delivery|send|shipping) to|coordinates|location)\\b|[?&](?:q|query)=|@").containsMatchIn(message)
        if (replacesLocation) return null
        val latestLocationMessage = history.lineSequence().lastOrNull {
            location(it) != null || Regex("(?i)\\b(instead|new address|change|changed|(?:deliver|delivery|send) to|coordinates|location)\\b").containsMatchIn(it)
        }
        return latestLocationMessage?.let(::location)
    }
    fun quote(profile: JSONObject, location: Pair<Double, Double>?): JSONObject {
        fun held(reason: String) = JSONObject().put("eligible", false).put("reason", reason)
        val p = profile.optJSONObject("delivery") ?: return held("Delivery rates are not configured in Terminal")
        val shop = profile.optJSONObject("shop") ?: return held("Shop unavailable")
        if (profile.optInt("seller_delivery_enabled") != 1 || p.optInt("enabled") != 1 || shop.optInt("verification_status") != 1)
            return held("Seller delivery is not available")
        if (location == null) return held("Ask for a Google Maps pin or coordinates and a nearby landmark; do not estimate coordinates from an area name")
        val lat = p.optDouble("origin_lat", shop.optDouble("delivery_pickup_latitude"))
        val lng = p.optDouble("origin_lng", shop.optDouble("delivery_pickup_longitude"))
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return held("Shop pickup location is missing")
        val radians = Math.PI / 180
        val a = sin((location.first-lat)*radians/2).pow(2) + cos(lat*radians)*cos(location.first*radians)*sin((location.second-lng)*radians/2).pow(2)
        val distance = 6371 * 2 * atan2(sqrt(a.coerceIn(0.0,1.0)), sqrt((1-a).coerceIn(0.0,1.0)))
        val radius = min(p.optDouble("radius_km", profile.optDouble("radius_max_km", 5.0)),profile.optDouble("radius_max_km",5.0))
        if (!radius.isFinite() || radius <= 0 || distance > radius) return held("Outside configured delivery radius; ask the manager for a quote")
        val base = p.optDouble("base_fee"); val perKm = p.optDouble("per_km_fee",0.0); val minimum=p.optDouble("min_fee",0.0)
        val maximum=p.optDouble("max_fee",Double.POSITIVE_INFINITY)
        if (!base.isFinite() || base<0 || !perKm.isFinite() || perKm<0 || !minimum.isFinite() || minimum<0 || maximum<minimum)
            return held("Delivery rates require manager review")
        val raw = when(p.optString("pricing_mode")) { "flat" -> base; "base_per_km", "tiered" -> base+distance*perKm; else -> return held("Unsupported delivery pricing") }
        val fee = ceil(min(max(raw,minimum),maximum)/100)*100
        if (!fee.isFinite() || fee>Int.MAX_VALUE) return held("Delivery amount requires review")
        return JSONObject().put("eligible",true).put("fee_ugx",fee.toLong()).put("distance_km",distance)
            .put("basis","Terminal seller-delivery estimate; straight-line distance, subject to checkout confirmation")
    }
    fun paymentInstructions(profile: JSONObject): String {
        val shop=profile.optJSONObject("shop") ?: return "Payment details are unavailable; I’ll ask the manager to confirm them."
        val methods=listOf("mtn_merchant_code" to "MTN MoMo merchant code", "airtel_merchant_code" to "Airtel Pay merchant code", "paybill_number" to "Paybill")
            .mapNotNull { (key,label) -> shop.optString(key).trim().takeIf { it.matches(Regex("[0-9 +()-]{3,24}")) }?.let { "$label: $it" } }
        return if(methods.isEmpty()) "The shop hasn’t provided payment details in Soko Terminal yet. I’ll ask the manager to confirm."
            else "${shop.optString("name")}:\n${methods.joinToString("\n")}\nCheck the recipient name before paying. Payment is confirmed only when the business verifies it."
    }
    fun paymentQuestion(message:String)=!Regex("(?i)\\b(paid|refund|receipt|charged|deducted|payment (?:done|sent|complete|failed)|haven['’]t received)\\b").containsMatchIn(message) && Regex("(?i)\\b(pay|payment|momo|airtel|merchant|paybill)\\b").containsMatchIn(message)
    fun deliveryQuestion(message:String)=Regex("(?i)\\b(delivery|deliver|shipping|coordinates)\\b").containsMatchIn(message) || location(message)!=null
}
