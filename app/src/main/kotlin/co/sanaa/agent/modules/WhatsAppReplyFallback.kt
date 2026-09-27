package co.sanaa.agent.modules

import org.json.JSONArray
import org.json.JSONObject

/** A small grounded fallback when the reply model is unavailable or too slow. */
internal object WhatsAppReplyFallback {
    fun forMessage(incoming: String, stage: ConversationStage): JSONObject {
        val clean = incoming.trim()
        val greeting = Regex("(?i)^(?:hi|hello|hey|good (?:morning|afternoon|evening))[.! ]*$")
        val thanks = Regex("(?i)^(?:thanks|thank you|okay|ok)[.! ]*$")
        val (reply, escalate, reason) = when {
            greeting.matches(clean) -> Triple("Hi. How can I help you today?", false, "")
            thanks.matches(clean) -> Triple("You’re welcome.", false, "")
            else -> Triple(
                "Thanks for your message. I need the manager to confirm that detail before I answer.",
                true,
                "The reply service was unavailable, so the requested detail needs manager confirmation.",
            )
        }
        return JSONObject()
            .put("messages", JSONArray().put(reply))
            .put("stage", stage.name)
            .put("summary", "Customer message received; any requested business detail still needs confirmation.")
            .put("escalate", escalate)
            .put("escalation_reason", reason)
            .put("customer_facts", JSONArray())
    }
}
