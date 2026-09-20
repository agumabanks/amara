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
class WorkQueue(private val context: Context) : SQLiteOpenHelper(context, "amara_work_queue.db", null, 1), java.io.Closeable {

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

    fun hasReadyCustomerOrPost(now: Long = System.currentTimeMillis(),
        kindAllowed: (WorkKind) -> Boolean = { true }): Boolean = readableDatabase.rawQuery(
        "SELECT DISTINCT kind FROM work_items WHERE status='PENDING' AND kind IN ('WA_REPLY_INBOUND','TIKTOK_POST_PUBLISH') AND not_before<=? AND (deadline IS NULL OR deadline>=?)",
        arrayOf(now.toString(),now.toString()),
    ).use { cursor ->
        var ready = false
        while (cursor.moveToNext()) if (kindAllowed(WorkKind.valueOf(cursor.getString(0)))) ready = true
        ready
    }

    /** A closed scheduled item is an audit tombstone and must never be replayed. */
    fun isOwnerClosed(dedupeKey: String): Boolean = readableDatabase.rawQuery(
        "SELECT status FROM work_items WHERE dedupe_key = ?", arrayOf(dedupeKey),
    ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == "OWNER_CLOSED" }

    @Synchronized
    fun offer(item: WorkItem): OfferResult {
        val db = writableDatabase
        // A replay must not compact newer work before its own tombstone is checked.
        val alreadyKnown = db.rawQuery("SELECT 1 FROM work_items WHERE dedupe_key = ?", arrayOf(item.dedupeKey))
            .use { it.moveToFirst() }
        if (alreadyKnown) return OfferResult.DEDUPED
        if (hasNewerInbound(item)) {
            evaluation.record("stale_inbound_ignored", item.dedupeKey)
            return OfferResult.DEDUPED
        }
        evaluation.record("offered", item.dedupeKey, org.json.JSONObject().put("kind", item.kind.name)
            .put("observed_at", item.payload.optLong("inbound_observed_at"))
            .put("eligible_at", item.payload.optLong("owner_takeover_at")))

        // Periodic sources describe the latest opportunity, not a debt that must be
        // replayed once for every missed time bucket. Keep at most one pending item
        // for these kinds so an idle/owner-active phone cannot later burst-post or
        // spend hours executing stale observations.
        if (item.kind in setOf(WorkKind.TIKTOK_COMMENT_REPLY, WorkKind.MARKET_ANALYSIS)) {
            fun family(payload: org.json.JSONObject): String? = when(item.kind) {
                WorkKind.TIKTOK_COMMENT_REPLY -> if(payload.optString("notification_id").isNotBlank() || payload.optString("community_post").isNotBlank()) null else "routine"
                else -> if(payload.optBoolean("manager_orders")) "manager_orders" else "market_review"
            }
            val bucket=family(item.payload)
            if(bucket!=null) {
                val ids=mutableListOf<Long>()
                db.rawQuery("SELECT id,payload FROM work_items WHERE status='PENDING' AND kind=? AND dedupe_key!=?",arrayOf(item.kind.name,item.dedupeKey)).use { c ->
                    while(c.moveToNext()) if(family(org.json.JSONObject(c.getString(1)))==bucket) ids.add(c.getLong(0))
                }
                ids.forEach { db.delete("work_items","id=?",arrayOf(it.toString())) }
            }
        } else if (item.kind in SINGLE_PENDING_KINDS) {
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
                maxOf(item.payload.optLong("owner_takeover_at", 0L),
                    if(item.kind==WorkKind.TIKTOK_COMMENT_REPLY) item.payload.optLong("community_not_before",0L) else 0L).toString()
            )
        )
        evaluation.record("accepted", item.dedupeKey)
        return OfferResult.ACCEPTED
    }

    /** Apply current owner hours to unclaimed work without clearing review holds or cooldowns. */
    @Synchronized
    fun refreshPendingChannelHours(whatsAppAlwaysOn: Boolean, tikTokAlwaysOn: Boolean): Int {
        val updates = mutableListOf<Pair<String, String>>()
        writableDatabase.rawQuery("SELECT dedupe_key,kind,payload FROM work_items WHERE status='PENDING' AND kind IN ('WA_REPLY_INBOUND','TIKTOK_POST_PUBLISH','TIKTOK_COMMENT_REPLY','TIKTOK_STORY_PUBLISH','TIKTOK_ANALYTICS_CHECK')", null).use { c ->
            while (c.moveToNext()) {
                val payload = runCatching { org.json.JSONObject(c.getString(2)) }.getOrNull() ?: continue
                if (payload.optBoolean("owner_command") || payload.optBoolean("owner_canary")) continue
                val allowed = if (c.getString(1) == "WA_REPLY_INBOUND") whatsAppAlwaysOn else tikTokAlwaysOn
                if (payload.optBoolean("owner_always_on", false) != allowed) {
                    payload.put("owner_always_on", allowed)
                    updates.add(c.getString(0) to payload.toString())
                }
            }
        }
        updates.forEach { (key, payload) ->
            writableDatabase.execSQL("UPDATE work_items SET payload=? WHERE dedupe_key=? AND status='PENDING'", arrayOf(payload, key))
        }
        return updates.size
    }

    data class MediaCleanupReference(val key: String, val kind: String, val status: String, val payload: String)

    @Synchronized
    fun <T> withMediaCleanupReferences(block: (List<MediaCleanupReference>) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val rows = db.rawQuery("SELECT dedupe_key,kind,status,payload FROM work_items", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        check((0..3).none { cursor.isNull(it) }) { "Incomplete work queue reference" }
                        val key = cursor.getString(0)
                        check(key.isNotBlank()) { "Invalid work queue reference" }
                        WorkKind.valueOf(cursor.getString(1))
                        val payload = cursor.getString(3)
                        org.json.JSONObject(payload)
                        add(MediaCleanupReference(key, cursor.getString(1), cursor.getString(2), payload))
                    }
                }
            }
            return block(rows).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    fun evaluationSnapshot(): org.json.JSONArray {
        val rows = org.json.JSONArray()
        readableDatabase.rawQuery("SELECT dedupe_key,kind,status,payload,not_before,created_at,deadline,attempt FROM work_items WHERE status!='COMPLETED'", null).use { c ->
            while(c.moveToNext()) {
                val payload=org.json.JSONObject(c.getString(3))
                rows.put(org.json.JSONObject().put("key",co.sanaa.agent.core.ContentHashing.hash(c.getString(0)))
                    .put("kind",c.getString(1)).put("queue_status",c.getString(2))
                    .put("observed_at",payload.optLong("inbound_observed_at"))
                    .put("message_at",payload.optLong("inbound_message_at"))
                    .put("review_reason",co.sanaa.agent.core.Redactor.redact(payload.optString("review_reason")).take(600))
                    .put("owner_disposition",payload.optString("owner_disposition"))
                    .put("not_before",c.getLong(4)).put("created_at",c.getLong(5))
                    .put("deadline",if(c.isNull(6)) org.json.JSONObject.NULL else c.getLong(6))
                    .put("attempt",c.getInt(7)))
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
        excludeKeys: Set<String> = emptySet(),
        successRate: (WorkKind) -> Double = { 0.5 },
        timeFactor: (WorkKind) -> Double = { 1.0 },
    ): WorkItem? {
        val db = readableDatabase
        val notBefore = now
        val notInFlight = now

        val kindExclude = if (excludeKinds.isNotEmpty()) {
            " AND kind NOT IN (${excludeKinds.joinToString(",") { "'${it.name}'" }})"
        } else ""

        val keyExclude = if (excludeKeys.isEmpty()) "" else " AND dedupe_key NOT IN (${excludeKeys.joinToString(",") { "?" }})"
        val cursor = db.rawQuery(
            """SELECT id, dedupe_key, domain, kind, payload, base_value_kes, deadline,
                      urgency_half_life_hours, estimated_screen_seconds, requires, risk_tier,
                      created_at, attempt
               FROM work_items
               WHERE (status = 'PENDING' OR (status = 'IN_FLIGHT' AND in_flight_until < ?))
                 AND (not_before = 0 OR not_before <= ?)
                 AND (in_flight_until IS NULL OR in_flight_until < ?)
                 $kindExclude
                 $keyExclude
               ORDER BY CASE WHEN kind = 'WA_REPLY_INBOUND' THEN 0 WHEN kind = 'TIKTOK_POST_PUBLISH' THEN 1 WHEN kind = 'WA_FOLLOWUP' THEN 2 ELSE 3 END, CASE WHEN kind = 'WA_REPLY_INBOUND' THEN 0 ELSE base_value_kes END DESC, created_at ASC
               LIMIT 50""",
            (listOf(notInFlight.toString(), notBefore.toString(), notInFlight.toString()) + excludeKeys).toTypedArray()
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
        val replies = items.filter { it.kind == WorkKind.WA_REPLY_INBOUND && !it.payload.optBoolean("manager_report") }
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

    /** Owner disposition only: never sends, retries or claims that delivery was verified. */
    @Synchronized
    fun closeReview(dedupeKey: String, disposition: String): Boolean {
        if (disposition !in setOf("handled_elsewhere", "no_longer_needed")) return false
        val payload = readableDatabase.rawQuery(
            "SELECT payload FROM work_items WHERE dedupe_key=? AND status='NEEDS_REVIEW'", arrayOf(dedupeKey),
        ).use { if (it.moveToFirst()) org.json.JSONObject(it.getString(0)) else null } ?: return false
        payload.put("owner_disposition", disposition).put("owner_reviewed_at", System.currentTimeMillis())
        val changed = writableDatabase.compileStatement(
            "UPDATE work_items SET status='OWNER_CLOSED',payload=?,in_flight_until=NULL WHERE dedupe_key=? AND status='NEEDS_REVIEW'",
        ).use { it.bindString(1, payload.toString()); it.bindString(2, dedupeKey); it.executeUpdateDelete() == 1 }
        if (changed) evaluation.record("owner_review_closed", dedupeKey,
            org.json.JSONObject().put("disposition", disposition).put("delivery_verified", false))
        return changed
    }

    /** Explicit owner disposition over a snapshot of keys; never touches pending or running work. */
    @Synchronized
    fun closeReviews(keys: List<String>, disposition: String): Int {
        require(keys.size <= 1000) { "Review at most 1000 holds at a time" }
        require(disposition in setOf("handled_elsewhere", "no_longer_needed"))
        var closed = 0
        for (key in keys.distinct()) {
            val row = readableDatabase.rawQuery("SELECT kind,payload FROM work_items WHERE dedupe_key=? AND status='NEEDS_REVIEW'", arrayOf(key)).use {
                if(it.moveToFirst()) it.getString(0) to org.json.JSONObject(it.getString(1)) else null
            } ?: continue
            if (closeReview(key, disposition)) {
                closed++
                val item=WorkItem(key, Domain.WHATSAPP, WorkKind.valueOf(row.first), row.second,
                    baseValueKes=0.0, urgencyHalfLifeHours=1.0, estimatedScreenSeconds=0)
                val sameScopeActive = readableDatabase.rawQuery(
                    "SELECT dedupe_key,kind,payload FROM work_items WHERE status IN ('NEEDS_REVIEW','PENDING','IN_FLIGHT')", null,
                ).use { cursor ->
                    var found=false
                    while(cursor.moveToNext()) {
                        val other=WorkItem(cursor.getString(0),Domain.WHATSAPP,WorkKind.valueOf(cursor.getString(1)),
                            org.json.JSONObject(cursor.getString(2)),baseValueKes=0.0,urgencyHalfLifeHours=1.0,estimatedScreenSeconds=0)
                        if(WorkBlockers.scope(other)==WorkBlockers.scope(item)) { found=true;break }
                    }
                    found
                }
                if(!sameScopeActive) WorkBlockers(context).resolveSuccess(item, System.currentTimeMillis(),
                    "Owner closed this hold: $disposition. No delivery or repair is claimed.")
            }
        }
        return closed
    }

    /** Archive one unclaimed task; retain its receipt and never cancel a running effect. */
    @Synchronized
    fun archivePending(dedupeKey: String): Boolean {
        val payload = readableDatabase.rawQuery(
            "SELECT payload FROM work_items WHERE dedupe_key=? AND status='PENDING'", arrayOf(dedupeKey),
        ).use { if (it.moveToFirst()) org.json.JSONObject(it.getString(0)) else null } ?: return false
        payload.put("owner_disposition", "cancelled_before_claim").put("owner_reviewed_at", System.currentTimeMillis())
        val changed = writableDatabase.compileStatement(
            "UPDATE work_items SET status='OWNER_CLOSED',payload=? WHERE dedupe_key=? AND status='PENDING'",
        ).use { it.bindString(1, payload.toString()); it.bindString(2, dedupeKey); it.executeUpdateDelete() == 1 }
        if (changed) evaluation.record("owner_archived_pending", dedupeKey, org.json.JSONObject().put("delivery_verified", false))
        return changed
    }

    /** Delay only unclaimed work. Never re-arm completed, uncertain or in-flight work. */
    @Synchronized
    fun deferPending(dedupeKey: String, until: Long): Boolean {
        val count = writableDatabase.compileStatement(
            "UPDATE work_items SET not_before=MAX(not_before,?) WHERE dedupe_key=? AND status='PENDING'").use {
            it.bindLong(1, until); it.bindString(2, dedupeKey); it.executeUpdateDelete()
        }
        return count == 1
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
                // Manager reports have an exact target, not a customer conversation.
                if (payload?.optBoolean("manager_report", false) == true) continue
                if (payload?.optBoolean("is_missed_call", false) == true) continue
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

    /** Scopes with live queue work; used to avoid clearing a blocker for active work. */
    @Synchronized
    fun activeScopes(): Set<String> = readableDatabase.rawQuery(
        "SELECT kind,payload FROM work_items WHERE status IN ('PENDING','IN_FLIGHT','NEEDS_REVIEW')", null,
    ).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) {
                val item = WorkItem(
                    dedupeKey = "maintenance",
                    domain = Domain.INTERNAL,
                    kind = WorkKind.valueOf(cursor.getString(0)),
                    payload = org.json.JSONObject(cursor.getString(1)),
                    baseValueKes = 0.0,
                    urgencyHalfLifeHours = 1.0,
                    estimatedScreenSeconds = 0,
                )
                add(WorkBlockers.scope(item))
            }
        }
    }

    private fun hasNewerInbound(item: WorkItem): Boolean {
        if (item.kind != WorkKind.WA_REPLY_INBOUND || !item.payload.optBoolean("inbound") || item.payload.optBoolean("is_missed_call")) return false
        val identity = item.payload.optString("conversation_identity")
        val messageAt = item.payload.optLong("inbound_message_at")
        if (identity.isBlank() || messageAt <= 0L) return false
        return readableDatabase.rawQuery("SELECT payload FROM work_items WHERE kind=?", arrayOf(item.kind.name)).use { cursor ->
            var newer = false
            while (cursor.moveToNext()) {
                val other = runCatching { org.json.JSONObject(cursor.getString(0)) }.getOrNull() ?: continue
                if (other.optBoolean("inbound") && !other.optBoolean("is_missed_call") &&
                    other.optString("conversation_identity") == identity && other.optLong("inbound_message_at") > messageAt) newer = true
            }
            newer
        }
    }

    private fun compactPendingConversation(db: SQLiteDatabase, item: WorkItem) {
        val conversation = item.payload.optString("conversation_identity").trim()
        if (conversation.isBlank() || item.payload.optBoolean("is_missed_call")) return
        val ids = mutableListOf<Long>()
        db.rawQuery(
            "SELECT id, payload, dedupe_key FROM work_items WHERE status = 'PENDING' AND kind = ? AND dedupe_key != ?",
            arrayOf(WorkKind.WA_REPLY_INBOUND.name, item.dedupeKey),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val other = runCatching { org.json.JSONObject(cursor.getString(1)) }.getOrNull() ?: continue
                if (other.optString("conversation_identity") == conversation && !other.optBoolean("is_missed_call")) {
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
            "DELETE FROM work_items WHERE status NOT IN ('NEEDS_REVIEW','OWNER_CLOSED') AND (deadline < ? OR created_at < ?) AND (status != 'IN_FLIGHT' OR in_flight_until < ?)",
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
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
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
    fun dashboard(now: Long = System.currentTimeMillis()): Map<String, Any> {
        val counts = linkedMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT status, COUNT(*) FROM work_items GROUP BY status", null).use { cursor ->
            while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1)
        }
        val timing = linkedMapOf<String, Long>()
        readableDatabase.rawQuery("SELECT dedupe_key,not_before FROM work_items WHERE status='PENDING'", null).use { c ->
            while (c.moveToNext()) timing[c.getString(0)] = c.getLong(1)
        }
        val pending = allPending()
        val due = pending.filter { (timing[it.dedupeKey] ?: 0L) <= now && (it.deadline == null || it.deadline >= now) && it.createdAt >= now - 48L * 3_600_000 }
        return mapOf(
            "dueCount" to due.size,
            "scheduledCount" to pending.count { (timing[it.dedupeKey] ?: 0L) > now },
            "nextDueAt" to (timing.values.filter { it > now }.minOrNull() ?: 0L),
            "counts" to counts,
            "needsReview" to readableDatabase.rawQuery("SELECT dedupe_key,kind,payload FROM work_items WHERE status='NEEDS_REVIEW'",null).use { c ->
                buildList { while(c.moveToNext()) {
                    val p=org.json.JSONObject(c.getString(2))
                    add(mapOf("key" to c.getString(0),"kind" to c.getString(1),"reason" to p.optString("review_reason"),"reviewAt" to p.optLong("review_at"),
                        "conversation" to co.sanaa.agent.core.Redactor.redact(p.optString("conversation").ifBlank { p.optString("sender").ifBlank { p.optString("target") } }).take(160),
                        "message" to co.sanaa.agent.core.Redactor.redact(p.optString("message")).take(500)))
                } }
            },
            "items" to pending.map { item -> mapOf(
                "key" to item.dedupeKey,
                "domain" to item.domain.name,
                "kind" to item.kind.name,
                "valueKes" to item.baseValueKes,
                "risk" to item.riskTier.name,
                "attempt" to item.attempt,
                "notBefore" to (timing[item.dedupeKey] ?: 0L),
                "due" to (item in due),
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
