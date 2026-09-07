package co.sanaa.agent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import co.sanaa.agent.workers.TikTokGrowthWorker
import co.sanaa.agent.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * BroadcastReceiver to start the TikTok test posting cycle.
 * Trigger via: adb shell am broadcast -a co.sanaa.agent.START_TIKTOK_TEST
 */
class TikTokTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "TikTok test broadcast received")
        
        val work = OneTimeWorkRequestBuilder<TikTokGrowthWorker>()
            .setInitialDelay(1, TimeUnit.MINUTES) // Start in 1 minute
            .addTag(TikTokGrowthWorker.TAG)
            .setInputData(workDataOf("batch_id" to System.currentTimeMillis().toString()))
            .build()
        
        WorkManager.getInstance(context)
            .enqueueUniqueWork(TikTokGrowthWorker.TAG, ExistingWorkPolicy.REPLACE, work)
        
        Log.i(TAG, "TikTok test work scheduled")
    }
    
    companion object {
        const val TAG = "TikTokTestReceiver"
    }
}
