package co.sanaa.agent.modules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppNotificationParserTest {
    @Test fun notificationCountsDoNotChangeGroupIdentity() {
        org.junit.Assert.assertEquals("Naalya E-Trade", WhatsAppNotificationParser.cleanConversationTitle("Naalya E-Trade (7 messages)"))
        org.junit.Assert.assertEquals("Club (2026)", WhatsAppNotificationParser.cleanConversationTitle("Club (2026)"))
        org.junit.Assert.assertEquals("Naalya E-Trade", WhatsAppInbound("Ann", "Hi", "Naalya E-Trade (1 message)", true).target)
    }
    @Test fun repeatedWordsInNewEventsAreNotPermanentDuplicates() {
        org.junit.Assert.assertNotEquals(
            WhatsAppInbound("Ann", "Hi", "Ann", false, messageTimestamp = 100).signature,
            WhatsAppInbound("Ann", "Hi", "Ann", false, messageTimestamp = 200).signature,
        )
    }

    @Test fun nonConversationalNotificationsAreRejected() {
        listOf(
            "Liked your status",
            "Reacted 👍 to \"hello\"",
            "🎤 Voice message (0:22)",
            "Video (1:30)",
            "Sticker",
        ).forEach { assertTrue(it, WhatsAppNotificationParser.isNonConversationalEvent(it)) }
    }

    @Test fun realCustomerWordsRemainActionable() {
        listOf("Is it available?", "It’s back", "I sent a voice message yesterday")
            .forEach { assertFalse(it, WhatsAppNotificationParser.isNonConversationalEvent(it)) }
    }
}
