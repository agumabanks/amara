package co.sanaa.agent.actions

/** Exact names, or full phone numbers differing only in display punctuation. */
internal object WhatsAppTargetMatching {
    fun phone(value: String): String? {
        val clean = value.trim().replace(Regex("[\\p{Cf}]"), "")
        if (clean.isBlank() || clean.any { !it.isDigit() && it !in "+()- ." }) return null
        val digits = clean.filter(Char::isDigit)
        return when {
            digits.startsWith("0") && digits.length == 10 -> "256" + digits.drop(1)
            digits.length in 10..15 -> digits
            else -> null
        }
    }

    fun isTruncated(value: String): Boolean = value.trimEnd().let { it.endsWith("…") || it.endsWith("...") }

    /** A full accessibility icon label locates its row; the opened chat must still be verified. */
    fun groupIconMatches(description: String, requested: String): Boolean =
        description.endsWith(", group profile icon") &&
            matches(description.removeSuffix(", group profile icon"), requested)

    /** Search text is only a locator. Never use this prefix as delivery identity. */
    fun searchQuery(value: String): String = value.trim().replace(Regex("(?:\\.\\.\\.|…)\\s*$"), "").trimEnd()

    fun matches(observed: String, requested: String): Boolean {
        fun normalize(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
            .replace(Regex("[\\p{Cf}]"), "").replace(Regex("[\\s\\u00a0]+"), " ").trim()
        val wanted = normalize(requested)
        val actual = normalize(observed)
        if (wanted.isEmpty() || isTruncated(wanted) || isTruncated(actual)) return false
        if (actual.equals(wanted, ignoreCase = true)) return true
        val number = phone(wanted) ?: return false
        return phone(actual) == number
    }
}

/** Conservative matching for the message that launched an exact WhatsApp route. */
internal object WhatsAppOriginText {
    private fun clean(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
        .replace(Regex("[\\p{Cf}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun matches(observed: String, requested: String): Boolean {
        val actual = clean(observed)
        val wanted = clean(requested)
        return actual.isNotBlank() && actual == wanted
    }

    /** A collapsed prefix locates the row to expand, but never proves the full incoming message. */
    fun expandablePrefix(observed: String, requested: String): Boolean {
        val actual = clean(observed)
        val wanted = clean(requested)
        val prefix = actual.replace(
            Regex("(?:\\.\\.\\.|…)\\s*Read more$", RegexOption.IGNORE_CASE),
            "",
        ).trimEnd()
        return prefix != actual && prefix.length >= 64 && wanted.length > prefix.length &&
            wanted.startsWith(prefix)
    }
}
