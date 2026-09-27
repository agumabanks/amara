package co.sanaa.agent.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Only verified manager commands enter this history. Scope and manager changes isolate it. */
class ManagerCommandThread(context: Context) {
    private val prefs = context.getSharedPreferences("manager_command_threads", Context.MODE_PRIVATE)
    private fun key(manager: String, scope: String): String {
        require(manager.isNotBlank() && scope.isNotBlank())
        return ContentHashing.hash("$scope:$manager")
    }

    fun context(manager: String, scope: String, now: Long = System.currentTimeMillis()): String {
        val rows = JSONArray(prefs.getString(key(manager, scope), "[]"))
        return (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { now - it.getLong("at") in 0..86_400_000L }
            .takeLast(4).joinToString("\n") {
                "Manager: ${it.getString("command")}\nAmara (${it.getString("status")}): ${it.getString("result") }"
            }.takeLast(2_400)
    }

    @Synchronized
    fun record(manager: String, scope: String, command: String, result: CommandResult,
               now: Long = System.currentTimeMillis()) {
        val storageKey = key(manager, scope)
        val old = JSONArray(prefs.getString(storageKey, "[]"))
        val rows = JSONArray()
        for (i in (old.length() - 3).coerceAtLeast(0) until old.length()) rows.put(old.getJSONObject(i))
        rows.put(JSONObject().put("at", now)
            .put("command", CredentialGuard.inspect(command).safeForPersistence.take(600))
            .put("status", result.status.take(80))
            .put("result", CredentialGuard.inspect(result.message).safeForPersistence.take(600)))
        check(prefs.edit().putString(storageKey, rows.toString()).commit())
    }
}
