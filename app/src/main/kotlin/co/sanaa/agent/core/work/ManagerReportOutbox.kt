package co.sanaa.agent.core.work

import android.content.Context
import org.json.JSONObject

/** Persist before queue admission so full queues and process restarts cannot lose manager updates. */
class ManagerReportOutbox(context: Context) {
    private val prefs=context.getSharedPreferences("manager_report_outbox",Context.MODE_PRIVATE)
    @Synchronized fun enqueue(item: WorkItem, offer: (WorkItem)->WorkQueue.OfferResult): Boolean {
        require(item.payload.optBoolean("manager_report"))
        if(!prefs.contains(item.dedupeKey)) check(prefs.edit().putString(item.dedupeKey,item.payload.toString()).commit())
        return admit(item.dedupeKey,offer)
    }
    @Synchronized fun flush(offer: (WorkItem)->WorkQueue.OfferResult): Int {
        var admitted=0
        for(key in prefs.all.keys) { if(admit(key,offer)) admitted++ else break }
        return admitted
    }
    fun pendingCount(): Int = prefs.all.size
    private fun admit(key:String,offer:(WorkItem)->WorkQueue.OfferResult):Boolean {
        val payload=JSONObject(prefs.getString(key,null) ?: return true)
        val item=ManagerReportWork.from(payload.optString("target"),"outbox",payload.optString("message"))
            ?.copy(dedupeKey=key,payload=payload) ?: return false
        if(offer(item)==WorkQueue.OfferResult.REJECTED_CAP) return false
        check(prefs.edit().remove(key).commit())
        return true
    }
}
