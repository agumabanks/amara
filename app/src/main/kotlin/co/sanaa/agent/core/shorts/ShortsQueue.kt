package co.sanaa.agent.core.shorts

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Separate durable queue. Uncertain dispatch is never made eligible again. */
class ShortsQueue(context: Context) : SQLiteOpenHelper(context, "shorts_queue.db", null, 1), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE shorts (source TEXT PRIMARY KEY,payload TEXT NOT NULL,state TEXT NOT NULL,at INTEGER NOT NULL,detail TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    fun offer(source: String, payload: JSONObject) {
        require(source.isNotBlank() && payload.optString("shop_scope").isNotBlank() && payload.optString("caption").isNotBlank())
        if (android.database.DatabaseUtils.queryNumEntries(readableDatabase, "shorts") >= 500) return
        writableDatabase.insertWithOnConflict("shorts", null, ContentValues().apply {
            put("source", source); put("payload", payload.toString()); put("state", "PENDING")
            put("at", System.currentTimeMillis()); put("detail", "Waiting for export")
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun next(): Pair<String, JSONObject>? = readableDatabase.rawQuery("SELECT source,payload FROM shorts WHERE state='PENDING' ORDER BY at DESC LIMIT 1", null).use {
        if(it.moveToFirst()) it.getString(0) to JSONObject(it.getString(1)) else null
    }
    fun retainedSources(): List<Pair<String, JSONObject>> = readableDatabase.rawQuery(
        "SELECT source,payload FROM shorts ORDER BY at DESC", null).use { rows -> buildList {
            while(rows.moveToNext()) add(rows.getString(0) to JSONObject(rows.getString(1)))
        } }
    fun latestVerifiedSource(memory: co.sanaa.agent.core.AmaraMemory): Pair<String, JSONObject>? =
        retainedSources().firstOrNull { (source, _) -> memory.findSideEffectTransaction(source)?.let {
            it.capability == co.sanaa.agent.core.CapabilityIds.POST_TIKTOK &&
                it.state == co.sanaa.agent.core.SideEffectState.VERIFIED
        } == true }

    fun sourcePayload(source: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT payload FROM shorts WHERE source=?",arrayOf(source)).use {
        if(it.moveToFirst()) JSONObject(it.getString(0)) else null
    }
    fun update(source: String, state: String, detail: String) {
        require(state in setOf("PENDING", "HELD", "UNCERTAIN", "VERIFIED"))
        // A later preparation failure or retry must never erase dispatch evidence.
        writableDatabase.update("shorts", ContentValues().apply { put("state", state); put("detail", detail.take(500)) },
            "source=? AND state NOT IN ('UNCERTAIN','VERIFIED')", arrayOf(source))
    }
    fun summary(): String = readableDatabase.rawQuery("SELECT state,COUNT(*) FROM shorts GROUP BY state", null).use { c ->
        buildList { while(c.moveToNext()) add("${c.getInt(1)} ${c.getString(0).lowercase()}") }.joinToString(" · ").ifBlank { "No verified TikTok ads queued" }
    }
}
