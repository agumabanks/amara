package co.sanaa.agent.modules

/**
 * SokoCatalogModule — crawls BOTH products and services from Soko Terminal.
 * Stores them in the unified offerings table in ChatStore.
 *
 * @param actions AccessibilityActions instance for crawling
 * @param chatStore ChatStore instance for storing offerings
 * @param pinProvider Function that provides the current Soko Terminal PIN
 */
class SokoCatalogModule(
    private val actions: co.sanaa.agent.actions.AccessibilityActions,
    private val chatStore: co.sanaa.agent.core.ChatStore,
    private val pinProvider: () -> String = { "" },
    private val backend: co.sanaa.agent.api.SokoApiClient? = null,
) {
    data class SyncResult(
        val productsSynced: Int = 0,
        val servicesSynced: Int = 0,
        val productFailure: String? = null,
        val serviceFailure: String? = null,
    ) {
        val offeringCount: Int get() = productsSynced + servicesSynced
        val success: Boolean get() = offeringCount > 0
        val complete: Boolean get() = productFailure == null && serviceFailure == null
        val failureSummary: String get() = listOfNotNull(productFailure, serviceFailure)
            .joinToString(" ").ifBlank { "Soko catalog sync produced no verified offerings." }
    }

    companion object {
        private const val TAG = "SokoCatalog"
    }

    /**
     * Syncs all products and services from Soko Terminal.
     * Uses the PIN from pinProvider to authenticate with Soko Terminal.
     */
    suspend fun syncAll(): SyncResult {
        val pin = pinProvider()
        if (pin.isBlank()) {
            android.util.Log.w(TAG, "Cannot sync catalog: Soko PIN is empty")
            return backendFallback(SyncResult(productFailure = "No usable Soko Terminal PIN is available.", serviceFailure = "Service sync was not attempted."))
        }

        var productsSynced = 0
        var servicesSynced = 0
        var productFailure: String? = null
        var serviceFailure: String? = null

        // Sync products from Soko Terminal inventory
        try {
            val productScan = actions.crawlSokoInventory(pin)
            if (productScan.failure == null && productScan.items.isNotEmpty()) {
                productScan.items.forEach { item ->
                    chatStore.storeOffering(
                        type = "PRODUCT",
                        name = item.name,
                        price = item.priceUgx,
                        priceFloor = null,
                        // "In stock" is not evidence of an exact quantity. Only
                        // preserve zero when the visible Terminal state is explicit.
                        stockCount = if (
                            item.stockState.contains("out of stock", true) ||
                            item.stockState.contains("sold out", true)
                        ) 0 else null,
                        durationMins = null,
                        requiresBooking = false,
                        description = item.rawText,
                        source = "Soko Terminal"
                    )
                }
                productsSynced = productScan.items.size
                android.util.Log.i(TAG, "Synced ${productScan.items.size} products")
            } else if (productScan.failure != null) {
                productFailure = productScan.failure
                android.util.Log.w(TAG, "Product scan failed: ${productScan.failure}")
            } else {
                productFailure = "The current Terminal product scan returned no verified products."
            }
        } catch (e: Exception) {
            productFailure = "Product sync failed: ${e.message ?: e.javaClass.simpleName}"
            android.util.Log.e(TAG, "Error syncing products", e)
        }

        // Sync services from Soko Terminal Services manager
        try {
            val serviceScan = actions.auditSokoServices(pin)
            if (serviceScan.failure == null && serviceScan.listings.isNotEmpty()) {
                serviceScan.listings.forEach { listing ->
                    chatStore.storeOffering(
                        type = "SERVICE",
                        name = listing.name,
                        price = listing.priceUgx,
                        priceFloor = null,
                        stockCount = null,
                        durationMins = null,
                        requiresBooking = true,
                        description = listing.description,
                        source = "Soko Terminal Services"
                    )
                }
                servicesSynced = serviceScan.listings.size
                android.util.Log.i(TAG, "Synced ${serviceScan.listings.size} services")
            } else if (serviceScan.failure != null) {
                serviceFailure = serviceScan.failure
                android.util.Log.w(TAG, "Service scan failed: ${serviceScan.failure}")
            } else {
                serviceFailure = "The current Terminal service scan returned no verified services."
            }
        } catch (e: Exception) {
            serviceFailure = "Service sync failed: ${e.message ?: e.javaClass.simpleName}"
            android.util.Log.e(TAG, "Error syncing services", e)
        }
        return backendFallback(SyncResult(productsSynced, servicesSynced, productFailure, serviceFailure))
    }

    private suspend fun backendFallback(terminal: SyncResult): SyncResult {
        val api = backend ?: return terminal
        var result = terminal
        if (result.productsSynced == 0) {
            try {
                val products = api.activeListings().filter { it.title.isNotBlank() }
                products.forEach { item ->
                    chatStore.storeOffering("PRODUCT", item.title, item.priceUgx.takeIf { it > 0 }, null,
                        item.stock, null, false, item.description, "Soko authenticated shop catalogue")
                }
                if (products.isNotEmpty()) result = result.copy(productsSynced = products.size, productFailure = null)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error
            } catch (error: Exception) {
                result = result.copy(productFailure = "Terminal unavailable; authenticated product catalogue fallback failed (${error.javaClass.simpleName}).")
            }
        }
        if (result.servicesSynced == 0) {
            try {
                val services = api.publishedServices()
                services.forEach { item ->
                    chatStore.storeOffering("SERVICE", item.getString("title"), item.optLong("base_price").takeIf { it > 0 }, null,
                        null, null, true, item.optString("description", item.optString("summary")), "Soko authenticated shop services")
                }
                if (services.isNotEmpty()) result = result.copy(servicesSynced = services.size, serviceFailure = null)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error
            } catch (error: Exception) {
                result = result.copy(serviceFailure = "Terminal unavailable; authenticated service catalogue fallback failed (${error.javaClass.simpleName}).")
            }
        }
        return result
    }
}
