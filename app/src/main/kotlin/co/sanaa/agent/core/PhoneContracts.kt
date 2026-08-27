package co.sanaa.agent.core

enum class ActionRisk {
    OBSERVE,
    RECOMMEND,
    LOW_IMPACT_CHANGE,
    EXTERNAL_COMMUNICATION,
    HIGH_IMPACT_CHANGE,
    SECURITY_IDENTITY,
    FINANCIAL;

    val requiresFreshApproval: Boolean
        get() = this == HIGH_IMPACT_CHANGE || this == SECURITY_IDENTITY || this == FINANCIAL
}

/** Who may start this capability. Proactive work is always narrower than owner chat. */
enum class Initiator {
    OWNER_CHAT,
    RECURRING_SCHEDULE,
    PROACTIVE_AUDIT,
    INTERNAL_RUNTIME,
    /**
     * An explicitly authorized department-workflow execution. This initiator is only
     * legal on capabilities that list it in [CapabilitySpec.allowedInitiators], and
     * [SideEffectRunner] unconditionally demands a fresh, exact-bound approval record —
     * validated immediately before acting — whenever it is used. It exists so bounded,
     * owner-approved workflows can run without pretending to be owner chat or a blind
     * recurring schedule; revenue targets never broaden authority beyond this gate.
     */
    AUTHORIZED_WORKFLOW,
}

/**
 * How authorization is obtained.
 * NONE            — observation/recommendation only, no external change.
 * EXPLICIT_OWNER_LANGUAGE — allowed when the owner's command names the action class.
 * FRESH_EXACT     — requires an unexpired approval record bound to target+content.
 */
enum class ApprovalRequirement { NONE, EXPLICIT_OWNER_LANGUAGE, FRESH_EXACT }

enum class TargetRule {
    NONE,                    // no target needed
    NON_BLANK,               // any non-blank target string
    WHATSAPP_TARGET_NAMED_IN_COMMAND, // target must appear in owner text
    LISTING_NAME_REQUIRED,
}

enum class IdempotencyStrategy {
    NOT_APPLICABLE,          // read-only
    OCCURRENCE_KEY,          // one key per schedule occurrence
    APPROVAL_BOUND_KEY,      // key derived from the consumed approval id
    CONTENT_HASH_KEY,        // key from normalized target+content hash
}

enum class VerifierKind {
    SCREEN_OBSERVATION,      // read-only: observed screen state is the result
    TARGET_CONTENT_DELIVERY, // exact target chat shows content + delivery state in window
    PUBLICATION_STATE,       // public surface shows published item in window
    FIELD_REOPEN_COMPARE,    // saved form reopened and field compared
}

enum class SideEffectRetryPolicy {
    NOT_APPLICABLE,          // read-only
    ZERO_BLIND_RETRY,        // never repeat after acting/uncertain without proof of non-effect
}

enum class RecoveryEligibility {
    SELF_RECOVERING,         // trusted skill performs bounded internal recovery
    READ_ONLY_RECOVERY,      // controller may replan with read-only steps
    TERMINAL_ON_FAILURE,     // stop; report; never auto-recover
}

enum class ProtectedScreenBehavior { NOT_APPLICABLE, FAIL_CLOSED_AND_HANDOFF }

enum class TelemetryHandling { DEVICE_LOCAL_ONLY, DEVICE_LOCAL_PLUS_REDACTED_OPTIN_EXPORT }

/**
 * The single authoritative typed definition of an Amara capability.
 * Policy checks, planner exposure, executor routing validation, recovery eligibility,
 * mission-control labels, receipts, and contract tests all derive from this spec.
 */
data class CapabilitySpec(
    val id: String,
    val label: String,
    val description: String,
    val risk: ActionRisk,
    val inputSchema: String,
    val outputSchema: String,
    val requiredPermissions: Set<String>,
    val allowedInitiators: Set<Initiator>,
    val externalSideEffect: Boolean,
    val approvalRequirement: ApprovalRequirement,
    val standingPolicyEligible: Boolean,
    val precondition: String,
    val targetRule: TargetRule,
    val idempotencyStrategy: IdempotencyStrategy,
    val verifierKind: VerifierKind,
    val timeoutMs: Long,
    val retryPolicy: SideEffectRetryPolicy,
    val recovery: RecoveryEligibility,
    val supportedPackages: Set<String>,
    val protectedScreenBehavior: ProtectedScreenBehavior,
    val receiptFields: Set<String>,
    val telemetry: TelemetryHandling,
    val uiExposed: Boolean,
    /** Exact sentence given to the planner; null hides the capability from planning. */
    val plannerPromptLine: String?,
    /** True when a failed run may replan using this action (read-only helpers). */
    val usableInRecoveryPlan: Boolean,
) {
    /** Parsed, enforceable input schema derived once from the declared description. */
    val parsedInputSchema: TypedSchema by lazy { TypedSchema.parse(inputSchema) }

    /** Parsed output contract — drift in declared outputs fails loudly at first touch. */
    val parsedOutputSchema: TypedSchema by lazy { TypedSchema.parse(outputSchema) }

    /** Authorization policy derived from the spec, independent of any model prose. */
    fun requiresApproval(): Boolean = approvalRequirement == ApprovalRequirement.FRESH_EXACT

    fun mayRunProactively(): Boolean = Initiator.PROACTIVE_AUDIT in allowedInitiators
}

