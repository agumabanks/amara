package co.sanaa.agent.core

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

object DeviceActivityMonitor {
    private val automationDepth = AtomicInteger(0)
    @Volatile private var lastExternalInteractionAt: Long = -1
    @Volatile private var automationEndedAtUptime: Long = -1

    fun beginAutomation() { automationDepth.incrementAndGet() }
    fun endAutomation(atUptimeMillis: Long = SystemClock.uptimeMillis()) {
        // Record before releasing the boundary: queued accessibility events may
        // arrive after the action coroutine has finished.
        automationEndedAtUptime = atUptimeMillis
        automationDepth.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
    }

    fun observe(
        eventType: Int,
        atMillis: Long = System.currentTimeMillis(),
        eventUptimeMillis: Long = SystemClock.uptimeMillis(),
    ) {
        if (automationDepth.get() > 0) return
        if (eventUptimeMillis <= automationEndedAtUptime) return
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastExternalInteractionAt = atMillis
        }
    }

    fun isUserLikelyActive(nowMillis: Long = System.currentTimeMillis(), quietWindowMillis: Long = 30_000): Boolean =
        lastExternalInteractionAt >= 0 && nowMillis - lastExternalInteractionAt in 0 until quietWindowMillis

    fun resetForTest() {
        lastExternalInteractionAt = -1
        automationEndedAtUptime = -1
        automationDepth.set(0)
    }
}

enum class AvailabilityBlocker { NONE, OWNER_ACTIVE, SCREEN_OFF, SECURE_KEYGUARD, NONSECURE_KEYGUARD }

/** Compat alias for pre-existing references to the blocker enum. */
typealias Blocker = AvailabilityBlocker

data class DeviceAvailability(
    val available: Boolean,
    val blocker: AvailabilityBlocker,
    val reason: String,
) {
    /** Legacy two-field constructor kept for existing callers. */
    constructor(available: Boolean, reason: String) : this(available, AvailabilityBlocker.NONE, reason)
}

/**
 * Decides whether scheduled work may take the device. A SECURE keyguard can never
 * yield an "available" verdict: no credential entry is ever attempted or stored —
 * the outcome is BLOCKED plus owner notification, nothing else.
 */
object DeviceAvailabilityGuard {
    const val SECURE_KEYGUARD_REASON = "Owner unlock required; Amara will not attempt credential entry"
    private const val FINAL_CHECK_GAP_MS = 50L
    private const val SYNC_WAKE_BUDGET_MS = 600L
    private const val SYNC_POLL_STEP_MS = 100L
    private const val OWNER_ACTIVE_REASON = "The owner appears to be using the phone."
    private const val SCREEN_OFF_UNWAKEABLE_REASON = "The phone screen did not come on for scheduled work."

    /**
     * Suspend guard used before any foreground work. Sequence: owner-active first;
     * wake + poll interactivity; then the keyguard ladder; then a FINAL double
     * observation immediately before reporting available=true.
     */
    suspend fun ensureAvailable(
        context: Context,
        sleeper: suspend (Long) -> Unit = { delay(it) },
        pollAttempts: Int = 20,
        pollIntervalMs: Long = 250,
    ): DeviceAvailability {
        if (DeviceActivityMonitor.isUserLikelyActive()) {
            return DeviceAvailability(false, AvailabilityBlocker.OWNER_ACTIVE, OWNER_ACTIVE_REASON)
        }
        val keyguard = keyguardManager(context)
        if (!ScreenController.isScreenOn(context)) {
            ScreenController.wakeScreen(context)
            var attempts = 0
            while (!ScreenController.isScreenOn(context) && attempts < pollAttempts.coerceAtLeast(0)) {
                sleeper(pollIntervalMs)
                attempts++
            }
            if (!ScreenController.isScreenOn(context)) {
                return DeviceAvailability(false, AvailabilityBlocker.SCREEN_OFF, SCREEN_OFF_UNWAKEABLE_REASON)
            }
        }
        if (keyguard.isKeyguardLocked) {
            if (keyguard.isKeyguardSecure) {
                return DeviceAvailability(false, AvailabilityBlocker.SECURE_KEYGUARD, SECURE_KEYGUARD_REASON)
            }
            // A swipe-only surface is not a credential boundary. ColorOS can keep
            // isKeyguardLocked=true in this process even after dumpsys reports
            // deviceLocked=0; allow foreground work through the activity's existing
            // SHOW_WHEN_LOCKED/DISMISS_KEYGUARD flags.
        }
        // FINAL observation: availability must hold TWICE, separated by a tiny gap,
        // immediately before reporting available=true.
        val interactiveOnce = ScreenController.isScreenOn(context)
        val lockedOnce = keyguard.isKeyguardLocked
        if (!interactiveOnce) {
            return DeviceAvailability(false, AvailabilityBlocker.SCREEN_OFF, "The phone screen turned off again before work could start.")
        }
        if (lockedOnce && keyguard.isKeyguardSecure) return relockedResult(keyguard)
        sleeper(FINAL_CHECK_GAP_MS)
        val interactiveTwice = ScreenController.isScreenOn(context)
        val lockedTwice = keyguard.isKeyguardLocked
        return when {
            interactiveTwice && (!lockedTwice || !keyguard.isKeyguardSecure) ->
                DeviceAvailability(true, AvailabilityBlocker.NONE, "The phone is awake and unlocked for scheduled work.")
            !interactiveTwice ->
                DeviceAvailability(false, AvailabilityBlocker.SCREEN_OFF, "The phone screen turned off again before work could start.")
            else -> relockedResult(keyguard)
        }
    }

