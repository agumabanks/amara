package co.sanaa.agent.modules

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TikTokCommentInboxTest {
    @Test fun refreshedNotificationDoesNotCreateAnotherReplyCandidate() {
        val context=RuntimeEnvironment.getApplication()
        val notification=android.app.Notification.Builder(context,"comments")
            .setContentTitle("TikTok").setContentText("Alice commented on your video: How much?").build()
        fun posted(at:Long)=android.service.notification.StatusBarNotification(
            "com.zhiliaoapp.musically","com.zhiliaoapp.musically",42,"comment",1000,1,0,
            notification,android.os.Process.myUserHandle(),at)
        TikTokCommentInbox(context).use { store ->
            val now=System.currentTimeMillis()
            assertNotNull(TikTokCommentNotifications.capture(posted(now),store))
            assertNull(TikTokCommentNotifications.capture(posted(now+1000),store))
        }
    }

    @Test fun reservationSurvivesReopeningAndCannotBeRepeated() {
        val context=RuntimeEnvironment.getApplication()
        TikTokCommentInbox(context).use { store ->
            assertTrue(store.add("one",TikTokCommentNotifications.Comment("Alice","How much?"),System.currentTimeMillis()))
            assertTrue(store.reserve("one","Which size would you like?"))
        }
        TikTokCommentInbox(context).use { store ->
            assertFalse(store.reserve("one","Another response"))
            assertEquals("RESERVED",store.get("one")!!.getString("state"))
        }
    }

    @Test fun oldNotificationsRepliedTodayCountAgainstTodaysLimit() {
        val context=RuntimeEnvironment.getApplication()
        val old=System.currentTimeMillis()-2*86400000L
        TikTokCommentInbox(context).use { store ->
            for(i in 0 until 13) store.add("id-$i",TikTokCommentNotifications.Comment("Alice","Question $i?"),old)
            for(i in 0 until 12) assertTrue(store.reserve("id-$i","Which item?"))
        }
        TikTokCommentInbox(context).use { store ->
            assertFalse(store.reserve("id-12","Which item?"))
            assertEquals("NEEDS_REVIEW",store.get("id-12")!!.getString("state"))
            assertFalse(store.reserve("id-0","Repeat"))
            assertEquals("RESERVED",store.get("id-0")!!.getString("state"))
        }
    }
}
