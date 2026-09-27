package co.sanaa.agent.core.work

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import co.sanaa.agent.BuildConfig
import co.sanaa.agent.core.TerminalShopIdentity
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Durable, bounded, privacy-filtered work evidence. No messages or raw prompts enter this database. */
class AgentEventOutbox(private val context: Context,
    private val signedShop: () -> TerminalShopIdentity = { TerminalShopIdentity.readFresh(context) },
) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {
    private val binding = context.getSharedPreferences("amara_event_binding", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE, payload TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'PENDING', rejection TEXT)")
        db.execSQL("CREATE INDEX events_pending ON events(state, sequence)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun bindShop(scope: String, revision: Long) {
        require(revision > 0)
        check(binding.edit().putString("scope", scope).putLong("revision", revision).commit())
        writableDatabase.execSQL("UPDATE events SET state='PENDING' WHERE state='WAITING_BINDING'")
    }

    /** Only emit when the fresh signed shop matches the server-confirmed binding. */
    @Synchronized fun enqueueOutcome(result: WorkResult): Boolean {
        val shop = runCatching { signedShop() }.getOrNull() ?: return false
        val revision = binding.getLong("revision", 0L)
        if (revision < 1 || binding.getString("scope", null) != shop.scope) return false
        val db = writableDatabase
        val pending = db.rawQuery("SELECT COUNT(*) FROM events", null).use { c -> c.moveToFirst(); c.getInt(0) }
        if (pending >= MAX_PENDING) return false
        val installationId = binding.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
            check(binding.edit().putString("installation_id", it).commit())
        }
        // The private salt keeps predictable dedupe keys (including contact IDs) out of guessable UUIDs.
        val idSalt = binding.getString("id_salt", null) ?: UUID.randomUUID().toString().also {
            check(binding.edit().putString("id_salt", it).commit())
        }
        val item = result.item
        val job = UUID.nameUUIDFromBytes("$idSalt:work:${item.dedupeKey}".toByteArray()).toString()
        val attempt = UUID.nameUUIDFromBytes("$idSalt:attempt:${item.dedupeKey}:${item.attempt}".toByteArray()).toString()
        val status = when (result.status) {
            WorkStatus.DONE -> "completed"
            WorkStatus.FAILED -> "failed"
            WorkStatus.SKIPPED -> "skipped"
            WorkStatus.PARTIAL -> "partial"
            WorkStatus.ESCALATED -> "escalated"
        }
        val category = result.failure?.klass?.name?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9_]+")) }
        val reasonCode = safeReasonCode(result.failure?.summary)
        val facts = JSONObject().apply { if (category != null) put("failure_category", category) }
        val eventId = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            val event = JSONObject()
                .put("schema_version", 1).put("event_id", eventId).put("installation_id", installationId)
                .put("boot_id", BOOT_ID).put("sequence", nextSequence(db))
                .put("occurred_at", Instant.now().toString()).put("monotonic_ms", android.os.SystemClock.elapsedRealtime())
                .put("event_type", "work.outcome")
                .put("binding_revision", revision).put("job_id", job).put("attempt_id", attempt)
                .put("platform", platform(item.kind)).put("operation", item.kind.name.lowercase())
                .put("status", status).put("duration_ms", result.screenSecondsUsed.coerceAtLeast(0).coerceAtMost(86400) * 1000)
                .put("app_build", BuildConfig.VERSION_CODE).put("facts", facts)
            if (reasonCode != null) event.put("reason_code", reasonCode)
            db.insertOrThrow("events", null, ContentValues().apply {
                put("event_id", eventId); put("payload", event.toString()); put("state", "PENDING")
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return true
    }

    /** Mirror durable effect transitions without exporting target, caption or customer content. */
    @Synchronized fun enqueueEffect(state: String, capability: String, effectKey: String, attemptNumber: Int = 0, workKey: String = effectKey): Boolean {
        val type = when (state) {
            "CLAIMED" -> "effect.claimed"
            "VERIFICATION_PENDING" -> "effect.dispatched"
            "VERIFIED" -> "effect.verified"
            "UNCERTAIN" -> "effect.uncertain"
            else -> return false
        }
        if (!capability.matches(Regex("[a-z0-9_]{1,80}")) || effectKey.isBlank() || workKey.isBlank() || attemptNumber < 0) return false
        val shop = runCatching { signedShop() }.getOrNull() ?: return false
        val revision = binding.getLong("revision", 0L)
        if (revision < 1 || binding.getString("scope", null) != shop.scope) return false
        val db = writableDatabase
        val count = db.rawQuery("SELECT COUNT(*) FROM events", null).use { c -> c.moveToFirst(); c.getInt(0) }
        if (count >= MAX_PENDING) return false
        val installationId = binding.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
            check(binding.edit().putString("installation_id", it).commit())
        }
        val idSalt = binding.getString("id_salt", null) ?: UUID.randomUUID().toString().also {
            check(binding.edit().putString("id_salt", it).commit())
        }
        val job = UUID.nameUUIDFromBytes("$idSalt:work:$workKey".toByteArray()).toString()
        val attempt = UUID.nameUUIDFromBytes("$idSalt:attempt:$workKey:$attemptNumber".toByteArray()).toString()
        val effect = UUID.nameUUIDFromBytes("$idSalt:effect:$capability:$effectKey".toByteArray()).toString()
        val eventId = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            val event = JSONObject()
                .put("schema_version", 1).put("event_id", eventId).put("installation_id", installationId)
                .put("boot_id", BOOT_ID).put("sequence", nextSequence(db))
                .put("occurred_at", Instant.now().toString()).put("monotonic_ms", android.os.SystemClock.elapsedRealtime())
                .put("event_type", type).put("binding_revision", revision)
                .put("job_id", job).put("attempt_id", attempt).put("effect_id", effect)
                .put("platform", when {
                    "tiktok" in capability -> "tiktok"
                    "youtube" in capability -> "youtube"
                    "whatsapp" in capability -> "whatsapp"
                    else -> "local"
                })
                .put("operation", capability)
                .put("status", when (state) {
                    "CLAIMED" -> "started"
                    "VERIFICATION_PENDING" -> "dispatched"
                    "VERIFIED" -> "verified"
                    else -> "uncertain"
                })
                .put("app_build", BuildConfig.VERSION_CODE)
                .put("facts", JSONObject().apply {
                    if (state == "VERIFIED") put("verification_method", "device_verifier")
                })
            db.insertOrThrow("events", null, ContentValues().apply {
                put("event_id", eventId); put("payload", event.toString()); put("state", "PENDING")
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return true
    }

    @Synchronized fun pending(limit: Int = 25): List<Pair<String, JSONObject>> {
        val result = mutableListOf<Pair<String, JSONObject>>()
        readableDatabase.rawQuery("SELECT event_id,payload FROM events WHERE state='PENDING' ORDER BY sequence LIMIT ?", arrayOf(limit.coerceIn(1, 50).toString())).use { c ->
            while (c.moveToNext()) result += c.getString(0) to JSONObject(c.getString(1))
        }
        return result
    }

    @Synchronized fun acknowledge(ids: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("events", "event_id=? AND state='PENDING'", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun reject(id: String, code: String) {
        writableDatabase.update("events", ContentValues().apply { put("state", "REJECTED"); put("rejection", code.take(80)) },
            "event_id=? AND state='PENDING'", arrayOf(id))
    }

    @Synchronized fun waitForBinding(id: String) {
        writableDatabase.update("events", ContentValues().apply { put("state", "WAITING_BINDING") },
            "event_id=? AND state='PENDING'", arrayOf(id))
    }

    @Synchronized fun pendingCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE state='PENDING'", null)
        .use { c -> c.moveToFirst(); c.getInt(0) }

    private fun nextSequence(db: SQLiteDatabase): Long = db.rawQuery("SELECT IFNULL((SELECT seq FROM sqlite_sequence WHERE name='events'),0)+1", null)
        .use { c -> c.moveToFirst(); c.getLong(0) }

    private fun platform(kind: WorkKind): String = when {
        kind.name.startsWith("WA_") -> "whatsapp"
        kind.name.startsWith("TIKTOK_") -> "tiktok"
        kind.name.startsWith("YOUTUBE_") -> "youtube"
        kind.name.startsWith("SOKO_") -> "soko"
        else -> "local"
    }

    internal fun safeReasonCode(summary: String?): String? {
        val message = summary?.lowercase() ?: return null
        return when {
            "invalid or oversized tiktok image dimensions" in message -> "media_dimensions_invalid"
            "tiktok image too large" in message -> "media_download_too_large"
            "tiktok image decode failed" in message -> "media_decode_failed"
            "tiktok image download http" in message -> "media_download_http"
            "shop changed" in message || "different-shop" in message -> "shop_scope_changed"
            "no internet connection" in message -> "network_unavailable"
            else -> null
        }
    }

    companion object {
        const val DATABASE_NAME = "amara_event_outbox.db"
        private const val MAX_PENDING = 10_000
        private val BOOT_ID = UUID.randomUUID().toString()
    }
}
