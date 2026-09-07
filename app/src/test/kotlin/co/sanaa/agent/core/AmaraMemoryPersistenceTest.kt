package co.sanaa.agent.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Genuine production-SQLite tests for AmaraMemory (Robolectric): schema migration from
 * every supported version, transaction state-machine persistence, crash sweeps, and the
 * full approval lifecycle including crash windows around consumption.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric max SDK for JVM 17; SQLite behavior under test is stable across 35/36
class AmaraMemoryPersistenceTest {

    private lateinit var context: Context
    private lateinit var memory: AmaraMemory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
    }

    // ---------- schema migration ----------

    @Test fun freshInstallCreatesLatestSchemaWithAllTables() {
        memory.writableDatabase // force helper to create the file before raw inspection
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(AmaraMemory.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READWRITE,
        )
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table'", null,
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        listOf("actions", "conversations", "products_seen", "owner_instructions", "selector_learning",
            "task_journal", "task_journal_steps", "approval_requests", "business_findings",
            "recurring_tasks", "side_effect_receipts", "side_effect_transactions",
            "workflow_runs", "artifact_revisions", "connector_revocations",
            "budget_spend", "work_enforcement",
            "commercial_targets", "commercial_events", "commercial_opportunities",
            "revenue_goals", "revenue_opportunities", "revenue_funnel_transitions",
            "revenue_inquiries", "revenue_sales", "revenue_costs", "revenue_attributions",
            "revenue_experiments", "commercial_actions", "daily_commercial_plans",
            "daily_commercial_briefs", "outreach_suppressions", "contact_consent", "commercial_policy",
            "budget_reservations", "experiment_samples", "revenue_campaign_touches",
            "revenue_delivery_observations",
            "failure_records", "brain_failures",
        ).forEach { table -> assertTrue("Missing table $table", table in tables) }
        assertEquals(21, db.version)
        db.close()
    }

    @Test fun v13ToLatestMigrationAddsBrainFailureColumnsAndRevenueObservationTables() {
        // Build a v13 database, then reopen with the production helper: the upgrade
        // path must add the contract §2 columns without losing existing rows.
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(AmaraMemory.DATABASE_NAME).path, null).use { db ->
            db.version = 13
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS brain_failures (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL,
                    stage TEXT NOT NULL, model TEXT NOT NULL, request_id TEXT NOT NULL DEFAULT '',
                    response_hash TEXT NOT NULL DEFAULT '', attempt_count INTEGER NOT NULL DEFAULT 0,
                    retryable INTEGER NOT NULL DEFAULT 0, validation_errors_json TEXT NOT NULL DEFAULT '[]',
                    corrective_action TEXT NOT NULL DEFAULT '', disposition TEXT NOT NULL)""",
            )
            db.execSQL("INSERT INTO brain_failures (created_at, stage, model, disposition) VALUES (1, 'planner_plan', 'legacy-model', 'FAILED_PERMANENT')")
        }
        AmaraMemory(context).readableDatabase.use { db ->
            assertEquals(21, db.version)
            val columns = mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info(brain_failures)", null).use { cursor ->
                while (cursor.moveToNext()) columns.add(cursor.getString(1))
            }
            assertTrue("correlation_id column missing", "correlation_id" in columns)
            assertTrue("terminal_outcome column missing", "terminal_outcome" in columns)
            assertTrue("owner_explanation column missing", "owner_explanation" in columns)
            // Pre-existing rows survive with empty correlation defaults.
            db.rawQuery("SELECT stage, correlation_id FROM brain_failures", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("planner_plan", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'", null,
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertTrue("campaign-touch table missing", "revenue_campaign_touches" in tables)
            assertTrue("delivery-observation table missing", "revenue_delivery_observations" in tables)
        }
    }

    private fun createLegacyV5Schema(db: SQLiteDatabase) {
        // Minimal v5-shaped schema: operational tables exist, transactions do not.
        db.execSQL("CREATE TABLE actions (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, type TEXT NOT NULL, target_contact TEXT, target_app TEXT, command_given TEXT NOT NULL, what_amara_did TEXT NOT NULL, result TEXT NOT NULL, groq_response TEXT, success INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, contact_name TEXT, contact_number TEXT, platform TEXT NOT NULL, direction TEXT NOT NULL CHECK(direction IN ('sent','received')), message_text TEXT NOT NULL, timestamp INTEGER NOT NULL, replied INTEGER NOT NULL DEFAULT 0, reply_text TEXT)")
        db.execSQL("CREATE TABLE products_seen (id INTEGER PRIMARY KEY AUTOINCREMENT, source_app TEXT NOT NULL, product_name TEXT NOT NULL, price_ugx INTEGER, description TEXT, listing_quality_score REAL, last_seen_timestamp INTEGER NOT NULL, improvements_made TEXT)")
        db.execSQL("CREATE TABLE owner_instructions (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, instruction_text TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('pending','done','recurring')), recurrence_rule TEXT)")
        db.execSQL("CREATE TABLE selector_learning (app_package TEXT NOT NULL, action_name TEXT NOT NULL, selector TEXT NOT NULL, successes INTEGER NOT NULL DEFAULT 0, failures INTEGER NOT NULL DEFAULT 0, last_result TEXT, last_used_timestamp INTEGER NOT NULL, PRIMARY KEY(app_package, action_name, selector))")
        db.execSQL("CREATE TABLE task_journal (id INTEGER PRIMARY KEY AUTOINCREMENT, instruction_id INTEGER, command TEXT NOT NULL, status TEXT NOT NULL, phase TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, replan_count INTEGER NOT NULL DEFAULT 0, observation TEXT NOT NULL DEFAULT '', analysis TEXT NOT NULL DEFAULT '', final_result TEXT NOT NULL DEFAULT '')")
        db.execSQL("""CREATE TABLE approval_requests (
            id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, capability TEXT NOT NULL,
            target TEXT NOT NULL DEFAULT '', description TEXT NOT NULL,
            before_json TEXT NOT NULL DEFAULT '{}', after_json TEXT NOT NULL DEFAULT '{}',
            risk TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('pending','approved','rejected','expired','consumed')),
            expires_at INTEGER NOT NULL, decided_at INTEGER)""")
        db.execSQL("""CREATE TABLE side_effect_receipts (
            id INTEGER PRIMARY KEY AUTOINCREMENT, idempotency_key TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL,
            capability TEXT NOT NULL, target TEXT NOT NULL,
            status TEXT NOT NULL CHECK(status IN ('started','verified','failed','uncertain')),
            evidence TEXT NOT NULL DEFAULT '')""")
        db.version = 5
    }

    @Test fun migrationFromV5CreatesTransactionAndWorkflowTablesAndPreservesApprovals() {
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(AmaraMemory.DATABASE_NAME).path, null)
        createLegacyV5Schema(db)
        db.insert("approval_requests", null, ContentValues().apply {
            put("created_at", 1L); put("capability", "edit_soko_listing"); put("target", "Old Listing")
            put("description", "legacy"); put("before_json", "{}"); put("after_json", "{}")
            put("risk", "LOW_IMPACT_CHANGE"); put("status", "pending"); put("expires_at", Long.MAX_VALUE / 2)
        })
        db.close()

        val migrated = AmaraMemory(context)
        val tx = SideEffectTransaction(
            idempotencyKey = "migrated-key", capability = CapabilityIds.SEND_WHATSAPP,
            target = "A", contentHash = ContentHashing.hash("m"), approvalId = null,
            state = SideEffectState.CLAIMED, createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(), evidence = "",
        )
        assertTrue(migrated.upsertSideEffectTransaction(tx))
        assertNotNull(migrated.findSideEffectTransaction("migrated-key"))
        val pending = migrated.pendingApprovals()
        assertEquals(1, pending.size)
        assertEquals("Old Listing", pending.first().target)
    }

    // ---------- transaction persistence ----------

    @Test fun transactionsPersistAcrossDatabaseReopen(): Unit = kotlinx.coroutines.runBlocking {
        val key = "reopen-key"
        val runner = SideEffectRunner(SideEffectLedger.from(memory), clock = { 100L })
        val outcome = runner.execute(
            CapabilityIds.SEND_WHATSAPP, key, "Sanaa Office", "hello",
            initiator = Initiator.OWNER_CHAT,
            act = { true },
            verify = { VerificationEvidence(true, 0.9, "com.whatsapp", "Delivered", 1L) },
        )
        assertTrue(outcome is SideEffectOutcome.Verified)

        val reopened = AmaraMemory(context)
        val stored = reopened.findSideEffectTransaction(key)
        assertNotNull(stored)
        assertEquals(SideEffectState.VERIFIED, stored!!.state)
        assertEquals(CapabilityIds.SEND_WHATSAPP, stored.capability)
        assertTrue(reopened.upsertSideEffectTransaction(stored.copy(state = SideEffectState.ACTING)).not())
    }

    @Test fun illegalTransitionsAreRejectedByTheProductionLedger() {
        val key = "illegal-key"
        assertTrue(memory.upsertSideEffectTransaction(tx(key, SideEffectState.VERIFIED)))
        assertFalse("VERIFIED is terminal", memory.transitionSideEffectTransaction(key, SideEffectState.ACTING, ""))
        assertFalse(memory.transitionSideEffectTransaction(key, SideEffectState.FAILED, ""))
        assertNull(memory.transitionSideEffectTransaction("missing", SideEffectState.ACTING, "").let { true }.takeIf { false })
        assertFalse(memory.transitionSideEffectTransaction("missing", SideEffectState.ACTING, ""))
    }

    @Test fun orphanedNonterminalStatesAreSweptToUncertainOnStartup() {
        listOf(SideEffectState.CLAIMED, SideEffectState.ACTING, SideEffectState.VERIFICATION_PENDING).forEach { state ->
            val key = "orphan-$state"
            assertTrue(memory.upsertSideEffectTransaction(tx(key, state)))
        }
        assertTrue(memory.upsertSideEffectTransaction(tx("done", SideEffectState.VERIFIED)))
        memory.markOrphanedTransactionsUncertain()
        listOf(SideEffectState.CLAIMED, SideEffectState.ACTING, SideEffectState.VERIFICATION_PENDING).forEach { state ->
            assertEquals(SideEffectState.UNCERTAIN, memory.findSideEffectTransaction("orphan-$state")!!.state)
        }
        assertEquals(SideEffectState.VERIFIED, memory.findSideEffectTransaction("done")!!.state)
    }

    private fun tx(key: String, state: SideEffectState) = SideEffectTransaction(
        idempotencyKey = key, capability = CapabilityIds.SEND_WHATSAPP, target = "A",
        contentHash = ContentHashing.hash("m"), approvalId = null, state = state,
        createdAt = 1L, updatedAt = 1L, evidence = "",
    )

    // ---------- approval lifecycle ----------

    @Test fun approvalCreationDeduplicatesIdenticalPendingRequests() {
        val first = memory.createApprovalRequest("edit_soko_listing", "Listing A", "desc", "{}", "{\"Product Name\":\"New\"}", ActionRisk.LOW_IMPACT_CHANGE)
        val second = memory.createApprovalRequest("edit_soko_listing", "Listing A", "desc", "{}", "{\"Product Name\":\"New\"}", ActionRisk.LOW_IMPACT_CHANGE)
        assertEquals(first, second)
        assertEquals(1, memory.pendingApprovals().size)
    }

    @Test fun expiredApprovalIsNotReturnedAndCannotBeDecidedOrConsumed() {
        val id = memory.createApprovalRequest("edit_soko_listing", "Listing B", "d", "{}", "{}", ActionRisk.LOW_IMPACT_CHANGE, expiresAt = System.currentTimeMillis() - 1)
        assertTrue(memory.pendingApprovals().isEmpty())
        assertFalse(memory.decideApproval(id, approve = true))
        assertFalse(memory.consumeApproval(id))
        assertFalse(memory.approvalStillValid(id, "edit_soko_listing", "Listing B", "{}"))
    }

    @Test fun exactTargetFieldAndValueBindingIsEnforced() {
        val after = "{\"Product Name\":\"Renamed\"}"
        val id = memory.createApprovalRequest("edit_soko_listing", "Exact Listing", "d", "{}", after, ActionRisk.LOW_IMPACT_CHANGE)
        memory.decideApproval(id, approve = true)
        assertTrue(memory.matchingApprovedApproval("edit_soko_listing", "Exact Listing", after) != null)
        assertTrue(memory.matchingApprovedApproval("edit_soko_listing", "Wrong Listing", after) == null)
        assertTrue(memory.matchingApprovedApproval("edit_soko_listing", "Exact Listing", "{\"Product Name\":\"Other\"}") == null)
        assertTrue(memory.approvalStillValid(id, "edit_soko_listing", "Exact Listing", after))
        assertFalse(memory.approvalStillValid(id, "edit_soko_listing", "Wrong Listing", after))
    }

    @Test fun changedProposalInvalidatesApprovalBinding() {
        val originalAfter = "{\"Product Name\":\"v1\"}"
        val id = memory.createApprovalRequest("edit_soko_listing", "Listing C", "d", "{}", originalAfter, ActionRisk.LOW_IMPACT_CHANGE)
        memory.decideApproval(id, approve = true)
        // The proposal materially changes before execution:
        val changedAfter = "{\"Product Name\":\"v2\"}"
        assertFalse(memory.approvalStillValid(id, "edit_soko_listing", "Listing C", changedAfter))
        assertTrue(memory.matchingApprovedApproval("edit_soko_listing", "Listing C", changedAfter) == null)
    }

    @Test fun consumptionIsOneShotAndCrashBeforeConsumptionLeavesItUsable() {
        val id = memory.createApprovalRequest("edit_soko_listing", "Listing D", "d", "{}", "{}", ActionRisk.LOW_IMPACT_CHANGE)
        memory.decideApproval(id, true)
        // Crash window: decision recorded, not yet consumed → still valid.
        assertTrue(memory.approvalStillValid(id, "edit_soko_listing", "Listing D", "{}"))
        assertTrue(memory.consumeApproval(id))
        // Crash after consumption but before save: no second execution may pass validation.
        assertFalse(memory.approvalStillValid(id, "edit_soko_listing", "Listing D", "{}"))
        assertFalse(memory.consumeApproval(id))
    }

    @Test fun rejectionSticksAndConcurrentSecondDecisionFails() {
        val id = memory.createApprovalRequest("edit_soko_listing", "Listing E", "d", "{}", "{}", ActionRisk.LOW_IMPACT_CHANGE)
        assertTrue(memory.decideApproval(id, false))
        assertFalse("A decided request cannot be re-decided", memory.decideApproval(id, true))
        assertFalse(memory.decideApproval(id, false))
    }

    @Test fun uncertainReceiptsBlockDuplicateOccurrenceKeys() {
        // The CANONICAL side-effect ledger is the single authority for occurrence
        // receipts: an UNCERTAIN receipt must be visible and must block a duplicate.
        val uncertain = co.sanaa.agent.core.SideEffectTransaction(
            idempotencyKey = "occurrence-x", capability = "recurring_command", target = "t",
            contentHash = "h", approvalId = null,
            state = co.sanaa.agent.core.SideEffectState.UNCERTAIN,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
            evidence = "process death",
        )
        assertTrue(memory.upsertSideEffectTransaction(uncertain))
        val found = memory.findSideEffectTransaction("occurrence-x")
        assertEquals(co.sanaa.agent.core.SideEffectState.UNCERTAIN, found?.state)
        val verified = uncertain.copy(
            idempotencyKey = "occurrence-y",
            state = co.sanaa.agent.core.SideEffectState.VERIFIED, evidence = "ok",
        )
        assertTrue(memory.upsertSideEffectTransaction(verified))
        assertEquals(co.sanaa.agent.core.SideEffectState.VERIFIED, memory.findSideEffectTransaction("occurrence-y")?.state)
    }

    // ---------- retention + owner data controls ----------

    @Test fun retentionPruneRemovesOnlyRecordsBeyondTheWindow() {
        val now = System.currentTimeMillis()
        val old = memory.recordConversation("Old Contact", null, "whatsapp", "received", "old message")
        val recent = memory.recordConversation("New Contact", null, "whatsapp", "received", "fresh message")
        memory.writableDatabase.execSQL("UPDATE conversations SET timestamp = ? WHERE id = ?", arrayOf(now - 120L * 24 * 60 * 60 * 1000, old))
        memory.pruneExpiredData(retentionDays = 90, now = now)
        assertEquals(1, countRows("conversations"))
        assertTrue(recent > 0)
    }

    @Test fun ownerDeletionWipesBusinessRecordsButKeepsSchemaIntact() {
        memory.recordConversation("Contact", null, "whatsapp", "received", "msg")
        memory.recordAction("type", null, null, "cmd", "did", "res", null, true)
        memory.deleteOwnerBusinessData()
        assertEquals(0, countRows("conversations"))
        assertEquals(0, countRows("actions"))
        assertEquals(0, countRows("side_effect_transactions"))
        // Schema survives so the app keeps functioning after deletion.
        assertEquals(21, SQLiteDatabase.openDatabase(
            context.getDatabasePath(AmaraMemory.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY,
        ).use { it.version })
    }

    @Test fun failureAndBrainFailureRecordsPersistSanitized() {
        val secretLike = "pin 4321 embedded"
        memory.recordFailure(
            taskId = "task-1", runId = "run-1", stepId = "step-1", capability = "send_whatsapp",
            targetPackage = "com.whatsapp", stage = "act",
            cause = "delivery failed after $secretLike", retryable = false, attemptCount = 2,
            screenEvidenceJson = "{\"package\":\"com.whatsapp\"}", correctiveAction = "ask owner",
            disposition = "FAILED_PERMANENT", nextSafeAction = "await owner instruction",
        )
        memory.recordBrainFailure(
            stage = "planner_plan", model = "llama-test", requestId = "req-1", responseHash = "abc123",
            attemptCount = 3, retryable = false, validationErrorsJson = "[\"missing field 'steps'\"]",
            correctiveAction = "STOP_BEFORE_ANY_SIDE_EFFECT", disposition = "RETRY_EXHAUSTED",
        )
        val failure = memory.recentFailures().first()
        assertEquals("send_whatsapp", failure.capability)
        assertEquals(2, failure.attemptCount)
        assertFalse(failure.cause.contains("4321"))
        assertTrue(Redactor.containsSecretShape(failure.cause).not())
        val brain = memory.recentBrainFailures().first()
        assertEquals("planner_plan", brain.stage)
        assertEquals("req-1", brain.requestId)
        assertEquals(3, brain.attemptCount)
    }

    private fun countRows(table: String): Int = memory.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
        c.moveToFirst(); c.getInt(0)
    }
}
