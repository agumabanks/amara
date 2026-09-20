package co.sanaa.agent.core.work

import co.sanaa.agent.core.CapabilityCatalog
import co.sanaa.agent.core.CapabilityIds
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TikTokTimingContractTest {
    @Test fun failedBackendDnsIsARecoverableNetworkPreparationFailure() {
        assertEquals(FailureClass.TRANSIENT_NETWORK,
            WorkExecutor.classifyPreparationException(java.net.UnknownHostException("cards.sanaa.ug")))
        assertEquals(FailureClass.UNKNOWN,
            WorkExecutor.classifyPreparationException(IllegalStateException("Changed shop")))
    }

    @Test fun storyBudgetIncludesPreparationAndConfirmation() {
        val transaction = CapabilityCatalog.get(CapabilityIds.POST_TIKTOK_STORY)!!.timeoutMs
        assertTrue(transaction >= 225_000L)
        assertTrue(AmaraWorkLoop.itemTimeoutMs(WorkKind.TIKTOK_STORY_PUBLISH) >= transaction + 180_000L)
    }

    @Test fun publicationBudgetIncludesPreparationAndVerification() {
        val transaction = CapabilityCatalog.get(CapabilityIds.POST_TIKTOK)!!.timeoutMs
        assertTrue("Editor and upload each have a 180s ceiling", transaction >= 360_000L)
        assertTrue("Rendering and session refresh need time before publication",
            AmaraWorkLoop.itemTimeoutMs(WorkKind.TIKTOK_POST_PUBLISH) >= transaction + 120_000L)
    }
}
