package co.sanaa.agent.modules

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import co.sanaa.agent.core.PromptInjectionGuard
import co.sanaa.agent.core.TrustedContent
import org.json.JSONArray

/** Customer statements remain scoped evidence, never business policy or secret storage. */
class ConversationKnowledge(context: Context) : SQLiteOpenHelper(context, "amara_conversation_knowledge.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) = db.execSQL("CREATE TABLE facts (chat_key TEXT NOT NULL, kind TEXT NOT NULL, evidence TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(chat_key,kind))")
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun learn(chatKey: String, message: String, facts: JSONArray?) {
        if (facts == null) return
        for (i in 0 until minOf(facts.length(), 6)) {
            val fact = facts.optJSONObject(i) ?: continue
            val kind = fact.optString("kind")
            val evidence = fact.optString("evidence").trim()
            if (!validEvidence(kind, evidence, message)) continue
            writableDatabase.execSQL("INSERT OR REPLACE INTO facts VALUES (?, ?, ?, ?)", arrayOf<Any>(chatKey, kind, evidence, System.currentTimeMillis()))
        }
    }
    fun recall(chatKey: String): String = readableDatabase.rawQuery(
        "SELECT kind,evidence FROM facts WHERE chat_key=? AND updated_at>=? ORDER BY updated_at DESC LIMIT 6",
        arrayOf(chatKey, (System.currentTimeMillis() - 90L * 86400000).toString())
    ).use { c -> buildList { while (c.moveToNext()) add("${c.getString(0)}: ${c.getString(1)}") }.joinToString("\n") }
    companion object {
        private val kinds = setOf("product_interest", "delivery_area", "language", "contact_preference", "size_colour", "budget")
        fun validEvidence(kind: String, evidence: String, message: String): Boolean =
            kind in kinds && evidence.length in 3..180 && message.contains(evidence) &&
                !Regex("(?i)\\b(pin|password|passcode|otp|secret|token|api.?key)\\b").containsMatchIn(evidence) &&
                !PromptInjectionGuard.blocksSideEffects(PromptInjectionGuard.scan(TrustedContent.message(evidence)))
    }
}
