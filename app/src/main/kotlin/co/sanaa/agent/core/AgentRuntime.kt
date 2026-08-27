package co.sanaa.agent.core

import android.content.Context
import kotlinx.coroutines.launch
import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.actions.ActionVerifier
import co.sanaa.agent.actions.TargetBoundVerifiers
import co.sanaa.agent.api.BackendSync
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.api.SokoApiClient
import co.sanaa.agent.core.artifacts.SqliteArtifactStore
import co.sanaa.agent.core.commerce.RevenueOperatorRuntime
import co.sanaa.agent.core.knowledge.KnowledgeBase
import co.sanaa.agent.core.work.DurableBudgetMeter
import co.sanaa.agent.core.work.DurableWorkflowCoordinator
import co.sanaa.agent.core.work.SqliteWorkflowStore
import co.sanaa.agent.core.work.SpendMeter
import co.sanaa.agent.integrations.DurableRevocationLedger
import co.sanaa.agent.modules.*
import co.sanaa.agent.notifications.NotificationReporter
import co.sanaa.agent.core.SideEffectLedger
import co.sanaa.agent.workflows.AmaraApprovalGateway
import co.sanaa.agent.workflows.ContractScopedEvidence
import co.sanaa.agent.workflows.TransactionRoutedEffects
import co.sanaa.agent.workflows.DepartmentWorkflowExecutor

class AgentRuntime private constructor(context: Context, useEncryptedPrefs: Boolean) {
    val config = SecureConfig(context, useEncryptedPrefs = useEncryptedPrefs)
    val memory = AmaraMemory(context)
    val backend = BackendSync(context, config, memory)
    val groq = GroqClient(config, memory)
    val soko = SokoApiClient(config)
    val actions = AccessibilityActions(context, memory)
    val state = ModuleStateStore(context)
    val reporter = NotificationReporter(context)
    val verifier = ActionVerifier(actions, soko, backend)
    val queue = TaskQueue()
    val sideEffects = SideEffectRunner(SideEffectLedger.from(memory))
    val targetVerifiers = TargetBoundVerifiers(actions)

    /** Keystore-backed vault for app-specific credentials (never the device unlock PIN). */
    val vault = CredentialVault(context)
    /**
     * Single submission authority for the Soko staff PIN: refuses release while
     * locked and records every production login outcome back onto the vault.
     */
    val sokoCredentialGate = SokoCredentialGate(vault, SOKO_PIN_ID, SOKO_TERMINAL_PACKAGE)
    /** Canonical contact identity + permission directory (single authority for sends/monitoring). */
    val contacts = co.sanaa.agent.core.ContactDirectory(co.sanaa.agent.core.ContactDirectoryStore(context))

    /**
     * Single production release path for the Soko staff PIN (campaign handoff:
     * lead-wired 2026-08-26). Routes through [sokoCredentialGate] so a LOCKED
     * credential is never released nor decrypted; only when no intact unlocked
     * record exists does the fenced one-time legacy migration run.
     */
    fun sokoPin(): String {
        val gated = sokoCredentialGate.releaseForSubmission()
        if (gated.isNotEmpty()) return gated
        return sokoPinFrom(vault, config)
    }


