package co.sanaa.agent.core.shorts

import android.content.Context

class ShortsSettings(context: Context) {
    private val prefs = context.getSharedPreferences("youtube_shorts", Context.MODE_PRIVATE)
    val enabled get() = prefs.getBoolean("enabled", false)
    val channel get() = prefs.getString("channel", "").orEmpty()
    val intervalMinutes get() = prefs.getInt("interval", 240)
    val dailyCap get() = prefs.getInt("cap", 3)
    val audioCleared get() = prefs.getBoolean("audio_cleared", false)
    val visibility get() = prefs.getString("visibility", "Public").orEmpty()
    val madeForKids get() = prefs.getBoolean("made_for_kids", false)
    fun all(): Map<String, Any> = mapOf("youtubeEnabled" to enabled, "youtubeChannel" to channel,
        "youtubeIntervalMinutes" to intervalMinutes, "youtubeDailyCap" to dailyCap,
        "youtubeAudioCleared" to audioCleared,
        "youtubeVisibility" to visibility, "youtubeMadeForKids" to madeForKids,
        "youtubeStatus" to (prefs.getString("status", null) ?: "No Shorts prepared yet"))
    fun status(value: String) { prefs.edit().putString("status", value.take(500)).apply() }
    fun set(key: String, value: Any): Boolean {
        val edit = prefs.edit()
        when (key) {
            "youtubeEnabled" -> edit.putBoolean("enabled", value as Boolean)
            "youtubeChannel" -> {
                val channel = (value as String).trim()
                if (channel.isNotEmpty() && !ShortsMediaPolicy.validHandle(channel)) return false
                edit.putString("channel", channel)
            }
            "youtubeIntervalMinutes" -> edit.putInt("interval", (value as Number).toInt().coerceIn(10, 1440))
            "youtubeDailyCap" -> edit.putInt("cap", (value as Number).toInt().coerceIn(1, 24))
            "youtubeAudioCleared" -> edit.putBoolean("audio_cleared", value as Boolean)
            "youtubeVisibility" -> {
                if (value !in setOf("Public", "Unlisted", "Private")) return false
                edit.putString("visibility", value as String)
            }
            "youtubeMadeForKids" -> edit.putBoolean("made_for_kids", value as Boolean)
            else -> return false
        }
        return edit.commit()
    }
}
