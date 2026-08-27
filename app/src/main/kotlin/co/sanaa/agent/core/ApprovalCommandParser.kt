package co.sanaa.agent.core

data class ApprovalCommand(
    val approve: Boolean,
    val ordinal: Int? = null,
    val targetHint: String = "",
    /** True when the owner asked to apply the approved change immediately ("approve and execute"). */
    val execute: Boolean = false,
)

object ApprovalCommandParser {
    private val ordinals = mapOf(
        "first" to 0, "1st" to 0, "one" to 0,
        "second" to 1, "2nd" to 1, "two" to 1,
        "third" to 2, "3rd" to 2, "three" to 2,
        "fourth" to 3, "4th" to 3, "four" to 3,
        "fifth" to 4, "5th" to 4, "five" to 4,
    )
    private val executeWords = listOf("and execute", "and apply", "and run", "and save", "& execute", "& apply")

    fun parse(command: String): ApprovalCommand? {
        val lower = command.lowercase().trim()
        val approve = when {
            Regex("^(approve|approved|yes,? approve|go ahead)(\\b|$)").containsMatchIn(lower) -> true
            Regex("^(reject|rejected|do not approve|don't approve|no,? reject)(\\b|$)").containsMatchIn(lower) -> false
            else -> return null
        }
        val execute = approve && executeWords.any(lower::contains)
        val ordinal = ordinals.entries.mapNotNull { entry ->
            Regex("\\b${entry.key}\\b").find(lower)?.range?.first?.let { position -> position to entry.value }
        }.minByOrNull { it.first }?.second
        val hint = lower
            .replace(Regex("^(approve|approved|yes,? approve|go ahead|reject|rejected|do not approve|don't approve|no,? reject)\\s*"), "")
            .replace(Regex("\\b(the|proposal|change|listing|one|first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th)\\b"), " ")
            .replace(Regex("\\b(and|execute|apply|run|save)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return ApprovalCommand(approve, ordinal, hint, execute)
    }
}
