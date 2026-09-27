package co.sanaa.agent.core.work

import android.content.Context

/** Persisted low-battery pause shared by admission, in-session checks and owner health. */
class BatteryWorkHold(context: Context) {
    private val prefs = context.getSharedPreferences("battery_work_hold", Context.MODE_PRIVATE)

    @Synchronized fun observe(percent: Int): Boolean {
        val wasHeld = prefs.getBoolean("held", false)
        if (percent !in 0..100) return wasHeld
        val held = percent <= PAUSE_PERCENT || (wasHeld && percent < RESUME_PERCENT)
        if (held != wasHeld) check(prefs.edit().putBoolean("held", held).commit())
        return held
    }

    fun isHeld(): Boolean = prefs.getBoolean("held", false)

    companion object {
        const val PAUSE_PERCENT = 15
        const val RESUME_PERCENT = 20
    }
}
