package co.sanaa.agent.api

import co.sanaa.agent.core.SecureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reads Soko projections from Amara's server-side SELECT-only database bridge.
 * It never calls the Soko API and intentionally exposes no database write path.
 */
class SokoApiClient(private val config: SecureConfig) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun activeListings(): List<SokoListing> = getArray("listings").mapNotNull(::parseListing)

    /** Authenticated bridge applies the registered business scope server-side. */
    suspend fun publishedServices(): List<JSONObject> = getArray("services").filter {
        it.optString("id").isNotBlank() && it.optString("title").isNotBlank() &&
            it.optString("currency").equals("UGX", true)
    }

    /** Products and services share rotation, but preserve distinct IDs and routes. */
    suspend fun promotableOfferings(): List<SokoListing> = activeListings() + publishedServices().map { service ->
        SokoListing(
            id = "service:${service.getString("id")}", title = service.getString("title"),
            description = service.optString("description", service.optString("summary")),
            priceUgx = service.optLong("base_price"), category = "Services",
            photoCount = if (service.optString("image_url").isNotBlank()) 1 else 0,
            viewCount = service.optInt("view_count"), stock = null,
            imageUrl = service.optString("image_url").takeIf { it.isNotBlank() && it != "null" },
            raw = JSONObject(service.toString()).put("offering_type", "SERVICE"),
        )
    }

    suspend fun competitors(category: String): List<SokoListing> = activeListings()
        .filter { category.isNotBlank() && it.category.equals(category, ignoreCase = true) }

    suspend fun unreadMessages(): List<SokoMessage> = getArray("messages")
        .filter { it.isNull("read_at") }
        .map { json ->
            SokoMessage(
                json.optString("id"),
                "Buyer ${json.optString("buyer_id")}",
                json.optString("message"),
                json.optString("conversation_id"),
                json,
            )
        }

    suspend fun pendingOrders(): List<JSONObject> = getArray("orders")
        .filter { it.optString("delivery_status").lowercase() !in setOf("delivered", "cancelled") }

    /** Soko remains read-only; customer replies must be performed through phone UI automation. */
    suspend fun reply(threadId: String, message: String): Boolean = false

    suspend fun listing(id: String): SokoListing? = activeListings().firstOrNull { it.id == id }

    suspend fun isHealthy(): Boolean = runCatching { activeListings(); true }.getOrDefault(false)

    private suspend fun getArray(resource: String): List<JSONObject> = withContext(Dispatchers.IO) {
        if (config.agentToken.isBlank() || config.deviceId.isBlank()) {
            throw IllegalStateException("Agent is not registered")
        }
        val url = "${config.backendUrl.trimEnd('/')}/soko/$resource?device_id=${config.deviceId}"
        val request = Request.Builder().url(url).get()
            .header("Authorization", "Bearer ${config.agentToken}")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Soko database bridge returned HTTP ${response.code}")
            val array = JSONObject(body).optJSONArray("data") ?: JSONArray()
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }
    }

    private fun parseListing(json: JSONObject?): SokoListing? {
        json ?: return null
        val id = json.optString("id")
        if (id.isBlank()) return null
        val photos = json.optString("photos").split(',').filter(String::isNotBlank)
        return SokoListing(
            id = id,
            title = json.optString("title"),
            description = json.optString("description"),
            priceUgx = json.optLong("price_ugx"),
            category = json.optString("category", json.optString("category_id")),
            photoCount = photos.size.coerceAtLeast(if (json.optString("image_path").isBlank()) 0 else 1),
            viewCount = json.optInt("view_count"),
            stock = if (json.has("current_stock")) json.optInt("current_stock") else null,
            imageUrl = json.optString("image_url").takeIf { it.isNotBlank() },
            raw = json,
        )
    }
}
