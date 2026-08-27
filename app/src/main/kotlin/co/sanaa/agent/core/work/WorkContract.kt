package co.sanaa.agent.core.work

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.Redactor
import co.sanaa.agent.core.SideEffectLedger
import co.sanaa.agent.core.SideEffectState
import co.sanaa.agent.core.SideEffectTransaction

/** Typed outcome-oriented assignment (Complete-Employee Phase B). */
data class WorkContract(
    val id: String,
    val objective: String,
    val owner: String,
    val deliverables: List<String>,
    val successCriteria: List<String>,
    val deadlineMs: Long?,
    val dependencies: List<String>,
    val allowedSystems: Set<String>,
    val dataClassification: DataClassification,
    val budgetUgx: Long?,
    val approvalPolicy: ApprovalPolicy,
    val verificationRules: List<String>,
    val escalationConditions: List<String>,
    val followUpObligations: List<FollowUp>,
    val createdAtMs: Long,
    /**
     * Recurrence/freshness semantics (explicit, never derived from absolute timestamps):
     * how long a completed occurrence stays EXECUTED_VERIFIED. When null the window is
     * derived as HALF THE ACTIVE INTERVAL (deadlineMs − createdAtMs), floored at
     * [MIN_FRESHNESS_MS]; contracts without a deadline use [DEFAULT_FRESHNESS_MS] (24h).
     * A completion older than its freshness window is stale and the occurrence returns
     * to PLANNED/MISSED per deadline state.
     */
    val freshnessWindowMs: Long? = null,
) {
    init {
        require(objective.isNotBlank()) { "A work contract needs an objective" }
        require(deliverables.isNotEmpty()) { "A work contract names at least one deliverable" }
        require(successCriteria.isNotEmpty()) { "A work contract defines success" }
        require(allowedSystems.isNotEmpty()) { "A work contract scopes its systems" }
        require(createdAtMs >= 0) { "A work contract carries a non-negative creation time" }
        require(freshnessWindowMs == null || freshnessWindowMs > 0) { "Freshness window must be positive when set" }
        require(deadlineMs == null || deadlineMs >= createdAtMs) { "Deadline cannot precede creation" }
    }

    fun fingerprint(): String = ContentHashing.hash(
        listOf(objective, deliverables.joinToString("|"), allowedSystems.sorted().joinToString(",")).joinToString("§"),
    )

    companion object {
        const val MIN_FRESHNESS_MS = 60_000L
        const val DEFAULT_FRESHNESS_MS = 86_400_000L
    }
}

enum class DataClassification { PUBLIC, BUSINESS_INTERNAL, CUSTOMER_DATA, FINANCIAL, SECRET }

data class FollowUp(val description: String, val dueAtMs: Long)

/**
 * Standing-policy reference or per-action approval demand attached to a contract.
 * The policy engine remains authoritative; this is declarative intent only.
 */
data class ApprovalPolicy(
    val standingPolicyId: String? = null,
    val requireFreshApprovalFor: Set<String> = emptySet(),
)

/** Execution state of one resumable workflow run bound to a contract. */
data class WorkflowRun(
    val id: String,
    val contractFingerprint: String,
    val stepIndex: Int,
    val phase: RunPhase,
    val leaseOwner: String?,
    val leaseExpiresAtMs: Long,
    val checkpointJson: String,
    val updatedAtMs: Long,
    /** Outstanding owner question while AWAITING_DECISION; blank otherwise. */
    val decisionQuestion: String = "",
)

enum class RunPhase { PENDING, RUNNING, CHECKPOINTED, AWAITING_DECISION, COMPLETED, FAILED, CANCELLED }

enum class OccurrenceState { PLANNED, CLAIMED, EXECUTED_VERIFIED, EXECUTED_FAILED, MISSED, DEFERRED }

/**
 * Pure resumable execution engine. Steps advance only after their checkpoint persists;
 * a crash between steps resumes from the last checkpoint without re-running completed
 * side effects (the executor refuses already-verified steps by occurrence key).
 */
