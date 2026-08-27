package co.sanaa.agent.workers

import android.content.Context
import androidx.work.*
import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.CommandExecutor
import co.sanaa.agent.core.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import co.sanaa.agent.core.ScheduleCalculator
import co.sanaa.agent.core.ScheduleCodec
import co.sanaa.agent.core.DeviceAvailabilityGuard
import co.sanaa.agent.core.QuietHoursPolicy

abstract class AgentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected val runtime = AgentRuntime.get(context)
    protected suspend fun guarded(block: suspend () -> Boolean): Result = withContext(Dispatchers.IO) {
        runtime.queue.withExclusiveDeviceAction {
            runCatching { if (block()) Result.success() else Result.retry() }.getOrElse { Result.retry() }
        }
    }
}

class MorningBroadcastWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun doWork(): Result = guarded { runtime.morning.run().success }.also { AgentWorkScheduler.scheduleMorning(applicationContext, runtime.config.broadcastTime) }
}
class ConversationPollWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun doWork(): Result = guarded { runtime.conversation.pollSoko().all { it.success } }
}
class ListingIntelligenceWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun doWork(): Result = guarded { runtime.listing.run().success }
}

class OwnerCommandWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val command = inputData.getString(COMMAND).orEmpty()
        val contactName = inputData.getString(CONTACT_NAME).orEmpty()
        val contactPhone = inputData.getString(CONTACT_PHONE).orEmpty()
        if (command.isBlank()) return Result.failure()
        return runCatching { CommandExecutor(applicationContext).execute(command, contactName, contactPhone) }
            .fold({ if (it.success) Result.success() else Result.failure() }, { Result.failure() })
    }

    companion object {
        const val COMMAND = "command"
        const val CONTACT_NAME = "contact_name"
        const val CONTACT_PHONE = "contact_phone"
    }
}

class ScheduledCommandWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runtime = AgentRuntime.get(applicationContext)
        val taskId = inputData.getLong(TASK_ID, -1L)
        val task = runtime.memory.recurringTask(taskId) ?: return Result.failure()
        if (!task.enabled) return Result.success()
        val availability = DeviceAvailabilityGuard.checkOrWake(applicationContext)
        if (!availability.available) {
            AgentWorkScheduler.scheduleRecurring(applicationContext, task.id, System.currentTimeMillis() + 5 * 60_000L)
            return Result.success()
        }
        // Occurrence claiming now lives on the universal transaction table so scheduled
        // work shares exactly-once semantics with every other external action.
        val occurrenceKey = "recurring:${task.id}:${task.nextRunAt}"
        val existing = runtime.memory.findSideEffectTransaction(occurrenceKey)
        if (existing != null && existing.state !in setOf(
                co.sanaa.agent.core.SideEffectState.PROPOSED,
                co.sanaa.agent.core.SideEffectState.AWAITING_APPROVAL,
                co.sanaa.agent.core.SideEffectState.APPROVED,
                co.sanaa.agent.core.SideEffectState.FAILED,
            )) {
            // A previous worker claimed this occurrence. Never risk duplicating it.
            scheduleNext(runtime, task)
            return Result.success()
        }
        val claimed = runtime.memory.upsertSideEffectTransaction(
            co.sanaa.agent.core.SideEffectTransaction(
                idempotencyKey = occurrenceKey, capability = "recurring_command",
                target = "schedule:${task.id}", contentHash = co.sanaa.agent.core.ContentHashing.hash(task.taskText),
                approvalId = null, state = co.sanaa.agent.core.SideEffectState.CLAIMED,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                evidence = "Occurrence claimed before execution.",
            ),
        ) || existing?.state == co.sanaa.agent.core.SideEffectState.FAILED
        if (!claimed) {
            scheduleNext(runtime, task)
            return Result.success()
        }
        runtime.memory.transitionSideEffectTransaction(occurrenceKey, co.sanaa.agent.core.SideEffectState.ACTING, "Executing scheduled occurrence once.")
        val outcome = runCatching { CommandExecutor(applicationContext).execute(task.taskText, "", "") }
        val result = outcome.getOrNull()
        val summary = result?.message ?: outcome.exceptionOrNull()?.message ?: "Scheduled task failed without a result."
        val finalState = when {
            result?.success == true -> co.sanaa.agent.core.SideEffectState.VERIFIED
            // An exception may have occurred after an externally visible action.
            outcome.exceptionOrNull() != null -> co.sanaa.agent.core.SideEffectState.UNCERTAIN
            else -> co.sanaa.agent.core.SideEffectState.FAILED
        }
        runtime.memory.transitionSideEffectTransaction(
            occurrenceKey, finalState,
            if (finalState == co.sanaa.agent.core.SideEffectState.VERIFIED) "Occurrence completed and reported." else co.sanaa.agent.core.Redactor.redact(summary),
        )
        scheduleNext(runtime, task, summary)
        return Result.success()
    }

    private fun scheduleNext(runtime: AgentRuntime, task: co.sanaa.agent.core.RecurringTaskRecord, result: String = "Duplicate occurrence suppressed.") {
        val schedule = ScheduleCodec.decode(task.scheduleJson, task.taskText)
        val next = ScheduleCalculator.nextRun(schedule, maxOf(System.currentTimeMillis(), task.nextRunAt))
        runtime.memory.completeRecurringOccurrence(task.id, result, next)
        AgentWorkScheduler.scheduleRecurring(applicationContext, task.id, next)
    }

    companion object { const val TASK_ID = "recurring_task_id" }
}
class FollowUpWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun doWork(): Result = guarded { runtime.followUp.run().success }
}
class HealthWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun doWork(): Result = guarded { runtime.health.run().success }
}

