package co.sanaa.agent.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.work.WakeReason

/**
 * WorkManager is an alarm clock, not an execution authority. Autonomous work is
 * discovered, admitted and executed by AmaraWorkLoop after this pulse.
 */
abstract class WorkLoopWakeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    protected abstract val wakeReason: WakeReason

    final override suspend fun doWork(): Result = runCatching {
        val runtime = AgentRuntime.get(applicationContext).awaitReady()
        runtime.workLoop.wake(wakeReason)
        Result.success()
    }.getOrElse { Result.retry() }
}

class ProactiveFollowUpWorker(context: Context, params: WorkerParameters) :
    WorkLoopWakeWorker(context, params) {
    override val wakeReason: WakeReason = WakeReason.ExternalEvent("follow_up_due", "")
}

class DailyBriefingWorker(context: Context, params: WorkerParameters) :
    WorkLoopWakeWorker(context, params) {
    override val wakeReason: WakeReason = WakeReason.ExternalEvent("daily_briefing_due", "")
}

class AmaraWorkPulseWorker(context: Context, params: WorkerParameters) :
    WorkLoopWakeWorker(context, params) {
    override val wakeReason: WakeReason = WakeReason.ScheduledAlarm
}
