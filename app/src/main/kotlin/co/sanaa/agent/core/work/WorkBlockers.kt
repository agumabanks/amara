package co.sanaa.agent.core.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import co.sanaa.agent.core.Redactor
import co.sanaa.agent.notifications.NotificationReporter
import org.json.JSONObject

/** Unresolved work is independent of the most recent successful/idle cycle. */
class WorkBlockers(private val context: Context) {
    private val prefs = context.getSharedPreferences("work_blockers", Context.MODE_PRIVATE)
    private val history = context.getSharedPreferences("resolved_work_blockers", Context.MODE_PRIVATE)
    private val reporter by lazy { NotificationReporter(context) }

    @Synchronized fun flag(key: String, task: String, reason: String, ownerAction: Boolean = false,
                           action: String = "Amara will retry when eligible. If this persists, inspect the affected app.",
                           global: Boolean = false, managerReport: Boolean = false, now: Long = System.currentTimeMillis()) {
        val old = JSONObject(prefs.getString(key, "{}")!!)
        val alert = old.optLong("lastAlert") == 0L || now - old.optLong("lastAlert") >= 30 * 60_000L
        val row = JSONObject().put("task", task).put("reason", Redactor.redact(reason).take(600))
            .put("managerReport", managerReport || old.optBoolean("managerReport")).put("ownerAction", ownerAction).put("action", action).put("global", global)
            .put("continuation", if (global) "Autonomous work is waiting for this device condition to clear."
                else "Other eligible tasks can continue. This task is not confirmed successful.")
            .put("observedAt", now).put("firstObservedAt", old.optLong("firstObservedAt", now))
            .put("lastAlert", if (alert) now else old.optLong("lastAlert"))
        check(prefs.edit().putString(key, row.toString()).commit())
        if (alert) runCatching {
            reporter.report("Amara: $task needs attention", "${row.getString("reason")}\n$action\n${row.getString("continuation")}",
                if (ownerAction) NotificationReporter.Priority.ACTION_NEEDED else NotificationReporter.Priority.INFO,
                notificationId = notificationId(key))
        }
    }

    @Synchronized fun clear(key: String, evidence: String = "Condition checked and no longer present") {
        if (!prefs.contains(key)) return
        val old=JSONObject(prefs.getString(key,"{}")!!).put("resolvedAt",System.currentTimeMillis()).put("evidence",evidence)
        history.edit().putString(key,old.toString()).commit()
        val retained=history.all.entries.sortedByDescending { runCatching { JSONObject(it.value.toString()).optLong("resolvedAt") }.getOrDefault(0L) }
        if(retained.size>50) history.edit().apply { retained.drop(50).forEach { remove(it.key) } }.commit()
        check(prefs.edit().remove(key).commit())
        runCatching { context.getSystemService(android.app.NotificationManager::class.java).cancel(notificationId(key)) }
    }

    fun refreshBattery() {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        if (percent <= 15) flag("device:battery", "Device battery", "Battery is $percent%; autonomous work pauses at 15% or below.",
            ownerAction = true, action = "Connect a reliable charger. Work resumes automatically above 15%.", global = true)
        else clear("device:battery")
        val temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        if (temperature >= 450) flag("device:temperature", "Device temperature",
            "Battery temperature is ${temperature / 10.0}°C; scheduled autonomous work waits at 45°C or above.",
            ownerAction = true, action = "Let the device cool before work resumes.", global = true)
        else if (temperature >= 0) clear("device:temperature")
    }

    fun deferred(item: WorkItem, reason: String) {
        if (reason.contains("Battery", true) || reason.contains("too hot", true)) { refreshBattery(); return }
        val help = ownerHelp(reason)
        flag("wait:${scope(item)}", label(item.kind), reason, help != null,
            help ?: "Waiting for the stated condition or retry time. Other eligible work can continue.")
    }

    fun admitted(item: WorkItem) = clear("wait:${scope(item)}")

    fun outcome(result: WorkResult, recovery: RecoveryDecision? = null) {
        val key = "failure:${scope(result.item)}"
        if (result.status == WorkStatus.DONE) {
            resolveSuccess(result.item,System.currentTimeMillis(),"Completed and verified by the task executor")
            return
        }
        if(result.status == WorkStatus.SKIPPED && result.failure?.summary == "Group schedule is paused or not due") return
        val failure = result.failure ?: if (result.status in setOf(WorkStatus.FAILED, WorkStatus.ESCALATED, WorkStatus.PARTIAL))
            FailureInfo(FailureClass.UNKNOWN, "Task ended as ${result.status.name.lowercase()} without a failure reason. Its outcome is not confirmed.", false)
            else return
        val review = result.status == WorkStatus.ESCALATED || !failure.recoverable ||
            recovery is RecoveryDecision.Drop || recovery is RecoveryDecision.Escalate
        val help = ownerHelp(failure.summary)
        flag(key, label(result.item.kind), failure.summary, review || help != null,
            help ?: if (review) "Open Work and inspect this task and the affected app. Confirm the outcome before another attempt."
            else if (recovery is RecoveryDecision.Requeue)
                "Retry queued in ${(recovery.delayMs / 60_000L).coerceAtLeast(1)} minute(s). Other eligible work can continue."
            else "Recovery is pending; no successful outcome has been confirmed.",
            managerReport = result.item.payload.optBoolean("manager_report"))
    }

