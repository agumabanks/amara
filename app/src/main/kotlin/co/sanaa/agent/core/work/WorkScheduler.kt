package co.sanaa.agent.core.work

/**
 * Phase B scheduling logic: dependency ordering across contracts, missed/deferred
 * occurrence computation against deadlines, and budget metering with hard stops.
 */
object WorkScheduler {

    sealed class OrderResult {
        data class Ordered(val sequence: List<WorkContract>) : OrderResult()
        data class CyclicDependency(val cycle: List<String>) : OrderResult()
    }

    /**
     * Topological ordering of contracts by their [WorkContract.dependencies].
     * Unknown dependencies fail loudly rather than silently dropping the edge.
     */
    fun order(contracts: List<WorkContract>): OrderResult {
        val byId = contracts.associateBy { it.id }
        contracts.forEach { contract ->
            contract.dependencies.forEach { dep ->
                require(byId.containsKey(dep)) { "Contract ${contract.id} depends on unknown '${dep}'" }
            }
        }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val ordered = mutableListOf<WorkContract>()
        fun visit(id: String): Boolean {
            if (id in visited) return true
            if (id in visiting) return false
            visiting += id
            val contract = byId.getValue(id)
            var cyclic = false
            for (dep in contract.dependencies) {
                if (!visit(dep)) { cyclic = true; break }
            }
            visiting -= id
            if (cyclic) return false
            visited += id
            ordered += contract
            return true
        }
        contracts.forEach { if (!visit(it.id)) return OrderResult.CyclicDependency(ordered.map { c -> c.id }) }
        return OrderResult.Ordered(ordered)
    }

    /**
     * Occurrence state of one scheduled contract at [nowMs].
     * Freshness is measured as an ELAPSED DURATION since the last completion against the
     * contract's freshness window ([WorkContract.freshnessWindowMs] or its documented
     * interval-derived default) — never by comparing against a fraction of a timestamp.
     */
    fun occurrenceState(contract: WorkContract, lastCompletedAtMs: Long?, deferredUntilMs: Long?, nowMs: Long): OccurrenceState = when {
        deferredUntilMs != null && deferredUntilMs > nowMs -> OccurrenceState.DEFERRED
        lastCompletedAtMs != null && nowMs - lastCompletedAtMs <= freshnessWindow(contract) -> OccurrenceState.EXECUTED_VERIFIED
        contract.deadlineMs != null && nowMs > contract.deadlineMs -> OccurrenceState.MISSED
        else -> OccurrenceState.PLANNED
    }

    /** Explicit freshness duration; see [WorkContract.freshnessWindowMs] for semantics. */
    fun freshnessWindow(contract: WorkContract): Long =
        contract.freshnessWindowMs
            ?: contract.deadlineMs?.let { deadline ->
                ((deadline - contract.createdAtMs) / 2).coerceAtLeast(WorkContract.MIN_FRESHNESS_MS)
            }
            ?: WorkContract.DEFAULT_FRESHNESS_MS
}

/**
 * Budget metering: records spend per contract and hard-stops execution once the
 * declared budget would be exceeded. Amounts are UGX whole units.
 */
interface SpendMeter {
    fun record(contract: WorkContract, amountUgx: Long, reason: String, atMs: Long): Result<Unit>
    fun spentOn(contractId: String): Long
    fun entries(): List<BudgetMeter.Entry>
    fun withinBudget(contract: WorkContract): Boolean
}

class BudgetMeter(private val maxOvershootUgx: Long = 0) : SpendMeter {

    data class Entry(val contractId: String, val amountUgx: Long, val reason: String, val atMs: Long)

    private val spend = mutableListOf<Entry>()

    @Synchronized
    override fun record(contract: WorkContract, amountUgx: Long, reason: String, atMs: Long): Result<Unit> {
        require(amountUgx >= 0) { "Spend cannot be negative" }
        val budget = contract.budgetUgx ?: return Result.success(Unit).also { spend += Entry(contract.id, amountUgx, reason, atMs) }
        val current = spentOn(contract.id)
        return if (current + amountUgx <= budget + maxOvershootUgx) {
            spend += Entry(contract.id, amountUgx, reason, atMs)
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Budget exceeded for ${contract.id}: spent $current + $amountUgx exceeds $budget"))
        }
    }

    @Synchronized
    override fun spentOn(contractId: String): Long = spend.filter { it.contractId == contractId }.sumOf { it.amountUgx }

    @Synchronized
    override fun entries(): List<Entry> = spend.toList()

    @Synchronized
    override fun withinBudget(contract: WorkContract): Boolean = contract.budgetUgx == null || spentOn(contract.id) <= contract.budgetUgx + maxOvershootUgx

    /** Deadline metering: returns remaining milliseconds or negative overrun. */
    fun remainingMs(contract: WorkContract, nowMs: Long): Long? = contract.deadlineMs?.minus(nowMs)
}

/**
 * PRODUCTION durable budget meter: every accepted entry persists in AmaraMemory's
 * `budget_spend` table and enforcement state in `work_enforcement`, so budget ceilings,
 * overdue, and deferral states survive process restarts. Rejected (over-budget) attempts
 * persist nothing. Thread safety comes from AmaraMemory's synchronized SQLite access.
 */
class DurableBudgetMeter(internal val memory: co.sanaa.agent.core.AmaraMemory, private val maxOvershootUgx: Long = 0) : SpendMeter {

    override fun record(contract: WorkContract, amountUgx: Long, reason: String, atMs: Long): Result<Unit> {
        require(amountUgx >= 0) { "Spend cannot be negative" }
        val current = spentOn(contract.id)
        val budget = contract.budgetUgx
        if (budget != null && current + amountUgx > budget + maxOvershootUgx) {
            memory.recordEnforcementState(contract.id, "BUDGET_EXCEEDED", "spent $current + $amountUgx exceeds $budget", atMs)
            return Result.failure(IllegalStateException("Budget exceeded for ${contract.id}: spent $current + $amountUgx exceeds $budget"))
        }
        memory.insertBudgetSpendEntry(contract.id, amountUgx, reason, atMs)
        if (budget != null) memory.recordEnforcementState(contract.id, "WITHIN_BUDGET", "", atMs)
        return Result.success(Unit)
    }

    override fun spentOn(contractId: String): Long = memory.budgetSpendTotal(contractId)

    override fun entries(): List<BudgetMeter.Entry> = memory.allBudgetSpendEntries().map {
        BudgetMeter.Entry(it.contractId, it.amountUgx, it.reason, it.createdAtMs)
    }

    override fun withinBudget(contract: WorkContract): Boolean {
        val budget = contract.budgetUgx ?: return true
        return spentOn(contract.id) <= budget + maxOvershootUgx
    }

    /** Deadline metering with durable overdue-state recording. */
    fun remainingMs(contract: WorkContract, nowMs: Long): Long? = contract.deadlineMs?.minus(nowMs)?.also { remaining ->
        if (remaining < 0) memory.recordEnforcementState(contract.id, "OVERDUE", "deadline overrun ${-remaining}ms", nowMs)
    }
}
