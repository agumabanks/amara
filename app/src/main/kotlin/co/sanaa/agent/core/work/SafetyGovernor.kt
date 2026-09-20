package co.sanaa.agent.core.work

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SafetyGovernor — enforces circuit breakers and safety boundaries.
 * 
 * State machine: NORMAL → DEGRADED → HALTED (asymmetric: degrade is cheap, halt is expensive to leave)
 */
class SafetyGovernor(
    context: Context,
    private val maxDailyScreenMinutes: () -> Int = { 90 },
    private val maxRetryCooldownMinutes: () -> Int = { 1440 },
    private val tikTokPostingMinutes: () -> Int = { 0 },
) : SQLiteOpenHelper(context, "amara_safety.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS safety_counters (
                scope TEXT NOT NULL,
                key TEXT NOT NULL,
                window_start INTEGER NOT NULL,
                count INTEGER DEFAULT 0,
                value_kes REAL DEFAULT 0.0,
                PRIMARY KEY (scope, key, window_start)
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS kind_breakers (
                kind TEXT PRIMARY KEY,
                trip_count INTEGER DEFAULT 0,
                last_trip_at INTEGER DEFAULT 0,
                cooldown_until INTEGER DEFAULT 0,
                consecutive_failures INTEGER DEFAULT 0,
                attempts_total INTEGER DEFAULT 0,
                failures_total INTEGER DEFAULT 0
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS governor_state (
                id INTEGER PRIMARY KEY DEFAULT 1,
                state TEXT DEFAULT 'NORMAL',
                reason TEXT DEFAULT '',
                updated_at INTEGER NOT NULL
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    enum class GovernorState { NORMAL, DEGRADED, HALTED }

    data class BreakerVerdict(val allowed: Boolean, val reason: String = "")

    /**
     * Get current governor state.
     */
    @Synchronized
    fun getState(): GovernorState {
        val cursor = readableDatabase.rawQuery("SELECT state FROM governor_state WHERE id = 1", null)
        return if (cursor.moveToFirst()) {
            try { GovernorState.valueOf(cursor.getString(0)) } catch (e: Exception) { GovernorState.NORMAL }
        } else {
            writableDatabase.execSQL(
                "INSERT OR IGNORE INTO governor_state(id, state, reason, updated_at) VALUES (1, 'NORMAL', '', ?)",
                arrayOf(System.currentTimeMillis().toString())
            )
            GovernorState.NORMAL
        }.also { cursor.close() }
    }

    /**
     * Transition to a new state.
     */
    @Synchronized
    fun transitionTo(newState: GovernorState, reason: String) {
        writableDatabase.execSQL(
            "UPDATE governor_state SET state = ?, reason = ?, updated_at = ? WHERE id = 1",
            arrayOf(newState.name, reason, System.currentTimeMillis().toString())
        )
    }

    /**
     * Check if a work item is allowed to execute.
     */
    @Synchronized
    fun isAllowed(
        item: WorkItem,
        sessionScreenSeconds: Int,
        sessionExternalActions: Int,
        snapshot: WorldSnapshot? = null,
    ): BreakerVerdict {
        val state = getState()

        // HALTED: nothing allowed
        if (state == GovernorState.HALTED) {
            return BreakerVerdict(false, "Governor is HALTED")
        }

        // Check per-kind circuit breaker
        val destinationManagedGroup = item.kind == WorkKind.WA_BROADCAST && item.payload.optString("group_target").isNotBlank()
        if (!destinationManagedGroup && getKindBreakerCooldown(item.kind,breakerKey(item)) > 0) {
            return BreakerVerdict(false, "Kind ${item.kind} is circuit-broken")
        }

        // DEGRADED: only T0/T1 work allowed
        if (state == GovernorState.DEGRADED && item.riskTier != RiskTier.LOW) {
            return BreakerVerdict(false, "Governor DEGRADED: only LOW risk work allowed")
        }

        if (Capability.NETWORK in item.requires && snapshot?.networkAvailable == false) {
            return BreakerVerdict(false, "Network capability is unavailable")
        }
        if (snapshot?.quietHours == true && Capability.CONSENT_TIER_2 in item.requires &&
            !item.payload.optBoolean("owner_always_on", false)) {
            return BreakerVerdict(false, "External communication is blocked during quiet hours")
        }

        // Check session limits
        if (sessionExternalActions >= 3 && item.requires.contains(Capability.CONSENT_TIER_2)) {
            return BreakerVerdict(false, "Session external action limit reached (3)")
        }

        if (sessionScreenSeconds >= 900) { // 15 min
            return BreakerVerdict(false, "Session screen time limit reached (15 min)")
        }

        // Check daily limits
        val dayStart = dayStart()
        val dailyScreen = getCounter("daily", "screen_seconds", dayStart)
        val dailyScreenLimit = maxDailyScreenMinutes().coerceIn(10, 1440) * 60
        val inboundReserveUsed = getCounter("daily", "inbound_screen_reserve", dayStart)
        val inboundReserveAvailable = item.kind == WorkKind.WA_REPLY_INBOUND && !item.payload.optBoolean("manager_report") && inboundReserveUsed < 180
        val postingReserveAvailable = item.kind == WorkKind.TIKTOK_POST_PUBLISH && tikTokPostingSecondsRemaining() >= 180
        if (Capability.SCREEN in item.requires && dailyScreen >= dailyScreenLimit && !inboundReserveAvailable && !postingReserveAvailable) {
            return BreakerVerdict(false, "Daily screen budget exhausted (${maxDailyScreenMinutes()} min)")
        }

        val dailyMessages = getCounter("daily", "messages_sent", dayStart)
        // Owner-scheduled groups have their own cadence, permissions and delivery
        // holds. Customer replies must not silently consume their entire allowance.
        if (!destinationManagedGroup && dailyMessages >= 40 && item.kind in setOf(WorkKind.WA_FOLLOWUP, WorkKind.WA_BROADCAST)) {
            return BreakerVerdict(false, "Daily message limit reached (40)")
        }

        return BreakerVerdict(true)
    }

    /**
     * Record an execution result.
     */
    @Synchronized
    fun recordExecution(result: WorkResult) {
        val now = System.currentTimeMillis()
        val dayStart = dayStart()

        // Update daily counters
        incrementCounter("daily", "attempts", dayStart, 1)
        incrementCounter("daily", "screen_seconds", dayStart, result.screenSecondsUsed)
        if (result.item.kind == WorkKind.TIKTOK_POST_PUBLISH) {
            incrementCounter("daily", "tiktok_posting_seconds", dayStart, result.screenSecondsUsed)
        }
        if (result.item.kind == WorkKind.WA_REPLY_INBOUND && !result.item.payload.optBoolean("manager_report")) {
            incrementCounter("daily", "inbound_screen_reserve", dayStart, result.screenSecondsUsed)
        }

        if (result.status == WorkStatus.DONE) {
            incrementCounter("daily", "successes", dayStart, 1)
            if (result.item.kind in setOf(WorkKind.WA_FOLLOWUP, WorkKind.WA_REPLY_INBOUND, WorkKind.WA_BROADCAST)) {
                incrementCounter("daily", "messages_sent", dayStart, 1)
            }
        } else if (result.status == WorkStatus.FAILED) {
            incrementCounter("daily", "failures", dayStart, 1)
        }

        // Policy deferrals and escalations are not implementation failures.
        if (result.status == WorkStatus.DONE || result.status == WorkStatus.FAILED) {
            updateKindBreaker(breakerKey(result.item), result.status == WorkStatus.DONE)
        }
    }

    /**
     * Check if a kind's circuit breaker is open.
     */
    @Synchronized
    fun isKindBreakerOpen(kind: WorkKind): Boolean {
        return getKindBreakerCooldown(kind) > 0
    }

    /**
     * Get remaining cooldown for a kind (0 if not broken).
     */
    @Synchronized
    fun getKindBreakerCooldown(kind: WorkKind, key: String = kind.name): Long {
        val cursor = readableDatabase.rawQuery(
            "SELECT cooldown_until, last_trip_at FROM kind_breakers WHERE kind = ?",
            arrayOf(key)
        )
        val configuredCap = maxRetryCooldownMinutes().coerceIn(0, 1440)
        // One difficult conversation must not silence every other customer for hours.
        val capMinutes = when(kind) {
            WorkKind.WA_REPLY_INBOUND -> minOf(configuredCap, 2)
            WorkKind.TIKTOK_POST_PUBLISH, WorkKind.TIKTOK_COMMENT_REPLY -> minOf(configuredCap, 30)
            else -> configuredCap
        }
        val cooldown = if (cursor.moveToFirst() && capMinutes > 0)
            minOf(cursor.getLong(0), cursor.getLong(1) + capMinutes * 60_000L) else 0L
        cursor.close()
        return maxOf(0, cooldown - System.currentTimeMillis())
    }

    fun screenSecondsToday(): Int = getCounter("daily", "screen_seconds", dayStart())
    fun tikTokPostingSecondsRemaining(): Int = (tikTokPostingMinutes().coerceIn(0, 240) * 60 -
        getCounter("daily", "tiktok_posting_seconds", dayStart())).coerceAtLeast(0)
    fun tikTokPostingLimitMinutes(): Int = tikTokPostingMinutes().coerceIn(0, 240)
    fun inboundReserveSecondsToday(): Int = getCounter("daily", "inbound_screen_reserve", dayStart())
    fun messagesSentToday(): Int = getCounter("daily", "messages_sent", dayStart())
    fun attemptsToday(): Int = getCounter("daily", "attempts", dayStart())

    fun dashboard(): Map<String, Any> = mapOf(
        "state" to getState().name,
        "attemptsToday" to attemptsToday(),
        "successesToday" to getCounter("daily", "successes", dayStart()),
        "failuresToday" to getCounter("daily", "failures", dayStart()),
        "messagesToday" to messagesSentToday(),
        "screenSecondsToday" to screenSecondsToday(),
        "screenLimitMinutes" to maxDailyScreenMinutes().coerceIn(10, 1440),
        "inboundReserveSecondsToday" to inboundReserveSecondsToday(),
        "inboundReserveSecondsRemaining" to (180 - inboundReserveSecondsToday()).coerceAtLeast(0),
        "openBreakers" to WorkKind.entries.filter(::isKindBreakerOpen).map { it.name },
    )

    fun successRate(kind: WorkKind): Double {
        val cursor = readableDatabase.rawQuery(
            "SELECT attempts_total, failures_total FROM kind_breakers WHERE kind = ?",
            arrayOf(kind.name),
        )
        val rate = if (cursor.moveToFirst()) {
            val attempts = cursor.getInt(0)
            val failures = cursor.getInt(1)
            ((attempts - failures + 1).toDouble() / (attempts + 2)).coerceIn(0.05, 0.95)
        } else 0.5
        cursor.close()
        return rate
    }

    fun cooldownFor(item: WorkItem): Long = getKindBreakerCooldown(item.kind, breakerKey(item))

    private fun breakerKey(item: WorkItem): String = when {
        item.payload.optBoolean("manager_report") -> "MANAGER_REPORT"
        item.payload.optBoolean("manager_command_candidate") -> "MANAGER_COMMAND"
        else -> item.kind.name
    }

    private fun updateKindBreaker(key: String, success: Boolean) {
        val now = System.currentTimeMillis()
        val existing = readableDatabase.rawQuery(
            "SELECT trip_count, consecutive_failures, attempts_total, failures_total FROM kind_breakers WHERE kind = ?",
            arrayOf(key)
        )

        if (existing.moveToFirst()) {
            val tripCount = existing.getInt(0)
            val consecutiveFailures = if (success) 0 else existing.getInt(1) + 1
            val attemptsTotal = existing.getInt(2) + 1
            val failuresTotal = if (success) existing.getInt(3) else existing.getInt(3) + 1

            // Check trip condition
            val shouldTrip = !success && (consecutiveFailures >= 3 ||
                (attemptsTotal >= 6 && (failuresTotal + 1).toDouble() / (attemptsTotal + 2) > 0.5))

            if (shouldTrip) {
                val newTripCount = tripCount + 1
                val cooldownHours = minOf(Math.pow(2.0, newTripCount.toDouble()).toLong(), 24)
                val cooldownUntil = now + (cooldownHours * 3_600_000)
                writableDatabase.execSQL(
                    """INSERT OR REPLACE INTO kind_breakers 
                       (kind, trip_count, last_trip_at, cooldown_until, consecutive_failures, attempts_total, failures_total)
                       VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    arrayOf(key, newTripCount.toString(), now.toString(), cooldownUntil.toString(),
                        "0", attemptsTotal.toString(), failuresTotal.toString())
                )
            } else {
                writableDatabase.execSQL(
                    "UPDATE kind_breakers SET consecutive_failures = ?, attempts_total = ?, failures_total = ? WHERE kind = ?",
                    arrayOf(consecutiveFailures.toString(), attemptsTotal.toString(), failuresTotal.toString(), key)
                )
            }
        } else {
            writableDatabase.execSQL(
                """INSERT OR REPLACE INTO kind_breakers 
                   (kind, trip_count, last_trip_at, cooldown_until, consecutive_failures, attempts_total, failures_total)
                   VALUES (?, 0, ?, 0, ?, 1, ?)""",
                arrayOf(key, now.toString(), if (success) "0" else "1", if (success) "0" else "1")
            )
        }
        existing.close()
    }

    private fun incrementCounter(scope: String, key: String, windowStart: Long, delta: Int) {
        writableDatabase.execSQL(
            """INSERT INTO safety_counters(scope, key, window_start, count) VALUES (?, ?, ?, ?)
               ON CONFLICT(scope, key, window_start) DO UPDATE SET count = count + ?""",
            arrayOf(scope, key, windowStart.toString(), delta.toString(), delta.toString())
        )
    }

    private fun getCounter(scope: String, key: String, windowStart: Long): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT count FROM safety_counters WHERE scope = ? AND key = ? AND window_start = ?",
            arrayOf(scope, key, windowStart.toString())
        )
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count
    }

    private fun dayStart(): Long {
        val now = System.currentTimeMillis()
        return now - (now % (24 * 3_600_000))
    }

    /**
     * Reset daily counters (call at midnight).
     */
    @Synchronized
    fun resetDailyCounters() {
        writableDatabase.execSQL("DELETE FROM safety_counters WHERE scope = 'daily'")
    }
}
