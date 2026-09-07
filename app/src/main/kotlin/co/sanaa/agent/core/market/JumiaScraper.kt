package co.sanaa.agent.core.market

import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.api.ElementSchema
import co.sanaa.agent.api.FieldType
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.api.ModelSchema
import co.sanaa.agent.core.InteractionKind
import org.json.JSONObject
import java.io.File

/**
 * Read-only Jumia Uganda intelligence adapter.
 *
 * Extracts grounded title/price pairs from visible catalogue cards, with the
 * owner-configured vision model as fallback for inaccessible storefronts. It never
 * adds to cart, signs in, purchases, or changes seller data.
 */
class JumiaScraper(
    private val db: MarketDatabase,
    private val actions: AccessibilityActions,
    private val groq: GroqClient,
) {
    data class CaptureResult(val products: Int, val screenshotPath: String, val surface: String, val pagesBrowsed: Int = 0, val sectionsVisited: Int = 0)

    fun isInstalled(): Boolean = actions.packageNameForApp("jumia") != null

    suspend fun captureFeaturedOffers(): CaptureResult {
        if (!isInstalled() || !actions.openAppByName("jumia")) return CaptureResult(0, "", "unavailable")
        if (actions.waitForForegroundPackage(PACKAGE_JUMIA) == null) return CaptureResult(0, "", "not_foreground")
        actions.pause(InteractionKind.NETWORK_CONTENT)
        for (attempt in 0 until 6) {
            val screen = actions.snapshot()
            if (screen.packageName != PACKAGE_JUMIA) return CaptureResult(0, "", "foreground_lost")
            if (screen.visibleText.any { it == "Categories" }) {
                actions.clickExactLabel("Home")
                break
            }
            if (screen.visibleText.isNotEmpty()) actions.globalBack()
            actions.pause(InteractionKind.NETWORK_CONTENT)
        }
        actions.pause(InteractionKind.NETWORK_CONTENT)
        // Browse first, extract second. A provider outage must not freeze navigation.
        val captured = mutableListOf<Pair<String, String>>()
        var sections = 1
        val directOffers = linkedMapOf<String, JumiaOfferParser.Offer>()
        suspend fun capture(section: String) {
            // Network-backed categories often render chrome before product cards.
            if (section != "categories") for (attempt in 0 until 24) {
                if (actions.visibleJumiaOffers().isNotEmpty()) break
                kotlinx.coroutines.delay(500)
            }
            actions.visibleJumiaOffers().forEach { directOffers[it.title] = it }
            val evidence = actions.captureScreenshot("jumia-${section}-${System.currentTimeMillis()}")
            evidence.path?.let { captured += section to it }
        }
        capture("home")
        if (actions.scrollPublicMarketPage(PACKAGE_JUMIA)) capture("home-scrolled")
        if (actions.snapshot().packageName == PACKAGE_JUMIA && actions.clickExactLabel("Categories", "Category")) {
            sections++
            actions.pause(InteractionKind.NETWORK_CONTENT)
            val categories = listOf("Computing", "Electronics", "Home & Office", "Phones & Tablets")
            val offset = ((System.currentTimeMillis() / 3_600_000) % categories.size).toInt()
            val ordered = categories.drop(offset) + categories.take(offset)
            val entered = ordered.any { actions.clickFullyVisibleLabel(it) }
            if (entered) {
                sections++; actions.pause(InteractionKind.NETWORK_CONTENT)
                if (actions.clickExactLabel("All Products")) { sections++; actions.pause(InteractionKind.NETWORK_CONTENT) }
            }
            capture(if (entered) "category-products" else "categories")
            if (actions.scrollPublicMarketPage(PACKAGE_JUMIA)) capture("category-scrolled")
        }
        android.util.Log.i("JumiaScraper", "Browse completed: pages=${captured.size} sections=$sections")
        if (directOffers.isNotEmpty()) {
            for (offer in directOffers.values) upsert("jumia:${offer.title.lowercase().replace(Regex("\\s+"), " ").hashCode().toUInt().toString(16)}",
                offer.title, offer.price, "", System.currentTimeMillis())
            return CaptureResult(directOffers.size, captured.lastOrNull()?.second.orEmpty(), "accessibility", captured.size, sections)
        }
        groq.visionConfigurationBlocker()?.let { return CaptureResult(0, captured.lastOrNull()?.second.orEmpty(), "configuration_required: $it", captured.size, sections) }
        val seen = mutableSetOf<String>()
        var stored = 0
        var lastPath = ""
        var surface = "other"
        for ((page, evidence) in captured.withIndex()) {
        val path = evidence.second
        lastPath = path
        val json = try { groq.completeVisionJson(
            """Read this Jumia Uganda storefront screenshot as market research.
                |Extract only product offers whose title and current UGX price are visibly legible.
                |Do not infer hidden text, previous prices, ratings, demand, stock, or seller identity.
                |Return ONLY JSON: {"surface":"home|category|search|other","products":[{"title":"","price_ugx":0,"badge":""}]}.
                |If no complete visible offer exists, return an empty products array.""".trimMargin(),
            File(path),
            JUMIA_SCREEN_SCHEMA,
            "jumia-market-${System.currentTimeMillis()}-$page",
        )
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.w("JumiaScraper", "Extraction deferred after browsing: ${error.javaClass.simpleName}")
            return CaptureResult(stored, lastPath, "extraction_deferred: ${error.message.orEmpty().take(160)}", captured.size, sections)
        }
        val now = System.currentTimeMillis()
        val products = json.optJSONArray("products")
        surface = json.optString("surface", "other")
        if (products != null) for (index in 0 until products.length()) {
            val product = products.optJSONObject(index) ?: continue
            val title = product.optString("title").trim().take(240)
            val price = product.optLong("price_ugx", 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (title.length < 4 || price <= 0) continue
            val identity = title.lowercase().replace("\\s+".toRegex(), " ")
            if (!seen.add(identity)) continue
            upsert(
                key = "jumia:${identity.hashCode().toUInt().toString(16)}",
                title = title,
                price = price,
                badge = product.optString("badge").trim().take(120),
                now = now,
            )
            stored++
        }
        }
        return CaptureResult(stored, lastPath, surface, captured.size, sections)
    }

    private fun upsert(key: String, title: String, price: Int, badge: String, now: Long) {
        val database = db.writableDatabase
        val cursor = database.rawQuery("SELECT id, price_ugx FROM jiji_listings WHERE listing_key = ?", arrayOf(key))
        if (cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            val oldPrice = cursor.getInt(1)
            database.execSQL(
                "UPDATE jiji_listings SET price_ugx = ?, description = ?, last_seen = ?, scraped_at = ? WHERE id = ?",
                arrayOf<Any>(price, badge, now, now, id),
            )
            if (oldPrice != price) database.execSQL(
                "INSERT INTO price_history(listing_key, price_ugx, recorded_at) VALUES (?, ?, ?)",
                arrayOf<Any>(key, price, now),
            )
        } else {
            database.execSQL(
                """INSERT INTO jiji_listings
                    (listing_key, title, price_ugx, description, seller_name, location, category,
                     image_count, is_featured, source, scraped_at, first_seen, last_seen)
                    VALUES (?, ?, ?, ?, 'Jumia', 'Uganda', 'Featured offers', 1, 1, 'JUMIA', ?, ?, ?)""",
                arrayOf<Any>(key, title, price, badge, now, now, now),
            )
        }
        cursor.close()
    }

    companion object {
        const val PACKAGE_JUMIA = "com.jumia.android"
        private val JUMIA_SCREEN_SCHEMA = ModelSchema(
            name = "jumia_market_screen",
            requiredFields = mapOf("surface" to FieldType.STRING, "products" to FieldType.ARRAY),
            enums = mapOf("surface" to setOf("home", "category", "search", "other")),
            arrayElements = mapOf(
                "products" to ElementSchema(
                    requiredFields = mapOf(
                        "title" to FieldType.STRING,
                        "price_ugx" to FieldType.NUMBER,
                        "badge" to FieldType.STRING,
                    ),
                    requiredNonBlank = setOf("title"),
                ),
            ),
        )
    }
}
