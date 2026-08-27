package co.sanaa.agent.actions

data class SokoInventoryItem(
    val name: String,
    val priceUgx: Long?,
    val priceText: String,
    val stockState: String,
    val rawText: String,
    val weakReasons: List<String>,
)

data class SokoInventoryScan(
    val items: List<SokoInventoryItem>,
    val screensRead: Int,
    val reachedEnd: Boolean,
    val duplicateCardsIgnored: Int,
    val failure: String? = null,
)

data class SokoBooking(val service: String, val customer: String, val status: String)
data class SokoBookingsScan(val bookings: List<SokoBooking>, val failure: String? = null)

internal object SokoBookingParser {
    private val navigation = setOf(
        "inbox", "refresh", "needs action", "in progress", "completed", "all", "orders", "bookings", "refunds",
        "show menu", "checkout", "sales", "create or open a seller tool", "alerts", "more",
    )

    fun parse(labels: List<String>): List<SokoBooking> = labels.mapNotNull { raw ->
        val lines = raw.split(Regex("[\\r\\n]+")).map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter(String::isNotBlank)
        if (lines.size != 2 || lines[0].lowercase() in navigation || lines[1].lowercase() in navigation) null
        else SokoBooking(lines[0], lines[1], "Needs action")
    }.distinctBy { "${it.service}|${it.customer}".lowercase() }
}

data class SokoServiceListing(
    val name: String,
    val description: String,
    val priceUgx: Long?,
    val status: String,
    val weakReasons: List<String>,
)
data class SokoServicesScan(
    val listings: List<SokoServiceListing>,
    val advertisedTotal: Int?,
    val screensRead: Int,
    val reachedEnd: Boolean,
    val failure: String? = null,
)

data class SokoAlert(val type: String, val subject: String, val detail: String)
data class SokoAlertsScan(val alerts: List<SokoAlert>, val failure: String? = null)

internal object SokoAlertParser {
    private val navigation = setOf(
        "inbox", "refresh", "needs action", "in progress", "completed", "all", "orders", "bookings", "refunds",
        "show menu", "checkout", "sales", "create or open a seller tool", "alerts", "more",
    )
    fun parse(labels: List<String>): List<SokoAlert> = labels.mapNotNull { raw ->
        val lines = raw.split(Regex("[\\r\\n]+")).map(String::trim).filter(String::isNotBlank)
        if (lines.size != 2 || lines[0].lowercase() in navigation) return@mapNotNull null
        val type = when {
            lines[0].startsWith("Order ", true) -> "Order"
            lines[0].equals("Low stock", true) -> "Low stock"
            else -> "Booking"
        }
        SokoAlert(type, lines[0], lines[1])
    }.distinctBy { "${it.type}|${it.subject}|${it.detail}".lowercase() }
}

data class SokoBuyerService(
    val name: String,
    val seller: String,
    val priceUgx: Long?,
    val turnaround: String,
    val rating: Double?,
    val reviewCount: Int?,
    val weakReasons: List<String>,
)
data class SokoBuyerServicesScan(
    val services: List<SokoBuyerService>,
    val advertisedTotal: Int?,
    val screensRead: Int,
    val reachedEnd: Boolean,
    val failure: String? = null,
)

internal object SokoBuyerServiceParser {
    private val price = Regex("(?i)^([0-9][0-9, ]*)\\s*/?=$")
    private val turnaround = Regex("(?i)^\\d+\\s*(?:-|to)\\s*\\d+\\s*days?$")
    private val rating = Regex("^[0-5](?:\\.\\d+)?$")
    private val reviews = Regex("^\\((\\d+)\\)$")

    fun parse(labels: List<String>): List<SokoBuyerService> = labels.mapNotNull { raw ->
        val lines = raw.split(Regex("[\\r\\n]+")).map(String::trim).filter(String::isNotBlank)
        if (lines.size < 5 || !turnaround.matches(lines.first())) return@mapNotNull null
        val priceIndex = lines.indexOfLast { price.matches(it) }
        if (priceIndex < 3) return@mapNotNull null
        val name = lines.getOrNull(1).orEmpty()
        val seller = lines.getOrNull(2).orEmpty()
        val ratingValue = lines.drop(3).firstOrNull { rating.matches(it) }?.toDoubleOrNull()
        val reviewCount = lines.drop(3).firstNotNullOfOrNull { reviews.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        val amount = price.matchEntire(lines[priceIndex])?.groupValues?.get(1)?.filter(Char::isDigit)?.toLongOrNull()
        if (name.isBlank()) return@mapNotNull null
        val weak = buildList {
            if (seller.isBlank()) add("seller is not shown")
            if (amount == null || amount <= 0) add("price is not shown")
            if (name.length < 5) add("title is too short")
            if (ratingValue == null) add("rating is not shown")
        }
        SokoBuyerService(name, seller, amount, lines.first(), ratingValue, reviewCount, weak)
    }.distinctBy { "${it.seller}|${it.name}".lowercase().filter(Char::isLetterOrDigit) }
}

internal object SokoServiceParser {
    private val price = Regex("(?i)^(?:UGX\\s*)?([0-9][0-9, ]*)\\s*/?=$")
    private val states = setOf("live", "draft", "pending")
    private val navigation = setOf("services", "all", "calendar", "availability", "clients", "insights", "sync from seller")
    private val stopWords = setOf("and", "the", "for", "with", "from", "your", "service", "services", "custom", "professional")

