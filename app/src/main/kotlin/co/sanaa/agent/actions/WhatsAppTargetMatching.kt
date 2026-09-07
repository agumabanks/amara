package co.sanaa.agent.actions

/** Exact names, or full phone numbers differing only in display punctuation. */
internal object WhatsAppTargetMatching {
    fun matches(observed: String, requested: String): Boolean {
        fun normalize(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
            .replace(Regex("[\\p{Cf}]"), "").replace(Regex("[\\s\\u00a0]+"), " ").trim()
        val wanted = normalize(requested)
        val actual = normalize(observed)
        if (wanted.isEmpty()) return false
        if (actual.equals(wanted, ignoreCase = true)) return true
        fun phone(value: String): String? = value
            .takeIf { it.all { c -> c.isDigit() || c in "+()- ." } }
            ?.filter(Char::isDigit)?.takeIf { it.length >= 8 }
        val number = phone(wanted) ?: return false
        return phone(actual) == number
    }
}
