package co.sanaa.agent.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import co.sanaa.agent.R
import co.sanaa.agent.BuildConfig
import co.sanaa.agent.core.AgentRuntime
import kotlinx.coroutines.*

/**
 * TikTokTestService — foreground service that posts TikTok ads every 10 minutes.
 * Runs for 10 hours then stops automatically.
 * Uses Soko Terminal app directly via accessibility (not API).
 */
class TikTokTestService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var testJob: Job? = null
    
    companion object {
        const val TAG = "TikTokTestService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "tiktok_test_channel"
        const val INTERVAL_MINUTES = 10L
        const val TEST_DURATION_HOURS = 10L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BuildConfig.DEBUG) {
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(TAG, "TikTok test service started")
        startTestLoop()
        return START_STICKY
    }

    private fun startTestLoop() {
        testJob = scope.launch {
            val runtime = AgentRuntime.get(applicationContext).awaitReady()
            val endTime = System.currentTimeMillis() + (TEST_DURATION_HOURS * 60 * 60 * 1000L)
            while (System.currentTimeMillis() < endTime && isActive) {
                if (!runtime.config.tikTokTestMode) break
                runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("tiktok_due", ""))
                delay(runtime.config.tikTokPostIntervalMinutes.coerceIn(10, 480) * 60_000L)
            }
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TikTok Test",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "TikTok test posting service"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TikTok Test Running")
            .setContentText("Posting every 10 minutes for 10 hours")
            .setSmallIcon(R.drawable.ic_agent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        testJob?.cancel()
        scope.cancel()
        Log.i(TAG, "TikTok test service destroyed")
    }
}
