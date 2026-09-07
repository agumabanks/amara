package co.sanaa.agent.core

import android.content.ContentValues
import co.sanaa.agent.core.commerce.CostEntry
import co.sanaa.agent.core.commerce.SaleEvidenceRecord
import co.sanaa.agent.core.work.RunPhase
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class MemoryChatMessage(val direction: String, val text: String, val timestamp: Long)
data class MemoryAction(val whatAmaraDid: String, val result: String, val timestamp: Long, val success: Boolean)
data class FollowUpCandidate(val contact: String, val lastMessage: String, val timestamp: Long)
data class TaskJournalSummary(
    val id: Long,
    val command: String,
    val status: String,
    val phase: String,
    val result: String,
    val updatedAt: Long,
)
data class TaskJournalStepSummary(val action: String, val reason: String, val success: Boolean, val result: String)
data class TaskJournalReceipt(
    val success: Boolean,
    val status: String,
    val message: String,
    val observation: String,
    val analysis: String,
    val steps: List<TaskJournalStepSummary>,
)
data class ApprovalRequest(
    val id: Long,
    val capability: String,
    val target: String,
    val description: String,
    val beforeJson: String,
    val afterJson: String,
    val risk: String,
    val status: String,
    val expiresAt: Long,
)
data class RecurringTaskRecord(
    val id: Long,
    val instruction: String,
    val taskText: String,
    val rule: String,
    val scheduleJson: String,
    val nextRunAt: Long,
    val enabled: Boolean,
)
data class BusinessFindingRecord(
    val id: Long,
    val sourceApp: String,
    val subject: String,
    val issue: String,
    val severity: String,
    val confidence: Double,
    val evidence: String,
    val recommendation: String,
    val status: String,
)

/** Durable failure record: what failed, where, why, and what safe step comes next. */
data class FailureRecord(
    val id: Long,
    val createdAt: Long,
    val taskId: String,
    val runId: String,
    val stepId: String,
    val capability: String,
    val targetPackage: String,
    val stage: String,
    val cause: String,
    val retryable: Boolean,
    val attemptCount: Int,
    val screenEvidenceJson: String,
    val correctiveAction: String,
    val disposition: String,
    val nextSafeAction: String,
)

/** Redacted model-gateway failure record (no raw provider content). */
data class BrainFailureRecord(
    val id: Long,
    val createdAt: Long,
    val stage: String,
    val model: String,
    val requestId: String,
    val responseHash: String,
    val attemptCount: Int,
    val retryable: Boolean,
    val validationErrorsJson: String,
    val correctiveAction: String,
    val disposition: String,
    val correlationId: String = "",
    val terminalOutcome: String = "",
    val ownerExplanation: String = "",
)

