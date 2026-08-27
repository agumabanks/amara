package co.sanaa.agent

import android.content.Intent
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.Initiator
import co.sanaa.agent.core.ModuleStateStore
import co.sanaa.agent.core.SideEffectOutcome
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.AutonomyController
import co.sanaa.agent.core.CommandExecutor
import co.sanaa.agent.core.ContactPermission
import co.sanaa.agent.core.RuntimePhase
import co.sanaa.agent.core.RuntimeStatusBus
import co.sanaa.agent.core.WorkStatus
import co.sanaa.agent.permissions.PermissionManager
import co.sanaa.agent.permissions.SelfHealingPermissionManager
import co.sanaa.agent.services.AgentService
import co.sanaa.agent.workers.AgentWorkScheduler
import co.sanaa.agent.workers.OwnerCommandWorker
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : FlutterActivity() {
    private lateinit var permissions: PermissionManager
    private lateinit var config: SecureConfig
    private lateinit var lightweightState: ModuleStateStore
    @Volatile private var certificationLevel: Int = 0
    private val runtime: AgentRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentRuntime.get(applicationContext)
    }
    private val statusRefresher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        co.sanaa.agent.core.RuntimeStatusRefresher(applicationContext)
    }
    private var pendingAttachmentResult: MethodChannel.Result? = null
    private var pendingContactResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        permissions = PermissionManager(this)
        config = SecureConfig(this)
        lightweightState = ModuleStateStore(this)
        // AgentRuntime performs durable-ledger recovery and secret/contact
        // migrations. Never construct it on Android's UI thread: a large
        // production ledger otherwise leaves Flutter on a blank launch frame.
        CoroutineScope(Dispatchers.IO).launch {
            val initialized = runtime
            certificationLevel = runCatching {
                co.sanaa.agent.certification.CertificationEvaluator.promoteToLevel(
                    co.sanaa.agent.certification.CertificationEvaluator.compute(initialized.certificationRunRecords()),
                )
            }.getOrDefault(0)
            runCatching { initialized.backend.registerAndSync() }
            statusRefresher.start()
            withContext(Dispatchers.Main) {
                ensureAgentRunning()
                handleTestCommand(intent)
            }
        }
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "permissionStatus" -> result.success(permissions.statusMap())
                "capabilityHealth" -> CoroutineScope(Dispatchers.IO).launch {
                    val permissionState = permissions.statusMap()
                    fun installed(packageName: String) = packageManager.getLaunchIntentForPackage(packageName) != null
                    val payload = permissionState + mapOf(
                        "accessibilityBound" to runtime.actions.isAvailable(),
                        "groqConfigured" to config.groqApiKey.isNotBlank(),
                        "sokoTerminalInstalled" to installed("com.soko24.soko_seller_terminal"),
                        "sokoBuyerInstalled" to installed("com.sanaa.soko24u.buyer"),
                        // Single authority: vault health only. The legacy plaintext
                        // config copy is never treated as a stored credential.
                        "sokoPinStored" to runtime.vault.status(AgentRuntime.SOKO_PIN_ID).let { it.configured && !it.locked },
                        "contactsAccess" to permissions.isContactsGranted(),
                        "screenshotSupported" to runtime.actions.isScreenshotSupported(),
                    )
                    withContext(Dispatchers.Main) { result.success(payload) }
                }
                "openAccessibility" -> { permissions.openAccessibilitySettings(); result.success(null) }
                "openBattery" -> { permissions.requestBatteryExemption(); result.success(null) }
                "openOverlay" -> { permissions.openOverlaySettings(); result.success(null) }
                "openNotifications" -> { permissions.openNotificationAccess(); result.success(null) }
                "requestNotifications" -> { permissions.requestNotificationPermission(); result.success(null) }
                "isAccessibilityBound" -> result.success(co.sanaa.agent.services.AccessibilityAgentService.isBound())
                "selfHealingDiagnose" -> CoroutineScope(Dispatchers.IO).launch {
                    val healer = SelfHealingPermissionManager(this@MainActivity)
                    val diagnoses = healer.diagnoseAll().map { d ->
                        mapOf("key" to d.key, "label" to d.label, "status" to d.status.name, "description" to d.description)
                    }
                    withContext(Dispatchers.Main) { result.success(diagnoses) }
                }
                "selfHealingFix" -> {
                    val key = call.argument<String>("key")
                    val healer = SelfHealingPermissionManager(this)
                    val diagnosis = healer.diagnoseAll().firstOrNull { it.key == key }
                    if (diagnosis != null) {
                        if (key == "storage") {
                            // Runtime dialog first; App Info is the fallback the healer opens.
                            permissions.requestStoragePermission()
                        } else if (key == "contacts") {
                            permissions.requestContactsPermission()
                        } else healer.openFix(diagnosis)
                    }
                    result.success(diagnosis != null)
                }
                "requestStoragePermission" -> result.success(permissions.requestStoragePermission())
                "requestContactsPermission" -> result.success(permissions.requestContactsPermission())
                "selfHealingFixAll" -> {
                    val healer = SelfHealingPermissionManager(this)
                    healer.fixAll()
                    result.success(true)
                }
                "pickContact" -> {
                    if (pendingContactResult != null) result.error("BUSY", "Contact picker already open", null)
                    else {
                        pendingContactResult = result
                        val intent = Intent(Intent.ACTION_PICK).apply {
                            type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
                        }
                        startActivityForResult(intent, PICK_CONTACT)
                    }
                }
                "pickWhatsAppContact" -> {
                    if (pendingContactResult != null) result.error("BUSY", "Picker already open", null)
                    else {
                        pendingContactResult = result
                        try {
                            val intent = Intent(Intent.ACTION_PICK).apply {
                                setPackage("com.whatsapp")
                                type = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
                            }
                            startActivityForResult(intent, PICK_CONTACT)
                        } catch (_: Exception) {
                            val intent = Intent(Intent.ACTION_PICK).apply {
                                type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
                            }
                            startActivityForResult(intent, PICK_CONTACT)
                        }
                    }
                }
                "startAgent" -> {
                    ContextCompat.startForegroundService(this, Intent(this, AgentService::class.java))
                    AgentWorkScheduler.scheduleAll(applicationContext, config.broadcastTime)
                    getSharedPreferences("agent_ui", MODE_PRIVATE).edit().putBoolean("setup_complete", true).apply()
                    result.success(true)
                }
                "setupComplete" -> {
                    val granted = permissions.statusMap().values.all { it }
                    result.success(getSharedPreferences("agent_ui", MODE_PRIVATE).getBoolean("setup_complete", false) || (granted && config.groqApiKey.isNotBlank()))
                }
                "saveGroqKey" -> {
                    val key = call.argument<String>("key").orEmpty().trim()
                    if (key.isBlank()) result.error("EMPTY_KEY", "Enter a Groq API key", null)
                    else { config.groqApiKey = key; result.success(true) }
                }
                "storeSokoPin" -> CoroutineScope(Dispatchers.IO).launch {
                    // Dedicated owner-only credential ingress. Unlike chat, this
                    // value is never journaled, displayed, logged, or sent to a
                    // model/backend. CredentialVault encrypts it with Android
                    // Keystore and clears the mutable CharArray in every outcome.
                    val supplied = call.argument<String>("pin").orEmpty()
                    if (!supplied.matches(Regex("^[0-9]{4,12}$"))) {
                        result.error("INVALID_PIN", "Enter the 4–12 digit Terminal PIN", null)
                    } else {
                        val outcome = runtime.vault.store(
                            AgentRuntime.SOKO_PIN_ID,
                            AgentRuntime.SOKO_TERMINAL_PACKAGE,
                            "terminal_login",
                            supplied.toCharArray(),
                        )
                        withContext(Dispatchers.Main) { when (outcome) {
                            is co.sanaa.agent.core.CredentialResult.Stored,
                            is co.sanaa.agent.core.CredentialResult.Rotated -> result.success(true)
                            else -> result.error(
                                "PIN_STORE_FAILED",
                                "The Terminal PIN was not saved; retry from Phone access",
                                null,
                            )
                        } }
                    }
                }
                "hasGroqKey" -> result.success(config.groqApiKey.isNotBlank())
                "testGroq" -> CoroutineScope(Dispatchers.IO).launch { runtime.groq.testConnection(result) }
                "syncConfig" -> CoroutineScope(Dispatchers.IO).launch {
                    runCatching { runtime.backend.registerAndSync() }
                        .onSuccess { withContext(Dispatchers.Main) { result.success(true) } }
                        .onFailure { withContext(Dispatchers.Main) { result.error("SYNC_ERROR", it.message, null) } }
                }
                "agentStatus" -> CoroutineScope(Dispatchers.IO).launch {
                    runCatching { runtime.backend.status().toString() }
                        .onSuccess { withContext(Dispatchers.Main) { result.success(it) } }
                        .onFailure { withContext(Dispatchers.Main) { result.error("STATUS_ERROR", it.message, null) } }
                }
                "chatHistory" -> CoroutineScope(Dispatchers.IO).launch {
                    val history = runtime.memory.ownerChatHistory().map {
                        mapOf("owner" to (it.direction == "received"), "text" to it.text, "timestamp" to it.timestamp)
                    }
                    withContext(Dispatchers.Main) { result.success(history) }
                }
                "latestTaskReceipt" -> CoroutineScope(Dispatchers.IO).launch {
                    val receipt = runtime.memory.latestTaskReceipt()?.let { task ->
                        mapOf(
                            "success" to task.success,
                            "status" to task.status,
                            "message" to task.message,
                            "observation" to task.observation,
                            "analysis" to task.analysis,
                            "steps" to task.steps.map { step -> mapOf(
                                "action" to step.action,
                                "reason" to step.reason,
                                "success" to step.success,
                                "result" to step.result,
                            ) },
                        )
                    }
                    withContext(Dispatchers.Main) { result.success(receipt) }
                }
                "runtimeStatusSnapshot" -> {
                    val snap = co.sanaa.agent.core.RuntimeStatusRegistry.snapshot()
                    val liveChip = RuntimeStatusBus.canonicalChipState()
                    val liveStatus = liveChip.status
                    val merged = mutableMapOf<String, Any>()
                    merged.putAll(snap.toMap())
                    liveStatus?.let { status ->
                        merged["targetApp"] = status.targetApp ?: snap.targetApp
                        merged["taskLabel"] = status.taskLabel
                        merged["stepIndex"] = status.stepIndex
                        merged["stepCount"] = status.stepCount
                        merged["retryCount"] = status.retryCount
                        status.blocker?.let { merged["detail"] = it }
                    }
                    merged["active"] = (snap.active || liveChip.working || liveChip.acting)
                    merged["blocked"] = (snap.blocked || liveChip.blocked)
                    if (!snap.isReady) merged["certificationLevel"] = certificationLevel
                    result.success(merged)
                }
                "autonomyStatus" -> {
                    val snap = co.sanaa.agent.core.RuntimeStatusRegistry.snapshot()
                    val liveChip = RuntimeStatusBus.canonicalChipState()
                    val liveStatus = liveChip.status
                    val detail = snap.detail.takeIf { it.isNotBlank() && it != "Ready for the next thing" }
                        ?: liveStatus?.blocker
                        ?: liveStatus?.taskLabel
                        ?: lightweightState.string(AutonomyController.DETAIL_KEY, "Ready for the next thing")
                    val phase = snap.phase.takeIf { snap.isReady } ?: liveChip.phase.name.lowercase()
                    result.success(
                        mapOf(
                            "phase" to phase,
                            "active" to (snap.active || liveChip.working || liveChip.acting),
                            "blocked" to (snap.blocked || liveChip.blocked),
                            "detail" to detail,
                            "targetApp" to (liveStatus?.targetApp ?: snap.targetApp),
                            "taskLabel" to (liveStatus?.taskLabel ?: snap.taskLabel),
                            "stepIndex" to (liveStatus?.stepIndex ?: snap.stepIndex),
                            "stepCount" to (liveStatus?.stepCount ?: snap.stepCount),
                            "retryCount" to (liveStatus?.retryCount ?: snap.retryCount),
                            // Cached after background runtime initialization so
                            // status polling can never wait on migrations/Keystore.
                            "certificationLevel" to (if (snap.isReady) snap.certificationLevel else certificationLevel),
                            "isReady" to snap.isReady,
                        ),
                    )
                }
                "missionControl" -> CoroutineScope(Dispatchers.IO).launch {
                    val payload = mapOf(
                        "approvals" to runtime.memory.pendingApprovals().map { approval -> mapOf(
                            "id" to approval.id, "capability" to approval.capability, "target" to approval.target,
                            "description" to approval.description, "before" to approval.beforeJson, "after" to approval.afterJson,
                            "risk" to approval.risk, "expiresAt" to approval.expiresAt,
                        ) },
                        "findings" to runtime.memory.openBusinessFindings().map { finding -> mapOf(
                            "id" to finding.id, "source" to finding.sourceApp, "subject" to finding.subject,
                            "issue" to finding.issue, "severity" to finding.severity, "confidence" to finding.confidence,
                            "evidence" to finding.evidence, "recommendation" to finding.recommendation, "status" to finding.status,
                        ) },
                        "schedules" to runtime.memory.recurringTasks().map { task -> mapOf(
                            "id" to task.id, "instruction" to task.instruction, "taskText" to task.taskText,
                            "rule" to task.rule, "nextRunAt" to task.nextRunAt, "enabled" to task.enabled,
                        ) },
                        "settings" to mapOf(
                            "proactiveReadOnlyAudits" to config.proactiveReadOnlyAudits,
                            "quietHoursStart" to config.quietHoursStart,
                            "quietHoursEnd" to config.quietHoursEnd,
                        ),
                        // Owner-visible data quality: knowledge conflicts surfaced by the
                        // evidence base, and artifact rows skipped as unparseable.
                        "dataQuality" to mapOf(
                            "knowledgeConflicts" to runtime.knowledge.recordedConflicts().size,
                            "artifactSkippedRows" to runtime.artifactStore.skippedRowIds().size,
                        ),
                        // Revenue workflow catalog with mechanical suite health (drift
                        // detection over the registered commercial workflows).
                        "revenueWorkflows" to co.sanaa.agent.workflows.WorkflowRegistry.revenueWorkflows().map { wf ->
                            val failures = co.sanaa.agent.workflows.WorkflowSimulator.runSuite(wf).count { !it.deliverableAccepted }
                            mapOf("id" to wf.id, "name" to wf.name, "scenarioFailures" to failures)
                        },
                    )
                    withContext(Dispatchers.Main) { result.success(payload) }
                }
                "activeCommitments" -> CoroutineScope(Dispatchers.IO).launch {
                    val payload = runtime.memory.activeCommitments().map { run -> mapOf(
                        "id" to run.id,
                        "phase" to run.phase.name,
                        "stepIndex" to run.stepIndex,
                        "decisionQuestion" to run.decisionQuestion,
                        "leaseOwner" to (run.leaseOwner ?: ""),
                        "updatedAt" to run.updatedAtMs,
                    ) }
                    withContext(Dispatchers.Main) { result.success(payload) }
                }
                "resolveCommitmentDecision" -> CoroutineScope(Dispatchers.IO).launch {
                    val id = call.argument<String>("id").orEmpty()
                    val answer = call.argument<String>("answer").orEmpty()
                    val resolved = runtime.memory.resolveWorkflowDecision(id, answer)
                    withContext(Dispatchers.Main) { result.success(resolved) }
                }
                "capabilityCatalog" -> result.success(
                    co.sanaa.agent.core.CapabilityCatalog.specs.values.map { spec -> mapOf(
                        "id" to spec.id,
                        "label" to spec.label,
                        "description" to spec.description,
                        "risk" to spec.risk.name,
                        "externalSideEffect" to spec.externalSideEffect,
                        "requiresFreshApproval" to spec.requiresApproval(),
                        "verifier" to spec.verifierKind.name,
                        "uiExposed" to spec.uiExposed,
                    ) },
                )
                "privacySettings" -> result.success(mapOf(
                    "telemetryOptIn" to config.telemetryOptIn,
                    "configSyncEnabled" to config.configSyncEnabled,
                    "artifactUploadOptIn" to config.artifactUploadOptIn,
                    "visionConsent" to config.visionConsent,
                    "retainUnmonitoredContactEvents" to config.retainUnmonitoredContactEvents,
                    "retentionDays" to config.retentionDays,
                ))
                "setPrivacySetting" -> {
                    val key = call.argument<String>("key").orEmpty()
                    val enabled = call.argument<Boolean>("value") == true
                    var known = true
                    when (key) {
                        "telemetryOptIn" -> config.telemetryOptIn = enabled
                        "configSyncEnabled" -> config.configSyncEnabled = enabled
                        "artifactUploadOptIn" -> config.artifactUploadOptIn = enabled
                        "visionConsent" -> config.visionConsent = enabled
                        "retainUnmonitoredContactEvents" -> config.retainUnmonitoredContactEvents = enabled
                        else -> known = false
                    }
                    if (known) result.success(true) else result.error("UNKNOWN_SETTING", "No such privacy setting: $key", null)
                }
                "setRetentionDays" -> {
                    val days = call.argument<Number>("days")?.toInt() ?: 90
                    config.retentionDays = days
                    // Retention takes effect immediately, not just on the next sweep.
                    CoroutineScope(Dispatchers.IO).launch { runCatching { runtime.memory.pruneExpiredData(config.retentionDays) } }
                    result.success(config.retentionDays)
                }
                "exportMyData" -> CoroutineScope(Dispatchers.IO).launch {
                    runCatching { runtime.memory.exportOwnerData().toString() }
                        .onSuccess { withContext(Dispatchers.Main) { result.success(it) } }
                        .onFailure { withContext(Dispatchers.Main) { result.error("EXPORT_FAILED", it.message, null) } }
                }
                "deleteMyData" -> CoroutineScope(Dispatchers.IO).launch {
                    runCatching { runtime.memory.deleteOwnerBusinessData() }
                        .onSuccess { withContext(Dispatchers.Main) { result.success(true) } }
                        .onFailure { withContext(Dispatchers.Main) { result.error("DELETE_FAILED", it.message, null) } }
                }
                "decideApproval" -> CoroutineScope(Dispatchers.IO).launch {
                    val id = call.argument<Number>("id")?.toLong() ?: -1L
                    val approve = call.argument<Boolean>("approve") == true
                    val decided = runtime.memory.decideApproval(id, approve)
                    withContext(Dispatchers.Main) { result.success(decided) }
                }
                "setScheduleEnabled" -> CoroutineScope(Dispatchers.IO).launch {
                    val id = call.argument<Number>("id")?.toLong() ?: -1L
                    val enabled = call.argument<Boolean>("enabled") == true
                    val changed = runtime.memory.setRecurringTaskEnabled(id, enabled)
                    if (changed && !enabled) AgentWorkScheduler.cancelRecurring(applicationContext, id)
                    else if (changed) runtime.memory.recurringTask(id)?.let { AgentWorkScheduler.scheduleRecurring(applicationContext, id, it.nextRunAt) }
                    withContext(Dispatchers.Main) { result.success(changed) }
                }
                "setProactiveReadOnlyAudits" -> {
                    config.proactiveReadOnlyAudits = call.argument<Boolean>("enabled") == true
                    AgentWorkScheduler.scheduleAll(applicationContext, config.broadcastTime)
                    result.success(true)
                }
                "stopCurrentTask" -> {
                    lightweightState.putBool(AutonomyController.CANCEL_KEY, true)
                    result.success(true)
                }
                "contactPermissions" -> CoroutineScope(Dispatchers.IO).launch {
                    // Single authority: the durable ContactDirectory is the listing source.
                    val payload = contactPayload(runtime)
                    withContext(Dispatchers.Main) { result.success(payload) }
                }
                "setContactPermission" -> CoroutineScope(Dispatchers.IO).launch {
                    val name = call.argument<String>("name").orEmpty()
                    val number = call.argument<String>("number")?.takeIf { it.isNotBlank() }
                    val isGroup = call.argument<Boolean>("isGroup") == true
                    val permission = call.argument<String>("permission") ?: "NONE"
                    val contactId = call.argument<String>("contactId")?.takeIf { it.isNotBlank() }
                    val perm = runCatching { ContactPermission.valueOf(permission) }.getOrDefault(ContactPermission.NONE)
                    // Durable identity first: grants land on the canonical directory row.
                    // The legacy JSON lists are migration input only and are never written.
                    val resolvedId = contactId?.takeIf { runtime.contacts.byId(it) != null } ?: when (val resolution =
                        runtime.contacts.resolve(co.sanaa.agent.core.ContactQuery(
                            name = name.trim().takeIf { it.isNotBlank() && co.sanaa.agent.core.Normalizer.normalizeUganda(number) == null },
                            phone = co.sanaa.agent.core.Normalizer.normalizeUganda(number), isGroup = isGroup,
                        ))) {
                        is co.sanaa.agent.core.Resolution.Unique -> resolution.entry.id
                        is co.sanaa.agent.core.Resolution.NotFound -> runtime.contacts.upsert(
                            co.sanaa.agent.core.DirectoryEntry(
                                id = "", displayName = name, normalizedPhone = co.sanaa.agent.core.Normalizer.normalizeUganda(number),
                                aliases = emptySet(), isGroup = isGroup, source = co.sanaa.agent.core.EntrySource.OWNER_CREATED,
                                lastVerifiedAt = System.currentTimeMillis(), ambiguity = co.sanaa.agent.core.Ambiguity.UNIQUE,
                                classification = co.sanaa.agent.core.Classification.UNKNOWN,
                                commercialConsent = co.sanaa.agent.core.CommercialConsent.UNKNOWN,
                                permissions = co.sanaa.agent.core.ContactDirectoryStore.operationsForLevel(co.sanaa.agent.core.ContactPermission.NONE),
                                whatsappSurfaceEvidence = null, revocationEvidence = null,
                            ),
                        ).id
                        else -> null // Ambiguous: fail closed, no grant lands until the owner disambiguates
                    }
                    if (resolvedId != null) {
                        when (perm) {
                            ContactPermission.NONE -> runtime.contacts.revokeAll(resolvedId, "owner revoked via app UI")
                            else -> runtime.contacts.authorizeLevel(resolvedId, perm)
                        }
                    }
                    withContext(Dispatchers.Main) { result.success(resolvedId != null || perm == ContactPermission.NONE) }
                }
                "setAllContactPermissions" -> CoroutineScope(Dispatchers.IO).launch {
                    val permission = call.argument<String>("permission") ?: "NONE"
                    val perm = runCatching { ContactPermission.valueOf(permission) }.getOrDefault(ContactPermission.NONE)
                    RuntimeStatusBus.report(contactWorkStatus(RuntimePhase.ACT, "Updating contact permissions"))
                    try {
                        var changed = 0
                        var skippedAmbiguous = 0
                        runtime.contacts.listAll().forEach { entry ->
                            if (entry.ambiguity != co.sanaa.agent.core.Ambiguity.UNIQUE) {
                                skippedAmbiguous++
                            } else {
                                if (perm == ContactPermission.NONE) {
                                    runtime.contacts.revokeAll(entry.id, "owner revoked all via app UI")
                                } else runtime.contacts.authorizeLevel(entry.id, perm)
                                changed++
                            }
                        }
                        withContext(Dispatchers.Main) {
                            result.success(mapOf("changed" to changed, "skippedAmbiguous" to skippedAmbiguous))
                        }
                        RuntimeStatusBus.clear(CONTACT_WORKER_ID)
                    } catch (failure: Exception) {
                        RuntimeStatusBus.report(
                            contactWorkStatus(RuntimePhase.FAILED, "Contact permission update failed", "Retry from Contact directory"),
                        )
                        withContext(Dispatchers.Main) {
                            result.error("CONTACT_PERMISSION_UPDATE_FAILED", failure.message ?: "Contact permission update failed", null)
                        }
                    }
                }
                "runtimeStatus" -> result.success(
                    RuntimeStatusBus.snapshot().map {
                        mapOf(
                            "workerId" to it.workerId, "targetApp" to (it.targetApp ?: ""),
                            "taskLabel" to it.taskLabel, "phase" to it.phase.name,
                            "stepIndex" to it.stepIndex, "stepCount" to it.stepCount,
                            "retryCount" to it.retryCount, "blocker" to (it.blocker ?: ""),
                        )
                    },
                )
                "discoverWhatsAppContacts" -> CoroutineScope(Dispatchers.IO).launch {
                    RuntimeStatusBus.report(contactWorkStatus(RuntimePhase.OBSERVE, "Discovering WhatsApp contacts"))
                    try {
                        importWhatsAppContacts(runtime)
                        val payload = contactPayload(runtime)
                        withContext(Dispatchers.Main) { result.success(payload) }
                        RuntimeStatusBus.clear(CONTACT_WORKER_ID)
                    } catch (failure: Exception) {
                        RuntimeStatusBus.report(
                            contactWorkStatus(RuntimePhase.FAILED, "WhatsApp discovery failed", "Open WhatsApp and retry discovery"),
                        )
                        withContext(Dispatchers.Main) {
                            result.error("WHATSAPP_DISCOVERY_FAILED", failure.message ?: "WhatsApp discovery failed", null)
                        }
                    }
                }
                "discoverAllContacts" -> CoroutineScope(Dispatchers.IO).launch {
                    if (!permissions.isContactsGranted()) {
                        withContext(Dispatchers.Main) {
                            result.error("CONTACTS_PERMISSION_REQUIRED", "Allow Contacts access, then retry discovery", null)
                        }
                    } else {
                        RuntimeStatusBus.report(contactWorkStatus(RuntimePhase.OBSERVE, "Discovering phone and WhatsApp contacts"))
                        try {
                            importAndroidContacts(runtime)
                            importWhatsAppContacts(runtime)
                            val payload = contactPayload(runtime)
                            withContext(Dispatchers.Main) { result.success(payload) }
                            RuntimeStatusBus.clear(CONTACT_WORKER_ID)
                        } catch (failure: Exception) {
                            RuntimeStatusBus.report(
                                contactWorkStatus(RuntimePhase.FAILED, "Contact discovery failed", "Check phone access and retry"),
                            )
                            withContext(Dispatchers.Main) {
                                result.error("CONTACT_DISCOVERY_FAILED", failure.message ?: "Contact discovery failed", null)
                            }
                        }
                    }
                }
                "pickAttachment" -> {
                    if (pendingAttachmentResult != null) result.error("PICKER_BUSY", "The attachment picker is already open", null)
                    else {
                        pendingAttachmentResult = result
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }, PICK_ATTACHMENT)
                    }
                }
                "sendWhatsAppAttachment" -> {
                    val uri = call.argument<String>("uri")?.let(Uri::parse)
                    val mimeType = call.argument<String>("mimeType").orEmpty().ifBlank { "application/octet-stream" }
                    val target = call.argument<String>("target").orEmpty().trim()
                    val caption = call.argument<String>("caption").orEmpty().trim()
                    if (uri?.scheme != "content" || target.isBlank()) result.error("INVALID_ATTACHMENT", "Choose a file and enter an exact WhatsApp contact or group", null)
                    else CoroutineScope(Dispatchers.IO).launch {
                        // Owner picked the exact file and target: explicit owner-initiated send,
                        // executed through the universal transaction like every other effect.
                        val outcome = runtime.queue.withExclusiveDeviceAction {
                            runtime.sideEffects.execute(
                                capabilityId = CapabilityIds.SEND_WHATSAPP_ATTACHMENT,
                                idempotencyKey = "attachment:${ContentHashing.hash("$target|$uri|$caption")}",
                                target = target,
                                content = if (caption.isBlank()) "attachment:${uri.lastPathSegment.orEmpty()}" else caption,
                                initiator = Initiator.OWNER_CHAT,
                                inputs = mapOf("target" to target, "content" to caption),
                                act = { runtime.actions.transacted { sendWhatsAppAttachment(target, uri, mimeType, caption) } },
                                verify = { runtime.targetVerifiers.verifyAttachment(target, caption) },
                            )
                        }
                        if (outcome is SideEffectOutcome.Verified) {
                            runtime.memory.recordAction("send_attachment", target, "WhatsApp", "Send attachment", "Sent an attachment${if (caption.isBlank()) "" else " with caption"} to $target.", "Verified in the target chat.", null, true)
                        } else if (outcome is SideEffectOutcome.Failed) {
                            runtime.memory.recordAction("send_attachment", target, "WhatsApp", "Send attachment", outcome.reason, "Not verified; no retry.", null, false)
                        }
                        withContext(Dispatchers.Main) { result.success(outcome.verified) }
                    }
                }
                "postWhatsAppMediaStatus" -> {
                    val uri = call.argument<String>("uri")?.let(Uri::parse)
                    val mimeType = call.argument<String>("mimeType").orEmpty().ifBlank { "image/*" }
                    val caption = call.argument<String>("caption").orEmpty().trim()
                    if (uri?.scheme != "content") result.error("INVALID_ATTACHMENT", "Choose a photo or video first", null)
                    else CoroutineScope(Dispatchers.IO).launch {
                        val outcome = runtime.queue.withExclusiveDeviceAction {
                            runtime.sideEffects.execute(
                                capabilityId = CapabilityIds.POST_WHATSAPP_MEDIA_STATUS,
                                idempotencyKey = "media-status:${ContentHashing.hash("$uri|$mimeType|$caption")}",
                                target = "status",
                                content = if (caption.isBlank()) "media:${uri.lastPathSegment.orEmpty()}" else caption,
                                initiator = Initiator.OWNER_CHAT,
                                act = { runtime.actions.transacted { postWhatsAppMediaStatus(uri, mimeType, caption) } },
                                verify = { runtime.targetVerifiers.verifyWhatsAppStatus(caption) },
                            )
                        }
                        if (outcome is SideEffectOutcome.Verified) {
                            runtime.memory.recordAction("post_media_status", null, "WhatsApp", "Post media Status", "Posted a WhatsApp media Status${if (caption.isBlank()) "." else " with caption."}", "Verified on the Status surface.", null, true)
                        }
                        withContext(Dispatchers.Main) { result.success(outcome.verified) }
                    }
                }
                "runDepartmentWorkflow" -> {
                    val workflowId = call.argument<String>("workflowId").orEmpty().trim()
                    val workflow = co.sanaa.agent.workflows.WorkflowRegistry.byId(workflowId)
                    if (workflow == null) {
                        result.error("UNKNOWN_WORKFLOW", "No registered department workflow '$workflowId'", null)
                    } else CoroutineScope(Dispatchers.IO).launch {
                        // Owner-initiated execution of a Phase E department workflow through
                        // the production executor (checkpoints, approvals, budget, artifacts).
                        val now = System.currentTimeMillis()
                        val budget = call.argument<Number>("budgetUgx")?.toLong()
                        val target = call.argument<String>("target").orEmpty()
                        val contract = co.sanaa.agent.core.work.WorkContract(
                            id = "wf-${workflow.id}-$now",
                            objective = workflow.name,
                            owner = "owner",
                            deliverables = workflow.requiredReportSections,
                            successCriteria = listOf("All required sections rendered", "Every consequential action verified"),
                            deadlineMs = now + 24 * 60 * 60 * 1_000L,
                            dependencies = emptyList(),
                            allowedSystems = setOf("device") + workflow.consequentialActions,
                            dataClassification = when (workflow.dataClassification) {
                                co.sanaa.agent.core.work.DataClassification.CUSTOMER_DATA -> co.sanaa.agent.core.work.DataClassification.CUSTOMER_DATA
                                else -> co.sanaa.agent.core.work.DataClassification.BUSINESS_INTERNAL
                            },
                            budgetUgx = budget ?: 25_000L,
                            approvalPolicy = co.sanaa.agent.core.work.ApprovalPolicy(requireFreshApprovalFor = workflow.consequentialActions),
                            verificationRules = listOf("Consequential actions verify through target-bound evidence"),
                            escalationConditions = workflow.escalationConditions,
                            followUpObligations = emptyList(),
                            createdAtMs = now,
                        )
                        val outcome = runtime.workflowExecutor.run(
                            workflow, contract,
                            inputs = if (target.isBlank()) emptyMap() else mapOf("target" to target),
                        )
                        val payload = when (outcome) {
                            is co.sanaa.agent.workflows.DepartmentWorkflowExecutor.Outcome.Completed -> mapOf(
                                "state" to "completed", "runId" to outcome.runId,
                                "sections" to outcome.sections.size, "verifiedActions" to outcome.verifiedActions,
                                "artifactRevisionId" to (outcome.artifactRevisionId ?: -1L),
                            )
                            is co.sanaa.agent.workflows.DepartmentWorkflowExecutor.Outcome.ParkedForOwnerDecision -> mapOf(
                                "state" to "awaiting_decision", "runId" to outcome.runId, "question" to outcome.question,
                            )
                            is co.sanaa.agent.workflows.DepartmentWorkflowExecutor.Outcome.RefusedByEnforcement -> mapOf(
                                "state" to "refused", "runId" to outcome.runId, "reason" to outcome.reason,
                            )
                        }
                        runtime.memory.recordAction("run_workflow", target.ifBlank { null }, "Amara",
                            "Run ${workflow.name}", "Workflow ${outcome.javaClass.simpleName}.", payload["state"].toString(), null,
                            payload["state"] == "completed")
                        withContext(Dispatchers.Main) { result.success(payload) }
                    }
                }
                "commercialStatus" -> CoroutineScope(Dispatchers.IO).launch {
                    // Reads the SAME canonical ledger as revenueDashboard and the brief.
                    val now = System.currentTimeMillis()
                    val daily = runtime.commercialPlanner.dailyStatus(now)
                    val weekly = runtime.commercialPlanner.weeklyStatus(now)
                    withContext(Dispatchers.Main) {
                        result.success(mapOf(
                            "dailyMet" to daily.met,
                            "verifiedInquiriesToday" to daily.verifiedInquiries,
                            "funnelAdvancesToday" to daily.funnelAdvances,
                            "weeklySalesContributed" to weekly.salesContributed,
                            "weeklyTargetMet" to weekly.met,
                            "basis" to daily.basis,
                        ))
                    }
                }
                "revenueDashboard" -> CoroutineScope(Dispatchers.IO).launch {
                    runCatching { runtime.revenueDashboard.build() }.fold(
                        onSuccess = { dashboard -> withContext(Dispatchers.Main) { result.success(dashboard) } },
                        onFailure = { withContext(Dispatchers.Main) { result.error("DASHBOARD_FAILED", it.message, null) } },
                    )
                }
                "commercialBrief" -> CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        val spec = runtime.commercialPlanner.endOfDayBrief(
                            nowMs = System.currentTimeMillis(),
                            observedFacts = call.argument<List<String>>("observed").orEmpty(),
                            diagnosis = call.argument<List<String>>("diagnosed").orEmpty(),
                            executedOutcomes = call.argument<List<String>>("actions").orEmpty(),
                            costsAndAttribution = call.argument<List<String>>("costs").orEmpty(),
                            lessons = call.argument<List<String>>("lessons").orEmpty(),
                            uncertainties = call.argument<List<String>>("uncertainties").orEmpty(),
                            tomorrowAdjustments = call.argument<List<String>>("tomorrow").orEmpty(),
                        )
                        runtime.artifactStore.commit(
                            artifactId = "commercial-brief-${co.sanaa.agent.core.commerce.DailyCommercialPlanner.startOfUtcDay(System.currentTimeMillis())}",
                            spec = spec, nowMs = System.currentTimeMillis(),
                        )
                    }.fold(
                        onSuccess = { revision -> withContext(Dispatchers.Main) {
                            result.success(mapOf("revisionId" to revision.id, "sha256" to revision.renderedBytesHash))
                        } },
                        onFailure = { withContext(Dispatchers.Main) {
                            result.error("BRIEF_REJECTED", it.message ?: "brief failed the rubric", null)
                        } },
                    )
                }
                // ---- Owner commerce channels: policy, consent, sales, opt-outs, experiments ----
                "commercialPolicyGet" -> CoroutineScope(Dispatchers.IO).launch {
                    val policy = runtime.commercialPolicy()
                    withContext(Dispatchers.Main) {
                        if (policy == null) result.success(null)
                        else result.success(policy.toJson())
                    }
                }
                "commercialPolicySave" -> CoroutineScope(Dispatchers.IO).launch {
                    val json = call.argument<String>("policyJson").orEmpty()
                    val policy = co.sanaa.agent.core.commerce.CommercialPolicy.fromJson(json)
                    if (policy == null) {
                        withContext(Dispatchers.Main) { result.error("INVALID_POLICY", "Policy failed validation; nothing was saved", null) }
                    } else {
                        val saved = runtime.revenueStore.savePolicy(policy, System.currentTimeMillis())
                        withContext(Dispatchers.Main) { result.success(saved) }
                    }
                }
                "grantContactConsent" -> CoroutineScope(Dispatchers.IO).launch {
                    val contactKey = call.argument<String>("contactKey").orEmpty()
                    val scope = call.argument<String>("scope").orEmpty().ifBlank { "outreach" }
                    val evidence = call.argument<String>("evidenceRef").orEmpty()
                    val expiresAt = call.argument<Number>("expiresAtMs")?.toLong()
                    val products = call.argument<List<String>>("products").orEmpty().toSet()
                    val channels = call.argument<List<String>>("channels").orEmpty().toSet()
                    if (contactKey.isBlank() || evidence.isBlank()) {
                        withContext(Dispatchers.Main) { result.error("INVALID_CONSENT", "Consent needs contact identity and durable evidence", null) }
                    } else {
                        val granted = runtime.revenueStore.grantContactConsent(
                            contactKey, "owner_ui", scope, System.currentTimeMillis(),
                            expiresAt, evidence, products, channels,
                        )
                        withContext(Dispatchers.Main) { result.success(granted) }
                    }
                }
                "revokeContactConsent" -> CoroutineScope(Dispatchers.IO).launch {
                    val contactKey = call.argument<String>("contactKey").orEmpty()
                    val reason = call.argument<String>("reason").orEmpty().ifBlank { "owner revocation" }
                    withContext(Dispatchers.Main) {
                        result.success(runtime.revenueStore.revokeContactConsent(contactKey, reason, System.currentTimeMillis()))
                    }
                }
                "consentLedger" -> CoroutineScope(Dispatchers.IO).launch {
                    withContext(Dispatchers.Main) {
                        result.success(runtime.revenueStore.consents().map { c -> mapOf(
                            "id" to c.id, "contact" to c.contactKey, "source" to c.source, "scope" to c.scope,
                            "grantedAt" to c.grantedAtMs, "expiresAt" to c.expiresAtMs, "revokedAt" to c.revokedAtMs,
                            "revokeReason" to c.revokeReason, "evidenceRef" to c.evidenceRef,
                            "products" to c.permittedProducts.sorted(), "channels" to c.permittedChannels.sorted(),
                        ) })
                    }
                }
                "confirmSaleByOwner" -> CoroutineScope(Dispatchers.IO).launch {
                    val r = runtime.revenueIngestion.confirmSaleByOwner(
                        saleRef = call.argument<String>("saleRef").orEmpty(),
                        contactKey = call.argument<String>("contactKey").orEmpty(),
                        productRef = call.argument<String>("productRef").orEmpty(),
                        amountUgx = call.argument<Number>("amountUgx")?.toLong() ?: 0L,
                        atMs = call.argument<Number>("atMs")?.toLong() ?: System.currentTimeMillis(),
                    )
                    withContext(Dispatchers.Main) {
                        when (r) {
                            is co.sanaa.agent.core.commerce.RevenueIngestion.IngestionResult.Recorded -> result.success(r.uniqueKey)
                            is co.sanaa.agent.core.commerce.RevenueIngestion.IngestionResult.Refused -> result.error("SALE_REFUSED", r.reason, null)
                        }
                    }
                }
                "recordCommercialOptOut" -> CoroutineScope(Dispatchers.IO).launch {
                    val contactKey = call.argument<String>("contactKey").orEmpty()
                    val reason = call.argument<String>("reason").orEmpty().ifBlank { "customer opt-out" }
                    withContext(Dispatchers.Main) {
                        result.success(runtime.revenueIngestion.recordOptOut(contactKey, reason, System.currentTimeMillis()))
                    }
                }
                "approveExperiment" -> CoroutineScope(Dispatchers.IO).launch {
                    // Launching an experiment is ALWAYS an explicit owner decision; a
                    // target-miss proposal can never launch itself.
                    val specId = call.argument<String>("experimentId").orEmpty()
                    val proposalJson = call.argument<String>("proposalJson").orEmpty()
                    withContext(Dispatchers.Main) {
                        runCatching {
                            val obj = org.json.JSONObject(proposalJson)
                            val spec = co.sanaa.agent.core.commerce.ExperimentEngine.LaunchSpec(
                                id = specId,
                                hypothesis = obj.getString("hypothesis"),
                                baseline = obj.optString("baseline", "prior concluded experiment"),
                                changeDimension = co.sanaa.agent.core.commerce.ExperimentSpec.ChangeDimension.valueOf(obj.optString("changeDimension", "PRODUCT_SELECTION")),
                                changeDescription = obj.getString("changeDescription"),
                                targetProductRef = obj.getString("targetProductRef"),
                                approvedAudience = obj.getString("approvedAudience"),
                                approvedChannel = obj.getString("approvedChannel"),
                                startAtMs = obj.optLong("startAtMs", System.currentTimeMillis()),
                                endAtMs = obj.getLong("endAtMs"),
                                minimumSampleCount = obj.optInt("minimumSampleCount", 1),
                                budgetUgx = obj.optLong("budgetUgx", 0),
                                communicationCapPerDay = obj.optInt("communicationCapPerDay", 1),
                                successMetric = obj.getString("successMetric"),
                                guardrailMetric = obj.optString("guardrailMetric", "opt-out rate stays under 1%"),
                                stopLossCondition = obj.optString("stopLossCondition", "guardrail >= 0.05"),
                                attributionWindowMs = obj.getLong("attributionWindowMs"),
                            )
                            when (val outcome = runtime.revenueOps.experiments.launch(spec, System.currentTimeMillis(), obj.optString("multivariateDesign").ifBlank { null })) {
                                is co.sanaa.agent.core.commerce.ExperimentEngine.LaunchResult.Running ->
                                    mapOf("state" to "running", "id" to outcome.id)
                                is co.sanaa.agent.core.commerce.ExperimentEngine.LaunchResult.Refused ->
                                    mapOf("state" to "refused", "reason" to outcome.reason)
                            }
                        }.fold(
                            onSuccess = { result.success(it) },
                            onFailure = { result.error("EXPERIMENT_REFUSED", it.message ?: "invalid experiment spec", null) },
                        )
                    }
                }
                "connectorGrantStatus" -> result.success(
                    runtime.connectorGrants.ids().map { runtime.connectorGrants.status(it) },
                )
                "setConnectorEnabled" -> {
                    val id = call.argument<String>("id").orEmpty()
                    val enabled = call.argument<Boolean>("enabled") == true
                    val changed = runCatching {
                        if (enabled) runtime.connectorGrants.reconsent(id) else { runtime.connectorGrants.revoke(id); true }
                    }.getOrDefault(false)
                    result.success(changed)
                }
                "exportLatestArtifact" -> {                    val artifactId = call.argument<String>("artifactId").orEmpty().trim()
                    if (artifactId.isBlank()) result.error("INVALID_ARTIFACT", "Name the artifact to export", null)
                    else runCatching {
                        co.sanaa.agent.core.artifacts.ArtifactDelivery.deliverLatest(applicationContext, runtime.artifactStore, artifactId)
                    }.fold(
                        onSuccess = { delivered ->
                            if (delivered == null) result.error("NO_ARTIFACT", "No revision stored for '$artifactId'", null)
                            else result.success(mapOf("uri" to delivered.first.toString(), "revisionId" to delivered.second.id))
                        },
                        onFailure = { result.error("EXPORT_FAILED", it.message ?: "artifact export failed", null) },
                    )
                }
                "submitTask" -> {
                    val command = call.argument<String>("command").orEmpty().trim()
                    val contactName = call.argument<String>("contactName").orEmpty().trim()
                    val contactPhone = call.argument<String>("contactPhone").orEmpty().trim()
                    val runAt = call.argument<Number>("runAt")?.toLong() ?: System.currentTimeMillis()
                    if (command.isBlank()) {
                        result.error("INVALID_TASK", "Tell Amara what outcome you want", null)
                    } else if (runAt > System.currentTimeMillis() + 30_000) {
                        val data = Data.Builder()
                            .putString(OwnerCommandWorker.COMMAND, command)
                            .putString(OwnerCommandWorker.CONTACT_NAME, contactName)
                            .putString(OwnerCommandWorker.CONTACT_PHONE, contactPhone)
                            .build()
                        val work = OneTimeWorkRequestBuilder<OwnerCommandWorker>()
                            .setInputData(data)
                            .setInitialDelay(runAt - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                            .build()
                        WorkManager.getInstance(applicationContext).enqueue(work)
                        result.success(mapOf("success" to true, "status" to "scheduled", "message" to "Task scheduled for the selected time."))
                    } else CoroutineScope(Dispatchers.IO).launch {
                        runCatching { CommandExecutor(applicationContext).execute(command, contactName, contactPhone).asMap() }
                            .onSuccess { withContext(Dispatchers.Main) { result.success(it) } }
                            .onFailure { withContext(Dispatchers.Main) { result.error("TASK_FAILED", it.message, null) } }
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    /** Imports address-book identities with NONE authority; discovery never grants messaging rights. */
    private fun importAndroidContacts(runtime: AgentRuntime): Int {
        if (!permissions.isContactsGranted()) return 0
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val seen = linkedSetOf<String>()
        var imported = 0
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberColumn = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val contactId = if (idColumn >= 0) cursor.getString(idColumn).orEmpty() else ""
                val name = if (nameColumn >= 0) cursor.getString(nameColumn).orEmpty().trim() else ""
                val rawNumber = if (numberColumn >= 0) cursor.getString(numberColumn).orEmpty() else ""
                if (name.isBlank()) continue
                val normalized = co.sanaa.agent.core.Normalizer.normalizeUganda(rawNumber)
                val dedupeKey = normalized ?: "${contactId.lowercase()}|${name.lowercase()}"
                if (!seen.add(dedupeKey)) continue
                runtime.contacts.upsert(
                    co.sanaa.agent.core.DirectoryEntry(
                        id = contactId.takeIf(String::isNotBlank)?.let { "android-contact-$it" }.orEmpty(),
                        displayName = name,
                        normalizedPhone = normalized,
                        aliases = emptySet(),
                        isGroup = false,
                        source = co.sanaa.agent.core.EntrySource.ANDROID_CONTACTS,
                        lastVerifiedAt = System.currentTimeMillis(),
                        ambiguity = co.sanaa.agent.core.Ambiguity.UNIQUE,
                        classification = co.sanaa.agent.core.Classification.UNKNOWN,
                        commercialConsent = co.sanaa.agent.core.CommercialConsent.UNKNOWN,
                        permissions = co.sanaa.agent.core.ContactDirectoryStore.operationsForLevel(ContactPermission.NONE),
                        whatsappSurfaceEvidence = null,
                        revocationEvidence = null,
                    ),
                )
                imported++
            }
        }
        return imported
    }

    /** Reads WhatsApp chat surfaces, classifies known groups, then merges into the same durable directory. */
    private suspend fun importWhatsAppContacts(runtime: AgentRuntime): Int {
        val groups = runtime.actions.discoverWhatsAppGroups().map(String::trim).filter(String::isNotBlank).toSet()
        val chats = runtime.actions.discoverWhatsAppChats().map(String::trim).filter(String::isNotBlank).toSet()
        var imported = 0
        (chats - groups).forEach { name ->
            if (upsertWhatsAppIdentity(runtime, name, false)) imported++
        }
        groups.forEach { name ->
            if (upsertWhatsAppIdentity(runtime, name, true)) imported++
        }
        return imported
    }

    private fun upsertWhatsAppIdentity(runtime: AgentRuntime, name: String, isGroup: Boolean): Boolean {
        val resolution = runtime.contacts.resolve(
            co.sanaa.agent.core.ContactQuery(name = name, isGroup = isGroup),
        )
        if (resolution is co.sanaa.agent.core.Resolution.Ambiguous) return false
        val existing = (resolution as? co.sanaa.agent.core.Resolution.Unique)?.entry
        runtime.contacts.upsert(
            co.sanaa.agent.core.DirectoryEntry(
                id = existing?.id.orEmpty(),
                displayName = name,
                normalizedPhone = existing?.normalizedPhone,
                aliases = existing?.aliases.orEmpty(),
                isGroup = isGroup,
                source = co.sanaa.agent.core.EntrySource.WHATSAPP,
                lastVerifiedAt = System.currentTimeMillis(),
                ambiguity = co.sanaa.agent.core.Ambiguity.UNIQUE,
                classification = existing?.classification ?: co.sanaa.agent.core.Classification.UNKNOWN,
                commercialConsent = existing?.commercialConsent ?: co.sanaa.agent.core.CommercialConsent.UNKNOWN,
                permissions = existing?.permissions
                    ?: co.sanaa.agent.core.ContactDirectoryStore.operationsForLevel(ContactPermission.NONE),
                whatsappSurfaceEvidence = "observed on WhatsApp chat list",
                revocationEvidence = existing?.revocationEvidence,
            ),
        )
        return true
    }

    private fun contactPayload(runtime: AgentRuntime): List<Map<String, Any>> =
        runtime.contacts.listAll().map { entry ->
            mapOf(
                "name" to entry.displayName,
                "number" to (entry.normalizedPhone ?: ""),
                "isGroup" to entry.isGroup,
                "permission" to entry.legacyLevel().name,
                "id" to entry.id,
                "source" to entry.source.name,
                "ambiguous" to (entry.ambiguity != co.sanaa.agent.core.Ambiguity.UNIQUE),
                "revoked" to (entry.revocationEvidence != null),
            )
        }

    private fun contactWorkStatus(phase: RuntimePhase, label: String, blocker: String? = null) = WorkStatus(
        workerId = CONTACT_WORKER_ID,
        targetApp = "Contacts / WhatsApp",
        taskLabel = label,
        phase = phase,
        stepIndex = 1,
        stepCount = 1,
        retryCount = 0,
        blocker = blocker,
    )

    private fun ensureAgentRunning() {
        val setupComplete = getSharedPreferences("agent_ui", MODE_PRIVATE).getBoolean("setup_complete", false)
        if (!setupComplete) return
        val agentRunning = (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getRunningServices(Int.MAX_VALUE).any { it.service.className == AgentService::class.java.name }
        if (!agentRunning) {
            ContextCompat.startForegroundService(this, Intent(this, AgentService::class.java))
        }
    }

    private fun handleTestCommand(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        var command = intent?.getStringExtra("test_command") ?: ""
        if (command.isBlank()) {
            val b64 = intent?.getStringExtra("test_command_b64") ?: ""
            if (b64.isNotBlank()) {
                command = runCatching { String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)) }.getOrDefault("")
            }
        }
        if (command.isBlank()) return
        intent?.removeExtra("test_command")
        intent?.removeExtra("test_command_b64")
        android.util.Log.i("SanaaTest", "Received debug test command")
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { CommandExecutor(applicationContext).execute(command, "", "").asMap() }
            android.util.Log.i("SanaaTest", "Command result: ${result.isSuccess}")
            result.onFailure { android.util.Log.e("SanaaTest", "Command failed: ${it.message}") }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestCommand(intent)
    }

    override fun onResume() {
        super.onResume()
        handleTestCommand(intent)
        if (::permissions.isInitialized) {
            MethodChannel(flutterEngine!!.dartExecutor.binaryMessenger, CHANNEL)
                .invokeMethod("permissionsChanged", permissions.statusMap())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_CONTACT) {
            val pending = pendingContactResult ?: return
            pendingContactResult = null
            if (resultCode != Activity.RESULT_OK || data?.data == null) {
                pending.success(null)
                return
            }
            val contactUri = data.data!!
            val cursor = contentResolver.query(contactUri, null, null, null, null)
            var name = ""
            var number = ""
            var contactIdOut = ""
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
                    if (nameIndex >= 0) name = it.getString(nameIndex) ?: ""
                    val idIndex = it.getColumnIndex(android.provider.ContactsContract.Contacts._ID)
                    if (idIndex >= 0) {
                        val contactId = it.getString(idIndex)
                        contactIdOut = contactId ?: ""
                        val numberCursor = contentResolver.query(
                            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(contactId),
                            null,
                        )
                        numberCursor?.use { nc ->
                            if (nc.moveToFirst()) {
                                val numIndex = nc.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                                if (numIndex >= 0) number = nc.getString(numIndex) ?: ""
                            }
                        }
                    }
                }
            }
            pending.success(mapOf("name" to name, "number" to number, "id" to contactIdOut))
            return
        }
        if (requestCode != PICK_ATTACHMENT) return
        val pending = pendingAttachmentResult ?: return
        pendingAttachmentResult = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            pending.success(null)
            return
        }
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        var displayName = "Attachment"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) displayName = cursor.getString(0) ?: displayName
        }
        pending.success(mapOf("uri" to uri.toString(), "name" to displayName, "mimeType" to (contentResolver.getType(uri) ?: "application/octet-stream")))
    }

    companion object {
        const val CHANNEL = "com.sanaa.agent/core"
        private const val PICK_ATTACHMENT = 7401
        private const val PICK_CONTACT = 7402
        private const val CONTACT_WORKER_ID = "contact-directory"
    }
}
