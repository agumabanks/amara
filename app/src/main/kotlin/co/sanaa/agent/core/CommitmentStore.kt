package co.sanaa.agent.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Durable commitments linked to conversation messages: meetings, callbacks, requested
 * follow-ups, promised quotes/documents and delivery check-ins.
 *
 * A suggestion is distinguished from a confirmed agreement, a request from acceptance,
 * and booking intent from an actual reserved slot. Ambiguous dates/times/timezones are
 * clarified before anything externally visible is scheduled; a customer's timezone is
 * never inferred from the development server. Reminder claiming and dispatch are
 * restart-safe and idempotent per commitment revision and reminder instance: a reschedule
 * invalidates old queued reminders, and cancellation wins over stale work before dispatch.
 */
class CommitmentStore(private val appContext: Context) : SQLiteOpenHelper(appContext, "amara_commitments.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS commitments (
                id TEXT PRIMARY KEY,
                conversation_key TEXT NOT NULL,
                participants TEXT NOT NULL DEFAULT '[]',
                type TEXT NOT NULL,
                subject TEXT NOT NULL,
                agreed_at INTEGER NOT NULL DEFAULT 0,
                timezone TEXT NOT NULL DEFAULT '',
                original_phrase TEXT NOT NULL DEFAULT '',
                source_messages TEXT NOT NULL DEFAULT '[]',
                confirmation_status TEXT NOT NULL,
                location TEXT NOT NULL DEFAULT '',
                link TEXT NOT NULL DEFAULT '',
                next_action TEXT NOT NULL DEFAULT '',
                reminder_lead_minutes INTEGER NOT NULL DEFAULT 5,
                revision INTEGER NOT NULL DEFAULT 0,
                reminder_recipients TEXT NOT NULL DEFAULT 'owner',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_commitments_status ON commitments(confirmation_status, agreed_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_commitments_time ON commitments(agreed_at)")
        // Append-only revision history; original evidence is never rewritten.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS commitment_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                commitment_id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                status TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT '',
                at INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_commitment_events ON commitment_events(commitment_id, at)")
        // Reminder instances are idempotent per commitment revision; a reschedule
        // invalidates old revisions' reminders.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS reminder_instances (
                commitment_id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                instance INTEGER NOT NULL,
                due_at INTEGER NOT NULL,
                state TEXT NOT NULL,
                outcome TEXT NOT NULL DEFAULT '',
                at INTEGER NOT NULL,
                PRIMARY KEY(commitment_id, revision, instance)
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun upsert(commitment: Commitment, detail: String = ""): Boolean {
        val now = System.currentTimeMillis()
        val saved: Boolean = writableDatabase.beginTransactionNonExclusive().let {
            try {
                val existing = byId(commitment.id)
                val revision = (existing?.revision ?: 0) + if (existing != null &&
                    (existing.revisableChange(commitment) || existing.confirmationStatus != commitment.confirmationStatus)) 1 else 0
                val value = commitment.copy(revision = revision, createdAt = existing?.createdAt ?: now, updatedAt = now)
                writableDatabase.insertWithOnConflict("commitments", null, ContentValues().apply {
                    put("id", value.id); put("conversation_key", value.conversationKey)
                    put("participants", value.participants.toString()); put("type", value.type)
                    put("subject", value.subject); put("agreed_at", value.agreedAt)
                    put("timezone", value.timezone); put("original_phrase", value.originalPhrase.take(300))
                    put("source_messages", value.sourceMessages.toString())
                    put("confirmation_status", value.confirmationStatus)
                    put("location", value.location.take(200)); put("link", value.link.take(300))
                    put("next_action", value.nextAction.take(300))
                    put("reminder_lead_minutes", value.reminderLeadMinutes.toInt())
                    put("reminder_recipients", value.reminderRecipients)
                    put("revision", value.revision); put("created_at", value.createdAt); put("updated_at", value.updatedAt)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                if (existing == null || revision != existing.revision || existing.confirmationStatus != value.confirmationStatus) {
                    appendEvent(value.id, value.revision, value.confirmationStatus,
                        detail.ifBlank { existing?.let { "Revised to ${value.confirmationStatus}" } ?: "Created ${value.confirmationStatus}" })
                }
                // A reschedule invalidates old queued reminders.
                if (existing != null && existing.agreedAt != value.agreedAt) {
                    writableDatabase.execSQL(
                        "UPDATE reminder_instances SET state = 'INVALIDATED' WHERE commitment_id = ? AND revision < ? AND state IN ('QUEUED','CLAIMED')",
                        arrayOf<Any>(value.id, value.revision),
                    )
                }
                writableDatabase.setTransactionSuccessful()
                true
            } finally {
                writableDatabase.endTransaction()
            }
        }
        // WorkManager provides a durable wake request. Android may defer it; the
        // executor still enforces a narrow useful-lateness window before sending.
        runCatching { co.sanaa.agent.workers.AgentWorkScheduler.scheduleMeetingReminder(appContext, commitment) }
        return saved
    }

    private fun appendEvent(id: String, revision: Int, status: String, detail: String) {
        writableDatabase.insert("commitment_events", null, ContentValues().apply {
            put("commitment_id", id); put("revision", revision); put("status", status)
            put("detail", Redactor.redact(detail).take(600)); put("at", System.currentTimeMillis())
        })
    }

    fun byId(id: String): Commitment? = readableDatabase.query("commitments", null,
        "id=?", arrayOf(id), null, null, null).use { c ->
        if (c.moveToFirst()) row(c) else null
    }

    private fun row(c: android.database.Cursor): Commitment {
        fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name))
        fun l(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
        val participants = runCatching { JSONArray(s("participants")) }.getOrDefault(JSONArray())
        val sources = runCatching { JSONArray(s("source_messages")) }.getOrDefault(JSONArray())
        return Commitment(
            id = s("id"), conversationKey = s("conversation_key"), participants = participants,
            type = s("type"), subject = s("subject"), agreedAt = l("agreed_at"),
            timezone = s("timezone"), originalPhrase = s("original_phrase"),
            sourceMessages = sources, confirmationStatus = s("confirmation_status"),
            location = s("location"), link = s("link"), nextAction = s("next_action"),
            reminderLeadMinutes = l("reminder_lead_minutes"), revision = l("revision").toInt(),
            reminderRecipients = s("reminder_recipients"), createdAt = l("created_at"), updatedAt = l("updated_at"),
        )
    }

    /**
     * Confirmed commitments whose reminder is due inside the lead window and not yet
     * dispatched for this revision. Cancellation wins over stale work before dispatch.
     */
    fun dueReminders(now: Long = System.currentTimeMillis(), limit: Int = 5): List<Commitment> =
        readableDatabase.query("commitments", null,
            "confirmation_status=? AND agreed_at>?", arrayOf(Confirmation.AGREED, now.toString()), null, null, "agreed_at ASC").use { c ->
            buildList {
                while (c.moveToNext() && size < limit.coerceIn(1, 20)) {
                    val commitment = row(c)
                    val dueAt = commitment.agreedAt - commitment.reminderLeadMinutes * 60_000
                    val reminded = readableDatabase.query("reminder_instances", null,
                        "commitment_id=? AND revision=? AND instance=? AND state!='INVALIDATED'",
                        arrayOf(commitment.id, commitment.revision.toString(), commitment.reminderInstance().toString()),
                        null, null, null).use { it.count > 0 }
                    if (dueAt <= now && !reminded) add(commitment)
                }
            }
        }

    /** Reminders already missed: past-due without a bounded useful-lateness extension. */
    fun missedReminders(now: Long = System.currentTimeMillis(), latenessMs: Long = 30 * 60_000L): List<Commitment> =
        readableDatabase.query("commitments", null,
            "confirmation_status IN (?,?) AND agreed_at>0",
            arrayOf(Confirmation.AGREED, Confirmation.COMPLETED), null, null, "agreed_at ASC").use { c ->
            buildList {
                while (c.moveToNext() && size < 20) {
                    val commitment = row(c)
                    val dueAt = commitment.agreedAt - commitment.reminderLeadMinutes * 60_000
                    if (commitment.agreedAt < now && dueAt < now - latenessMs) {
                        val sent = readableDatabase.query("reminder_instances", null,
                            "commitment_id=? AND state='SENT'", arrayOf(commitment.id), null, null, null).use { it.count }
                        if (sent == 0 && commitment.confirmationStatus == Confirmation.AGREED) add(commitment)
                    }
                }
            }
        }

    /**
     * Idempotent reminder claim: exactly one winner per commitment revision and reminder
     * instance. A reschedule or cancellation invalidates the claim before dispatch.
     */
    fun claimReminder(id: String, revision: Int, instance: Int, dueAt: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = byId(id)
            if (current == null || current.revision != revision ||
                current.confirmationStatus != Confirmation.AGREED ||
                current.agreedAt - current.reminderLeadMinutes * 60_000 != dueAt ||
                current.agreedAt <= System.currentTimeMillis()) return false
            val inserted: Long = db.insertWithOnConflict("reminder_instances", null, ContentValues().apply {
                put("commitment_id", id); put("revision", revision); put("instance", instance)
                put("due_at", dueAt); put("state", "CLAIMED"); put("outcome", "")
                put("at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
            return inserted != -1L
        } finally {
            db.endTransaction()
        }
    }

    fun settleReminder(id: String, revision: Int, instance: Int, state: String, outcome: String) {
        writableDatabase.execSQL(
            "UPDATE reminder_instances SET state=?, outcome=?, at=? WHERE commitment_id=? AND revision=? AND instance=?",
            arrayOf<Any>(state.take(40), Redactor.redact(outcome).take(300), System.currentTimeMillis(),
                id, revision.toLong(), instance.toLong()),
        )
    }

    /** Cancellation must win over stale queued reminders before dispatch. */
    fun cancel(id: String, reason: String = "Cancelled"): Boolean {
        val commitment = byId(id) ?: return false
        if (!upsert(commitment.copy(confirmationStatus = Confirmation.CANCELLED), reason)) return false
        writableDatabase.execSQL(
            "UPDATE reminder_instances SET state='INVALIDATED' WHERE commitment_id=? AND state IN ('QUEUED','CLAIMED')",
            arrayOf(id),
        )
        return true
    }

    fun complete(id: String, detail: String = "Completed"): Boolean {
        val commitment = byId(id) ?: return false
        return upsert(commitment.copy(confirmationStatus = Confirmation.COMPLETED), detail)
    }

    fun needsClarification(limit: Int = 20): List<Commitment> = readableDatabase.query(
        "commitments", null, "confirmation_status=?", arrayOf(Confirmation.CLARIFICATION_NEEDED),
        null, null, "updated_at DESC").use { c ->
        buildList { while (c.moveToNext() && size < limit.coerceIn(1, 50)) add(row(c)) }
    }

    fun upcoming(now: Long = System.currentTimeMillis(), limit: Int = 20): List<Commitment> = readableDatabase.query(
        "commitments", null, "confirmation_status=? AND agreed_at>=?",
        arrayOf(Confirmation.AGREED, now.toString()), null, null, "agreed_at ASC").use { c ->
        buildList { while (c.moveToNext() && size < limit.coerceIn(1, 50)) add(row(c)) }
    }

    fun commitments(limit: Int = 100): List<Commitment> = readableDatabase.query(
        "commitments", null, null, null, null, null, "updated_at DESC").use { c ->
        buildList { while (c.moveToNext() && size < limit.coerceIn(1, 200)) add(row(c)) }
    }

    fun history(id: String): List<Map<String, Any>> = readableDatabase.query(
        "commitment_events", null, "commitment_id=?", arrayOf(id), null, null, "at ASC").use { c ->
        buildList {
            while (c.moveToNext()) add(mapOf(
                "revision" to c.getInt(c.getColumnIndexOrThrow("revision")),
                "status" to c.getString(c.getColumnIndexOrThrow("status")),
                "detail" to c.getString(c.getColumnIndexOrThrow("detail")),
                "at" to c.getLong(c.getColumnIndexOrThrow("at"))))
        }
    }

    fun remindersFor(id: String): List<Map<String, Any>> = readableDatabase.query(
        "reminder_instances", null, "commitment_id=?", arrayOf(id), null, null, "due_at ASC").use { c ->
        buildList {
            while (c.moveToNext()) add(mapOf(
                "revision" to c.getInt(c.getColumnIndexOrThrow("revision")),
                "instance" to c.getInt(c.getColumnIndexOrThrow("instance")),
                "due_at" to c.getLong(c.getColumnIndexOrThrow("due_at")),
                "state" to c.getString(c.getColumnIndexOrThrow("state")),
                "outcome" to c.getString(c.getColumnIndexOrThrow("outcome")),
                "at" to c.getLong(c.getColumnIndexOrThrow("at"))))
        }
    }

    /**
     * Resolve an ambiguous time phrase against the relevant message time and confirmed
     * timezone. "tomorrow" resolves against the message time, never the server clock's
     * locale. Returns null when the phrase stays ambiguous — the commitment then needs
     * clarification before anything externally visible is scheduled.
     */
    fun resolveTimePhrase(phrase: String, messageTime: Long, zone: ZoneId?): Long? {
        if (zone == null) return null
        val value = phrase.trim().lowercase()
        if (value.isBlank()) return null
        val local = Instant.ofEpochMilli(messageTime).atZone(zone)
        val atTime = Regex("([0-9]{1,2})(?::([0-9]{2}))?\\s*(am|pm)?").find(value)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull()?.coerceIn(0, 23) ?: return@let null
            val minute = match.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0
            var adjusted = hour
            if (match.groupValues[3] == "pm" && hour < 12) adjusted = hour + 12
            if (match.groupValues[3] == "am" && hour == 12) adjusted = 0
            local.toLocalDate().atTime(adjusted, minute)
        } ?: return null
        val day = when {
            "tomorrow" in value -> atTime.plusDays(1)
            "today" in value || "tonight" in value -> atTime
            "next week" in value -> atTime.plusDays(7)
            else -> atTime
        }
        // An unresolvable bare weekday or a missing clock time stays ambiguous.
        if (!("tomorrow" in value || "today" in value || "tonight" in value || "next week" in value)) return null
        return day.atZone(zone).toInstant().toEpochMilli()
    }

    private fun Commitment.reminderInstance(): Int = 1

    object Confirmation {
        const val SUGGESTED = "suggested"
        const val REQUESTED = "requested"
        const val AGREED = "agreed"
        const val CLARIFICATION_NEEDED = "clarification_needed"
        const val CANCELLED = "cancelled"
        const val COMPLETED = "completed"
    }
}

data class Commitment(
    val id: String,
    val conversationKey: String,
    val participants: JSONArray,
    val type: String,
    val subject: String,
    val agreedAt: Long,
    val timezone: String,
    val originalPhrase: String,
    val sourceMessages: JSONArray,
    val confirmationStatus: String,
    val location: String,
    val link: String,
    val nextAction: String,
    val reminderLeadMinutes: Long,
    val revision: Int,
    val reminderRecipients: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Any change affecting who receives a reminder or what it says needs a new key. */
    fun revisableChange(other: Commitment): Boolean =
        agreedAt != other.agreedAt || subject != other.subject || nextAction != other.nextAction ||
            location != other.location || link != other.link || timezone != other.timezone ||
            participants.toString() != other.participants.toString() ||
            reminderLeadMinutes != other.reminderLeadMinutes ||
            reminderRecipients != other.reminderRecipients || conversationKey != other.conversationKey
}
