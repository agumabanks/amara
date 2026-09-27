package co.sanaa.agent.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import co.sanaa.agent.core.work.WorkResult
import co.sanaa.agent.core.work.WorkStatus
import org.json.JSONObject

/**
 * Task attempts and external-effect receipts remain separate measures.
 *
 * Receipts project the canonical side-effect ledger ([co.sanaa.agent.core.SideEffectTransaction],
 * the single authority for transaction state) into owner-visible per-attempt records.
 * They add attempt detail — stage, dispatch certainty, evidence references, platform
 * identity when observable — and never hold a competing transaction state.
 */
class ModuleActivityStore(context: Context) : SQLiteOpenHelper(context, "module_activity.db", null, 2), java.io.Closeable {
    private val appVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE activity (id TEXT PRIMARY KEY,module TEXT,kind TEXT,status TEXT,at INTEGER,detail TEXT)")
        db.execSQL("CREATE INDEX activity_time ON activity(at)")
        createReceipts(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) createReceipts(db)
    }
    private fun createReceipts(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS receipts (" +
            "idempotency_key TEXT PRIMARY KEY," +
            "attempt INTEGER NOT NULL DEFAULT 0," +
            "module TEXT NOT NULL," +
            "capability TEXT NOT NULL," +
            "platform TEXT NOT NULL DEFAULT ''," +
            "target TEXT NOT NULL DEFAULT ''," +
            "shop_scope TEXT NOT NULL DEFAULT ''," +
            "content_hash TEXT NOT NULL DEFAULT ''," +
            "media_digest TEXT NOT NULL DEFAULT ''," +
            "intended_at INTEGER NOT NULL DEFAULT 0," +
            "prepared_at INTEGER NOT NULL DEFAULT 0," +
            "dispatched_at INTEGER NOT NULL DEFAULT 0," +
            "settled_at INTEGER NOT NULL DEFAULT 0," +
            "stage TEXT NOT NULL DEFAULT ''," +
            "failure_category TEXT NOT NULL DEFAULT ''," +
            "dispatch_certainty TEXT NOT NULL DEFAULT ''," +
            "platform_ref TEXT NOT NULL DEFAULT ''," +
            "app_version TEXT NOT NULL DEFAULT ''," +
            "outcome TEXT NOT NULL," +
            "evidence TEXT NOT NULL DEFAULT ''," +
            "created_at INTEGER NOT NULL," +
            "updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS receipts_module_updated ON receipts(module, updated_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS receipts_outcome ON receipts(outcome, updated_at)")
        // Append-only history of state/evidence changes; original evidence is never rewritten.
        db.execSQL("CREATE TABLE IF NOT EXISTS receipt_events (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "receipt_key TEXT NOT NULL," +
            "outcome TEXT NOT NULL," +
            "dispatch_certainty TEXT NOT NULL DEFAULT ''," +
            "stage TEXT NOT NULL DEFAULT ''," +
            "evidence TEXT NOT NULL DEFAULT ''," +
            "at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS receipt_events_key ON receipt_events(receipt_key, at)")
    }

    fun record(result: WorkResult) {
        val kind = result.item.kind.name
        val detail = Redactor.redact(result.failure?.summary ?: result.outcomeFacts.joinToString("; ")).take(600)
        record("${result.item.dedupeKey}:${result.item.attempt}",kind,result.status.name,detail)
        runCatching { recordReceipt(result) }
    }
    fun record(id: String, kind: String, status: String, detail: String) {
        writableDatabase.insertWithOnConflict("activity", null, ContentValues().apply {
            put("id", id)
            put("module", moduleFor(kind)); put("kind", kind); put("status", status)
            put("at", System.currentTimeMillis()); put("detail", Redactor.redact(detail).take(600))
        }, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.delete("activity", "at < ?", arrayOf((System.currentTimeMillis() - 30L * 86_400_000).toString()))
    }

    /**
     * Owner-visible receipt for every attempted effect, including preparation
     * failures before dispatch. Keyed by the canonical idempotency key so attempts,
     * the side-effect transaction and the source post/conversation stay linked.
     * Merging is monotonic: a later update never downgrades a more final outcome,
     * so a verified side effect is never overwritten by a generic attempt record.
     */
    fun recordReceipt(result: WorkResult) {
        val key = result.item.dedupeKey
        if (key.isBlank()) return
        val payload = result.item.payload
        val target = Redactor.redact(payload.optString("target")
            .ifBlank { payload.optString("chat_key") }
            .ifBlank { payload.optString("conversation_identity")
                .ifBlank { payload.optString("youtube_channel") }
                .ifBlank { payload.optString("group_target") } }).take(300)
        upsert(Receipt(
            idempotencyKey = key,
            attempt = result.item.attempt,
            module = moduleFor(result.item.kind.name),
            capability = result.item.kind.name,
            platform = platformFor(result.item.domain.name),
            target = target,
            shopScope = payload.optString("shop_scope").take(120),
            contentHash = "",
            mediaDigest = payload.optString("media_digest").take(140),
            intendedAt = payload.optLong("not_before"),
            stage = "",
            failureCategory = result.failure?.klass?.name ?: "",
            dispatchCertainty = "",
            platformRef = "",
            appVersion = appVersion,
            outcome = receiptOutcomeFor(result.status, result.failure?.summary),
            evidence = Redactor.redact(result.failure?.summary ?: result.outcomeFacts.joinToString("; ")).take(600),
        ))
    }

    /**
     * Side-effect ledger projection: records dispatch certainty, verification and
     * content binding as the transaction progresses. Called from the runner hook at
     * CLAIMED/ACTING/FAILED/UNCERTAIN/VERIFIED/CANCELLED states.
     */
    fun recordEffect(capability: String, idempotencyKey: String, state: String,
                     target: String = "", contentHash: String = "") {
        if (idempotencyKey.isBlank()) return
        val certainty = certaintyFor(state)
        val outcome = when (state) {
            SideEffectState.CLAIMED.name -> Receipt.OUTCOME_PREPARED
            SideEffectState.ACTING.name -> Receipt.OUTCOME_PREPARED
            SideEffectState.VERIFICATION_PENDING.name -> Receipt.OUTCOME_DISPATCHED_UNVERIFIED
            SideEffectState.VERIFIED.name -> Receipt.OUTCOME_VERIFIED
            SideEffectState.FAILED.name -> Receipt.OUTCOME_FAILED_BEFORE_DISPATCH
            SideEffectState.UNCERTAIN.name -> Receipt.OUTCOME_UNCERTAIN
            SideEffectState.CANCELLED.name -> Receipt.OUTCOME_CANCELLED
            SideEffectState.EXPIRED.name -> Receipt.OUTCOME_EXPIRED
            else -> ""
        }
        if (outcome.isBlank()) return
        upsert(Receipt(
            idempotencyKey = idempotencyKey,
            attempt = -1,
            module = moduleFor(capability.uppercase()),
            capability = capability,
            platform = platformFor(capability),
            target = Redactor.redact(target).take(300),
            shopScope = "",
            contentHash = contentHash,
            mediaDigest = "",
            intendedAt = 0,
            stage = "",
            failureCategory = "",
            dispatchCertainty = certainty,
            platformRef = "",
            appVersion = appVersion,
            outcome = outcome,
            evidence = "",
        ))
    }

    /**
     * Crash-recovery projection of [co.sanaa.agent.core.AmaraMemory.markOrphanedTransactionsUncertain]:
     * interrupted persistence and restart cannot leave a receipt claiming a verified
     * dispatch when the canonical ledger settled the transaction UNCERTAIN.
     */
    fun recordOrphansUncertain(transactions: List<SideEffectTransaction>) {
        // Only canonical transactions recovered as uncertain may change receipts.
        // A prepared work attempt with no transaction has not necessarily dispatched.
        transactions.filter { it.state == SideEffectState.UNCERTAIN }.forEach { transaction ->
            val current = readableDatabase.query("receipts", arrayOf("outcome"), "idempotency_key=?",
                arrayOf(transaction.idempotencyKey), null, null, null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            if (current == Receipt.OUTCOME_PREPARED || current == Receipt.OUTCOME_DISPATCHED_UNVERIFIED) {
                recordEffect(transaction.capability, transaction.idempotencyKey,
                    SideEffectState.UNCERTAIN.name, transaction.target, transaction.contentHash)
            }
        }
    }

    private fun upsert(receipt: Receipt) {
        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            var existingCreatedAt: Long? = null
            val existing = readableDatabase.query("receipts", null, "idempotency_key=?",
                arrayOf(receipt.idempotencyKey), null, null, null).use { c ->
                if (c.moveToFirst()) {
                    existingCreatedAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    mapOf(
                        "attempt" to c.getInt(c.getColumnIndexOrThrow("attempt")),
                        "prepared_at" to c.getLong(c.getColumnIndexOrThrow("prepared_at")),
                        "dispatched_at" to c.getLong(c.getColumnIndexOrThrow("dispatched_at")),
                        "settled_at" to c.getLong(c.getColumnIndexOrThrow("settled_at")),
                        "shop_scope" to c.getString(c.getColumnIndexOrThrow("shop_scope")),
                        "content_hash" to c.getString(c.getColumnIndexOrThrow("content_hash")),
                        "media_digest" to c.getString(c.getColumnIndexOrThrow("media_digest")),
                        "intended_at" to c.getLong(c.getColumnIndexOrThrow("intended_at")),
                        "stage" to c.getString(c.getColumnIndexOrThrow("stage")),
                        "failure_category" to c.getString(c.getColumnIndexOrThrow("failure_category")),
                        "dispatch_certainty" to c.getString(c.getColumnIndexOrThrow("dispatch_certainty")),
                        "platform_ref" to c.getString(c.getColumnIndexOrThrow("platform_ref")),
                        "target" to c.getString(c.getColumnIndexOrThrow("target")),
                        "evidence" to c.getString(c.getColumnIndexOrThrow("evidence")),
                        "outcome" to c.getString(c.getColumnIndexOrThrow("outcome")),
                    )
                } else null
            }
            val merged = merge(existing, receipt, now)
            // The shown outcome's evidence is kept: a downgrade observation never
            // rewrites the original evidence of a more final state.
            val previousOutcome = existing?.get("outcome") as? String
            val existingEvidence = existing?.get("evidence") as? String ?: ""
            val evidence = when {
                receipt.evidence.isBlank() -> existingEvidence
                existingEvidence.isBlank() -> receipt.evidence
                precedence(receipt.outcome) >= precedence(previousOutcome ?: "") -> receipt.evidence
                else -> existingEvidence
            }
            writableDatabase.insertWithOnConflict("receipts", null, ContentValues().apply {
                put("idempotency_key", receipt.idempotencyKey)
                put("attempt", merged.intValue("attempt")); put("module", receipt.module)
                put("capability", receipt.capability); put("platform", receipt.platform)
                put("target", merged.stringValue("target"))
                put("shop_scope", merged.stringValue("shop_scope")); put("content_hash", merged.stringValue("content_hash"))
                put("media_digest", merged.stringValue("media_digest")); put("intended_at", merged.longValue("intended_at"))
                put("prepared_at", merged.longValue("prepared_at")); put("dispatched_at", merged.longValue("dispatched_at"))
                put("settled_at", merged.longValue("settled_at")); put("stage", merged.stringValue("stage"))
                put("failure_category", merged.stringValue("failure_category"))
                put("dispatch_certainty", merged.stringValue("dispatch_certainty"))
                put("platform_ref", merged.stringValue("platform_ref")); put("app_version", receipt.appVersion)
                put("outcome", merged.stringValue("outcome")); put("evidence", evidence)
                put("created_at", existingCreatedAt ?: now); put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            // Append-only history: record every observed state change (including a
            // downgrade such as a held retry) without rewriting the receipt row.
            val lastEvent = readableDatabase.query("receipt_events", null, "receipt_key=?",
                arrayOf(receipt.idempotencyKey), null, null, "at DESC", "1").use { c ->
                if (c.moveToFirst()) mapOf(
                    "outcome" to c.getString(c.getColumnIndexOrThrow("outcome")),
                    "certainty" to c.getString(c.getColumnIndexOrThrow("dispatch_certainty")),
                ) else null
            }
            if (lastEvent?.get("outcome") != merged["outcome"] ||
                lastEvent?.get("certainty") != merged["dispatch_certainty"]) {
                writableDatabase.insert("receipt_events", null, ContentValues().apply {
                    put("receipt_key", receipt.idempotencyKey)
                    put("outcome", merged.stringValue("outcome")); put("dispatch_certainty", merged.stringValue("dispatch_certainty"))
                    put("stage", merged.stringValue("stage")); put("evidence", evidence.take(600))
                    put("at", now)
                })
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** Monotonic merge: never downgrade a more final outcome or lose identity fields. */
    private fun merge(existing: Map<String, Any?>?, receipt: Receipt, now: Long): Map<String, Any?> {
        if (existing == null) return mapOf(
            "attempt" to receipt.attempt.coerceAtLeast(0),
            "prepared_at" to if (receipt.outcome == Receipt.OUTCOME_PREPARED) now else 0L,
            "dispatched_at" to if (receipt.outcome == Receipt.OUTCOME_DISPATCHED_UNVERIFIED) now else 0L,
            "settled_at" to if (receipt.outcome in Receipt.SETTLED_OUTCOMES) now else 0L,
            "shop_scope" to receipt.shopScope,
            "content_hash" to receipt.contentHash,
            "media_digest" to receipt.mediaDigest,
            "intended_at" to receipt.intendedAt,
            "stage" to receipt.stage,
            "failure_category" to receipt.failureCategory,
            "dispatch_certainty" to receipt.dispatchCertainty,
            "platform_ref" to receipt.platformRef,
            "target" to receipt.target,
            "outcome" to receipt.outcome,
        )
        fun keep(field: String, value: String): String =
            if (value.isNotBlank()) value else existing[field] as? String ?: ""
        fun keepTime(field: String, set: Boolean): Long =
            if (set) now else existing[field] as? Long ?: 0L
        val previousOutcome = existing["outcome"] as? String ?: ""
        val outcome = if (precedence(receipt.outcome) >= precedence(previousOutcome)) receipt.outcome else previousOutcome
        return mapOf(
            "attempt" to maxOf(receipt.attempt, existing["attempt"] as? Int ?: 0).coerceAtLeast(0),
            "prepared_at" to keepTime("prepared_at", receipt.outcome == Receipt.OUTCOME_PREPARED),
            "dispatched_at" to keepTime("dispatched_at", receipt.outcome == Receipt.OUTCOME_DISPATCHED_UNVERIFIED),
            "settled_at" to keepTime("settled_at", receipt.outcome in Receipt.SETTLED_OUTCOMES),
            "shop_scope" to keep("shop_scope", receipt.shopScope),
            "content_hash" to keep("content_hash", receipt.contentHash),
            "media_digest" to keep("media_digest", receipt.mediaDigest),
            "intended_at" to (receipt.intendedAt.takeIf { it > 0 } ?: existing["intended_at"] as? Long ?: 0L),
            "stage" to keep("stage", receipt.stage),
            "failure_category" to keep("failure_category", receipt.failureCategory),
            "dispatch_certainty" to keep("dispatch_certainty", receipt.dispatchCertainty),
            "platform_ref" to keep("platform_ref", receipt.platformRef),
            "target" to keep("target", receipt.target),
            "outcome" to outcome,
        )
    }

    private fun Map<String, Any?>.stringValue(field: String): String =
        when (val value = this[field]) {
            is String -> value
            else -> value?.toString().orEmpty()
        }

    private fun Map<String, Any?>.intValue(field: String): Int =
        when (val value = this[field]) {
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toInt()
            else -> 0
        }

    private fun Map<String, Any?>.longValue(field: String): Long =
        when (val value = this[field]) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            else -> 0L
        }

    /** Indexed, paginated receipt reads for the owner-facing screen. */
    fun receipts(module: String? = null, outcome: String? = null, query: String? = null,
                 limit: Int = 50, offset: Int = 0): List<Map<String, Any>> {
        val selection = buildList {
            module?.takeIf { it.isNotBlank() }?.let { add("module=?") }
            outcome?.takeIf { it.isNotBlank() }?.let { add("outcome=?") }
            query?.takeIf { it.isNotBlank() }?.let { add("(idempotency_key LIKE ? OR capability LIKE ? OR target LIKE ? OR stage LIKE ?)") }
        }
        val args = buildList {
            module?.takeIf { it.isNotBlank() }?.let { add(it) }
            outcome?.takeIf { it.isNotBlank() }?.let { add(it) }
            query?.takeIf { it.isNotBlank() }?.let { add("%$it%"); add("%$it%"); add("%$it%"); add("%$it%") }
        }
        return readableDatabase.query("receipts", null,
            selection.joinToString(" AND ").ifBlank { null },
            args.takeIf { it.isNotEmpty() }?.toTypedArray(), null, null,
            "updated_at DESC",
            "${offset.coerceAtLeast(0)}, ${limit.coerceIn(1, 200)}").use { c ->
            buildList {
                while (c.moveToNext()) add(receiptRow(c))
            }
        }
    }

    private fun receiptRow(c: android.database.Cursor): Map<String, Any> = mapOf(
        "idempotency_key" to c.getString(c.getColumnIndexOrThrow("idempotency_key")),
        "attempt" to c.getInt(c.getColumnIndexOrThrow("attempt")),
        "module" to c.getString(c.getColumnIndexOrThrow("module")),
        "capability" to c.getString(c.getColumnIndexOrThrow("capability")),
        "platform" to c.getString(c.getColumnIndexOrThrow("platform")),
        "target" to c.getString(c.getColumnIndexOrThrow("target")),
        "shop_scope" to c.getString(c.getColumnIndexOrThrow("shop_scope")),
        "content_hash" to c.getString(c.getColumnIndexOrThrow("content_hash")),
        "media_digest" to c.getString(c.getColumnIndexOrThrow("media_digest")),
        "intended_at" to c.getLong(c.getColumnIndexOrThrow("intended_at")),
        "prepared_at" to c.getLong(c.getColumnIndexOrThrow("prepared_at")),
        "dispatched_at" to c.getLong(c.getColumnIndexOrThrow("dispatched_at")),
        "settled_at" to c.getLong(c.getColumnIndexOrThrow("settled_at")),
        "stage" to c.getString(c.getColumnIndexOrThrow("stage")),
        "failure_category" to c.getString(c.getColumnIndexOrThrow("failure_category")),
        "dispatch_certainty" to c.getString(c.getColumnIndexOrThrow("dispatch_certainty")),
        "platform_ref" to c.getString(c.getColumnIndexOrThrow("platform_ref")),
        "app_version" to c.getString(c.getColumnIndexOrThrow("app_version")),
        "outcome" to c.getString(c.getColumnIndexOrThrow("outcome")),
        "evidence" to c.getString(c.getColumnIndexOrThrow("evidence")),
        "created_at" to c.getLong(c.getColumnIndexOrThrow("created_at")),
        "updated_at" to c.getLong(c.getColumnIndexOrThrow("updated_at")),
    )

    /** All attempts of one logical action, oldest first. */
    fun receiptAttempts(idempotencyKey: String): List<Map<String, Any>> {
        // Attempts share the canonical idempotency key; the current row holds the
        // latest attempt. Legacy scrubbed identities resolve through the same key.
        return receipts(query = idempotencyKey.take(24))
    }

    /** Append-only correction/reconciliation history; original evidence is not rewritten. */
    fun receiptHistory(idempotencyKey: String): List<Map<String, Any>> =
        readableDatabase.query("receipt_events", null, "receipt_key=?",
            arrayOf(idempotencyKey), null, null, "at ASC").use { c ->
            buildList {
                while (c.moveToNext()) add(mapOf(
                    "outcome" to c.getString(c.getColumnIndexOrThrow("outcome")),
                    "dispatch_certainty" to c.getString(c.getColumnIndexOrThrow("dispatch_certainty")),
                    "stage" to c.getString(c.getColumnIndexOrThrow("stage")),
                    "evidence" to c.getString(c.getColumnIndexOrThrow("evidence")),
                    "at" to c.getLong(c.getColumnIndexOrThrow("at"))))
            }
        }

    fun receiptCount(idempotencyKey: String): Int =
        readableDatabase.query("receipts", null, "idempotency_key=?",
            arrayOf(idempotencyKey), null, null, null).use { it.count }

    fun receiptCounts(now: Long = System.currentTimeMillis()): Map<String, Int> {
        val cutoff = now - 86_400_000L
        val counts = mutableMapOf<String, Int>()
        readableDatabase.query("receipts", arrayOf("outcome", "COUNT(*) AS n"), "updated_at>=?",
            arrayOf(cutoff.toString()), "outcome", null, null).use { c ->
            while (c.moveToNext()) counts[c.getString(0)] = c.getInt(1)
        }
        return counts
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

    /** Redacted local export; never customer conversation content or raw secrets. */
    fun exportReceipts(limit: Int = 500): String {
        val rows = receipts(limit = limit.coerceIn(1, 1000))
        return JSONObject().put("receipts", org.json.JSONArray(rows.map { row ->
            JSONObject().put("capability", row["capability"]).put("module", row["module"])
                .put("platform", row["platform"]).put("outcome", row["outcome"])
                .put("dispatch_certainty", row["dispatch_certainty"])
                .put("platform_ref", row["platform_ref"]).put("stage", row["stage"])
                .put("failure_category", row["failure_category"])
                .put("updated_at", row["updated_at"]).put("app_version", row["app_version"])
        })).toString()
    }

    data class Receipt(
        val idempotencyKey: String,
        val attempt: Int,
        val module: String,
        val capability: String,
        val platform: String,
        val target: String,
        val shopScope: String,
        val contentHash: String,
        val mediaDigest: String,
        val intendedAt: Long,
        val stage: String,
        val failureCategory: String,
        val dispatchCertainty: String,
        val platformRef: String,
        val appVersion: String,
        val outcome: String,
        val evidence: String,
    ) {        companion object {
            const val OUTCOME_PREPARED = "prepared"
            const val OUTCOME_HELD = "held"
            const val OUTCOME_COMPLETED = "completed"
            const val OUTCOME_FAILED_BEFORE_DISPATCH = "failed_before_dispatch"
            const val OUTCOME_DISPATCHED_UNVERIFIED = "dispatched_unverified"
            const val OUTCOME_UNCERTAIN = "uncertain"
            const val OUTCOME_VERIFIED = "verified"
            const val OUTCOME_CANCELLED = "cancelled"
            const val OUTCOME_EXPIRED = "expired"
            /** Outcomes that settle the action's final position in time. */
            val SETTLED_OUTCOMES = setOf(OUTCOME_FAILED_BEFORE_DISPATCH, OUTCOME_UNCERTAIN, OUTCOME_VERIFIED)
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
        fun platformFor(kind: String): String = when {
            "TIKTOK" in kind || kind.contains("musically") -> "tiktok"
            "YOUTUBE" in kind || "SHORTS" in kind -> "youtube"
            "WHATSAPP" in kind || kind.startsWith("WA_") || kind.contains("whatsapp") -> "whatsapp"
            "SOKO" in kind -> "soko"
            else -> ""
        }
        private fun precedence(outcome: String): Int = when (outcome) {
            Receipt.OUTCOME_PREPARED -> 1
            Receipt.OUTCOME_HELD -> 2
            Receipt.OUTCOME_COMPLETED -> 3
            Receipt.OUTCOME_CANCELLED -> 4
            Receipt.OUTCOME_EXPIRED -> 5
            Receipt.OUTCOME_DISPATCHED_UNVERIFIED -> 6
            Receipt.OUTCOME_FAILED_BEFORE_DISPATCH -> 7
            Receipt.OUTCOME_UNCERTAIN -> 8
            Receipt.OUTCOME_VERIFIED -> 9
            else -> 0
        }
        /** WorkStatus + proven-no-dispatch summary → receipt outcome. */
        fun receiptOutcomeFor(status: WorkStatus, failureSummary: String?): String = when {
            status == WorkStatus.DONE && failureSummary == null -> Receipt.OUTCOME_COMPLETED
            status == WorkStatus.DONE -> Receipt.OUTCOME_COMPLETED
            status == WorkStatus.SKIPPED -> Receipt.OUTCOME_HELD
            status == WorkStatus.PARTIAL -> Receipt.OUTCOME_HELD
            failureSummary != null && (failureSummary.contains("never dispatched", true) ||
                failureSummary.contains(SideEffectRunner.NON_EFFECT_BLOCKER, true)) -> Receipt.OUTCOME_FAILED_BEFORE_DISPATCH
            status == WorkStatus.ESCALATED -> Receipt.OUTCOME_HELD
            status == WorkStatus.FAILED -> Receipt.OUTCOME_FAILED_BEFORE_DISPATCH
            else -> Receipt.OUTCOME_HELD
        }
        /** SideEffectState → dispatch certainty. */
        fun certaintyFor(state: String): String = when (state) {
            SideEffectState.CLAIMED.name -> "prepared"
            // ACTING is entered immediately before act(); it does not prove the final
            // external trigger was accepted. A later false return may prove no effect.
            SideEffectState.ACTING.name -> "dispatch_attempting"
            SideEffectState.VERIFICATION_PENDING.name -> "dispatched_unverified"
            SideEffectState.VERIFIED.name -> "verified"
            SideEffectState.FAILED.name -> "proven_no_effect"
            SideEffectState.UNCERTAIN.name -> "unknown"
            SideEffectState.CANCELLED.name -> "not_dispatched"
            SideEffectState.EXPIRED.name -> "not_dispatched"
            else -> ""
        }
    }
}
