package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing

/** Missing structured evidence is a review request, never invented listing copy. */
object ServiceListingChecklist {
    fun missing(listing: SokoListing): List<String> {
        if (listing.raw.optString("offering_type") != "SERVICE") return emptyList()
        val raw = listing.raw
        fun present(vararg keys: String) = keys.any { key ->
            raw.optString(key).trim().let { it.isNotBlank() && it !in setOf("null", "[]", "{}") }
        }
        return buildList {
            if (!present("deliverables", "inclusions")) add("Confirm deliverables and inclusions")
            if (!present("options", "packages")) add("Confirm service options or explicitly record none")
            if (!present("pricing_type")) add("Confirm whether the base price is fixed, starting or quote-only")
            if (!present("delivery_timeframe")) add("Confirm turnaround")
            if (!present("required_inputs", "requirements")) add("Confirm required customer inputs")
            if (listing.imageUrl.isNullOrBlank()) add("Supply an approved work example")
            if (!present("slug")) add("Supply the service booking route")
        }
    }
}
