package co.sanaa.agent.modules

import kotlinx.coroutines.sync.withLock
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.api.BackendSync
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.api.ModuleResult
import co.sanaa.agent.core.ModuleStateStore
import co.sanaa.agent.notifications.NotificationReporter
import co.sanaa.agent.services.AgentService
import co.sanaa.agent.core.knowledge.ConnectivityMonitor

class AgentHealthMonitor(
    private val context: Context, private val groq: GroqClient,
    private val backend: BackendSync, private val accessibility: AccessibilityActions,
    private val state: ModuleStateStore, private val reporter: NotificationReporter,
    /** Periodic retention enforcement over the owner-configured window. */
    private val memory: co.sanaa.agent.core.AmaraMemory? = null,
    private val retentionDays: () -> Int = { 90 },
    /** Safe callbacks supplied by the runtime; neither changes system settings. */
    private val selfHeal: () -> Unit = {},
    private val managerReport: (String, String) -> Unit = { _, _ -> },
    private val loopDiagnostics: () -> Map<String, Any> = { emptyMap() },
    private val scheduleBlockers: () -> List<String> = { emptyList() },
    private val reconcile: () -> Unit = {},
    private val governorDashboard: () -> Map<String, Any> = { emptyMap() },
) {
    private val alertDelivery = HealthAlertDelivery(context)
    fun acknowledgeAlerts() {
        val reasons = (snapshot()["blockers"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        alertDelivery.record(reasons)
    }
    private val issueHistory = HealthIssueHistory(context)
    private val sweepMutex = kotlinx.coroutines.sync.Mutex()
    private val backendReadiness = co.sanaa.agent.core.BackendReadiness(context)
    private val healthPrefs = context.getSharedPreferences("operational_health", Context.MODE_PRIVATE)

    /** Owner-facing snapshot. It records observed facts and never implies delivery. */
    fun snapshot(now: Long = System.currentTimeMillis()): Map<String, Any> {
        val network = ConnectivityMonitor(context).getCurrentState()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in setOf(
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL,
        )
        val loop = loopDiagnostics()
        val startedAt = loop["startedAt"] as? Long ?: 0L
        val lastCycleAt = loop["lastCycleAt"] as? Long ?: 0L
        val loopStale = startedAt > 0L && (loop["jobActive"] == false || now - maxOf(lastCycleAt, startedAt) > LOOP_STALE_MS)
        val governor = governorDashboard()
        val openBreakers = (governor["openBreakers"] as? List<*>)
            ?.mapNotNull { it as? String }
            .orEmpty()
        val inboundReserveLeft = (governor["inboundReserveSecondsRemaining"] as? Number)?.toInt() ?: 0
        val screenUsed = (governor["screenSecondsToday"] as? Number)?.toInt() ?: 0
        val screenLimit = ((governor["screenLimitMinutes"] as? Number)?.toInt() ?: 90) * 60
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val backendStatus = backendReadiness.snapshot()
        val blockers = buildList {
            if (backendStatus["state"] in setOf("DNS_FAILED", "UNAVAILABLE", "SERVER_ERROR"))
                add(backendStatus["detail"].toString())
            if (!co.sanaa.agent.core.OwnerPower(context).isOn()) add("Amara is off by owner request; work is held until turned on")
            if (!groq.hasConfiguredKey()) add("No Groq credential is configured; model work is unavailable")
            addAll(scheduleBlockers())
            (loop["blockers"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { issue ->
                val reason = issue["reason"]?.toString().orEmpty()
                if (reason.isNotBlank() && issue["managerReport"] != true) add("${issue["task"] ?: "Work"}: $reason")
            }
            val permissions = co.sanaa.agent.permissions.SelfHealingPermissionManager(context)
            for (check in listOf(permissions.diagnoseOverlay(), permissions.diagnoseBattery(),
                permissions.diagnoseNotificationAccess(), permissions.diagnoseNotificationPermission())) {
                if (check.status != co.sanaa.agent.permissions.PermissionStatus.READY) add("${check.label}: ${check.description}")
            }
            if (!isInstalled("com.soko24.soko_seller_terminal")) add("Soko Seller Terminal is not installed")
            if (!network.validated) add(if (network.state == ConnectivityMonitor.ConnectivityState.OFFLINE) "No internet connection" else "Internet connection is limited")
            if (batteryPercent in 0..LOW_BATTERY_PERCENT) add("Battery is $batteryPercent%; screen work pauses at 15% or lower")
            if (!charging && batteryPercent in 16..20) add("Battery is low; connect power before work pauses at 15%")
            storageBlocker()?.let(::add)
            if (screenLimit - screenUsed < 180) add("Daily screen budget has less than three minutes left; scheduled screen work is waiting for renewal")
            if (governor["state"] == "HALTED") add("Autonomy is halted; owner review is required")
            if (keyguard.isKeyguardLocked) add("Lock screen is showing; scheduled screen work is waiting for unlock")
            if (!accessibility.isAvailable()) add("Phone control is unavailable")
            if (!isServiceRunning()) add("Amara background service is not running")
            if ((loop["lastSummary"] as? String)?.startsWith("Loop recovery waiting") == true) add(loop["lastSummary"].toString())
            if (loopStale) add("Autonomous loop has not completed a cycle recently")
            if (openBreakers.isNotEmpty()) add("Scheduled work is cooling down: ${openBreakers.joinToString()}")
            if (inboundReserveLeft == 0 && screenUsed >= screenLimit)
                add("The inbound reply reserve has been used; new customer replies remain queued until the normal phone-time budget renews")
        }
        return mapOf(
            "backend" to backendStatus, "issueHistory" to issueHistory.rows(), "lastCheckAt" to healthPrefs.getLong("last_at",0L), "checkIntervalMinutes" to 30,
            "healthy" to blockers.isEmpty(), "blockers" to blockers, "network" to network.state.name,
            "transport" to network.transportType, "batteryPercent" to batteryPercent, "charging" to charging,
            "accessibilityBound" to accessibility.isAvailable(), "serviceRunning" to isServiceRunning(),
            "loop" to loop, "governor" to governor, "updatedAt" to now,
        )
    }

    suspend fun run(): ModuleResult = sweepMutex.withLock {
        if (!co.sanaa.agent.core.OwnerPower(context).isOn()) return@withLock ModuleResult(NAME, true, "Amara is off; recovery and manager messages are held")
        backendReadiness.check(co.sanaa.agent.core.SecureConfig(context).backendUrl)
        reconcile()
        val now = System.currentTimeMillis()
        val snapshot = snapshot(now)
        val blockers = (snapshot["blockers"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val checks = linkedMapOf(
            // A validated Android network is the connectivity signal. Do not make a
            // paid model ping every thirty minutes just to test the internet.
            "Internet" to (snapshot["network"] == ConnectivityMonitor.ConnectivityState.ONLINE.name),
            "Groq intelligence configured" to groq.hasConfiguredKey(),
            "Accessibility service" to accessibility.isAvailable(),
            "Soko Seller Terminal installed" to isInstalled("com.soko24.soko_seller_terminal"),
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
        if (checks["Groq intelligence configured"] == false && snapshot["network"] == "ONLINE") {
            runCatching { backend.fetchConfig() }
        }
        if (checks["foreground service"] == false) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java)) }
            kotlinx.coroutines.delay(1_000)
            checks["foreground service"] = isServiceRunning()
        }
        var recoveryRequested = false
        // A stale loop can be safely nudged. Permission, battery, and network
        // blockers are only surfaced; Amara never tries to bypass them.
        if (blockers.any { it.contains("loop", true) || it.contains("background service") } &&
            now - healthPrefs.getLong("last_recovery_at", 0L) >= 5 * 60_000L) {
            healthPrefs.edit().putLong("last_recovery_at", now).apply()
            recoveryRequested = runCatching { selfHeal() }.isSuccess
            co.sanaa.agent.core.EvaluationJournal(context).record("health_recovery_requested")
        }
        issueHistory.observe(blockers, now, recoveryRequested)
        val after=snapshot()
        val currentBlockers=(after["blockers"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        issueHistory.observe(currentBlockers, System.currentTimeMillis())
        checks["Groq intelligence configured"]=groq.hasConfiguredKey()
        checks["foreground service"]=isServiceRunning()
        val failed = (checks.filterValues { !it }.keys + currentBlockers).distinct()
        val failureNote = if (actionFailures24h + brainFailures24h > 0) {
            "; $actionFailures24h action failure(s) and $brainFailures24h model failure(s) recorded in the last 24h"
        } else ""
        val summary = (if (failed.isEmpty()) "All agent health checks are green" else "Needs attention: ${failed.joinToString()}") + failureNote
        if (failed.isEmpty()) state.success(NAME) else state.failure(NAME, summary)
        co.sanaa.agent.core.EvaluationJournal(context).record("operational_health",
            fields = org.json.JSONObject().put("healthy", failed.isEmpty())
                .put("blocker_count", currentBlockers.size).put("network", after["network"])
                .put("battery_percent", after["batteryPercent"])
                .put("loop_stale", currentBlockers.any { it.contains("loop", true) }))
        val operationalSummary = currentBlockers.joinToString("; ").ifBlank { "No operational blockers" }
        healthPrefs.edit().putString("last_summary", operationalSummary).putLong("last_at", System.currentTimeMillis()).apply()
        alertDelivery.reconcile(currentBlockers)
        val newAlerts = alertDelivery.unseen(currentBlockers)
        if (newAlerts.isNotEmpty() && shouldAlert(now = System.currentTimeMillis(), signature = alertSignature(newAlerts))) {
            reporter.report("Amara needs attention", operationalSummary, NotificationReporter.Priority.ACTION_NEEDED)
            managerReport("operational:${now / ALERT_INTERVAL_MS}:${co.sanaa.agent.core.ContentHashing.hash(alertSignature(currentBlockers))}",
                co.sanaa.agent.core.work.ManagerReportWork.blockerMessage(newAlerts, recoveryRequested))
            alertDelivery.record(newAlerts)
        }
        runCatching { backend.log(NAME, "health_check", "device", summary, failed.isEmpty(), error = failed.takeIf { it.isNotEmpty() }?.joinToString()) }
        if (currentBlockers.isEmpty()) healthPrefs.edit().remove("alert_signature").apply()
        ModuleResult(NAME, failed.isEmpty(), summary, failed.takeIf { it.isNotEmpty() }?.joinToString())
    }

    private fun isInstalled(packageName: String): Boolean = context.packageManager.getLaunchIntentForPackage(packageName) != null

    private fun storageBlocker(): String? = runCatching {
        val stats = android.os.StatFs(context.filesDir.absolutePath)
        val total = stats.totalBytes
        val free = stats.availableBytes
        if (total > 0 && free * 100L <= total * STORAGE_FREE_PERCENT) {
            "Device storage is ${free * 100L / total}% free; autonomous work is held at ${STORAGE_FREE_PERCENT}% or less. Free space before continuing."
        } else null
    }.getOrNull()

    // Battery readings and counters change frequently; alert on the condition,
    // not every percentage point. Epoch in the work key permits later reminders.
    private fun alertSignature(blockers: List<String>): String =
        blockers.map { it.replace(Regex("\\d+"), "#") }.sorted().joinToString("|")

    private fun shouldAlert(now: Long, signature: String): Boolean {
        val conditionKey = "alert_condition:" + co.sanaa.agent.core.ContentHashing.hash(signature)
        val lastConditionAt = healthPrefs.getLong(conditionKey, 0L)
        val lastAnyAt = healthPrefs.getLong("alert_at", 0L)
        // Flapping connectivity must not reset another condition's cooldown.
        if (lastConditionAt > 0 && now - lastConditionAt < ALERT_INTERVAL_MS) return false
        if (lastAnyAt > 0 && now - lastAnyAt < 5 * 60_000L) return false
        healthPrefs.edit().putLong(conditionKey, now).putLong("alert_at", now).apply()
        return true
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == AgentService::class.java.name }

    companion object {
        const val NAME = "health_monitor"
        private const val FAILURE_LOOKBACK_MS = 24 * 60 * 60 * 1000L
        private const val LOOP_STALE_MS = 45 * 60 * 1000L
        private const val ALERT_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val LOW_BATTERY_PERCENT = 15
        private const val STORAGE_FREE_PERCENT = 5L
    }
}
