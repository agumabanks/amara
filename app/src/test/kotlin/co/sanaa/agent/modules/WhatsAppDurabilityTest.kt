package co.sanaa.agent.modules

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WhatsAppDurabilityTest {
    @Test fun bundledNotificationsPreserveEveryDistinctEventAndSender() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notification = Notification.Builder(context, "test").build()
        fun message(text: String, time: Long) = Bundle().apply {
            putCharSequence("text", text); putLong("time", time)
            putParcelable("sender_person", Person.Builder().setName("Customer").build())
        }
        notification.extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "Customer")
            putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            putParcelable(Notification.EXTRA_MESSAGING_PERSON, Person.Builder().setName("Phone owner").build())
            putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(message("How much?", 2), message("Hi", 1), message("Hi", 1)))
        }
        val events = WhatsAppNotificationParser.parseAll(notification)
        assertEquals(listOf("Hi", "How much?"), events.map { it.message })
        assertTrue(events.all { it.target == "Customer" })
        notification.flags = notification.flags or Notification.FLAG_GROUP_SUMMARY
        assertTrue(WhatsAppNotificationParser.parseAll(notification).isEmpty())
    }
    @Test fun messagingPersonIsThePhoneOwnerNotTheInboundSender() {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "Customer")
            putCharSequence(Notification.EXTRA_TEXT, "Hi")
            putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            putParcelable(Notification.EXTRA_MESSAGING_PERSON, Person.Builder().setName("Phone owner").build())
        }
        assertEquals("Customer", WhatsAppNotificationParser.parse(extras)!!.sender)
    }
    @Test fun restartAndRegenerationCannotChangeBoundReply() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("amara_reply_drafts.db")
        WhatsAppReplyStore(context).use { it.bind("event", JSONObject().put("reply", "Original")) }
        WhatsAppReplyStore(context).use {
            assertEquals("Original", it.get("event")!!.getString("reply"))
            assertEquals("Original", it.bind("event", JSONObject().put("reply", "Changed")).getString("reply"))
            assertNull(it.get("different-event"))
        }
    }
}
