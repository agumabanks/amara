package co.sanaa.agent.modules

import android.content.Context
import co.sanaa.agent.core.ContentHashing

/** Only new conditions notify the manager; acknowledgement does not claim repair. */
class HealthAlertDelivery(context: Context) {
    private val prefs = context.getSharedPreferences("health_alert_delivery", Context.MODE_PRIVATE)
    private fun key(reason: String) = ContentHashing.hash(reason.replace(Regex("\\d+"), "#").trim())
    @Synchronized fun unseen(reasons: List<String>): List<String> = reasons.distinctBy(::key).filter { !prefs.contains(key(it)) }
    @Synchronized fun record(reasons: List<String>) {
        check(prefs.edit().apply { reasons.forEach { putBoolean(key(it), true) } }.commit())
    }
    @Synchronized fun reconcile(current: List<String>) {
        val keys = current.map(::key).toSet()
        check(prefs.edit().apply { prefs.all.keys.filterNot { it in keys }.forEach(::remove) }.commit())
    }
}
