package co.sanaa.agent.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AgentService must promote itself to a foreground service within the 5s budget
 * the OS gives foreground starts. The notification channel, importance, and
 * ongoing flag are part of the same contract: a service that calls
 * startForeground with a dismissable or loud notification invites a system ANR
 * ("Foreground service did not call startForeground in time") the moment the
 * user swipes it away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentServiceForegroundTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        co.sanaa.agent.core.AgentRuntime.resetForTest()
    }

    @After
    fun tearDown() {
        co.sanaa.agent.core.AgentRuntime.resetForTest()
    }

    @Test
    fun onCreateCallsStartForegroundWithTheStatusChannelAndOngoingNotification() {
        val controller = Robolectric.buildService(AgentService::class.java)
        val service = controller.create().get()
        try {
            val posted: Notification? = service.lastForegroundNotificationForTest()
            assertNotNull("startForeground must be called from onCreate", posted)
            assertTrue(
                "the foreground notification must be ongoing so the user cannot dismiss it",
                posted!!.flags and Notification.FLAG_ONGOING_EVENT != 0,
            )
            assertEquals(
                "the posted notification must be bound to the agent status channel " +
                    "so the system can suppress it under the right user preferences",
                AgentService.CHANNEL,
                posted.channelId,
            )

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel: NotificationChannel? = nm.notificationChannels
                .firstOrNull { it.id == AgentService.CHANNEL }
            if (channel != null) {
                assertEquals(
                    "channel must use IMPORTANCE_LOW so the persistent chip never plays a sound",
                    NotificationManager.IMPORTANCE_LOW,
                    channel.importance,
                )
            }
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun onStartCommandReturnsStartStickySoTheOsRestartsAfterKill() {
        val controller = Robolectric.buildService(AgentService::class.java)
        val service = controller.get()
        try {
            val sticky = service.onStartCommand(null, 0, 1)
            assertEquals(
                "AgentService must return START_STICKY so the OS recreates it after a process kill",
                Service.START_STICKY,
                sticky,
            )
        } finally {
            controller.destroy()
        }
    }
}
