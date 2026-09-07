package co.sanaa.agent.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Owner-started, bounded evaluation. No message bodies, names, tokens or credentials. */
class EvaluationJournal(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("evaluation_window", Context.MODE_PRIVATE)
    fun start(durationMs: Long = 5 * 3_600_000L): JSONObject = synchronized(lock) {
        val now = System.currentTimeMillis()
        val end = now + durationMs.coerceIn(60_000, 5 * 3_600_000L)
        val file = File(context.filesDir, "evaluation-$now.jsonl")
        check(prefs.edit().putLong("start", now).putLong("end", end).putString("file", file.name).commit())
        record("window_started", fields = JSONObject().put("ends_at", end).put("version", co.sanaa.agent.BuildConfig.VERSION_NAME))
        JSONObject().put("start", now).put("end", end).put("file", file.name)
    }
    fun record(event: String, key: String = "", fields: JSONObject = JSONObject()) {
        synchronized(lock) {
            try {
                val now = System.currentTimeMillis()
                if (now >= prefs.getLong("end", 0)) return
                val name = prefs.getString("file", null) ?: return
                val row = JSONObject().put("at", now).put("event", event).put("build_segment", "whatsapp-followup-identity-v6").put("fields", fields)
                if (key.isNotBlank()) row.put("key", ContentHashing.hash(key))
                File(context.filesDir, name).appendText(row.toString() + "\n")
            } catch (e: Exception) {
                android.util.Log.e("EvaluationJournal", "Evaluation event persistence failed")
            }
        }
    }
    companion object { private val lock = Any() }
}
