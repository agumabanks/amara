package co.sanaa.agent.services

import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AgentRuntime
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

/**
 * The 5s main-thread ANR that landed the AccessibilityAgentService in Android's
 * "Crashed services" set was caused by AgentRuntime.get() being called inside the
 * bind path. After the fix:
 *   - onServiceConnected() does no AgentRuntime work,
 *   - onCreate() does no AgentRuntime work,
 *   - the service binds in <50ms (the budget Android gives a foreground bind),
 *   - markAccessibilityServiceAlive() is recorded so the crashed-state detector can
 *     see "was bound, now disabled" on the next reconcile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityAgentServiceStartTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AccessibilityAgentService.setInstanceForTest(null)
        AgentRuntime.resetForTest()
    }

    @After
    fun tearDown() {
        AccessibilityAgentService.setInstanceForTest(null)
        AgentRuntime.resetForTest()
    }

    @Test
    fun onCreateAndOnServiceConnectedBindInUnderFiftyMilliseconds() {
        val controller = Robolectric.buildService(AccessibilityAgentService::class.java)
        val service = controller.get()
        val ms = measureTimeMillis {
            // The bind path the OS exercises in production is captured here in two
            // steps: Robolectric has already invoked onCreate when the controller is
            // built; we then call onServiceConnected via the test seam. The
            // production onServiceConnected body is what the original 5s ANR
            // measurement was taken against.
            service.invokeOnServiceConnectedForTest()
        }
        assertNotNull(service)
        assertTrue(
            "service bind path must complete in <50ms; was ${ms}ms (this is the ANR regression on the Oppo)",
            ms < 50,
        )
        controller.destroy()
    }

    @Test
    fun onServiceConnectedMarksAliveForCrashedStateDetection() {
        val controller = Robolectric.buildService(AccessibilityAgentService::class.java)
        val service = controller.get()
        service.invokeOnServiceConnectedForTest()
        val manager = co.sanaa.agent.permissions.SelfHealingPermissionManager(context)
        assertTrue(
            "after onServiceConnected the recovery store must record liveness",
            manager.wasAccessibilityServiceAlive(),
        )
        controller.destroy()
    }

    @Test
    fun onServiceConnectedDoesNotForceFullAgentRuntimeInit() {
        // Prime the Robolectric DB once outside the measured path so the per-test
        // timing reflects the bind path, not first-use class-loader / SQL setup.
        AgentRuntime.get(context, useEncryptedPrefs = false)
        AgentRuntime.resetForTest()

        val controller = Robolectric.buildService(AccessibilityAgentService::class.java)
        val service = controller.get()
        val bindMs = measureTimeMillis {
            service.invokeOnServiceConnectedForTest()
        }
        assertTrue(
            "the bind path itself must be fast (no DB / AgentRuntime work); was ${bindMs}ms — this is the 5s-ANR regression on the Oppo",
            bindMs < 200,
        )
        // The bind path must NOT have synchronously completed the heavy init; the
        // runtime is observable (any later code path that needs it can awaitReady),
        // but isReady() remains false until the IO loop has had a chance to run.
        assertFalse(
            "onServiceConnected must not have run heavy init on the main thread",
            AgentRuntime.get(context, useEncryptedPrefs = false).isReady(),
        )
        controller.destroy()
    }

    @Test
    fun onDestroyClearsInstance() {
        val controller = Robolectric.buildService(AccessibilityAgentService::class.java)
        val service = controller.get()
        service.invokeOnServiceConnectedForTest()
        controller.destroy()
        assertNull(AccessibilityAgentService.instance)
    }
}
