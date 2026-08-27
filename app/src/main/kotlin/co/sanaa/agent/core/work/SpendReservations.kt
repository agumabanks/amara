package co.sanaa.agent.core.work

import co.sanaa.agent.core.AmaraMemory
import java.util.concurrent.atomic.AtomicLong

/**
 * Explicit spend-accounting lifecycle (corrective directive 3). Replaces
 * charge-before-attempt:
 *
 *   RESERVED   — ceiling-checked hold taken BEFORE any external surface is touched;
 *   COMMITTED  — the effect verified; the cost is recognized;
 *   RELEASED   — catalog rejection, preflight refusal, or proven non-effect: no cost;
 *   FAILED_NONBILLABLE — effect attempted but provably not performed: no cost;
 *   REFUNDED   — a committed cost later reversed.
 *
 * Budget ceilings count RESERVED + COMMITTED rows. Only COMMITTED cost is recognized
 * spend for profitability reporting.
 */
interface SpendReservations {
    data class Reservation(val id: String, val contractId: String, val amountUgx: Long)

    /** Ceiling-checked reservation; null when the declared budget refuses it. */
    fun reserve(contract: WorkContract, amountUgx: Long, reason: String, atMs: Long): Reservation?

    fun commit(reservation: Reservation, atMs: Long): Boolean
    fun release(reservation: Reservation, atMs: Long): Boolean
    fun markFailedNonbillable(reservation: Reservation, atMs: Long): Boolean
    fun refund(reservation: Reservation, atMs: Long): Boolean

    fun reservedOn(contractId: String): Long
    /** Recognized (COMMITTED) cost only — the profitability-honest figure. */
    fun committedOn(contractId: String): Long
    fun withinBudget(contract: WorkContract, nowMs: Long): Boolean
}

/** In-memory implementation for simulations and pure tests. */
class MemorySpendReservations : SpendReservations {

    enum class State { RESERVED, COMMITTED, RELEASED, FAILED_NONBILLABLE, REFUNDED }

    data class Row(val id: String, val contractId: String, val amountUgx: Long, var state: State)

    private val rows = mutableListOf<Row>()
    private val counter = AtomicLong(0)
    private var maxOvershootUgx: Long = 0

    @Synchronized
    override fun reserve(contract: WorkContract, amountUgx: Long, reason: String, atMs: Long): SpendReservations.Reservation? {
        require(amountUgx >= 0) { "Reservations cannot be negative" }
        val outstanding = outstanding(contract.id)
        if (contract.budgetUgx != null && outstanding + amountUgx > contract.budgetUgx + maxOvershootUgx) return null
        val row = Row("res-${counter.incrementAndGet()}", contract.id, amountUgx, State.RESERVED)
        rows += row
        return SpendReservations.Reservation(row.id, row.contractId, row.amountUgx)
    }

    @Synchronized
    private fun move(id: String, from: State, to: State): Boolean {
        val row = rows.firstOrNull { it.id == id } ?: return false
        if (row.state != from) return false
        row.state = to
        return true
    }

    override fun commit(reservation: SpendReservations.Reservation, atMs: Long) = move(reservation.id, State.RESERVED, State.COMMITTED)
    override fun release(reservation: SpendReservations.Reservation, atMs: Long) = move(reservation.id, State.RESERVED, State.RELEASED)
    override fun markFailedNonbillable(reservation: SpendReservations.Reservation, atMs: Long) = move(reservation.id, State.RESERVED, State.FAILED_NONBILLABLE)
    override fun refund(reservation: SpendReservations.Reservation, atMs: Long) = move(reservation.id, State.COMMITTED, State.REFUNDED)

    @Synchronized
    private fun outstanding(contractId: String) =
        rows.filter { it.contractId == contractId && it.state in setOf(State.RESERVED, State.COMMITTED) }.sumOf { it.amountUgx }

    @Synchronized
    override fun reservedOn(contractId: String): Long =
        rows.filter { it.contractId == contractId && it.state == State.RESERVED }.sumOf { it.amountUgx }

    @Synchronized
    override fun committedOn(contractId: String): Long =
        rows.filter { it.contractId == contractId && it.state == State.COMMITTED }.sumOf { it.amountUgx }

    @Synchronized
    override fun withinBudget(contract: WorkContract, nowMs: Long): Boolean =
        contract.budgetUgx == null || outstanding(contract.id) <= contract.budgetUgx + maxOvershootUgx
}

/**
 * PRODUCTION durable reservation ledger over AmaraMemory's `budget_reservations` table.
 * Every state move is atomic and survives process death; a crash between reserve and
 * act leaves a RESERVED row that still counts against the ceiling until explicitly
 * resolved (fail-safe: never silently forgotten).
 */
class DurableSpendReservations(internal val memory: AmaraMemory, private val maxOvershootUgx: Long = 0) : SpendReservations {

    override fun reserve(contract: WorkContract, amountUgx: Long, reason: String, atMs: Long): SpendReservations.Reservation? {
        require(amountUgx >= 0) { "Reservations cannot be negative" }
        val outstanding = memory.budgetReservationOutstanding(contract.id)
        if (contract.budgetUgx != null && outstanding + amountUgx > contract.budgetUgx + maxOvershootUgx) {
            memory.recordEnforcementState(contract.id, "BUDGET_EXCEEDED", "reservation refused: $outstanding + $amountUgx exceeds ${contract.budgetUgx}", atMs)
            return null
        }
        val id = "res-$atMs-${(reason.hashCode().toLong() and 0xFFFF)}"
        // Unique-per-attempt suffix to avoid collisions across steps in the same ms.
        var attempt = 0
        while (!memory.insertBudgetReservation("$id-${attempt}", contract.id, amountUgx, reason, atMs)) attempt++
        return SpendReservations.Reservation("$id-$attempt", contract.id, amountUgx)
    }

    override fun commit(reservation: SpendReservations.Reservation, atMs: Long): Boolean =
        memory.transitionBudgetReservation(reservation.id, "COMMITTED", atMs)

    override fun release(reservation: SpendReservations.Reservation, atMs: Long): Boolean =
        memory.transitionBudgetReservation(reservation.id, "RELEASED", atMs)

    override fun markFailedNonbillable(reservation: SpendReservations.Reservation, atMs: Long): Boolean =
        memory.transitionBudgetReservation(reservation.id, "FAILED_NONBILLABLE", atMs)

    override fun refund(reservation: SpendReservations.Reservation, atMs: Long): Boolean =
        memory.transitionBudgetReservation(reservation.id, "REFUNDED", atMs)

    override fun reservedOn(contractId: String): Long =
        memory.budgetReservations(contractId).filter { it.state == "RESERVED" }.sumOf { it.amountUgx }

    override fun committedOn(contractId: String): Long = memory.budgetReservationCommitted(contractId)

    override fun withinBudget(contract: WorkContract, nowMs: Long): Boolean {
        val budget = contract.budgetUgx ?: return true
        return memory.budgetReservationOutstanding(contract.id) <= budget + maxOvershootUgx
    }
}
