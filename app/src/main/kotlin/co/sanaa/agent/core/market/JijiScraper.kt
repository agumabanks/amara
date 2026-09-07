package co.sanaa.agent.core.market

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.actions.WhatsAppScreenSnapshot
import co.sanaa.agent.core.InteractionKind

/**
 * JijiScraper — Uses accessibility automation to read Jiji listings.
 * Scrapes product data, prices, sellers from the Jiji app.
 */
class JijiScraper(
    private val context: Context,
    private val db: MarketDatabase,
    private val actions: AccessibilityActions
) {
    companion object {
        const val PACKAGE_JIJI = "com.olx.ssa.ug"  // Jiji Uganda package
        const val NAME = "jiji_scraper"
        private val CARD_BADGES = setOf("ENTERPRISE", "Popular", "Verified", "Promoted", "Sponsored")
    }

    data class ScrapedListing(
        val listingKey: String,
        val title: String,
        val priceUgx: Int?,
        val description: String,
        val sellerName: String,
        val sellerRating: Float?,
        val location: String,
        val category: String,
        val imageCount: Int,
        val isFeatured: Boolean
    )

    /**
     * Check if Jiji is installed.
     */
    fun isInstalled(): Boolean {
        return actions.packageNameForApp("jiji") != null
    }

    /**
     * Open Jiji app.
     */
    suspend fun openJiji(): Boolean {
        if (!isInstalled()) return false
        return actions.openAppByName("jiji")
    }

    /**
     * Scrape a category page. Returns number of listings scraped.
     */
    @Volatile var lastFailure = ""
        private set
    private fun failed(stage: String): Boolean {
        lastFailure = stage
        android.util.Log.w("JijiScraper", "Stopped at $stage; package=${actions.snapshot().packageName}")
        return false
    }

    suspend fun scrapeCategory(category: String, maxItems: Int = 20): Int {
        lastFailure = ""
        if (!openJiji() || actions.waitForForegroundPackage(PACKAGE_JIJI) == null) { failed("foreground"); return 0 }
        var ready = false
        repeat(20) {
            if (!ready) {
                val screen = actions.snapshot()
                ready = isHome(screen) || screen.visibleText.any(::isPriceLine)
                if (!ready) kotlinx.coroutines.delay(300)
            }
        }
        if (!openCategoryResults(category)) return 0

        val listings = linkedMapOf<String, ScrapedListing>()
        var scrollCount = 0
        val maxScrolls = (maxItems / 5) + 1  // Assume ~5 items per screen

        while (scrollCount < maxScrolls && listings.size < maxItems) {
            val screen = actions.snapshot()
            val visibleItems = parseListingsFromScreen(screen, category)
            visibleItems.forEach { listings[it.listingKey] = it }

            if (!actions.scrollDown()) break
            actions.pause(InteractionKind.SCROLL_SETTLE)
            scrollCount++
        }

        // Store in database
        val now = System.currentTimeMillis()
        val writableDb = db.writableDatabase
        for (listing in listings.values.take(maxItems)) {
            upsertListing(writableDb, listing, now)
        }

        return listings.size.coerceAtMost(maxItems)
    }

    /**
     * Scrape search results for a query.
     */
    suspend fun scrapeSearch(query: String, maxItems: Int = 20): Int {
        if (!openJiji()) return 0

        // Type search query
        if (!actions.clickExactLabel("Search", "Search Jiji", "What are you looking for?")) return 0
        actions.pause(InteractionKind.TYPE_SETTLE)
        if (!actions.typeAndSendInCurrentChat(query)) return 0
        actions.pause(InteractionKind.NETWORK_CONTENT)

        return scrapeCategory(query, maxItems)
    }

    /**
     * Parse listings from current screen.
     */
    private suspend fun openCategoryResults(category: String): Boolean {
        repeat(6) {
            val screen = actions.snapshot()
            if (screen.packageName != PACKAGE_JIJI) return failed("category_stage_3")
            if (screen.contains("Search in $category") && screen.contains("Found") &&
                screen.visibleText.any(::isPriceLine)) return true
            if (isHome(screen)) return@repeat
            if (!actions.globalBack()) return failed("category_stage_7")
            actions.pause(InteractionKind.TAP_SETTLE)
        }

        if (!isHome(actions.snapshot())) return failed("category_stage_11")
        if (category.equals("Printers & Scanners", true)) {
            if (!actions.clickExactLabel("Electronics")) return failed("category_stage_13")
            actions.pause(InteractionKind.NETWORK_CONTENT)
        }

        repeat(8) {
            if (actions.clickFullyVisibleLabel(category)) {
                actions.pause(InteractionKind.NETWORK_CONTENT)
                val result = actions.snapshot()
                repeat(20) {
                    val settled = actions.snapshot()
                    if (settled.packageName == PACKAGE_JIJI && settled.visibleText.any(::isPriceLine)) return true
                    kotlinx.coroutines.delay(300)
                }
                return failed("category_stage_26")
            }
            if (!actions.scrollDown()) return failed("category_stage_28")
            actions.pause(InteractionKind.SCROLL_SETTLE)
        }
        return failed("category_stage_31")
    }


    private fun isHome(screen: WhatsAppScreenSnapshot): Boolean =
        screen.contains("What are you looking for?") && screen.contains("Trending")

    internal fun parseListingsFromScreen(screen: WhatsAppScreenSnapshot, category: String): List<ScrapedListing> {
        val listings = mutableListOf<ScrapedListing>()
        val textItems = screen.visibleText.filter { it.isNotBlank() }

        // Current Jiji Uganda cards expose: price, title, location/details,
        // badges, then seller. Anchor on a currency line so ad counts and
        // filter values cannot be mistaken for listing prices.
        textItems.indices.filter { isPriceLine(textItems[it]) }.forEach { priceIndex ->
            val priceText = textItems[priceIndex]
            val title = textItems.getOrNull(priceIndex + 1)?.trim().orEmpty()
            val location = textItems.getOrNull(priceIndex + 2)?.trim().orEmpty()
            val price = parsePrice(priceText)
            if (price != null && title.length > 3 && location.isNotBlank()) {
                val sellerName = textItems
                    .drop(priceIndex + 3)
                    .take(4)
                    .firstOrNull { it !in CARD_BADGES && !isPriceLine(it) }
                    ?: "Unknown"
                val identity = "$title|$location|$category"
                    .lowercase()
                    .replace("\\s+".toRegex(), " ")
                listings.add(ScrapedListing(
                    listingKey = "jiji:${identity.hashCode().toUInt().toString(16)}",
                    title = title,
                    priceUgx = price,
                    description = "",
                    sellerName = sellerName,
                    sellerRating = null,
                    location = location,
                    category = category,
                    imageCount = 1,
                    isFeatured = false
                ))
            }
        }

        return listings.distinctBy { it.listingKey }
    }

    private fun isPriceLine(text: String): Boolean =
        (text.contains("USh", true) || text.contains("UGX", true)) && parsePrice(text) != null

    /**
     * Parse price from text like "UGX 85,000" or "85000" or "85k".
     */
    private fun parsePrice(text: String): Int? {
        val cleaned = text.replace("[^\\d.]".toRegex(), "")
        if (cleaned.isBlank()) return null

        // Handle "85k" format
        if (text.contains("k", true)) {
            return (cleaned.toDoubleOrNull()?.times(1000))?.toInt()
        }

        return cleaned.toIntOrNull()
    }

    private fun upsertListing(db: SQLiteDatabase, listing: ScrapedListing, now: Long) {
        // Check if exists
        val cursor = db.rawQuery(
            "SELECT id, price_ugx FROM jiji_listings WHERE listing_key = ?",
            arrayOf(listing.listingKey)
        )

        if (cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            val oldPrice = cursor.getInt(1)

            // Update last_seen
            db.execSQL(
                "UPDATE jiji_listings SET last_seen = ?, price_ugx = ? WHERE id = ?",
                arrayOf(now.toString(), listing.priceUgx.toString(), id.toString())
            )

            // Record price change
            if (oldPrice != listing.priceUgx) {
                db.execSQL(
                    "INSERT INTO price_history(listing_key, price_ugx, recorded_at) VALUES (?, ?, ?)",
                    arrayOf(listing.listingKey, listing.priceUgx.toString(), now.toString())
                )
            }
        } else {
            // Insert new listing
            db.execSQL(
                """INSERT INTO jiji_listings 
                   (listing_key, title, price_ugx, description, seller_name, seller_rating, 
                    location, category, image_count, is_featured, source, scraped_at, first_seen, last_seen)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'JIJI', ?, ?, ?)""",
                arrayOf(
                    listing.listingKey, listing.title, listing.priceUgx?.toString(),
                    listing.description, listing.sellerName, listing.sellerRating?.toString(),
                    listing.location, listing.category, listing.imageCount.toString(),
                    if (listing.isFeatured) "1" else "0", now.toString(), now.toString(), now.toString()
                )
            )
        }
        cursor.close()
    }

    /**
     * Get total listings in database.
     */
    fun getTotalListings(): Int {
        val cursor = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM jiji_listings", null)
        return if (cursor.moveToFirst()) cursor.getInt(0) else 0.also { cursor.close() }
    }

    /**
     * Get listings by category.
     */
    fun getListingsByCategory(category: String, limit: Int = 50): List<ScrapedListing> {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT listing_key, title, price_ugx, description, seller_name, seller_rating, location, category, image_count, is_featured FROM jiji_listings WHERE category = ? ORDER BY scraped_at DESC LIMIT ?",
            arrayOf(category, limit.toString())
        )

        val listings = mutableListOf<ScrapedListing>()
        while (cursor.moveToNext()) {
            listings.add(ScrapedListing(
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
}
