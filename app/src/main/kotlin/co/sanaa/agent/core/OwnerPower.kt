package co.sanaa.agent.core

import android.content.Context

/** Owner-only durable hold. Never changed by remote config or recovery. */
class OwnerPower(context: Context) {
    private val prefs = context.getSharedPreferences("owner_power", Context.MODE_PRIVATE)
    fun isOn(): Boolean = prefs.getBoolean("on", true)
    fun setOn(value: Boolean) {
        check(prefs.edit().putBoolean("on", value).commit()) { "Could not persist owner power state" }
    }
}
