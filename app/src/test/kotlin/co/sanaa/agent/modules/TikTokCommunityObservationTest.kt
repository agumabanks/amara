package co.sanaa.agent.modules

import org.junit.Assert.*
import org.junit.Test

class TikTokCommunityObservationTest {
    @Test fun unreadableFeedIsBlockedRatherThanSuccessfulEmptyDiscovery() {
        assertNotNull(TikTokCommunityObservation.blocker(0, "NO_RELEVANT_POST"))
    }
    @Test fun observedIrrelevantPostsAreLegitimateEmptyDiscovery() {
        assertNull(TikTokCommunityObservation.blocker(3, "NO_RELEVANT_POST"))
    }
    @Test fun priorityYieldIsNotAReadFailure() {
        assertNull(TikTokCommunityObservation.blocker(0, "YIELDED_TO_PRIORITY_WORK"))
    }
}
