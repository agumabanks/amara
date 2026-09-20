package co.sanaa.agent.modules

import co.sanaa.agent.core.ChatMessage

/** Small, conservative rules for conversational continuity, independent of model output. */
object ConversationReplyPolicy {
    private val acknowledgement = Regex("(?i)^(?:ok(?:ay)?|alright|got it|thanks|thank you|thanks a lot|thank you very much)[.! ,]*(?:thanks|thank you)?[.! ]*$")
    private val closingReply = Regex("(?i)^(?:you['’]re welcome|you are welcome|happy to help|no problem|my pleasure|anytime)[.! ]*$")

    fun needsNoReply(incoming: String, history: List<ChatMessage>): Boolean {
        if (!acknowledgement.matches(incoming.trim())) return false
        val previous = history.lastOrNull { it.direction == "sent" } ?: return false
        if (history.drop(history.indexOfLast { it.direction == "sent" } + 1)
                .any { it.direction == "received" && !acknowledgement.matches(it.text.trim()) }) return false
        // A price, question, instruction or unresolved issue is not a closing acknowledgement.
        return closingReply.matches(previous.text.trim())
    }

    fun groundedStage(proposed: ConversationStage): ConversationStage =
        if (proposed == ConversationStage.CONFIRMED) ConversationStage.CLOSING else proposed

    fun relevantOfferings(offerings: List<String>, incoming: String, history: List<ChatMessage>): List<String> {
        val ignored = setOf("the", "and", "you", "have", "with", "for", "please", "want", "need", "price", "much", "how", "ugx", "can", "this", "that")
        fun words(text: String) = Regex("[\\p{L}\\p{N}]{3,}").findAll(text.lowercase())
            .map { it.value }.filter { it !in ignored }.toSet()
        val current = words(incoming)
        val earlier = words(history.filter { it.direction == "received" }.takeLast(4).joinToString(" ") { it.text })
        return offerings.sortedByDescending { offering ->
            val title = words(offering.substringBefore(" ("))
            title.intersect(current).size * 10 + title.intersect(earlier).size
        }.take(5)
    }
}
