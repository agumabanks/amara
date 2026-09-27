package co.sanaa.agent.core.social

import org.junit.Assert.*
import org.junit.Test

class TikTokCommentFallbackTest {
    @Test fun explicitPackagingContextProducesValidBoundQuestion() {
        val caption="A Kampala shop compares product labels and packaging finishes for a new retail range."
        val decision=TikTokCommentFallback.decide(caption)
        assertNotNull(decision)
        assertTrue(caption.contains(decision!!.evidence))
        assertTrue(TikTokSocialPolicy.validResponse(decision.response,decision.evidence,caption,emptyList()))
    }

    @Test fun unrelatedOrSensitiveContextNeverProducesComment() {
        assertNull(TikTokCommentFallback.decide("A travel clip showing the evening skyline and a quiet walk through town."))
        assertNull(TikTokCommentFallback.decide("Investment and money advice for a small business using new packaging."))
    }

    @Test fun explicitRetailListingProducesNeutralBuyerQuestion() {
        val caption="Cordless drills, grinders and welders are in stock. Available at our Kampala shop."
        val decision=TikTokCommentFallback.decide(caption)
        assertNotNull(decision)
        assertEquals("Which option gets the most questions from buyers?",decision!!.response)
        assertTrue(TikTokSocialPolicy.validResponse(decision.response,decision.evidence,caption,emptyList()))
    }

    @Test fun explicitShoppingLocationProducesNeutralQuestion() {
        val caption="Find us next to Ham shopping ground in Kampala for the new collection."
        val decision=TikTokCommentFallback.decide(caption)
        assertNotNull(decision)
        assertEquals("What do buyers usually ask about first?",decision!!.response)
        assertTrue(TikTokSocialPolicy.validResponse(decision.response,decision.evidence,caption,emptyList()))
    }

    @Test fun repeatedResponseStillFailsOrdinaryPolicy() {
        val caption="Graphic design choices for a clear brand identity in Kampala."
        val decision=TikTokCommentFallback.decide(caption)!!
        assertFalse(TikTokSocialPolicy.validResponse(
            decision.response,decision.evidence,caption,listOf(decision.response),
        ))
    }
}
