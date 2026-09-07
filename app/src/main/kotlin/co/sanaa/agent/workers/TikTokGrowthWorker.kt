package co.sanaa.agent.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.work.WakeReason
import java.util.concurrent.TimeUnit

/** A standing-policy pulse. Publishing remains inside the governed work loop. */
class TikTokGrowthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val TAG = "tiktok_growth_test"
        const val INTERVAL_MINUTES = 10L
        const val TEST_DURATION_HOURS = 10L
    }

    override suspend fun doWork(): Result = runCatching {
        val runtime = AgentRuntime.get(applicationContext).awaitReady()
        if (!runtime.config.tikTokTestMode) return Result.success()
        runtime.workLoop.wake(WakeReason.ExternalEvent("tiktok_due", ""))
        scheduleNext(runtime.config.tikTokPostIntervalMinutes)
        Result.success()
    }.getOrElse { Result.retry() }

    private fun scheduleNext(intervalMinutes: Long) {
        val request = OneTimeWorkRequestBuilder<TikTokGrowthWorker>()
            .setInitialDelay(intervalMinutes.coerceIn(10, 480), TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(TAG, androidx.work.ExistingWorkPolicy.REPLACE, request)
    }
}
