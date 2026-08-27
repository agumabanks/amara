package co.sanaa.agent.core

import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable, wait-free read of the runtime's status surface that the Flutter
 * `autonomyStatus` 2-second poll and the overlay chip render from. The
 * snapshot is the ONLY object the status handlers read; AgentRuntime /
 * AmaraMemory are touched exclusively by the background [RuntimeStatusRefresher]
 * loop, which calls `AgentRuntime.get(context).awaitReady()` once and then
 * samples the underlying state.
 *
 * Thread safety: the snapshot is held in an [AtomicReference]; reads are
 * wait-free and never block, so a 2s poll on the main thread is bounded by
 * the cost of a volatile read plus a shallow [toMap] copy.
 */
data class RuntimeStatusSnapshot(
    val isReady: Boolean = false,
    val phase: String = "idle",
    val detail: String = "Ready for the next thing",
    val active: Boolean = false,
    val blocked: Boolean = false,
    val targetApp: String = "",
    val taskLabel: String = "",
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val retryCount: Int = 0,
    val autonomyPhase: String = "idle",
    val certificationLevel: Int = 0,
    val updatedAtMillis: Long = 0L,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "phase" to phase,
        "active" to active,
        "blocked" to blocked,
        "detail" to detail,
        "targetApp" to targetApp,
        "taskLabel" to taskLabel,
        "stepIndex" to stepIndex,
        "stepCount" to stepCount,
        "retryCount" to retryCount,
        "autonomyPhase" to autonomyPhase,
        "certificationLevel" to certificationLevel,
        "isReady" to isReady,
        "updatedAtMillis" to updatedAtMillis,
    )
}

/**
 * Single source of truth for the runtime status snapshot. Reads are
 * wait-free and never touch the durable runtime, so the Flutter 2s poll
 * can be answered from the main thread without ever invoking
 * [AgentRuntime.get] or [AmaraMemory]. The background
 * [RuntimeStatusRefresher] is the only writer.
 */
object RuntimeStatusRegistry {
    private val snapshotRef = AtomicReference(RuntimeStatusSnapshot())
    private val started = AtomicBoolean(false)
    private val refreshCount = AtomicLong(0L)
    private val lastRefreshError = AtomicReference<String?>(null)

    /**
     * Synchronous, wait-free read of the latest snapshot. The status path on
     * the main thread never touches AgentRuntime or AmaraMemory; it only reads
     * this atomic ref.
     */
    fun snapshot(): RuntimeStatusSnapshot = snapshotRef.get()

    /** Replaces the snapshot atomically. Called only from the background loop. */
    fun replace(next: RuntimeStatusSnapshot) {
        snapshotRef.set(next)
        refreshCount.incrementAndGet()
    }

    /** True after [start] has been called. Test seam. */
    fun isStarted(): Boolean = started.get()

    fun refreshCount(): Long = refreshCount.get()

    fun lastRefreshError(): String? = lastRefreshError.get()

    fun recordRefreshError(message: String?) {
        lastRefreshError.set(message)
    }

    fun markStarted() {
        started.set(true)
    }

    /**
     * Test seam: clears the started flag and resets the snapshot/error state.
     * Production code never calls this; tests use it to start a fresh loop.
     */
    internal fun resetForTest() {
        started.set(false)
        snapshotRef.set(RuntimeStatusSnapshot())
        refreshCount.set(0L)
        lastRefreshError.set(null)
    }
}

/**
 * Background coroutine that owns the AgentRuntime / AmaraMemory reads needed
 * to populate [RuntimeStatusRegistry]. Started by [MainActivity] on
 * `Dispatchers.IO`; suspends until `AgentRuntime.get(context).awaitReady()`
 * completes, then samples the chip state, certification level, and autonomy
 * detail once and atomically swaps the snapshot. The status path on the main
 * thread never invokes this — it only reads the registry.
 */
class RuntimeStatusRefresher(
    private val appContext: android.content.Context,
    private val scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    ),
) {
    suspend fun refreshOnce(): RuntimeStatusSnapshot {
        val runtime = AgentRuntime.get(appContext.applicationContext).awaitReady()
        val chip = RuntimeStatusBus.canonicalChipState()
        val state = ModuleStateStore(appContext.applicationContext)
        val detail = chip.status?.blocker
            ?: chip.status?.taskLabel
            ?: state.string(AutonomyController.DETAIL_KEY, "Ready for the next thing")
        val certLevel = runCatching {
            co.sanaa.agent.certification.CertificationEvaluator.promoteToLevel(
                co.sanaa.agent.certification.CertificationEvaluator.compute(runtime.certificationRunRecords()),
            )
        }.getOrDefault(0)
        return RuntimeStatusSnapshot(
            isReady = true,
            phase = chip.phase.name.lowercase(),
            detail = detail,
            active = (chip.working || chip.acting),
            blocked = chip.blocked,
            targetApp = (chip.status?.targetApp ?: ""),
            taskLabel = (chip.status?.taskLabel ?: ""),
            stepIndex = (chip.status?.stepIndex ?: 0),
            stepCount = (chip.status?.stepCount ?: 0),
            retryCount = (chip.status?.retryCount ?: 0),
            autonomyPhase = chip.phase.name.lowercase(),
            certificationLevel = certLevel,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    fun start() {
        if (!RuntimeStatusRegistry.isStarted()) {
            RuntimeStatusRegistry.markStarted()
            scope.launch {
                try {
                    val snap = refreshOnce()
                    RuntimeStatusRegistry.replace(snap)
                } catch (t: Throwable) {
                    RuntimeStatusRegistry.recordRefreshError(t.message ?: t::class.java.simpleName)
                }
            }
        }
    }
}
