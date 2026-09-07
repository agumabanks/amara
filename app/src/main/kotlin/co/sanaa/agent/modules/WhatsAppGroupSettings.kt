package co.sanaa.agent.modules

import android.content.Context
import co.sanaa.agent.core.*
import org.json.JSONObject

/** Owner settings keyed by directory/origin ID, never display name. */
class WhatsAppGroupSettings(context: Context) {
    private val prefs=context.getSharedPreferences("whatsapp_group_settings",Context.MODE_PRIVATE)
    fun row(entry: DirectoryEntry): Map<String,Any> {
        val data=JSONObject(prefs.getString(entry.id,"{}")!!)
        return mapOf("id" to entry.id,"name" to entry.displayName,
            "listen" to (entry.canMonitor && data.optBoolean("listen",true)),
            "reply" to (entry.canReply && data.optBoolean("reply",true)),
            "promote" to (entry.canSend && data.optBoolean("promote",true)),
            "intervalMinutes" to data.optInt("intervalMinutes",1440),
            "originVerified" to entry.id.startsWith("wa-origin:"),
            "promotionsReady" to (entry.ambiguity==Ambiguity.UNIQUE && entry.canSend),
            "lastSeen" to maxOf(entry.lastVerifiedAt ?: 0L,data.optLong("lastObservedAt")),
            "paused" to data.optBoolean("paused"),
            "lastReason" to readableReason(data.optString("lastReason")),
            "consecutiveFailures" to data.optInt("consecutiveFailures"),
            "lastOutcome" to data.optString("lastOutcome","No scheduled outcome yet"),
            "nextPromotionAt" to data.optLong("nextDue"))
    }
    @Synchronized fun update(directory: ContactDirectory,id: String,field: String,value: Any) {
        val entry=requireNotNull(directory.byId(id));require(entry.isGroup)
        val data=JSONObject(prefs.getString(id,"{}")!!)
        when(field) {
            "listen","reply","promote" -> {
                require(value is Boolean)
                val op=when(field){"listen"->Operation.MONITOR;"reply"->Operation.REPLY;else->Operation.SEND}
                if(value) { directory.setPermission(id,Operation.MONITOR,true);data.put("listen",true) }
                directory.setPermission(id,op,value)
                data.put(field,value)
                if(field=="listen" && !value) { data.put("reply",false);directory.setPermission(id,Operation.REPLY,false) }
            }
            "resume" -> {
                require(value == true)
                require(entry.canSend)
                data.put("paused",false).put("consecutiveFailures",0).put("nextDue",System.currentTimeMillis())
            }
            "intervalMinutes" -> {
                require(value is Number)
                val minutes=value.toInt().coerceIn(60,10080)
                data.put(field,minutes)
                if(data.optLong("lastAttempt")>0) data.put("nextDue",data.optLong("lastAttempt")+minutes*60_000L)
            }
            else -> error("Unsupported group setting")
        }
        check(prefs.edit().putString(id,data.toString()).commit())
    }
    @Synchronized fun observe(id:String,signature:String,at:Long): Boolean {
        val data=JSONObject(prefs.getString(id,"{}")!!)
        val hash=ContentHashing.hash(signature)
        if(data.optString("lastObservation")==hash) return false
        data.put("lastObservation",hash).put("lastObservedAt",at)
        check(prefs.edit().putString(id,data.toString()).commit())
        return true
    }
    fun due(entry:DirectoryEntry): Long {
        val data=JSONObject(prefs.getString(entry.id,"{}")!!)
        return if(data.optBoolean("paused")) Long.MAX_VALUE else data.optLong("nextDue")
    }
    @Synchronized fun outcome(entry:DirectoryEntry,status:String,reason:String="") {
        val data=JSONObject(prefs.getString(entry.id,"{}")!!)
        val success=status in setOf("DONE","VERIFIED")
        val failures=if(success) 0 else data.optInt("consecutiveFailures")+1
        val hold=!success && (failures>=2 || reason.contains("recipient_unavailable") || reason.contains("recipient_not_unique") || reason.contains("Uncertain",true))
        data.put("consecutiveFailures",failures).put("paused",hold).put("lastReason",reason.take(500))
        data.put("lastOutcome",status).put("lastAttempt",System.currentTimeMillis()).put("nextDue",System.currentTimeMillis()+interval(entry)*60_000L)
        check(prefs.edit().putString(entry.id,data.toString()).commit())
    }
    private fun readableReason(reason:String): String = when {
        reason.contains("recipient_unavailable") -> "WhatsApp returned no matching recipient. Check the saved group name, membership and whether you can post there."
        reason.contains("recipient_not_unique") -> "A single exact recipient could not be selected. Check similarly named groups and the saved group name."
        else -> reason
    }
    fun allows(entry: DirectoryEntry, field:String): Boolean = row(entry)[field]==true
    fun interval(entry: DirectoryEntry): Int = row(entry)["intervalMinutes"] as Int
}
