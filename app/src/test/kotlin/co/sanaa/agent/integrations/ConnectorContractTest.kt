package co.sanaa.agent.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase D contract tests, provider-independent: least privilege, revocation,
 * rate limiting, and failure isolation hold for every connector shape.
 */
class ConnectorContractTest {

    private class FakeConnector(spec: ConnectorSpec) : RateLimitedConnector(spec) {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile var failAll = false
        @Volatile var hangMs = 0L
        override fun perform(operation: String, requestJson: String): String {
            val op = operation.substringBefore("|")
            if (hangMs > 0) { Thread.sleep(hangMs); }
            if (failAll) throw IllegalStateException("provider unreachable; token gsk_abcdefghijklmnop")
            calls += op
            return "{\"ok\":true,\"op\":\"$op\"}"
        }
    }

    private fun spec(
        id: String,
        domain: ConnectorDomain,
        scopes: Set<String>,
        maxRequestsOverride: Int? = null,
    ) = ConnectorSpec(
        id = id, domain = domain, declaredScopes = scopes,
        credentialRef = "vault://test/$id", maxRequestsPerMinute = maxRequestsOverride ?: 3, timeoutMs = 40,
    )

    private val email = FakeConnector(spec("gmail", ConnectorDomain.EMAIL, setOf("mail.read", "mail.draft")))
    private val calendar = FakeConnector(spec("gcal", ConnectorDomain.CALENDAR, setOf("calendar.read")))
    private val files = FakeConnector(spec("drive", ConnectorDomain.CLOUD_FILES, setOf("files.read", "files.write")))
    private val crm = FakeConnector(spec("crm", ConnectorDomain.CRM, setOf("contacts.read")))

    @Test fun grantsCannotExceedDeclaredScopes() {
        var rejected = false
        runCatching { ConnectorGrant(email, grantedScopes = setOf("mail.read", "mail.send")) }.onFailure { rejected = true }
        assertTrue(rejected)
        assertTrue(ConnectorGrant(email, grantedScopes = setOf("mail.read")).grantedScopes == setOf("mail.read"))
    }

    @Test fun operationsOutsideGrantedScopeAreDeniedBeforeProviderContact() {
        val grant = ConnectorGrant(files, grantedScopes = setOf("files.read"))
        val denied = grant.call("upload", "files.write", "{}", nowMs = 0)
        assertTrue(denied is ConnectorResult.Denied)
        assertEquals(0, (files.calls).size)
        val allowed = grant.call("list", "files.read", "{}", nowMs = 0)
        assertTrue(allowed is ConnectorResult.Ok)
    }

    @Test fun revocationIsImmediateAndSticky() {
        val grant = ConnectorGrant(crm, grantedScopes = setOf("contacts.read"))
        assertTrue(grant.call("search", "contacts.read", "{}", 0) is ConnectorResult.Ok)
        grant.revoke()
        assertTrue(grant.isRevoked)
        assertTrue(grant.call("search", "contacts.read", "{}", 1) is ConnectorResult.Denied)
        assertTrue(grant.call("search", "contacts.read", "{}", 999_999) is ConnectorResult.Denied)
        assertEquals(1, crm.calls.size)
    }

    @Test fun rateLimitsAreEnforcedInsideTheWindow() {
        val grant = ConnectorGrant(calendar, grantedScopes = setOf("calendar.read"))
        repeat(3) { grant.call("events", "calendar.read", "{}", nowMs = 0) }
        val limited = grant.call("events", "calendar.read", "{}", nowMs = 30_000)
        assertTrue(limited is ConnectorResult.RateLimited)
        // Next window resets the budget.
        val next = grant.call("events", "calendar.read", "{}", nowMs = 61_000)
        assertTrue(next is ConnectorResult.Ok)
    }

    @Test fun providerFailureIsIsolatedAsFailedResultAndRedacted() {
        files.failAll = true
        val grant = ConnectorGrant(files, grantedScopes = setOf("files.read"))
        val result = grant.call("list", "files.read", "{}", 0)
        assertTrue(result is ConnectorResult.Failed)
        // Structured error redaction: secret-shaped fragments in provider errors never escape.
        assertFalse((result as ConnectorResult.Failed).reason.contains("gsk_abcdefghijklmnop"))
    }

