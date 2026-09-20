package co.sanaa.agent.modules
import org.junit.Test
import org.junit.Assert.*
class TikTokCommentNotificationsTest {
    @Test fun ownCommentsParseButLikesAndBundlesDoNot() {
        assertEquals(TikTokCommentNotifications.Comment("alice","How much?"),TikTokCommentNotifications.parse("TikTok","alice commented on your video: How much?"))
        assertEquals("Where are you?",TikTokCommentNotifications.parse("alice","commented: Where are you?")!!.text)
        assertNull(TikTokCommentNotifications.parse("TikTok","alice liked your video"))
        assertNull(TikTokCommentNotifications.parse("TikTok","alice and 4 others commented: hello"))
    }
    @Test fun onlyPotentialQuestionsNeedAReply() {
        assertTrue(TikTokCommentNotifications.worthReview("Can I order in blue?"))
        assertFalse(TikTokCommentNotifications.worthReview("🔥🔥🔥"))
        assertFalse(TikTokCommentNotifications.worthReview("nice"))
    }
}