private fun observeSpec(
    id: String, label: String, description: String,
    selfRecovering: Boolean = false, proactive: Boolean = false,
    packages: Set<String> = emptySet(),
    plannerLine: String? = null,
): CapabilitySpec = CapabilitySpec(
    id = id, label = label, description = description, risk = ActionRisk.OBSERVE,
    inputSchema = "{}", outputSchema = "{summary:string, evidence:string[]}",
    requiredPermissions = setOf("accessibility_bound"),
    allowedInitiators = buildSet {
        add(Initiator.OWNER_CHAT)
        if (proactive) add(Initiator.PROACTIVE_AUDIT)
    },
    externalSideEffect = false,
    approvalRequirement = ApprovalRequirement.NONE,
    standingPolicyEligible = proactive,
    precondition = "Accessibility service enabled and bound.",
    targetRule = TargetRule.NONE,
    idempotencyStrategy = IdempotencyStrategy.NOT_APPLICABLE,
    verifierKind = VerifierKind.SCREEN_OBSERVATION,
    timeoutMs = 120_000,
    retryPolicy = SideEffectRetryPolicy.NOT_APPLICABLE,
    recovery = if (selfRecovering) RecoveryEligibility.SELF_RECOVERING else RecoveryEligibility.READ_ONLY_RECOVERY,
    supportedPackages = packages,
    protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
    receiptFields = setOf("capability", "observed_state", "coverage", "confidence", "timestamp"),
    telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
    uiExposed = true,
    plannerPromptLine = plannerLine,
    usableInRecoveryPlan = plannerLine != null,
)

private fun communicationSpec(
    id: String, label: String, description: String,
    verifierKind: VerifierKind,
    idempotency: IdempotencyStrategy,
    packages: Set<String>,
    plannerLine: String?,
    risk: ActionRisk = ActionRisk.EXTERNAL_COMMUNICATION,
    approvalRequirement: ApprovalRequirement = ApprovalRequirement.EXPLICIT_OWNER_LANGUAGE,
    /**
     * When true, an [Initiator.AUTHORIZED_WORKFLOW] execution is also allowed. This is
     * NOT a control weakening: the runner still demands a fresh exact-bound approval
     * record validated immediately before acting for every workflow-initiated run, so
     * each workflow send remains owner-authorized per target+content.
     */
    workflowAuthorized: Boolean = false,
    /** Publication surfaces the owner enabled as standing daily policy (Status/TikTok draft legs). */
    recurringAllowed: Boolean = false,
): CapabilitySpec = CapabilitySpec(
    id = id, label = label, description = description, risk = risk,
    inputSchema = "{target:nonblank!, message:string!}",
    outputSchema = "{verified:boolean, evidence:{deliveryState:string, timestamp:long}}",
    requiredPermissions = setOf("accessibility_bound"),
    allowedInitiators = buildSet {
        add(Initiator.OWNER_CHAT)
        if (workflowAuthorized) { add(Initiator.AUTHORIZED_WORKFLOW); add(Initiator.RECURRING_SCHEDULE) }
        else if (recurringAllowed) add(Initiator.RECURRING_SCHEDULE)
    },
    externalSideEffect = true,
    approvalRequirement = approvalRequirement,
    standingPolicyEligible = false,
    precondition = "Target app installed; Accessibility bound; exact target resolvable.",
    targetRule = TargetRule.WHATSAPP_TARGET_NAMED_IN_COMMAND,
    idempotencyStrategy = idempotency,
    verifierKind = verifierKind,
    timeoutMs = 90_000,
    retryPolicy = SideEffectRetryPolicy.ZERO_BLIND_RETRY,
    recovery = RecoveryEligibility.TERMINAL_ON_FAILURE,
    supportedPackages = packages,
    protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
    receiptFields = setOf("capability", "target", "contentHash", "deliveryState", "evidenceTimestamp", "transactionId"),
    telemetry = TelemetryHandling.DEVICE_LOCAL_PLUS_REDACTED_OPTIN_EXPORT,
    uiExposed = true,
    plannerPromptLine = plannerLine,
    usableInRecoveryPlan = false,
)

private fun internalCommunicationSpec(
    id: String, label: String, description: String,
    verifierKind: VerifierKind,
    packages: Set<String>,
    workflowAuthorized: Boolean = false,
): CapabilitySpec = CapabilitySpec(
    id = id, label = label, description = description,
    risk = ActionRisk.EXTERNAL_COMMUNICATION,
    inputSchema = "{target:nonblank!, content:string!}",
    outputSchema = "{verified:boolean, deliveryState:string?, evidenceTimestamp:long}",
    requiredPermissions = setOf("accessibility_bound"),
    allowedInitiators = if (workflowAuthorized) setOf(Initiator.INTERNAL_RUNTIME, Initiator.AUTHORIZED_WORKFLOW)
        else setOf(Initiator.INTERNAL_RUNTIME),
    externalSideEffect = true,
    approvalRequirement = ApprovalRequirement.NONE,
    standingPolicyEligible = false,
    precondition = "Authorized wrapper only; exact target resolved before dispatch.",
    targetRule = TargetRule.NON_BLANK,
    idempotencyStrategy = IdempotencyStrategy.CONTENT_HASH_KEY,
    verifierKind = verifierKind,
    timeoutMs = 90_000,
    retryPolicy = SideEffectRetryPolicy.ZERO_BLIND_RETRY,
    recovery = RecoveryEligibility.TERMINAL_ON_FAILURE,
    supportedPackages = packages,
    protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
    receiptFields = setOf("capability", "target", "contentHash", "deliveryState", "evidenceTimestamp", "transactionId"),
    telemetry = TelemetryHandling.DEVICE_LOCAL_PLUS_REDACTED_OPTIN_EXPORT,
    uiExposed = false,
    plannerPromptLine = null,
    usableInRecoveryPlan = false,
)

