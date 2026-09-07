package co.sanaa.agent.core.work

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Room-style persistent priority work queue backed by SQLite.
 * 
 * Rules:
 * - Dedupe on dedupeKey (re-propose refreshes value, never duplicates)
 * - Per-domain cap: 25 items
 * - Default TTL: 48h if no deadline
 * - Stale work is worse than no work
 */
class WorkQueue(context: Context) : SQLiteOpenHelper(context, "amara_work_queue.db", null, 1) {

    private val evaluation = co.sanaa.agent.core.EvaluationJournal(context)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS work_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dedupe_key TEXT NOT NULL UNIQUE,
                domain TEXT NOT NULL,
                kind TEXT NOT NULL,
                payload TEXT DEFAULT '{}',
                base_value_kes REAL NOT NULL,
                deadline INTEGER,
                urgency_half_life_hours REAL NOT NULL,
                estimated_screen_seconds INTEGER NOT NULL,
                requires TEXT DEFAULT '',
                risk_tier TEXT DEFAULT 'LOW',
                created_at INTEGER NOT NULL,
                attempt INTEGER DEFAULT 0,
                status TEXT DEFAULT 'PENDING',
                in_flight_until INTEGER,
                not_before INTEGER DEFAULT 0
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_work_status ON work_items(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_work_dedupe ON work_items(dedupe_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /**
     * Add a work item to the queue.
     * Returns: DEDUPED if already exists (value refreshed), ACCEPTED if new, REJECTED_CAP if domain full.
     */
    enum class OfferResult { DEDUPED, ACCEPTED, REJECTED_CAP }

    @Synchronized
    fun offer(item: WorkItem): OfferResult {
        val db = writableDatabase
        evaluation.record("offered", item.dedupeKey, org.json.JSONObject().put("kind", item.kind.name)
            .put("observed_at", item.payload.optLong("inbound_observed_at"))
            .put("eligible_at", item.payload.optLong("owner_takeover_at")))

        // Periodic sources describe the latest opportunity, not a debt that must be
        // replayed once for every missed time bucket. Keep at most one pending item
        // for these kinds so an idle/owner-active phone cannot later burst-post or
        // spend hours executing stale observations.
        if (item.kind in SINGLE_PENDING_KINDS) {
            db.delete(
                "work_items",
                "status = 'PENDING' AND kind = ? AND dedupe_key != ?",
                arrayOf(item.kind.name, item.dedupeKey),
            )
        } else if (item.kind == WorkKind.WA_REPLY_INBOUND) {
            compactPendingConversation(db, item)
        }

        // Check for existing
        val existing = db.rawQuery("SELECT id, status FROM work_items WHERE dedupe_key = ?", arrayOf(item.dedupeKey))
        val exists = existing.moveToFirst()
        val existingStatus = if (exists) existing.getString(1) else null
        existing.close()

        if (exists) {
            // A completed bucket key is a short-lived tombstone. Keeping it
            // prevents periodic sources from rerunning on every heartbeat.
            if (existingStatus == "COMPLETED") return OfferResult.DEDUPED
            // Refresh value and update timestamp
            db.execSQL(
                "UPDATE work_items SET base_value_kes = ?, created_at = ? WHERE dedupe_key = ?",
                arrayOf(item.baseValueKes.toString(), System.currentTimeMillis().toString(), item.dedupeKey)
            )
            return OfferResult.DEDUPED
        }

        // Check domain cap
        val domainCount = db.rawQuery(
            "SELECT COUNT(*) FROM work_items WHERE domain = ? AND status = 'PENDING'",
            arrayOf(item.domain.name)
        )
        val count = if (domainCount.moveToFirst()) domainCount.getInt(0) else 0
        domainCount.close()

        if (count >= 25) {
            evaluation.record("rejected_capacity", item.dedupeKey)
            return OfferResult.REJECTED_CAP
        }

        // Insert new item
        db.execSQL(
            """INSERT INTO work_items 
               (dedupe_key, domain, kind, payload, base_value_kes, deadline, urgency_half_life_hours,
                estimated_screen_seconds, requires, risk_tier, created_at, attempt, not_before, status)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')""",
            arrayOf(
                item.dedupeKey, item.domain.name, item.kind.name, item.payload.toString(),
                item.baseValueKes.toString(), item.deadline?.toString(), item.urgencyHalfLifeHours.toString(),
                item.estimatedScreenSeconds.toString(), item.requires.joinToString(","),
                item.riskTier.name, System.currentTimeMillis().toString(), item.attempt.toString(),
                item.payload.optLong("owner_takeover_at", 0L).toString()
            )
        )
        evaluation.record("accepted", item.dedupeKey)
        return OfferResult.ACCEPTED
    }

    fun evaluationSnapshot(): org.json.JSONArray {
        val rows = org.json.JSONArray()
        readableDatabase.rawQuery("SELECT dedupe_key,kind,status,payload FROM work_items WHERE status!='COMPLETED'", null).use { c ->
            while(c.moveToNext()) {
                val payload=org.json.JSONObject(c.getString(3))
                rows.put(org.json.JSONObject().put("key",co.sanaa.agent.core.ContentHashing.hash(c.getString(0)))
                    .put("kind",c.getString(1)).put("queue_status",c.getString(2))
                    .put("observed_at",payload.optLong("inbound_observed_at")))
            }
        }
        return rows
    }

    fun nextWakeDelayMillis(now: Long): Long = readableDatabase.rawQuery(
        "SELECT MIN(not_before) FROM work_items WHERE status = 'PENDING' AND not_before > ?",
        arrayOf(now.toString()),
    ).use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0))
            (cursor.getLong(0) - now).coerceIn(1L, 30_000L)
        else 30_000L
    }

    /**
     * Get the best pending work item (highest score) that is eligible to run now.
     * Returns null if nothing is available.
     */
    @Synchronized
    fun peekBest(
        now: Long,
        excludeKinds: Set<WorkKind> = emptySet(),
        successRate: (WorkKind) -> Double = { 0.5 },
        timeFactor: (WorkKind) -> Double = { 1.0 },
    ): WorkItem? {
        val db = readableDatabase
        val notBefore = now
        val notInFlight = now

        val kindExclude = if (excludeKinds.isNotEmpty()) {
            " AND kind NOT IN (${excludeKinds.joinToString(",") { "'${it.name}'" }})"
        } else ""

        val cursor = db.rawQuery(
            """SELECT id, dedupe_key, domain, kind, payload, base_value_kes, deadline,
                      urgency_half_life_hours, estimated_screen_seconds, requires, risk_tier,
                      created_at, attempt
               FROM work_items
               WHERE (status = 'PENDING' OR (status = 'IN_FLIGHT' AND in_flight_until < ?))
                 AND (not_before = 0 OR not_before <= ?)
                 AND (in_flight_until IS NULL OR in_flight_until < ?)
                 $kindExclude
               ORDER BY CASE WHEN kind = 'WA_REPLY_INBOUND' THEN 0 WHEN kind = 'TIKTOK_POST_PUBLISH' THEN 1 WHEN kind = 'WA_FOLLOWUP' THEN 2 ELSE 3 END, CASE WHEN kind = 'WA_REPLY_INBOUND' THEN 0 ELSE base_value_kes END DESC, created_at ASC
               LIMIT 50""",
            arrayOf(notInFlight.toString(), notBefore.toString(), notInFlight.toString())
        )

        val items = mutableListOf<WorkItem>()
        while (cursor.moveToNext()) {
            val item = WorkItem(
                dedupeKey = cursor.getString(1),
                domain = Domain.valueOf(cursor.getString(2)),
                kind = WorkKind.valueOf(cursor.getString(3)),
                payload = runCatching { org.json.JSONObject(cursor.getString(4)) }.getOrDefault(org.json.JSONObject()),
                baseValueKes = cursor.getDouble(5),
                deadline = if (cursor.isNull(6)) null else cursor.getLong(6),
                urgencyHalfLifeHours = cursor.getDouble(7),
                estimatedScreenSeconds = cursor.getInt(8),
                requires = cursor.getString(9).split(",").filter { it.isNotBlank() }.map { Capability.valueOf(it) }.toSet(),
                riskTier = RiskTier.valueOf(cursor.getString(10)),
                createdAt = cursor.getLong(11),
                attempt = cursor.getInt(12),
            )
            if (!item.isExpired) items.add(item)
        }
        cursor.close()

        // Score and return best
        val context = ScoringContext(
            now = now,
            successRate = successRate,
            hoursSinceDomainTouched = { 0.0 },
            todFactor = timeFactor,
        )
        // A waiting person outranks scheduled promotion/research, even after failures
        // have lowered this kind's learned success rate. Finish the current action first.
        val replies = items.filter { it.kind == WorkKind.WA_REPLY_INBOUND }
        if (replies.isNotEmpty()) return replies.minByOrNull { it.createdAt }
        // Due publication precedes optional engagement/research. Inbound still wins above.
        items.filter { it.kind == WorkKind.TIKTOK_POST_PUBLISH }.minByOrNull { it.createdAt }?.let { return it }
        items.filter { it.kind == WorkKind.WA_FOLLOWUP }.minByOrNull { it.createdAt }?.let { return it }
        return items.maxByOrNull { WorkScorer.score(it, context) }
    }

    /**
     * Simple scoring for queue ordering. Full scoring is in WorkScorer.
     */
    /**
     * Mark an item as in-flight (leased for execution).
     * Returns true if successfully claimed.
     */
    @Synchronized
    fun markInFlight(dedupeKey: String, leaseMs: Long = 300_000): Boolean {
        val db = writableDatabase
        val statement = db.compileStatement(
            "UPDATE work_items SET status = 'IN_FLIGHT', in_flight_until = ? WHERE dedupe_key = ? AND (status = 'PENDING' OR (status = 'IN_FLIGHT' AND in_flight_until < ?))",
        )
        statement.bindLong(1, System.currentTimeMillis() + leaseMs)
        statement.bindString(2, dedupeKey)
        statement.bindLong(3, System.currentTimeMillis())
        val claimed = statement.use { it.executeUpdateDelete() == 1 }
        if (claimed) evaluation.record("started", dedupeKey)
        return claimed
    }

    /** Bind randomized post content durably before any external action. */
    @Synchronized
    fun bindTikTokPayload(dedupeKey: String, payload: org.json.JSONObject): Boolean =
        writableDatabase.compileStatement(
            "UPDATE work_items SET payload = ? WHERE dedupe_key = ? AND kind = 'TIKTOK_POST_PUBLISH' AND status = 'IN_FLIGHT'",
        ).use { statement ->
            statement.bindString(1, payload.toString())
            statement.bindString(2, dedupeKey)
            statement.executeUpdateDelete() == 1
        }

    @Synchronized
    fun bindGroupPayload(dedupeKey: String, payload: org.json.JSONObject): Boolean =
        writableDatabase.compileStatement(
            "UPDATE work_items SET payload = ? WHERE dedupe_key = ? AND kind = 'WA_BROADCAST' AND status = 'IN_FLIGHT'",
        ).use {
            it.bindString(1, payload.toString())
            it.bindString(2, dedupeKey)
            it.executeUpdateDelete() == 1
        }

    /** Bind before dispatch; retries may reuse but never replace a persisted follow-up draft. */
    @Synchronized
    fun bindFollowUpPayload(dedupeKey: String, payload: org.json.JSONObject): Boolean {
        val message=payload.optString("message")
        if(message.isBlank()) return false
        val old=readableDatabase.rawQuery(
            "SELECT payload FROM work_items WHERE dedupe_key=? AND kind='WA_FOLLOWUP' AND status='IN_FLIGHT'",
            arrayOf(dedupeKey)).use { if(it.moveToFirst()) org.json.JSONObject(it.getString(0)) else null } ?: return false
        val existing=old.optString("message")
        if(existing.isNotBlank()) return existing==message
        old.put("message",message)
        return writableDatabase.compileStatement(
            "UPDATE work_items SET payload=? WHERE dedupe_key=? AND kind='WA_FOLLOWUP' AND status='IN_FLIGHT'").use {
            it.bindString(1,old.toString());it.bindString(2,dedupeKey);it.executeUpdateDelete()==1
        }
    }

    /** Complete an item while retaining its dedupe key until stale cleanup. */
    @Synchronized
    fun complete(dedupeKey: String) {
        writableDatabase.execSQL(
            "UPDATE work_items SET status = 'COMPLETED', in_flight_until = NULL WHERE dedupe_key = ?",
            arrayOf(dedupeKey),
        )
        evaluation.record("completed", dedupeKey)
    }

    /** Unresolved customer work remains visible and is never silently called complete. */
    @Synchronized
    fun requireReview(item: WorkItem, reason: String) {
        val payload=org.json.JSONObject(item.payload.toString()).put("review_reason",reason)
            .put("review_at",System.currentTimeMillis())
        writableDatabase.execSQL("UPDATE work_items SET status='NEEDS_REVIEW',payload=?,in_flight_until=NULL WHERE dedupe_key=?",
            arrayOf(payload.toString(),item.dedupeKey))
        evaluation.record("needs_review",item.dedupeKey,org.json.JSONObject().put("kind",item.kind.name))
    }

    /**
     * Requeue a work item for later retry.
     */
    @Synchronized
    fun requeue(dedupeKey: String, notBefore: Long, attempt: Int) {
        writableDatabase.execSQL(
            "UPDATE work_items SET status = 'PENDING', not_before = ?, attempt = ?, in_flight_until = NULL WHERE dedupe_key = ?",
            arrayOf(notBefore.toString(), attempt.toString(), dedupeKey)
        )
        evaluation.record("deferred", dedupeKey, org.json.JSONObject().put("not_before", notBefore).put("attempt", attempt))
    }

    /** Remove not-yet-started work when its owner-controlled channel is disabled. */
    @Synchronized
    fun cancelPending(kinds: Set<WorkKind>): Int {
        if (kinds.isEmpty()) return 0
        val placeholders = kinds.joinToString(",") { "?" }
        val statement = writableDatabase.compileStatement(
            "DELETE FROM work_items WHERE status = 'PENDING' AND kind IN ($placeholders)",
        )
        kinds.forEachIndexed { index, kind -> statement.bindString(index + 1, kind.name) }
        return statement.executeUpdateDelete()
    }

    /** Debug canaries are one-shot supervision artifacts and must never survive an
     * app update as ordinary autonomous work. */
    @Synchronized
    fun cancelPendingOwnerCanaries(): Int = writableDatabase.compileStatement(
        "DELETE FROM work_items WHERE status = 'PENDING' AND payload LIKE '%\"owner_canary\":true%'",
    ).executeUpdateDelete()

    /** Remove historical periodic buckets and superseded inbound messages while
     * preserving the newest actionable item. This also repairs queues created by
     * older builds before offer-time coalescing existed. */
    @Synchronized
    fun compactPendingBacklog(): Int {
        val db = writableDatabase
        var removed = 0
        SINGLE_PENDING_KINDS.forEach { kind ->
            val statement = db.compileStatement(
                "DELETE FROM work_items WHERE status = 'PENDING' AND kind = ? AND id != " +
                    "(SELECT MAX(id) FROM work_items WHERE status = 'PENDING' AND kind = ?)",
            )
            statement.bindString(1, kind.name)
            statement.bindString(2, kind.name)
            removed += statement.executeUpdateDelete()
        }

        val newestByConversation = linkedMapOf<String, Long>()
        val superseded = mutableListOf<Long>()
        db.rawQuery(
            "SELECT id, payload FROM work_items WHERE status = 'PENDING' AND kind = ? ORDER BY id DESC",
            arrayOf(WorkKind.WA_REPLY_INBOUND.name),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val payload = runCatching { org.json.JSONObject(cursor.getString(1)) }.getOrNull()
                val conversation = payload?.optString("conversation_identity").orEmpty()
                val message = payload?.optString("message")?.trim().orEmpty()
                if (payload?.optString("conversation").orEmpty().isBlank() ||
                    message.startsWith("Sending ", ignoreCase = true) ||
                    (!(payload?.optBoolean("is_missed_call", false) ?: false) &&
                        co.sanaa.agent.modules.WhatsAppNotificationParser.isNonConversationalEvent(message))) {
                    superseded += id
                    continue
                }
                if (conversation.isNotBlank() && newestByConversation.putIfAbsent(conversation, id) != null) {
                    superseded += id
                }
            }
        }
        superseded.forEach { id -> removed += db.delete("work_items", "id = ?", arrayOf(id.toString())) }
        return removed
    }

    private fun compactPendingConversation(db: SQLiteDatabase, item: WorkItem) {
        val conversation = item.payload.optString("conversation_identity").trim()
        if (conversation.isBlank()) return
        val ids = mutableListOf<Long>()
        db.rawQuery(
            "SELECT id, payload, dedupe_key FROM work_items WHERE status = 'PENDING' AND kind = ? AND dedupe_key != ?",
            arrayOf(WorkKind.WA_REPLY_INBOUND.name, item.dedupeKey),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val other = runCatching { org.json.JSONObject(cursor.getString(1)) }.getOrNull() ?: continue
                if (other.optString("conversation_identity") == conversation) {
                    ids += cursor.getLong(0)
                    evaluation.record("superseded", cursor.getString(2), org.json.JSONObject().put("replacement", co.sanaa.agent.core.ContentHashing.hash(item.dedupeKey)))
                }
            }
        }
        ids.forEach { id -> db.delete("work_items", "id = ?", arrayOf(id.toString())) }
    }

    /**
     * Expire stale items (deadline passed or default TTL exceeded).
     * Returns number of items expired.
     */
    @Synchronized
    fun expireStale(now: Long): Int {
        val ttl = now - (48L * 3_600_000) // 48h default TTL
        val db = writableDatabase
        val statement = db.compileStatement(
            "DELETE FROM work_items WHERE status != 'NEEDS_REVIEW' AND (deadline < ? OR created_at < ?) AND (status != 'IN_FLIGHT' OR in_flight_until < ?)",
        )
        statement.bindLong(1, now)
        statement.bindLong(2, ttl)
        statement.bindLong(3, now)
        return statement.executeUpdateDelete()
    }

    /**
     * Get count of pending items.
     */
    @Synchronized
    fun pendingCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM work_items WHERE status = 'PENDING'", null)
        return if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    /**
     * Get all pending items (for debugging/daily report).
     */
    @Synchronized
    fun allPending(): List<WorkItem> {
        val cursor = readableDatabase.rawQuery(
            """SELECT dedupe_key, domain, kind, payload, base_value_kes, deadline,
                      urgency_half_life_hours, estimated_screen_seconds, requires, risk_tier,
                      created_at, attempt
               FROM work_items WHERE status = 'PENDING' ORDER BY base_value_kes DESC""",
            null
        )
        val items = mutableListOf<WorkItem>()
        while (cursor.moveToNext()) {
            items.add(
                WorkItem(
                    dedupeKey = cursor.getString(0),
                    domain = Domain.valueOf(cursor.getString(1)),
                    kind = WorkKind.valueOf(cursor.getString(2)),
                    payload = runCatching { org.json.JSONObject(cursor.getString(3)) }.getOrDefault(org.json.JSONObject()),
                    baseValueKes = cursor.getDouble(4),
                    deadline = if (cursor.isNull(5)) null else cursor.getLong(5),
                    urgencyHalfLifeHours = cursor.getDouble(6),
                    estimatedScreenSeconds = cursor.getInt(7),
                    requires = cursor.getString(8).split(",").filter { it.isNotBlank() }.map { Capability.valueOf(it) }.toSet(),
                    riskTier = RiskTier.valueOf(cursor.getString(9)),
                    createdAt = cursor.getLong(10),
                    attempt = cursor.getInt(11),
                )
            )
        }
        cursor.close()
        return items
    }

    companion object {
        private val SINGLE_PENDING_KINDS = setOf(
            WorkKind.SOKO_AUDIT,
            WorkKind.SOKO_INVENTORY_CHECK,
            WorkKind.TIKTOK_COMMENT_REPLY,
            WorkKind.TIKTOK_POST_PUBLISH,
            WorkKind.TIKTOK_ANALYTICS_CHECK,
            WorkKind.JIJI_SCRAPE,
            WorkKind.JUMIA_CAPTURE,
            WorkKind.MARKET_ANALYSIS,
            WorkKind.INTERNAL_RECONCILIATION,
            WorkKind.INTERNAL_COMMERCIAL_CYCLE,
            WorkKind.INTERNAL_BRIEFING_PREP,
            WorkKind.INTERNAL_LEDGER_COMPACTION,
            WorkKind.INTERNAL_HEALTH_CHECK,
            WorkKind.INTERNAL_CONFIG_SYNC,
        )
    }

    @Synchronized
    fun dashboard(): Map<String, Any> {
        val counts = linkedMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT status, COUNT(*) FROM work_items GROUP BY status", null).use { cursor ->
            while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1)
        }
        return mapOf(
            "counts" to counts,
            "needsReview" to readableDatabase.rawQuery("SELECT dedupe_key,kind,payload FROM work_items WHERE status='NEEDS_REVIEW'",null).use { c ->
                buildList { while(c.moveToNext()) {
                    val p=org.json.JSONObject(c.getString(2))
                    add(mapOf("key" to c.getString(0),"kind" to c.getString(1),"reason" to p.optString("review_reason"),"reviewAt" to p.optLong("review_at")))
                } }
            },
            "items" to allPending().map { item -> mapOf(
                "key" to item.dedupeKey,
                "domain" to item.domain.name,
                "kind" to item.kind.name,
                "valueKes" to item.baseValueKes,
                "risk" to item.riskTier.name,
                "attempt" to item.attempt,
                "createdAt" to item.createdAt,
                "deadline" to item.deadline,
                "estimatedScreenSeconds" to item.estimatedScreenSeconds,
                "urgencyHalfLifeHours" to item.urgencyHalfLifeHours,
                "payload" to item.payload.toString(),
                "requires" to item.requires.map { it.name },
            ) },
        )
    }
}
