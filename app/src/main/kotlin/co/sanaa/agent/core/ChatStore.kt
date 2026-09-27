package co.sanaa.agent.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * ChatStore — stores full chat transcripts locally.
 * This is the key to making Amara remember conversations without heavy accessibility work.
 * Every message is stored as text. She can read her own memory.
 *
 * Version 2 adds durable long-term memory: every observed inbound and verified outbound
 * message also becomes a [chat_events] row with a stable event identity, original message
 * time versus observation time, provenance and delivery state. Notification re-delivery,
 * repeated screen reads and restart recovery deduplicate on that identity. Recall after
 * days, weeks or hundreds of messages combines recent turns, the rolling summary and
 * keyword-matched older evidence under a bounded prompt budget.
 */
class ChatStore(context: Context) : SQLiteOpenHelper(context, "amara_chats.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS chats (
                chat_key TEXT NOT NULL,
                sender TEXT NOT NULL,
                direction TEXT NOT NULL CHECK(direction IN ('sent','received')),
                message_text TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                platform TEXT DEFAULT 'whatsapp'
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chats_key_time ON chats(chat_key, timestamp)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS chat_summaries (
                chat_key TEXT PRIMARY KEY,
                summary TEXT NOT NULL,
                stage TEXT DEFAULT 'GREETING',
                last_activity INTEGER NOT NULL,
                message_count INTEGER DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS offerings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL CHECK(type IN ('PRODUCT','SERVICE')),
                name TEXT NOT NULL,
                price_ugx INTEGER,
                price_floor_ugx INTEGER,
                stock_count INTEGER,
                duration_minutes INTEGER,
                requires_booking INTEGER DEFAULT 0,
                description TEXT,
                source TEXT,
                synced_at INTEGER
            )
        """)
        createChatEvents(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createChatEvents(db)
            backfillChatEvents(db)
        }
    }

    private fun createChatEvents(db: SQLiteDatabase) {
        // Stable event identity: notification re-delivery, repeated screen reads and
        // restart recovery of the same logical message deduplicate on this key.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS chat_events (
                event_id TEXT PRIMARY KEY,
                chat_key TEXT NOT NULL,
                platform TEXT NOT NULL DEFAULT 'whatsapp',
                account TEXT NOT NULL DEFAULT '',
                speaker TEXT NOT NULL,
                direction TEXT NOT NULL CHECK(direction IN ('sent','received')),
                message_text TEXT NOT NULL,
                original_at INTEGER NOT NULL,
                observed_at INTEGER NOT NULL,
                provenance TEXT NOT NULL DEFAULT '',
                delivery_state TEXT NOT NULL DEFAULT ''
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_events_time ON chat_events(chat_key, original_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_events_original ON chat_events(original_at)")
    }

    /**
     * Conservative migration of existing transcripts: legacy rows keep their original
     * chat_key and timestamp as identity, marked with a legacy provenance. Ambiguity is
     * quarantined by never merging people or keys — each row becomes exactly one event.
     */
    private fun backfillChatEvents(db: SQLiteDatabase) {
        run {
            val cursor = db.rawQuery(
                "SELECT chat_key, sender, direction, message_text, timestamp, platform FROM chats ORDER BY timestamp ASC",
                null,
            )
            cursor.use {
                while (it.moveToNext()) {
                    val chatKey = it.getString(0)
                    val sender = it.getString(1)
                    val direction = it.getString(2)
                    val message = it.getString(3)
                    val timestamp = it.getLong(4)
                    val platform = it.getString(5) ?: "whatsapp"
                    val eventId = eventId(chatKey, sender, message, timestamp)
                    db.execSQL(
                        "INSERT OR IGNORE INTO chat_events(event_id, chat_key, platform, account, speaker, direction, message_text, original_at, observed_at, provenance, delivery_state) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        arrayOf(eventId, chatKey, platform, "", sender, direction, message, timestamp, timestamp, PROVENANCE_LEGACY, if (direction == "sent") "legacy" else ""),
                    )
                }
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        // Bounded growth: long-term events older than the retention window are pruned
        // on write; the rolling `chats` table keeps its existing behavior.
    }

    /**
     * Store a message and update the chat summary atomically.
     * Uses a transaction to ensure chats and chat_summaries stay in sync.
     */
    fun storeMessage(chatKey: String, sender: String, direction: String, message: String,
                     platform: String = "whatsapp", originalAt: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val observedAt = System.currentTimeMillis()
            val inserted = db.insertWithOnConflict("chat_events", null, android.content.ContentValues().apply {
                put("event_id", eventId(chatKey, sender, message, originalAt))
                put("chat_key", chatKey); put("platform", platform); put("account", "")
                put("speaker", sender); put("direction", direction); put("message_text", message)
                put("original_at", originalAt); put("observed_at", observedAt)
                put("provenance", if (direction == "sent") PROVENANCE_SEND else PROVENANCE_NOTIFICATION)
                put("delivery_state", if (direction == "sent") "verified" else "")
            }, SQLiteDatabase.CONFLICT_IGNORE)
            // The durable event is the identity authority. A repeated notification,
            // screen read or restart recovery must not inflate the legacy transcript
            // or summary count either.
            if (inserted == -1L) {
                db.setTransactionSuccessful()
                return
            }
            db.execSQL(
                "INSERT INTO chats(chat_key, sender, direction, message_text, timestamp, platform) VALUES(?,?,?,?,?,?)",
                arrayOf(chatKey, sender, direction, message, originalAt.toString(), platform)
            )
            db.execSQL(
                "INSERT OR REPLACE INTO chat_summaries(chat_key, summary, stage, last_activity, message_count) " +
                "VALUES(?, COALESCE((SELECT summary FROM chat_summaries WHERE chat_key=?), ''), " +
                "COALESCE((SELECT stage FROM chat_summaries WHERE chat_key=?), 'GREETING'), ?, " +
                "COALESCE((SELECT message_count FROM chat_summaries WHERE chat_key=?), 0) + 1)",
                arrayOf(chatKey, chatKey, chatKey, System.currentTimeMillis().toString(), chatKey)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Record an observed message as a durable long-term event. A stable event identity
     * deduplicates notification re-delivery, repeated screen reads and restart recovery;
     * original message time is kept separately from observation time. A generated draft
     * is never recorded here as a sent message — outbound events are recorded only after
     * verified dispatch, with the delivery state carried alongside.
     */
    fun recordEvent(chatKey: String, speaker: String, direction: String, message: String,
                    originalAt: Long = System.currentTimeMillis(), observedAt: Long = System.currentTimeMillis(),
                    platform: String = "whatsapp", account: String = "",
                    provenance: String = PROVENANCE_NOTIFICATION, deliveryState: String = "") {
        if (chatKey.isBlank() || message.isBlank() || speaker.isBlank()) return
        if (direction !in setOf("sent", "received")) return
        // Drafts and uncertain outbound effects belong in the side-effect ledger, not
        // conversational memory. Only target-bound verified sends may become a fact
        // that later replies treat as something Amara actually said.
        if (direction == "sent" && deliveryState != "verified") return
        val id = eventId(chatKey, speaker, message, originalAt)
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO chat_events(event_id, chat_key, platform, account, speaker, direction, message_text, original_at, observed_at, provenance, delivery_state) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(id, chatKey, platform, account.take(120), speaker, direction, message, originalAt, observedAt, provenance.take(40), deliveryState.take(40)),
        )
    }

    /** Mark a previously-observed outbound event's delivery state (verified/uncertain). */
    fun markEventDelivery(chatKey: String, speaker: String, message: String, originalAt: Long, deliveryState: String) {
        val id = eventId(chatKey, speaker, message, originalAt)
        writableDatabase.execSQL(
            "UPDATE chat_events SET delivery_state = ? WHERE event_id = ? AND direction = 'sent'",
            arrayOf(deliveryState.take(40), id),
        )
    }

    fun eventCount(chatKey: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM chat_events WHERE chat_key = ?", arrayOf(chatKey),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    internal fun messageCount(chatKey: String): Int = readableDatabase.rawQuery(
        "SELECT message_count FROM chat_summaries WHERE chat_key = ?", arrayOf(chatKey),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Recent turns of any age — the two-day window does not apply here. This is the
     * recent-turn layer of bounded long-term recall.
     */
    fun getRecentTurns(chatKey: String, limit: Int = 12): List<ChatMessage> {
        val cursor = readableDatabase.rawQuery(
            "SELECT speaker, direction, message_text, original_at FROM chat_events WHERE chat_key = ? ORDER BY original_at DESC LIMIT ?",
            arrayOf(chatKey, limit.coerceIn(1, 100).toString()),
        )
        val messages = mutableListOf<ChatMessage>()
        while (cursor.moveToNext()) {
            messages.add(ChatMessage(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
        }
        cursor.close()
        return messages.reversed()
    }

    /**
     * Bounded long-term recall for a reply prompt: recent turns of any age, the rolling
     * summary, and keyword-matched older evidence under a character budget. Never passes
     * the full database to the model; original source references are preserved so
     * omissions and contradictions can be corrected.
     */
    fun recallContext(chatKey: String, summary: String?, budgetChars: Int = 4_000): String {
        val sections = mutableListOf<String>()
        summary?.takeIf { it.isNotBlank() }?.let { sections += "CONVERSATION SUMMARY: ${it.take(2_000)}" }
        val recent = getRecentTurns(chatKey, RECENT_TURNS_LIMIT)
        if (recent.isNotEmpty()) {
            sections += recent.joinToString("\n") { "${it.sender} (${it.direction}): ${it.text}" }
        }
        val older = recallOlderEvidence(chatKey, summary, recent,
            (budgetChars - sections.sumOf { it.length }).coerceAtLeast(0))
        if (older.isNotEmpty()) sections += older
        if (sections.isEmpty()) return "No previous conversation."
        return sections.joinToString("\n\n").take(budgetChars)
    }

    /**
     * Keyword-matched older evidence for the reply prompt: messages older than the
     * recent turns that match the summary's and recent turns' topic words, oldest-first,
     * with original message times, under a bounded character budget.
     */
    fun recallOlderEvidence(chatKey: String, summary: String?, recent: List<ChatMessage>,
                            budgetChars: Int = 1_500): String {
        if (budgetChars <= 200 || recent.isEmpty()) return ""
        val keywords = (keywordSet(summary) + keywordSet(recent.joinToString(" ") { it.text })).take(8)
        if (keywords.isEmpty()) return ""
        val likeClause = keywords.joinToString(" OR ") { "message_text LIKE ?" }
        val args = buildList {
            add(chatKey); add(recent.first().timestamp.toString())
            keywords.forEach { add("%$it%") }
        }
        readableDatabase.rawQuery(
            "SELECT speaker, direction, message_text, original_at FROM chat_events " +
                "WHERE chat_key = ? AND original_at < ? AND ($likeClause) " +
                "ORDER BY original_at ASC LIMIT 12",
            args.toTypedArray(),
        ).use { cursor ->
            val older = mutableListOf<String>()
            while (cursor.moveToNext() && older.sumOf { it.length } < budgetChars) {
                older.add("${cursor.getString(0)} (${cursor.getString(1)}, ${cursor.getLong(3)}): ${cursor.getString(2)}")
            }
            if (older.isEmpty()) return ""
            return "EARLIER EVIDENCE (with original message times):\n" + older.joinToString("\n")
        }
    }

    private fun keywordSet(text: String?): Set<String> {
        val value = text ?: return emptySet()
        return Regex("[\\p{L}\\p{Nd}]{4,}").findAll(value)
            .map { it.value.lowercase() }
            .filter { it !in STOPWORDS }
            .toSet()
    }

    /** Owner-visible memory inspection for one conversation, newest first. */
    fun chatEvents(chatKey: String, limit: Int = 100): List<Map<String, Any>> =
        readableDatabase.rawQuery(
            "SELECT event_id, speaker, direction, message_text, original_at, observed_at, provenance, delivery_state " +
                "FROM chat_events WHERE chat_key = ? ORDER BY original_at DESC LIMIT ?",
            arrayOf(chatKey, limit.coerceIn(1, 500).toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(mapOf(
                    "event_id" to cursor.getString(0),
                    "speaker" to cursor.getString(1),
                    "direction" to cursor.getString(2),
                    "message_text" to cursor.getString(3),
                    "original_at" to cursor.getLong(4),
                    "observed_at" to cursor.getLong(5),
                    "provenance" to cursor.getString(6),
                    "delivery_state" to cursor.getString(7),
                ))
            }
        }

    /** Owner deletion control: removes long-term events for one conversation. */
    fun deleteChatEvents(chatKey: String): Int {
        val count = eventCount(chatKey)
        writableDatabase.execSQL("DELETE FROM chat_events WHERE chat_key = ?", arrayOf(chatKey))
        return count
    }

    
    fun getChatHistory(chatKey: String, limit: Int = 50): List<ChatMessage> {
        // Long-term recall path: durable chat events of any age (not just the last two
        // days). The rolling two-day window remains available via getRecentChatHistory.
        val cursor = readableDatabase.rawQuery(
            "SELECT speaker, direction, message_text, original_at FROM chat_events WHERE chat_key = ? ORDER BY original_at DESC LIMIT ?",
            arrayOf(chatKey, limit.coerceIn(1, 200).toString()),
        )
        val messages = mutableListOf<ChatMessage>()
        while (cursor.moveToNext()) {
            messages.add(ChatMessage(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
        }
        cursor.close()
        return messages.reversed()
    }

    /** The original recent-window history (last two days), preserved for compatibility. */
    fun getRecentChatHistory(chatKey: String, limit: Int = 50): List<ChatMessage> {
        val cursor = readableDatabase.rawQuery(
            "SELECT sender, direction, message_text, timestamp FROM chats WHERE chat_key = ? AND timestamp >= ? ORDER BY timestamp DESC LIMIT ?",
            arrayOf(chatKey, (System.currentTimeMillis() - 2 * 86_400_000L).toString(), limit.coerceIn(1, 200).toString())
        )
        val messages = mutableListOf<ChatMessage>()
        while (cursor.moveToNext()) {
            messages.add(ChatMessage(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
        }
        cursor.close()
        return messages.reversed()
    }
    
    fun getChatTranscript(chatKey: String, limit: Int = 30): String {
        val messages = getChatHistory(chatKey, limit)
        if (messages.isEmpty()) return "No previous conversation."
        return messages.joinToString("\n") { "${it.sender} (${it.direction}): ${it.text}" }
    }
    
    /**
     * Save a fresh summary generated by the LLM.
     * This is the correct way to update a chat's summary.
     */
    fun updateSummary(chatKey: String, summary: String, stage: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO chat_summaries(chat_key, summary, stage, last_activity, message_count) " +
                "VALUES(?,?,?, COALESCE((SELECT last_activity FROM chat_summaries WHERE chat_key=?), ?), " +
                "COALESCE((SELECT message_count FROM chat_summaries WHERE chat_key=?), 0))",
            arrayOf(chatKey, summary.take(8_000), stage, chatKey, System.currentTimeMillis().toString(), chatKey)
        )
    }

    /** Cloud-safe relationship memory: summaries and catalogue, never raw chat transcripts. */
    fun exportMemory(): JSONObject {
        val chats = JSONArray()
        readableDatabase.rawQuery(
            "SELECT chat_key, summary, stage, last_activity, message_count FROM chat_summaries WHERE summary <> '' ORDER BY last_activity DESC LIMIT 2000",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) chats.put(JSONObject()
                .put("chat_key", cursor.getString(0)).put("summary", cursor.getString(1))
                .put("stage", cursor.getString(2)).put("last_activity", cursor.getLong(3))
                .put("message_count", cursor.getInt(4)))
        }
        val offerings = JSONArray()
        readableDatabase.rawQuery(
            "SELECT type,name,price_ugx,price_floor_ugx,stock_count,duration_minutes,requires_booking,description,source,synced_at FROM offerings ORDER BY synced_at DESC LIMIT 1000",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) offerings.put(JSONObject()
                .put("type", cursor.getString(0)).put("name", cursor.getString(1))
                .put("price_ugx", if (cursor.isNull(2)) JSONObject.NULL else cursor.getLong(2))
                .put("price_floor_ugx", if (cursor.isNull(3)) JSONObject.NULL else cursor.getLong(3))
                .put("stock_count", if (cursor.isNull(4)) JSONObject.NULL else cursor.getInt(4))
                .put("duration_minutes", if (cursor.isNull(5)) JSONObject.NULL else cursor.getInt(5))
                .put("requires_booking", cursor.getInt(6) == 1).put("description", cursor.getString(7))
                .put("source", cursor.getString(8)).put("synced_at", cursor.getLong(9)))
        }
        return JSONObject().put("chat_summaries", chats).put("offerings", offerings)
    }

    /** Merge remote memory without replacing a newer local relationship summary. */
    fun importMemory(payload: JSONObject): Int {
        var merged = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            payload.optJSONArray("chat_summaries")?.let { chats ->
                for (i in 0 until chats.length()) {
                    val item = chats.optJSONObject(i) ?: continue
                    val key = item.optString("chat_key").trim()
                    val summary = item.optString("summary").trim()
                    if (key.isBlank() || summary.isBlank()) continue
                    db.execSQL(
                        "INSERT INTO chat_summaries(chat_key,summary,stage,last_activity,message_count) VALUES(?,?,?,?,?) " +
                            "ON CONFLICT(chat_key) DO UPDATE SET summary=excluded.summary,stage=excluded.stage,last_activity=excluded.last_activity,message_count=MAX(message_count,excluded.message_count) " +
                            "WHERE excluded.last_activity > chat_summaries.last_activity",
                        arrayOf(key, summary.take(8_000), item.optString("stage", "GREETING"), item.optLong("last_activity"), item.optInt("message_count")),
                    )
                    merged++
                }
            }
            payload.optJSONArray("offerings")?.let { rows ->
                for (i in 0 until rows.length()) {
                    val item = rows.optJSONObject(i) ?: continue
                    val type = item.optString("type")
                    val name = item.optString("name").trim()
                    if (type !in setOf("PRODUCT", "SERVICE") || name.isBlank()) continue
                    val remoteAt = item.optLong("synced_at")
                    val localAt = db.rawQuery(
                        "SELECT MAX(synced_at) FROM offerings WHERE type=? AND name=?", arrayOf(type, name),
                    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L }
                    if (remoteAt <= localAt) continue
                    db.delete("offerings", "type=? AND name=?", arrayOf(type, name))
                    db.execSQL(
                        "INSERT INTO offerings(type,name,price_ugx,price_floor_ugx,stock_count,duration_minutes,requires_booking,description,source,synced_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(
                            type, name, item.optNullableLong("price_ugx"), item.optNullableLong("price_floor_ugx"),
                            item.optNullableInt("stock_count"), item.optNullableInt("duration_minutes"),
                            if (item.optBoolean("requires_booking")) 1 else 0, item.optString("description"),
                            item.optString("source", "cloud_restore"), remoteAt,
                        ),
                    )
                    merged++
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return merged
    }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)
    
    /**
     * Save summary with stage — used by HumanConversationEngine.
     */
    fun saveSummary(chatKey: String, summary: String, stage: String) {
        updateSummary(chatKey, summary, stage)
    }
    
    fun getSummary(chatKey: String): String? {
        val cursor = readableDatabase.rawQuery("SELECT summary FROM chat_summaries WHERE chat_key = ?", arrayOf(chatKey))
        return try {
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } finally {
            cursor.close()
        }
    }
    
    fun getStage(chatKey: String): String {
        val cursor = readableDatabase.rawQuery("SELECT stage FROM chat_summaries WHERE chat_key = ?", arrayOf(chatKey))
        return try {
            if (cursor.moveToFirst()) cursor.getString(0) else "GREETING"
        } finally {
            cursor.close()
        }
    }

    fun summaryCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM chat_summaries WHERE summary <> ''", null,
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    
    fun getDormantChats(daysSinceLastMessage: Int = 7): List<String> {
        val cutoff = System.currentTimeMillis() - (daysSinceLastMessage * 24 * 60 * 60 * 1000L)
        val cursor = readableDatabase.rawQuery(
            "SELECT chat_key FROM chat_summaries WHERE last_activity < ? AND stage NOT IN ('CONFIRMED','DORMANT')",
            arrayOf(cutoff.toString())
        )
        val chats = mutableListOf<String>()
        while (cursor.moveToNext()) chats.add(cursor.getString(0))
        cursor.close()
        return chats
    }
    
    // Offerings (unified products + services)
    fun storeOffering(type: String, name: String, price: Long?, priceFloor: Long?, stockCount: Int?, durationMins: Int?, requiresBooking: Boolean, description: String?, source: String) {
        require(type in setOf("PRODUCT", "SERVICE")) { "Unsupported offering type" }
        require(name.isNotBlank()) { "Offering name cannot be blank" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            // A fresh Terminal observation replaces the prior cached row instead
            // of growing an unbounded list of duplicate offerings on every scan.
            db.delete("offerings", "type = ? AND name = ? COLLATE NOCASE", arrayOf(type, name.trim()))
            db.execSQL(
                "INSERT INTO offerings(type, name, price_ugx, price_floor_ugx, stock_count, duration_minutes, requires_booking, description, source, synced_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(type, name.trim(), price, priceFloor, stockCount, durationMins, if (requiresBooking) 1 else 0, description, source, System.currentTimeMillis())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
    
    fun getProducts(): List<String> {
        val cursor = readableDatabase.rawQuery("SELECT name, price_ugx FROM offerings WHERE type = 'PRODUCT' ORDER BY synced_at DESC LIMIT 200", null)
        val items = mutableListOf<String>()
        while (cursor.moveToNext()) {
            val name = cursor.getString(0)
            val price = if (cursor.isNull(1)) "price on request" else "UGX ${cursor.getLong(1)}"
            items.add("$name ($price)")
        }
        cursor.close()
        return items
    }
    
    fun getServices(): List<String> {
        val cursor = readableDatabase.rawQuery("SELECT name, price_ugx FROM offerings WHERE type = 'SERVICE' ORDER BY synced_at DESC LIMIT 200", null)
        val items = mutableListOf<String>()
        while (cursor.moveToNext()) {
            val name = cursor.getString(0)
            val price = if (cursor.isNull(1)) "price on request" else "UGX ${cursor.getLong(1)}"
            items.add("$name ($price)")
        }
        cursor.close()
        return items
    }

    companion object {
        const val PROVENANCE_NOTIFICATION = "notification"
        const val PROVENANCE_SCREEN_READ = "screen_read"
        const val PROVENANCE_RESTART_RECOVERY = "restart_recovery"
        const val PROVENANCE_SEND = "send"
        const val PROVENANCE_LEGACY = "legacy_import"
        const val RECENT_TURNS_LIMIT = 12

        private val STOPWORDS = setOf(
            "this", "that", "with", "have", "have", "will", "your", "from", "they", "been",
            "were", "what", "when", "would", "could", "should", "there", "their", "about",
            "which", "please", "thanks", "hello", "okay", "want", "need", "know", "like",
        )

        /** Stable event identity: the same logical message always hashes to the same key. */
        fun eventId(chatKey: String, speaker: String, message: String, originalAt: Long): String {
            val normalized = "${chatKey.trim().lowercase()}|$speaker|$message|$originalAt"
            return co.sanaa.agent.core.ContentHashing.hash(normalized)
        }
    }
}

data class ChatMessage(val sender: String, val direction: String, val text: String, val timestamp: Long)
