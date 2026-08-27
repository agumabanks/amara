package co.sanaa.agent.core

import java.security.MessageDigest

/**
 * Universal side-effect transaction states. Every external action passes through
 * this state machine; nothing outside it may perform an externally visible change.
 */
enum class SideEffectState {
    PROPOSED,
    AWAITING_APPROVAL,
    APPROVED,
    CLAIMED,
    ACTING,
    VERIFICATION_PENDING,
    VERIFIED,
    FAILED,
    UNCERTAIN,
    CANCELLED,
    EXPIRED;

    /** States from which an automatic retry of the external action is forbidden forever. */
    val noAutoRetry: Boolean
        get() = this == ACTING || this == VERIFICATION_PENDING || this == UNCERTAIN || this == VERIFIED

    val terminal: Boolean
        get() = this == VERIFIED || this == FAILED || this == CANCELLED || this == EXPIRED

    companion object {
        /** The only legal transitions. Anything else is rejected and recorded. */
        val LEGAL_TRANSITIONS: Map<SideEffectState, Set<SideEffectState>> = mapOf(
            PROPOSED to setOf(AWAITING_APPROVAL, APPROVED, CLAIMED, CANCELLED, EXPIRED),
            AWAITING_APPROVAL to setOf(APPROVED, CANCELLED, EXPIRED),
            APPROVED to setOf(CLAIMED, CANCELLED, EXPIRED),
            CLAIMED to setOf(ACTING, CANCELLED, UNCERTAIN),
            ACTING to setOf(VERIFICATION_PENDING, FAILED, UNCERTAIN),
            VERIFICATION_PENDING to setOf(VERIFIED, FAILED, UNCERTAIN),
            VERIFIED to emptySet(),
            FAILED to emptySet(),
            UNCERTAIN to emptySet(),
            CANCELLED to emptySet(),
            EXPIRED to emptySet(),
        )

        fun canTransition(from: SideEffectState, to: SideEffectState): Boolean =
            from == to || to in LEGAL_TRANSITIONS.getValue(from)
    }
}

object ContentHashing {
    /**
     * Normalization removes whitespace/case variance so a quoted copy or draft with the
     * same normalized content hashes identically, while different content cannot collide.
     */
    fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim().lowercase()

    fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(normalize(value).toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/**
 * Capability-specific verification contract (Phase A3).
 * Binds the expected app package, exact target identity, normalized content hash,
 * pre-state, observation window, and required delivery/publication state.
 */
data class VerificationContract(
    val expectedPackage: String?,
    val expectedTarget: String,
    val contentHash: String,
    val preStateSignature: String,
    val windowMs: Long,
    val requireDeliveryState: Boolean,
) {
    fun describe(): String =
        "package=${expectedPackage ?: "any"}; target=$expectedTarget; contentHash=${contentHash.take(12)}; requireDeliveryState=$requireDeliveryState; windowMs=$windowMs"
}

/** Observation result produced by a capability verifier; never inferred from a tap alone. */
data class VerificationEvidence(
    val verified: Boolean,
    val confidence: Double,
    val observedPackage: String,
    val deliveryState: String?,
    val evidenceTimestamp: Long,
    val blocker: String? = null,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be within [0,1]" }
        require(!verified || blocker == null) { "verified evidence cannot carry a blocker" }
    }

    companion object {
        fun impossible(blocker: String, observedPackage: String = "") = VerificationEvidence(
            verified = false, confidence = 0.0, observedPackage = observedPackage,
            deliveryState = null, evidenceTimestamp = System.currentTimeMillis(), blocker = blocker,
        )
    }
}

data class SideEffectTransaction(
    val idempotencyKey: String,
    val capability: String,
    val target: String,
    val contentHash: String,
    val approvalId: Long?,
    val state: SideEffectState,
    val createdAt: Long,
    val updatedAt: Long,
    val evidence: String,
)

/**
 * Result of the external trigger dispatch.
 * [dispatched]=true means the final external trigger was dispatched and its effect is
 * unproven until verification; false means the dispatch provably did not occur.
 * A thrown exception leaves the effect position unknown and finalizes UNCERTAIN.
 */
data class ActResult(val dispatched: Boolean, val proofOfNoEffect: String? = null) {
    companion object {
        fun dispatched() = ActResult(true)
        fun notPerformed(proof: String) = ActResult(false, proof)
    }
}

/** Outcome returned to callers after one full transaction pass. */
sealed class SideEffectOutcome {
    data class Verified(val evidence: VerificationEvidence) : SideEffectOutcome()
    data class Failed(val reason: String) : SideEffectOutcome()
    data class Uncertain(val reason: String) : SideEffectOutcome()
    data class DuplicateBlocked(val existingState: SideEffectState) : SideEffectOutcome()
    data class Rejected(val reason: String) : SideEffectOutcome()

