package co.sanaa.agent.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import co.sanaa.agent.MainActivity
import co.sanaa.agent.R
import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.RuntimePhase
import co.sanaa.agent.core.RuntimeStatusBus
import co.sanaa.agent.core.WorkStatus
import co.sanaa.agent.notifications.NotificationReporter
import co.sanaa.agent.overlay.OverlayService
import co.sanaa.agent.permissions.PermissionStatus
import co.sanaa.agent.permissions.SelfHealingPermissionManager
import co.sanaa.agent.workers.AgentWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Agent status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps Amara available for scheduled business work"
            setShowBadge(false)
        })
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle("Amara is ready")
            .setContentText("Watching for the next piece of business.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        startForegroundCompat(notification)
        OverlayService.start(applicationContext)
        scope.launch {
            // Durable recovery/migrations can be substantial on a long-lived
            // employee install. Constructing AgentRuntime in Service.onCreate
            // blocked the process main thread, delaying Flutter's first frame
            // and causing Android to mark the accessibility service crashed.
            val runtime = AgentRuntime.get(applicationContext).awaitReady()
            restoreDurableRuntimeStatus(runtime)
            AgentWorkScheduler.scheduleAll(applicationContext, runtime.config.broadcastTime)
            runCatching { runtime.backend.registerAndSync() }
                .onFailure { runtime.reporter.report("I couldn't sync my settings", "I'll retry shortly. ${it.message}", NotificationReporter.Priority.ACTION_NEEDED) }
            accessibilityWatchdog(runtime)
        }
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        captureLastForegroundForTest(notification)
    }

    @androidx.annotation.VisibleForTesting
    internal fun lastForegroundNotificationForTest(): android.app.Notification? = lastForegroundNotification

    @Volatile private var lastForegroundNotification: android.app.Notification? = null
    private fun captureLastForegroundForTest(notification: android.app.Notification) {
        lastForegroundNotification = notification
    }

    private suspend fun accessibilityWatchdog(runtime: co.sanaa.agent.core.AgentRuntime) {
        val manager = SelfHealingPermissionManager(applicationContext)
        var previouslyReady: Boolean? = null
        var lastCrashedReportAt = 0L
        while (true) {
            val diagnosis = manager.diagnoseAccessibility()
            val bound = AccessibilityAgentService.instance != null
            val ready = diagnosis.status == PermissionStatus.READY && bound
            runtime.state.putBool("accessibility_bound", bound)
            if (!ready) {
                RuntimeStatusBus.report(
                    WorkStatus(
                        workerId = PERMISSION_WORKER_ID,
                        targetApp = "Android settings",
                        taskLabel = "Restore phone control",
                        phase = RuntimePhase.BLOCKED,
                        stepIndex = 0,
                        stepCount = 1,
                        retryCount = 0,
                        blocker = "Accessibility",
                    ),
                )
            } else {
                RuntimeStatusBus.clear(PERMISSION_WORKER_ID)
            }
            // Notify on the transition, not every watchdog tick.
            if (!ready && previouslyReady != false) {
                runtime.reporter.report(
                    "Accessibility disconnected",
                    "Amara can't interact with the phone. Open settings to re-enable.",
                    NotificationReporter.Priority.ACTION_NEEDED,
                )
            }
            // Crashed-state detection: the user previously had this service bound
            // (or explicitly enabled) and the OS reset the flag. Surface a
            // non-dismissable owner-action card every 6h, never on a force-stop
            // path, pointing at the OPPO autostart screen first and the standard
            // Accessibility screen as the always-available fallback.
            if (diagnosis.status == PermissionStatus.BLOCKED) {
                val now = System.currentTimeMillis()
                if (now - lastCrashedReportAt > 6 * 60 * 60 * 1000L) {
                    runtime.reporter.report(
                        "Re-enable Sanaa Agent",
                        "ColorOS disabled the accessibility service. Open Settings → Apps → App management → Sanaa Agent → Battery usage → Allow autostart, then re-enable Accessibility for Sanaa Agent.",
                        NotificationReporter.Priority.ACTION_NEEDED,
                    )
                    lastCrashedReportAt = now
                }
            }
            previouslyReady = ready
            delay(30_000)
        }
    }

    /** Rehydrates truthful terminal state after install/restart; moving work is not resumed here. */
    private fun restoreDurableRuntimeStatus(runtime: AgentRuntime) {
        val storedPhase = runtime.state.string(co.sanaa.agent.core.AutonomyController.PHASE_KEY, "idle")
        val detail = runtime.state.string(co.sanaa.agent.core.AutonomyController.DETAIL_KEY, "Ready for the next thing")
        val phase = durableRuntimePhase(storedPhase, detail)
        if (phase == RuntimePhase.IDLE) {
            RuntimeStatusBus.clear(co.sanaa.agent.core.AutonomyController.RUNTIME_WORKER_ID)
            return
        }
        RuntimeStatusBus.report(
            WorkStatus(
                workerId = co.sanaa.agent.core.AutonomyController.RUNTIME_WORKER_ID,
                targetApp = null,
                taskLabel = "Owner task",
                phase = phase,
                stepIndex = 0,
                stepCount = 0,
                retryCount = 0,
                blocker = detail.takeIf { phase == RuntimePhase.BLOCKED || phase == RuntimePhase.FAILED },
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val CHANNEL = "agent_status"
        const val NOTIFICATION_ID = 1001
        internal const val PERMISSION_WORKER_ID = "permission-accessibility"

        internal fun durableRuntimePhase(storedPhase: String, detail: String): RuntimePhase {
            val normalizedDetail = detail.lowercase()
            return when {
                storedPhase == "blocked" || storedPhase == "needs_owner" -> RuntimePhase.BLOCKED
                storedPhase == "failed" || storedPhase == "unsupported" -> RuntimePhase.FAILED
                storedPhase == "complete" || storedPhase == "completed" -> RuntimePhase.COMPLETE
                // Compatibility for releases that wrote `idle`/`report` even when
                // their owner-facing detail plainly described a terminal blocker.
                listOf("wait", "attention", "stopped", "couldn’t", "couldn't", "unavailable")
                    .any(normalizedDetail::contains) -> RuntimePhase.BLOCKED
                else -> RuntimePhase.IDLE
            }
        }
    }
}
