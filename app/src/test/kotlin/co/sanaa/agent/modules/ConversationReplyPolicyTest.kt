package co.sanaa.agent.modules

import co.sanaa.agent.core.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class ConversationReplyPolicyTest {
    private fun sent(text: String) = ChatMessage("Amara", "sent", text, 1)
    @Test fun closesOnlyAnAcknowledgementLoop() {
        assertTrue(ConversationReplyPolicy.needsNoReply("Thanks!", listOf(sent("You’re welcome."))))
        assertTrue(ConversationReplyPolicy.needsNoReply("Okay, thanks", listOf(sent("Happy to help!"))))
        assertFalse(ConversationReplyPolicy.needsNoReply("Thanks, what is the price?", listOf(sent("You’re welcome."))))
        assertFalse(ConversationReplyPolicy.needsNoReply("Okay", listOf(sent("Would you like delivery?"))))
        assertFalse(ConversationReplyPolicy.needsNoReply("Thanks", emptyList()))
        assertFalse(ConversationReplyPolicy.needsNoReply("Thanks", listOf(sent("You’re welcome."),
            ChatMessage("Customer", "received", "The payment failed", 2))))
    }
    @Test fun chatIntentCannotConfirmAnOrder() {
        assertEquals(ConversationStage.CLOSING, ConversationReplyPolicy.groundedStage(ConversationStage.CONFIRMED))
        assertEquals(ConversationStage.NEGOTIATING, ConversationReplyPolicy.groundedStage(ConversationStage.NEGOTIATING))
    }
    @Test fun relevantServiceOutranksUnrelatedRecentCatalogueRows() {
        val rows = (1..30).map { "Office chair $it (UGX 50000)" } + "Wedding photography (UGX 300000)"
        assertEquals("Wedding photography (UGX 300000)",
            ConversationReplyPolicy.relevantOfferings(rows, "Do you offer wedding photography?", emptyList()).first())
        assertEquals(5, ConversationReplyPolicy.relevantOfferings(rows, "Hello", emptyList()).size)
    }
}
