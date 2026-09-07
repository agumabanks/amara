package co.sanaa.agent.core.work

import android.content.Context

/** One durable opportunity, never a new random value on every heartbeat. */
class TikTokCadence(context: Context) {
    private val prefs = context.getSharedPreferences("tiktok_cadence", Context.MODE_PRIVATE)

    @Synchronized
    fun dueAt(intervalMinutes: Long, now: Long = System.currentTimeMillis()): Long {
        val previousInterval = prefs.getLong("interval", -1)
        val due = prefs.getLong("due", 0)
        if (due > 0 && previousInterval == intervalMinutes) return due
        val next = now + randomDelayMillis(intervalMinutes)
        check(prefs.edit().putLong("interval", intervalMinutes).putLong("due", next).commit())
        return next
    }

    @Synchronized
    fun finishOpportunity(key: String, intervalMinutes: Long, now: Long = System.currentTimeMillis(), verified: Boolean = true) {
        val due = prefs.getLong("due", 0)
        if (key != "tiktok-due-$due") return
        check(prefs.edit().putLong("interval", intervalMinutes)
            .putLong("due", now + if (verified) randomDelayMillis(intervalMinutes) else 5 * 60_000L)
            .putBoolean("last_verified", verified).commit())
    }

    companion object {
        // The configured interval is a promise, not a hidden randomized window.
        internal fun randomDelayMillis(intervalMinutes: Long, unit: Double = 0.0): Long =
            intervalMinutes.coerceIn(10, 480) * 60_000L
    }
}