    // Durable artifact history, connector revocations, and workflow persistence — all on
    // the production SQLite store, all reachable from this composition root.
    val artifactStore = SqliteArtifactStore(memory)
    val connectorRevocations = DurableRevocationLedger(memory)
    /** Owner-facing connector grants: revoke/re-consent flow through the durable ledger. */
    val connectorGrants = co.sanaa.agent.integrations.ConnectorGrants(
        specs = listOf(
            co.sanaa.agent.integrations.ConnectorSpec(
                id = "email.owner", domain = co.sanaa.agent.integrations.ConnectorDomain.EMAIL,
                declaredScopes = setOf("mailbox.read"), credentialRef = "OWNER_CREDENTIALS_EMAIL",
                maxRequestsPerMinute = 30, timeoutMs = 20_000,
            ),
            co.sanaa.agent.integrations.ConnectorSpec(
                id = "calendar.owner", domain = co.sanaa.agent.integrations.ConnectorDomain.CALENDAR,
                declaredScopes = setOf("calendar.read"), credentialRef = "OWNER_CREDENTIALS_CALENDAR",
                maxRequestsPerMinute = 30, timeoutMs = 20_000,
            ),
        ),
        ledger = connectorRevocations,
    )
    val knowledge = KnowledgeBase()
    val workflowStore = SqliteWorkflowStore(memory)
    val workflowBudget: SpendMeter = DurableBudgetMeter(memory)
    val workflowSpendReservations: co.sanaa.agent.core.work.SpendReservations =
        co.sanaa.agent.core.work.DurableSpendReservations(memory)
    // Single-authority revenue system: one composition root constructs every
    // commercial component; nothing else may build a parallel ledger.
    val revenueOps by lazy {
        RevenueOperatorRuntime.create(
            memory = memory,
            uncertainSideEffects = { memory.allSideEffectTransactions().count { it.state == SideEffectState.UNCERTAIN } },
        )
    }
    val revenueStore: co.sanaa.agent.core.commerce.RevenueStore get() = revenueOps.store
    /** Owner policy; fail-closed null until the owner configures one. */
    val commercialPolicy: () -> co.sanaa.agent.core.commerce.CommercialPolicy? get() = revenueOps.policy
    val commercialPlanner: co.sanaa.agent.core.commerce.DailyCommercialPlanner get() = revenueOps.commercialPlanner
    val revenueDashboard: co.sanaa.agent.core.commerce.RevenueDashboard get() = revenueOps.dashboard
    /** Authorized ingestion path for live business signals (inquiries, sales, opt-outs). */
    val revenueIngestion: co.sanaa.agent.core.commerce.RevenueIngestion get() = revenueOps.revenueIngestion
    /** Mechanical anti-spam guard; every customer-directed commercial send passes it. */
    val outreachGuard: co.sanaa.agent.core.commerce.OutreachGuard get() = revenueOps.outreachGuard
    val workflowApprovals by lazy { AmaraApprovalGateway(memory) }
    val workflowExecutor by lazy {
        DepartmentWorkflowExecutor(
            store = workflowStore,
            coordinator = DurableWorkflowCoordinator(workflowStore, SideEffectLedger.from(memory)),
            ledger = SideEffectLedger.from(memory),
            budget = workflowSpendReservations,
            evidence = ContractScopedEvidence(knowledge),
            approvals = workflowApprovals,
            effects = TransactionRoutedEffects(
                sideEffects = sideEffects, queue = queue,
                actions = { actions }, ownerPhone = { config.ownerPhone },
                approvalGateway = workflowApprovals,
                // UNBYPASSABLE commercial preflight: every customer-directed send
                // re-passes consent/suppression/caps/honesty at the final boundary.
                commercialPreflight = co.sanaa.agent.workflows.CommercialOutreachPreflight { customerTarget, content, capabilityId ->
                    val policyNow = revenueOps.policy()
                        ?: return@CommercialOutreachPreflight "no owner policy configured; missing policy fails closed"
                    if (policyNow.approvedChannels.isEmpty()) {
                        return@CommercialOutreachPreflight "no channels are owner-approved; missing policy fails closed"
                    }
                    val zone = policyNow.ownerZone()
                    val nowMs = System.currentTimeMillis()
                    val localTime = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime()
                    val sentTodayToCustomer = memory.allSideEffectTransactions().count {
                        it.target == customerTarget && it.state == SideEffectState.VERIFIED &&
                            java.time.Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate() ==
                            java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
                    }
                    val productRef = revenueOps.store.commercialActions().firstOrNull {
                        it.target == customerTarget
                    }?.let { row ->
                        runCatching { org.json.JSONObject(row.rankingJson).optString("productRef").ifBlank { null } }.getOrNull()
                    }
                    when (val verdict = outreachGuard.gate(
                        contactKey = customerTarget, productRef = productRef, channel = "whatsapp",
                        content = content, localTime = localTime,
                        messagesToCustomerToday = sentTodayToCustomer, messagesToCustomerInWindow = 0,
                        globalMessagesToday = revenueOps.store.executedActionCountToday(zone, nowMs), nowMs = nowMs,
                    )) {
                        is co.sanaa.agent.core.commerce.OutreachGuard.Verdict.Allow -> null
                        is co.sanaa.agent.core.commerce.OutreachGuard.Verdict.Block -> verdict.reason
                        is co.sanaa.agent.core.commerce.OutreachGuard.Verdict.SuppressAndBlock -> verdict.reason
                    }
                },
            ),
            artifactStore = artifactStore,
        )
    }

