package co.sanaa.agent.core.shorts

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Separate durable queue. Uncertain dispatch is never made eligible again. */
class ShortsQueue(context: Context) : SQLiteOpenHelper(context, "shorts_queue.db", null, 1), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE shorts (source TEXT PRIMARY KEY,payload TEXT NOT NULL,state TEXT NOT NULL,at INTEGER NOT NULL,detail TEXT NOT NULL,source_kind TEXT NOT NULL DEFAULT 'tiktok_export')")
        db.execSQL("CREATE INDEX idx_shorts_pending ON shorts(state,source_kind,at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Additive migration keeps SQLite user_version=1. The installed legacy
        // helper can still open this database if an APK rollback is needed.
        if (!db.isReadOnly) {
            val hasSourceKind = db.rawQuery("PRAGMA table_info(shorts)", null).use { columns ->
                val name = columns.getColumnIndexOrThrow("name")
                var found = false
                while (columns.moveToNext()) if (columns.getString(name) == "source_kind") found = true
                found
            }
            if (!hasSourceKind) {
                db.execSQL("ALTER TABLE shorts ADD COLUMN source_kind TEXT NOT NULL DEFAULT 'tiktok_export'")
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_shorts_pending ON shorts(state,source_kind,at)")
        }
    }
    fun offer(source: String, payload: JSONObject, sourceKind: String = "tiktok_export") {
        require(source.isNotBlank() && payload.optString("shop_scope").isNotBlank() && payload.optString("caption").isNotBlank())
        require(sourceKind in setOf("tiktok_export", "catalogue_variant"))
        if (android.database.DatabaseUtils.queryNumEntries(readableDatabase, "shorts") >= 500) return
        writableDatabase.insertWithOnConflict("shorts", null, ContentValues().apply {
            put("source", source); put("payload", payload.toString()); put("state", "PENDING")
            put("at", System.currentTimeMillis()); put("detail", if (sourceKind == "tiktok_export") "Waiting for export" else "Waiting for catalogue variant")
            put("source_kind", sourceKind)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    // Only the legacy path is dispatchable until catalogue rendering and rights
    // checks are implemented. Preserve future variant rows without replaying them.
    fun next(): Pair<String, JSONObject>? = readableDatabase.rawQuery("SELECT source,payload FROM shorts WHERE state='PENDING' AND source_kind='tiktok_export' ORDER BY at ASC,rowid ASC LIMIT 1", null).use {
        if(it.moveToFirst()) it.getString(0) to JSONObject(it.getString(1)) else null
    }
    /** Keep another shop's rows intact while selecting this shop's oldest eligible source. */
    fun nextForShop(scope: String): Pair<String, JSONObject>? {
        if (scope.isBlank()) return null
        return readableDatabase.rawQuery(
            "SELECT source,payload FROM shorts WHERE state='PENDING' AND source_kind='tiktok_export' ORDER BY at ASC,rowid ASC", null
        ).use { rows ->
            var match: Pair<String, JSONObject>? = null
            while (rows.moveToNext()) {
                val payload = runCatching { JSONObject(rows.getString(1)) }.getOrNull() ?: continue
                if (payload.optString("shop_scope") == scope) {
                    match = rows.getString(0) to payload
                    break
                }
            }
            match
        }
    }
    fun retainedSources(): List<Pair<String, JSONObject>> = readableDatabase.rawQuery(
        "SELECT source,payload FROM shorts WHERE source_kind='tiktok_export' ORDER BY at DESC,rowid DESC", null).use { rows -> buildList {
            while(rows.moveToNext()) add(rows.getString(0) to JSONObject(rows.getString(1)))
        } }
    fun latestVerifiedSource(memory: co.sanaa.agent.core.AmaraMemory, scope: String? = null): Pair<String, JSONObject>? =
        retainedSources().firstOrNull { (source, payload) -> (scope == null || payload.optString("shop_scope") == scope) && memory.findSideEffectTransaction(source)?.let {
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
