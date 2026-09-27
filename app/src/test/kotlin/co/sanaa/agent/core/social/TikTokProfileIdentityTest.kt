package co.sanaa.agent.core.social

import org.junit.Assert.*
import org.junit.Test

class TikTokProfileIdentityTest {
    @Test fun ownerControlsAreCaseInsensitive() {
        assertTrue(TikTokProfileIdentity.hasOwnerControls(listOf("Edit profile", "followers")))
        assertTrue(TikTokProfileIdentity.hasOwnerControls(listOf("Business Suite", "Followers")))
        assertFalse(TikTokProfileIdentity.hasOwnerControls(listOf("@mention", "Followers")))
    }

    @Test fun genericParserRejectsAmbiguousCaptionMentions() {
        assertNull(TikTokProfileIdentity.handle(listOf("Edit", "Followers", "@owner", "@captionMention")))
        assertEquals("@owner", TikTokProfileIdentity.handle(listOf("Edit", "Followers", "@owner")))
    }
}
