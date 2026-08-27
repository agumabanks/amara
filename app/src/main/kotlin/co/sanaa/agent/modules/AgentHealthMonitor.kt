package co.sanaa.agent.modules

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.api.BackendSync
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.api.ModuleResult
import co.sanaa.agent.core.ModuleStateStore
import co.sanaa.agent.notifications.NotificationReporter
import co.sanaa.agent.services.AgentService

class AgentHealthMonitor(
    private val context: Context, private val groq: GroqClient,
    private val backend: BackendSync, private val accessibility: AccessibilityActions,
    private val state: ModuleStateStore, private val reporter: NotificationReporter,
    /** Periodic retention enforcement over the owner-configured window. */
    private val memory: co.sanaa.agent.core.AmaraMemory? = null,
    private val retentionDays: () -> Int = { 90 },
) {
    suspend fun run(): ModuleResult {
        val checks = linkedMapOf(
            "Groq intelligence" to groq.ping(),
            "Accessibility service" to accessibility.isAvailable(),
            "Soko Seller Terminal installed" to isInstalled("com.soko24.soko_seller_terminal"),
            "Soko Buyer installed" to isInstalled("com.sanaa.soko24u.buyer"),
            "foreground service" to isServiceRunning(),
        )
        // Retention policy enforcement: records beyond the owner's window are pruned
        // on every health sweep (production call site for pruneExpiredData).
        runCatching { memory?.pruneExpiredData(retentionDays()) }
        // Durable-failure observability: send/model failure receipts are surfaced to
        // the owner every sweep instead of sitting unseen in SQLite (production call
        // sites for recentFailures and recentBrainFailures). Counts inform the report;
        // they do not by themselves flip the verdict because each module already owns
        // its own truthful success/failure state.
        val dayAgo = System.currentTimeMillis() - FAILURE_LOOKBACK_MS
        val actionFailures24h = runCatching { memory?.recentFailures(50).orEmpty().count { it.createdAt >= dayAgo } }.getOrDefault(0)
        val brainFailures24h = runCatching { memory?.recentBrainFailures(50).orEmpty().count { it.createdAt >= dayAgo } }.getOrDefault(0)
        if (checks["Groq intelligence"] == false) {
            runCatching { backend.fetchConfig() }
        }
        if (checks["foreground service"] == false) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java)) }
            kotlinx.coroutines.delay(1_000)
            checks["foreground service"] = isServiceRunning()
        }
        val failed = checks.filterValues { !it }.keys
        val failureNote = if (actionFailures24h + brainFailures24h > 0) {
            "; $actionFailures24h action failure(s) and $brainFailures24h model failure(s) recorded in the last 24h"
        } else ""
        val summary = (if (failed.isEmpty()) "All agent health checks are green" else "Needs attention: ${failed.joinToString()}") + failureNote
        if (failed.isEmpty()) state.success(NAME) else {
            state.failure(NAME, summary)
            if (state.errors(NAME) >= 2) reporter.report("Hey — I need your attention", "$summary. Open Sanaa Agent to fix it.", NotificationReporter.Priority.ACTION_NEEDED)
        }
        runCatching { backend.log(NAME, "health_check", "device", summary, failed.isEmpty(), error = failed.takeIf { it.isNotEmpty() }?.joinToString()) }
        return ModuleResult(NAME, failed.isEmpty(), summary, failed.takeIf { it.isNotEmpty() }?.joinToString())
    }

    private fun isInstalled(packageName: String): Boolean = context.packageManager.getLaunchIntentForPackage(packageName) != null

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == AgentService::class.java.name }

    companion object {
        const val NAME = "health_monitor"
        private const val FAILURE_LOOKBACK_MS = 24 * 60 * 60 * 1000L
    }
}
