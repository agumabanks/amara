package co.sanaa.agent.core

/** Failed owner tasks report verified steps directly; a model may not rewrite partial work as an empty shop. */
internal object OwnerTaskOutcomeReport {
    fun incomplete(outcomes: List<Triple<String, Boolean, String>>): String {
        val successful = outcomes.filter { it.second }
        val failed = outcomes.firstOrNull { !it.second }
        val verified = successful.firstOrNull { it.first == "scan_soko_inventory" }
            ?: successful.firstOrNull()
        return buildString {
            append("I could not finish the full request.")
            if (verified != null) {
                append(" Verified: ")
                append(verified.third.trim().take(480))
            }
            if (failed != null) {
                append(" Blocker: ")
                append(failed.third.trim().take(240))
            }
            if (verified == null && failed == null) append(" No action was verified.")
        }
    }
}