private fun internalPublicationSpec(
    id: String, label: String, description: String,
    packages: Set<String>,
): CapabilitySpec = CapabilitySpec(
    id = id, label = label, description = description,
    risk = ActionRisk.EXTERNAL_COMMUNICATION,
    inputSchema = "{mediaUri:nonblank!, mimeType:string!, caption:string!}",
    outputSchema = "{verified:boolean, surfacePackage:string, evidenceTimestamp:long}",
    requiredPermissions = setOf("accessibility_bound"),
    allowedInitiators = setOf(Initiator.OWNER_CHAT, Initiator.RECURRING_SCHEDULE),
    externalSideEffect = true,
    approvalRequirement = ApprovalRequirement.EXPLICIT_OWNER_LANGUAGE,
    standingPolicyEligible = true,
    precondition = "Owner explicitly requested the publication or enabled the exact standing policy.",
    targetRule = TargetRule.NON_BLANK,
    idempotencyStrategy = IdempotencyStrategy.CONTENT_HASH_KEY,
    verifierKind = VerifierKind.PUBLICATION_STATE,
    timeoutMs = 90_000,
    retryPolicy = SideEffectRetryPolicy.ZERO_BLIND_RETRY,
    recovery = RecoveryEligibility.TERMINAL_ON_FAILURE,
    supportedPackages = packages,
    protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
    receiptFields = setOf("capability", "surfacePackage", "contentHash", "publicationState", "evidenceTimestamp", "transactionId"),
    telemetry = TelemetryHandling.DEVICE_LOCAL_PLUS_REDACTED_OPTIN_EXPORT,
    uiExposed = true,
    plannerPromptLine = null,
    usableInRecoveryPlan = false,
)

/**
 * One authoritative registry. Secondary representations (planner prompt lines,
 * recovery allow-lists, UI labels) are derived here so they cannot drift.
 */
object CapabilityCatalog {

    private fun sokoRead(id: String, label: String, description: String, plannerLine: String? = null) =
        observeSpec(
            id, label, description, selfRecovering = true,
            packages = setOf("com.soko24.soko_seller_terminal"),
            plannerLine = plannerLine ?: "- $id: $description",
        )

