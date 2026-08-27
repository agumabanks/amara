package co.sanaa.agent.integrations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Durable connector revocation against PRODUCTION SQLite (Robolectric): the ledger under
 * test is the production [DurableRevocationLedger] wired through [co.sanaa.agent.core.AgentRuntime],
 * and restarts are real database reopens through fresh [AmaraMemory] instances.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DurableRevocationLedgerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
    }

    private fun spec(id: String) = ConnectorSpec(
        id = id, domain = ConnectorDomain.EMAIL, declaredScopes = setOf("mail.read"),
        credentialRef = "vault://test/$id", maxRequestsPerMinute = 100, timeoutMs = 5_000,
    )

    private fun okConnector(id: String) = object : RateLimitedConnector(spec(id)) {
        override fun perform(operation: String, requestJson: String): String = "{\"ok\":true}"
    }

    @Test fun revocationSurvivesRealDatabaseReopenAndBlocksForeverAfterRestart() {
        val firstMemory = AmaraMemory(context)
        val grant = ConnectorGrant(okConnector("gmail"), setOf("mail.read"), revocationLedger = DurableRevocationLedger(firstMemory))
        assertTrue(grant.call("read", "mail.read", "{}", 0) is ConnectorResult.Ok)

        grant.revoke()
        // Restart: a brand-new AmaraMemory over the same database + a brand-new grant.
        val restartedGrant = ConnectorGrant(
            okConnector("gmail"), setOf("mail.read"),
            revocationLedger = DurableRevocationLedger(AmaraMemory(context)),
        )
        assertTrue("revocation must survive process restart", restartedGrant.isRevoked)
        assertTrue(restartedGrant.call("read", "mail.read", "{}", 10) is ConnectorResult.Denied)
        assertTrue(restartedGrant.call("read", "mail.read", "{}", 999_999_999) is ConnectorResult.Denied)
        assertFalse("re-consent cannot resurrect a durably revoked grant", restartedGrant.reconsent())
        // And a non-revoked connector remains callable after the same reopen dance.
        val untouched = ConnectorGrant(
            okConnector("calendar"), setOf("mail.read"),
            revocationLedger = DurableRevocationLedger(AmaraMemory(context)),
        )
        assertTrue(untouched.call("read", "mail.read", "{}", 1) is ConnectorResult.Ok)
    }

    @Test fun concurrentCallsCannotSlipThroughAfterDurableRevocation() {
        val memory = AmaraMemory(context)
        val grant = ConnectorGrant(okConnector("crm"), setOf("mail.read"), revocationLedger = DurableRevocationLedger(memory))
        assertTrue(grant.call("read", "mail.read", "{}", 0) is ConnectorResult.Ok)

        val sawDenied = AtomicBoolean(false)
        val revokedAndVisible = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(4)
        val start = java.util.concurrent.CountDownLatch(1)
        val callers = (1..8).map {
            pool.submit<Boolean> {
                start.await()
                // Spin until the durable revoke is visible, then every call must be denied.
                val deadline = System.currentTimeMillis() + 10_000
                while (!revokedAndVisible.get() && System.currentTimeMillis() < deadline) Thread.sleep(2)
                var allDeniedAfterRevoke = revokedAndVisible.get()
                repeat(25) { attempt ->
                    when (grant.call("read", "mail.read", "{\"n\":$attempt}", attempt.toLong())) {
                        is ConnectorResult.Denied -> sawDenied.set(true)
                        else -> allDeniedAfterRevoke = false
                    }
                }
                allDeniedAfterRevoke
            }
        }
        start.countDown()
        grant.revoke()                       // durable write happens synchronously inside revoke()
        revokedAndVisible.set(true)          // from here on, every call must be denied
        val results = callers.map { it.get(15, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue("revocation became visible to concurrent callers", sawDenied.get())
        assertTrue(
            "no call after durable revocation returned non-Denied: $results",
            results.all { it },
        )
        // The durable record is visible to a completely fresh process view.
        assertTrue(DurableRevocationLedger(AmaraMemory(context)).isRevoked("crm"))
    }
}