class ResumableWorkflowEngine(private val store: WorkflowStore) {

    /** Attempts to acquire an expired-or-free lease; returns false when another worker owns it. */
    fun acquireLease(runId: String, worker: String, nowMs: Long, leaseMs: Long): Boolean =
        store.tryAcquireLease(runId, worker, nowMs + leaseMs, nowMs)

    fun checkpoint(runId: String, stepIndex: Int, checkpointJson: String, nowMs: Long): Boolean =
        store.recordCheckpoint(runId, stepIndex, checkpointJson, nowMs)

    /**
     * Resume decision for one persisted run.
     * Returns the step to execute next plus its exactly-once key, or null when nothing
     * may run (lease held elsewhere, terminal phase, or awaiting owner decision).
     */
    fun nextAction(runId: String, worker: String, nowMs: Long, totalSteps: Int): NextAction? {
        val run = store.run(runId) ?: return null
        if (run.phase in setOf(RunPhase.COMPLETED, RunPhase.CANCELLED)) return null
        if (run.phase == RunPhase.AWAITING_DECISION) return null
        if (run.stepIndex >= totalSteps) return null
        if (run.leaseOwner != null && run.leaseOwner != worker && run.leaseExpiresAtMs > nowMs) return null
        return NextAction(stepIndex = run.stepIndex, occurrenceKey = "run:$runId:step:${run.stepIndex}")
    }

    data class NextAction(val stepIndex: Int, val occurrenceKey: String)
}

interface WorkflowStore {
    fun tryAcquireLease(runId: String, worker: String, newExpiryMs: Long, nowMs: Long): Boolean
    fun recordCheckpoint(runId: String, stepIndex: Int, checkpointJson: String, nowMs: Long): Boolean
    fun run(runId: String): WorkflowRun?
    fun markDecisionRequired(runId: String, question: String): Boolean
    fun resolveDecision(runId: String, answer: String): Boolean

    /** Creates the durable run row when absent; returns true when the run now exists. */
    fun ensureRun(runId: String, contractFingerprint: String, totalSteps: Int, nowMs: Long): Boolean = false

    /** Marks a finished run COMPLETED (terminal, inspectable as fulfilled commitment). */
    fun completeRun(runId: String, resultJson: String, nowMs: Long): Boolean = false
}

sealed class StepDispatch {
    /** Execute under this exactly-once key, then checkpoint stepIndex+1. */
    data class Execute(val stepIndex: Int, val occurrenceKey: String) : StepDispatch()
    /** A previous attempt crashed inside the effect window; owner decision required. */
    data class UncertainCrashWindow(val occurrenceKey: String, val state: String) : StepDispatch()
    object NothingToDo : StepDispatch()
}

/**
 * Production coordinator binding resumable workflows to the universal transaction
 * ledger. Exactly-once behavior comes from durable occurrence claims in
 * [SideEffectLedger] — never from in-memory sets. The crash window between an external
 * effect and its checkpoint resolves to an owner decision, never a blind replay.
 */
