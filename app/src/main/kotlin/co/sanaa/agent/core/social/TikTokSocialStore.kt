package co.sanaa.agent.core.social

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import co.sanaa.agent.core.ContentHashing
import org.json.JSONArray
import org.json.JSONObject

/** Device-first public-post research and irreversible interaction reservations. */
class TikTokSocialStore(context: Context) : SQLiteOpenHelper(context, "amara_tiktok_social.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE observations (post_key TEXT PRIMARY KEY, creator TEXT NOT NULL, payload TEXT NOT NULL, observed_at INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'OBSERVED', response TEXT NOT NULL DEFAULT '', reserved_at INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE metrics (observed_at INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun observe(key: String, creator: String, data: JSONObject, now: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO observations(post_key,creator,payload,observed_at) VALUES(?,?,?,?)", arrayOf(key,creator,data.toString(),now))
        val previous=readableDatabase.rawQuery("SELECT payload FROM observations WHERE post_key=?",arrayOf(key)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else JSONObject() }
        previous.optJSONObject("decision")?.let { data.put("decision",it).put("decision_at",previous.optLong("decision_at")) }
        if(previous.has("reconciled_at")) data.put("reconciled_at",previous.optLong("reconciled_at"))
        if(previous.has("reconciled_verified")) data.put("reconciled_verified",previous.optBoolean("reconciled_verified"))
        data.put("last_seen",now)
        writableDatabase.execSQL("UPDATE observations SET payload=? WHERE post_key=?",arrayOf(data.toString(),key))
    }
    fun decision(key: String, decision: JSONObject) {
        val data=readableDatabase.rawQuery("SELECT payload FROM observations WHERE post_key=?",arrayOf(key)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null } ?: return
        data.put("decision",decision).put("decision_at",System.currentTimeMillis())
        writableDatabase.execSQL("UPDATE observations SET payload=? WHERE post_key=?",arrayOf(data.toString(),key))
    }
    fun needsReview(key: String, now: Long = System.currentTimeMillis()): Boolean =
        readableDatabase.rawQuery("SELECT payload FROM observations WHERE post_key=?",arrayOf(key)).use { c ->
            if(!c.moveToFirst()) true else JSONObject(c.getString(0)).let { data ->
                data.optJSONObject("decision")==null || now-data.optLong("decision_at")>=7L*86400000
            }
        }
    fun eligible(key: String, creator: String, now: Long = System.currentTimeMillis()): Boolean {
        val db = readableDatabase
        fun count(sql: String, args: Array<String>) = db.rawQuery(sql,args).use { it.moveToFirst(); it.getInt(0) }
        return count("SELECT COUNT(*) FROM observations WHERE post_key=? AND state!='OBSERVED'",arrayOf(key)) == 0 &&
            count("SELECT COUNT(*) FROM observations WHERE creator=? AND reserved_at>?",arrayOf(creator,(now-86400000).toString())) == 0 &&
            count("SELECT COUNT(*) FROM observations WHERE reserved_at>?",arrayOf((now-86400000).toString())) < 12 &&
            count("SELECT COUNT(*) FROM observations WHERE reserved_at>?",arrayOf((now-1200000).toString())) == 0
    }
    @Synchronized fun reserve(key: String, creator: String, response: String, now: Long = System.currentTimeMillis()): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!eligible(key,creator,now) || recentResponses().any { ContentHashing.normalize(it) == ContentHashing.normalize(response) }) return false
            val values = android.content.ContentValues().apply { put("state","RESERVED");put("response",response);put("reserved_at",now) }
            val claimed = db.update("observations",values,"post_key=? AND state='OBSERVED'",arrayOf(key)) == 1
            db.setTransactionSuccessful();return claimed
        } finally { db.endTransaction() }
    }
    fun outcome(key: String, state: String) {
        require(state in setOf("VERIFIED","UNCERTAIN","FAILED"))
        writableDatabase.execSQL("UPDATE observations SET state=? WHERE post_key=? AND state='RESERVED'",arrayOf(state,key))
    }
    data class Reconciliation(val key: String, val creator: String, val response: String)
    fun nextReconciliation(now: Long = System.currentTimeMillis()): Reconciliation? {
        readableDatabase.rawQuery("SELECT post_key,creator,response,payload FROM observations WHERE state IN ('UNCERTAIN','RESERVED') AND reserved_at<? ORDER BY reserved_at", arrayOf((now-3_600_000).toString())).use { c ->
            while(c.moveToNext()) {
                val data=JSONObject(c.getString(3))
                if(c.getString(2).isBlank() || data.optBoolean("reconciled_verified") || now-data.optLong("reconciled_at")<6*3_600_000L) continue
                data.put("reconciled_at",now)
                writableDatabase.execSQL("UPDATE observations SET payload=? WHERE post_key=?",arrayOf(data.toString(),c.getString(0)))
                return Reconciliation(c.getString(0),c.getString(1),c.getString(2))
            }
        }
        return null
    }
    /** Read-only proof never re-arms a reservation or changes the transaction ledger. */
    fun recordReconciliation(key: String, response: String, verified: Boolean, now: Long = System.currentTimeMillis()) {
        val data=readableDatabase.rawQuery("SELECT payload FROM observations WHERE post_key=? AND response=?",arrayOf(key,response)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null } ?: return
        data.put("reconciled_at",now).put("reconciled_verified",verified)
        writableDatabase.execSQL("UPDATE observations SET payload=? WHERE post_key=?",arrayOf(data.toString(),key))
    }
    fun recentLearning(limit: Int = 8): JSONArray {
        val rows=JSONArray()
        readableDatabase.rawQuery("SELECT payload,state FROM observations ORDER BY observed_at DESC LIMIT ?",arrayOf(limit.coerceIn(1,20).toString())).use { c ->
            while(c.moveToNext()) {
                val data=JSONObject(c.getString(0)); val decision=data.optJSONObject("decision") ?: continue
                rows.put(JSONObject().put("caption_excerpt",data.optString("caption").take(180))
                    .put("action",decision.optString("action")).put("relevance",decision.optDouble("relevance",0.0))
                    .put("evidence",decision.optString("evidence").take(180))
                    .put("audience_comments",data.optJSONArray("comments") ?: JSONArray()).put("outcome",c.getString(1)))
            }
        }
        return rows
    }
    fun recentResponses(): List<String> = readableDatabase.rawQuery("SELECT response FROM observations WHERE reserved_at>0 ORDER BY reserved_at DESC LIMIT 30",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun metrics(data: JSONObject, now: Long = System.currentTimeMillis()) { writableDatabase.execSQL("INSERT OR IGNORE INTO metrics VALUES(?,?)",arrayOf(now,data.toString())) }
    fun summary(): JSONObject {
        val states = JSONObject()
        readableDatabase.rawQuery("SELECT state,COUNT(*) FROM observations GROUP BY state",null).use { while(it.moveToNext()) states.put(it.getString(0),it.getInt(1)) }
        val points = readableDatabase.rawQuery("SELECT payload FROM metrics ORDER BY observed_at DESC LIMIT 2",null).use { c -> buildList { while(c.moveToNext()) add(JSONObject(c.getString(0))) } }
        val result = JSONObject().put("states",states).put("profile_samples",points.size)
        if(points.isNotEmpty()) result.put("latest_profile",points.first())
        if(points.size==2 && points.all { it.has("followers") } && points[0].optString("handle")==points[1].optString("handle")) result.put("observed_follower_change",points[0].optLong("followers")-points[1].optLong("followers"))
        return result.put("attribution","Follower changes are observations, not proof that comments caused growth")
    }
    fun exportMemory(): JSONObject {
        val posts = JSONArray(); val metrics = JSONArray()
        readableDatabase.rawQuery("SELECT post_key,creator,payload,observed_at,state,response,reserved_at FROM observations ORDER BY observed_at DESC LIMIT 250",null).use { c -> while(c.moveToNext()) posts.put(JSONObject().put("key",c.getString(0)).put("creator",c.getString(1)).put("data",JSONObject(c.getString(2))).put("at",c.getLong(3)).put("state",c.getString(4)).put("response",c.getString(5)).put("reserved_at",c.getLong(6))) }
        readableDatabase.rawQuery("SELECT observed_at,payload FROM metrics ORDER BY observed_at DESC LIMIT 90",null).use { c -> while(c.moveToNext()) metrics.put(JSONObject().put("at",c.getLong(0)).put("data",JSONObject(c.getString(1)))) }
        val claims=JSONArray()
        readableDatabase.rawQuery("SELECT post_key,creator,response,reserved_at FROM observations WHERE reserved_at>0",null).use { c -> while(c.moveToNext()) claims.put(JSONObject().put("key",c.getString(0)).put("creator",c.getString(1)).put("response",c.getString(2)).put("reserved_at",c.getLong(3))) }
        return JSONObject().put("posts",posts).put("metrics",metrics).put("claims",claims)
    }
    fun importMemory(data: JSONObject): Int {
        var count=0
        data.optJSONArray("posts")?.let { rows -> for(i in 0 until minOf(rows.length(),250)) {
            val row=rows.optJSONObject(i) ?: continue
            val key=row.optString("key");val creator=row.optString("creator")
            if(!key.matches(Regex("[a-f0-9]{64}")) || creator.isBlank()) continue
            observe(key,creator,row.optJSONObject("data") ?: JSONObject(),row.optLong("at"))
            // Cloud restore may only make deduplication more conservative, never re-arm an action.
            if(row.optString("state") != "OBSERVED") writableDatabase.execSQL("UPDATE observations SET state='UNCERTAIN',response=?,reserved_at=MAX(reserved_at,?) WHERE post_key=? AND state='OBSERVED'",arrayOf(row.optString("response"),row.optLong("reserved_at"),key))
            count++
        } }
        data.optJSONArray("metrics")?.let { rows -> for(i in 0 until minOf(rows.length(),90)) rows.optJSONObject(i)?.let { metrics(it.optJSONObject("data") ?: JSONObject(),it.optLong("at")) } }
        data.optJSONArray("claims")?.let { rows -> for(i in 0 until rows.length()) {
            val row=rows.optJSONObject(i) ?: continue
            val key=row.optString("key");val creator=row.optString("creator")
            if(!key.matches(Regex("[a-f0-9]{64}")) || creator.isBlank()) continue
            writableDatabase.execSQL("INSERT OR IGNORE INTO observations(post_key,creator,payload,observed_at) VALUES(?,?,?,?)",arrayOf(key,creator,"{}",row.optLong("reserved_at")))
            writableDatabase.execSQL("UPDATE observations SET state='UNCERTAIN',response=?,reserved_at=MAX(reserved_at,?) WHERE post_key=? AND state='OBSERVED'",arrayOf(row.optString("response"),row.optLong("reserved_at"),key))
        } }
        return count
    }
}
