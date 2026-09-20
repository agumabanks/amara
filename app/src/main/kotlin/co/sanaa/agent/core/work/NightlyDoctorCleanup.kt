package co.sanaa.agent.core.work

import android.content.Context
import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.QuietHoursPolicy
import java.time.LocalDate
import java.time.LocalTime

data class NightlyDoctorResult(
    val ran: Boolean,
    val reason: String,
    val learned: Int = 0,
    val compacted: Int = 0,
    val clearedStaleWaits: Int = 0,
)

/** Quiet-hours maintenance. It learns and reconciles state, never invents delivery proof. */
class NightlyDoctorCleanup(private val context: Context) {
    private val prefs = context.getSharedPreferences("nightly_doctor", Context.MODE_PRIVATE)

    suspend fun run(runtime: AgentRuntime, now: Long = System.currentTimeMillis()): NightlyDoctorResult {
        if (!runtime.config.nightlyDoctorEnabled) return NightlyDoctorResult(false, "disabled")
        if (!co.sanaa.agent.core.OwnerPower(context).isOn()) return NightlyDoctorResult(false, "owner_off")
        val quiet = QuietHoursPolicy.parse(runtime.config.quietHoursStart, runtime.config.quietHoursEnd)
            ?: return NightlyDoctorResult(false, "invalid_quiet_hours")
        if (!quiet.contains(LocalTime.now())) return NightlyDoctorResult(false, "outside_quiet_hours")
        val day = LocalDate.now().toString()
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
        prefs.edit().putString("last_run_day", day).putLong("last_run_at", now).commit()
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