package co.sanaa.agent.core.social

import org.junit.Assert.*
import org.junit.Test

class TikTokProfileIdentityTest {
    @Test fun currentProfileLabelsIdentifyOwnAccountWithoutResourceIds() {
        assertEquals("@sanaamedia",TikTokProfileIdentity.handle(listOf("Sanaa media","Edit","@sanaamedia","Following","Followers","Likes")))
    }
    @Test fun visitorProfileAndAmbiguousHandlesAreRejected() {
        assertNull(TikTokProfileIdentity.handle(listOf("@other","Follow","Followers")))
        assertNull(TikTokProfileIdentity.handle(listOf("@one","@two","Edit","Followers")))
        assertNull(TikTokProfileIdentity.handle(listOf("@one","Edit")))
    }
}