    fun parse(labels: List<String>): List<SokoServiceListing> = labels.mapNotNull(::parseCard)
        .distinctBy { it.name.lowercase().filter(Char::isLetterOrDigit) }

    private fun parseCard(raw: String): SokoServiceListing? {
        val lines = raw.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter(String::isNotBlank)
        if (lines.size < 3) return null
        val stateIndex = lines.indexOfLast { it.lowercase() in states }
        if (stateIndex < 2) return null
        val name = lines.first().take(220)
        if (name.lowercase() in navigation) return null
        val priceIndex = lines.subList(1, stateIndex).indexOfFirst { price.matches(it) }
            .takeIf { it >= 0 }?.plus(1)
        val descriptionEnd = priceIndex ?: stateIndex
        val description = lines.subList(1, descriptionEnd).joinToString(" ").take(1_500)
        val amount = priceIndex?.let { price.matchEntire(lines[it])?.groupValues?.get(1)?.filter(Char::isDigit)?.toLongOrNull() }
        val weak = buildList {
            if (name.length < 5) add("title is too short")
            if (description.length < 35) add("description is too thin")
            if (amount == null || amount <= 0) add("price is missing")
            val titleWords = meaningfulWords(name)
            val descriptionWords = meaningfulWords(description)
            if (titleWords.isNotEmpty() && descriptionWords.isNotEmpty() && titleWords.intersect(descriptionWords).isEmpty()) {
                add("description may not match the title")
            }
        }
        return SokoServiceListing(name, description, amount, lines[stateIndex].replaceFirstChar(Char::uppercase), weak)
    }

    private fun meaningfulWords(value: String): Set<String> = value.lowercase().split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 4 && it !in stopWords }.toSet()
}

internal object SokoInventoryParser {
    private val combined = Regex(
        "(?i)(?:UGX\\s*)?([0-9][0-9, ]*)\\s*/?=\\s*(.+?)\\s+(In stock|Out of stock|Low stock|Sold out)(?:\\b|$)",
    )
    private val withoutPrice = Regex("(?i)^(.{4,}?)\\s+(In stock|Out of stock|Low stock|Sold out)(?:\\b|$)")
    private val nameThenPrice = Regex("(?i)^(.{3,}?)\\s+(?:UGX\\s*)?([0-9][0-9, ]*)\\s*/?=$")

    fun parse(labels: List<String>): List<SokoInventoryItem> = labels.mapNotNull(::parseCard)
        .distinctBy { normalizeName(it.name) }

    private fun parseCard(rawValue: String): SokoInventoryItem? {
        val raw = rawValue.replace(Regex("\\s+"), " ").trim()
        val priced = combined.find(raw)
        val name: String
        val priceText: String
        val price: Long?
        val stock: String
        if (priced != null) {
            priceText = priced.groupValues[1].trim()
            price = priceText.filter(Char::isDigit).toLongOrNull()
            name = cleanName(priced.groupValues[2])
            stock = priced.groupValues[3]
        } else {
            val trailingPrice = nameThenPrice.find(raw)
            if (trailingPrice != null) {
                name = cleanName(trailingPrice.groupValues[1])
                priceText = trailingPrice.groupValues[2].trim()
                price = priceText.filter(Char::isDigit).toLongOrNull()
                stock = "Stock not shown"
            } else {
                val unpriced = withoutPrice.find(raw) ?: return null
                name = cleanName(unpriced.groupValues[1])
                priceText = ""
                price = null
                stock = unpriced.groupValues[2]
            }
        }
        if (name.length < 2 || name.lowercase() in navigationLabels) return null
        val weaknesses = buildList {
            if (price == null || price <= 0) add("price is not shown")
            if (name.length < 5) add("title is too short")
            if (name.lowercase() in genericTitles) add("title is too generic")
        }
        return SokoInventoryItem(name, price, priceText, normalizeStock(stock), raw, weaknesses)
    }

    private fun cleanName(value: String) = value.trim(' ', '-', ':', '|').replace(Regex("\\s+"), " ").take(180)
    private fun normalizeName(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
    private fun normalizeStock(value: String) = value.lowercase().replaceFirstChar(Char::uppercase)
    private val navigationLabels = setOf("products", "point of sale", "checkout", "sales", "alerts", "more")
    private val genericTitles = setOf("product", "item", "new product", "good product", "available")
}
