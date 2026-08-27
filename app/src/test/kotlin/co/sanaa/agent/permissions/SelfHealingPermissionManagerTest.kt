package co.sanaa.agent.permissions

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device-run regression (2026-08-24): the notification-access diagnosis compared the
 * enabled-listener set (full "pkg/cls" component flattenings) against the bare package
 * name, so a CORRECTLY granted listener was reported as needing action on the Oppo.
 * These tests pin the component-prefix matching against the real settings string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelfHealingPermissionManagerTest {

    private lateinit var context: Context
    private lateinit var manager: SelfHealingPermissionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = SelfHealingPermissionManager(context)
    }

    private fun setListeners(flat: String) {
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            flat,
        )
    }

    @Test fun grantedListenerWithFullComponentReadsAsActive() {
        setListeners(
            "com.google.android.projection.gearhead/com.google.android.gearhead.notifications.SharedNotificationListenerManager\$ListenerService:" +
                "co.sanaa.agent/co.sanaa.agent.services.AgentNotificationListenerService",
        )
        val diagnosis = manager.diagnoseNotificationAccess()
        assertEquals(PermissionStatus.READY, diagnosis.status)
    }

    @Test fun absentListenerReadsAsNeedsAction() {
        setListeners("com.other.vendor/com.other.VendorListener")
        val diagnosis = manager.diagnoseNotificationAccess()
        assertEquals(PermissionStatus.NEEDS_ACTION, diagnosis.status)
    }

    @Test fun similarlyNamedPackagesDoNotCount() {
        setListeners("co.sanaa.agent.evil/co.sanaa.agent.evil.FakeListener")
        val diagnosis = manager.diagnoseNotificationAccess()
        assertEquals(PermissionStatus.NEEDS_ACTION, diagnosis.status)
    }

    @Test fun missingSettingReadsAsNeedsAction() {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
        val diagnosis = manager.diagnoseNotificationAccess()
        assertEquals(PermissionStatus.NEEDS_ACTION, diagnosis.status)
    }
}
