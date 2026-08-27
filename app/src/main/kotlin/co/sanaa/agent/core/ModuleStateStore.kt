package co.sanaa.agent.core

import android.content.Context

class ModuleStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("agent_module_state", Context.MODE_PRIVATE)
    fun success(module: String) { prefs.edit().putLong("${module}_success", System.currentTimeMillis()).putInt("${module}_errors", 0).apply() }
    fun failure(module: String, error: String) { prefs.edit().putLong("${module}_failure", System.currentTimeMillis()).putInt("${module}_errors", errors(module) + 1).putString("${module}_error", error.take(2_000)).apply() }
    fun lastSuccess(module: String) = prefs.getLong("${module}_success", 0L)
    fun errors(module: String) = prefs.getInt("${module}_errors", 0)
    fun string(key: String, default: String = "") = prefs.getString(key, default) ?: default
    fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun bool(key: String) = prefs.getBoolean(key, false)
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
}
