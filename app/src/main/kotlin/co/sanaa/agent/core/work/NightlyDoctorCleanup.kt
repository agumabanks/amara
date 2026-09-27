package co.sanaa.agent.core.work

import android.content.Context
import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.QuietHoursPolicy
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NightlyDoctorResult(
    val ran: Boolean,
    val reason: String,
    val learned: Int = 0,
    val compacted: Int = 0,
    val clearedStaleWaits: Int = 0,
)

/** Quiet-hours maintenance. It learns and reconciles state, never invents delivery proof. */
class NightlyDoctorCleanup(private val context: Context) {
    companion object {
        private val maintenanceLock = Mutex()
        fun diagnostics(context: Context): Map<String, Any> {
            val prefs = context.getSharedPreferences("nightly_doctor", Context.MODE_PRIVATE)
            return mapOf("lastCheckAt" to prefs.getLong("last_check_at", 0L),
                "lastCheckReason" to prefs.getString("last_check_reason", "not_checked").orEmpty(),
                "lastRunAt" to prefs.getLong("last_run_at", 0L),
                "lastSessionDay" to prefs.getString("last_run_day", "").orEmpty())
        }
        /** A quiet interval crossing midnight is one maintenance session. */
        internal fun sessionDay(now: Long, zone: ZoneId, quiet: QuietHoursPolicy): String? {
            val local = Instant.ofEpochMilli(now).atZone(zone)
            if (!quiet.contains(local.toLocalTime())) return null
            val date = if (quiet.start > quiet.end && local.toLocalTime() < quiet.end) local.toLocalDate().minusDays(1) else local.toLocalDate()
            return date.toString()
        }
    }
    private val prefs = context.getSharedPreferences("nightly_doctor", Context.MODE_PRIVATE)

    suspend fun run(runtime: AgentRuntime, now: Long = System.currentTimeMillis()): NightlyDoctorResult =
        maintenanceLock.withLock {
            try {
                runOnce(runtime, now).also { recordCheck(it.reason, now) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                recordCheck("failed:${error.javaClass.simpleName}", now)
                throw error
            }
        }

    private fun recordCheck(reason: String, now: Long) {
        check(prefs.edit().putLong("last_check_at", now).putString("last_check_reason", reason).commit())
    }

    private suspend fun runOnce(runtime: AgentRuntime, now: Long): NightlyDoctorResult {
        if (!runtime.config.nightlyDoctorEnabled) return NightlyDoctorResult(false, "disabled")
        if (!co.sanaa.agent.core.OwnerPower(context).isOn()) return NightlyDoctorResult(false, "owner_off")
        val quiet = QuietHoursPolicy.parse(runtime.config.quietHoursStart, runtime.config.quietHoursEnd)
            ?: return NightlyDoctorResult(false, "invalid_quiet_hours")
        val zone = runCatching { ZoneId.of(runtime.commercialPolicy()?.ownerTimeZoneId.orEmpty()) }.getOrNull()
            ?: return NightlyDoctorResult(false, "owner_timezone_missing")
        val day = sessionDay(now, zone, quiet)
            ?: return NightlyDoctorResult(false, "outside_quiet_hours")
        if (prefs.getString("last_run_day", "") == day) return NightlyDoctorResult(false, "already_ran_today")

        val blockers = runtime.workBlockers.rows()
        blockers.forEach { row ->
            runtime.learningLoop.recordAction(
                actionType = "NIGHTLY_BLOCKER_LEARNING",
                domain = "INTERNAL",
                success = false,
                details = "${row["task"]}: ${row["reason"]}",
            )
        }
        val compacted = runtime.workQueue.compactPendingBacklog()
        val cleared = runtime.workBlockers.clearStaleWaits(runtime.workQueue.activeScopes())
        runtime.health.run()
        val report = org.json.JSONObject().put("session_day",day).put("timezone",zone.id)
            .put("observed_at",now).put("blockers",org.json.JSONArray(blockers))
            .put("compacted_pending",compacted).put("cleared_stale_waits",cleared)
            .put("delivery_claim", "Maintenance counts are not successful sends or sales; uncertain work remains held")
        check(prefs.edit().putString("report:$day",report.toString()).putString("last_run_day", day).putLong("last_run_at", now).commit())
        runtime.evaluation.record(
            "nightly_doctor_cleanup",
            fields = org.json.JSONObject()
                .put("learned_blockers", blockers.size)
                .put("compacted_pending", compacted)
                .put("cleared_stale_waits", cleared)
                .put("quiet_start", runtime.config.quietHoursStart)
                .put("quiet_end", runtime.config.quietHoursEnd),
        )
        return NightlyDoctorResult(true, "completed", blockers.size, compacted, cleared)
    }
}
