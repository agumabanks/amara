package co.sanaa.agent.integrations

import co.sanaa.agent.core.ContentHashing

/** Professional domains Amara may connect to under owner-granted, revocable scope (Phase D). */
enum class ConnectorDomain { EMAIL, CALENDAR, CLOUD_FILES, PROJECT_MANAGEMENT, CRM }

data class ConnectorSpec(
    val id: String,
    val domain: ConnectorDomain,
    val declaredScopes: Set<String>,
    val credentialRef: String,
    val maxRequestsPerMinute: Int,
    val timeoutMs: Long,
) {
    init {
        require(id.isNotBlank()) { "Connector id must be non-blank" }
        require(declaredScopes.isNotEmpty()) { "A connector declares at least one scope" }
        require(credentialRef.isNotBlank()) { "A connector names its credential reference" }
        require(maxRequestsPerMinute > 0) { "Rate limit must be positive" }
        require(timeoutMs > 0) { "Connectors always carry a deadline" }
    }

    fun fingerprint(): String = ContentHashing.hash("$id|${declaredScopes.sorted().joinToString(",")}")
}

sealed class ConnectorResult {
    data class Ok(val payloadJson: String) : ConnectorResult()
    data class Denied(val reason: String) : ConnectorResult()
    data class RateLimited(val retryAfterMs: Long) : ConnectorResult()
    data class TimedOut(val deadlineMs: Long) : ConnectorResult()

    /**
     * Typed saturation failure: the shared bounded provider-call service had no free
     * thread or queue slot, so the call was refused before any provider was contacted.
     * Distinct from [TimedOut] so operators can size capacity from real telemetry.
     */
    data class Saturated(val reason: String) : ConnectorResult()
    data class Failed(val reason: String) : ConnectorResult()
}

/**
 * Explicitly owned, bounded execution service for provider calls. One instance is shared
 * by every connector in the process (no per-connector cached executors), so total
 * concurrent provider calls and queued calls are bounded process-wide.
 *
 * Cancellation honesty: a deadline timeout cancels the future (cooperative providers see
 * the interrupt and stop), but a provider that ignores interruption keeps its worker
 * occupied until it returns. The bound therefore converts uncooperative-provider leaks
 * into typed [ConnectorResult.Saturated] refusals instead of unbounded thread growth.
 */
class ConnectorExecutionService(
    activeCalls: Int = DEFAULT_ACTIVE_CALLS,
    queuedCalls: Int = DEFAULT_QUEUED_CALLS,
) {
    private val pool = java.util.concurrent.ThreadPoolExecutor(
        activeCalls, activeCalls, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(queuedCalls),
    ) { runnable -> Thread(runnable, "amara-connector-provider").apply { isDaemon = true } }

    init {
        // Fail fast on misconfiguration; prestart so thread-count assertions are stable.
        require(activeCalls > 0 && queuedCalls >= 0) { "Bounded executor needs positive capacity" }
        repeat(activeCalls) { pool.prestartAllCoreThreads() }
        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
    }

    fun <T> submit(task: java.util.concurrent.Callable<T>): java.util.concurrent.Future<T> = pool.submit(task)

    val queuedCount: Int get() = pool.queue.size

    /** Graceful lifecycle: no new calls accepted; running calls finish or hit their deadline. */
    fun shutdown() {
        pool.shutdown()
        pool.shutdownNow()
    }

    companion object {
        const val DEFAULT_ACTIVE_CALLS = 4
        const val DEFAULT_QUEUED_CALLS = 32

        @Volatile private var shared: ConnectorExecutionService? = null

        /** Process-wide service used by all production connectors unless one is injected. */
        @Synchronized
        fun shared(): ConnectorExecutionService =
            shared ?: ConnectorExecutionService().also { shared = it }
    }
}

/**
 * Least-privilege execution boundary for structured integrations. Every call is checked
 * against the granted scopes; a revoked grant blocks all subsequent calls; rate limits
 * and the spec deadline are enforced before the provider is ever contacted.
 */
interface Connector {
    val spec: ConnectorSpec
    fun call(operation: String, requestJson: String, nowMs: Long): ConnectorResult
}

/**
 * Owner-controlled grant: least privilege by construction — grants may only narrow,
 * never widen, the connector's declared scopes. Revocation is sticky and can be made
 * durable via a [RevocationLedger] so a restart cannot resurrect a revoked grant.
 */
