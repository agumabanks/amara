package co.sanaa.agent.actions

/** Read back a fresh search node: ACTION_SET_TEXT does not refresh the old node. */
internal object WhatsAppPickerSearch {
    fun matches(current: String?, query: String): Boolean = current != null &&
        current.replace(Regex("[\\p{Cf}]"), "") == query.replace(Regex("[\\p{Cf}]"), "")
    suspend fun enter(query: String, read: () -> String?, write: (String) -> Boolean,
                      open: () -> Boolean, settle: suspend () -> Unit): Boolean {
        if (query.isBlank()) return false
        repeat(24) {
            val current = read()
            if (matches(current, query)) return true
            if (current == null) open() else write(query)
            settle()
        }
        return matches(read(), query)
    }
}
