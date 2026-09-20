package co.sanaa.agent.core.work

import android.content.Context
import org.json.JSONObject

/** Persist recurrence across restarts; alert once per six hours, never claim an attempted fix worked. */
class RecoveryHealth(context: Context) {
    private val prefs=context.getSharedPreferences("recovery_health",Context.MODE_PRIVATE)
    @Synchronized fun observe(result: WorkResult, now: Long = System.currentTimeMillis()): String? {
        val scope=result.item.kind.name+":"+result.item.payload.optString("group_target")
        val key=co.sanaa.agent.core.ContentHashing.hash(scope)
        val row=JSONObject(prefs.getString(key,"{}")!!)
        val reason=result.failure?.summary?.take(350)
        if(reason==null) {
            if(result.status==WorkStatus.DONE) {
                row.put("consecutive",0).put("last_success",now)
                prefs.edit().putString(key,row.toString()).commit()
            }
            return null
        }
        val count=row.optInt("consecutive")+1
        row.put("consecutive",count).put("last_reason",reason).put("last_failure",now)
        val alert=count>=3 && (row.optLong("last_alert")==0L || now-row.optLong("last_alert")>=6*3_600_000L)
        if(alert) row.put("last_alert",now)
        prefs.edit().putString(key,row.toString()).commit()
        return if(alert) "${result.item.kind}: $count consecutive unsuccessful attempts. $reason. Recovery has not yet been verified; review the affected module." else null
    }
}
