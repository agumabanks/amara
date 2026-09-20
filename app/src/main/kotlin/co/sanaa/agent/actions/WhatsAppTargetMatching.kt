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

    /** Search text is only a locator. Never use this prefix as delivery identity. */
    fun searchQuery(value: String): String = value.trim().replace(Regex("(?:\\.\\.\\.|…)\\s*$"), "").trimEnd()

    fun matches(observed: String, requested: String): Boolean {
        fun normalize(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
            .replace(Regex("[\\p{Cf}]"), "").replace(Regex("[\\s\\u00a0]+"), " ").trim()
        val wanted = normalize(requested)
        val actual = normalize(observed)
        if (wanted.isEmpty() || isTruncated(wanted) || isTruncated(actual)) return false
        if (actual.equals(wanted, ignoreCase = true)) return true
        fun phone(value: String): String? = value
            .takeIf { it.all { c -> c.isDigit() || c in "+()- ." } }
            ?.filter(Char::isDigit)?.takeIf { it.length >= 8 }
        val number = phone(wanted) ?: return false
        return phone(actual) == number
    }
}
