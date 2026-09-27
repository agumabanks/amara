package co.sanaa.agent.modules

class AdHeadlineUnavailable : IllegalArgumentException("A clear two- or three-word product headline is required before creating this ad")

/** A headline must identify the offering before it tries to attract attention. */
object AdHeadline {
    private val productNames = listOf("self-inking stamp", "business cards", "business card", "date stamp", "rubber stamp",
        "solar water pump", "water pump", "company stamp", "logo design", "graphic design", "web design", "website design",
        "banner printing", "flyer printing", "poster printing", "receipt books", "invoice books",
        "t-shirt printing", "office chair", "face masks", "company flag", "event badges", "name tags")
    private fun words(value: String) = Regex("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*")
        .findAll(value.lowercase()).map { it.value }.toList()
    private fun product(title: String) = productNames.firstOrNull { phrase ->
        Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(phrase)}(?![\\p{L}\\p{N}])").containsMatchIn(title)
    }
    private val distinctions = listOf("smart", "nfc", "digital", "wireless", "rechargeable")
    fun fallback(title: String): String {
        val tokens = words(title)
        val noun = product(title)
        val chosen = if (noun != null) {
            val modifier = distinctions.firstOrNull { it in tokens && it !in words(noun) }
            if (modifier != null && words(noun).size == 2) "$modifier $noun" else noun
        } else if (tokens.size in 2..3) tokens.joinToString(" ") else {
            throw AdHeadlineUnavailable()
        }
        return chosen.split(' ').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    }
    fun validated(title: String, candidate: String): String? {
        val clean = candidate.replace(Regex("\\s+"), " ").trim()
        val tokens = words(clean)
        if (clean.length !in 3..60 || tokens.size !in 2..3) return null
        val source = words(title).toSet()
        val selected = tokens.toSet()
        if (!source.containsAll(selected)) return null
        val noun = product(title)
        // For recognized products, enforce the shortest complete phrase, not merely
        // a maximum word count. Brand padding and reordered nouns are not headlines.
        if (noun != null && tokens != words(fallback(title))) return null
        val required = noun?.let(::words)?.toSet()
        if (required != null && !selected.containsAll(required)) return null
        // For unfamiliar titles, the model must retain the terminal product noun.
        if (required == null && words(title).lastOrNull() !in selected) return null
        val modifier = distinctions.firstOrNull { it in source }
        if (modifier != null && (required?.size ?: 2) < 3 && modifier !in selected) return null
        return clean
    }
}
