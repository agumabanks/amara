package co.sanaa.agent.certification

import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.RuntimeStatusRegistry
import co.sanaa.agent.core.RuntimeStatusRefresher
import co.sanaa.agent.core.RuntimeStatusSnapshot
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The status path must never recompute certification metrics on the main
 * thread. The snapshot caches the level after the background refresher
 * samples it; the Flutter poll only reads the cached value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CertificationSnapshotCacheTest {

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
    fun snapshotCachesCertificationLevelAfterBackgroundRefresh() = runBlocking {
        val refresher = RuntimeStatusRefresher(context.applicationContext)
        val snap = withTimeout(5_000) { refresher.refreshOnce() }
        assertTrue("snapshot must be ready after background refresh", snap.isReady)
        assertTrue("cached certificationLevel must be within 0..4; was ${snap.certificationLevel}",
            snap.certificationLevel in 0..4)
        RuntimeStatusRegistry.replace(snap)
        assertEquals(snap.certificationLevel, RuntimeStatusRegistry.snapshot().certificationLevel)
    }

    @Test
    fun preReadySnapshotReportsZeroCertificationWithoutComputing() {
        val preReady = RuntimeStatusSnapshot()
        assertEquals(0, preReady.certificationLevel)
        assertEquals(false, preReady.isReady)
    }

    @Test
    fun snapshotMapExposesCertificationKey() {
        val map = RuntimeStatusSnapshot(certificationLevel = 2, isReady = true).toMap()
        assertEquals(2, map["certificationLevel"])
    }
}
