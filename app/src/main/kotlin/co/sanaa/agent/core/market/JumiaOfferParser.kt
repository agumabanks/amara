package co.sanaa.agent.core.market

/** Strict visible card parsing; crossed-out/ambiguous prices require visual review. */
object JumiaOfferParser {
    data class Offer(val title: String, val price: Int)
    private val price = Regex("(?i)^(?:UGX|USh|UShs|Shs)\\s*([0-9][0-9, ]*)(?:\\.00)?$")
    fun card(labels: List<String>): Offer? {
        val lines = labels.map(String::trim).filter(String::isNotBlank).distinct()
        val prices = lines.mapNotNull { line -> price.matchEntire(line)?.groupValues?.get(1)?.filter(Char::isDigit)?.toIntOrNull() }.distinct()
        if (prices.size != 1 || prices.single() <= 0) return null
        val title = lines.firstOrNull { line -> line.length >= 8 && line.any(Char::isLetter) &&
            !price.matches(line) && !line.contains("%") && !line.matches(Regex("(?i)(add to cart|official store|sponsored|free delivery|jumia express)")) } ?: return null
        return Offer(title.take(240), prices.single())
    }
}
