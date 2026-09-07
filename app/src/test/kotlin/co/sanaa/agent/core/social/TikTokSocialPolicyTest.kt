package co.sanaa.agent.core.social
import org.junit.Assert.*
import org.junit.Test
class TikTokSocialPolicyTest {
    @Test fun commentsNeedGroundedEvidenceAndCannotSolicitFollowsOrRepeat() {
        val post="A guide to choosing paper for business cards"
        assertTrue(TikTokSocialPolicy.validResponse("Which paper weight works best here?","choosing paper",post,emptyList()))
        assertFalse(TikTokSocialPolicy.validResponse("Follow me for more!","choosing paper",post,emptyList()))
        assertFalse(TikTokSocialPolicy.validResponse("Looks good","invented quote",post,emptyList()))
        assertFalse(TikTokSocialPolicy.validResponse("Looks good","choosing paper",post,listOf("LOOKS GOOD")))
        assertFalse(TikTokSocialPolicy.validResponse("Shop https://soko24.co","choosing paper",post,emptyList()))
    }
    @Test fun unknownMetricsAreNotZero() {
        assertNull(TikTokSocialPolicy.parseCount("Followers"))
        assertEquals(2438L,TikTokSocialPolicy.parseCount("2,438"))
        assertEquals(1200L,TikTokSocialPolicy.parseCount("1.2K"))
        assertEquals(0L,TikTokSocialPolicy.parseCount("0"))
    }
}
