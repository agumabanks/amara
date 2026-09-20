package co.sanaa.agent.core

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager

/** Ask Android to dismiss only a nonsecure lock; never supply or solicit credentials. */
class WorkUnlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT < 26 || !OwnerPower(this).isOn() ||
            DeviceActivityMonitor.isUserLikelyActive() || keyguard.isKeyguardSecure || !keyguard.isKeyguardLocked) {
            finish(); return
        }
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { finish() }
            override fun onDismissCancelled() { finish() }
            override fun onDismissError() { finish() }
        })
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 2500L)
    }

    companion object {
        private var lastAttemptAt = -30_000L
        @Synchronized
        fun request(context: Context): Boolean {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (Build.VERSION.SDK_INT < 26 || !OwnerPower(context).isOn() ||
                DeviceActivityMonitor.isUserLikelyActive() || keyguard.isKeyguardSecure || !keyguard.isKeyguardLocked) return false
            val now = SystemClock.elapsedRealtime()
            if (now - lastAttemptAt < 30_000L) return false
            lastAttemptAt = now
            return runCatching {
                context.startActivity(Intent(context, WorkUnlockActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                true
            }.getOrDefault(false)
        }
    }
}
