package co.sanaa.agent.modules

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WhatsAppToneTest {
    @Test fun financialAndComplaintRepliesHaveNoEmojis() {
        assertEquals("UGX 50,000",WhatsAppTone.polish("UGX 50,000 😊🙏","Thanks, price?"))
        assertEquals("Let me check.",WhatsAppTone.polish("Let me check. 🙏","This is wrong"))
    }
    @Test fun friendlyAcknowledgementKeepsOnlyOneWholeEmoji() {
        assertEquals("You’re welcome 👍🏽",WhatsAppTone.polish("You’re welcome 👍🏽 😊","Thanks"))
        assertEquals("Hello",WhatsAppTone.polish("Hello 😊","Hello"))
    }
}
