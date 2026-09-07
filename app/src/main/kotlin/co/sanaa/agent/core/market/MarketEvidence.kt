package co.sanaa.agent.core.market

/** Matching is conservative: different model numbers/conditions cannot drive pricing. */
object MarketEvidence {
    private val stop = setOf("the", "for", "and", "with", "new", "brand", "sale", "uganda", "kampala", "original", "used", "refurbished")
    private fun tokens(text: String) = Regex("[a-z0-9]+").findAll(text.lowercase()).map { it.value }.filter { it.length > 1 && it !in stop }.toSet()
    fun comparable(ours: String, theirs: String): Boolean {
        val a = tokens(ours); val b = tokens(theirs)
        if (a.size < 2 || b.size < 2) return ours.trim().equals(theirs.trim(), true)
        val modelsA = a.filter { word -> word.any(Char::isDigit) }.toSet()
        val modelsB = b.filter { word -> word.any(Char::isDigit) }.toSet()
        if (modelsA != modelsB) return false
        val used = Regex("\\b(used|refurbished|second.hand)\\b", RegexOption.IGNORE_CASE)
        if (used.containsMatchIn(ours) != used.containsMatchIn(theirs)) return false
        return a.intersect(b).size.toDouble() / a.union(b).size >= 0.6
    }
}
