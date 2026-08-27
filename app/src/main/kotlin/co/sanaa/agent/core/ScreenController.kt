package co.sanaa.agent.core

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager

object ScreenController {
    private const val TAG = "SanaaScreen"
    private const val WAKE_LOCK_TAG = "SanaaAgent:wake"
    private const val WAKE_LOCK_TIMEOUT_MS = 10_000L

    /** Test seam: when non-null it stands in for the device-reported interactivity. */
    @Volatile internal var screenOnOverride: Boolean? = null

    /** Turns the screen on only when it is currently off. Best-effort; never throws. */
    fun turnScreenOnIfOff(context: Context) {
        try {
            if (isScreenOn(context)) return
            wakeScreen(context)
        } catch (e: RuntimeException) {
            Log.w(TAG, "turnScreenOnIfOff failed: ${e.javaClass.simpleName}")
        }
    }

    fun wakeScreen(context: Context) {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power == null) {
            Log.w(TAG, "wakeScreen: PowerManager unavailable")
            return
        }
        @Suppress("DEPRECATION")
        val wakeLock = try {
            power.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                WAKE_LOCK_TAG,
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "wakeScreen: wake lock rejected: ${e.javaClass.simpleName}")
            null
        }
        try {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (e: SecurityException) {
            Log.w(TAG, "wakeScreen: acquire denied: ${e.javaClass.simpleName}")
        }
        try {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            // A SECURE keyguard can never be dismissed programmatically; attempting it
            // is pointless and misleading. Only nonsecure lock surfaces get the legacy
            // best-effort dismiss, which may clear swipe-only locks.
            if (keyguard != null && !keyguard.isKeyguardSecure) {
                disableNonsecureKeyguardBestEffort(keyguard)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "wakeScreen: keyguard probe failed: ${e.javaClass.simpleName}")
        } finally {
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } catch (e: RuntimeException) {
                Log.w(TAG, "wakeScreen: release failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun disableNonsecureKeyguardBestEffort(keyguard: KeyguardManager) {
        try {
            @Suppress("DEPRECATION")
            keyguard.newKeyguardLock("SanaaAgent").disableKeyguard()
        } catch (e: SecurityException) {
            Log.w(TAG, "nonsecure keyguard dismiss denied: ${e.javaClass.simpleName}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "nonsecure keyguard dismiss unavailable: ${e.javaClass.simpleName}")
        }
    }

    fun isScreenOn(context: Context): Boolean {
        screenOnOverride?.let { return it }
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return power.isInteractive
    }

    fun isLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return keyguard.isKeyguardLocked
    }

    fun isSecurelyLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return keyguard.isKeyguardLocked && keyguard.isKeyguardSecure
    }

    fun makeUnlockedActivityFlags(): Int {
        var flags = 0
        flags = flags or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        flags = flags or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        flags = flags or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        return flags
    }
}