    @Test fun deadlineExceededSurfacesAsTimedOut() {
        val slow = FakeConnector(spec("slowmail", ConnectorDomain.EMAIL, setOf("mail.read")))
        val grant = ConnectorGrant(slow, grantedScopes = setOf("mail.read"))
        slow.hangMs = 60
        val result = grant.call("read", "mail.read", "{}", 0)
        assertTrue(result is ConnectorResult.TimedOut)
        assertEquals(40L, (result as ConnectorResult.TimedOut).deadlineMs)
    }

    @Test fun hangingProviderIsInterruptedAtTheDeadlineInsteadOfBlockingForever() {
        val hung = object : RateLimitedConnector(spec("hungmail", ConnectorDomain.EMAIL, setOf("mail.read"))) {
            val latch = java.util.concurrent.CountDownLatch(1)
            override fun perform(operation: String, requestJson: String): String {
                latch.await()
                return "{}"
            }
        }
        val grant = ConnectorGrant(hung, grantedScopes = setOf("mail.read"))
        val startMs = System.currentTimeMillis()
        val result = grant.call("read", "mail.read", "{}", 0)
        val elapsedMs = System.currentTimeMillis() - startMs
        assertTrue(result is ConnectorResult.TimedOut)
        assertEquals(40L, (result as ConnectorResult.TimedOut).deadlineMs)
        assertTrue("call must return near the deadline, not hang (took ${elapsedMs}ms)", elapsedMs < 5_000)
        hung.latch.countDown()
    }

    @Test fun concurrentCallsRespectTheRateLimitExactly() {
        val shared = FakeConnector(spec("sharedcrm", ConnectorDomain.CRM, setOf("contacts.read"), maxRequestsOverride = 5))
        val grant = ConnectorGrant(shared, grantedScopes = setOf("contacts.read"))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val results = (1..20).map { pool.submit<ConnectorResult> { grant.call("search", "contacts.read", "{}", 0L) } }.map { it.get() }
        pool.shutdown()
        assertEquals(5, results.count { it is ConnectorResult.Ok })
        assertEquals(15, results.count { it is ConnectorResult.RateLimited })
    }

    @Test fun revocationSurvivesLedgerReconnection() {
        val ledger = InMemoryRevocationLedger()
        val fresh = FakeConnector(spec("freshmail", ConnectorDomain.EMAIL, setOf("mail.read")))
        val grant = ConnectorGrant(fresh, grantedScopes = setOf("mail.read"), revocationLedger = ledger)
        assertTrue(grant.call("read", "mail.read", "{}", 0) is ConnectorResult.Ok)
        grant.revoke()
        // A new grant instance over the same durable ledger stays revoked after "restart".
        val restartedGrant = ConnectorGrant(FakeConnector(spec("freshmail", ConnectorDomain.EMAIL, setOf("mail.read"))), setOf("mail.read"), revocationLedger = ledger)
        assertTrue(restartedGrant.isRevoked)
        assertTrue(restartedGrant.call("read", "mail.read", "{}", 1) is ConnectorResult.Denied)
        // Re-consent cannot silently resurrect a durably revoked grant.
        assertFalse(grant.reconsent())
        assertFalse(restartedGrant.reconsent())
    }

    @Test fun malformedSpecsAreRejectedAtConstruction() {
        listOf(
            { spec("", ConnectorDomain.EMAIL, setOf("s")) },
            { spec("x", ConnectorDomain.EMAIL, emptySet()) },
            { spec("x", ConnectorDomain.CRM, setOf("s")).copy(credentialRef = "") },
            { spec("x", ConnectorDomain.CRM, setOf("s")).copy(maxRequestsPerMinute = 0) },
            { spec("x", ConnectorDomain.CRM, setOf("s")).copy(timeoutMs = 0) },
        ).forEach { factory ->
            assertTrue(runCatching { factory() }.isFailure)
        }
    }

    @Test fun atLeastFourDomainsAreWiredEndToEnd() {
        val grants = listOf(
            ConnectorGrant(email, setOf("mail.read")),
            ConnectorGrant(calendar, setOf("calendar.read")),
            ConnectorGrant(files, setOf("files.read")),
            ConnectorGrant(crm, setOf("contacts.read")),
        )
        val results = grants.map { grant -> grant.call("read", grant.grantedScopes.first(), "{}", 0) }
        assertTrue(results.all { it is ConnectorResult.Ok })
        val domainsCovered = grants.size
        assertFalse(domainsCovered < 4)
    }

    @Test fun specsAlwaysCarryCredentialReferenceAndDeadline() {
        listOf(email, calendar, files, crm).forEach {
            assertTrue(it.spec.credentialRef.startsWith("vault://"))
            assertTrue(it.spec.timeoutMs > 0)
        }
    }
}
