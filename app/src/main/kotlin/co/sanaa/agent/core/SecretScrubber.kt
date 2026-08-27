package co.sanaa.agent.core

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase

/**
 * Generic secret scrub over an existing SQLite database. Walks EVERY user table and
 * EVERY text column (discovered from sqlite_master + PRAGMA table_info, so future
 * tables are covered automatically) and rewrites secret-shaped content to the stable
 * redaction marker. Identity columns that legitimately carry long digit runs (phone
 * numbers) are redacted with credential-shape rules only, so contact identity
 * survives while embedded PIN/OTP/key material is still removed.
 */
object SecretScrubber {

    /** Columns whose values are phone/key identity, not free text. */
    private val IDENTITY_COLUMN_HINTS = listOf(
        "normalized_phone", "contact_number", "contact_key", "aliases_json", "phone",
    )

    /** Applied to the whole database; returns the number of scrubbed cells. */
    fun scrub(db: SQLiteDatabase, vault: CredentialVault? = null): Int {
        var scrubbed = 0
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_metadata'",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val columnsByTable = tables.associateWith { table -> textColumnsOf(db, table).orEmpty() }
        // Exact configured credentials used to be decrypted through Android
        // Keystore once for every database cell (O(cells × credentials)). On a
        // mature employee ledger that held AgentRuntime's singleton lock long
        // enough to ANR the UI and accessibility service. Apply each secret once
        // per text column in SQLite, then run the generic shape scrub normally.
        if (vault != null) {
            vault.forEachKnownSecret { id, secret ->
                val marker = "[REDACTED:CREDENTIAL:$id]"
                for ((table, columns) in columnsByTable) {
                    for ((column, isIdentity) in columns) {
                        if (isIdentity) continue
                        val changed = runCatching {
                            db.execSQL(
                                "UPDATE ${quote(table)} SET ${quote(column)} = REPLACE(${quote(column)}, ?, ?) " +
                                    "WHERE ${quote(column)} IS NOT NULL AND instr(${quote(column)}, ?) > 0",
                                arrayOf<Any>(secret, marker, secret),
                            )
                            DatabaseUtils.longForQuery(db, "SELECT changes()", null).toInt()
                        }.getOrDefault(0)
                        scrubbed += changed
                    }
                }
            }
        }
        for ((table, textColumns) in columnsByTable) {
            for ((column, isIdentity) in textColumns) {
                // WITHOUT ROWID tables fail the rowid query inside scrubColumn and are
                // skipped honestly; every production table is a normal rowid table.
                scrubbed += scrubColumn(db, table, "rowid", column, isIdentity)
            }
        }
        return scrubbed
    }

    /** (column name, isIdentity) pairs for every TEXT-typed column; null when the table vanished. */
    private fun textColumnsOf(db: SQLiteDatabase, table: String): List<Pair<String, Boolean>>? = runCatching {
        db.rawQuery("PRAGMA table_info(${quote(table)})", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    val type = cursor.getString(cursor.getColumnIndexOrThrow("type")).uppercase()
                    if ("TEXT" in type || "VARCHAR" in type || "CLOB" in type) {
                        add(name to IDENTITY_COLUMN_HINTS.any { hint -> name.contains(hint, ignoreCase = true) })
                    }
                }
            }
        }
    }.getOrNull()

    private fun rowidColumnOf(db: SQLiteDatabase, table: String): String = "rowid"

    private fun scrubColumn(
        db: SQLiteDatabase,
        table: String,
        rowidColumn: String,
        column: String,
        isIdentity: Boolean,
    ): Int {
        val updates = mutableListOf<Pair<String, String>>() // rowid -> redacted value
        val readable = runCatching {
            db.rawQuery(
                "SELECT ${quote(rowidColumn)}, ${quote(column)} FROM ${quote(table)} WHERE ${quote(column)} IS NOT NULL",
                null,
            ).use { cursor: Cursor ->
                while (cursor.moveToNext()) {
                    val rowId = cursor.getString(0)
                    val value = cursor.getString(1) ?: continue
                    val redacted = redactValue(value, isIdentity)
                    if (redacted != value) updates += rowId to redacted
                }
            }
            true
        }.getOrDefault(false)
        if (!readable) return 0
        var applied = 0
        for ((rowId, redacted) in updates) {
            val written = runCatching {
                db.execSQL(
                    "UPDATE ${quote(table)} SET ${quote(column)} = ? WHERE ${quote(rowidColumn)} = ?",
                    arrayOf<Any>(redacted, rowId),
                )
            }.isSuccess
            if (written) applied++
        }
        return applied
    }

    private fun redactValue(value: String, isIdentity: Boolean): String =
        if (isIdentity) Redactor.redactCredentialShapes(value) else Redactor.redact(value)

    private fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
}
