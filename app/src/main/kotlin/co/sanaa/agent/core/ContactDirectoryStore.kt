package co.sanaa.agent.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * Dedicated durable store for the canonical contact directory (contact_directory.db).
 * Deliberately separate from AmaraMemory so contact identity can evolve independently;
 * legacy SecureConfig JSON is imported lazily once and never rewritten.
 */
class ContactDirectoryStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS contacts (
                id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                normalized_phone TEXT,
                aliases_json TEXT NOT NULL DEFAULT '[]',
                is_group INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL,
                last_verified_at INTEGER,
                classification TEXT NOT NULL DEFAULT 'UNKNOWN',
                commercial_consent TEXT NOT NULL DEFAULT 'UNKNOWN',
                permissions_json TEXT NOT NULL DEFAULT '{}',
                ambiguity TEXT NOT NULL DEFAULT 'UNIQUE',
                whatsapp_surface_evidence TEXT,
                revocation_evidence TEXT,
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        // SQLite treats NULLs as distinct in unique indexes, so contacts without numbers
        // never collide while every real E.164 stays globally unique.
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_contacts_normalized_phone ON contacts(normalized_phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contacts_display_name ON contacts(display_name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < DATABASE_VERSION) onCreate(db)
    }

    @Synchronized
    fun upsert(entry: DirectoryEntry): DirectoryEntry {
        writableDatabase.beginTransactionNonExclusive()
        try {
            val saved = upsertLocked(entry)
            writableDatabase.setTransactionSuccessful()
            return saved
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun byIdLocked(id: String): DirectoryEntry? = readableDatabase.query(
        "contacts", COLUMNS, "id = ?", arrayOf(id), null, null, null, "1",
    ).use(::singleRow)

    private fun byPhoneLocked(normalizedPhone: String): DirectoryEntry? = readableDatabase.query(
        "contacts", COLUMNS, "normalized_phone = ?", arrayOf(normalizedPhone), null, null, null, "1",
    ).use(::singleRow)

    private fun upsertLocked(entry: DirectoryEntry): DirectoryEntry {
        val now = System.currentTimeMillis()
        byIdLocked(entry.id)?.let { existing ->
            return persistLocked(merge(existing, entry), now).also { recomputeNameAmbiguityLocked(it.displayName) }
        }
        val phone = Normalizer.normalizeUganda(entry.normalizedPhone)
        if (phone != null) {
            byPhoneLocked(phone)?.let { claimed ->
                return if (claimed.displayName.equals(entry.displayName, true)) {
                    persistLocked(merge(claimed, entry), now).also { recomputeNameAmbiguityLocked(it.displayName) }
                } else {
                    // AmbiguousNumberPolicy: a second distinct display name claiming an
                    // already-claimed number keeps BOTH entries; both are flagged so any
                    // resolution against that number fails closed. The unique index holds
                    // one row per E.164, so the challenger carries its raw number as an alias.
                    persistLocked(claimed.copy(ambiguity = Ambiguity.AMBIGUOUS_NUMBER), now)
                    val challenger = entry.copy(
                        id = entry.id.ifBlank { freshId() },
                        normalizedPhone = null,
                        aliases = entry.aliases + listOfNotNull(entry.normalizedPhone?.takeIf(String::isNotBlank)),
                        ambiguity = Ambiguity.AMBIGUOUS_NUMBER,
                    )
                    insertLocked(challenger, now).also { recomputeNameAmbiguityLocked(it.displayName) }
                }
            }
        }
        return insertLocked(entry.copy(id = entry.id.ifBlank { freshId() }, normalizedPhone = phone), now)
            .also { recomputeNameAmbiguityLocked(it.displayName) }
    }

    /** Union merge: newest display fields win; permissions and aliases only ever widen. */
    private fun merge(existing: DirectoryEntry, incoming: DirectoryEntry): DirectoryEntry {
        val mergedPermissions = Operation.entries.associateWith { op ->
            when {
                incoming.permissions[op] == Permission.ALLOW || existing.permissions[op] == Permission.ALLOW -> Permission.ALLOW
                else -> existing.permissions[op] ?: Permission.NONE
            }
        }
        return existing.copy(
            displayName = incoming.displayName.ifBlank { existing.displayName },
            normalizedPhone = existing.normalizedPhone ?: Normalizer.normalizeUganda(incoming.normalizedPhone),
            aliases = existing.aliases + incoming.aliases.filter(String::isNotBlank),
            isGroup = existing.isGroup || incoming.isGroup,
            source = existing.source.takeIf { it != EntrySource.OBSERVED } ?: incoming.source,
            lastVerifiedAt = maxOf(existing.lastVerifiedAt ?: 0L, incoming.lastVerifiedAt ?: 0L).takeIf { it > 0 },
            classification = strongestClassification(existing.classification, incoming.classification),
            commercialConsent = when {
                CommercialConsent.SUPPRESSED in setOf(incoming.commercialConsent, existing.commercialConsent) -> CommercialConsent.SUPPRESSED
                CommercialConsent.GRANTED in setOf(incoming.commercialConsent, existing.commercialConsent) -> CommercialConsent.GRANTED
                else -> CommercialConsent.UNKNOWN
            },
            permissions = mergedPermissions,
            whatsappSurfaceEvidence = incoming.whatsappSurfaceEvidence ?: existing.whatsappSurfaceEvidence,
            revocationEvidence = existing.revocationEvidence ?: incoming.revocationEvidence,
            ambiguity = existing.ambiguity,
        )
    }

    private fun strongestClassification(a: Classification, b: Classification): Classification = when {
        Classification.OWNER in setOf(a, b) -> Classification.OWNER
        Classification.TEST in setOf(a, b) -> Classification.TEST
        a != Classification.UNKNOWN -> a
        else -> b
    }

    @Synchronized
    fun byId(id: String): DirectoryEntry? = readableDatabase.query(
        "contacts", COLUMNS, "id = ?", arrayOf(id), null, null, null, "1",
    ).use(::singleRow)

    @Synchronized
    fun byPhone(normalizedPhone: String): DirectoryEntry? = readableDatabase.query(
        "contacts", COLUMNS, "normalized_phone = ?", arrayOf(normalizedPhone), null, null, null, "1",
    ).use(::singleRow)

    /** Exact case-insensitive display-name or alias matches, optionally surface-filtered. */
    @Synchronized
    fun exactNameMatches(name: String, isGroup: Boolean?): List<DirectoryEntry> = exactNameMatchesLocked(name, isGroup)

    @Synchronized
    fun listAmbiguous(): List<DirectoryEntry> = readableDatabase.query(
        "contacts", COLUMNS, null, null, null, null, "updated_at ASC",
    ).use { cursor -> allRows(cursor).filter { it.ambiguity != Ambiguity.UNIQUE } }

    /** Every durable entry (single-authority listing source for owner UI and engines). */
    @Synchronized
    fun listAll(): List<DirectoryEntry> = readableDatabase.query(
        "contacts", COLUMNS, null, null, null, null, "display_name COLLATE NOCASE ASC",
    ).use(::allRows)

    @Synchronized
    fun setPermission(id: String, operation: Operation, allow: Boolean) {
        byId(id)?.let { entry ->
            persistLocked(
                entry.copy(permissions = entry.permissions + (operation to if (allow) Permission.ALLOW else Permission.DENY)),
                System.currentTimeMillis(),
            )
        }
    }

    /** Explicit owner re-authorization: replaces the operation set atomically and clears only the contact-permission revocation marker. */
    @Synchronized
    fun authorizeLevel(id: String, level: ContactPermission): Boolean {
        val entry = byId(id) ?: return false
        persistLocked(
            entry.copy(
                permissions = operationsForLevel(level),
                revocationEvidence = null,
            ),
            System.currentTimeMillis(),
        )
        return true
    }

    @Synchronized
    fun revokeAll(id: String, evidence: String) {
        byId(id)?.let { entry ->
            persistLocked(
                entry.copy(
                    permissions = Operation.entries.associateWith { Permission.DENY },
                    revocationEvidence = evidence,
                    commercialConsent = CommercialConsent.SUPPRESSED,
                ),
                System.currentTimeMillis(),
            )
        }
    }

    /**
     * One-time lazy import of legacy SecureConfig JSON (contact permissions, monitored
     * WhatsApp targets, configured groups, owner phone). Idempotent via the meta table;
     * never widens authority beyond what the legacy JSON granted.
     */
    @Synchronized
    fun lazyMigrateFromLegacy(config: SecureConfig) {
        val done = readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(META_KEY)).use { it.moveToFirst() }
        if (done) return
        writableDatabase.beginTransactionNonExclusive()
        try {
            importLegacyContactPermissions(config.contactPermissionsJson)
            importLegacyMonitoredTargets(config.monitoredWhatsAppTargets(), config.ownerPhone)
            importLegacyGroups(config.whatsAppGroupsJson)
            writableDatabase.execSQL(
                "INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)",
                arrayOf<Any>(META_KEY, System.currentTimeMillis().toString()),
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /**
     * Migration/scrub for credential material that may have reached directory text
     * columns (evidence fields, aliases). Identity columns keep their phone digits;
     * credential shapes are rewritten to redaction markers. Idempotent.
     */
    @Synchronized
    fun scrubSecrets(): Int = SecretScrubber.scrub(writableDatabase)

    private fun importLegacyContactPermissions(raw: String) {
        runCatching {
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) continue
                val number = obj.optString("number").takeIf(String::isNotBlank)
                val level = runCatching { ContactPermission.valueOf(obj.optString("permission", "NONE")) }.getOrDefault(ContactPermission.NONE)
                val operations = operationsForLevel(level)
                if (operations.values.none { it == Permission.ALLOW }) continue
                upsertLocked(
                    DirectoryEntry(
                        id = "", displayName = name, normalizedPhone = number, aliases = emptySet(),
                        isGroup = obj.optBoolean("isGroup", false), source = EntrySource.OWNER_CREATED,
                        lastVerifiedAt = System.currentTimeMillis(), ambiguity = Ambiguity.UNIQUE,
                        classification = Classification.UNKNOWN, commercialConsent = CommercialConsent.UNKNOWN,
                        permissions = operations, whatsappSurfaceEvidence = "legacy contact_permissions list",
                        revocationEvidence = null,
                    ),
                )
            }
        }.getOrNull()
    }

    private fun importLegacyMonitoredTargets(targets: Set<String>, ownerPhone: String) {
        Normalizer.normalizeUganda(ownerPhone)?.let { phone ->
            val owner = DirectoryEntry(
                id = OWNER_ID, displayName = "Owner", normalizedPhone = phone, aliases = emptySet(),
                isGroup = false, source = EntrySource.OWNER_CREATED, lastVerifiedAt = System.currentTimeMillis(),
                ambiguity = Ambiguity.UNIQUE, classification = Classification.OWNER,
                commercialConsent = CommercialConsent.GRANTED,
                permissions = Operation.entries.associateWith { Permission.ALLOW },
                whatsappSurfaceEvidence = "legacy owner_phone", revocationEvidence = null,
            )
            if (byIdLocked(OWNER_ID) != null) upsertLocked(merge(byIdLocked(OWNER_ID)!!, owner)) else insertLocked(owner, System.currentTimeMillis())
        }
        targets.forEach { target ->
            val trimmed = target.trim()
            if (trimmed.isEmpty() || trimmed.equals(ownerPhone.trim(), true)) return@forEach
            val base = DirectoryEntry(
                id = "", displayName = trimmed, normalizedPhone = Normalizer.normalizeUganda(trimmed),
                aliases = emptySet(), isGroup = false, source = EntrySource.OBSERVED,
                lastVerifiedAt = System.currentTimeMillis(), ambiguity = Ambiguity.UNIQUE,
                classification = Classification.CUSTOMER, commercialConsent = CommercialConsent.GRANTED,
                permissions = mapOf(Operation.MONITOR to Permission.ALLOW),
                whatsappSurfaceEvidence = "legacy monitored_whatsapp list", revocationEvidence = null,
            )
            if (base.normalizedPhone != null || exactNameMatchesLocked(trimmed, null).isEmpty()) {
                upsertLocked(base)
            } else {
                // Name-keyed target: merge into the existing same-name entry instead of
                // creating a duplicate that would self-inflict AMBIGUOUS_NAME.
                upsertLocked(merge(exactNameMatchesLocked(trimmed, null).first(), base))
            }
        }
    }

    private fun importLegacyGroups(raw: String) {
        runCatching {
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                val group = array.optString(i).trim()
                if (group.isEmpty()) continue
                val base = DirectoryEntry(
                    id = "", displayName = group, normalizedPhone = null, aliases = emptySet(),
                    isGroup = true, source = EntrySource.OWNER_CREATED, lastVerifiedAt = System.currentTimeMillis(),
                    ambiguity = Ambiguity.UNIQUE, classification = Classification.UNKNOWN,
                    commercialConsent = CommercialConsent.UNKNOWN,
                    // Owner-configured broadcast groups carry standing consent through SEND;
                    // WRITE is never granted by migration.
                    permissions = mapOf(
                        Operation.MONITOR to Permission.ALLOW,
                        Operation.REPLY to Permission.ALLOW,
                        Operation.SEND to Permission.ALLOW,
                    ),
                    whatsappSurfaceEvidence = "legacy whatsapp_groups list", revocationEvidence = null,
                )
                val existing = exactNameMatchesLocked(group, true).firstOrNull()
                if (existing != null) upsertLocked(merge(existing, base)) else upsertLocked(base)
            }
        }.getOrNull()
    }

    private fun exactNameMatchesLocked(name: String, isGroup: Boolean?): List<DirectoryEntry> {
        val wanted = name.trim()
        if (wanted.isEmpty()) return emptyList()
        val all = readableDatabase.query("contacts", COLUMNS, null, null, null, null, "display_name COLLATE NOCASE ASC").use(::allRows)
        return all.filter { entry ->
            (isGroup == null || entry.isGroup == isGroup) &&
                (entry.displayName.equals(wanted, true) || entry.aliases.any { it.equals(wanted, true) })
        }
    }

    /** Two entries sharing one display name are flagged so name resolution fails closed. */
    private fun recomputeNameAmbiguityLocked(displayName: String) {
        exactNameMatchesLocked(displayName, null)
            .filter { it.ambiguity == Ambiguity.UNIQUE }
            .takeIf { it.size > 1 }
            ?.forEach { match -> persistLocked(match.copy(ambiguity = Ambiguity.AMBIGUOUS_NAME), System.currentTimeMillis()) }
    }

    private fun insertLocked(entry: DirectoryEntry, now: Long): DirectoryEntry {
        val id = entry.id.ifBlank { freshId() }
        writableDatabase.insertOrThrow("contacts", null, rowValues(id, entry, now))
        return byIdLocked(id) ?: entry.copy(id = id)
    }

    private fun persistLocked(entry: DirectoryEntry, now: Long): DirectoryEntry {
        writableDatabase.insertWithOnConflict("contacts", null, rowValues(entry.id, entry, now), SQLiteDatabase.CONFLICT_REPLACE)
        return byIdLocked(entry.id) ?: entry
    }

    private fun rowValues(id: String, entry: DirectoryEntry, now: Long) = ContentValues().apply {
        put("id", id)
        put("display_name", entry.displayName)
        put("normalized_phone", Normalizer.normalizeUganda(entry.normalizedPhone))
        put("aliases_json", org.json.JSONArray(entry.aliases.filter(String::isNotBlank)).toString())
        put("is_group", if (entry.isGroup) 1 else 0)
        put("source", entry.source.name)
        put("last_verified_at", entry.lastVerifiedAt)
        put("classification", entry.classification.name)
        put("commercial_consent", entry.commercialConsent.name)
        put("permissions_json", org.json.JSONObject().apply {
            entry.permissions.forEach { (op, permission) -> put(op.name, permission.name) }
        }.toString())
        put("ambiguity", entry.ambiguity.name)
        put("whatsapp_surface_evidence", entry.whatsappSurfaceEvidence)
        put("revocation_evidence", entry.revocationEvidence)
        put("updated_at", now)
    }

    private fun singleRow(cursor: Cursor): DirectoryEntry? = if (!cursor.moveToFirst()) null else readRow(cursor)

    private fun allRows(cursor: Cursor): List<DirectoryEntry> = buildList {
        while (cursor.moveToNext()) add(readRow(cursor))
    }

    private fun readRow(cursor: Cursor): DirectoryEntry {
        fun text(column: String): String = cursor.getString(cursor.getColumnIndexOrThrow(column))
        val permissions = runCatching {
            val obj = org.json.JSONObject(text("permissions_json"))
            Operation.entries.associateWith { op ->
                runCatching { Permission.valueOf(obj.optString(op.name, Permission.NONE.name)) }.getOrDefault(Permission.NONE)
            }
        }.getOrElse { Operation.entries.associateWith { Permission.NONE } }
        val aliases = runCatching {
            val array = org.json.JSONArray(text("aliases_json"))
            buildSet { for (i in 0 until array.length()) add(array.optString(i)) }
        }.getOrElse { emptySet() }
        return DirectoryEntry(
            id = text("id"),
            displayName = text("display_name"),
            normalizedPhone = cursor.getString(cursor.getColumnIndexOrThrow("normalized_phone")),
            aliases = aliases,
            isGroup = cursor.getInt(cursor.getColumnIndexOrThrow("is_group")) == 1,
            source = runCatching { EntrySource.valueOf(text("source")) }.getOrDefault(EntrySource.OBSERVED),
            lastVerifiedAt = if (cursor.isNull(cursor.getColumnIndexOrThrow("last_verified_at"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("last_verified_at")),
            ambiguity = runCatching { Ambiguity.valueOf(text("ambiguity")) }.getOrDefault(Ambiguity.UNIQUE),
            classification = runCatching { Classification.valueOf(text("classification")) }.getOrDefault(Classification.UNKNOWN),
            commercialConsent = runCatching { CommercialConsent.valueOf(text("commercial_consent")) }.getOrDefault(CommercialConsent.UNKNOWN),
            permissions = permissions,
            whatsappSurfaceEvidence = cursor.getString(cursor.getColumnIndexOrThrow("whatsapp_surface_evidence")),
            revocationEvidence = cursor.getString(cursor.getColumnIndexOrThrow("revocation_evidence")),
        )
    }

    companion object {
        const val DATABASE_NAME = "contact_directory.db"
        const val DATABASE_VERSION = 1
        private const val META_KEY = "legacy_import_done"
        const val OWNER_ID = "directory-owner"

        private val COLUMNS = arrayOf(
            "id", "display_name", "normalized_phone", "aliases_json", "is_group", "source",
            "last_verified_at", "classification", "commercial_consent", "permissions_json",
            "ambiguity", "whatsapp_surface_evidence", "revocation_evidence", "updated_at",
        )

        /** Legacy cumulative levels become explicit per-operation grants (never wider). */
        fun operationsForLevel(level: ContactPermission): Map<Operation, Permission> {
            val order = listOf(Operation.MONITOR, Operation.REPLY, Operation.SEND, Operation.WRITE)
            val allowThrough = when (level) {
                ContactPermission.FULL -> Operation.WRITE
                ContactPermission.SEND -> Operation.SEND
                ContactPermission.REPLY -> Operation.REPLY
                ContactPermission.MONITOR -> Operation.MONITOR
                ContactPermission.NONE -> return Operation.entries.associateWith { Permission.NONE }
            }
            return Operation.entries.associateWith { op ->
                if (order.indexOf(op) <= order.indexOf(allowThrough)) Permission.ALLOW else Permission.NONE
            }
        }

        private fun freshId(): String = "dir-" + UUID.randomUUID().toString()
    }
}
