package co.sanaa.agent.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.RuntimeStatusRegistry
import co.sanaa.agent.core.RuntimeStatusSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
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
 * Regression for the workers holding the durable runtime and blocking the
 * status path. The worker body must run on a non-main dispatcher and must
 * not acquire a lock the status path needs. The status path on the main
 * thread must remain wait-free even while a worker is mid-scrub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentWorkersTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AgentRuntime.resetForTest()
        RuntimeStatusRegistry.resetForTest()
        AgentRuntime.get(context, useEncryptedPrefs = false)
    }

    @After
    fun tearDown() {
        RuntimeStatusRegistry.resetForTest()
        AgentRuntime.resetForTest()
    }

    @Test
    fun workerBodyOffMainDispatcher() = runBlocking {
        val runtime = AgentRuntime.get(context, useEncryptedPrefs = false).awaitReady()
        val mainThread = Thread.currentThread()
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bodyThreadHolder = arrayOfNulls<Thread>(1)
        val resultDeferred: Deferred<Boolean> = workerScope.async(Dispatchers.IO) {
            runtime.queue.withExclusiveDeviceAction {
                bodyThreadHolder[0] = Thread.currentThread()
                true
            }
        }
        val result = withTimeout(5_000) { resultDeferred.await() }
        assertTrue(result)
        val bodyThread = bodyThreadHolder[0]
        assertNotNull("body must run on some thread", bodyThread)
        assertFalse(
            "guarded body must NOT run on the main thread; was ${bodyThread!!.name}",
            bodyThread === mainThread,
        )
    }

    @Test
    fun scrubJobDoesNotBlockStatusPath() = runBlocking {
        AgentRuntime.get(context, useEncryptedPrefs = false).awaitReady()
        RuntimeStatusRegistry.replace(
            RuntimeStatusSnapshot(isReady = true, phase = "idle", certificationLevel = 1),
        )
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scrubJob = workerScope.launch(Dispatchers.IO) {
            withTimeout(5_000) {
                AgentRuntime.get(context, useEncryptedPrefs = false).queue
                    .withExclusiveDeviceAction { withContext(Dispatchers.IO) { delay(600) } }
            }
        }
        delay(60)
        val statusReadMs = measureTimeMillis {
            repeat(200) {
                val snap = RuntimeStatusRegistry.snapshot()
                assertTrue("status must remain readable while workers run", snap.isReady)
            }
        }
        assertTrue(
            "200 status reads during an in-flight worker scrub must complete in <100ms; was ${statusReadMs}ms",
            statusReadMs < 100,
        )
        scrubJob.join()
    }

    @Test
    fun multipleConcurrentStatusReadsRemainWaitFree() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val workers = (0 until 4).map {
            workerScope.launch(Dispatchers.IO) {
                withTimeout(5_000) {
                    AgentRuntime.get(context, useEncryptedPrefs = false).queue
                        .withExclusiveDeviceAction { withContext(Dispatchers.IO) { delay(150) } }
                }
            }
        }
        delay(60)
        val ms = measureTimeMillis {
            repeat(500) { RuntimeStatusRegistry.snapshot() }
        }
        assertTrue(
            "500 atomic snapshot reads with workers running must stay under 100ms; was ${ms}ms",
            ms < 100,
        )
        workers.forEach { it.join() }
    }
}
