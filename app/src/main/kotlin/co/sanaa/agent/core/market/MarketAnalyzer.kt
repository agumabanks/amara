package co.sanaa.agent.core.market

import android.content.Context

/**
 * MarketAnalyzer — combines Jiji, Jumia, and Soko evidence into decisions.
 * Identifies pricing trends, hot products, and opportunities.
 */
class MarketAnalyzer(
    private val context: Context,
    private val db: MarketDatabase
) {

    /** Structured owner dashboard; unlike the legacy prose report this is actionable UI data. */
    fun dashboard(sokoProducts: List<String> = emptyList()): Map<String, Any> {
        val sources = listOf("JIJI", "JUMIA").map { source ->
            val cursor = db.readableDatabase.rawQuery(
                "SELECT COUNT(*), COALESCE(AVG(price_ugx),0), COALESCE(MAX(last_seen),0) FROM jiji_listings WHERE source = ?",
                arrayOf(source),
            )
            val row = if (cursor.moveToFirst()) mapOf(
                "source" to source,
                "listings" to cursor.getInt(0),
                "averagePriceUgx" to cursor.getDouble(1).toInt(),
                "lastObservedAt" to cursor.getLong(2),
            ) else mapOf("source" to source, "listings" to 0, "averagePriceUgx" to 0, "lastObservedAt" to 0L)
            cursor.close()
            row
        }
        val categories = getTrackedCategories().map { analysis -> analyzeCategory(analysis) }.map {
            mapOf(
                "category" to it.category,
                "listings" to it.totalListings,
                "averagePriceUgx" to it.avgPriceUgx,
                "medianPriceUgx" to it.medianPriceUgx,
                "minimumPriceUgx" to it.minPriceUgx,
                "maximumPriceUgx" to it.maxPriceUgx,
                "hotProducts" to it.hotProducts,
                "topSellers" to it.topSellers.map { seller -> mapOf(
                    "name" to seller.name, "listings" to seller.listingCount, "averagePriceUgx" to seller.avgPriceUgx,
                ) },
            )
        }
        val recent = mutableListOf<Map<String, Any>>()
        db.readableDatabase.rawQuery(
            "SELECT source, title, price_ugx, category, seller_name, last_seen FROM jiji_listings ORDER BY last_seen DESC LIMIT 12",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) recent += mapOf(
                "source" to cursor.getString(0), "title" to cursor.getString(1),
                "priceUgx" to cursor.getInt(2), "category" to cursor.getString(3).orEmpty(),
                "seller" to cursor.getString(4).orEmpty(), "observedAt" to cursor.getLong(5),
            )
        }
        val comparisons = sokoProducts.mapNotNull(::parseSokoProduct).take(12).map { (title, price) ->
            val position = compareOurProduct(title, price)
            mapOf(
                "product" to title, "ourPriceUgx" to price, "marketAverageUgx" to position.marketAvgUgx,
                "marketMinimumUgx" to position.marketMinUgx, "competitors" to position.competitorCount,
                "position" to position.position, "recommendation" to position.recommendation,
            )
        }
        return mapOf(
            "generatedAt" to System.currentTimeMillis(),
            "sources" to sources,
            "categories" to categories,
            "opportunities" to findOpportunities().take(8).map {
                mapOf("category" to it.category, "type" to it.type, "description" to it.description,
                    "potentialRevenueUgx" to it.potentialRevenueUgx, "confidence" to it.confidence)
            },
            "recentListings" to recent,
            "sokoComparisons" to comparisons,
            "totalListings" to sources.sumOf { (it["listings"] as Number).toInt() },
        )
    }

    private fun parseSokoProduct(raw: String): Pair<String, Int>? {
        val match = Regex("^(.*) \\(UGX ([0-9,]+)\\)$").find(raw.trim()) ?: return null
        val price = match.groupValues[2].replace(",", "").toIntOrNull() ?: return null
        return match.groupValues[1].trim() to price
    }

    data class CategoryAnalysis(
        val category: String,
        val totalListings: Int,
        val avgPriceUgx: Int,
        val minPriceUgx: Int,
        val maxPriceUgx: Int,
        val medianPriceUgx: Int,
        val topSellers: List<SellerInfo>,
        val hotProducts: List<String>,
        val priceDistribution: Map<String, Int>  // price range -> count
    )

    data class SellerInfo(
        val name: String,
        val listingCount: Int,
        val avgPriceUgx: Int,
        val rating: Float?
    )

    data class CompetitivePosition(
        val ourProduct: String,
        val ourPriceUgx: Int,
        val marketAvgUgx: Int,
        val marketMinUgx: Int,
        val marketMaxUgx: Int,
        val position: String,  // "BELOW_MARKET", "AT_MARKET", "ABOVE_MARKET"
        val competitorCount: Int,
        val recommendation: String
    )

    /**
     * Analyze a category and return insights.
     */
    fun analyzeCategory(category: String): CategoryAnalysis {
        val listings = getListings(category)
        if (listings.isEmpty()) {
            return CategoryAnalysis(category, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyMap())
        }

        val prices = listings.mapNotNull { it.priceUgx?.takeIf { p -> p > 0 } }.sorted()
        if (prices.isEmpty()) return CategoryAnalysis(category, listings.size, 0, 0, 0, 0, emptyList(), emptyList(), emptyMap())
        val avgPrice = prices.average().toInt()
        val minPrice = prices.first()
        val maxPrice = prices.last()
        val medianPrice = prices[prices.size / 2]

        // Top sellers by listing count
        val sellerGroups = listings.groupBy { it.sellerName }
        val topSellers = sellerGroups.map { (name, sellerListings) ->
            SellerInfo(
                name = name,
                listingCount = sellerListings.size,
                avgPriceUgx = sellerListings.mapNotNull { it.priceUgx }.average().toInt(),
                rating = sellerListings.firstOrNull { it.sellerRating != null }?.sellerRating
            )
        }.sortedByDescending { it.listingCount }.take(5)

        // Price distribution
        val priceDist = mutableMapOf<String, Int>()
        for (price in prices) {
            val range = when {
                price < 50000 -> "0-50k"
                price < 100000 -> "50k-100k"
                price < 250000 -> "100k-250k"
                price < 500000 -> "250k-500k"
                price < 1000000 -> "500k-1M"
                else -> "1M+"
            }
            priceDist[range] = (priceDist[range] ?: 0) + 1
        }

        return CategoryAnalysis(
            category = category,
            totalListings = listings.size,
            avgPriceUgx = avgPrice,
            minPriceUgx = minPrice,
            maxPriceUgx = maxPrice,
            medianPriceUgx = medianPrice,
            topSellers = topSellers,
            hotProducts = findHotProducts(listings),
            priceDistribution = priceDist
        )
    }

    /**
     * Compare our Soko product against market.
     */
    fun compareOurProduct(productTitle: String, ourPriceUgx: Int): CompetitivePosition {
        val listings = getTrackedCategories().flatMap(::getListings)
            .filter { MarketEvidence.comparable(productTitle, it.title) }
        val prices = listings.mapNotNull { it.priceUgx?.takeIf { p -> p > 0 } }

        if (prices.size < 3) {
            return CompetitivePosition(productTitle, ourPriceUgx, 0, 0, 0, "UNKNOWN", prices.size, "Need at least 3 recent comparable offers with matching model and condition; do not change price from category averages")
        }

        val avg = prices.average().toInt()
        val min = prices.min()
        val max = prices.max()

        val position = when {
            ourPriceUgx < avg * 0.85 -> "BELOW_MARKET"
            ourPriceUgx > avg * 1.15 -> "ABOVE_MARKET"
            else -> "AT_MARKET"
        }

        val recommendation = when (position) {
            "BELOW_MARKET" -> "Below this sample of comparable asking prices. Confirm specifications, condition, costs and margin before considering a price change."
            "ABOVE_MARKET" -> "Above this sample of comparable asking prices. Check specifications, condition and your margin before considering a price change."
            else -> "Your price is competitive. Focus on better photos and descriptions to stand out."
        }

        return CompetitivePosition(
            ourProduct = productTitle,
            ourPriceUgx = ourPriceUgx,
            marketAvgUgx = avg,
            marketMinUgx = min,
            marketMaxUgx = max,
            position = position,
            competitorCount = listings.size,
            recommendation = recommendation
        )
    }

    /**
     * Find market opportunities.
     */
    fun findOpportunities(): List<MarketOpportunity> {
        val opportunities = mutableListOf<MarketOpportunity>()
        val categories = getTrackedCategories()

        for (category in categories) {
            val analysis = analyzeCategory(category)

            if (analysis.totalListings > 0) opportunities.add(MarketOpportunity(
                category = category, type = "RESEARCH_FOLLOWUP",
                description = "${analysis.totalListings} observed offers in $category. Compare matching models and conditions; sample size does not establish demand or total competition.",
                potentialRevenueUgx = 0, confidence = 0.4,
            ))
        }

        return opportunities.sortedByDescending { it.confidence }
    }

    /**
     * Generate a market report for the owner.
     */
    fun generateReport(): String {
        val categories = getTrackedCategories()
        if (categories.isEmpty()) return "No market evidence yet. Jiji and Jumia observations will appear after an allowed autonomous cycle."

        val sb = StringBuilder()
        sb.appendLine("📊 Market Intelligence Report")
        sb.appendLine("Generated: ${java.text.SimpleDateFormat("MMM dd, HH:mm").format(java.util.Date())}")
        sb.appendLine()

        for (category in categories.take(5)) {
            val analysis = analyzeCategory(category)
            sb.appendLine("## $category")
            sb.appendLine("- Listings: ${analysis.totalListings}")
            sb.appendLine("- Avg price: ${analysis.avgPriceUgx} UGX")
            sb.appendLine("- Range: ${analysis.minPriceUgx}-${analysis.maxPriceUgx} UGX")
            sb.appendLine("- Top seller: ${analysis.topSellers.firstOrNull()?.name ?: "N/A"} (${analysis.topSellers.firstOrNull()?.listingCount ?: 0} listings)")
            sb.appendLine()
        }

        val opportunities = findOpportunities().take(3)
        if (opportunities.isNotEmpty()) {
            sb.appendLine("## Opportunities")
            for (opp in opportunities) {
                sb.appendLine("- [${opp.type}] ${opp.description}")
            }
        }

        return sb.toString()
    }

    private fun getListings(category: String): List<JijiScraper.ScrapedListing> {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT listing_key, title, price_ugx, description, seller_name, seller_rating, location, category, image_count, is_featured FROM jiji_listings WHERE category = ? AND last_seen >= ?",
            arrayOf(category, (System.currentTimeMillis() - 7 * 86_400_000L).toString())
        )

        val listings = mutableListOf<JijiScraper.ScrapedListing>()
        while (cursor.moveToNext()) {
            listings.add(JijiScraper.ScrapedListing(
                listingKey = cursor.getString(0),
                title = cursor.getString(1),
                priceUgx = if (cursor.isNull(2)) null else cursor.getInt(2),
                description = cursor.getString(3),
                sellerName = cursor.getString(4),
                sellerRating = if (cursor.isNull(5)) null else cursor.getFloat(5),
                location = cursor.getString(6),
                category = cursor.getString(7),
                imageCount = cursor.getInt(8),
                isFeatured = cursor.getInt(9) == 1
            ))
        }
        cursor.close()
        return listings
    }

    private fun getTrackedCategories(): List<String> {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT DISTINCT category FROM jiji_listings WHERE category IS NOT NULL AND category != ''", null
        )
        val categories = mutableListOf<String>()
        while (cursor.moveToNext()) {
            categories.add(cursor.getString(0))
        }
        cursor.close()
        return categories
    }

    private fun findHotProducts(listings: List<JijiScraper.ScrapedListing>): List<String> {
        // Count product name frequency
        val titleWords = listings.map { it.title.lowercase() }
            .flatMap { it.split(" ") }
            .filter { it.length > 3 }
            .groupBy { it }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }

        return titleWords
    }

    private fun guessCategory(productTitle: String): String {
        val lower = productTitle.lowercase()
        return when {
            lower.contains("phone") || lower.contains("iphone") || lower.contains("samsung") || lower.contains("android") -> "Phones"
            lower.contains("laptop") || lower.contains("computer") || lower.contains("macbook") -> "Computers"
            lower.contains("tv") || lower.contains("television") -> "Electronics"
            lower.contains("sofa") || lower.contains("chair") || lower.contains("table") || lower.contains("furniture") -> "Furniture"
            lower.contains("dress") || lower.contains("shirt") || lower.contains("shoe") || lower.contains("fashion") -> "Fashion"
            lower.contains("cream") || lower.contains("beauty") || lower.contains("skin") || lower.contains("makeup") -> "Beauty"
            else -> "General"
        }
    }
}

data class MarketOpportunity(
    val category: String,
    val type: String,
    val description: String,
    val potentialRevenueUgx: Int,
    val confidence: Double
)
