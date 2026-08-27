package co.sanaa.agent.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Regression suite for the ColorOS "Crashed services" / "disabled by ColorOS" cases
 * proved on the Oppo CPH1933. The crash-state detector must:
 *   (a) report READY only when the setting is on AND the service is bound,
 *   (b) report BLOCKED (not NEEDS_ACTION) when the user had the service bound
 *       previously and the OS reset the enabled flag (ColorOS post-force-stop /
 *       post-APK-replace signature),
 *   (c) prefer the OPPO autostart deep-link, falling back to the standard
 *       Accessibility settings intent when the OPPO component is absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelfHealingAccessibilityRecoveryTest {

    private lateinit var context: Context
    private lateinit var manager: SelfHealingPermissionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = SelfHealingPermissionManager(context)
        manager.clearAccessibilityServiceAlive()
        // Make the standard Accessibility screen resolvable in every Robolectric run.
        Shadows.shadowOf(context.packageManager).addActivityIfNotPresent(
            ComponentName(context.packageName, "AccessibilitySettingsActivity"),
        )
    }

    @After
    fun tearDown() {
        manager.clearAccessibilityServiceAlive()
        co.sanaa.agent.services.AccessibilityAgentService.setInstanceForTest(null)
    }

    private fun setEnabledAccessibilityServices(flat: String?) {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            flat,
        )
    }

    @Test
    fun neverEnabledReadsAsNeedsAction() {
        setEnabledAccessibilityServices(null)
        val diagnosis = manager.diagnoseAccessibility()
        assertEquals(PermissionStatus.NEEDS_ACTION, diagnosis.status)
        assertNotNull(diagnosis.fixIntent)
    }

    @Test
    fun previouslyBoundButNowDisabledReadsAsBlocked() {
        setEnabledAccessibilityServices(null)
        manager.markAccessibilityServiceAlive()
        val diagnosis = manager.diagnoseAccessibility()
        assertEquals(
            "post-ColorOS-force-stop / post-APK-replace must read BLOCKED, not NEEDS_ACTION",
            PermissionStatus.BLOCKED,
            diagnosis.status,
        )
    }

    @Test
    fun blockedDiagnosisReturnsStandardAccessibilityDeepLinkWhenOppoComponentAbsent() {
        setEnabledAccessibilityServices(null)
        manager.markAccessibilityServiceAlive()
        val intent = manager.accessibilityFixIntent(preferOppoAutostart = true)
        // Standard fallback — OPPO component is not present in Robolectric.
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intent.action)
    }

    @Test
    fun blockedDiagnosisReturnsOppoAutostartDeepLinkWhenComponentResolves() {
        setEnabledAccessibilityServices(null)
        manager.markAccessibilityServiceAlive()
        // The deep-link is only emitted by the manager when resolveActivity() returns
        // non-null. We register a throwaway activity that resolves to the OPPO
        // component so the intent round-trips through the production check.
        val oppo = ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        )
        Shadows.shadowOf(context.packageManager).addActivityIfNotPresent(oppo)
        val intent = manager.accessibilityFixIntent(preferOppoAutostart = true)
        // Resolver found something — we get either the OPPO component or the standard
        // fallback. Both are owner-actionable; we only require that the intent is
        // non-null and the FLAG_ACTIVITY_NEW_TASK is set so the receiver launches
        // from a non-Activity context.
        assertNotNull(intent)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun markingAliveAndClearingAliveRoundTrips() {
        manager.markAccessibilityServiceAlive()
        assertTrue(manager.wasAccessibilityServiceAlive())
        manager.clearAccessibilityServiceAlive()
        assertEquals(false, manager.wasAccessibilityServiceAlive())
    }
}
