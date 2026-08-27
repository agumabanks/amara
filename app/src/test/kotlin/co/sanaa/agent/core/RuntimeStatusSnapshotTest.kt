package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

/**
 * Regression for the 2-second Flutter `autonomyStatus` poll ANR on the Oppo.
 * The status path on the main thread must NEVER invoke `AgentRuntime.get`
 * or `AmaraMemory`; it must be satisfied by a wait-free read of an atomic
 * snapshot that a background coroutine populates after
 * `AgentRuntime.get(context).awaitReady()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuntimeStatusSnapshotTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AgentRuntime.resetForTest()
        RuntimeStatusRegistry.resetForTest()
        RuntimeStatusBus.clear("contact-directory")
        // Bootstrap the singleton with the test-friendly prefs path so the
        // background refresher's production `AgentRuntime.get(context)` call
        // returns the already-cached instance instead of trying to construct
        // a new one with encrypted prefs (which Robolectric cannot back).
        AgentRuntime.get(context, useEncryptedPrefs = false)
    }

    @After
    fun tearDown() {
        RuntimeStatusRegistry.resetForTest()
        AgentRuntime.resetForTest()
    }

    @Test
    fun snapshotIsReadableWithoutAwaitingAgentRuntime() {
        val snap = RuntimeStatusRegistry.snapshot()
        assertFalse("fresh snapshot must report not-ready", snap.isReady)
        assertEquals("idle", snap.phase)
        assertEquals(0, snap.certificationLevel)
        assertNotNull(snap.toMap())
    }

    @Test
    fun backgroundRefresherFlipsIsReadyAfterAwaitReady() {
        val refresher = RuntimeStatusRefresher(context.applicationContext)
        refresher.start()
        val deadline = System.currentTimeMillis() + 5_000L
        while (!RuntimeStatusRegistry.snapshot().isReady && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val snap = RuntimeStatusRegistry.snapshot()
        assertTrue(
            "background refresher must flip isReady within 5s; lastError=${RuntimeStatusRegistry.lastRefreshError()}",
            snap.isReady,
        )
        assertTrue("refresh count must be >= 1; was ${RuntimeStatusRegistry.refreshCount()}",
            RuntimeStatusRegistry.refreshCount() >= 1L)
    }

    @Test
    fun snapshotReadIsWaitFreeOnMainThread() {
        val ms = measureTimeMillis {
            repeat(1_000) {
                val snap = RuntimeStatusRegistry.snapshot()
                assertNotNull(snap.toMap())
            }
        }
        assertTrue(
            "1000 atomic snapshot reads must complete in <200ms (Robolectric budget); was ${ms}ms",
            ms < 200,
        )
    }

    @Test
    fun snapshotToMapContainsAllBridgeKeys() {
        val map = RuntimeStatusRegistry.snapshot().toMap()
        for (key in listOf(
            "phase", "active", "blocked", "detail", "targetApp", "taskLabel",
            "stepIndex", "stepCount", "retryCount", "autonomyPhase",
            "certificationLevel", "isReady", "updatedAtMillis",
        )) {
            assertTrue("snapshot map must contain key '$key'", map.containsKey(key))
        }
    }

    @Test
    fun customScopeRefreshesSnapshotDeterministically() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val refresher = RuntimeStatusRefresher(context.applicationContext, scope)
        val snap = withTimeout(5_000) { refresher.refreshOnce() }
        assertTrue("refreshOnce must produce a ready snapshot", snap.isReady)
        RuntimeStatusRegistry.replace(snap)
        assertEquals(snap, RuntimeStatusRegistry.snapshot())
    }

    @Test
    fun resetForTestClearsRegistryState() {
        val refresher = RuntimeStatusRefresher(context.applicationContext)
        refresher.start()
        val deadline = System.currentTimeMillis() + 2_000L
        while (!RuntimeStatusRegistry.snapshot().isReady && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        RuntimeStatusRegistry.resetForTest()
        assertFalse(RuntimeStatusRegistry.snapshot().isReady)
        assertEquals(0L, RuntimeStatusRegistry.refreshCount())
    }

    @Test
    fun autonomousPhaseAndChipDetailFlowThroughSnapshot() {
        val refresher = RuntimeStatusRefresher(context.applicationContext)
        refresher.start()
        val deadline = System.currentTimeMillis() + 5_000L
        while (!RuntimeStatusRegistry.snapshot().isReady && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val snap = RuntimeStatusRegistry.snapshot()
        assertEquals("idle", snap.autonomyPhase)
        assertEquals(snap.phase, snap.autonomyPhase)
    }
}
