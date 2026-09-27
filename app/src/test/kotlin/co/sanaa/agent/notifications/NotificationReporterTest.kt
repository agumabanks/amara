package co.sanaa.agent.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class NotificationReporterTest {
    @Test fun unresolvedDeviceWarningRemainsUntilExplicitlyCancelled() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val manager=context.getSystemService(NotificationManager::class.java)
        val id=40101
        try {
            NotificationReporter(context).report("Battery low", "Connect charger",
                NotificationReporter.Priority.ACTION_NEEDED,notificationId=id,ongoing=true)
            val notification=manager.activeNotifications.single { it.id==id }.notification
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertFalse(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        } finally { manager.cancel(id) }
    }
}