    /**
     * Earned-autonomy evidence: maps DURABLE workflow-run rows into certification run
     * records. The mapping is deliberately conservative — fields the ledger cannot prove
     * (device evidence, false-completion claims) stay false/zero so the computed level
     * can never overclaim. Local builds therefore cap at L1 until device soak exists.
     */
    fun certificationRunRecords(): List<co.sanaa.agent.certification.RunRecord> =
        memory.allWorkflowRuns().map { row ->
            co.sanaa.agent.certification.RunRecord(
                runId = row.id,
                workflowId = row.contractFingerprint.take(60),
                eligible = true,
                completed = row.phase == co.sanaa.agent.core.work.RunPhase.COMPLETED,
                falseCompletionClaim = false,
                duplicatedSideEffect = false,
                unauthorizedConsequentialAction = false,
                ownerInterventions = if (row.phase == co.sanaa.agent.core.work.RunPhase.AWAITING_DECISION) 1 else 0,
                totalActions = row.stepIndex.coerceAtLeast(0) + 1,
                auditReconstructable = row.checkpointJson.isNotBlank(),
                deviceEvidence = false, // provable only from on-device observation legs
            )
        }

    val morning = MorningBroadcastModule(config, soko, groq, backend, actions, verifier, state, reporter, sideEffects, memory = memory)
    val conversation = ConversationEngine(config, soko, groq, backend, actions, verifier, state, reporter, memory, queue, sideEffects, revenueIngestion = revenueOps.revenueIngestion)
    val listing = ListingIntelligenceModule(config, soko, groq, backend, actions, verifier, state, reporter, sideEffects, memory)
    val followUp = FollowUpEngine(config, backend, actions, verifier, state, memory, groq, sideEffects)
    // Every Soko credential consumer shares ONE vault-backed authority: the pin
    // provider releases only through the lockout gate; the auditor feeds login
    // outcomes (accepted/rejected) back onto the same vault slot.
    private val sokoCredentialAuditor = object : SokoCredentialAuditor {
        override fun onLoginAccepted() { runCatching { sokoCredentialGate.recordLoginAccepted() } }
        override fun onLoginRejected(staticRedactedReason: String) {
            runCatching { sokoCredentialGate.recordLoginRejected(staticRedactedReason) }
        }
    }
    val studioSharing = SokoStudioSharingModule(config, actions, groq, memory, reporter, sideEffects, pin = { sokoPin() })
    val sokoInventory = SokoInventoryModule(config, actions, memory, pin = { sokoPin() })
    val sokoIntelligence = SokoIntelligenceModule(config, actions, memory, groq, pin = { sokoPin() }, credentialAudit = sokoCredentialAuditor)
    val sokoFull = SokoFullIntelligence(config, actions, memory, groq, pin = { sokoPin() }, credentialAudit = sokoCredentialAuditor)
    val sokoEdit = SokoEditModule(config, actions, memory)
    val visualListing = VisualListingIntelligence(groq, memory)
    val tiktok = TikTokSkill(config, actions, memory, groq)
    val health = AgentHealthMonitor(context, groq, backend, actions, state, reporter, memory = memory, retentionDays = { config.retentionDays })

    private val readyDeferred: kotlinx.coroutines.CompletableDeferred<AgentRuntime> =
        kotlinx.coroutines.CompletableDeferred()

    private val initScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /**
     * Performs the heavy one-time initialisation (DB open + migrations + secret scrubs)
     * on a background dispatcher. Constructor field initialisers above already create the
     * composition root in <50ms, so the AccessibilityService bind path never blocks the
     * main thread waiting on this. Awaiters suspend until completion.
     */
    private fun runHeavyInit() {
        initScope.launch {
            try {
                memory.markInterruptedTasks()
                memory.markOrphanedTransactionsUncertain()
                runCatching { memory.scrubSecrets(vault) }
                    .onFailure { android.util.Log.w("AgentRuntime", "Memory secret scrub failed: ${it.message}") }
                runCatching { contacts.migrateLegacyIfConfigured(config) }
                    .onFailure { android.util.Log.w("AgentRuntime", "Contact directory legacy migration failed: ${it.message}") }
                runCatching { contacts.scrubSecrets() }
                    .onFailure { android.util.Log.w("AgentRuntime", "Contact directory secret scrub failed: ${it.message}") }
                co.sanaa.agent.core.ContactDirectoryProvider.instance = contacts
                readyDeferred.complete(this@AgentRuntime)
            } catch (t: Throwable) {
                readyDeferred.completeExceptionally(t)
            }
        }
    }

