package co.sanaa.agent.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppReplyFallbackTest {
    @Test fun greetingAndThanksStayBriefWithoutInventingFacts() {
        val greeting = WhatsAppReplyFallback.forMessage("Hello", ConversationStage.GREETING)
        assertEquals("Hi. How can I help you today?", greeting.getJSONArray("messages").getString(0))
        assertFalse(greeting.getBoolean("escalate"))
        val thanks = WhatsAppReplyFallback.forMessage("Thank you!", ConversationStage.CLOSING)
        assertEquals("You’re welcome.", thanks.getJSONArray("messages").getString(0))
        assertFalse(thanks.getBoolean("escalate"))
    }

    @Test fun unansweredBusinessDetailGetsDurableManagerHandoff() {
        val fallback = WhatsAppReplyFallback.forMessage("Can you deliver tomorrow?", ConversationStage.DISCOVERY)
        assertTrue(fallback.getBoolean("escalate"))
        assertTrue(fallback.getString("escalation_reason").contains("unavailable"))
        assertFalse(fallback.getJSONArray("messages").getString(0).contains("tomorrow"))
    }
}
