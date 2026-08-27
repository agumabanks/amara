package co.sanaa.agent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import co.sanaa.agent.services.AgentService

/**
 * Restores foreground service + WorkManager scheduling after reboot, package
 * replacement, timezone change, or wall-clock change. Every branch is idempotent:
 * scheduleAll uses ExistingPeriodicWorkPolicy.UPDATE and AgentService start is a no-op
 * when already running. LOCKED_BOOT_COMPLETED is listed for defense-in-depth on
 * devices that deliver it pre-unlock, but the manifest's <intent-filter> still
 * targets BOOT_COMPLETED so the actual first delivery is the post-unlock variant.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        android.util.Log.i("SanaaBoot", "Recovery trigger received: ${intent.action}")
        // The AtomicBoolean lives in the companion object because each broadcast
        // constructs a fresh BootReceiver instance; without process-level state the
        // BOOT_COMPLETED + MY_PACKAGE_REPLACED pair fired after an APK replace
        // would both enqueue an AgentService start and the second one re-runs
        // AgentRuntime.get() on the main thread, retriggering the 5s ANR that puts
        // the accessibility service into Android's "Crashed services" set.
        if (serviceStartEnqueued.compareAndSet(false, true)) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java)) }
                .onFailure {
                    serviceStartEnqueued.set(false)
                    android.util.Log.w("SanaaBoot", "foreground service restart failed: ${it.javaClass.simpleName}")
                }
        }
        if (scheduleEnqueued.compareAndSet(false, true)) {
            // Reboot AND timezone/clock changes reschedule the commercial cycle: the daily
            // plan/brief phases are keyed on the OWNER timezone, so a zone or time change
            // must re-run scheduling to stay phase-correct.
            runCatching { co.sanaa.agent.workers.AgentWorkScheduler.scheduleAll(context, "") }
                .onFailure {
                    scheduleEnqueued.set(false)
                    android.util.Log.w("SanaaBoot", "reschedule failed: ${it.javaClass.simpleName}")
                }
        }
    }

    companion object {
        internal val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.TIME_SET",
        )
        private val serviceStartEnqueued = java.util.concurrent.atomic.AtomicBoolean(false)
        private val scheduleEnqueued = java.util.concurrent.atomic.AtomicBoolean(false)

        @androidx.annotation.VisibleForTesting
        internal fun resetForTest() {
            serviceStartEnqueued.set(false)
            scheduleEnqueued.set(false)
        }
    }
}