    val specs: Map<String, CapabilitySpec> = listOf(
        CapabilitySpec(
            id = "respond", label = "Answer the owner",
            description = "Reply to the owner without touching the phone.",
            risk = ActionRisk.RECOMMEND, inputSchema = "{message:string}", outputSchema = "{text:string}",
            requiredPermissions = emptySet(), allowedInitiators = Initiator.entries.toSet(),
            externalSideEffect = false, approvalRequirement = ApprovalRequirement.NONE,
            standingPolicyEligible = false, precondition = "None.", targetRule = TargetRule.NONE,
            idempotencyStrategy = IdempotencyStrategy.NOT_APPLICABLE, verifierKind = VerifierKind.SCREEN_OBSERVATION,
            timeoutMs = 10_000, retryPolicy = SideEffectRetryPolicy.NOT_APPLICABLE,
            recovery = RecoveryEligibility.READ_ONLY_RECOVERY, supportedPackages = emptySet(),
            protectedScreenBehavior = ProtectedScreenBehavior.NOT_APPLICABLE,
            receiptFields = setOf("capability", "message"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = true, plannerPromptLine = "- respond (answer the owner without touching the phone)",
            usableInRecoveryPlan = true,
        ),
        CapabilitySpec(
            id = "open_app", label = "Open app",
            description = "Launch an installed app and verify it reached the foreground.",
            risk = ActionRisk.OBSERVE, inputSchema = "{app:nonblank!}", outputSchema = "{foregroundPackage:string}",
            requiredPermissions = setOf("accessibility_bound"), allowedInitiators = setOf(Initiator.OWNER_CHAT, Initiator.RECURRING_SCHEDULE),
            externalSideEffect = false, approvalRequirement = ApprovalRequirement.NONE,
            standingPolicyEligible = false, precondition = "App installed.",
            targetRule = TargetRule.NON_BLANK,
            idempotencyStrategy = IdempotencyStrategy.NOT_APPLICABLE, verifierKind = VerifierKind.SCREEN_OBSERVATION,
            timeoutMs = 15_000, retryPolicy = SideEffectRetryPolicy.NOT_APPLICABLE,
            recovery = RecoveryEligibility.READ_ONLY_RECOVERY, supportedPackages = emptySet(),
            protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
            receiptFields = setOf("capability", "app", "foregroundPackage"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = true, plannerPromptLine = "- open_app, read_screen, scroll_up, scroll_down, wait",
            usableInRecoveryPlan = true,
        ),
        observeSpec("read_screen", "Read screen", "Read visible screen text."),
        observeSpec("scroll_up", "Scroll up", "Scroll the visible list up."),
        observeSpec("scroll_down", "Scroll down", "Scroll the visible list down."),
        observeSpec("wait", "Wait", "Wait briefly for the screen to settle."),
        sokoRead(
            "scan_soko_inventory", "Scan Soko inventory",
            "read the Soko Terminal product catalogue across multiple scrolls, deduplicate cards, and report visible quality issues without editing",
        ),
        sokoRead(
            "scan_soko_bookings", "Scan Soko bookings",
            "open Terminal Alerts and report bookings waiting for action without changing them",
        ),
        sokoRead(
            "audit_soko_services", "Audit Soko services",
            "browse the Terminal Services manager and report weak visible listing text without editing",
        ),
        sokoRead(
            "scan_soko_alerts", "Scan Soko alerts",
            "inspect Terminal items needing action, including orders, bookings, and low stock, without changing them",
        ),
        sokoRead(
            "audit_soko_buyer_services", "Audit buyer services",
            "browse buyer-visible Soko services and report exact seller, price, turnaround, rating, and coverage",
            plannerLine = "- audit_soko_buyer_services: browse buyer-visible Soko services and report exact seller, price, turnaround, rating, and coverage",
        ),
        observeSpec(
            "report_shop_health", "Report shop health",
            "summarize verified open Soko findings already stored on the device",
            plannerLine = "- report_shop_health: summarize verified open Soko findings already stored on the device",
        ),
        observeSpec(
            "visual_listing_audit", "Visual listing audit",
            "capture or read the private screenshot of one exact Soko listing and inspect its visible image against the listing name (owner vision consent required; read-only)",
            selfRecovering = false,
            packages = setOf("com.soko24.soko_seller_terminal"),
            plannerLine = "- visual_listing_audit: inspect the visible cover image of one exact Soko listing from its private screenshot and report mismatches (read-only; needs vision consent)",
        ),
        sokoRead(
            "read_soko_dashboard", "Read dashboard",
            "read today's KPIs and sales pulse from the Soko Terminal",
        ),
        sokoRead("read_soko_customers", "Read customers", "read customer contacts and their details"),
        sokoRead("read_soko_orders", "Read orders", "read marketplace orders and pickups"),
        sokoRead("read_soko_refunds", "Read refunds", "read returns, disputes, and reversals"),
        sokoRead("read_soko_suppliers", "Read suppliers", "read vendor and supplier relationships"),
        sokoRead("soko_full_report", "Full shop report", "read all major Soko sections and report a complete shop overview"),
        observeSpec(
            "list_whatsapp_chats", "List WhatsApp chats", "Discover visible WhatsApp direct chats.",
            plannerLine = "- list_whatsapp_chats, list_whatsapp_groups, read_group_participants",
        ),
        observeSpec("list_whatsapp_groups", "List WhatsApp groups", "Discover visible WhatsApp groups."),
        observeSpec("read_group_participants", "Read group participants", "Read participants of one named group."),
        CapabilitySpec(
            id = "monitor_whatsapp", label = "Monitor WhatsApp contact",
            description = "Enable automatic contextual replies for one exact contact.",
            risk = ActionRisk.LOW_IMPACT_CHANGE, inputSchema = "{target:nonblank!}", outputSchema = "{enabled:boolean}",
            requiredPermissions = setOf("notification_listener"),
            allowedInitiators = setOf(Initiator.OWNER_CHAT),
            externalSideEffect = true, approvalRequirement = ApprovalRequirement.EXPLICIT_OWNER_LANGUAGE,
            standingPolicyEligible = false, precondition = "Contact exists.",
            targetRule = TargetRule.WHATSAPP_TARGET_NAMED_IN_COMMAND,
            idempotencyStrategy = IdempotencyStrategy.CONTENT_HASH_KEY, verifierKind = VerifierKind.SCREEN_OBSERVATION,
            timeoutMs = 5_000, retryPolicy = SideEffectRetryPolicy.ZERO_BLIND_RETRY,
            recovery = RecoveryEligibility.TERMINAL_ON_FAILURE, supportedPackages = setOf("com.whatsapp"),
            protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
            receiptFields = setOf("capability", "target", "change"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = true, plannerPromptLine = "- monitor_whatsapp, stop_monitoring_whatsapp",
            usableInRecoveryPlan = false,
        ),
        CapabilitySpec(
            id = "stop_monitoring_whatsapp", label = "Stop WhatsApp monitoring",
            description = "Disable automatic replies for one exact contact.",
            risk = ActionRisk.LOW_IMPACT_CHANGE, inputSchema = "{target:nonblank!}", outputSchema = "{enabled:boolean}",
            requiredPermissions = setOf("notification_listener"),
            allowedInitiators = setOf(Initiator.OWNER_CHAT),
            externalSideEffect = true, approvalRequirement = ApprovalRequirement.EXPLICIT_OWNER_LANGUAGE,
            standingPolicyEligible = false, precondition = "Contact monitored.",
            targetRule = TargetRule.WHATSAPP_TARGET_NAMED_IN_COMMAND,
            idempotencyStrategy = IdempotencyStrategy.CONTENT_HASH_KEY, verifierKind = VerifierKind.SCREEN_OBSERVATION,
            timeoutMs = 5_000, retryPolicy = SideEffectRetryPolicy.ZERO_BLIND_RETRY,
            recovery = RecoveryEligibility.TERMINAL_ON_FAILURE, supportedPackages = setOf("com.whatsapp"),
            protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
            receiptFields = setOf("capability", "target", "change"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = true, plannerPromptLine = null,
            usableInRecoveryPlan = false,
        ),
        communicationSpec(
            "send_whatsapp", "Send WhatsApp message",
            "send exact text to one exact contact or group",
            VerifierKind.TARGET_CONTENT_DELIVERY, IdempotencyStrategy.CONTENT_HASH_KEY,
            setOf("com.whatsapp"),
            "- send_whatsapp: send exact text to one exact contact or group",
            workflowAuthorized = true,
        ),
        internalCommunicationSpec(
            CapabilityIds.REPLY_WHATSAPP, "Reply in open WhatsApp chat",
            "Send the composed reply inside the already-open, target-verified chat.",
            VerifierKind.TARGET_CONTENT_DELIVERY, setOf("com.whatsapp"),
            workflowAuthorized = true,
        ),
        internalCommunicationSpec(
            CapabilityIds.FOLLOW_UP_WHATSAPP, "WhatsApp follow-up",
            "Send a warm follow-up to an exact monitored contact for one exact unanswered message.",
            VerifierKind.TARGET_CONTENT_DELIVERY, setOf("com.whatsapp"),
            workflowAuthorized = true,
        ),
        internalCommunicationSpec(
            CapabilityIds.NOTIFY_OWNER_WHATSAPP, "Notify owner",
            "Deliver an escalation or permission request to the owner's own WhatsApp.",
            VerifierKind.TARGET_CONTENT_DELIVERY, setOf("com.whatsapp"),
        ),
        internalCommunicationSpec(
            CapabilityIds.BROADCAST_GROUP_WHATSAPP, "Broadcast to group",
            "Send standing-policy routine content to one configured group.",
            VerifierKind.TARGET_CONTENT_DELIVERY, setOf("com.whatsapp"),
        ),
        internalCommunicationSpec(
            CapabilityIds.SEND_WHATSAPP_ATTACHMENT, "Send WhatsApp attachment",
            "Share one document or media file with an exact chat plus optional caption.",
            VerifierKind.TARGET_CONTENT_DELIVERY, setOf("com.whatsapp"),
        ),
        internalPublicationSpec(
            CapabilityIds.POST_WHATSAPP_MEDIA_STATUS, "Post media Status",
            "Publish one photo/video Status (only when the owner explicitly asks).",
            setOf("com.whatsapp"),
        ),
        communicationSpec(
            "share_soko_studio_ad", "Share Soko Studio ad",
            "open Soko Terminal, read visible Studio ads, choose an unsent grounded product, polish it, and share to one exact WhatsApp target",
            VerifierKind.TARGET_CONTENT_DELIVERY, IdempotencyStrategy.CONTENT_HASH_KEY,
            setOf("com.soko24.soko_seller_terminal", "com.whatsapp"),
            "- share_soko_studio_ad: open Soko Terminal, read visible Studio ads, choose an unsent grounded product, polish it, and share to one exact WhatsApp target",
        ).let { spec ->
            // Studio sharing performs bounded internal recovery before any send.
            spec.copy(recovery = RecoveryEligibility.SELF_RECOVERING)
        },
        internalCommunicationSpec(
            CapabilityIds.SHARE_SOKO_STUDIO_CAPTION, "Share Studio caption",
            "Send the polished grounded caption for one exact Studio product.",
            VerifierKind.TARGET_CONTENT_DELIVERY, setOf("com.whatsapp"),
        ),
        communicationSpec(
            "post_whatsapp_status", "Post WhatsApp Status",
            "publish a text Status (only when the owner explicitly asks)",
            VerifierKind.PUBLICATION_STATE, IdempotencyStrategy.CONTENT_HASH_KEY,
            setOf("com.whatsapp"),
            "- post_whatsapp_status (only when the owner explicitly asks to publish a Status)",
            workflowAuthorized = true,
            recurringAllowed = true,
        ),
        communicationSpec(
            "post_tiktok", "Post TikTok",
            "create a TikTok draft or publish (only when the owner explicitly asks)",
            VerifierKind.PUBLICATION_STATE, IdempotencyStrategy.CONTENT_HASH_KEY,
            setOf("com.zhiliaoapp.musically"),
            "- post_tiktok (only when the owner explicitly asks to publish a TikTok)",
            workflowAuthorized = true,
            recurringAllowed = true,
        ),
        observeSpec(
            "tiktok_analytics", "TikTok analytics",
            "read TikTok profile analytics (views, likes, comments, shares, followers)",
            packages = setOf("com.zhiliaoapp.musically"),
            plannerLine = "- tiktok_analytics: read TikTok profile analytics (views, likes, comments, shares, followers)",
        ),
        observeSpec(
            "tiktok_comments", "TikTok comments", "read comments on TikTok videos",
            packages = setOf("com.zhiliaoapp.musically"),
            plannerLine = "- tiktok_comments: read comments on TikTok videos",
        ),
        observeSpec("tiktok_feed", "TikTok feed", "read the TikTok For You feed", packages = setOf("com.zhiliaoapp.musically"), plannerLine = "- tiktok_feed: read the TikTok For You feed"),
        observeSpec("tiktok_search", "TikTok search", "search TikTok for content", packages = setOf("com.zhiliaoapp.musically"), plannerLine = "- tiktok_search: search TikTok for content"),
        observeSpec("tiktok_sounds", "TikTok sounds", "read trending sounds on TikTok", packages = setOf("com.zhiliaoapp.musically"), plannerLine = "- tiktok_sounds: read trending sounds on TikTok"),
        CapabilitySpec(
            id = "propose_soko_edit", label = "Propose Soko edit",
            description = "open a Soko listing edit form, capture before/after fields, and create an approval request without saving",
            risk = ActionRisk.RECOMMEND, inputSchema = "{product:nonblank!, field:string!, value:string!}",
            outputSchema = "{approvalId:long}",
            requiredPermissions = setOf("accessibility_bound"), allowedInitiators = setOf(Initiator.OWNER_CHAT, Initiator.RECURRING_SCHEDULE),
            externalSideEffect = false, approvalRequirement = ApprovalRequirement.NONE,
            standingPolicyEligible = false, precondition = "Listing resolvable.",
            targetRule = TargetRule.LISTING_NAME_REQUIRED,
            idempotencyStrategy = IdempotencyStrategy.NOT_APPLICABLE, verifierKind = VerifierKind.SCREEN_OBSERVATION,
            timeoutMs = 60_000, retryPolicy = SideEffectRetryPolicy.NOT_APPLICABLE,
            recovery = RecoveryEligibility.READ_ONLY_RECOVERY, supportedPackages = setOf("com.soko24.soko_seller_terminal"),
            protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
            receiptFields = setOf("capability", "listing", "field", "before", "after"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = true,
            plannerPromptLine = "- propose_soko_edit: open a Soko listing edit form, capture before/after fields, and create an approval request without saving",
            usableInRecoveryPlan = true,
        ),
        CapabilitySpec(
            id = "apply_soko_edit", label = "Apply approved Soko edit",
            description = "after approval, open the listing edit form, apply the exact approved field change, save, and verify",
            risk = ActionRisk.LOW_IMPACT_CHANGE, inputSchema = "{product:nonblank!, field:string!, value:string!}",
            outputSchema = "{verified:boolean, reopenedFields:map}",
            requiredPermissions = setOf("accessibility_bound"),
            allowedInitiators = setOf(Initiator.OWNER_CHAT, Initiator.AUTHORIZED_WORKFLOW),
            externalSideEffect = true, approvalRequirement = ApprovalRequirement.FRESH_EXACT,
            standingPolicyEligible = false, precondition = "Unexpired approval exactly matches listing, field, and value.",
            targetRule = TargetRule.LISTING_NAME_REQUIRED,
            idempotencyStrategy = IdempotencyStrategy.APPROVAL_BOUND_KEY, verifierKind = VerifierKind.FIELD_REOPEN_COMPARE,
            timeoutMs = 180_000, retryPolicy = SideEffectRetryPolicy.ZERO_BLIND_RETRY,
            recovery = RecoveryEligibility.TERMINAL_ON_FAILURE, supportedPackages = setOf("com.soko24.soko_seller_terminal"),
            protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
            receiptFields = setOf("capability", "target", "approvalId", "beforeHash", "afterHash", "verificationState", "evidenceTimestamp"),
            telemetry = TelemetryHandling.DEVICE_LOCAL_PLUS_REDACTED_OPTIN_EXPORT,
            uiExposed = true,
            plannerPromptLine = "- apply_soko_edit: after approval, open the listing edit form, apply the exact approved field change, save, and verify",
            usableInRecoveryPlan = false,
        ),
        CapabilitySpec(
            id = "remember_soko_pin", label = "Remember Terminal PIN",
            description = "securely remember a Terminal PIN explicitly supplied by the owner",
            risk = ActionRisk.SECURITY_IDENTITY, inputSchema = "{pin:string}", outputSchema = "{stored:boolean}",
            requiredPermissions = emptySet(), allowedInitiators = setOf(Initiator.OWNER_CHAT),
            externalSideEffect = true, approvalRequirement = ApprovalRequirement.FRESH_EXACT,
            standingPolicyEligible = false, precondition = "Owner supplied the PIN in this instruction.",
            targetRule = TargetRule.NON_BLANK,
            idempotencyStrategy = IdempotencyStrategy.NOT_APPLICABLE, verifierKind = VerifierKind.SCREEN_OBSERVATION,
            timeoutMs = 5_000, retryPolicy = SideEffectRetryPolicy.ZERO_BLIND_RETRY,
            recovery = RecoveryEligibility.TERMINAL_ON_FAILURE, supportedPackages = setOf("com.soko24.soko_seller_terminal"),
            protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
            receiptFields = setOf("capability", "storedCredentialReference"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = true,
            plannerPromptLine = "- remember_soko_pin: securely remember a Terminal PIN explicitly supplied by the owner",
            usableInRecoveryPlan = false,
        ),
        CapabilitySpec(
            id = "cancel_soko_booking", label = "Cancel Soko booking",
            description = "Cancel an exact booking after fresh owner approval.",
            risk = ActionRisk.HIGH_IMPACT_CHANGE, inputSchema = "{booking:string}",
            outputSchema = "{cancelled:boolean, evidence:string}",
            requiredPermissions = setOf("accessibility_bound"), allowedInitiators = setOf(Initiator.OWNER_CHAT),
            externalSideEffect = true, approvalRequirement = ApprovalRequirement.FRESH_EXACT,
            standingPolicyEligible = false, precondition = "Fresh approval for this exact booking.",
            targetRule = TargetRule.NON_BLANK,
            idempotencyStrategy = IdempotencyStrategy.APPROVAL_BOUND_KEY, verifierKind = VerifierKind.FIELD_REOPEN_COMPARE,
            timeoutMs = 120_000, retryPolicy = SideEffectRetryPolicy.ZERO_BLIND_RETRY,
            recovery = RecoveryEligibility.TERMINAL_ON_FAILURE, supportedPackages = setOf("com.soko24.soko_seller_terminal"),
            protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
            receiptFields = setOf("capability", "target", "approvalId", "verificationState"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = true, plannerPromptLine = null,
            usableInRecoveryPlan = false,
        ),
        CapabilitySpec(
            id = "delete", label = "Delete",
            description = "Destructive deletion; fresh approval for the exact object.",
            risk = ActionRisk.HIGH_IMPACT_CHANGE, inputSchema = "{target:string}", outputSchema = "{deleted:boolean}",
            requiredPermissions = setOf("accessibility_bound"), allowedInitiators = setOf(Initiator.OWNER_CHAT),
            externalSideEffect = true, approvalRequirement = ApprovalRequirement.FRESH_EXACT,
            standingPolicyEligible = false, precondition = "Fresh approval for the exact object.",
            targetRule = TargetRule.NON_BLANK,
            idempotencyStrategy = IdempotencyStrategy.APPROVAL_BOUND_KEY, verifierKind = VerifierKind.FIELD_REOPEN_COMPARE,
            timeoutMs = 60_000, retryPolicy = SideEffectRetryPolicy.ZERO_BLIND_RETRY,
            recovery = RecoveryEligibility.TERMINAL_ON_FAILURE, supportedPackages = emptySet(),
            protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
            receiptFields = setOf("capability", "target", "approvalId", "verificationState"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = false, plannerPromptLine = null, usableInRecoveryPlan = false,
        ),
        CapabilitySpec(
            id = "account_login", label = "Account login",
            description = "Owner-assisted account identity flow; never guessed.",
            risk = ActionRisk.SECURITY_IDENTITY, inputSchema = "{}", outputSchema = "{}",
            requiredPermissions = emptySet(), allowedInitiators = setOf(Initiator.OWNER_CHAT),
            externalSideEffect = true, approvalRequirement = ApprovalRequirement.FRESH_EXACT,
            standingPolicyEligible = false, precondition = "Owner physically assists.",
            targetRule = TargetRule.NONE,
            idempotencyStrategy = IdempotencyStrategy.NOT_APPLICABLE, verifierKind = VerifierKind.SCREEN_OBSERVATION,
            timeoutMs = 30_000, retryPolicy = SideEffectRetryPolicy.ZERO_BLIND_RETRY,
            recovery = RecoveryEligibility.TERMINAL_ON_FAILURE, supportedPackages = emptySet(),
            protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
            receiptFields = setOf("capability", "handoffReason"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = false, plannerPromptLine = null, usableInRecoveryPlan = false,
        ),
        CapabilitySpec(
            id = "enter_otp", label = "Enter OTP",
            description = "Owner-assisted OTP entry; never guessed.",
            risk = ActionRisk.SECURITY_IDENTITY, inputSchema = "{}", outputSchema = "{}",
            requiredPermissions = emptySet(), allowedInitiators = setOf(Initiator.OWNER_CHAT),
            externalSideEffect = true, approvalRequirement = ApprovalRequirement.FRESH_EXACT,
            standingPolicyEligible = false, precondition = "Owner supplies the code.",
            targetRule = TargetRule.NONE,
            idempotencyStrategy = IdempotencyStrategy.NOT_APPLICABLE, verifierKind = VerifierKind.SCREEN_OBSERVATION,
            timeoutMs = 30_000, retryPolicy = SideEffectRetryPolicy.ZERO_BLIND_RETRY,
            recovery = RecoveryEligibility.TERMINAL_ON_FAILURE, supportedPackages = emptySet(),
            protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
            receiptFields = setOf("capability", "handoffReason"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = false, plannerPromptLine = null, usableInRecoveryPlan = false,
        ),
        CapabilitySpec(
            id = "pay", label = "Pay",
            description = "Payment; fresh approval plus amount/payee confirmation.",
            risk = ActionRisk.FINANCIAL, inputSchema = "{payee:string, amount:number}", outputSchema = "{paid:boolean}",
            requiredPermissions = emptySet(), allowedInitiators = setOf(Initiator.OWNER_CHAT),
            externalSideEffect = true, approvalRequirement = ApprovalRequirement.FRESH_EXACT,
            standingPolicyEligible = false, precondition = "Fresh approval with amount and payee.",
            targetRule = TargetRule.NON_BLANK,
            idempotencyStrategy = IdempotencyStrategy.APPROVAL_BOUND_KEY, verifierKind = VerifierKind.FIELD_REOPEN_COMPARE,
            timeoutMs = 60_000, retryPolicy = SideEffectRetryPolicy.ZERO_BLIND_RETRY,
            recovery = RecoveryEligibility.TERMINAL_ON_FAILURE, supportedPackages = emptySet(),
            protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
            receiptFields = setOf("capability", "target", "amount", "approvalId", "verificationState"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = false, plannerPromptLine = null, usableInRecoveryPlan = false,
        ),
        CapabilitySpec(
            id = "refund", label = "Refund",
            description = "Refund; fresh approval plus amount confirmation.",
            risk = ActionRisk.FINANCIAL, inputSchema = "{target:string, amount:number}", outputSchema = "{refunded:boolean}",
            requiredPermissions = emptySet(), allowedInitiators = setOf(Initiator.OWNER_CHAT),
            externalSideEffect = true, approvalRequirement = ApprovalRequirement.FRESH_EXACT,
            standingPolicyEligible = false, precondition = "Fresh approval with amount.",
            targetRule = TargetRule.NON_BLANK,
            idempotencyStrategy = IdempotencyStrategy.APPROVAL_BOUND_KEY, verifierKind = VerifierKind.FIELD_REOPEN_COMPARE,
            timeoutMs = 60_000, retryPolicy = SideEffectRetryPolicy.ZERO_BLIND_RETRY,
            recovery = RecoveryEligibility.TERMINAL_ON_FAILURE, supportedPackages = emptySet(),
            protectedScreenBehavior = ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
            receiptFields = setOf("capability", "target", "amount", "approvalId", "verificationState"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = true, plannerPromptLine = null, usableInRecoveryPlan = false,
        ),
        CapabilitySpec(
            id = "ask_owner", label = "Ask owner",
            description = "One missing fact would materially change the action.",
            risk = ActionRisk.RECOMMEND, inputSchema = "{question:string}", outputSchema = "{question:string}",
            requiredPermissions = emptySet(), allowedInitiators = Initiator.entries.toSet(),
            externalSideEffect = false, approvalRequirement = ApprovalRequirement.NONE,
            standingPolicyEligible = false, precondition = "None.", targetRule = TargetRule.NONE,
            idempotencyStrategy = IdempotencyStrategy.NOT_APPLICABLE, verifierKind = VerifierKind.SCREEN_OBSERVATION,
            timeoutMs = 5_000, retryPolicy = SideEffectRetryPolicy.NOT_APPLICABLE,
            recovery = RecoveryEligibility.READ_ONLY_RECOVERY, supportedPackages = emptySet(),
            protectedScreenBehavior = ProtectedScreenBehavior.NOT_APPLICABLE,
            receiptFields = setOf("capability", "question"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = true, plannerPromptLine = "- ask_owner (one missing fact would materially change the action)",
            usableInRecoveryPlan = true,
        ),
        CapabilitySpec(
            id = "unsupported", label = "Unsupported",
            description = "No safe verified capability exists for the request yet.",
            risk = ActionRisk.OBSERVE, inputSchema = "{}", outputSchema = "{}",
            requiredPermissions = emptySet(), allowedInitiators = Initiator.entries.toSet(),
            externalSideEffect = false, approvalRequirement = ApprovalRequirement.NONE,
            standingPolicyEligible = false, precondition = "None.", targetRule = TargetRule.NONE,
            idempotencyStrategy = IdempotencyStrategy.NOT_APPLICABLE, verifierKind = VerifierKind.SCREEN_OBSERVATION,
            timeoutMs = 0, retryPolicy = SideEffectRetryPolicy.NOT_APPLICABLE,
            recovery = RecoveryEligibility.READ_ONLY_RECOVERY, supportedPackages = emptySet(),
            protectedScreenBehavior = ProtectedScreenBehavior.NOT_APPLICABLE,
            receiptFields = setOf("capability", "blocker"), telemetry = TelemetryHandling.DEVICE_LOCAL_ONLY,
            uiExposed = true, plannerPromptLine = "- unsupported",
            usableInRecoveryPlan = true,
        ),
    ).associateBy(CapabilitySpec::id)

    fun get(id: String): CapabilitySpec? = specs[id]

    /** Planner-visible prompt lines, derived so the prompt cannot drift from the catalog. */
    fun plannerLines(): List<String> = specs.values.mapNotNull { it.plannerPromptLine }

    /** Actions the plan guard accepts, derived from executable planner-exposed specs. */
    fun plannableActionIds(): Set<String> = specs.values.filter { it.plannerPromptLine != null }.map { it.id }.toSet()

    /** Read-only actions permitted inside a recovery plan. */
    fun recoveryActionIds(): Set<String> = specs.values.filter { it.usableInRecoveryPlan }.map { it.id }.toSet()
}

data class PhoneCapability(
    val name: String,
    val risk: ActionRisk,
    val hasExternalSideEffect: Boolean,
    val internallyRecovers: Boolean = false,
)

/** Backward-compatible view over the unified {@link CapabilityCatalog}. */
object PhoneCapabilityRegistry {
    private val capabilities = CapabilityCatalog.specs.values.associate { spec ->
        spec.id to PhoneCapability(
            name = spec.id,
            risk = spec.risk,
            hasExternalSideEffect = spec.externalSideEffect,
            internallyRecovers = spec.recovery == RecoveryEligibility.SELF_RECOVERING,
        )
    }

    fun get(name: String): PhoneCapability? = capabilities[name]
    fun getSpec(name: String): CapabilitySpec? = CapabilityCatalog.get(name)
    fun names(): Set<String> = capabilities.keys
}

data class ActionAuthorization(
    val allowed: Boolean,
    val reason: String,
    val risk: ActionRisk,
    val requiresApproval: Boolean,
)

object ActionPolicy {
    private val communicationWords = setOf("send", "message", "tell", "reply", "share", "forward", "post", "publish")
    private val changeWords = setOf("edit", "change", "update", "polish", "fix", "save", "confirm", "complete", "cancel", "delete", "monitor", "stop monitoring")
    private val securityWords = setOf("login", "log in", "sign in", "otp", "pin", "password")
    private val financialWords = setOf("pay", "purchase", "buy", "refund", "transfer", "checkout")

    fun authorize(capabilityName: String, ownerCommand: String, hasMatchingApproval: Boolean = false): ActionAuthorization {
        val spec = CapabilityCatalog.get(capabilityName)
            ?: return ActionAuthorization(false, "The capability is not registered.", ActionRisk.HIGH_IMPACT_CHANGE, true)
        if (!spec.externalSideEffect) return ActionAuthorization(true, "Read-only or owner-facing action.", spec.risk, false)

        val normalized = ownerCommand.lowercase()
        val explicitlyRequested = when (spec.approvalRequirement) {
            ApprovalRequirement.NONE -> true
            ApprovalRequirement.EXPLICIT_OWNER_LANGUAGE -> when (spec.risk) {
                ActionRisk.LOW_IMPACT_CHANGE -> changeWords.any(normalized::contains) || capabilityName in normalized
                ActionRisk.EXTERNAL_COMMUNICATION -> communicationWords.any(normalized::contains)
                else -> false
            }
            ApprovalRequirement.FRESH_EXACT -> when (spec.risk) {
                ActionRisk.LOW_IMPACT_CHANGE, ActionRisk.EXTERNAL_COMMUNICATION, ActionRisk.HIGH_IMPACT_CHANGE ->
                    changeWords.any(normalized::contains) || communicationWords.any(normalized::contains)
                ActionRisk.SECURITY_IDENTITY -> securityWords.any(normalized::contains)
                ActionRisk.FINANCIAL -> financialWords.any(normalized::contains)
                else -> false
            }
        }
        if (!explicitlyRequested) {
            return ActionAuthorization(false, "The owner did not explicitly authorize this ${spec.risk.name.lowercase().replace('_', ' ')} action.", spec.risk, true)
        }
        val explicitSecretStorage = capabilityName == "remember_soko_pin" &&
            Regex("(?i)\\bpin(?:\\s+is|\\s*:|\\s*=)?\\s*\\d{4,8}\\b").containsMatchIn(ownerCommand)
        if (spec.requiresApproval() && !hasMatchingApproval && !explicitSecretStorage) {
            return ActionAuthorization(false, "This ${spec.risk.name.lowercase().replace('_', ' ')} action needs fresh approval for the exact target and change.", spec.risk, true)
        }
        return ActionAuthorization(true, "The owner explicitly authorized this action.", spec.risk, spec.requiresApproval())
    }
}

enum class ProtectedScreenKind { ACCOUNT_LOGIN, OTP, BIOMETRIC, CAPTCHA, ANDROID_PERMISSION, UNKNOWN }

data class ProtectedScreen(val kind: ProtectedScreenKind, val ownerAction: String)

object ProtectedScreenClassifier {
    fun classify(labels: Collection<String>): ProtectedScreen? {
        val text = labels.joinToString(" ").lowercase()
        return when {
            "captcha" in text || "i'm not a robot" in text || "i am not a robot" in text ->
                ProtectedScreen(ProtectedScreenKind.CAPTCHA, "Complete the CAPTCHA on the phone.")
            "fingerprint" in text || "face unlock" in text || "use biometrics" in text ->
                ProtectedScreen(ProtectedScreenKind.BIOMETRIC, "Complete biometric verification on the phone.")
            "one-time password" in text || "verification code" in text || Regex("\\botp\\b").containsMatchIn(text) ->
                ProtectedScreen(ProtectedScreenKind.OTP, "Review and enter the one-time verification code on the phone.")
            "enter your phone number" in text || "sign in to your account" in text ->
                ProtectedScreen(ProtectedScreenKind.ACCOUNT_LOGIN, "Sign into the account or explicitly authorize the account identity flow.")
            "allow" in text && ("permission" in text || "access" in text) ->
                ProtectedScreen(ProtectedScreenKind.ANDROID_PERMISSION, "Review and grant the Android permission if you agree.")
            else -> null
        }
    }
}