    val verified: Boolean get() = this is Verified
}

/**
 * Executes external actions through resolve → validate → claim → preflight → act →
 * verify → finalize. The transaction is persisted before execution; `acting`,
 * `verification_pending`, and `uncertain` work is never automatically repeated;
 * unknown capabilities and illegal transitions are rejected outright.
 */
class SideEffectRunner(private val ledger: SideEffectLedger, private val clock: () -> Long = System::currentTimeMillis) {

    /**
     * @param capabilityId must exist in [CapabilityCatalog] and be externalSideEffect.
     * @param initiator must be an allowed initiator of the capability.
     * @param approvalId required when the spec demands FRESH_EXACT approval; validated
     *   through [approvalValidator] immediately before acting.
     * @param preflight runs after claiming and before ACTING; returning a non-null
     *   string aborts as FAILED with that reason (no effect possible).
     */
    suspend fun execute(
        capabilityId: String,
        idempotencyKey: String,
        target: String,
        content: String,
        initiator: Initiator = Initiator.INTERNAL_RUNTIME,
        approvalId: Long? = null,
        approvalValidator: suspend (Long) -> Boolean = { true },
        inputs: Map<String, Any?> = emptyMap(),
        preflight: (suspend () -> String?)? = null,
        act: suspend () -> Boolean,
        verify: suspend () -> VerificationEvidence,
    ): SideEffectOutcome {
        // ---- Resolve + validate against the authoritative catalog ----
        val spec = CapabilityCatalog.get(capabilityId)
            ?: return SideEffectOutcome.Rejected("Capability '$capabilityId' is not registered; refusing to execute an unknown external action.")
        if (!spec.externalSideEffect) {
            return SideEffectOutcome.Rejected("Capability '$capabilityId' declares no external side effect; route it through normal execution instead of the transaction.")
        }
        if (initiator !in spec.allowedInitiators) {
            return SideEffectOutcome.Rejected("Initiator $initiator may not start '$capabilityId' (allowed: ${spec.allowedInitiators}).")
        }
        // Proactive-initiation enforcement: a capability may only be started by the
        // PROACTIVE_AUDIT initiator when ITS OWN spec allows proactive runs. This keeps
        // scheduled/background flows from ever starting capabilities that demand an
        // owner-commanded initiator, independent of what the caller passes.
        if (initiator == Initiator.PROACTIVE_AUDIT && !spec.mayRunProactively()) {
            return SideEffectOutcome.Rejected("Capability '$capabilityId' does not allow proactive initiation; owner command required.")
        }
        when (spec.targetRule) {
            TargetRule.NON_BLANK, TargetRule.WHATSAPP_TARGET_NAMED_IN_COMMAND, TargetRule.LISTING_NAME_REQUIRED ->
                if (target.isBlank()) return SideEffectOutcome.Rejected("Capability '$capabilityId' requires an exact non-blank target.")
            TargetRule.NONE -> Unit
        }
        if (spec.idempotencyStrategy == IdempotencyStrategy.NOT_APPLICABLE && idempotencyKey.isBlank()) {
            return SideEffectOutcome.Rejected("Capability '$capabilityId' requires an idempotency key.")
        }
        val workflowAuthorized = initiator == Initiator.AUTHORIZED_WORKFLOW
        if (workflowAuthorized && approvalId == null) {
            // An authorized-workflow run is ONLY legal with a fresh, exact-bound owner
            // approval. This holds even when the spec's owner-chat requirement is weaker:
            // revenue targets never broaden authority at the execution boundary.
            return SideEffectOutcome.Rejected(
                "Capability '$capabilityId' started by an authorized workflow demands a fresh exact approval; none was bound.",
            )
        }
        if (spec.approvalRequirement == ApprovalRequirement.FRESH_EXACT && approvalId == null) {
            return SideEffectOutcome.Rejected("Capability '$capabilityId' requires fresh exact approval; none was bound.")
        }
        // Typed schema validation at the boundary (Phase A gap fix).
        val effectiveInputs = if (inputs.isEmpty()) autoDeriveInputs(spec, target, content) else inputs
        val schemaFailures = spec.parsedInputSchema.validate(effectiveInputs)
        if (schemaFailures.isNotEmpty()) {
            return SideEffectOutcome.Rejected("Inputs rejected by the '$capabilityId' schema: ${schemaFailures.joinToString("; ")}")
        }
        val contentHash = ContentHashing.hash(content)

        // ---- Duplicate / uncertainty protection ----
        val existing = ledger.find(idempotencyKey)
        when {
            existing != null && existing.capability != capabilityId ->
                return SideEffectOutcome.Rejected("Idempotency key already bound to capability '${existing.capability}'; keys must never be reused across capabilities.")
            existing != null && existing.contentHash != contentHash ->
                return SideEffectOutcome.Rejected("Content changed under a reused idempotency key; mint a new key for the new content.")
            existing?.state == SideEffectState.VERIFIED ->
                return SideEffectOutcome.DuplicateBlocked(SideEffectState.VERIFIED)
            existing?.state?.noAutoRetry == true ->
                return SideEffectOutcome.Uncertain(
                    "A previous attempt for $capabilityId ($idempotencyKey) reached ${existing.state.name} and must not be repeated automatically.",
                )
            existing?.state == SideEffectState.FAILED ->
                return SideEffectOutcome.Failed(existing.evidence.ifBlank { "A previous attempt with this key failed." })
            else -> Unit // no prior record, or a pre-acting state (proposed/approved/claimed): safe to claim now
        }

        // ---- Claim before acting ----
        val now = clock()
        val claimed = if (existing == null) {
            ledger.upsert(
                SideEffectTransaction(idempotencyKey, capabilityId, target, contentHash, approvalId, SideEffectState.CLAIMED, now, now, "Claimed for execution."),
            )
        } else {
            // Legal re-claim of a pre-acting record (proposed/approved/awaiting/claimed).
            ledger.transition(idempotencyKey, SideEffectState.CLAIMED, "Re-claimed before acting.")
        }
        if (!claimed) {
            return SideEffectOutcome.Rejected("The transaction could not be claimed for '$capabilityId' ($idempotencyKey); refusing to act.")
        }
        try {
            // Preflight runs while still CLAIMED and BEFORE the approval is consumed: a
            // rejected preflight proves nothing was dispatched, so an otherwise-valid
            // approval survives for the corrected attempt (policy may override by
            // consuming inside the preflight itself when it wants one-shot semantics).
            preflight?.let { check ->
                val blocker = check()
                if (blocker != null) {
                    ledger.transition(idempotencyKey, SideEffectState.CANCELLED, Redactor.redact(blocker))
                    return SideEffectOutcome.Failed(blocker)
                }
            }
            // FINAL PRE-ACT BOUNDARY: the approval is validated AND consumed atomically
            // here — the last moment before any external dispatch. Expiry, revocation,
            // binding mismatch, and exhausted execution counts all cancel cleanly.
            if (approvalId != null && !approvalValidator(approvalId)) {
                ledger.transition(idempotencyKey, SideEffectState.CANCELLED, "Approval expired, revoked, mismatched, or exhausted at the pre-act boundary.")
                return SideEffectOutcome.Failed("The approval was not consumable at the final boundary; nothing was done.")
            }
            ledger.transition(idempotencyKey, SideEffectState.ACTING, "Preflight and authority checks passed; acting once.")
            val deadlineMs = clock() + spec.timeoutMs
            fun overDeadline(): Boolean = clock() > deadlineMs
            // Contract: act() returns false ONLY when the external trigger provably never
            // dispatched; any exception during act leaves the effect position UNKNOWN and
            // finalizes UNCERTAIN — never silently retried.
            val acted: Boolean = try {
                act()
            } catch (error: Throwable) {
                ledger.transition(idempotencyKey, SideEffectState.UNCERTAIN,
                    "Exception during external action: ${Redactor.redact(error.message ?: error.javaClass.simpleName)}")
                return SideEffectOutcome.Uncertain(
                    "The action was interrupted mid-flight (${error.message ?: "unknown"}); whether it took effect could not be proven.",
                )
            }
            if (!acted) {
                val proof = "Action reported the external trigger was never dispatched."
                ledger.transition(idempotencyKey, SideEffectState.FAILED, proof)
                return SideEffectOutcome.Failed(proof.removePrefix("Action reported the "))
            }
            ledger.transition(idempotencyKey, SideEffectState.VERIFICATION_PENDING, "Verifying post-state against the verification contract.")
            val evidence = runCatching { verify() }.getOrElse {
                VerificationEvidence.impossible("Verifier itself failed: ${it.message ?: "unknown"}")
            }
            if (overDeadline() && evidence.verified) {
                // The effect happened but proof arrived after the capability deadline:
                // record honestly as UNCERTAIN with timeout evidence (terminal state).
                ledger.finalize(
                    idempotencyKey, SideEffectState.UNCERTAIN,
                    "Verification completed after the ${spec.timeoutMs}ms deadline for '$capabilityId'; result kept unproven.",
                )
                return SideEffectOutcome.Uncertain(
                    "The ${'$'}capabilityId result could only be proven after its ${spec.timeoutMs}ms deadline, so it is recorded unproven.",
                )
            }
            val finalState = when {
                evidence.verified -> SideEffectState.VERIFIED
                evidence.blocker?.contains(NON_EFFECT_BLOCKER, ignoreCase = true) == true -> SideEffectState.FAILED
                else -> SideEffectState.UNCERTAIN
            }
            val redactedEvidence = listOf(
                "verified=${evidence.verified}",
                "confidence=${evidence.confidence}",
                "observedPackage=${evidence.observedPackage}",
                "deliveryState=${evidence.deliveryState ?: "none"}",
                "blocker=${evidence.blocker?.let(Redactor::redact)?.take(300) ?: "none"}",
            ).joinToString("; ")
            ledger.finalize(idempotencyKey, finalState, redactedEvidence)
            return when (finalState) {
                SideEffectState.VERIFIED -> SideEffectOutcome.Verified(evidence)
                SideEffectState.FAILED -> SideEffectOutcome.Failed(evidence.blocker ?: "Verification proved no effect occurred.")
                else -> SideEffectOutcome.Uncertain("Could not prove whether the action took effect. ${evidence.blocker ?: ""}".trim())
            }
        } catch (t: Throwable) {
            ledger.finalize(idempotencyKey, SideEffectState.UNCERTAIN, "Unexpected termination during transaction: ${Redactor.redact(t.message ?: t.javaClass.simpleName)}")
            return SideEffectOutcome.Uncertain("The transaction ended unexpectedly; the effect could not be proven.")
        }
    }

