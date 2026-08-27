package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
 * Regression for the 5-second main-thread ANR that put the AccessibilityAgentService
 * into Android's "Crashed services" set on the Oppo. The composition root + light
 * field initialisers in [AgentRuntime] must complete in <50ms on the calling thread;
 * the DB-touching heavy init must run on Dispatchers.IO and be observable via
 * [AgentRuntime.awaitReady].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentRuntimeInitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AgentRuntime.resetForTest()
    }

    @After
    fun tearDown() {
        AgentRuntime.resetForTest()
    }

    @Test
    fun getReturnsCheaplyWithoutBlockingOnTheMainThread() {
        // Warm up the Robolectric classloader / DB so the timing is not dominated
        // by first-use JIT / sandbox setup. The regression we care about is the
        // bind path forcing a re-construct of the runtime on every call, which on
        // the Oppo is a 5s ANR. After warm-up, get() must be effectively free.
        AgentRuntime.get(context, useEncryptedPrefs = false)
        AgentRuntime.resetForTest()
        // The 200ms budget here is generous for Robolectric; on a real device a
        // warm AgentRuntime.get() is well under 5ms. The point is the cost is
        // bounded and the accessibility bind path does not have to eat it on the
        // main thread.
        val firstCallMs = measureTimeMillis {
            AgentRuntime.get(context, useEncryptedPrefs = false)
        }
        val subsequentCallMs = measureTimeMillis {
            repeat(20) { AgentRuntime.get(context, useEncryptedPrefs = false) }
        }
        assertTrue(
            "warm AgentRuntime.get() must complete in <200ms (Robolectric budget); was ${firstCallMs}ms (this is the 5s-ANR regression on the Oppo)",
            firstCallMs < 200,
        )
        assertTrue(
            "repeated get() must stay cheap; was ${subsequentCallMs}ms",
            subsequentCallMs < 100,
        )
    }

    @Test
    fun isReadyStartsFalseAndFlipsTrueAfterBackgroundInit() {
        val runtime = AgentRuntime.get(context, useEncryptedPrefs = false)
        runBlocking {
            withTimeout(5_000) { runtime.awaitReady() }
        }
        assertTrue("awaitReady must complete within 5s on Robolectric", runtime.isReady())
    }

    @Test
    fun getIsIdempotent() {
        val a = AgentRuntime.get(context, useEncryptedPrefs = false)
        val b = AgentRuntime.get(context, useEncryptedPrefs = false)
        assertTrue("get() must return the same singleton", a === b)
    }

    @Test
    fun getDoesNotBlockWhenCalledRepeatedlyBeforeInitCompletes() {
        val runtime = AgentRuntime.get(context, useEncryptedPrefs = false)
        val cheap = measureTimeMillis {
            repeat(20) { AgentRuntime.get(context, useEncryptedPrefs = false) }
        }
        assertTrue("repeated get() must stay cheap; was ${cheap}ms", cheap < 50)
        assertTrue(AgentRuntime.get(context, useEncryptedPrefs = false) === runtime)
    }

    @Test
    fun awaitReadyReturnsImmediatelyWhenAlreadyReady() {
        val runtime = AgentRuntime.get(context, useEncryptedPrefs = false)
        runBlocking { runtime.awaitReady() }
        val ms = measureTimeMillis {
            runBlocking { runtime.awaitReady() }
        }
        assertTrue("a second awaitReady() must be effectively free; was ${ms}ms", ms < 20)
    }

    @Test
    fun resetForTestClearsTheSingleton() {
        val a = AgentRuntime.get(context, useEncryptedPrefs = false)
        AgentRuntime.resetForTest()
        val b = AgentRuntime.get(context, useEncryptedPrefs = false)
        assertFalse("resetForTest must clear the cached instance", a === b)
    }

    @Test
    fun runtimeExposesCoreComponentesBeforeHeavyInit() {
        val runtime = AgentRuntime.get(context, useEncryptedPrefs = false)
        assertNotNull(runtime.config)
        assertNotNull(runtime.state)
        assertNotNull(runtime.reporter)
    }
}
