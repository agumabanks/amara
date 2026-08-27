package co.sanaa.agent.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Global automation phase shown by the status chip and surfaced to Flutter. */
enum class RuntimePhase {
    IDLE, OBSERVE, THINK, ACT, VERIFY, RETRY, RECOVER, BLOCKED, FAILED, COMPLETE,
}

/** One worker/module's live work status. Never carries secret content. */
data class WorkStatus(
    val workerId: String,
    val targetApp: String?,
    val taskLabel: String,
    val phase: RuntimePhase,
    val stepIndex: Int,
    val stepCount: Int,
    val retryCount: Int,
    val blocker: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

/**
 * The ONE canonical snapshot the overlay chip renders from (campaign handoff §6).
 * Derived exclusively from live bus state: fresh work statuses, the acting flag,
 * and the provider-offline flag. A chip can never report a resting state while
 * active/blocked/retrying/recovering work exists, and stale entries from an
 * interrupted process are ignored rather than trusted.
 */
data class OverlayChipState(
    /** Dominant phase for palette/detail rendering; IDLE only when truly at rest. */
    val phase: RuntimePhase,
    /** True when any fresh non-IDLE work status exists. */
    val working: Boolean,
    /** True while automation holds the acting lock (touch suppression in force). */
    val acting: Boolean,
    /** True when the dominant phase is BLOCKED or FAILED (owner attention). */
    val blocked: Boolean,
    /** True when the model provider is marked offline AND nothing else is running. */
    val offline: Boolean,
    /** Freshest fresh WorkStatus driving the detail line; null when at rest. */
    val status: WorkStatus?,
) {
    val atRest: Boolean get() = !working && !acting && !offline
}

/**
 * Process-wide observational status bus. The overlay subscribes to render the
 * edge-safe status chip; MainActivity exposes a read-only snapshot over the
 * Flutter bridge. Entries carry labels and blockers only — never message
 * bodies, contact numbers beyond masked targets, or credentials.
 */
object RuntimeStatusBus {
    private val statuses = ConcurrentHashMap<String, WorkStatus>()
    private val listeners = CopyOnWriteArraySet<(WorkStatus?) -> Unit>()
    private val overlaySuppression = AtomicReference(0)
    private val providerOffline = AtomicBoolean(false)

    fun report(status: WorkStatus) {
        statuses[status.workerId] = status
        notifyListeners(status)
    }

    /** Clears one worker's entry when its run reaches a terminal state. */
    fun clear(workerId: String) {
        statuses.remove(workerId)
        notifyListeners(null)
    }

    fun snapshot(): List<WorkStatus> = statuses.values.sortedBy { worker -> worker.workerId }

    fun activeCount(): Int = statuses.size

    /**
     * While automation is acting the overlay must not intercept touches or be
     * draggable; suppression is reference-counted so nested transactions are safe.
     */
    fun beginActing() { overlaySuppression.updateAndGet { it + 1 } }
    fun endActing() { overlaySuppression.updateAndGet { (it - 1).coerceAtLeast(0) } }
    fun isActing(): Boolean = overlaySuppression.get() > 0

    /**
     * Canonical provider connectivity flag (e.g. circuit open / network dead).
     * Purely observational: it downgrades an otherwise-idle chip to Offline but
     * NEVER overrides live work states.
     */
    fun setProviderOffline(offline: Boolean) { providerOffline.set(offline) }
    fun isProviderOffline(): Boolean = providerOffline.get()

    fun addListener(listener: (WorkStatus?) -> Unit): Boolean = listeners.add(listener)
    fun removeListener(listener: (WorkStatus?) -> Unit) { listeners.remove(listener) }

    /**
     * THE canonical runtime state for the overlay chip. Severity ordering means a
     * blocked/retrying/failed worker dominates concurrent lower-severity work; an
     * acting lock always presents as working; statuses older than [staleAfterMillis]
     * are ignored so a crashed process can never leave the chip stuck on a lie.
     */
    fun canonicalChipState(
        keyguardLocked: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
        staleAfterMillis: Long = STALE_AFTER_MS,
    ): OverlayChipState {
        val fresh = statuses.values.filter {
            // Terminal attention states belong to this live process and must remain
            // visible until a real repair/new run clears or replaces them. Moving
            // phases can expire because a wedged publisher must not claim work
            // forever. Process death clears this in-memory bus by construction.
            it.phase == RuntimePhase.BLOCKED || it.phase == RuntimePhase.FAILED ||
                nowMillis - it.updatedAtMillis <= staleAfterMillis
        }
        val dominant = fresh.maxByOrNull { severity(it.phase) }
        val acting = isActing()
        val working = dominant != null && dominant.phase != RuntimePhase.IDLE
        val effectiveWorking = working || acting
        val offline = providerOffline.get() && !effectiveWorking
        val phase = when {
            !effectiveWorking -> RuntimePhase.IDLE
            // An acting lock presents as ACT unless fresher work is more severe.
            acting && (dominant == null || severity(dominant.phase) < severity(RuntimePhase.ACT)) -> RuntimePhase.ACT
            dominant != null -> dominant.phase
            else -> RuntimePhase.ACT
        }
        val blocked = effectiveWorking && (phase == RuntimePhase.BLOCKED || phase == RuntimePhase.FAILED)
        return OverlayChipState(
            phase = phase,
            working = effectiveWorking,
            acting = acting,
            blocked = blocked,
            offline = offline,
            status = dominant,
        )
    }

    /** Higher severity wins the chip; COMPLETE loses to everything still moving. */
    private fun severity(phase: RuntimePhase): Int = when (phase) {
        RuntimePhase.BLOCKED -> 9
        RuntimePhase.FAILED -> 8
        RuntimePhase.RETRY -> 7
        RuntimePhase.RECOVER -> 6
        RuntimePhase.ACT -> 5
        RuntimePhase.VERIFY -> 4
        RuntimePhase.THINK -> 3
        RuntimePhase.OBSERVE -> 2
        RuntimePhase.COMPLETE -> 1
        RuntimePhase.IDLE -> 0
    }

    private fun notifyListeners(status: WorkStatus?) {
        for (listener in listeners) {
            runCatching { listener(status) }
        }
    }

    /** Entries older than this are treated as debris from an interrupted process. */
    const val STALE_AFTER_MS: Long = 10 * 60_000L
}
