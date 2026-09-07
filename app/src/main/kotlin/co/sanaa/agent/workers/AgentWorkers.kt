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
    override suspend fun doWork(): Result {
        runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("morning_broadcast_due", ""))
        AgentWorkScheduler.scheduleMorning(applicationContext, runtime.config.broadcastTime)
        return Result.success()
    }
}
class ConversationPollWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun doWork(): Result {
        runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("conversation_poll_due", ""))
        return Result.success()
    }
}
class ListingIntelligenceWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun doWork(): Result {
        runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("listing_audit_due", ""))
        return Result.success()
    }
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
        val runtime = AgentRuntime.get(applicationContext).awaitReady()
        val taskId = inputData.getLong(TASK_ID, -1L)
        val task = runtime.memory.recurringTask(taskId) ?: return Result.failure()
        if (!task.enabled) return Result.success()
        val occurrenceKey = "recurring:${task.id}:${task.nextRunAt}"
        runtime.workQueue.offer(co.sanaa.agent.core.work.WorkItem(
            dedupeKey = occurrenceKey,
            domain = co.sanaa.agent.core.work.Domain.INTERNAL,
            kind = co.sanaa.agent.core.work.WorkKind.OWNER_SCHEDULED_COMMAND,
            payload = org.json.JSONObject().put("task_id", task.id).put("task_text", task.taskText),
            baseValueKes = co.sanaa.agent.core.work.WorkScorer.defaultBaseValue(co.sanaa.agent.core.work.WorkKind.OWNER_SCHEDULED_COMMAND),
            urgencyHalfLifeHours = co.sanaa.agent.core.work.WorkScorer.defaultHalfLife(co.sanaa.agent.core.work.WorkKind.OWNER_SCHEDULED_COMMAND),
            estimatedScreenSeconds = co.sanaa.agent.core.work.WorkScorer.defaultScreenSeconds(co.sanaa.agent.core.work.WorkKind.OWNER_SCHEDULED_COMMAND),
            requires = setOf(co.sanaa.agent.core.work.Capability.SCREEN, co.sanaa.agent.core.work.Capability.NETWORK, co.sanaa.agent.core.work.Capability.GROQ),
            riskTier = co.sanaa.agent.core.work.WorkScorer.defaultRiskTier(co.sanaa.agent.core.work.WorkKind.OWNER_SCHEDULED_COMMAND),
        ))
        runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("owner_schedule_due", occurrenceKey))
        return Result.success()
    }

    companion object { const val TASK_ID = "recurring_task_id" }
}
class FollowUpWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun doWork(): Result {
        runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("follow_up_due", ""))
        return Result.success()
    }
}
class HealthWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun doWork(): Result {
        runtime.awaitReady().workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("health_check_due", ""))
        return Result.success()
    }
}

/** Opt-in, read-only shop inspection. It never edits or communicates externally. */
class ReadOnlyShopAuditWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!runtime.config.proactiveReadOnlyAudits) return Result.success()
        val quiet = QuietHoursPolicy.parse(runtime.config.quietHoursStart, runtime.config.quietHoursEnd)
        if (quiet?.contains(LocalTime.now()) == true) return Result.success()
        runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("soko_audit_due", ""))
        return Result.success()
    }
}
class ConfigSyncWorker(context: Context, params: WorkerParameters) : AgentWorker(context, params) {
    override suspend fun doWork(): Result {
        runtime.awaitReady().workLoop.wake(
            co.sanaa.agent.core.work.WakeReason.ExternalEvent("config_sync_due", ""),
        )
        return Result.success()
    }
}

object AgentWorkScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun scheduleAll(context: Context, @Suppress("UNUSED_PARAMETER") broadcastTime: String) {
        val work = WorkManager.getInstance(context)
        // Cancel legacy API-era and unapproved side-effecting jobs. Screen-first
        // work now runs only from an explicit owner schedule or a future
        // standing policy with an occurrence receipt.
        listOf("agent_conversations", "agent_follow_up", "agent_listings", "agent_morning").forEach(work::cancelUniqueWork)
        work.enqueueUniquePeriodicWork("agent_config", ExistingPeriodicWorkPolicy.UPDATE, periodic<ConfigSyncWorker>(12, TimeUnit.HOURS))
        work.enqueueUniquePeriodicWork("agent_health", ExistingPeriodicWorkPolicy.UPDATE, periodic<HealthWorker>(30, TimeUnit.MINUTES))
        work.enqueueUniquePeriodicWork("amara_work_loop_pulse", ExistingPeriodicWorkPolicy.UPDATE, periodic<AmaraWorkPulseWorker>(15, TimeUnit.MINUTES))
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

    /**
     * Schedules TikTok test posting every 10 minutes.
     */
    fun scheduleTikTokTest(context: Context) {
        val work = OneTimeWorkRequestBuilder<co.sanaa.agent.workers.TikTokGrowthWorker>()
            .setInitialDelay(1, TimeUnit.MINUTES)
            .addTag(co.sanaa.agent.workers.TikTokGrowthWorker.TAG)
            .setInputData(workDataOf("batch_id" to System.currentTimeMillis().toString()))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(co.sanaa.agent.workers.TikTokGrowthWorker.TAG, ExistingWorkPolicy.REPLACE, work)
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