    /**
     * Canonical auto-derivation for simple {target,…}+{message|content} schemas so
     * callers need not repeat obvious bindings. Any other schema demands explicit inputs.
     */
    private fun autoDeriveInputs(spec: CapabilitySpec, target: String, content: String): Map<String, Any?> {
        val fields = spec.parsedInputSchema.fields
        val derivable = fields.all { it.name == "target" || it.name == "message" || it.name == "content" }
        require(derivable) {
            "Capability '${spec.id}' needs explicit inputs; its schema is not the canonical target/message shape."
        }
        return fields.associate { field ->
            when (field.name) {
                "target" -> "target" to target
                "message" -> "message" to content
                else -> "content" to content
            }
        }.filterKeys { true }
    }

    companion object {
        const val NON_EFFECT_BLOCKER = "NO_EFFECT_PROVEN"
    }
}

/**
 * Durable ledger for side-effect transactions backed by AmaraMemory's SQLite store.
 * Implementations must enforce legal transitions and stay crash-safe under concurrency.
 */
interface SideEffectLedger {
    fun find(idempotencyKey: String): SideEffectTransaction?
    fun upsert(transaction: SideEffectTransaction): Boolean
    fun transition(idempotencyKey: String, newState: SideEffectState, evidence: String): Boolean
    fun finalize(idempotencyKey: String, finalState: SideEffectState, evidence: String) {
        transition(idempotencyKey, finalState, evidence)
    }

    companion object {
        fun from(memory: AmaraMemory): SideEffectLedger = object : SideEffectLedger {
            override fun find(idempotencyKey: String): SideEffectTransaction? = memory.findSideEffectTransaction(idempotencyKey)
            override fun upsert(transaction: SideEffectTransaction): Boolean = memory.upsertSideEffectTransaction(transaction)
            override fun transition(idempotencyKey: String, newState: SideEffectState, evidence: String): Boolean =
                memory.transitionSideEffectTransaction(idempotencyKey, newState, evidence)
        }
    }
}
