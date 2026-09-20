package co.sanaa.agent.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Owner-started, bounded evaluation. No message bodies, names, tokens or credentials. */
class EvaluationJournal(private val context: Context) {
    private val buildSegment by lazy {
        val installedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        "amara-release-observation:$installedAt"
    }
    private val prefs get() = context.getSharedPreferences("evaluation_window", Context.MODE_PRIVATE)
    fun start(durationMs: Long = 5 * 3_600_000L): JSONObject = synchronized(lock) {
        val now = System.currentTimeMillis()
        val end = now + durationMs.coerceIn(60_000, 7 * 24 * 3_600_000L)
        val file = File(context.filesDir, "evaluation-$now.jsonl")
        check(prefs.edit().putLong("start", now).putLong("end", end).putString("file", file.name).putBoolean("capacity_reached", false).putBoolean("rolling_release", false).commit())
        record("window_started", fields = JSONObject().put("ends_at", end).put("version", co.sanaa.agent.BuildConfig.VERSION_NAME))
        JSONObject().put("start", now).put("end", end).put("file", file.name)
    }
    /** One bounded observation window per installed release, retained across restarts. */
    fun startReleaseObservation() = synchronized(lock) {
        val installedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        if (prefs.getLong("release_install", -1) != installedAt) {
            // Preserve an active owner observation across an APK update.
            val active = prefs.getLong("end", 0) > System.currentTimeMillis() &&
                prefs.getString("file", null)?.let { File(context.filesDir, it).isFile } == true
            if (!active) start(24 * 3_600_000L)
            else record("release_updated", fields = JSONObject().put("installed_at", installedAt))
            check(prefs.edit().putLong("release_install", installedAt).putBoolean("rolling_release", true).commit())
        }
    }

    fun exportObservation(): File? = synchronized(lock) {
        val name = prefs.getString("file", null) ?: return null
        val source = File(context.filesDir, name)
        if (!source.isFile) return null
        val directory = context.getExternalFilesDir("evaluation") ?: return null
        directory.mkdirs()
        // Export retained windows too; a fresh active file is not 24h coverage.
        context.filesDir.listFiles().orEmpty().filter {
            it.name.matches(Regex("evaluation-[0-9]+\\.jsonl")) && it != source
        }.forEach { it.copyTo(File(directory, it.name), overwrite = true) }
        source.copyTo(File(directory, name), overwrite = true)
    }

    fun record(event: String, key: String = "", fields: JSONObject = JSONObject()) {
        synchronized(lock) {
            try {
                val now = System.currentTimeMillis()
                if (now >= prefs.getLong("end", 0)) {
                    if (!prefs.getBoolean("rolling_release", false)) return
                    start(24 * 3_600_000L)
                    check(prefs.edit().putBoolean("rolling_release", true).commit())
                    val cutoff = now - 7 * 24 * 3_600_000L
                    // Only diagnostic journals; business memory and delivery ledgers are untouched.
                    listOfNotNull(context.filesDir, context.getExternalFilesDir("evaluation")).forEach { directory ->
                        directory.listFiles().orEmpty().filter {
                            it.name.matches(Regex("evaluation-[0-9]+\\.jsonl")) &&
                                (it.name.removePrefix("evaluation-").removeSuffix(".jsonl").toLongOrNull() ?: now) < cutoff
                        }.forEach { it.delete() }
                    }
                }
                val name = prefs.getString("file", null) ?: return
                val row = JSONObject().put("at", now).put("event", event).put("build_segment", buildSegment).put("fields", fields)
                if (key.isNotBlank()) row.put("key", ContentHashing.hash(key))
                val file = File(context.filesDir, name)
                if (file.length() < 32L * 1024 * 1024) file.appendText(row.toString() + "\n")
                else if (!prefs.getBoolean("capacity_reached", false)) {
                    file.appendText(JSONObject().put("at", now).put("event", "capacity_reached")
                        .put("fields", JSONObject()).toString() + "\n")
                    check(prefs.edit().putBoolean("capacity_reached", true).commit())
                }
            } catch (e: Exception) {
                android.util.Log.e("EvaluationJournal", "Evaluation event persistence failed")
            }
        }
    }
    companion object { private val lock = Any() }
}
