package co.sanaa.agent.modules

import org.junit.Assert.*
import org.junit.Test
import co.sanaa.agent.core.work.WorkStatus

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
    @Test fun readOnlyAndPriorityYieldAreSkipsRatherThanCompletedInteractions() {
        assertEquals(WorkStatus.SKIPPED,TikTokCommunityObservation.status("NO_RELEVANT_POST",""))
        assertEquals(WorkStatus.SKIPPED,TikTokCommunityObservation.status("YIELDED_TO_PRIORITY_WORK",""))
        assertEquals(WorkStatus.FAILED,TikTokCommunityObservation.status("NO_RELEVANT_POST",
            TikTokCommunityObservation.blocker(0,"NO_RELEVANT_POST")!!))
        assertEquals(WorkStatus.SKIPPED,TikTokCommunityObservation.status("MODEL_DEFERRED",""))
        assertEquals(WorkStatus.DONE,TikTokCommunityObservation.status("VERIFIED",""))
        assertEquals(WorkStatus.FAILED,TikTokCommunityObservation.status("",""))
    }
}
