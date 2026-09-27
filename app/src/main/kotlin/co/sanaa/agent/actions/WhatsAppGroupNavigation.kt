package co.sanaa.agent.actions

/** A launch token is a locator, never proof of the selected group. */
internal object WhatsAppGroupNavigation {
    suspend fun open(exact: () -> Boolean, route: () -> Boolean,
                     settle: suspend () -> Unit, search: suspend () -> Boolean): Boolean {
        if (exact()) return true
        if (route()) repeat(12) {
            settle()
            if (exact()) return true
        }
        return search() && exact()
    }
}
