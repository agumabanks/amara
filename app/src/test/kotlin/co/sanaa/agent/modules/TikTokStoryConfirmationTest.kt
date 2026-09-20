package co.sanaa.agent.modules

import org.junit.Assert.*
import org.junit.Test

class TikTokStoryConfirmationTest {
    @Test fun onlyExplicitStorySuccessConfirmsPublication() {
        assertTrue(TikTokStoryConfirmation.isConfirmation("Posted to your story"))
        assertTrue(TikTokStoryConfirmation.isConfirmation("Your story has been posted"))
        for(text in listOf("Your Story","Posting story","Story could not be posted","Post","Video posted"))
            assertFalse(text,TikTokStoryConfirmation.isConfirmation(text))
    }
    @Test fun anotherAppCannotProvideStoryEvidence() {
        TikTokStoryConfirmation.observe("com.zhiliaoapp.musically","Story posted",100)
        TikTokStoryConfirmation.observe("com.whatsapp","Story posted",200)
        assertEquals(100L,TikTokStoryConfirmation.latest?.first)
    }
}
