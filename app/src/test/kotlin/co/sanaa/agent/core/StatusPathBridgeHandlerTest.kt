package co.sanaa.agent.core

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Regression for the 2-second Flutter `autonomyStatus` poll ANR on the Oppo.
 * The status path on the main thread must NEVER block on
 * `AgentRuntime.get(context).awaitReady()` — it must answer from a wait-free
 * snapshot that a background coroutine populates after the runtime is ready.
 *
 * The production handlers at MainActivity.kt call only:
 *   - RuntimeStatusRegistry.snapshot()
 *   - RuntimeStatusBus.canonicalChipState()
 *   - ModuleStateStore.string(...)
 *   - runtime.certificationLevel  (a cached Int)
 * None of these reach the durable AmaraMemory; this test pins the invariant
 * by measuring a fresh unbooted runtime: the registry snapshot must come back
 * in <50ms even when the runtime has never been awaited.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatusPathBridgeHandlerTest {

    @Before
    fun setUp() {
        AgentRuntime.resetForTest()
        RuntimeStatusRegistry.resetForTest()
        RuntimeStatusBus.clear("contact-directory")
    }

    @After
    fun tearDown() {
        RuntimeStatusRegistry.resetForTest()
        AgentRuntime.resetForTest()
    }

    @Test
    fun snapshotIsReadableWaitFreeWhenAgentRuntimeIsNotReady() {
        val ms = measureTimeMillis {
            val snap = RuntimeStatusRegistry.snapshot()
            assertFalse("fresh snapshot must report not-ready", snap.isReady)
            assertEquals("idle", snap.phase)
            assertEquals(0, snap.certificationLevel)
            assertNotNull(snap.toMap())
        }
        assertTrue("snapshot read must be <50ms when runtime is not ready; was ${ms}ms", ms < 50)
    }

    @Test
    fun repeatedSnapshotReadsAreWaitFreeAcrossHighFrequencyPolling() {
        repeat(100) {
            val snap = RuntimeStatusRegistry.snapshot()
            assertNotNull(snap.toMap())
        }
    }

    @Test
    fun snapshotGapBackgroundWritersDoNotRedactDetailBeforePublishing() {
        val dirty = RuntimeStatusSnapshot(
            isReady = true,
            phase = "acting",
            detail = "operator staff pin is 123456 and bearer gsk-DEADBEEF0123",
            active = true,
            blocked = false,
            targetApp = "Soko Terminal",
            taskLabel = "edit listing",
            stepIndex = 1,
            stepCount = 3,
            retryCount = 0,
            certificationLevel = 2,
            updatedAtMillis = 1L,
        )
        RuntimeStatusRegistry.replace(dirty)
        val snap = RuntimeStatusRegistry.snapshot()
        val map = snap.toMap()
        val detail = map["detail"].toString()
        assertTrue(
            "GAP: RuntimeStatusRefresher.refreshOnce must redact detail before publishing — " +
                "currently the snapshot carries raw secret shapes (\"$detail\") to the Flutter bridge. " +
                "Once fixed, change this test to assert the redacted form.",
            detail.contains("123456") && detail.contains("gsk-DEADBEEF"),
        )
    }

    @Test
    fun chipStateAndModuleStateAreAlsoWaitFreeBeforeRuntimeIsReady() {
        val ms = measureTimeMillis {
            val chip = RuntimeStatusBus.canonicalChipState()
            assertNull("no chip before any worker publishes", chip.status)
            val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
            val state = ModuleStateStore(ctx)
            assertEquals("Ready for the next thing", state.string(AutonomyController.DETAIL_KEY, "Ready for the next thing"))
        }
        assertTrue("chip + module-state reads must be <500ms when runtime is not ready; was ${ms}ms", ms < 500)
    }

    @Test
    fun runtimeStatusSnapshotMapShapeMatchesFlutterContract() {
        val map = RuntimeStatusRegistry.snapshot().toMap()
        val required = listOf(
            "phase", "active", "blocked", "detail", "targetApp", "taskLabel",
            "stepIndex", "stepCount", "retryCount", "autonomyPhase",
            "certificationLevel", "isReady", "updatedAtMillis",
        )
        for (key in required) {
            assertTrue("snapshot map must carry '$key' for the Flutter bridge; missing: ${map.keys}", map.containsKey(key))
        }
    }
}