    /** Legacy keys are resolved only for the same destination after a newer verified success. */
    fun resolveSuccess(item: WorkItem, verifiedAt: Long, evidence: String) {
        val oldDestination=listOf("group_target","chat_key","contact_id").joinToString(":") { item.payload.optString(it) }
        val scopes=mutableSetOf(scope(item))
        if(item.kind != WorkKind.WA_REPLY_INBOUND) scopes += "${item.kind}:${co.sanaa.agent.core.ContentHashing.hash(oldDestination)}"
        for(scope in scopes) for(prefix in listOf("wait:","failure:")) {
            val key=prefix+scope
            val row=runCatching { JSONObject(prefs.getString(key,"{}")!!) }.getOrNull() ?: continue
            if(row.optLong("observedAt") <= verifiedAt) clear(key,evidence)
        }
    }

    fun reconcileGroups(groups: List<Pair<String,Long>>) {
        refreshBattery()
        // Being not due is a scheduling state, never an unresolved delivery failure.
        for((key,value) in prefs.all) {
            val row=runCatching { JSONObject(value.toString()) }.getOrNull() ?: continue
            if(row.optString("reason")=="Group schedule is paused or not due") clear(key,"Reclassified as a normal schedule wait; no delivery claimed")
        }
        for((target,at) in groups) if(at>0) resolveSuccess(WorkItem("reconcile",Domain.WHATSAPP,WorkKind.WA_BROADCAST,
            JSONObject().put("group_target",target),baseValueKes=0.0,urgencyHalfLifeHours=1.0,estimatedScreenSeconds=0),
            at,"A later group promotion was verified at ${java.time.Instant.ofEpochMilli(at)}")
    }

    @Synchronized fun clearStaleWaits(activeScopes: Set<String>): Int {
        var cleared = 0
        prefs.all.keys.filter { it.startsWith("wait:") }.forEach { key ->
            if (key.removePrefix("wait:") !in activeScopes && prefs.contains(key)) {
                clear(key, "Nightly Doctor found no live queued work for this wait")
                cleared++
            }
        }
        return cleared
    }

    /** Owner-confirmed administrative reset; retains every row in resolved history. */
    @Synchronized fun clearAllOwnerReviewed(evidence: String): Int {
        val keys = prefs.all.keys.toList()
        keys.forEach { clear(it, evidence) }
        return keys.size
    }

    @Synchronized fun resolved(): List<Map<String,Any>> = history.all.values.mapNotNull {
        runCatching { val row=JSONObject(it.toString()); row.keys().asSequence().associateWith { k->row.get(k) } }.getOrNull()
    }.sortedByDescending { it["resolvedAt"] as? Long ?: 0L }

    @Synchronized fun rows(): List<Map<String, Any>> = prefs.all.entries.mapNotNull { (key,value) ->
        runCatching { val row = JSONObject(value.toString()).put("id",key)
            row.keys().asSequence().associateWith { k -> row.get(k) } }.getOrNull()
    }.sortedWith(compareByDescending<Map<String, Any>> { it["global"] == true }
        .thenByDescending { it["ownerAction"] == true }.thenByDescending { it["observedAt"] as? Long ?: 0L })

    companion object {
        internal fun scope(item: WorkItem): String {
            val destination = listOf("group_target", "chat_key", "contact_id", "target", "conversation_identity", "manager_report", "manager_command_candidate").joinToString(":") { item.payload.optString(it) }
            return "${item.kind}:${co.sanaa.agent.core.ContentHashing.hash(destination)}"
        }
        private fun notificationId(key: String) = 0x40000000 or (key.hashCode() and 0x0fffffff)
        private fun label(kind: WorkKind) = kind.name.lowercase().split('_').joinToString(" ").replaceFirstChar { it.uppercase() }
        private fun ownerHelp(reason: String): String? = when {
            reason.contains("accessibility", true) -> "Open Phone access and enable Amara's Accessibility service."
            reason.contains("unlock", true) || reason.contains("keyguard", true) -> "Unlock this device so Amara can access the app."
            reason.contains("Terminal", true) && reason.contains("identity", true) || reason.contains("logged-in shop", true) -> "Open Soko Terminal, then verify the logged-in shop in Amara Settings."
            reason.contains("sign-in", true) || reason.contains("login", true) || reason.contains("log in", true) -> "Open the affected app and complete its sign-in."
            reason.contains("too hot", true) -> "Let the device cool; work resumes when its temperature is safe."
            else -> null
        }
    }
}
