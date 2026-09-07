package co.sanaa.agent.modules

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Immutable draft per inbound event: process restarts cannot change a pending send. */
class WhatsAppReplyStore(context: Context) : SQLiteOpenHelper(context, "amara_reply_drafts.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) = db.execSQL("CREATE TABLE drafts (event_key TEXT PRIMARY KEY, payload TEXT NOT NULL, created_at INTEGER NOT NULL)")
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun get(key: String): JSONObject? = readableDatabase.rawQuery("SELECT payload FROM drafts WHERE event_key=?", arrayOf(key)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }
    @Synchronized fun bind(key: String, payload: JSONObject): JSONObject {
        require(key.isNotBlank())
        writableDatabase.execSQL("INSERT OR IGNORE INTO drafts VALUES (?, ?, ?)", arrayOf<Any>(key, payload.toString(), System.currentTimeMillis()))
        return requireNotNull(get(key))
    }
}
