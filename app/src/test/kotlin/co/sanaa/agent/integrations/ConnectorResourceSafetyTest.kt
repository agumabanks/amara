package co.sanaa.agent.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resource-safety contract of the shared bounded connector execution service:
 * repeated timeouts, providers that ignore interruption, typed saturation, cooperative
 * cancellation, and the absence of unbounded thread growth.
 */
class ConnectorResourceSafetyTest {

    private fun spec(id: String, timeoutMs: Long = 120) = ConnectorSpec(
        id = id, domain = ConnectorDomain.EMAIL, declaredScopes = setOf("mail.read"),
        credentialRef = "vault://t/$id", maxRequestsPerMinute = 10_000, timeoutMs = timeoutMs,
    )

    private fun service(active: Int = 2, queued: Int = 4) = ConnectorExecutionService(activeCalls = active, queuedCalls = queued)

    @Test fun callerReturnsNearTheDeadlineEvenWhenTheProviderIgnoresInterruption() {
        val svc = service(active = 1, queued = 8)
        val ignoreInterrupt = object : RateLimitedConnector(spec("deaf"), svc) {
            override fun perform(operation: String, requestJson: String): String {
                // Deliberately swallows interruption and keeps working past the deadline.
                val deadline = System.currentTimeMillis() + 1_500
                while (System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(5) } catch (_: InterruptedException) { /* ignores */ }
                }
                return "{}"
            }
        }
        val grant = ConnectorGrant(ignoreInterrupt, setOf("mail.read"))
        repeat(3) { attempt ->
            val started = System.currentTimeMillis()
            val result = grant.call("read", "mail.read", "{\"n\":$attempt}", 0)
            val elapsed = System.currentTimeMillis() - started
            assertTrue(result is ConnectorResult.TimedOut)
            assertTrue(
                "caller must return near its deadline even against an uncooperative provider (took ${elapsed}ms)",
                elapsed < 900,
            )
        }
        svc.shutdown()
    }

    @Test fun saturationIsATypedFailureAndNeverBlocksTheCaller() {
        val svc = service(active = 1, queued = 2)
        val gate = CountDownLatch(1)
        var hanging: RateLimitedConnector? = null
        hanging = object : RateLimitedConnector(spec("hog", timeoutMs = 30_000), svc) {
            override fun perform(operation: String, requestJson: String): String {
                gate.await()
                return "{}"
            }
        }
        val grant = ConnectorGrant(hanging!!, setOf("mail.read"))
        val pool = Executors.newFixedThreadPool(6)
        // One call occupies the single worker; two more fill the queue; further calls saturate.
        pool.submit { grant.call("read", "mail.read", "{}", 0) }
        Thread.sleep(150) // let it take the worker
        val queuedResults = (1..2).map {
            pool.submit<ConnectorResult> { grant.call("read", "mail.read", "{}", 0) }
        }
        Thread.sleep(250)
        assertEquals("queue slots are parked behind the live call", 0, queuedResults.count { it.isDone })
        val saturated = (1..4).map {
            pool.submit<ConnectorResult> { grant.call("read", "mail.read", "{}", 0) }
        }
        val saturatedOutcomes = saturated.map { it.get(5, TimeUnit.SECONDS) }
        assertEquals("overflow must be typed Saturated", 4, saturatedOutcomes.count { it is ConnectorResult.Saturated })
        assertFalse(saturatedOutcomes.any { it is ConnectorResult.TimedOut })
        gate.countDown()
        pool.shutdown()
        svc.shutdown()
    }

    @Test fun repeatedTimeoutsDoNotGrowThreadsUnboundedly() {
        val svc = service(active = 2, queued = 16)
        val slow = object : RateLimitedConnector(spec("slowpoke", timeoutMs = 60), svc) {
            override fun perform(operation: String, requestJson: String): String {
                Thread.sleep(400) // always overruns its 60ms deadline
                return "{}"
            }
        }
        val grant = ConnectorGrant(slow, setOf("mail.read"))
        fun amaraThreads(): Int = Thread.getAllStackTraces().keys.count { it.name.startsWith("amara-connector-provider") }
        val before = amaraThreads()
        repeat(12) { attempt ->
            val result = grant.call("read", "mail.read", "{\"n\":$attempt}", 0)
            assertTrue(result is ConnectorResult.TimedOut || result is ConnectorResult.Saturated)
            assertTrue("thread count must stay within the bound", amaraThreads() <= before + 2)
        }
        // Fixed-size ownership: repeated timeouts never mint replacement/extra threads.
        assertTrue(amaraThreads() <= before + 2)
        svc.shutdown()
    }

    @Test fun cooperativeProvidersObserveCancellationAtTheDeadline() {
        val svc = service(active = 1, queued = 4)
        val interrupted = AtomicInteger(0)
        val polite = object : RateLimitedConnector(spec("polite", timeoutMs = 80), svc) {
            override fun perform(operation: String, requestJson: String): String {
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { interrupted.incrementAndGet() }
                return "{}"
            }
        }
        val grant = ConnectorGrant(polite, setOf("mail.read"))
        val result = grant.call("read", "mail.read", "{}", 0)
        assertTrue(result is ConnectorResult.TimedOut)
        // future.cancel(true) reaches cooperative providers as an interrupt; allow a beat.
        val deadline = System.currentTimeMillis() + 2_000
        while (interrupted.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(interrupted.get() >= 1)
        svc.shutdown()
    }
}
