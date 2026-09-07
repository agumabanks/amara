package co.sanaa.agent.core.work

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import co.sanaa.agent.core.DeviceActivityMonitor
import java.time.Duration
import java.time.LocalTime

/**
 * Monitors owner presence using layered detection (cheapest first).
 * 
 * Layer 1: screen on + unlocked → owner holding phone
 * Locked or non-interactive means the owner is not actively using the phone.
 * Unlocked + interactive means Amara must yield immediately.
 */
class OwnerPresenceMonitor(
    private val context: Context,
    private val foregroundPackage: () -> String? = { null },
) {

    enum class OwnerPresence { ACTIVE, IDLE_SHORT, IDLE_LONG, ASLEEP }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private var automationForeground: String? = null
    private var observedForeground: String? = null
    private var foregroundChangedAt: Long = 0L

    fun rememberAutomationForeground() {
        automationForeground = foregroundPackage()
    }

    /**
     * Check if the owner is currently active.
     */
    fun isOwnerActive(): Boolean {
        val recentInteraction = DeviceActivityMonitor.isUserLikelyActive()
        if (recentInteraction) automationForeground = null
        val currentPackage = foregroundPackage()
        val now = android.os.SystemClock.elapsedRealtime()
        if (currentPackage != observedForeground) {
            observedForeground = currentPackage
            foregroundChangedAt = now
        }
        return screenIndicatesOwnerActive(
            interactive = powerManager.isInteractive,
            deviceLocked = keyguardManager.isDeviceLocked,
            recentHumanInteraction = recentInteraction,
            foregroundPackage = currentPackage,
            agentPackage = context.packageName,
            automationOwnsForeground = currentPackage != null && currentPackage == automationForeground,
            foregroundIdle = currentPackage != null && now - foregroundChangedAt >= 30_000L,
        )
    }

    /**
     * Get current owner presence state.
     */
    fun getPresence(): OwnerPresence {
        if (isOwnerActive()) return OwnerPresence.ACTIVE

        // Check if asleep (after 22:00 and no activity for 45+ min)
        val hour = java.time.LocalTime.now().hour
        if (hour >= 22 || hour < 6) {
            val lastActive = getLastActiveTime()
            if (lastActive != null && Duration.between(
                    java.time.Instant.ofEpochMilli(lastActive).atZone(java.time.ZoneId.systemDefault()).toLocalTime(),
                    java.time.Instant.ofEpochMilli(System.currentTimeMillis()).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                ).toMinutes() > 45
            ) {
                return OwnerPresence.ASLEEP
            }
        }

        return OwnerPresence.IDLE_LONG
    }

    private fun getLastActiveTime(): Long? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY,
            now - 60 * 60 * 1000, // last hour
            now
        )
        return stats?.maxByOrNull { it.lastTimeUsed }?.lastTimeUsed
    }

    companion object {
        /** Pure policy used by device and regression certification. */
        internal fun screenIndicatesOwnerActive(
            interactive: Boolean,
            deviceLocked: Boolean,
            recentHumanInteraction: Boolean = false,
            foregroundPackage: String? = null,
            agentPackage: String = "co.sanaa.agent",
            automationOwnsForeground: Boolean = false,
            foregroundIdle: Boolean = false,
        ): Boolean {
            if (!interactive || deviceLocked) return false
            if (recentHumanInteraction) return true
            if (automationOwnsForeground) return false
            if (foregroundIdle && !foregroundPackage.isNullOrBlank()) return false
            // An awake screen showing Amara is commonly the result of Amara's own
            // scheduled wake. Any other or unreadable foreground stays conservative.
            return foregroundPackage.isNullOrBlank() || foregroundPackage != agentPackage
        }
    }
}

/**
 * Manages phone time budget for Amara's autonomous work.
 * 
 * Default: 90 min/day, spent in discrete sessions based on time of day.
 */
class PhoneTimeBudgeter(
    private val context: Context,
    private val config: () -> Int = { 90 }, // max screen minutes per day
) {

    data class SessionGrant(
        val maxDurationSeconds: Int,
        val hardStopAt: Long,
        val grantId: String,
    )

    /**
     * Request a session grant based on current conditions.
     * Returns null if no session should be granted.
     */
    fun requestSession(snapshot: WorldSnapshot, bestItem: WorkItem): SessionGrant? {
        val remainingSeconds = ((config() - snapshot.screenMinutesUsedToday) * 60).coerceAtLeast(0)

        // Base slice by time of day
        val hour = snapshot.currentHour
        val baseSliceSeconds = when {
            bestItem.payload.optBoolean("owner_always_on", false) -> 480
            snapshot.quietHours -> if (Capability.SCREEN !in bestItem.requires && Capability.CONSENT_TIER_2 !in bestItem.requires) 300 else 0
            hour in 6..8 -> 900      // 15 min morning
            hour in 9..16 -> 480     // 8 min steady-state
            hour in 17..20 -> 720    // 12 min evening
            else -> 480 // Owner-configured quiet hours, not a hidden night window.
        }

        // Apply thermal cap
        val ownerAuthorizedOverride = bestItem.payload.optBoolean("owner_canary", false) ||
            bestItem.payload.optBoolean("owner_command", false)
        val thermalMultiplier = if (ownerAuthorizedOverride) 1.0 else when (snapshot.thermalState) {
            ThermalState.NORMAL -> 1.0
            ThermalState.WARM -> 0.5
            ThermalState.HOT -> 0.0
        }

        // Apply battery cap
        val batteryMultiplier = when {
            snapshot.batteryPercent > 50 -> 1.0
            snapshot.batteryPercent > 20 -> 0.5
            snapshot.batteryPercent > 10 && snapshot.batteryPercent <= 20 -> 0.0
            else -> 0.0
        }

        val sessionLengthSeconds = minOf(
            (baseSliceSeconds * thermalMultiplier * batteryMultiplier).toInt(),
            remainingSeconds
        )

        // Minimum 3 minutes to bother
        if (sessionLengthSeconds < 180) return null

        // Don't grant if owner is active
        if (snapshot.ownerActive) return null

        return SessionGrant(
            maxDurationSeconds = sessionLengthSeconds,
            hardStopAt = System.currentTimeMillis() + (sessionLengthSeconds * 1000L),
            grantId = "grant-${System.currentTimeMillis()}"
        )
    }

    /**
     * Get remaining minutes in the daily budget.
     */
    fun remainingMinutes(screenSecondsUsedToday: Int): Int {
        val dailyBudgetSeconds = config() * 60
        return ((dailyBudgetSeconds - screenSecondsUsedToday) / 60).coerceAtLeast(0)
    }
}
