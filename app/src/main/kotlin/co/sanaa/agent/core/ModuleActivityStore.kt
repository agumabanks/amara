package co.sanaa.agent.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import co.sanaa.agent.core.work.WorkResult

/** Task attempts and external-effect receipts remain separate measures. */
class ModuleActivityStore(context: Context) : SQLiteOpenHelper(context, "module_activity.db", null, 1), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE activity (id TEXT PRIMARY KEY,module TEXT,kind TEXT,status TEXT,at INTEGER,detail TEXT)")
        db.execSQL("CREATE INDEX activity_time ON activity(at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    fun record(result: WorkResult) {
        val kind = result.item.kind.name
        val detail = Redactor.redact(result.failure?.summary ?: result.outcomeFacts.joinToString("; ")).take(600)
        record("${result.item.dedupeKey}:${result.item.attempt}",kind,result.status.name,detail)
    }
    fun record(id: String, kind: String, status: String, detail: String) {
        writableDatabase.insertWithOnConflict("activity", null, ContentValues().apply {
            put("id", id)
            put("module", moduleFor(kind)); put("kind", kind); put("status", status)
            put("at", System.currentTimeMillis()); put("detail", Redactor.redact(detail).take(600))
        }, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.delete("activity", "at < ?", arrayOf((System.currentTimeMillis() - 30L * 86_400_000).toString()))
    }
    fun dashboard(receipts: List<SideEffectTransaction>, now: Long = System.currentTimeMillis()): Map<String, Any> {
        val cutoff = now - 86_400_000L
        return MODULES.associateWith { module ->
            val rows = readableDatabase.query("activity", null, "module=? AND at>=?", arrayOf(module, cutoff.toString()), null, null, "at DESC").use { c ->
                buildList<Map<String, Any>> { while(c.moveToNext()) add(mapOf(
                    "kind" to c.getString(c.getColumnIndexOrThrow("kind")),
                    "status" to c.getString(c.getColumnIndexOrThrow("status")),
                    "at" to c.getLong(c.getColumnIndexOrThrow("at")),
                    "detail" to c.getString(c.getColumnIndexOrThrow("detail")))) }
            }
            val effects = receipts.filter { it.updatedAt >= cutoff && moduleFor(it.capability.uppercase()) == module }
            mapOf("window" to "Last 24 hours", "attempts" to rows.size,
                "completed" to rows.count { it["status"] == "DONE" },
                "failed" to rows.count { it["status"] == "FAILED" },
                "held" to rows.count { it["status"] == "ESCALATED" },
                "skipped" to rows.count { it["status"] == "SKIPPED" },
                "partial" to rows.count { it["status"] == "PARTIAL" },
                "verified" to effects.count { it.state == SideEffectState.VERIFIED },
                "uncertain" to effects.count { it.state == SideEffectState.UNCERTAIN },
                "recent" to rows.take(10))
        }
    }
    companion object {
        val MODULES = listOf("TikTok", "YouTube", "WhatsApp", "Soko", "Market", "Doctor", "Memory", "General")
        fun moduleFor(kind: String): String = when {
            "TIKTOK" in kind -> "TikTok"
            "YOUTUBE" in kind || "SHORTS" in kind -> "YouTube"
            "WHATSAPP" in kind || kind.startsWith("WA_") -> "WhatsApp"
            "SOKO" in kind -> "Soko"
            listOf("MARKET", "JIJI", "JUMIA").any { it in kind } -> "Market"
            "HEALTH" in kind || "DOCTOR" in kind -> "Doctor"
            "MEMORY" in kind || "COMPACTION" in kind -> "Memory"
            else -> "General"
        }
    }
}