    /** Honest one-shot read of the current state without waking anything. */
    fun check(context: Context): DeviceAvailability {
        val keyguard = keyguardManager(context)
        return when {
            DeviceActivityMonitor.isUserLikelyActive() ->
                DeviceAvailability(false, AvailabilityBlocker.OWNER_ACTIVE, OWNER_ACTIVE_REASON)
            !ScreenController.isScreenOn(context) ->
                DeviceAvailability(false, AvailabilityBlocker.SCREEN_OFF, "The phone screen is off.")
            keyguard.isKeyguardLocked && keyguard.isKeyguardSecure ->
                DeviceAvailability(false, AvailabilityBlocker.SECURE_KEYGUARD, SECURE_KEYGUARD_REASON)
            keyguard.isKeyguardLocked ->
                DeviceAvailability(false, AvailabilityBlocker.NONSECURE_KEYGUARD, "A lock screen without owner credentials is showing.")
            else -> DeviceAvailability(true, AvailabilityBlocker.NONE, "The phone is available for scheduled work.")
        }
    }

    /**
     * Legacy synchronous entry point kept for worker compatibility. Wakes the device,
     * waits a bounded <=600ms interval, then reports the HONEST observed result — it
     * never claims availability that was not re-verified after waking.
     */
    fun checkOrWake(context: Context): DeviceAvailability {
        if (DeviceActivityMonitor.isUserLikelyActive()) {
            return DeviceAvailability(false, AvailabilityBlocker.OWNER_ACTIVE, OWNER_ACTIVE_REASON)
        }
        val keyguard = keyguardManager(context)
        var waited = 0L
        if (!ScreenController.isScreenOn(context)) {
            ScreenController.wakeScreen(context)
            while (!ScreenController.isScreenOn(context) && waited < SYNC_WAKE_BUDGET_MS) {
                boundedSleep(SYNC_POLL_STEP_MS)
                waited += SYNC_POLL_STEP_MS
            }
            if (!ScreenController.isScreenOn(context)) {
                return DeviceAvailability(false, AvailabilityBlocker.SCREEN_OFF, SCREEN_OFF_UNWAKEABLE_REASON)
            }
        }
        if (keyguard.isKeyguardLocked) {
            if (keyguard.isKeyguardSecure) {
                return DeviceAvailability(false, AvailabilityBlocker.SECURE_KEYGUARD, SECURE_KEYGUARD_REASON)
            }
            while (keyguard.isKeyguardLocked && waited < SYNC_WAKE_BUDGET_MS) {
                boundedSleep(SYNC_POLL_STEP_MS)
                waited += SYNC_POLL_STEP_MS
            }
            if (keyguard.isKeyguardLocked) {
                return DeviceAvailability(
                    false,
                    AvailabilityBlocker.NONSECURE_KEYGUARD,
                    "A lock screen without owner credentials is still showing and did not dismiss on its own.",
                )
            }
        }
        val interactiveNow = ScreenController.isScreenOn(context)
        val lockedNow = keyguard.isKeyguardLocked
        return when {
            interactiveNow && !lockedNow ->
                DeviceAvailability(true, AvailabilityBlocker.NONE, "The phone woke up and is unlocked for scheduled work.")
            lockedNow -> relockedResult(keyguard)
            else -> DeviceAvailability(false, AvailabilityBlocker.SCREEN_OFF, "The phone did not stay awake after waking.")
        }
    }

    private fun relockedResult(keyguard: KeyguardManager): DeviceAvailability =
        if (keyguard.isKeyguardSecure) {
            DeviceAvailability(false, AvailabilityBlocker.SECURE_KEYGUARD, SECURE_KEYGUARD_REASON)
        } else {
            DeviceAvailability(
                false,
                AvailabilityBlocker.NONSECURE_KEYGUARD,
                "A lock screen appeared again before work could start.",
            )
        }

    private fun boundedSleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun keyguardManager(context: Context): KeyguardManager =
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
}