/** Opt-in, read-only shop inspection. It never edits or communicates externally. */
class ReadOnlyShopAuditWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!runtime.config.proactiveReadOnlyAudits) return Result.success()
        val quiet = QuietHoursPolicy.parse(runtime.config.quietHoursStart, runtime.config.quietHoursEnd)
        if (quiet?.contains(LocalTime.now()) == true) return Result.success()
        if (!DeviceAvailabilityGuard.check(applicationContext).available) return Result.success()
        return guarded {
            val alerts = runtime.sokoIntelligence.alertsNeedingAction()
            val services = runtime.sokoIntelligence.auditServices()
            alerts.success && services.success
        }
    }
}
class ConfigSyncWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun doWork(): Result = guarded { runtime.backend.registerAndSync(); true }
}

object AgentWorkScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun scheduleAll(context: Context, @Suppress("UNUSED_PARAMETER") broadcastTime: String) {
        val work = WorkManager.getInstance(context)
        // Cancel legacy API-era and unapproved side-effecting jobs. Screen-first
        // work now runs only from an explicit owner schedule or a future
        // standing policy with an occurrence receipt.
        listOf("agent_config", "agent_conversations", "agent_follow_up", "agent_listings", "agent_morning").forEach(work::cancelUniqueWork)
        work.enqueueUniquePeriodicWork("agent_health", ExistingPeriodicWorkPolicy.UPDATE, periodic<HealthWorker>(30, TimeUnit.MINUTES))
        // Daily commercial cycle: morning plan / during-day recheck / end-of-day brief,
        // phased by the OWNER-configured timezone with per-day occurrence keys.
        work.enqueueUniquePeriodicWork("agent_commercial_cycle", ExistingPeriodicWorkPolicy.UPDATE, periodic<CommercialCycleWorker>(4, TimeUnit.HOURS))
        if (co.sanaa.agent.core.AgentRuntime.get(context).config.proactiveReadOnlyAudits) {
            work.enqueueUniquePeriodicWork("amara_read_only_shop_audit", ExistingPeriodicWorkPolicy.UPDATE, periodic<ReadOnlyShopAuditWorker>(6, TimeUnit.HOURS))
        } else {
            work.cancelUniqueWork("amara_read_only_shop_audit")
        }
    }

    fun scheduleMorning(context: Context, time: String) {
        val request = OneTimeWorkRequestBuilder<MorningBroadcastWorker>().setConstraints(network)
            .setInitialDelay(delayUntil(time), TimeUnit.MILLISECONDS).addTag("morning_broadcast").build()
        WorkManager.getInstance(context).enqueueUniqueWork("agent_morning", ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleRecurring(context: Context, taskId: Long, nextRunAt: Long) {
        val request = OneTimeWorkRequestBuilder<ScheduledCommandWorker>()
            .setInputData(workDataOf(ScheduledCommandWorker.TASK_ID to taskId))
            .setInitialDelay(ScheduleCalculator.delayMillis(nextRunAt), TimeUnit.MILLISECONDS)
            .addTag("amara_recurring")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("amara_recurring_$taskId", ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelRecurring(context: Context, taskId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("amara_recurring_$taskId")
    }

    private inline fun <reified T : ListenableWorker> periodic(interval: Long, unit: TimeUnit, initialDelayMs: Long = 0) =
        PeriodicWorkRequestBuilder<T>(interval, unit).setConstraints(network).setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS).build()

    private fun delayUntil(value: String): Long {
        val targetTime = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(7, 0))
        val now = LocalDateTime.now(); var target = now.toLocalDate().atTime(targetTime)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return NativeBridge.nextScheduledDelay(System.currentTimeMillis(), System.currentTimeMillis() + Duration.between(now, target).toMillis())
    }
}
