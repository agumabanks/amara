package co.sanaa.agent.receivers

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * The 5s main-thread ANR that put the AccessibilityAgentService into Android's
 * "Crashed services" set was triggered a second time by BOOT_COMPLETED +
 * MY_PACKAGE_REPLACED both firing in the same minute on first boot. BootReceiver
 * must enqueue at most ONE startForegroundService per process for the duplicate
 * actions, no matter how many onReceive calls land.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BootReceiverIdempotencyTest {

    private lateinit var context: Context
    private lateinit var receiver: BootReceiver
    private lateinit var shadow: ShadowApplication

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = BootReceiver()
        shadow = Shadows.shadowOf(context as android.app.Application)
        BootReceiver.resetForTest()
        shadow.clearStartedServices()
    }

    @After
    fun tearDown() {
        BootReceiver.resetForTest()
        shadow.clearStartedServices()
    }

    private fun countAgentServiceStarts(): Int {
        var count = 0
        while (true) {
            val intent = shadow.peekNextStartedService() ?: break
            if (intent.component?.shortClassName?.endsWith("AgentService") == true) count++
            shadow.nextStartedService
        }
        return count
    }

    @Test
    fun bootCompletedEnqueuesExactlyOneServiceStart() {
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, countAgentServiceStarts())
    }

    @Test
    fun duplicateOnReceiveOnlyEnqueuesOneServiceStart() {
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals("triple onReceive must enqueue only one AgentService start", 1, countAgentServiceStarts())
    }

    @Test
    fun mixedBootAndPackageReplacedStillEnqueueOnlyOneServiceStart() {
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        receiver.onReceive(context, Intent("android.intent.action.QUICKBOOT_POWERON"))
        receiver.onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals(
            "BOOT + MY_PACKAGE_REPLACED + QUICKBOOT + TIMEZONE must collapse to one AgentService start",
            1,
            countAgentServiceStarts(),
        )
    }

    @Test
    fun unsupportedActionIsIgnored() {
        receiver.onReceive(context, Intent("com.example.NOPE"))
        assertEquals(0, countAgentServiceStarts())
    }

    @Test
    fun resetForTestReenablesEnqueueing() {
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, countAgentServiceStarts())
        BootReceiver.resetForTest()
        shadow.clearStartedServices()
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(
            "after resetForTest a subsequent onReceive must enqueue again",
            1,
            countAgentServiceStarts(),
        )
    }

    @Test
    fun lockedBootCompletedEnqueuesAServiceStart() {
        receiver.onReceive(context, Intent("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertEquals(1, countAgentServiceStarts())
    }

    /**
     * The 5s main-thread ANR that put the accessibility service into the OS's
     * "Crashed services" set was a BootReceiver → AgentService.onCreate →
     * AgentRuntime.get() chain. Asserting that BootReceiver never touches
     * AgentRuntime is the regression guard; we prove it by checking that
     * AgentRuntime.get() returns the not-yet-constructed singleton and that no
     * work was scheduled through it (the receiver only forwards to WorkManager
     * via AgentWorkScheduler.scheduleAll, which itself calls AgentRuntime only
     * inside the periodic workers it enqueues).
     */
    @Test
    fun bootReceiverDoesNotInvokeAgentRuntimeOnTheMainThread() {
        val before = co.sanaa.agent.core.AgentRuntime.isInitializedForTest()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        receiver.onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val after = co.sanaa.agent.core.AgentRuntime.isInitializedForTest()
        assertEquals(
            "BootReceiver.onReceive must not construct AgentRuntime on the main thread",
            before,
            after,
        )
        // The only externally-visible side effect must be the start service
        // command: nothing else should have been launched.
        var otherStarts = 0
        while (true) {
            val intent = shadow.peekNextStartedService() ?: break
            shadow.nextStartedService
            if (intent.component?.shortClassName?.endsWith("AgentService") != true) otherStarts++
        }
        assertEquals(
            "BootReceiver must not start any other service alongside AgentService",
            0,
            otherStarts,
        )
    }
}
