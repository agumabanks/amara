package co.sanaa.agent.modules

import android.content.Context
import org.json.JSONObject
import co.sanaa.agent.core.ContentHashing

/** Tracks recurrence and recovery evidence; a repair request alone never resolves an issue. */
class HealthIssueHistory(context: Context) {
    private val prefs = context.getSharedPreferences("health_issue_history", Context.MODE_PRIVATE)
    @Synchronized fun observe(issues: List<String>, now: Long, recoveryRequested: Boolean = false) {
        val active = issues.distinct().associateBy { ContentHashing.hash(it.replace(Regex("\\d+"), "#")) }
        val editor = prefs.edit()
        for ((key, reason) in active) {
            val previous = prefs.getString(key, null)?.let(::JSONObject)
            val record = previous ?: JSONObject().put("firstSeen", now).put("episodes", 0).put("recoveryAttempts", 0)
            if (previous == null || record.optLong("resolvedAt") > 0) record.put("episodes", record.optInt("episodes") + 1)
            record.put("reason", reason).put("lastSeen", now).put("resolvedAt", 0)
            if (recoveryRequested && (reason.contains("loop", true) || reason.contains("background service", true)))
                record.put("recoveryAttempts", record.optInt("recoveryAttempts") + 1)
            editor.putString(key, record.toString())
        }
        for ((key, value) in prefs.all) {
            if (key in active) continue
            val record = JSONObject(value as String)
            if (record.optLong("resolvedAt") == 0L) {
                record.put("resolvedAt", now).put("evidence", "A later health check no longer observed this condition")
                editor.putString(key, record.toString())
            } else if (now - record.optLong("resolvedAt") > 90L * 24 * 60 * 60 * 1000) editor.remove(key)
        }
        check(editor.commit())
    }
    fun rows(): List<Map<String, Any>> = prefs.all.values.map {
        val o = JSONObject(it as String)
        o.keys().asSequence().associateWith { key -> o.get(key) }
    }.sortedByDescending { (it["lastSeen"] as Number).toLong() }
}