class DurableWorkflowCoordinator(
    private val store: WorkflowStore,
    private val ledger: SideEffectLedger,
) {
    fun dispatchNext(
        runId: String,
        worker: String,
        nowMs: Long,
        totalSteps: Int,
        capabilityId: String,
        contentFingerprint: String,
    ): StepDispatch {
        val next = ResumableWorkflowEngine(store).nextAction(runId, worker, nowMs, totalSteps)
            ?: return StepDispatch.NothingToDo
        val key = "${next.occurrenceKey}:$capabilityId"
        val existing = ledger.find(key)
        if (existing != null && existing.state.noAutoRetry) {
            store.markDecisionRequired(
                runId,
                "A previous attempt of step ${next.stepIndex} reached ${existing.state.name} and cannot be repeated automatically.",
            )
            return StepDispatch.UncertainCrashWindow(key, existing.state.name)
        }
        val claimed = if (existing == null) {
            ledger.upsert(
                SideEffectTransaction(
                    idempotencyKey = key, capability = capabilityId, target = runId,
                    contentHash = contentFingerprint, approvalId = null,
                    state = SideEffectState.CLAIMED, createdAt = nowMs, updatedAt = nowMs,
                    evidence = "Workflow step claimed before execution.",
                ),
            )
        } else {
            // Pre-acting states may legally re-claim (proposed/approved).
            ledger.transition(key, SideEffectState.CLAIMED, "Re-claimed by workflow coordinator.")
        }
        if (!claimed) return StepDispatch.UncertainCrashWindow(key, existing?.state?.name ?: "UNKNOWN")
        return StepDispatch.Execute(next.stepIndex, key)
    }

    /** Records the external outcome and advances the durable checkpoint atomically enough. */
    fun completeStep(occurrenceKey: String, verified: Boolean, evidence: String, runId: String, stepIndex: Int, checkpointJson: String, nowMs: Long) {
        ledger.transition(occurrenceKey, if (verified) SideEffectState.VERIFIED else SideEffectState.FAILED, co.sanaa.agent.core.Redactor.redactForExport(evidence))
        if (verified) store.recordCheckpoint(runId, stepIndex, checkpointJson, nowMs)
        else store.markDecisionRequired(runId, "Step $stepIndex did not complete cleanly; owner review required.")
    }
}


/** Production [WorkflowStore] backed by AmaraMemory's durable SQLite tables. */
class SqliteWorkflowStore(private val memory: co.sanaa.agent.core.AmaraMemory) : WorkflowStore {
    override fun ensureRun(runId: String, contractFingerprint: String, totalSteps: Int, nowMs: Long): Boolean {
        if (memory.findWorkflowRun(runId) != null) return true
        return memory.upsertWorkflowRun(
            co.sanaa.agent.core.AmaraMemory.WorkflowRunRow(
                id = runId, contractFingerprint = contractFingerprint, stepIndex = 0,
                phase = RunPhase.PENDING, leaseOwner = null, leaseExpiresAtMs = 0,
                decisionQuestion = "", checkpointJson = "{}", updatedAtMs = nowMs,
            ),
        )
    }

    override fun completeRun(runId: String, resultJson: String, nowMs: Long): Boolean =
        memory.markWorkflowCompleted(runId, resultJson, nowMs)

    override fun tryAcquireLease(runId: String, worker: String, newExpiryMs: Long, nowMs: Long): Boolean =
        memory.tryAcquireWorkflowLease(runId, worker, newExpiryMs, nowMs)

    override fun recordCheckpoint(runId: String, stepIndex: Int, checkpointJson: String, nowMs: Long): Boolean {
        val run = memory.findWorkflowRun(runId) ?: return false
        return memory.recordWorkflowCheckpoint(runId, stepIndex, checkpointJson, nowMs).also { advanced ->
            if (advanced) memory.upsertWorkflowRun(
                (memory.findWorkflowRun(runId) ?: run).copy(updatedAtMs = nowMs),
            )
        }
    }

    override fun run(runId: String): WorkflowRun? = memory.findWorkflowRun(runId)?.toEngineRun()

    override fun markDecisionRequired(runId: String, question: String): Boolean =
        memory.markWorkflowDecisionRequired(runId, question)

    override fun resolveDecision(runId: String, answer: String): Boolean =
        memory.resolveWorkflowDecision(runId, answer)
}

private fun co.sanaa.agent.core.AmaraMemory.WorkflowRunRow.toEngineRun() = WorkflowRun(
    id = id, contractFingerprint = contractFingerprint, stepIndex = stepIndex,
    phase = phase, leaseOwner = leaseOwner, leaseExpiresAtMs = leaseExpiresAtMs,
    checkpointJson = checkpointJson, decisionQuestion = decisionQuestion, updatedAtMs = updatedAtMs,
)