/** Private, device-only memory stored at databases/amara_memory.db. */
class AmaraMemory(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 21) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE actions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                type TEXT NOT NULL,
                target_contact TEXT,
                target_app TEXT,
                command_given TEXT NOT NULL,
                what_amara_did TEXT NOT NULL,
                result TEXT NOT NULL,
                groq_response TEXT,
                success INTEGER NOT NULL DEFAULT 0
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                contact_name TEXT,
                contact_number TEXT,
                platform TEXT NOT NULL,
                direction TEXT NOT NULL CHECK(direction IN ('sent','received')),
                message_text TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                replied INTEGER NOT NULL DEFAULT 0,
                reply_text TEXT
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE products_seen (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_app TEXT NOT NULL,
                product_name TEXT NOT NULL,
                price_ugx INTEGER,
                description TEXT,
                listing_quality_score REAL,
                last_seen_timestamp INTEGER NOT NULL,
                improvements_made TEXT
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE owner_instructions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                instruction_text TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('pending','done','recurring')),
                recurrence_rule TEXT
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX conversations_platform_time ON conversations(platform, timestamp)")
        db.execSQL("CREATE INDEX actions_time ON actions(timestamp)")
        createLearningTable(db)
        createTaskJournalTables(db)
        createOperationalTables(db)
        createTransactionTables(db)
        createWorkflowAndArtifactTables(db)
        createBudgetTables(db)
        createBudgetReservationTable(db)
        createCommerceTables(db)
        createRevenueTables(db)
        createConsentLedger(db)
        createExperimentSampleTable(db)
        createFailureTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createLearningTable(db)
        if (oldVersion < 3) createTaskJournalTables(db)
        if (oldVersion == 3) {
            db.execSQL("ALTER TABLE task_journal ADD COLUMN observation TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE task_journal ADD COLUMN analysis TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 5) createOperationalTables(db)
        if (oldVersion < 6) createTransactionTables(db)
        if (oldVersion < 7) createWorkflowAndArtifactTables(db)
        if (oldVersion < 8) createBudgetTables(db)
        if (oldVersion < 9) createCommerceTables(db)
        if (oldVersion < 10) {
            upgradeApprovalsToV10(db)
            createRevenueTables(db)
            createBudgetReservationTable(db)
        }
        if (oldVersion < 11) {
            createRevenueTables(db)
            createConsentLedger(db)
            migrateLegacyCommerceToCanonical(db)
        }
        if (oldVersion < 12) createExperimentSampleTable(db)
        if (oldVersion < 13) createFailureTables(db)
        if (oldVersion < 14) upgradeBrainFailuresToV14(db)
        if (oldVersion < 15) createRevenueObservationTables(db)
    }

    /**
     * v11 consolidation migration: the legacy v9 commerce system (commercial_events /
     * commercial_opportunities / commercial_targets) is migrated into the canonical
     * revenue tables. Every insert is INSERT OR IGNORE keyed on the canonical unique
     * keys, so an interrupted or repeated migration can never duplicate records, and
     * canonical rows recorded after the migration window always win over legacy rows.
     * The legacy tables are kept read-only for audit history; no production writer
     * touches them anymore.
     */
    private fun migrateLegacyCommerceToCanonical(db: SQLiteDatabase) {
        fun tableExists(name: String): Boolean = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name),
        ).use { it.moveToFirst() }

        if (tableExists("commercial_events")) {
            db.rawQuery(
                """SELECT unique_key, kind, channel, contact_key, product_ref, amount_ugx,
                          evidence_kind, evidence_ref, detail_json, occurred_at, recorded_at
                   FROM commercial_events""",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val uniqueKey = c.getString(0)
                    val kind = c.getString(1)
                    val channel = c.getString(2)
                    val contactKey = c.getString(3)
                    val productRef = c.getString(4)
                    val amountUgx = if (c.isNull(5)) null else c.getLong(5)
                    val evidenceKind = c.getString(6)
                    val evidenceRef = c.getString(7)
                    val detailJson = c.getString(8) ?: "{}"
                    val occurredAt = c.getLong(9)
                    val recordedAt = c.getLong(10)
                    when (kind) {
                        "QUALIFIED_INQUIRY" -> runCatching {
                            val interactionId = org.json.JSONObject(detailJson).optString("interactionId").ifBlank { uniqueKey }
                            db.insertWithOnConflict(
                                "revenue_inquiries", null,
                                ContentValues().apply {
                                    put("unique_key", uniqueKey); put("channel", channel.take(80))
                                    put("contact_key", contactKey.take(200)); put("product_ref", productRef.take(300))
                                    put("interaction_id", interactionId.take(200)); put("evidence_kind", evidenceKind.take(80))
                                    put("evidence_ref", evidenceRef.take(500)); put("confidence", 0.8)
                                    put("occurred_at", occurredAt); put("recorded_at", recordedAt)
                                },
                                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
                            )
                        }.getOrNull()
                        "SALE" -> runCatching {
                            // Only completed-sale evidence kinds migrate; draft orders and
                            // promises were refused at the legacy boundary and never stored.
                            val legal = SaleEvidenceRecord.SaleEvidenceKind.entries.map { it.name }
                            if (evidenceKind in legal && amountUgx != null && amountUgx > 0) {
                                db.insertWithOnConflict(
                                    "revenue_sales", null,
                                    ContentValues().apply {
                                        put("unique_key", uniqueKey); put("evidence_kind", evidenceKind)
                                        put("sale_ref", evidenceRef.take(300)); put("contact_key", contactKey.take(200))
                                        put("product_ref", productRef.take(300)); put("amount_ugx", amountUgx)
                                        put("state", SaleEvidenceRecord.SaleState.ACTIVE.name)
                                        put("correction_reason", ""); put("occurred_at", occurredAt)
                                        put("recorded_at", recordedAt)
                                    },
                                    android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
                                )
                            }
                        }.getOrNull()
                        "COST" -> runCatching {
                            val components = CostEntry.CostComponent.entries.map { it.name }
                            if (evidenceKind in components) {
                                db.insertWithOnConflict(
                                    "revenue_costs", null,
                                    ContentValues().apply {
                                        put("unique_key", uniqueKey); put("component", evidenceKind)
                                        put("product_ref", productRef.take(300)); put("ref", evidenceRef.take(300))
                                        put("amount_ugx", amountUgx ?: 0L); put("occurred_at", occurredAt)
                                        put("recorded_at", recordedAt)
                                    },
                                    android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
                                )
                            }
                        }.getOrNull()
                        else -> Unit // FUNNEL_ADVANCE / EXPERIMENT rows stay in the legacy table
                    }
                }
            }
        }

        if (tableExists("commercial_opportunities")) {
            val stageMap = mapOf(
                "IDENTIFIED" to "OBSERVED",
                "QUALIFIED" to "QUALIFIED_INQUIRY",
                "ENGAGED" to "ENGAGED",
                "OFFER_SENT" to "ORDER_INTENT",
                "NEGOTIATING" to "ORDER_INTENT",
                "ORDER_PLACED" to "ORDER_CREATED",
                "PAID" to "SALE_VERIFIED",
                "DELIVERED" to "SALE_VERIFIED",
            )
            db.rawQuery(
                "SELECT id, contact_key, product_ref, stage, updated_at FROM commercial_opportunities", null,
            ).use { c ->
                while (c.moveToNext()) {
                    runCatching {
                        val mappedStage = stageMap[c.getString(3)] ?: return@runCatching
                        db.insertWithOnConflict(
                            "revenue_opportunities", null,
                            ContentValues().apply {
                                put("id", c.getString(0)); put("contact_key", c.getString(1).take(200))
                                put("product_ref", c.getString(2).take(300)); put("stage", mappedStage)
                                put("dedupe_key", "legacy:" + c.getString(0))
                                put("confidence", 0.5); put("created_at", c.getLong(4)); put("updated_at", c.getLong(4))
                            },
                            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
                        )
                    }.getOrNull()
                }
            }
        }

        // Legacy owner targets become the seed of the canonical owner policy document.
        // Allow-lists stay empty (fail closed): preserved targets never widen authority.
        if (tableExists("commercial_targets")) {
            db.rawQuery("SELECT config_json FROM commercial_targets WHERE id = 1", null).use { c ->
                if (c.moveToFirst()) runCatching {
                    val legacy = org.json.JSONObject(c.getString(0))
                    val policyExists = db.rawQuery(
                        "SELECT 1 FROM commercial_policy WHERE id = 1", null,
                    ).use { it.moveToFirst() }
                    if (!policyExists) {
                        co.sanaa.agent.core.commerce.CommercialPolicy(
                            dailyQualifiedInquiryTarget = legacy.optInt("minVerifiedInquiriesPerDay", 1),
                            weeklyVerifiedSaleTarget = maxOf(3, legacy.optInt("weeklySalesContributions", 3)),
                            monthlyProfitFloorUgx = legacy.optLong("monthlyMinProfitUgx", 0L),
                        ).let { policy ->
                            db.insertWithOnConflict(
                                "commercial_policy", null,
                                ContentValues().apply {
                                    put("id", 1); put("config_json", policy.toJson().take(8_000)); put("updated_at", System.currentTimeMillis())
                                },
                                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
                            )
                        }
                    }
                }.getOrNull()
            }
        }
    }

    /** Durable consent/contact-eligibility ledger (append-only grants + revocations). */
    private fun createConsentLedger(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS contact_consent (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                contact_key TEXT NOT NULL,
                source TEXT NOT NULL,
                scope TEXT NOT NULL,
                granted_at INTEGER NOT NULL,
                expires_at INTEGER,
                revoked_at INTEGER,
                revoke_reason TEXT NOT NULL DEFAULT '',
                evidence_ref TEXT NOT NULL,
                permitted_products TEXT NOT NULL DEFAULT '',
                permitted_channels TEXT NOT NULL DEFAULT ''
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS contact_consent_contact ON contact_consent(contact_key)")
    }

    /** v12: durable per-experiment evidence samples (guardrail measurements, conversions). */
    private fun createExperimentSampleTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS experiment_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                experiment_id TEXT NOT NULL,
                metric TEXT NOT NULL,
                value REAL NOT NULL,
                evidence_ref TEXT NOT NULL,
                recorded_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS experiment_samples_exp ON experiment_samples(experiment_id, metric)")
    }

    private fun createFailureTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS failure_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                task_id TEXT NOT NULL DEFAULT '',
                run_id TEXT NOT NULL DEFAULT '',
                step_id TEXT NOT NULL DEFAULT '',
                capability TEXT NOT NULL DEFAULT '',
                target_package TEXT NOT NULL DEFAULT '',
                stage TEXT NOT NULL,
                cause TEXT NOT NULL,
                retryable INTEGER NOT NULL DEFAULT 0,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                screen_evidence_json TEXT NOT NULL DEFAULT '{}',
                corrective_action TEXT NOT NULL DEFAULT '',
                disposition TEXT NOT NULL,
                next_safe_action TEXT NOT NULL DEFAULT ''
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS failure_records_time ON failure_records(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS failure_records_cap ON failure_records(capability, stage)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS brain_failures (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                stage TEXT NOT NULL,
                model TEXT NOT NULL,
                request_id TEXT NOT NULL DEFAULT '',
                response_hash TEXT NOT NULL DEFAULT '',
                attempt_count INTEGER NOT NULL DEFAULT 0,
                retryable INTEGER NOT NULL DEFAULT 0,
                validation_errors_json TEXT NOT NULL DEFAULT '[]',
                corrective_action TEXT NOT NULL DEFAULT '',
                disposition TEXT NOT NULL,
                correlation_id TEXT NOT NULL DEFAULT '',
                terminal_outcome TEXT NOT NULL DEFAULT '',
                owner_explanation TEXT NOT NULL DEFAULT ''
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS brain_failures_time ON brain_failures(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS brain_failures_corr ON brain_failures(correlation_id)")
    }

    /** v14: brain_failures gains correlation/terminal-owner columns (contract §2). */
    private fun upgradeBrainFailuresToV14(db: SQLiteDatabase) {
        val existing = mutableSetOf<String>()
        val cursor = db.rawQuery("PRAGMA table_info(brain_failures)", null)
        cursor.use { while (it.moveToNext()) existing.add(it.getString(1)) }
        if (existing.isEmpty()) return // table not created yet; onCreate covers it
        if ("correlation_id" !in existing) db.execSQL("ALTER TABLE brain_failures ADD COLUMN correlation_id TEXT NOT NULL DEFAULT ''")
        if ("terminal_outcome" !in existing) db.execSQL("ALTER TABLE brain_failures ADD COLUMN terminal_outcome TEXT NOT NULL DEFAULT ''")
        if ("owner_explanation" !in existing) db.execSQL("ALTER TABLE brain_failures ADD COLUMN owner_explanation TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS brain_failures_corr ON brain_failures(correlation_id)")
    }

    private fun createBudgetReservationTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS budget_reservations (
                id TEXT PRIMARY KEY,
                contract_id TEXT NOT NULL,
                amount_ugx INTEGER NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('RESERVED','COMMITTED','RELEASED','FAILED_NONBILLABLE','REFUNDED')),
                reason TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS budget_reservations_contract ON budget_reservations(contract_id, state)")
    }

    /** v10: exact-binding columns for workflow approvals (content hash, contract, count). */
    private fun upgradeApprovalsToV10(db: SQLiteDatabase) {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(approval_requests)", null).use { c ->
            while (c.moveToNext()) columns += c.getString(1)
        }
        if (columns.isEmpty()) return // table not created yet; onCreate covers it
        if ("content_hash" !in columns) db.execSQL("ALTER TABLE approval_requests ADD COLUMN content_hash TEXT NOT NULL DEFAULT ''")
        if ("contract_id" !in columns) db.execSQL("ALTER TABLE approval_requests ADD COLUMN contract_id TEXT NOT NULL DEFAULT ''")
        if ("executions_allowed" !in columns) db.execSQL("ALTER TABLE approval_requests ADD COLUMN executions_allowed INTEGER NOT NULL DEFAULT 1")
        if ("executions_used" !in columns) db.execSQL("ALTER TABLE approval_requests ADD COLUMN executions_used INTEGER NOT NULL DEFAULT 0")
    }

    private fun createLearningTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS selector_learning (
                app_package TEXT NOT NULL,
                action_name TEXT NOT NULL,
                selector TEXT NOT NULL,
                successes INTEGER NOT NULL DEFAULT 0,
                failures INTEGER NOT NULL DEFAULT 0,
                last_result TEXT,
                last_used_timestamp INTEGER NOT NULL,
                PRIMARY KEY(app_package, action_name, selector)
            )""".trimIndent(),
        )
    }

    private fun createTaskJournalTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS task_journal (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                instruction_id INTEGER,
                command TEXT NOT NULL,
                status TEXT NOT NULL,
                phase TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                replan_count INTEGER NOT NULL DEFAULT 0,
                observation TEXT NOT NULL DEFAULT '',
                analysis TEXT NOT NULL DEFAULT '',
                final_result TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(instruction_id) REFERENCES owner_instructions(id)
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS task_journal_steps (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                step_index INTEGER NOT NULL,
                attempt INTEGER NOT NULL,
                action TEXT NOT NULL,
                reason TEXT NOT NULL,
                pre_package TEXT NOT NULL,
                pre_signature TEXT NOT NULL,
                post_package TEXT NOT NULL,
                post_signature TEXT NOT NULL,
                status TEXT NOT NULL,
                result TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(task_id) REFERENCES task_journal(id) ON DELETE CASCADE
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS task_journal_updated ON task_journal(updated_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS task_journal_steps_task ON task_journal_steps(task_id, step_index, attempt)")
    }

    private fun createOperationalTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS approval_requests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                capability TEXT NOT NULL,
                target TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL,
                before_json TEXT NOT NULL DEFAULT '{}',
                after_json TEXT NOT NULL DEFAULT '{}',
                risk TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('pending','approved','rejected','expired','consumed')),
                expires_at INTEGER NOT NULL,
                decided_at INTEGER,
                content_hash TEXT NOT NULL DEFAULT '',
                contract_id TEXT NOT NULL DEFAULT '',
                executions_allowed INTEGER NOT NULL DEFAULT 1,
                executions_used INTEGER NOT NULL DEFAULT 0
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS business_findings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                source_app TEXT NOT NULL,
                subject TEXT NOT NULL,
                issue TEXT NOT NULL,
                severity TEXT NOT NULL,
                confidence REAL NOT NULL,
                evidence TEXT NOT NULL,
                recommendation TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('open','proposed','approved','fixed','dismissed'))
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS recurring_tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                instruction TEXT NOT NULL,
                task_text TEXT NOT NULL,
                rule TEXT NOT NULL,
                schedule_json TEXT NOT NULL,
                next_run_at INTEGER NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                last_run_at INTEGER,
                last_result TEXT
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS side_effect_receipts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                idempotency_key TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL,
                capability TEXT NOT NULL,
                target TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('started','verified','failed','uncertain')),
                evidence TEXT NOT NULL DEFAULT ''
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS approvals_status_expiry ON approval_requests(status, expires_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS findings_status_created ON business_findings(status, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS recurring_enabled_next ON recurring_tasks(enabled, next_run_at)")
    }

    private fun createTransactionTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS side_effect_transactions (
                idempotency_key TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                capability TEXT NOT NULL,
                target TEXT NOT NULL DEFAULT '',
                content_hash TEXT NOT NULL DEFAULT '',
                approval_id INTEGER,
                state TEXT NOT NULL CHECK(state IN (
                    'PROPOSED','AWAITING_APPROVAL','APPROVED','CLAIMED','ACTING','VERIFICATION_PENDING',
                    'VERIFIED','FAILED','UNCERTAIN','CANCELLED','EXPIRED')),
                evidence TEXT NOT NULL DEFAULT ''
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS transactions_state_updated ON side_effect_transactions(state, updated_at)")
    }

    private fun createWorkflowAndArtifactTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS workflow_runs (
                id TEXT PRIMARY KEY,
                contract_fingerprint TEXT NOT NULL,
                step_index INTEGER NOT NULL DEFAULT 0,
                phase TEXT NOT NULL CHECK(phase IN (
                    'PENDING','RUNNING','CHECKPOINTED','AWAITING_DECISION','COMPLETED','FAILED','CANCELLED')),
                lease_owner TEXT,
                lease_expires_at INTEGER NOT NULL DEFAULT 0,
                decision_question TEXT NOT NULL DEFAULT '',
                checkpoint_json TEXT NOT NULL DEFAULT '{}',
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS artifact_revisions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                artifact_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                author TEXT NOT NULL DEFAULT 'amara',
                spec_json TEXT NOT NULL,
                parent_revision INTEGER,
                content_hash TEXT NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS revisions_artifact ON artifact_revisions(artifact_id, id)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS connector_revocations (
                connector_id TEXT PRIMARY KEY,
                revoked_at INTEGER NOT NULL
            )""".trimIndent(),
        )
    }

    // ---------- durable budget metering (Phase B) ----------

    private fun createBudgetTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS budget_spend (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                contract_id TEXT NOT NULL,
                amount_ugx INTEGER NOT NULL,
                reason TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS budget_spend_contract ON budget_spend(contract_id, id)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS work_enforcement (
                contract_id TEXT PRIMARY KEY,
                state TEXT NOT NULL CHECK(state IN ('WITHIN_BUDGET','BUDGET_EXCEEDED','OVERDUE','DEFERRED')),
                reason TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
    }

    /** Persists one accepted spend entry; rejected (over-budget) attempts never persist. */
    @Synchronized
    fun insertBudgetSpendEntry(contractId: String, amountUgx: Long, reason: String, createdAtMs: Long): Long =
        writableDatabase.insertOrThrow("budget_spend", null, ContentValues().apply {
            put("contract_id", contractId)
            put("amount_ugx", amountUgx)
            put("reason", reason.take(500))
            put("created_at", createdAtMs)
        })

    @Synchronized
    fun budgetSpendTotal(contractId: String): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(amount_ugx), 0) FROM budget_spend WHERE contract_id = ?", arrayOf(contractId),
    ).use { it.moveToFirst(); it.getLong(0) }

    @Synchronized
    fun budgetSpendEntries(contractId: String): List<BudgetSpendRow> = readableDatabase.query(
        "budget_spend", arrayOf("id", "contract_id", "amount_ugx", "reason", "created_at"),
        "contract_id = ?", arrayOf(contractId), null, null, "id ASC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(BudgetSpendRow(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getLong(4)))
        }
    }

    @Synchronized
    fun allBudgetSpendEntries(limit: Int = 500): List<BudgetSpendRow> = readableDatabase.query(
        "budget_spend", arrayOf("id", "contract_id", "amount_ugx", "reason", "created_at"),
        null, null, null, null, "id ASC", limit.coerceIn(1, 2_000).toString(),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(BudgetSpendRow(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getLong(4)))
        }
    }

    @Synchronized
    fun recordEnforcementState(contractId: String, state: String, reason: String, nowMs: Long) {
        writableDatabase.execSQL(
            """INSERT INTO work_enforcement(contract_id, state, reason, updated_at) VALUES(?,?,?,?)
               ON CONFLICT(contract_id) DO UPDATE SET state=excluded.state, reason=excluded.reason, updated_at=excluded.updated_at""",
            arrayOf<Any>(contractId, state, reason.take(1_000), nowMs),
        )
    }

    @Synchronized
    fun enforcementState(contractId: String): EnforcementRow? = readableDatabase.query(
        "work_enforcement", arrayOf("contract_id", "state", "reason", "updated_at"),
        "contract_id = ?", arrayOf(contractId), null, null, null,
    ).use { c -> if (!c.moveToFirst()) null else EnforcementRow(c.getString(0), c.getString(1), c.getString(2), c.getLong(3)) }

    data class BudgetSpendRow(val id: Long, val contractId: String, val amountUgx: Long, val reason: String, val createdAtMs: Long)
    data class EnforcementRow(val contractId: String, val state: String, val reason: String, val updatedAtMs: Long)

    // ---------- spend reservations (reserved → committed/released/failed/refunded) ----------

    /**
     * Explicit reservation lifecycle for workflow spend. A reservation is charged
     * against the ceiling while RESERVED or COMMITTED only; RELEASED, FAILED_NONBILLABLE
     * and REFUNDED rows stop counting. This replaces charge-before-attempt: a catalog
     * rejection or proven non-effect releases the reservation instead of keeping cost.
     */
    @Synchronized
    fun insertBudgetReservation(id: String, contractId: String, amountUgx: Long, reason: String, nowMs: Long): Boolean {
        require(amountUgx >= 0) { "Reservations cannot be negative" }
        return runCatching {
            writableDatabase.insertOrThrow("budget_reservations", null, ContentValues().apply {
                put("id", id); put("contract_id", contractId); put("amount_ugx", amountUgx)
                put("state", "RESERVED"); put("reason", reason.take(500))
                put("created_at", nowMs); put("updated_at", nowMs)
            })
        }.isSuccess
    }

    /** Atomic state move; refuses unknown ids, double commits, and illegal reversals. */
    @Synchronized
    fun transitionBudgetReservation(id: String, toState: String, nowMs: Long): Boolean {
        require(toState in setOf("RESERVED", "COMMITTED", "RELEASED", "FAILED_NONBILLABLE", "REFUNDED")) {
            "Unknown reservation state $toState"
        }
        val current = readableDatabase.query(
            "budget_reservations", arrayOf("state"), "id = ?", arrayOf(id), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.getString(0) else return false }
        val legal = when (toState) {
            "COMMITTED" -> current == "RESERVED"
            "RELEASED" -> current == "RESERVED"
            "FAILED_NONBILLABLE" -> current == "RESERVED"
            "REFUNDED" -> current == "COMMITTED"
            else -> false
        }
        if (!legal) return false
        writableDatabase.execSQL(
            "UPDATE budget_reservations SET state = ?, updated_at = ? WHERE id = ? AND state = ?",
            arrayOf<Any>(toState, nowMs, id, current),
        )
        return true
    }

    @Synchronized
    fun budgetReservation(id: String): BudgetReservationRow? = readableDatabase.query(
        "budget_reservations",
        arrayOf("id", "contract_id", "amount_ugx", "state", "reason", "created_at", "updated_at"),
        "id = ?", arrayOf(id), null, null, null,
    ).use { c ->
        if (!c.moveToFirst()) null else BudgetReservationRow(
            c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4), c.getLong(5), c.getLong(6),
        )
    }

    /** Amount counted against the ceiling right now: RESERVED + COMMITTED only. */
    @Synchronized
    fun budgetReservationOutstanding(contractId: String): Long = readableDatabase.rawQuery(
        """SELECT COALESCE(SUM(amount_ugx), 0) FROM budget_reservations
           WHERE contract_id = ? AND state IN ('RESERVED','COMMITTED')""",
        arrayOf(contractId),
    ).use { it.moveToFirst(); it.getLong(0) }

    /** Committed-only total: the recognized-cost figure used for profitability honesty. */
    @Synchronized
    fun budgetReservationCommitted(contractId: String): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(amount_ugx), 0) FROM budget_reservations WHERE contract_id = ? AND state = 'COMMITTED'",
        arrayOf(contractId),
    ).use { it.moveToFirst(); it.getLong(0) }

    @Synchronized
    fun budgetReservations(contractId: String): List<BudgetReservationRow> = readableDatabase.query(
        "budget_reservations",
        arrayOf("id", "contract_id", "amount_ugx", "state", "reason", "created_at", "updated_at"),
        "contract_id = ?", arrayOf(contractId), null, null, "created_at ASC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(BudgetReservationRow(
                c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4), c.getLong(5), c.getLong(6),
            ))
        }
    }

    data class BudgetReservationRow(
        val id: String,
        val contractId: String,
        val amountUgx: Long,
        val state: String,
        val reason: String,
        val createdAtMs: Long,
        val updatedAtMs: Long,
    )

    // ---------- exact-bound workflow approvals (atomic consume at the pre-act boundary) ----------

    /**
     * Creates a durable approval request bound to capability + exact target + normalized
     * content hash + structured inputs + owning contract/workflow, with an expiry and an
     * allowed execution count. Duplicate pending requests for the identical binding dedupe.
     */
    @Synchronized
    fun createWorkflowApprovalRequest(
        capability: String,
        target: String,
        description: String,
        contentHash: String,
        contractId: String,
        inputsJson: String,
        risk: ActionRisk,
        expiresAt: Long,
        executionsAllowed: Int = 1,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val existing = readableDatabase.query(
            "approval_requests", arrayOf("id"),
            "capability = ? AND target = ? AND content_hash = ? AND contract_id = ? AND status IN ('pending','approved') AND expires_at > ?",
            arrayOf(capability, target, contentHash, contractId, nowMs.toString()), null, null, "created_at DESC", "1",
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (existing != null) return existing
        return writableDatabase.insertOrThrow("approval_requests", null, ContentValues().apply {
            put("created_at", nowMs)
            put("capability", capability)
            put("target", target)
            put("description", description.take(1_500))
            put("before_json", "{}")
            put("after_json", inputsJson.take(8_000))
            put("risk", risk.name)
            put("status", "pending")
            put("expires_at", expiresAt)
            put("content_hash", contentHash)
            put("contract_id", contractId)
            put("executions_allowed", executionsAllowed.coerceIn(1, 10))
            put("executions_used", 0)
        })
    }

    /** Non-consuming validity probe: approved, unexpired, count not exhausted, exact binding. */
    @Synchronized
    fun workflowApprovalStillValid(
        id: Long, capability: String, target: String, contentHash: String, contractId: String, nowMs: Long,
    ): Boolean = readableDatabase.query(
        "approval_requests", arrayOf("id"),
        """id = ? AND capability = ? AND target = ? AND content_hash = ? AND contract_id = ?
           AND status = 'approved' AND expires_at > ? AND executions_used < executions_allowed""",
        arrayOf(id.toString(), capability, target, contentHash, contractId, nowMs.toString()),
        null, null, null, "1",
    ).use { it.moveToFirst() }

    /**
     * ATOMIC consume-at-the-final-boundary: one UPDATE that checks every binding dimension
     * (capability, target, normalized-content hash, contract, expiry, remaining count) and
     * increments the use counter in the same statement. Returns true exactly once per
     * allowed execution — concurrent callers cannot both win.
     */
    @Synchronized
    fun consumeWorkflowApprovalForExecution(
        id: Long, capability: String, target: String, contentHash: String, contractId: String, nowMs: Long,
    ): Boolean {
        val updated = writableDatabase.execSQLWithCount(
            """UPDATE approval_requests SET executions_used = executions_used + 1,
                   status = CASE WHEN executions_used + 1 >= executions_allowed THEN 'consumed' ELSE status END
               WHERE id = ? AND capability = ? AND target = ? AND content_hash = ? AND contract_id = ?
                 AND status = 'approved' AND expires_at > ? AND executions_used < executions_allowed""",
            arrayOf<Any>(id.toString(), capability, target, contentHash, contractId, nowMs.toString()),
        )
        return updated > 0
    }

    @Synchronized
    fun findWorkflowApproval(capability: String, target: String, contentHash: String, contractId: String, nowMs: Long): ApprovalRequest? =
        readableDatabase.query(
            "approval_requests",
            arrayOf("id", "capability", "target", "description", "before_json", "after_json", "risk", "status", "expires_at"),
            "capability = ? AND target = ? AND content_hash = ? AND contract_id = ? AND status = 'approved' AND expires_at > ? AND executions_used < executions_allowed",
            arrayOf(capability, target, contentHash, contractId, nowMs.toString()),
            null, null, "decided_at DESC", "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ApprovalRequest(
                cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
                cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getLong(8),
            )
        }

    // ---------- durable commercial ledger (Revenue Operator charter) ----------

    private fun createCommerceTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS commercial_targets (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                config_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS commercial_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                unique_key TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL CHECK(kind IN ('QUALIFIED_INQUIRY','FUNNEL_ADVANCE','SALE','COST','EXPERIMENT')),
                channel TEXT NOT NULL DEFAULT '',
                contact_key TEXT NOT NULL DEFAULT '',
                product_ref TEXT NOT NULL DEFAULT '',
                amount_ugx INTEGER,
                evidence_kind TEXT NOT NULL,
                evidence_ref TEXT NOT NULL,
                attribution TEXT NOT NULL DEFAULT '{}',
                detail_json TEXT NOT NULL DEFAULT '{}',
                occurred_at INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS commercial_opportunities (
                id TEXT PRIMARY KEY,
                contact_key TEXT NOT NULL,
                product_ref TEXT NOT NULL,
                stage TEXT NOT NULL CHECK(stage IN (
                    'IDENTIFIED','QUALIFIED','ENGAGED','OFFER_SENT','NEGOTIATING','ORDER_PLACED','PAID','DELIVERED')),
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS commercial_events_time ON commercial_events(occurred_at)")
    }

    @Synchronized
    fun setCommercialTargetsJson(json: String, nowMs: Long) {
        writableDatabase.execSQL(
            """INSERT INTO commercial_targets(id, config_json, updated_at) VALUES(1, ?, ?)
               ON CONFLICT(id) DO UPDATE SET config_json=excluded.config_json, updated_at=excluded.updated_at""".trimIndent(),
            arrayOf<Any>(json.take(4_000), nowMs),
        )
    }

    @Synchronized
    fun commercialTargetsJson(): String? = readableDatabase.rawQuery(
        "SELECT config_json FROM commercial_targets WHERE id = 1", null,
    ).use { if (!it.moveToFirst()) null else it.getString(0) }

    /**
     * Revenue Operator domain store (v10). Typed durable records for goals, opportunities
     * with full funnel-transition history, inquiries, sales with correction state, cost
     * entries, per-sale attribution, experiments, commercial actions, daily plans/briefs,
     * suppression list, and the owner policy document.
     */
    private fun createRevenueTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS revenue_goals (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL CHECK(kind IN ('DAILY_QUALIFIED_INQUIRY','WEEKLY_VERIFIED_SALES','MONTHLY_PROFIT_TO_COST')),
                metric TEXT NOT NULL,
                target_value REAL NOT NULL,
                period_start_ms INTEGER,
                active INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS revenue_opportunities (
                id TEXT PRIMARY KEY,
                contact_key TEXT NOT NULL,
                product_ref TEXT NOT NULL,
                stage TEXT NOT NULL CHECK(stage IN (
                    'OBSERVED','CONTACTABLE','ENGAGED','QUALIFIED_INQUIRY','ORDER_INTENT',
                    'ORDER_CREATED','SALE_VERIFIED','LOST','DISQUALIFIED')),
                dedupe_key TEXT NOT NULL UNIQUE,
                confidence REAL NOT NULL DEFAULT 0.5,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS revenue_opportunities_stage ON revenue_opportunities(stage, updated_at)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS revenue_funnel_transitions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                opportunity_id TEXT NOT NULL,
                from_stage TEXT NOT NULL,
                to_stage TEXT NOT NULL,
                occurred_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                evidence_kind TEXT NOT NULL,
                evidence_ref TEXT NOT NULL,
                confidence REAL NOT NULL,
                reason TEXT NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS revenue_transitions_opp ON revenue_funnel_transitions(opportunity_id, occurred_at)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS revenue_inquiries (
                unique_key TEXT PRIMARY KEY,
                channel TEXT NOT NULL,
                contact_key TEXT NOT NULL,
                product_ref TEXT NOT NULL,
                interaction_id TEXT NOT NULL,
                evidence_kind TEXT NOT NULL,
                evidence_ref TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 0.8,
                occurred_at INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS revenue_sales (
                unique_key TEXT PRIMARY KEY,
                evidence_kind TEXT NOT NULL CHECK(evidence_kind IN ('SOKO_ORDER','BOOKING','POS_RECEIPT','PAYMENT_RECORD','OWNER_CONFIRMED')),
                sale_ref TEXT NOT NULL UNIQUE,
                contact_key TEXT NOT NULL,
                product_ref TEXT NOT NULL,
                amount_ugx INTEGER NOT NULL,
                state TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','CANCELLED','REFUNDED')),
                correction_reason TEXT NOT NULL DEFAULT '',
                occurred_at INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS revenue_sales_time ON revenue_sales(state, occurred_at)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS revenue_costs (
                unique_key TEXT PRIMARY KEY,
                component TEXT NOT NULL CHECK(component IN (
                    'PRODUCT_COST','DISCOUNTS','CAMPAIGN_SPEND','TRANSACTION_FEES','REFUNDS',
                    'OPERATING_ALLOCATION','SUBSCRIPTION')),
                product_ref TEXT NOT NULL DEFAULT '',
                ref TEXT NOT NULL,
                amount_ugx INTEGER NOT NULL,
                occurred_at INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS revenue_attributions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sale_unique_key TEXT NOT NULL UNIQUE,
                label TEXT NOT NULL CHECK(label IN ('DIRECT','INFLUENCED','UNATTRIBUTED')),
                rule_type TEXT NOT NULL,
                window_ms INTEGER NOT NULL,
                campaign_ref TEXT NOT NULL DEFAULT '',
                touch_ref TEXT NOT NULL DEFAULT '',
                confidence REAL NOT NULL,
                recorded_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS revenue_experiments (
                id TEXT PRIMARY KEY,
                spec_json TEXT NOT NULL,
                state TEXT NOT NULL DEFAULT 'PROPOSED' CHECK(state IN ('PROPOSED','RUNNING','CONCLUDED','STOPPED_LOSS','ROLLED_BACK')),
                started_at INTEGER,
                ended_at INTEGER,
                result_json TEXT NOT NULL DEFAULT '{}',
                created_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS commercial_actions (
                id TEXT PRIMARY KEY,
                dedupe_key TEXT NOT NULL UNIQUE,
                plan_day TEXT NOT NULL DEFAULT '',
                capability_id TEXT NOT NULL,
                target TEXT NOT NULL,
                content_hash TEXT NOT NULL DEFAULT '',
                state TEXT NOT NULL CHECK(state IN (
                    'PLANNED','AWAITING_APPROVAL','EXECUTED_VERIFIED','FAILED',
                    'BLOCKED_POLICY','SUPPRESSED')),
                ranking_json TEXT NOT NULL DEFAULT '{}',
                evidence_ref TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS commercial_actions_state ON commercial_actions(state, updated_at)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS daily_commercial_plans (
                day_key TEXT PRIMARY KEY,
                plan_json TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS daily_commercial_briefs (
                day_key TEXT PRIMARY KEY,
                revision_id INTEGER NOT NULL,
                brief_json TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS outreach_suppressions (
                contact_key TEXT PRIMARY KEY,
                reason TEXT NOT NULL,
                suppressed_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS commercial_policy (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                config_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        createRevenueObservationTables(db)
    }

    /**
     * v15 evidence ledgers. Stable unique keys make ingestion idempotent across
     * notification replay, worker retry, and process death.
     */
    private fun createRevenueObservationTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS revenue_campaign_touches (
                unique_key TEXT PRIMARY KEY,
                campaign_ref TEXT NOT NULL,
                contact_key TEXT NOT NULL,
                evidence_ref TEXT NOT NULL,
                occurred_at INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS revenue_campaign_touches_lookup ON revenue_campaign_touches(campaign_ref, contact_key, occurred_at)",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS revenue_delivery_observations (
                unique_key TEXT PRIMARY KEY,
                contact_key TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                delivery_state TEXT NOT NULL,
                observed_at INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS revenue_delivery_lookup ON revenue_delivery_observations(contact_key, content_hash, observed_at)",
        )
    }

    /** Returns the row id, or null when the unique key already exists (duplicate rejected). */
    @Synchronized
    fun recordCommercialEvent(event: CommercialEventRow): Long? = try {
        val id = writableDatabase.insertOrThrow("commercial_events", null, ContentValues().apply {
            put("unique_key", event.uniqueKey)
            put("kind", event.kind)
            put("channel", event.channel.take(80))
            put("contact_key", event.contactKey.take(200))
            put("product_ref", event.productRef.take(300))
            put("amount_ugx", event.amountUgx)
            put("evidence_kind", event.evidenceKind)
            put("evidence_ref", event.evidenceRef.take(500))
            put("attribution", event.attributionJson.take(4_000))
            put("detail_json", event.detailJson.take(8_000))
            put("occurred_at", event.occurredAtMs)
            put("recorded_at", System.currentTimeMillis())
        })
        id
    } catch (_: android.database.sqlite.SQLiteConstraintException) {
        null
    }

    @Synchronized
    fun commercialEvents(fromMs: Long, toMs: Long): List<CommercialEventRow> = readableDatabase.query(
        "commercial_events", null, "occurred_at >= ? AND occurred_at <= ?",
        arrayOf(fromMs.toString(), toMs.toString()), null, null, "occurred_at ASC",
    ).use { c ->
        buildList { while (c.moveToNext()) add(commercialEventFromCursor(c)) }
    }

    @Synchronized
    fun findCommercialEvent(uniqueKey: String): CommercialEventRow? = readableDatabase.query(
        "commercial_events", null, "unique_key = ?", arrayOf(uniqueKey), null, null, null,
    ).use { c -> if (!c.moveToFirst()) null else commercialEventFromCursor(c) }

    private fun commercialEventFromCursor(c: android.database.Cursor): CommercialEventRow = CommercialEventRow(
        uniqueKey = c.getString(c.getColumnIndexOrThrow("unique_key")),
        kind = c.getString(c.getColumnIndexOrThrow("kind")),
        channel = c.getString(c.getColumnIndexOrThrow("channel")),
        contactKey = c.getString(c.getColumnIndexOrThrow("contact_key")),
        productRef = c.getString(c.getColumnIndexOrThrow("product_ref")),
        amountUgx = if (c.isNull(c.getColumnIndexOrThrow("amount_ugx"))) null else c.getLong(c.getColumnIndexOrThrow("amount_ugx")),
        evidenceKind = c.getString(c.getColumnIndexOrThrow("evidence_kind")),
        evidenceRef = c.getString(c.getColumnIndexOrThrow("evidence_ref")),
        attributionJson = c.getString(c.getColumnIndexOrThrow("attribution")),
        detailJson = c.getString(c.getColumnIndexOrThrow("detail_json")),
        occurredAtMs = c.getLong(c.getColumnIndexOrThrow("occurred_at")),
        rowId = c.getLong(c.getColumnIndexOrThrow("id")),
    )

    /** Advances an opportunity by EXACTLY one funnel stage; anything else is refused. */
    @Synchronized
    fun advanceOpportunityStage(id: String, contactKey: String, productRef: String, toStage: String, nowMs: Long): Boolean {
        val order = listOf("IDENTIFIED","QUALIFIED","ENGAGED","OFFER_SENT","NEGOTIATING","ORDER_PLACED","PAID","DELIVERED")
        val targetIndex = order.indexOf(toStage)
        require(targetIndex > 0) { "Stage '$toStage' is not an advance target" }
        val current = readableDatabase.query(
            "commercial_opportunities", arrayOf("stage"), "id = ?", arrayOf(id), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (current == null) {
            if (targetIndex != 1) return false // a fresh opportunity starts at QUALIFIED at most via creation+advance
            writableDatabase.insertOrThrow("commercial_opportunities", null, ContentValues().apply {
                put("id", id); put("contact_key", contactKey.take(200))
                put("product_ref", productRef.take(300)); put("stage", toStage); put("updated_at", nowMs)
            })
            return true
        }
        if (order.indexOf(current) + 1 != targetIndex) return false
        return writableDatabase.update(
            "commercial_opportunities",
            ContentValues().apply { put("stage", toStage); put("updated_at", nowMs) },
            "id = ? AND stage = ?", arrayOf(id, current),
        ) == 1
    }

    @Synchronized
    fun findOpportunity(id: String): OpportunityRow? = readableDatabase.query(
        "commercial_opportunities", null, "id = ?", arrayOf(id), null, null, null,
    ).use { c ->
        if (!c.moveToFirst()) null else OpportunityRow(
            id = c.getString(0), contactKey = c.getString(1), productRef = c.getString(2),
            stage = c.getString(3), updatedAtMs = c.getLong(4),
        )
    }

    @Synchronized
    fun opportunities(): List<OpportunityRow> = readableDatabase.query(
        "commercial_opportunities", null, null, null, null, null, "updated_at DESC", "200",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                OpportunityRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)),
            )
        }
    }

    data class CommercialEventRow(
        val uniqueKey: String,
        val kind: String,
        val channel: String,
        val contactKey: String,
        val productRef: String,
        val amountUgx: Long?,
        val evidenceKind: String,
        val evidenceRef: String,
        val attributionJson: String,
        val detailJson: String,
        val occurredAtMs: Long,
        val rowId: Long = 0,
    )

    data class OpportunityRow(val id: String, val contactKey: String, val productRef: String, val stage: String, val updatedAtMs: Long)

    // ---------- durable workflow runs (Phase B) ----------

    @Synchronized
    fun upsertWorkflowRun(run: WorkflowRunRow): Boolean {
        writableDatabase.execSQL(
            """INSERT INTO workflow_runs(id, contract_fingerprint, step_index, phase, lease_owner, lease_expires_at, decision_question, checkpoint_json, updated_at)
               VALUES(?,?,?,?,?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET updated_at=excluded.updated_at""",
            arrayOf<Any?>(run.id, run.contractFingerprint, run.stepIndex, run.phase.name, run.leaseOwner,
                run.leaseExpiresAtMs, run.decisionQuestion, run.checkpointJson, run.updatedAtMs),
        )
        return true
    }

    @Synchronized
    fun findWorkflowRun(runId: String): WorkflowRunRow? = readableDatabase.query(
        "workflow_runs",        arrayOf("id", "contract_fingerprint", "step_index", "phase", "lease_owner", "lease_expires_at", "decision_question", "checkpoint_json", "updated_at"),
        "id = ?", arrayOf(runId), null, null, null,
    ).use { c ->
        if (!c.moveToFirst()) null else WorkflowRunRow(
            id = c.getString(0), contractFingerprint = c.getString(1), stepIndex = c.getInt(2),
            phase = RunPhase.valueOf(c.getString(3)), leaseOwner = if (c.isNull(4)) null else c.getString(4),
            leaseExpiresAtMs = c.getLong(5), decisionQuestion = c.getString(6),
            checkpointJson = c.getString(7), updatedAtMs = c.getLong(8),
        )
    }

    /** All durable workflow runs — the evidence feed for earned-autonomy metrics. */
    @Synchronized
    fun allWorkflowRuns(limit: Int = 200): List<WorkflowRunRow> = readableDatabase.query(
        "workflow_runs",        arrayOf("id", "contract_fingerprint", "step_index", "phase", "lease_owner", "lease_expires_at", "decision_question", "checkpoint_json", "updated_at"),
        null, null, null, null, "updated_at DESC", limit.coerceIn(1, 1000).toString(),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                WorkflowRunRow(
                    id = c.getString(0), contractFingerprint = c.getString(1), stepIndex = c.getInt(2),
                    phase = RunPhase.valueOf(c.getString(3)), leaseOwner = if (c.isNull(4)) null else c.getString(4),
                    leaseExpiresAtMs = c.getLong(5), decisionQuestion = c.getString(6),
                    checkpointJson = c.getString(7), updatedAtMs = c.getLong(8),
                ),
            )
        }
    }

    /** CAS lease acquisition: succeeds only when the lease is free or expired. */
    @Synchronized
    fun tryAcquireWorkflowLease(runId: String, worker: String, newExpiryMs: Long, nowMs: Long): Boolean {
        val run = findWorkflowRun(runId) ?: return false
        val heldByOther = run.leaseOwner != null && run.leaseOwner != worker && run.leaseExpiresAtMs > nowMs
        if (heldByOther) return false
        writableDatabase.execSQL(
            """UPDATE workflow_runs SET lease_owner = ?, lease_expires_at = ?, updated_at = ? WHERE id = ?""",
            arrayOf<Any>(worker, newExpiryMs, nowMs, runId),
        )
        return true
    }

    /**
     * Optimistic checkpoint advance: applies only when the persisted step index still
     * equals [expectedStepIndex], so two workers can never both advance the same step.
     */
    @Synchronized
    fun recordWorkflowCheckpoint(runId: String, expectedStepIndex: Int, checkpointJson: String, nowMs: Long): Boolean {
        val updated = writableDatabase.execSQLWithCount(
            """UPDATE workflow_runs SET step_index = ?, checkpoint_json = ?, phase = ?, updated_at = ?
               WHERE id = ? AND step_index = ? AND phase IN ('PENDING','RUNNING','CHECKPOINTED')""",
            arrayOf<Any>(expectedStepIndex + 1, checkpointJson.take(4_000), RunPhase.CHECKPOINTED.name, nowMs, runId, expectedStepIndex),
        )
        return updated > 0
    }

    @Synchronized
    fun markWorkflowDecisionRequired(runId: String, question: String): Boolean {
        val updated = writableDatabase.execSQLWithCount(
            "UPDATE workflow_runs SET phase = ?, decision_question = ?, updated_at = ? WHERE id = ? AND phase NOT IN ('COMPLETED','CANCELLED')",
            arrayOf<Any>(RunPhase.AWAITING_DECISION.name, question.take(1_000), System.currentTimeMillis(), runId),
        )
        return updated > 0
    }

    @Synchronized
    fun resolveWorkflowDecision(runId: String, answer: String): Boolean {
        // The owner's answer is appended to the checkpoint JSON under "decisionAnswer"
        // so resumption keeps the accumulated sections/claims that approvals bind against.
        val current = findWorkflowRun(runId)?.checkpointJson.orEmpty()
        val merged = runCatching {
            val json = org.json.JSONObject(if (current.startsWith("{")) current else "{}")
            json.put("decisionAnswer", answer.take(1_000))
            json.toString()
        }.getOrDefault("""{"decisionAnswer":${org.json.JSONObject.quote(answer.take(1_000))}}""")
        val updated = writableDatabase.execSQLWithCount(
            """UPDATE workflow_runs SET phase = ?, decision_question = '', checkpoint_json = ?, updated_at = ?
               WHERE id = ? AND phase = ?""",
            arrayOf<Any>(RunPhase.RUNNING.name, merged.take(16_000), System.currentTimeMillis(), runId, RunPhase.AWAITING_DECISION.name),
        )
        return updated > 0
    }

    /** Terminal completion of a run: only actively-executing runs may complete. */
    @Synchronized
    fun markWorkflowCompleted(runId: String, resultJson: String, nowMs: Long): Boolean {
        val updated = writableDatabase.execSQLWithCount(
            """UPDATE workflow_runs SET phase = ?, checkpoint_json = ?, decision_question = '', lease_owner = NULL, updated_at = ?
               WHERE id = ? AND phase IN ('PENDING','RUNNING','CHECKPOINTED')""",
            arrayOf<Any>(RunPhase.COMPLETED.name, resultJson.take(4_000), nowMs, runId),
        )
        return updated > 0
    }

    /** Commitment inspection: every nonterminal workflow with its next owner question. */
    @Synchronized
    fun activeCommitments(): List<WorkflowRunRow> = readableDatabase.query(
        "workflow_runs", null,
        "phase NOT IN ('COMPLETED','CANCELLED') ORDER BY updated_at DESC LIMIT 100", null as Array<String>?,
        null, null, null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                WorkflowRunRow(
                    id = c.getString(0), contractFingerprint = c.getString(1), stepIndex = c.getInt(2),
                    phase = RunPhase.valueOf(c.getString(3)), leaseOwner = if (c.isNull(4)) null else c.getString(4),
                    leaseExpiresAtMs = c.getLong(5), decisionQuestion = c.getString(6),
                    checkpointJson = c.getString(7), updatedAtMs = c.getLong(8),
                ),
            )
        }
    }

    // ---------- durable artifact revisions (Phase C) ----------

    @Synchronized
    fun insertArtifactRevision(artifactId: String, createdAtMs: Long, author: String, specJson: String, parentRevision: Long?, contentHash: String): Long {
        writableDatabase.execSQL(
            """INSERT INTO artifact_revisions(artifact_id, created_at, author, spec_json, parent_revision, content_hash)
               VALUES(?,?,?,?,?,?)""",
            arrayOf<Any?>(artifactId, createdAtMs, author, specJson.take(20_000), parentRevision, contentHash),
        )
        var id = -1L
        readableDatabase.rawQuery("SELECT last_insert_rowid()", null).use { c -> if (c.moveToFirst()) id = c.getLong(0) }
        return id
    }

    @Synchronized
    fun artifactRevisions(artifactId: String): List<ArtifactRevisionRow> = readableDatabase.query(
        "artifact_revisions", null, "artifact_id = ?", arrayOf(artifactId), null, null, "id ASC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                ArtifactRevisionRow(
                    id = c.getLong(0), artifactId = c.getString(1), createdAtMs = c.getLong(2),
                    author = c.getString(3), specJson = c.getString(4),
                    parentRevision = if (c.isNull(5)) null else c.getLong(5), contentHash = c.getString(6),
                ),
            )
        }
    }

    // ---------- durable connector revocations (Phase D) ----------

    @Synchronized
    fun recordConnectorRevocation(connectorId: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO connector_revocations(connector_id, revoked_at) VALUES(?, ?)",
            arrayOf<Any>(connectorId, System.currentTimeMillis()),
        )
    }

    @Synchronized
    fun isConnectorRevoked(connectorId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM connector_revocations WHERE connector_id = ?", arrayOf(connectorId),
    ).use { it.moveToFirst() }

    data class WorkflowRunRow(
        val id: String,
        val contractFingerprint: String,
        val stepIndex: Int,
        val phase: co.sanaa.agent.core.work.RunPhase,
        val leaseOwner: String?,
        val leaseExpiresAtMs: Long,
        val decisionQuestion: String,
        val checkpointJson: String,
        val updatedAtMs: Long,
    )

    data class ArtifactRevisionRow(
        val id: Long,
        val artifactId: String,
        val createdAtMs: Long,
        val author: String,
        val specJson: String,
        val parentRevision: Long?,
        val contentHash: String,
    )

    /** Crash recovery: a process death during acting/verification leaves the effect uncertain. */
    @Synchronized
    fun markOrphanedTransactionsUncertain() {
        writableDatabase.execSQL(
            """UPDATE side_effect_transactions
               SET state = 'UNCERTAIN',
                   updated_at = ?,
                   evidence = CASE WHEN evidence = '' THEN 'Process died before the result could be proven.' ELSE evidence END
               WHERE state IN ('CLAIMED','ACTING','VERIFICATION_PENDING')""".trimIndent(),
            arrayOf(System.currentTimeMillis()),
        )
    }

    @Synchronized
    fun findSideEffectTransaction(idempotencyKey: String): SideEffectTransaction? = readableDatabase.query(
        "side_effect_transactions",
        arrayOf("idempotency_key", "capability", "target", "content_hash", "approval_id", "state", "created_at", "updated_at", "evidence"),
        "idempotency_key = ?", arrayOf(idempotencyKey), null, null, null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else SideEffectTransaction(
            cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
            if (cursor.isNull(4)) null else cursor.getLong(4),
            SideEffectState.valueOf(cursor.getString(5)), cursor.getLong(6), cursor.getLong(7), cursor.getString(8),
        )
    }

    /** Full transaction listing (audit/certification use). */
    @Synchronized
    fun allSideEffectTransactions(): List<SideEffectTransaction> = readableDatabase.query(
        "side_effect_transactions",
        arrayOf("idempotency_key", "capability", "target", "content_hash", "approval_id", "state", "created_at", "updated_at", "evidence"),
        null, null, null, null, "created_at ASC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                SideEffectTransaction(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    if (c.isNull(4)) null else c.getLong(4),
                    SideEffectState.valueOf(c.getString(5)), c.getLong(6), c.getLong(7), c.getString(8),
                ),
            )
        }
    }

    /** Inserts a new transaction; fails when the key already exists (no silent overwrite). */
    @Synchronized
    fun upsertSideEffectTransaction(transaction: SideEffectTransaction): Boolean {
        val existing = findSideEffectTransaction(transaction.idempotencyKey) ?: run {
            writableDatabase.execSQL(
                """INSERT INTO side_effect_transactions(
                       idempotency_key, created_at, updated_at, capability, target, content_hash, approval_id, state, evidence)
                   VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                arrayOf<Any?>(
                    transaction.idempotencyKey, transaction.createdAt, transaction.updatedAt, transaction.capability,
                    transaction.target.take(300), transaction.contentHash, transaction.approvalId, transaction.state.name,
                    transaction.evidence.take(2_000),
                ),
            )
            return true
        }
        // Re-claim is legal only within identical capability/content while pre-acting.
        if (existing.capability != transaction.capability || existing.contentHash != transaction.contentHash) return false
        if (!SideEffectState.canTransition(existing.state, transaction.state)) return false
        writableDatabase.execSQL(
            """UPDATE side_effect_transactions SET updated_at = ?, target = ?, approval_id = ?, state = ?, evidence = ?
               WHERE idempotency_key = ? AND state = ?""",
            arrayOf<Any?>(
                clockNow(), transaction.target.take(300), transaction.approvalId, transaction.state.name,
                transaction.evidence.take(2_000), transaction.idempotencyKey, existing.state.name,
            ),
        )
        return true
    }

    /**
     * Atomically applies one legal transition. Returns false for unknown keys, illegal
     * transitions, and lost races — callers must treat false as enforcement, not retry.
     */
    @Synchronized
    fun transitionSideEffectTransaction(idempotencyKey: String, newState: SideEffectState, evidence: String): Boolean {
        val existing = findSideEffectTransaction(idempotencyKey) ?: return false
        if (!SideEffectState.canTransition(existing.state, newState)) return false
        val legalFromStates = (SideEffectState.LEGAL_TRANSITIONS.filterValues { newState in it }.keys + existing.state)
            .joinToString(",") { "'${it.name}'" }
        writableDatabase.execSQL(
            """UPDATE side_effect_transactions SET state = ?, updated_at = ?, evidence = ?
               WHERE idempotency_key = ? AND state IN ($legalFromStates)""",
            arrayOf<Any?>(newState.name, clockNow(), evidence.take(2_000), idempotencyKey),
        )
        return true
    }

    /**
     * Retention sweep (Phase A6): deletes business records older than the retention
     * window. Owner chat history and security-relevant transaction records are kept
     * until explicitly deleted by the owner.
     */
    @Synchronized
    fun pruneExpiredData(retentionDays: Int, now: Long = System.currentTimeMillis()) {
        val cutoff = now - retentionDays.coerceAtLeast(1) * 24 * 60 * 60 * 1_000L
        listOf(
            "conversations" to "timestamp",
            "actions" to "timestamp",
            "products_seen" to "last_seen_timestamp",
            "business_findings" to "created_at",
        ).forEach { (table, column) ->
            writableDatabase.execSQL("DELETE FROM $table WHERE $column <= ?", arrayOf<Any>(cutoff))
        }
    }

    /** Owner-initiated deletion of all recorded business activity; schema is preserved. */
    @Synchronized
    fun deleteOwnerBusinessData() {
        listOf(
            "conversations", "actions", "products_seen", "business_findings",
            "side_effect_receipts", "side_effect_transactions", "task_journal_steps", "task_journal",
            "owner_instructions", "artifact_revisions", "budget_spend", "work_enforcement",
            "commercial_events", "commercial_opportunities",
            "revenue_goals", "revenue_opportunities", "revenue_funnel_transitions",
            "revenue_inquiries", "revenue_sales", "revenue_costs", "revenue_attributions",
            "revenue_experiments", "commercial_actions", "daily_commercial_plans",
            "daily_commercial_briefs", "outreach_suppressions", "contact_consent",
            "revenue_campaign_touches", "revenue_delivery_observations",
            "commercial_targets",
        ).forEach { table -> writableDatabase.execSQL("DELETE FROM $table") }
    }

    /**
     * Owner export (Phase A6): redacted JSON of every stored record class. Secrets are
     * removed by [Redactor] before the payload is returned.
     */
    @Synchronized
    fun exportOwnerData(): org.json.JSONObject {
        val export = org.json.JSONObject()
        fun table(name: String, columns: String): org.json.JSONArray {
            val rows = org.json.JSONArray()
            readableDatabase.rawQuery("SELECT $columns FROM $name ORDER BY rowid DESC LIMIT 500", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = org.json.JSONObject()
                    for (index in 0 until cursor.columnCount) {
                        val value = when (cursor.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            else -> Redactor.redact(cursor.getString(index) ?: "")
                        }
                        row.put(cursor.getColumnName(index), value)
                    }
                    rows.put(row)
                }
            }
            return rows
        }
        export.put("exported_at", System.currentTimeMillis())
        export.put("conversations", table("conversations", "contact_name, platform, direction, message_text, timestamp"))
        export.put("actions", table("actions", "type, target_app, what_amara_did, result, timestamp, success"))
        export.put("approvals", table("approval_requests", "capability, target, description, risk, status, expires_at"))
        export.put("findings", table("business_findings", "source_app, subject, issue, severity, confidence, status"))
        export.put("schedules", table("recurring_tasks", "instruction, task_text, rule, next_run_at, enabled"))
        export.put("transactions", table("side_effect_transactions", "idempotency_key, capability, target, state, evidence, updated_at"))
        export.put("artifact_revisions", table("artifact_revisions", "artifact_id, author, spec_json, content_hash, created_at"))
        export.put("budget_spend", table("budget_spend", "contract_id, amount_ugx, reason, created_at"))
        export.put("campaign_touches", table("revenue_campaign_touches", "campaign_ref, contact_key, evidence_ref, occurred_at"))
        export.put("delivery_observations", table("revenue_delivery_observations", "contact_key, content_hash, delivery_state, observed_at"))
        return export
    }

    private fun clockNow(): Long = System.currentTimeMillis()

    @Synchronized
    fun createApprovalRequest(
        capability: String,
        target: String,
        description: String,
        beforeJson: String,
        afterJson: String,
        risk: ActionRisk,
        expiresAt: Long = System.currentTimeMillis() + 24 * 60 * 60 * 1_000L,
    ): Long {
        val existing = readableDatabase.query(
            "approval_requests", arrayOf("id"),
            "capability = ? AND target = ? AND after_json = ? AND status = 'pending' AND expires_at > ?",
            arrayOf(capability, target, afterJson.take(8_000), System.currentTimeMillis().toString()), null, null, "created_at DESC", "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        if (existing != null) return existing
        return writableDatabase.insertOrThrow("approval_requests", null, ContentValues().apply {
        put("created_at", System.currentTimeMillis())
        put("capability", capability)
        put("target", target)
        put("description", description.take(1_500))
        put("before_json", beforeJson.take(8_000))
        put("after_json", afterJson.take(8_000))
        put("risk", risk.name)
        put("status", "pending")
        put("expires_at", expiresAt)
        })
    }

    @Synchronized
    fun latestPendingApproval(now: Long = System.currentTimeMillis()): ApprovalRequest? {
        writableDatabase.execSQL("UPDATE approval_requests SET status = 'expired' WHERE status = 'pending' AND expires_at <= ?", arrayOf(now))
        return readableDatabase.query(
            "approval_requests",
            arrayOf("id", "capability", "target", "description", "before_json", "after_json", "risk", "status", "expires_at"),
            "status = 'pending' AND expires_at > ?", arrayOf(now.toString()), null, null, "created_at DESC", "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ApprovalRequest(
                cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
                cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getLong(8),
            )
        }
    }

    @Synchronized
    fun pendingApprovals(now: Long = System.currentTimeMillis(), limit: Int = 50): List<ApprovalRequest> {
        writableDatabase.execSQL("UPDATE approval_requests SET status = 'expired' WHERE status = 'pending' AND expires_at <= ?", arrayOf(now))
        return readableDatabase.query(
            "approval_requests",
            arrayOf("id", "capability", "target", "description", "before_json", "after_json", "risk", "status", "expires_at"),
            "status = 'pending' AND expires_at > ?", arrayOf(now.toString()), null, null, "created_at DESC", limit.coerceIn(1, 100).toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ApprovalRequest(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
                    cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getLong(8),
                ))
            }
        }
    }

    @Synchronized
    fun decideApproval(id: Long, approve: Boolean): Boolean = writableDatabase.update(
        "approval_requests",
        ContentValues().apply {
            put("status", if (approve) "approved" else "rejected")
            put("decided_at", System.currentTimeMillis())
        },
        "id = ? AND status = 'pending' AND expires_at > ?",
        arrayOf(id.toString(), System.currentTimeMillis().toString()),
    ) == 1

    @Synchronized
    fun consumeApproval(id: Long): Boolean = writableDatabase.update(
        "approval_requests", ContentValues().apply { put("status", "consumed") }, "id = ? AND status = 'approved'", arrayOf(id.toString()),
    ) == 1

    /** True when approval id still exists, is approved, unexpired, and bound to this exact capability/target/content. */
    @Synchronized
    fun approvalStillValid(id: Long, capability: String, target: String, afterJson: String): Boolean = readableDatabase.query(
        "approval_requests", arrayOf("id"),
        "id = ? AND capability = ? AND target = ? AND after_json = ? AND status = 'approved' AND expires_at > ?",
        arrayOf(id.toString(), capability, target, afterJson.take(8_000), System.currentTimeMillis().toString()),
        null, null, null, "1",
    ).use { it.moveToFirst() }

    @Synchronized
    fun matchingApprovedApproval(capability: String, target: String, afterJson: String): ApprovalRequest? = readableDatabase.query(
        "approval_requests",
        arrayOf("id", "capability", "target", "description", "before_json", "after_json", "risk", "status", "expires_at"),
        "capability = ? AND target = ? AND after_json = ? AND status = 'approved' AND expires_at > ?",
        arrayOf(capability, target, afterJson.take(8_000), System.currentTimeMillis().toString()),
        null, null, "decided_at DESC", "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else ApprovalRequest(
            cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
            cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getLong(8),
        )
    }

    @Synchronized
    fun recordBusinessFinding(
        sourceApp: String,
        subject: String,
        issue: String,
        severity: String,
        confidence: Double,
        evidence: String,
        recommendation: String,
    ): Long {
        val existing = readableDatabase.query(
            "business_findings", arrayOf("id"),
            "source_app = ? AND subject = ? AND issue = ? AND status IN ('open','proposed','approved')",
            arrayOf(sourceApp, subject, issue), null, null, "created_at DESC", "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        if (existing != null) return existing
        return writableDatabase.insertOrThrow("business_findings", null, ContentValues().apply {
        put("created_at", System.currentTimeMillis())
        put("source_app", sourceApp)
        put("subject", subject.take(300))
        put("issue", issue.take(1_500))
        put("severity", severity)
        put("confidence", confidence.coerceIn(0.0, 1.0))
        put("evidence", evidence.take(4_000))
        put("recommendation", recommendation.take(2_000))
        put("status", "open")
        })
    }

    @Synchronized
    fun openBusinessFindings(limit: Int = 50): List<BusinessFindingRecord> = readableDatabase.query(
        "business_findings", arrayOf("id", "source_app", "subject", "issue", "severity", "confidence", "evidence", "recommendation", "status"),
        "status IN ('open','proposed','approved')", null, null, null, "created_at DESC", limit.coerceIn(1, 200).toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(BusinessFindingRecord(
                cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
                cursor.getDouble(5), cursor.getString(6), cursor.getString(7), cursor.getString(8),
            ))
        }
    }

    @Synchronized
    fun createRecurringTask(instruction: String, schedule: ParsedSchedule, nextRunAt: Long): Long = writableDatabase.insertOrThrow(
        "recurring_tasks", null, ContentValues().apply {
            put("created_at", System.currentTimeMillis())
            // Storage-boundary sanitization: recurring instructions are replayed by
            // schedulers and exported; only credential-free text may persist.
            put("instruction", Redactor.redact(instruction).take(2_000))
            put("task_text", Redactor.redact(schedule.taskText).take(2_000))
            put("rule", schedule.rule())
            put("schedule_json", org.json.JSONObject().apply {
                put("kind", schedule.kind.name)
                schedule.intervalMinutes?.let { put("intervalMinutes", it) }
                schedule.localTime?.let { put("localTime", it.toString()) }
                put("days", org.json.JSONArray(schedule.days.map { it.name }))
            }.toString())
            put("next_run_at", nextRunAt)
            put("enabled", 1)
        },
    )

    @Synchronized
    fun recurringTask(id: Long): RecurringTaskRecord? = readableDatabase.query(
        "recurring_tasks", arrayOf("id", "instruction", "task_text", "rule", "schedule_json", "next_run_at", "enabled"),
        "id = ?", arrayOf(id.toString()), null, null, null, "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else RecurringTaskRecord(
            cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getLong(5), cursor.getInt(6) == 1,
        )
    }

    @Synchronized
    fun recurringTasks(limit: Int = 100): List<RecurringTaskRecord> = readableDatabase.query(
        "recurring_tasks", arrayOf("id", "instruction", "task_text", "rule", "schedule_json", "next_run_at", "enabled"),
        null, null, null, null, "created_at DESC", limit.coerceIn(1, 200).toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(RecurringTaskRecord(
                cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getLong(5), cursor.getInt(6) == 1,
            ))
        }
    }

    @Synchronized
    fun setRecurringTaskEnabled(id: Long, enabled: Boolean): Boolean = writableDatabase.update(
        "recurring_tasks", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id = ?", arrayOf(id.toString()),
    ) == 1

    @Synchronized
    fun completeRecurringOccurrence(id: Long, result: String, nextRunAt: Long) {
        writableDatabase.update("recurring_tasks", ContentValues().apply {
            put("last_run_at", System.currentTimeMillis())
            put("last_result", Redactor.redact(result).take(2_000))
            put("next_run_at", nextRunAt)
        }, "id = ?", arrayOf(id.toString()))
    }

    // Legacy side_effect_receipts accessors were removed: the canonical
    // side-effect ledger is upsert/find/transitionSideEffectTransaction (single
    // authority for transaction receipts). The table itself is retained so older
    // databases keep their schema; deleteOwnerBusinessData still wipes it.

    @Synchronized
    fun updateTaskContext(taskId: Long, observation: String, analysis: String) {
        writableDatabase.update("task_journal", ContentValues().apply {
            put("observation", Redactor.redact(observation).take(4_000))
            put("analysis", Redactor.redact(analysis).take(4_000))
            put("updated_at", System.currentTimeMillis())
        }, "id = ?", arrayOf(taskId.toString()))
    }

    @Synchronized
    fun createTaskJournal(instructionId: Long, command: String): Long {
        val now = System.currentTimeMillis()
        return writableDatabase.insertOrThrow("task_journal", null, ContentValues().apply {
            put("instruction_id", instructionId)
            put("command", Redactor.redact(command))
            put("status", "running")
            put("phase", "observe")
            put("created_at", now)
            put("updated_at", now)
        })
    }

    @Synchronized
    fun updateTaskJournal(taskId: Long, status: String, phase: String, result: String = "", replanCount: Int? = null) {
        writableDatabase.update("task_journal", ContentValues().apply {
            put("status", status)
            put("phase", phase)
            put("updated_at", System.currentTimeMillis())
            put("final_result", Redactor.redact(result).take(4_000))
            if (replanCount != null) put("replan_count", replanCount)
        }, "id = ?", arrayOf(taskId.toString()))
    }

    @Synchronized
    fun recordTaskJournalStep(
        taskId: Long,
        stepIndex: Int,
        attempt: Int,
        action: String,
        reason: String,
        prePackage: String,
        preSignature: String,
        postPackage: String,
        postSignature: String,
        status: String,
        result: String,
    ): Long = writableDatabase.insertOrThrow("task_journal_steps", null, ContentValues().apply {
        put("task_id", taskId)
        put("step_index", stepIndex)
        put("attempt", attempt)
        put("action", action)
        put("reason", reason.take(500))
        put("pre_package", prePackage)
        put("pre_signature", preSignature.take(8_000))
        put("post_package", postPackage)
        put("post_signature", postSignature.take(8_000))
        put("status", status)
        put("result", result.take(4_000))
        put("created_at", System.currentTimeMillis())
    })

    @Synchronized
    fun latestTaskJournal(): TaskJournalSummary? = readableDatabase.query(
        "task_journal", arrayOf("id", "command", "status", "phase", "final_result", "updated_at"),
        null, null, null, null, "updated_at DESC", "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else TaskJournalSummary(
            cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getLong(5),
        )
    }

    @Synchronized
    fun latestTaskReceipt(): TaskJournalReceipt? {
        val task = readableDatabase.query(
            "task_journal", arrayOf("id", "status", "final_result", "observation", "analysis"),
            null, null, null, null, "updated_at DESC", "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            listOf(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4))
        }
        val taskId = task[0] as Long
        val steps = readableDatabase.query(
            "task_journal_steps", arrayOf("action", "reason", "status", "result"),
            "task_id = ?", arrayOf(taskId.toString()), null, null, "id ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    TaskJournalStepSummary(cursor.getString(0), cursor.getString(1), cursor.getString(2) == "verified", cursor.getString(3)),
                )
            }
        }
        val status = task[1] as String
        return TaskJournalReceipt(
            success = status == "completed",
            status = status,
            message = task[2] as String,
            observation = task[3] as String,
            analysis = task[4] as String,
            steps = steps,
        )
    }

    @Synchronized
    fun markInterruptedTasks() {
        writableDatabase.execSQL(
            """UPDATE task_journal
               SET status = 'interrupted', phase = 'report', updated_at = ?,
                   final_result = CASE WHEN final_result = '' THEN 'The app process stopped before this task reached a verified result.' ELSE final_result END
               WHERE status IN ('running', 'recovering')""".trimIndent(),
            arrayOf(System.currentTimeMillis()),
        )
    }

    @Synchronized
    fun recordInstruction(text: String, status: String = "pending", recurrenceRule: String? = null): Long =
        writableDatabase.insertOrThrow("owner_instructions", null, ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("instruction_text", text)
            put("status", status)
            put("recurrence_rule", recurrenceRule)
        })

    @Synchronized
    fun updateInstruction(id: Long, status: String) {
        writableDatabase.update("owner_instructions", ContentValues().apply { put("status", status) }, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun recordAction(
        type: String,
        targetContact: String?,
        targetApp: String?,
        command: String,
        whatAmaraDid: String,
        result: String,
        groqResponse: String?,
        success: Boolean,
    ): Long = writableDatabase.insertOrThrow("actions", null, ContentValues().apply {
        put("timestamp", System.currentTimeMillis())
        put("type", type)
        put("target_contact", targetContact)
        put("target_app", targetApp)
        put("command_given", command)
        put("what_amara_did", whatAmaraDid)
        put("result", result)
        put("groq_response", groqResponse)
        put("success", if (success) 1 else 0)
    })

    @Synchronized
    fun recordConversation(
        contactName: String?,
        contactNumber: String?,
        platform: String,
        direction: String,
        message: String,
        replied: Boolean = false,
        replyText: String? = null,
    ): Long = writableDatabase.insertOrThrow("conversations", null, ContentValues().apply {
        put("contact_name", contactName)
        put("contact_number", contactNumber)
        put("platform", platform)
        put("direction", direction)
        // Owner-chat rows are the durable mirror of owner commands and Amara answers:
        // they receive credential-sanitized text at the storage boundary so a raw
        // secret can never persist even if a future call site forgets the guard.
        if (platform == OWNER_CHAT) {
            put("message_text", Redactor.redact(message))
            put("reply_text", replyText?.let(Redactor::redact))
        } else {
            put("message_text", message)
            put("reply_text", replyText)
        }
        put("timestamp", System.currentTimeMillis())
        put("replied", if (replied) 1 else 0)
    })

    @Synchronized
    fun recordProductSeen(sourceApp: String, name: String, priceUgx: Long?, description: String, qualityScore: Double? = null): Long =
        writableDatabase.insertOrThrow("products_seen", null, ContentValues().apply {
            put("source_app", sourceApp)
            put("product_name", name)
            if (priceUgx == null) putNull("price_ugx") else put("price_ugx", priceUgx)
            put("description", description)
            if (qualityScore == null) putNull("listing_quality_score") else put("listing_quality_score", qualityScore)
            put("last_seen_timestamp", System.currentTimeMillis())
        })

    @Synchronized
    fun recordSelectorOutcome(appPackage: String, actionName: String, selector: String, success: Boolean, result: String) {
        writableDatabase.execSQL(
            """INSERT INTO selector_learning(app_package, action_name, selector, successes, failures, last_result, last_used_timestamp)
               VALUES(?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(app_package, action_name, selector) DO UPDATE SET
                 successes = successes + excluded.successes,
                 failures = failures + excluded.failures,
                 last_result = excluded.last_result,
                 last_used_timestamp = excluded.last_used_timestamp""".trimIndent(),
            arrayOf<Any>(appPackage, actionName, selector, if (success) 1 else 0, if (success) 0 else 1, result.take(240), System.currentTimeMillis()),
        )
    }

    @Synchronized
    fun selectorScore(appPackage: String, actionName: String, selector: String): Int = readableDatabase.rawQuery(
        "SELECT successes - failures FROM selector_learning WHERE app_package = ? AND action_name = ? AND selector = ?",
        arrayOf(appPackage, actionName, selector),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    @Synchronized
    fun actionSucceededRecently(type: String, commandContains: String, sinceMillis: Long): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM actions WHERE type = ? AND success = 1 AND timestamp >= ? AND command_given LIKE ? LIMIT 1",
        arrayOf(type, sinceMillis.toString(), "%$commandContains%"),
    ).use { it.moveToFirst() }

    @Synchronized
    fun actionSucceededRecentlyForTarget(type: String, target: String, commandContains: String, sinceMillis: Long): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM actions WHERE type = ? AND success = 1 AND target_contact = ? AND timestamp >= ? AND command_given LIKE ? LIMIT 1",
        arrayOf(type, target, sinceMillis.toString(), "%$commandContains%"),
    ).use { it.moveToFirst() }

    /** Newest-first product targets attempted by the TikTok worker. Attempts, not just
     * verified posts, are included so uncertain publications cannot be duplicated. */
    @Synchronized
    fun recentTikTokProductTargets(sinceMillis: Long, limit: Int = 50): List<String> = readableDatabase.rawQuery(
        """SELECT target_contact FROM actions
           WHERE type = 'tiktok_post' AND target_contact IS NOT NULL AND timestamp >= ?
           ORDER BY timestamp DESC LIMIT ?""".trimIndent(),
        arrayOf(sinceMillis.toString(), limit.coerceIn(1, 200).toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    @Synchronized
    fun ownerChatHistory(limit: Int = 100): List<MemoryChatMessage> {
        val descending = readableDatabase.query(
            "conversations", arrayOf("direction", "message_text", "timestamp"),
            "platform = ?", arrayOf(OWNER_CHAT), null, null, "timestamp DESC", limit.coerceIn(1, 200).toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(MemoryChatMessage(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
            }
        }
        return descending.asReversed()
    }

    @Synchronized
    fun latestAction(): MemoryAction? = readableDatabase.query(
        "actions", arrayOf("what_amara_did", "result", "timestamp", "success"),
        null, null, null, null, "timestamp DESC", "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else MemoryAction(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getInt(3) == 1)
    }

    /** A small, bounded memory window safe to place in the model system prompt. */
    @Synchronized
    fun promptContext(): String {
        val products = readableDatabase.rawQuery(
            """SELECT product_name, price_ugx, description FROM products_seen p
               WHERE last_seen_timestamp = (SELECT MAX(last_seen_timestamp) FROM products_seen WHERE product_name = p.product_name)
               ORDER BY last_seen_timestamp DESC LIMIT 30""".trimIndent(), null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val price = if (cursor.isNull(1)) "price not shown" else "UGX ${cursor.getLong(1)}"
                    add("- ${compact(cursor.getString(0))}, $price: ${compact(cursor.getString(2).orEmpty())}")
                }
            }
        }
        return """
            Products learned from the phone:
            ${products.ifEmpty { listOf("- No products learned yet") }.joinToString("\n")}
        """.trimIndent()
    }

    /** Distinct recently-seen product names — the real cached-inventory feed used by
     *  the commercial cycle's morning snapshot (never invented values). */
    @Synchronized
    fun recentProductNames(limit: Int = 40): List<String> = readableDatabase.rawQuery(
        """SELECT DISTINCT product_name FROM products_seen
           ORDER BY last_seen_timestamp DESC LIMIT ?""".trimIndent(),
        arrayOf(limit.coerceIn(1, 100).toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Synchronized
    fun conversationHistory(contact: String, limit: Int = 30): List<MemoryChatMessage> {
        val descending = readableDatabase.rawQuery(
            """SELECT direction, message_text, timestamp FROM conversations
               WHERE platform = 'whatsapp' AND (contact_name = ? OR contact_number = ?)
               ORDER BY timestamp DESC LIMIT ?""".trimIndent(),
            arrayOf(contact, contact, limit.coerceIn(1, 100).toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(MemoryChatMessage(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
            }
        }
        return descending.asReversed()
    }

    @Synchronized
    fun pendingWhatsAppFollowUps(olderThanMillis: Long, limit: Int = 20): List<FollowUpCandidate> = readableDatabase.rawQuery(
        """SELECT COALESCE(c.contact_name, c.contact_number), c.message_text, c.timestamp
           FROM conversations c
           WHERE c.platform = 'whatsapp' AND c.direction = 'received' AND c.timestamp <= ?
             AND c.id = (SELECT MAX(c2.id) FROM conversations c2
                         WHERE c2.platform = 'whatsapp'
                           AND COALESCE(c2.contact_name, c2.contact_number) = COALESCE(c.contact_name, c.contact_number))
           ORDER BY c.timestamp ASC LIMIT ?""".trimIndent(),
        arrayOf(olderThanMillis.toString(), limit.coerceIn(1, 50).toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(FollowUpCandidate(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
        }
    }

    fun recordOwnerChat(message: String) = recordConversation("Owner", null, OWNER_CHAT, "received", message, replied = true)
    fun recordAmaraChat(message: String) = recordConversation("Owner", null, OWNER_CHAT, "sent", message, replied = true)

    /**
     * Durable failure/recovery records (schema v13). Everything here is sanitized
     * before insert: callers pass already-redacted causes; the insert truncates and
     * re-checks the secret-shape detector so a raw secret can never reach disk.
     */
    @Synchronized
    fun recordFailure(
        taskId: String, runId: String, stepId: String, capability: String,
        targetPackage: String, stage: String, cause: String, retryable: Boolean,
        attemptCount: Int, screenEvidenceJson: String, correctiveAction: String,
        disposition: String, nextSafeAction: String,
    ): Long = writableDatabase.insertOrThrow("failure_records", null, ContentValues().apply {
        put("created_at", System.currentTimeMillis())
        put("task_id", taskId.take(200))
        put("run_id", runId.take(200))
        put("step_id", stepId.take(200))
        put("capability", capability.take(120))
        put("target_package", targetPackage.take(160))
        put("stage", stage.take(60))
        val sanitizedCause = Redactor.redact(compact(cause))
        put("cause", if (Redactor.containsSecretShape(sanitizedCause)) "[redacted secret-shaped failure detail]" else sanitizedCause.take(2_000))
        put("retryable", if (retryable) 1 else 0)
        put("attempt_count", attemptCount)
        put("screen_evidence_json", Redactor.redact(screenEvidenceJson).take(4_000))
        put("corrective_action", Redactor.redact(correctiveAction).take(1_000))
        put("disposition", disposition.take(60))
        put("next_safe_action", Redactor.redact(nextSafeAction).take(500))
    })

    @Synchronized
    fun recentFailures(limit: Int = 50): List<FailureRecord> = readableDatabase.query(
        "failure_records", null, null, null, null, null, "created_at DESC", limit.coerceIn(1, 500).toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(FailureRecord(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                taskId = cursor.getString(cursor.getColumnIndexOrThrow("task_id")),
                runId = cursor.getString(cursor.getColumnIndexOrThrow("run_id")),
                stepId = cursor.getString(cursor.getColumnIndexOrThrow("step_id")),
                capability = cursor.getString(cursor.getColumnIndexOrThrow("capability")),
                targetPackage = cursor.getString(cursor.getColumnIndexOrThrow("target_package")),
                stage = cursor.getString(cursor.getColumnIndexOrThrow("stage")),
                cause = cursor.getString(cursor.getColumnIndexOrThrow("cause")),
                retryable = cursor.getInt(cursor.getColumnIndexOrThrow("retryable")) == 1,
                attemptCount = cursor.getInt(cursor.getColumnIndexOrThrow("attempt_count")),
                screenEvidenceJson = cursor.getString(cursor.getColumnIndexOrThrow("screen_evidence_json")),
                correctiveAction = cursor.getString(cursor.getColumnIndexOrThrow("corrective_action")),
                disposition = cursor.getString(cursor.getColumnIndexOrThrow("disposition")),
                nextSafeAction = cursor.getString(cursor.getColumnIndexOrThrow("next_safe_action")),
            ))
        }
    }

    /** Redacted model-failure record for the typed brain gateway (contract §2). */
    @Synchronized
    fun recordBrainFailure(
        stage: String, model: String, requestId: String, responseHash: String,
        attemptCount: Int, retryable: Boolean, validationErrorsJson: String,
        correctiveAction: String, disposition: String,
        correlationId: String = "", terminalOutcome: String = "",
        ownerExplanation: String = "",
    ): Long = writableDatabase.insertOrThrow("brain_failures", null, ContentValues().apply {
        put("created_at", System.currentTimeMillis())
        put("stage", stage.take(80))
        put("model", model.take(120))
        put("request_id", requestId.take(200))
        put("response_hash", responseHash.take(64))
        put("attempt_count", attemptCount)
        put("retryable", if (retryable) 1 else 0)
        put("validation_errors_json", Redactor.redact(validationErrorsJson).take(3_000))
        put("corrective_action", Redactor.redact(correctiveAction).take(500))
        put("disposition", disposition.take(60))
        put("correlation_id", Redactor.redact(correlationId).take(200))
        put("terminal_outcome", terminalOutcome.take(60))
        val safeExplanation = Redactor.redact(ownerExplanation)
        put("owner_explanation", if (Redactor.containsSecretShape(safeExplanation)) "" else safeExplanation.take(500))
    })

    /**
     * Terminal update for ONE logical task failure: stamps the newest open
     * brain_failures row matching [correlationId] and [stage] (terminal_outcome
     * still empty) with the final outcome and a redacted owner-facing
     * explanation. NEVER inserts; returns false when no open row matches, so a
     * second finalize cannot double-write.
     */
    @Synchronized
    fun finalizeBrainFailure(
        correlationId: String, stage: String,
        terminalOutcome: String, ownerExplanation: String,
    ): Boolean {
        if (correlationId.isBlank()) return false
        val db = writableDatabase
        val rowId = db.query(
            "brain_failures", arrayOf("id"),
            "correlation_id = ? AND stage = ? AND terminal_outcome = ''",
            arrayOf(correlationId.take(200), stage.take(80)),
            null, null, "created_at DESC, id DESC", "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            ?: return false
        val safeExplanation = Redactor.redact(ownerExplanation)
        return db.update(
            "brain_failures",
            ContentValues().apply {
                put("terminal_outcome", terminalOutcome.take(60))
                put("owner_explanation", if (Redactor.containsSecretShape(safeExplanation)) "" else safeExplanation.take(500))
            },
            "id = ? AND terminal_outcome = ''",
            arrayOf(rowId.toString()),
        ) == 1
    }

    @Synchronized
    fun brainFailuresByCorrelation(correlationId: String, limit: Int = 50): List<BrainFailureRecord> {
        if (correlationId.isBlank()) return emptyList()
        return readableDatabase.query(
            "brain_failures", null,
            "correlation_id = ?", arrayOf(correlationId.take(200)),
            null, null, "created_at DESC", limit.coerceIn(1, 500).toString(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(brainFailureFromCursor(cursor)) } }
    }

    @Synchronized
    fun recentBrainFailures(limit: Int = 50): List<BrainFailureRecord> = readableDatabase.query(
        "brain_failures", null, null, null, null, null, "created_at DESC", limit.coerceIn(1, 500).toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(brainFailureFromCursor(cursor))
        }
    }

    private fun brainFailureFromCursor(cursor: android.database.Cursor) = BrainFailureRecord(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
        stage = cursor.getString(cursor.getColumnIndexOrThrow("stage")),
        model = cursor.getString(cursor.getColumnIndexOrThrow("model")),
        requestId = cursor.getString(cursor.getColumnIndexOrThrow("request_id")),
        responseHash = cursor.getString(cursor.getColumnIndexOrThrow("response_hash")),
        attemptCount = cursor.getInt(cursor.getColumnIndexOrThrow("attempt_count")),
        retryable = cursor.getInt(cursor.getColumnIndexOrThrow("retryable")) == 1,
        validationErrorsJson = cursor.getString(cursor.getColumnIndexOrThrow("validation_errors_json")),
        correctiveAction = cursor.getString(cursor.getColumnIndexOrThrow("corrective_action")),
        disposition = cursor.getString(cursor.getColumnIndexOrThrow("disposition")),
        correlationId = cursor.getString(cursor.getColumnIndexOrThrow("correlation_id")),
        terminalOutcome = cursor.getString(cursor.getColumnIndexOrThrow("terminal_outcome")),
        ownerExplanation = cursor.getString(cursor.getColumnIndexOrThrow("owner_explanation")),
    )

    /**
     * Migration/scrub for historical secret ingress: walks EVERY table and EVERY text
     * column of this database and rewrites credential-shaped content to redaction
     * markers. Idempotent; phone-identity columns keep their digits while any embedded
     * PIN/OTP/key material is removed. Returns the number of scrubbed cells.
     */
    @Synchronized
    fun scrubSecrets(vault: CredentialVault? = null): Int = SecretScrubber.scrub(writableDatabase, vault)

    fun getRecentSalesCount(days: Int): Int {
        val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM revenue_sales WHERE occurred_at >= ? AND state = 'ACTIVE'",
            arrayOf(cutoff.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun getRecentRevenue(days: Int): Long {
        val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        return readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(amount_ugx), 0) FROM revenue_sales WHERE occurred_at >= ? AND state = 'ACTIVE'",
            arrayOf(cutoff.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }

    companion object {
        const val DATABASE_NAME = "amara_memory.db"
        const val OWNER_CHAT = "owner_chat"

        private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(280)
    }
}
