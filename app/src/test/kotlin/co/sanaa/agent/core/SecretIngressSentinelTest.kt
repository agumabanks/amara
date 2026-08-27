package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.api.GroqClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sentinel for the credential-ingress directive: no PIN/credential material may exist
 * in ANY durable SQLite text column, any receipt, any owner export, or any Groq
 * request body — and the AutonomyController must run the credential guard before the
 * first persistence call. The database walk is mechanical: every table and every
 * TEXT column discovered from sqlite_master/PRAGMA is both seeded and asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecretIngressSentinelTest {

    private lateinit var context: Context
    private lateinit var memory: AmaraMemory
    private lateinit var config: SecureConfig

    /** The secret used throughout; must never survive anywhere durable or outbound. */
    private val pin = "483920"
    private val secretCommand = "Update the Terminal, my pin is $pin and remember it"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
        config = SecureConfig(context, useEncryptedPrefs = false)
        config.groqApiKey = "test-key"
    }

    @After
    fun tearDown() {
        memory.close()
    }

    // ---------- guard behavior ----------

    @Test
    fun guardRedactsLeadingAndTrailingPinStatements() {
        val leading = CredentialGuard.inspect(secretCommand)
        assertTrue(leading.credentialDetected)
        assertTrue("terminal_pin" in leading.kinds)
        assertFalse(leading.safeForPersistence.contains(pin))
        assertTrue(leading.safeForPersistence.contains(Redactor.REDACTION_PREFIX))

        val trailing = CredentialGuard.inspect("use $pin as the Soko PIN")
        assertTrue(trailing.credentialDetected)
        assertFalse(trailing.safeForPersistence.contains(pin))

        assertFalse(CredentialGuard.inspect("check my Soko bookings").credentialDetected)
        assertFalse(Redactor.containsSecretShape(leading.safeForPersistence))
    }

    @Test
    fun guardKeepsTheCommandWordsSoParsingStillWorks() {
        val inspection = CredentialGuard.inspect(secretCommand)
        assertTrue(inspection.safeForPersistence.contains("Terminal"))
        assertTrue(inspection.safeForPersistence.contains("remember"))
    }

    // ---------- controller ordering (mechanical source proof) ----------

    @Test
    fun autonomyControllerRunsTheCredentialGuardBeforeTheFirstPersistenceCall() {
        val source = repoRoot()
            .resolve("app/src/main/kotlin/co/sanaa/agent/core/AutonomyController.kt")
            .toFile()
            .readText()
        val guardAt = source.indexOf("CredentialGuard.inspect(command)")
        val instructionAt = source.indexOf("recordInstruction(")
        val journalAt = source.indexOf("createTaskJournal(")
        val ownerChatAt = source.indexOf("recordOwnerChat(")
        assertTrue("guard must exist", guardAt >= 0)
        assertTrue(guardAt < instructionAt)
        assertTrue(guardAt < journalAt)
        assertTrue(guardAt < ownerChatAt)
        // Every durable sink uses the redacted command, never the raw one.
        assertFalse(Regex("""recordInstruction\(command\b""").containsMatchIn(source))
        assertFalse(Regex("""recordOwnerChat\(command\b""").containsMatchIn(source))
        assertFalse(Regex("""createTaskJournal\([^,]+,\s*command\)""").containsMatchIn(source))
        // Persisted plan JSON redacts grounded step messages (the raw-PIN carrier).
        assertTrue(source.contains("put(\"message\", Redactor.redact(step.message))"))
    }

    // ---------- mechanical SQLite sweep ----------

    private fun textColumns(): List<Pair<String, String>> {
        val db = memory.readableDatabase
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_metadata'",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.flatMap { table ->
            db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val name = c.getString(c.getColumnIndexOrThrow("name"))
                        val type = c.getString(c.getColumnIndexOrThrow("type")).uppercase()
                        if ("TEXT" in type || "VARCHAR" in type || "CLOB" in type) add(table to name)
                    }
                }
            }
        }
    }

    /** Primary-key / unique-identity columns are asserted but never re-seeded. */
    private fun seedExcludedColumns(): Set<String> {
        val db = memory.readableDatabase
        val excluded = mutableSetOf<String>()
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_metadata'",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        for (table in tables) {
            db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(c.getColumnIndexOrThrow("name"))
                    if (c.getInt(c.getColumnIndexOrThrow("pk")) > 0) excluded += "$table.$name"
                }
            }
        }
        return excluded
    }

    private fun populateRepresentativeRows() {
        val now = System.currentTimeMillis()
        memory.recordConversation("Buyer Seven", "+256700000007", "whatsapp", "received", "How much is delivery?")
        memory.recordInstruction("check bookings")
        memory.recordAction("autonomy_step", "Buyer Seven", "WhatsApp", "check bookings", "did work", "verified", "{}", true)
        val instructionId = memory.recordInstruction("seed")
        val taskId = memory.createTaskJournal(instructionId, "seed command")
        memory.updateTaskJournal(taskId, "completed", "report", "done")
        memory.recordTaskJournalStep(taskId, 1, 1, "wait", "settle", "pkg", "sig", "pkg", "sig", "verified", "ok")
        memory.recordOwnerChat("hello amara")
        memory.createApprovalRequest("apply_soko_edit", "Listing", "desc", "{}", "{}", ActionRisk.LOW_IMPACT_CHANGE)
        memory.recordFailure("t", "r", "s", "cap", "pkg", "stage", "cause", false, 1, "{}", "fix", "FAILED_PERMANENT", "next")
        memory.recordBrainFailure("stage", "model", "req", "hash", 1, false, "[]", "fix", "FAILED_PERMANENT")
        memory.upsertSideEffectTransaction(
            SideEffectTransaction("seed-key-1", "send_whatsapp", "Buyer Seven", ContentHashing.hash("m"), null, SideEffectState.VERIFIED, now, now, "{\"deliveryState\":\"sent\"}"),
        )
        memory.insertBudgetSpendEntry("contract-1", 5_000, "seed spend", now)
        memory.recordEnforcementState("contract-1", "WITHIN_BUDGET", "within", now)
        memory.insertBudgetReservation("res-1", "contract-1", 1_000, "seed reserve", now)
        memory.recordSelectorOutcome("com.whatsapp", "tap", "selector-text", true, "ok")
        memory.recordProductSeen("com.soko24.soko_seller_terminal", "Thermal Printer", 120_000, "desc")
    }

    @Test
    fun scrubRemovesSeededCredentialMaterialFromEverySqliteTextColumn() {
        populateRepresentativeRows()
        val excluded = seedExcludedColumns()
        val db = memory.writableDatabase
        var seededColumns = 0
        for ((table, column) in textColumns()) {
            if ("$table.$column" in excluded) continue
            val updated = runCatching {
                db.execSQL("UPDATE \"$table\" SET \"$column\" = ? WHERE rowid IN (SELECT rowid FROM \"$table\" LIMIT 1)", arrayOf<Any>(secretCommand))
            }.isSuccess
            if (updated) seededColumns++
        }
        assertTrue("sentinel must seed a meaningful number of free-text columns", seededColumns >= 25)

        val scrubbed = memory.scrubSecrets()

        assertTrue("scrub must rewrite the seeded material", scrubbed > 0)
        for ((table, column) in textColumns()) {
            val values = db.rawQuery("SELECT \"$column\" FROM \"$table\" WHERE \"$column\" IS NOT NULL", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) ?: "") }
            }
            for (value in values) {
                assertFalse(
                    "column $table.$column still carries the PIN after scrub: $value",
                    value.contains(pin),
                )
            }
        }
        // Identity columns keep their digits while credential shapes are still removed.
        memory.recordConversation("Phone Keeper", "+256$pin", "whatsapp", "received", "x")
        memory.scrubSecrets()
        val kept = db.rawQuery(
            "SELECT contact_number FROM conversations WHERE contact_name = 'Phone Keeper' LIMIT 1", null,
        ).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
        assertTrue("phone identity must survive the scrub", kept.startsWith("+256"))
    }

    @Test
    fun scrubIsIdempotent() {
        populateRepresentativeRows()
        memory.recordConversation("Leaker", null, "whatsapp", "received", secretCommand)
        val first = memory.scrubSecrets()
        assertTrue(first > 0)
        val second = memory.scrubSecrets()
        assertEquals(0, second)
    }

    // ---------- receipts ----------

    @Test
    fun receiptAndTransactionEvidenceAreScrubbed() {
        val now = System.currentTimeMillis()
        memory.writableDatabase.execSQL(
            """INSERT INTO side_effect_receipts(idempotency_key, created_at, capability, target, status, evidence)
               VALUES('seed-receipt-1', ?, 'send_whatsapp', 'Buyer', 'verified', ?)""",
            arrayOf<Any>(now, "sent proof: $secretCommand"),
        )
        memory.upsertSideEffectTransaction(
            SideEffectTransaction("seed-tx-1", "send_whatsapp", "Buyer", ContentHashing.hash("m"), null,
                SideEffectState.VERIFIED, now, now, "evidence: pin is $pin"),
        )
        memory.scrubSecrets()
        val receipt = memory.readableDatabase.rawQuery(
            "SELECT evidence FROM side_effect_receipts WHERE idempotency_key='seed-receipt-1'", null,
        ).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
        val tx = memory.readableDatabase.rawQuery(
            "SELECT evidence FROM side_effect_transactions WHERE idempotency_key='seed-tx-1'", null,
        ).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
        assertFalse(receipt.contains(pin))
        assertFalse(tx.contains(pin))
    }

    // ---------- owner export ----------

    @Test
    fun ownerExportNeverCarriesCredentialMaterial() {
        memory.recordConversation("Buyer Seven", null, "whatsapp", "received", secretCommand)
        memory.recordAction("autonomy_step", "Buyer Seven", null, secretCommand, "did work", "ok", null, true)
        val export = memory.exportOwnerData().toString()
        assertFalse(export.contains(pin))
    }

    // ---------- Groq request bodies ----------

    @Test
    fun groqRequestBodiesNeverCarryCredentialMaterial() {
        val server = MockWebServer()
        server.start()
        val previousEndpoint = config.groqEndpoint
        config.groqEndpoint = server.url("/v1/chat/completions").toString()
        val content = JSONObject.quote("{\"ok\":true}")
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":$content},"finish_reason":"stop"}]}"""),
        )
        try {
            val client = GroqClient(config, memory, allowInsecureTestEndpoint = true)
            val reply = runBlocking { client.completeJson(secretCommand) }
            assertTrue(reply.optBoolean("ok"))
            assertEquals(1, server.requestCount)
            val body = server.takeRequest().body.readUtf8()
            val prompt = JSONObject(body).getJSONArray("messages").let { messages ->
                buildString { for (i in 0 until messages.length()) append(messages.getJSONObject(i).optString("content")) }
            }
            assertFalse("raw PIN reached the provider request body", prompt.contains(pin))
            assertTrue(prompt.contains(Redactor.REDACTION_PREFIX))
        } finally {
            config.groqEndpoint = previousEndpoint
            server.shutdown()
        }
    }

    @Test
    fun jsonArrayImportPathInPromptsStaysIntactAfterRedaction() {
        // Redaction must not corrupt structured prompt content (schema examples, arrays).
        val prompt = """Return ONLY JSON: {"steps":[{"action":"wait","message":"123456784"}]} and OTP is 556677"""
        val safe = CredentialGuard.inspect(prompt).safeForPersistence
        assertFalse(safe.contains("556677"))
        assertTrue(safe.contains("\"steps\""))
    }

    private fun repoRoot(): java.nio.file.Path {
        var dir = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null && !dir.resolve("settings.gradle").toFile().exists()) {
            dir = dir.parent
        }
        return requireNotNull(dir) { "repo root with settings.gradle not found from ${System.getProperty("user.dir")}" }
    }
}