    /**
     * Suspends until the heavy init has completed. Safe to call from any dispatcher;
     * when already ready it returns immediately. This is the only path that production
     * code on a coroutine should use; the synchronous [get] is reserved for the
     * composition-root + tests.
     */
    suspend fun awaitReady(): AgentRuntime = if (readyDeferred.isCompleted) {
        this
    } else {
        readyDeferred.await()
        this
    }

    /** Non-blocking readiness check; safe to call from the main thread. */
    fun isReady(): Boolean = readyDeferred.isCompleted

    companion object {
        const val SOKO_PIN_ID = "soko_staff_pin"
        const val SOKO_TERMINAL_PACKAGE = "com.soko24.soko_seller_terminal"

        /**
         * Soko staff PIN access for the exact Terminal login surface. The ONLY
         * production authority for this credential. A locked slot is never
         * released nor migrated; a blank/short value can never reach
         * authentication. Returns "" when unconfigured, incomplete/corrupted,
         * or locked (owner must reconfigure through the guarded flow).
         */
        fun sokoPinFrom(vault: CredentialVault, config: SecureConfig): String {
            if (vault.isLocked(SOKO_PIN_ID)) return ""
            when (val retrieval = vault.retrieve(SOKO_PIN_ID, SOKO_TERMINAL_PACKAGE)) {
                is CredentialRetrieval.Secret -> return consumeInPlace(retrieval)
                else -> Unit
            }
            // [SOKO-PIN-AUTHORITY-BEGIN]
            // Documented ONE-TIME legacy migration: the only sanctioned read of
            // the plaintext config copy anywhere in production source. The
            // plaintext is cleared immediately after protected storage succeeds;
            // storing over any partial/corrupted record heals it.
            val legacy = config.sokoTerminalPin
            if (legacy.length in 4..8) {
                // Plaintext is cleared ONLY after protected storage provably
                // succeeded; a failed store keeps the copy for the next attempt.
                val stored = vault.store(SOKO_PIN_ID, SOKO_TERMINAL_PACKAGE, "terminal_login", legacy.toCharArray())
                if (stored == CredentialResult.Stored || stored == CredentialResult.Rotated) {
                    config.sokoTerminalPin = ""
                }
                when (val migrated = vault.retrieve(SOKO_PIN_ID, SOKO_TERMINAL_PACKAGE)) {
                    is CredentialRetrieval.Secret -> return consumeInPlace(migrated)
                    else -> return ""
                }
            }
            // [SOKO-PIN-AUTHORITY-END]
            return ""
        }

        /** Consumes the decrypted buffer in place: one String handout, zero residue. */
        private fun consumeInPlace(secret: CredentialRetrieval.Secret): String {
            val pin = secret.value.concatToString()
            secret.value.fill('\u0000')
            return pin
        }

        @Volatile private var instance: AgentRuntime? = null

        /**
         * Returns the singleton (creating it on first call). The composition root +
         * light field initialisers run synchronously here; the DB-touching heavy init
         * is dispatched to IO inside the constructor. This call must remain
         * main-thread-safe — never read [AmaraMemory] / touch the DB here.
         */
        fun get(context: Context): AgentRuntime = get(context, useEncryptedPrefs = true)

        /**
         * Test-friendly overload: Robolectric's Keystore support is partial, so
         * tests that need to construct [AgentRuntime] (e.g. asserting the
         * composition-root cost) pass [useEncryptedPrefs] = false to skip the
         * EncryptedSharedPreferences path. Production callers always go through
         * the single-argument [get].
         */
        fun get(context: Context, useEncryptedPrefs: Boolean): AgentRuntime = instance ?: synchronized(this) {
            instance ?: AgentRuntime(context.applicationContext, useEncryptedPrefs).also {
                instance = it
                it.runHeavyInit()
            }
        }

        /** Test seam: clear the cached singleton so a fresh init runs. */
        internal fun resetForTest() {
            synchronized(this) { instance = null }
        }

        @androidx.annotation.VisibleForTesting
        internal fun isInitializedForTest(): Boolean = instance != null
    }
}