class ConnectorGrant(
    private val connector: Connector,
    grantedScopes: Set<String>,
    private val revocationLedger: RevocationLedger? = null,
) {
    val id: String = connector.spec.id

    val grantedScopes: Set<String> = grantedScopes.also {
        require(it.isNotEmpty()) { "A grant names at least one scope" }
        require(it.all { s -> s in connector.spec.declaredScopes }) { "Grants cannot exceed declared scopes" }
    }

    val isRevoked: Boolean
        get() = revoked || revocationLedger?.isRevoked(id) == true

    @Volatile
    private var revoked = false

    fun revoke() {
        revoked = true
        revocationLedger?.recordRevocation(id)
    }

    /** Restores a grant only if it was never durably revoked (used by re-consent flows). */
    fun reconsent(): Boolean {
        if (revocationLedger?.isRevoked(id) == true) return false
        revoked = false
        return true
    }

    fun call(operation: String, requiredScope: String, requestJson: String, nowMs: Long): ConnectorResult = when {
        isRevoked -> ConnectorResult.Denied("Grant for ${connector.spec.id} was revoked; no further calls are possible.")
        requiredScope !in grantedScopes -> ConnectorResult.Denied("Operation '$operation' needs scope '$requiredScope' which was not granted.")
        else -> connector.call("$operation|$requiredScope", requestJson, nowMs)
    }
}

/**
 * Durable revocation record: revocations must survive process restarts, so the ledger
 * interface is backed by storage in production (AmaraMemory) and memory in tests.
 */
interface RevocationLedger {
    fun recordRevocation(connectorId: String)
    fun isRevoked(connectorId: String): Boolean
}

class InMemoryRevocationLedger : RevocationLedger {
    private val revoked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    override fun recordRevocation(connectorId: String) { revoked += connectorId }
    override fun isRevoked(connectorId: String): Boolean = connectorId in revoked
}

/**
 * Rate-limited, deadline-enforced base for fake and real adapters alike. Windowed
 * counting is thread-safe; the spec deadline is enforced around the provider call so a
 * hanging provider surfaces as [ConnectorResult.TimedOut] instead of blocking forever.
 * Provider-call concurrency runs on the shared bounded [ConnectorExecutionService]:
 * saturation is a typed [ConnectorResult.Saturated], and the caller always returns
 * within its deadline. Cancellation is cooperative — an uncooperative provider keeps its
 * worker occupied (bounded), which the caller observes as later Saturated results, never
 * as unbounded thread growth or a hung caller.
 */
abstract class RateLimitedConnector(
    final override val spec: ConnectorSpec,
    private val executionService: ConnectorExecutionService = ConnectorExecutionService.shared(),
) : Connector {

    private val windowLock = Any()
    private var windowStartMs = 0L
    private var windowCount = 0

    /** Provider call. Implementations may block; the deadline is enforced around this. */
    protected abstract fun perform(operation: String, requestJson: String): String

    final override fun call(operation: String, requestJson: String, nowMs: Long): ConnectorResult {
        synchronized(windowLock) {
            if (nowMs - windowStartMs >= 60_000) { windowStartMs = nowMs; windowCount = 0 }
            if (windowCount >= spec.maxRequestsPerMinute) {
                return ConnectorResult.RateLimited(retryAfterMs = 60_000 - (nowMs - windowStartMs))
            }
            windowCount++
        }
        return try {
            callWithDeadline(operation, requestJson)
        } catch (timeout: ConnectorDeadlineExceeded) {
            ConnectorResult.TimedOut(deadlineMs = spec.timeoutMs)
        } catch (saturated: ExecutorSaturated) {
            ConnectorResult.Saturated(saturated.message ?: "provider-call service saturated")
        } catch (error: Exception) {
            ConnectorResult.Failed(co.sanaa.agent.core.Redactor.redact(error.message ?: "connector failure"))
        }
    }

    private fun callWithDeadline(operation: String, requestJson: String): ConnectorResult {
        // Run the provider call on the shared bounded service and wait at most
        // spec.timeoutMs. The caller ALWAYS returns near the deadline whether or not the
        // provider cooperates with interruption.
        val future = try {
            executionService.submit(java.util.concurrent.Callable { perform(operation, requestJson) })
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            throw ExecutorSaturated("No queue slot for '${spec.id}' within ${ConnectorExecutionService.DEFAULT_QUEUED_CALLS} queued calls")
        }
        try {
            val payload = future.get(spec.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            return ConnectorResult.Ok(payload)
        } catch (timeout: java.util.concurrent.TimeoutException) {
            future.cancel(true) // cooperative stop; uncooperative providers occupy their worker only
            throw ConnectorDeadlineExceeded(spec.timeoutMs)
        } catch (aborted: java.util.concurrent.ExecutionException) {
            throw aborted.cause ?: aborted
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ConnectorDeadlineExceeded(spec.timeoutMs)
        }
    }

    class ConnectorDeadlineExceeded(val timeoutMs: Long) : Exception("Connector deadline of ${timeoutMs}ms exceeded")
    class ExecutorSaturated(message: String) : Exception(message)
}
