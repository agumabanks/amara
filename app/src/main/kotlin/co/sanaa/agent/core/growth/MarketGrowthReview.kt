package co.sanaa.agent.core.growth

import co.sanaa.agent.api.SokoListing
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.ActionRisk
import co.sanaa.agent.core.market.MarketAnalyzer
import org.json.JSONArray
import org.json.JSONObject

/** Makes research reviewable against our own catalogue. Competitor claims never become our stock. */
class MarketGrowthReview(private val memory: AmaraMemory, private val market: MarketAnalyzer, private val store: GrowthStore) {
    fun review(offerings: List<SokoListing>): JSONObject {
        val decisions = JSONArray()
        var drafts = 0
        for (item in offerings) {
            val issues = mutableListOf<String>()
            if (item.imageUrl.isNullOrBlank()) issues += "Supply an original photo of this offering"
            if (item.priceUgx <= 0) issues += "Confirm price and unit with the business"
            if (item.stock == 0) issues += "Confirm restock before promoting this product"
            val clean = plainText(item.description)
            if (clean != item.description) issues += "Clean existing description formatting without changing product facts"
            if (clean.length < 80) issues += "Confirm specifications, scope, delivery terms and buyer questions before expanding the description"
            val position = market.compareOurProduct(item.title, item.priceUgx.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (position.competitorCount >= 3) issues += position.recommendation
            if (issues.isEmpty()) continue
            val evidence = JSONObject().put("listingId", item.id).put("ownTitle", item.title)
                .put("ownDescription", item.description.take(1800)).put("ownPriceUgx", item.priceUgx)
                .put("comparableOffers", position.competitorCount).put("marketAverageUgx", position.marketAvgUgx)
            val action = issues.joinToString(". ")
            memory.recordBusinessFinding("Market growth review", item.title, "Listing improvement plan", "medium", 0.7,
                evidence.toString(), action)
            // A deterministic copy draft uses ONLY existing owner-catalogue words.
            // It can be reviewed now; the existing exact-edit workflow controls saving.
            if (clean.length in 35..1500 && clean != item.description && drafts < 5 && GrowthStore.typeOf(item) == "PRODUCT") {
                memory.createApprovalRequest("edit_soko_listing", item.title,
                    "Clean existing description formatting. Verify the exact Terminal listing before saving. Market prices are research only.",
                    JSONObject().put("description", item.description).toString(),
                    JSONObject().put("description", clean).toString(), ActionRisk.LOW_IMPACT_CHANGE)
                drafts++
            }
            decisions.put(JSONObject().put("listingId", item.id).put("title", item.title).put("action", action)
                .put("comparableOffers", position.competitorCount))
        }
        val briefs = JSONArray()
        for (opportunity in market.findOpportunities().take(5)) {
            val next = "Confirm a supplier or service provider, actual available stock/capacity, original photos, specifications, landed cost and selling margin before creating a Soko listing. Test interest through relevant customer enquiries."
            briefs.put(JSONObject().put("category", opportunity.category).put("evidence", opportunity.description).put("action", next))
            memory.recordBusinessFinding("Market growth review", opportunity.category, "Sourcing brief", "low", opportunity.confidence,
                JSONObject().put("observed", opportunity.description).toString(), next)
        }
        val report = JSONObject().put("generatedAt", System.currentTimeMillis()).put("offeringsReviewed", offerings.size)
            .put("sourcingBriefs", briefs).put("decisions", decisions).put("exactTextDrafts", drafts)
            .put("summary", "Reviewed ${offerings.size} products and services; ${decisions.length()} listing improvement plans; $drafts exact text drafts. Verified sales and costs, not posting volume, determine business growth.")
            .put("newListingRule", "Prepare sourcing/creation briefs from market evidence; publish only after business ownership, supplier, original media, price, stock or service capacity are verified. Terminal login is required for writes.")
        store.saveReport(report)
        return report
    }
    companion object {
        fun plainText(value: String): String = value.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()
    }
}
