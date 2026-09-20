package co.sanaa.agent.core.shorts

import org.junit.Assert.*
import org.junit.Test

class ShortsMediaPolicyTest {
    @Test fun changingChannelAfterInterruptedDispatchCannotAuthorizeReplay() {
        for(state in listOf(co.sanaa.agent.core.SideEffectState.ACTING,
            co.sanaa.agent.core.SideEffectState.VERIFICATION_PENDING,
            co.sanaa.agent.core.SideEffectState.UNCERTAIN, co.sanaa.agent.core.SideEffectState.VERIFIED)) {
            val receipt=co.sanaa.agent.core.SideEffectTransaction("youtube:source-post:@oldchannel",
                co.sanaa.agent.core.CapabilityIds.POST_YOUTUBE_SHORT,"@oldchannel","hash",null,state,1,2,"Interrupted")
            assertEquals(receipt,ShortsMediaPolicy.priorDispatch("source-post",listOf(receipt)))
            assertNull(ShortsMediaPolicy.priorDispatch("different-post",listOf(receipt)))
            assertNull(ShortsMediaPolicy.priorDispatch("source-post",listOf(receipt.copy(state=co.sanaa.agent.core.SideEffectState.FAILED))))
        }
    }
    private val valid = ShortsMediaPolicy.Evidence(true, "1:2", "1:2", "Date stamp UGX 85000",
        "Date stamp UGX 85000", true, true, "@sanaa", "@sanaa", "a".repeat(64))

    @Test fun verifiedFinishedVideoCanUseItsExistingSoundtrack() {
        assertNull(ShortsMediaPolicy.blocker(valid))
    }

    @Test fun silentOriginalAndUnclearedAudioAreHeld() {
        assertNotNull(ShortsMediaPolicy.blocker(valid.copy(soundtrackPresent = false)))
        assertNotNull(ShortsMediaPolicy.blocker(valid.copy(soundtrackCleared = false)))
    }

    @Test fun uncertainSourceWrongAdShopOrAccountCannotCrossPost() {
        for (e in listOf(valid.copy(sourceVerified = false), valid.copy(currentShop = "3:4"),
            valid.copy(observedCaption = "Different ad"), valid.copy(observedChannel = "@another"),
            valid.copy(selectedChannel = ""), valid.copy(mediaSha256 = ""))) {
            assertNotNull(ShortsMediaPolicy.blocker(e))
        }
    }
}
