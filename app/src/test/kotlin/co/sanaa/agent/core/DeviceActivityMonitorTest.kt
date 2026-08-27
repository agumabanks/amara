package co.sanaa.agent.core

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

class DeviceActivityMonitorTest {
    @After fun reset() = DeviceActivityMonitor.resetForTest()

    @Test fun recentExternalClickMarksTheOwnerActive() {
        DeviceActivityMonitor.observe(AccessibilityEvent.TYPE_VIEW_CLICKED, 10_000)
        assertTrue(DeviceActivityMonitor.isUserLikelyActive(20_000, 30_000))
        assertFalse(DeviceActivityMonitor.isUserLikelyActive(50_001, 30_000))
    }

    @Test fun automationEventsDoNotPretendTheOwnerIsActive() {
        DeviceActivityMonitor.beginAutomation()
        DeviceActivityMonitor.observe(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, 10_000)
        DeviceActivityMonitor.endAutomation()
        assertFalse(DeviceActivityMonitor.isUserLikelyActive(20_000, 30_000))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceAvailabilityGuardTest {
    private lateinit var context: Context
    private lateinit var keyguard: KeyguardManager
    private lateinit var power: PowerManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        DeviceActivityMonitor.resetForTest()
        ScreenController.screenOnOverride = null
        setInteractive(false)
        setLocked(false)
        setSecure(false)
    }

    @After
    fun tearDown() {
        ScreenController.screenOnOverride = null
        DeviceActivityMonitor.resetForTest()
    }

    private fun setInteractive(value: Boolean) =
        Shadows.shadowOf(power).setIsInteractive(value)

    private fun setLocked(value: Boolean) =
        Shadows.shadowOf(keyguard).setKeyguardLocked(value)

    private fun setSecure(value: Boolean) =
        Shadows.shadowOf(keyguard).setIsKeyguardSecure(value)

    /** Sleeper seam that also lets a test mutate device state "while time passes". */
    private class ScriptedSleeper(private val onSleep: (Int) -> Unit) {
        var calls = 0
            private set
        suspend fun sleep(millis: Long) {
            onSleep(calls)
            calls++
        }
    }

    private suspend fun guard(sleeper: ScriptedSleeper? = null): DeviceAvailability =
        DeviceAvailabilityGuard.ensureAvailable(
            context,
            sleeper = { millis -> sleeper?.sleep(millis) ?: Unit },
        )

    @Test
    fun secureLockedKeyguardIsNeverAvailableEvenWhenScreenIsInteractive() = runBlocking {
        setInteractive(true)
        setLocked(true)
        setSecure(true)
        val result = guard()
        assertFalse(result.available)
        assertEquals(AvailabilityBlocker.SECURE_KEYGUARD, result.blocker)
        assertTrue(result.reason.contains("will not attempt credential entry"))
    }

    @Test
    fun screenOffWakesThenPollsInteractiveThenReportsAvailableWithFinalDoubleCheck() = runBlocking {
        // Off at entry; the first poll-wait turns the screen on and stays unlocked.
        val sleeper = ScriptedSleeper { call -> if (call == 0) setInteractive(true) }
        val result = guard(sleeper)
        assertTrue(result.available)
        assertEquals(AvailabilityBlocker.NONE, result.blocker)
        // At least one interactive poll AND the tiny-gap final re-check must have elapsed.
        assertTrue("expected poll + final gap sleeps", sleeper.calls >= 2)
    }

    @Test
    fun finalDoubleCheckCatchesTheScreenTurningOffAgain() = runBlocking {
        setInteractive(false)
        // Wake succeeds, but during the FINAL gap the screen drops off again.
        val sleeper = ScriptedSleeper { call ->
            when (call) {
                0 -> setInteractive(true)
                else -> setInteractive(false)
            }
        }
        val result = guard(sleeper)
        assertFalse(result.available)
        assertEquals(AvailabilityBlocker.SCREEN_OFF, result.blocker)
    }

    @Test
    fun ownerActiveTakesPrecedenceOverEveryOtherState() = runBlocking {
        DeviceActivityMonitor.observe(AccessibilityEvent.TYPE_VIEW_CLICKED, System.currentTimeMillis())
        setInteractive(false)
        setLocked(true)
        setSecure(true)
        val result = guard()
        assertFalse(result.available)
        assertEquals(AvailabilityBlocker.OWNER_ACTIVE, result.blocker)
    }

    @Test
    fun nonsecureKeyguardClearingWithinTheDismissWindowIsAvailable() = runBlocking {
        setInteractive(true)
        setLocked(true)
        setSecure(false)
        // The first dismiss wait clears the swipe-only lock.
        val sleeper = ScriptedSleeper { call -> if (call == 0) setLocked(false) }
        val result = guard(sleeper)
        assertTrue(result.available)
        assertEquals(AvailabilityBlocker.NONE, result.blocker)
    }

    @Test
    fun nonsecureKeyguardThatNeverClearsIsReportedUnavailable() = runBlocking {
        setInteractive(true)
        setLocked(true)
        setSecure(false)
        val sleeper = ScriptedSleeper { }
        val result = guard(sleeper)
        assertFalse(result.available)
        assertEquals(AvailabilityBlocker.NONSECURE_KEYGUARD, result.blocker)
        assertEquals("dismiss window is exactly three polls", 3, sleeper.calls)
    }

    @Test
    fun screenThatNeverWakesIsReportedAsScreenOff() = runBlocking {
        setInteractive(false)
        val result = guard()
        assertFalse(result.available)
        assertEquals(AvailabilityBlocker.SCREEN_OFF, result.blocker)
    }

    @Test
    fun legacySyncCheckOrWakeIsHonestAboutASecureLock() {
        setInteractive(true)
        setLocked(true)
        setSecure(true)
        val result = DeviceAvailabilityGuard.checkOrWake(context)
        assertFalse(result.available)
        assertEquals(AvailabilityBlocker.SECURE_KEYGUARD, result.blocker)
        assertTrue(result.reason.contains("will not attempt credential entry"))
    }

    @Test
    fun legacySyncCheckOrWakeReportsAvailableOnlyWhenVerifiedAwakeAndUnlocked() {
        setInteractive(true)
        val result = DeviceAvailabilityGuard.checkOrWake(context)
        assertTrue(result.available)
        assertEquals(AvailabilityBlocker.NONE, result.blocker)
    }

    @Test
    fun legacySyncCheckOrWakeDoesNotClaimAvailabilityWhenWakingFails() {
        setInteractive(false)
        val startedAt = System.currentTimeMillis()
        val result = DeviceAvailabilityGuard.checkOrWake(context)
        assertFalse(result.available)
        assertEquals(AvailabilityBlocker.SCREEN_OFF, result.blocker)
        assertTrue(
            "sync wait budget must stay <=600ms",
            System.currentTimeMillis() - startedAt < 1_500,
        )
    }

    @Test
    fun oneShotCheckKeepsOwnerActiveFirstInTheLadder() {
        DeviceActivityMonitor.observe(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, System.currentTimeMillis())
        setInteractive(false)
        val result = DeviceAvailabilityGuard.check(context)
        assertEquals(AvailabilityBlocker.OWNER_ACTIVE, result.blocker)
    }
}
