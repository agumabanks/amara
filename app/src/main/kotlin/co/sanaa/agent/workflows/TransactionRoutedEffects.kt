package co.sanaa.agent.workflows

import co.sanaa.agent.actions.PublicationSurfaceRule
import co.sanaa.agent.actions.PublicationSurfaces
import co.sanaa.agent.actions.SokoSaveVerification
import co.sanaa.agent.actions.WorkflowDeviceSurface
import co.sanaa.agent.core.CapabilityCatalog
import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.Initiator
import co.sanaa.agent.core.SideEffectOutcome
import co.sanaa.agent.core.SideEffectRunner
import co.sanaa.agent.core.TaskQueue
import co.sanaa.agent.core.VerificationEvidence
import co.sanaa.agent.core.work.WorkContract

/**
 * Verification logic evaluated over the narrow [WorkflowDeviceSurface] seam. Reuses the
 * production [co.sanaa.agent.actions.SendVerificationLogic] rules so local certification
 * exercises exactly the same decision code as live verification.
 */
class SurfaceVerifiers(private val surface: WorkflowDeviceSurface) {

    suspend fun evaluateCurrentChat(target: String, content: String): VerificationEvidence {
        val snapshot = surface.snapshot()
        val nodeFacts = surface.observeMessageNodes(content)
        val (inBubble, draftOnlyByRoles) = co.sanaa.agent.actions.SendVerificationLogic.evaluateNodeRoles(content, nodeFacts)
        val lineFallback = !inBubble && !draftOnlyByRoles &&
            co.sanaa.agent.actions.SendVerificationLogic.contentMatches(
                snapshot.visibleText.filterNot { co.sanaa.agent.actions.SendVerificationLogic.detectDraftOnly(listOf(it), content) },
                content,
            )
        val observation = co.sanaa.agent.actions.SendObservation(
            observedPackage = snapshot.packageName,
            expectedPackage = PublicationSurfaces.WHATSAPP_PACKAGE,
            targetVisible = snapshot.contains(target),
            contentVisibleOutsideDraft = inBubble || lineFallback,
            contentStillInDraftField = draftOnlyByRoles ||
                (!inBubble && co.sanaa.agent.actions.SendVerificationLogic.detectDraftOnly(snapshot.visibleText, content)),
            deliveryState = surface.messageDeliveryState(content.take(40)) ?: nodeFacts.deliveryState
                ?: snapshot.visibleText.lastOrNull { it.equals("Sent", true) || it.equals("Delivered", true) || it.equals("Read", true) },
            observedAtMs = System.currentTimeMillis(),
        )
        return co.sanaa.agent.actions.SendVerificationLogic.evaluate(content, observation)
    }

    fun evaluatePublication(content: String, snapshotPackage: String, visibleText: List<String>, expectedPackage: String): VerificationEvidence =
        PublicationSurfaceRule(expectedPackage).evaluate(
            content, snapshotPackage,
            co.sanaa.agent.actions.SendVerificationLogic.contentMatches(visibleText, content),
        )
}

/**
 * PRODUCTION effect router for department workflow executions. Every consequential step
 * is executed inside the universal side-effect transaction ([SideEffectRunner]) using
 * [Initiator.AUTHORIZED_WORKFLOW] — an explicitly authorized workflow initiator that the
 * runner only accepts together with a fresh, exact-bound owner approval consumed ATOMICALLY
 * at the FINAL PRE-ACT BOUNDARY (while CLAIMED, immediately before acting). A preflight or
 * approval rejection therefore cannot destroy an otherwise-valid approval, while a crash
 * after consumption can never pair a used approval with an unproven effect.
 *
 * Catalog controls are never weakened here: unknown capability, unauthorized initiator,
 * schema violations, blank targets, missing approvals, and unrouted capabilities are all
 * refusals BEFORE any claim or dispatch, reported as rejected-before-act so the executor
 * can release spend reservations honestly.
 */
/**
 * Mandatory commercial preflight seam (anti-bypass rule): every CUSTOMER-DIRECTED
 * capability re-checks consent/suppression/caps/honesty through [OutreachGuard] here —
 * INSIDE the side-effect transaction, between CLAIMED and ACTING — so no workflow
 * caller can reach a device primitive without passing the guard, even if planning-time
 * checks were skipped or state changed since planning.
 */
fun interface CommercialOutreachPreflight {
    /** Returns the refusal reason, or null when outreach may proceed. */
    fun refuse(customerTarget: String, content: String, capabilityId: String): String?
}

class TransactionRoutedEffects(
    private val sideEffects: SideEffectRunner,
    private val queue: TaskQueue,
    private val actions: () -> WorkflowDeviceSurface,
    private val ownerPhone: () -> String,
    private val approvalGateway: WorkflowApprovals?,
    private val commercialPreflight: CommercialOutreachPreflight? = null,
) : WorkflowEffectRouter {

    override suspend fun execute(
        capabilityId: String,
        target: String,
        content: String,
        contract: WorkContract,
        step: GraphStep,
        approvalId: Long?,
        inputs: Map<String, String>,
    ): ExecutedEffect {
        // ---- Catalog authorization + schema validation happen INSIDE SideEffectRunner;
        // ---- this pre-check exists only to classify refusals before any claim is taken.
        val spec = CapabilityCatalog.get(capabilityId)
            ?: return ExecutedEffect(
                false,
                "Capability '$capabilityId' is not registered; refusing to execute.",
                rejectedBeforeAct = true,
            )
        if (!spec.allowedInitiators.contains(Initiator.AUTHORIZED_WORKFLOW)) {
            return ExecutedEffect(
                false,
                "Capability '$capabilityId' does not authorize workflow-initiated execution (${spec.allowedInitiators}).",
                rejectedBeforeAct = true,
            )
        }
        if (approvalId != null && approvalGateway == null) {
            return ExecutedEffect(false, "An approval is bound but no approval gateway is wired; refusing to act.", rejectedBeforeAct = true)
        }
        val bindingContent = approvalBindingContent(capabilityId, content, inputs, target)
        val contentHash = ContentHashing.hash(bindingContent)
        // Atomic consume inside the transaction between claim and acting.
        val validateApproval: suspend (Long) -> Boolean = { id ->
            approvalGateway!!.consumeForExecution(id, capabilityId, target, ContentHashing.hash(approvalBindingContent(capabilityId, content, inputs, target)), contract.id, System.currentTimeMillis())
        }
        val outcome = when (capabilityId) {
            CapabilityIds.SEND_WHATSAPP -> routeChatSend(capabilityId, contract.id, step.id, target, content, approvalId, validateApproval, ChatSendKind.CONTACT)
            CapabilityIds.REPLY_WHATSAPP -> routeChatSend(capabilityId, contract.id, step.id, target, content, approvalId, validateApproval, ChatSendKind.CURRENT_CHAT)
            CapabilityIds.FOLLOW_UP_WHATSAPP -> routeChatSend(capabilityId, contract.id, step.id, target, content, approvalId, validateApproval, ChatSendKind.CONTACT)
            CapabilityIds.NOTIFY_OWNER_WHATSAPP -> {
                val owner = ownerPhone()
                routeChatSend(capabilityId, contract.id, step.id, owner, content, approvalId, validateApproval, ChatSendKind.PHONE)
            }
            CapabilityIds.POST_WHATSAPP_STATUS -> queue.withExclusiveDeviceAction {
                val surface = actions()
                val verifiers = SurfaceVerifiers(surface)
                sideEffects.execute(
                    capabilityId = capabilityId,
                    idempotencyKey = "workflow:${contract.id}:${step.id}:$contentHash",
                    target = "status", content = content,
                    initiator = Initiator.AUTHORIZED_WORKFLOW,
                    approvalId = approvalId,
                    approvalValidator = validateApproval,
                    inputs = mapOf("target" to "status", "message" to content),
                    act = { surface.transacted { postWhatsAppTextStatus(content) } },
                    verify = {
                        val snapshot = surface.snapshot()
                        verifiers.evaluatePublication(content, snapshot.packageName, snapshot.visibleText, PublicationSurfaces.WHATSAPP_PACKAGE)
                    },
                )
            }
            CapabilityIds.POST_TIKTOK -> queue.withExclusiveDeviceAction {
                val surface = actions()
                val verifiers = SurfaceVerifiers(surface)
                sideEffects.execute(
                    capabilityId = capabilityId,
                    idempotencyKey = "workflow:${contract.id}:${step.id}:$contentHash",
                    target = inputs["product_id"] ?: target, content = content,
                    initiator = Initiator.AUTHORIZED_WORKFLOW,
                    approvalId = approvalId,
                    approvalValidator = validateApproval,
                    inputs = mapOf("target" to (inputs["product_id"] ?: target), "message" to content),
                    act = { surface.transacted { postTikTok(inputs["mediaUri"] ?: "", content, publish = true) } },
                    verify = {
                        val snapshot = surface.snapshot()
                        verifiers.evaluatePublication(content, snapshot.packageName, snapshot.visibleText, PublicationSurfaces.TIKTOK_PACKAGE)
                    },
                )
            }
            CapabilityIds.APPLY_SOKO_EDIT -> queue.withExclusiveDeviceAction {
                val surface = actions()
                val product = inputs["product"] ?: inputs["listing_id"] ?: target
                val field = inputs["field"].orEmpty().lowercase()
                val value = inputs["value"].orEmpty().ifBlank { content }
                sideEffects.execute(
                    capabilityId = capabilityId,
                    idempotencyKey = "workflow:${contract.id}:${step.id}:$contentHash",
                    target = product, content = value,
                    initiator = Initiator.AUTHORIZED_WORKFLOW,
                    approvalId = approvalId,
                    approvalValidator = validateApproval,
                    inputs = mapOf("product" to product, "field" to field.ifBlank { "value" }, "value" to value),
                    preflight = { if (field.isBlank()) "apply_soko_edit needs structured listing/field/value inputs" else null },
                    act = {
                        if (!surface.openSokoEditForm(product)) false
                        else if (!surface.setFirstEditableField(value)) false
                        else surface.saveSokoEditForm()
                    },
                    verify = {
                        val reopened = surface.openSokoEditForm(product)
                        SokoSaveVerification.evaluate(
                            field.ifBlank { "value" }, value,
                            SokoSaveVerification.ReopenObservation(
                                reopened = reopened,
                                observedPackage = if (reopened) SokoSaveVerification.SOKO_PACKAGE else "",
                                fieldMatchesApprovedValue = reopened && surface.verifyEditFormFields(mapOf(field to value)),
                                observedAtMs = System.currentTimeMillis(),
                            ),
                        )
                    },
                )
            }
            else -> SideEffectOutcome.Rejected(
                "Capability '$capabilityId' has no routed device effect in the workflow executor; refusing to fake success.",
            )
        }
        return ExecutedEffect(
            verified = outcome is SideEffectOutcome.Verified ||
                outcome is SideEffectOutcome.DuplicateBlocked &&
                outcome.existingState == co.sanaa.agent.core.SideEffectState.VERIFIED,
            evidenceSummary = when (outcome) {
                is SideEffectOutcome.Verified -> "${capabilityId} verified: ${outcome.evidence.deliveryState ?: "observed"}"
                is SideEffectOutcome.DuplicateBlocked -> "${capabilityId} already verified earlier (${outcome.existingState.name})"
                is SideEffectOutcome.Failed -> "${capabilityId} failed: ${outcome.reason}"
                is SideEffectOutcome.Uncertain -> "${capabilityId} uncertain: ${outcome.reason}"
                is SideEffectOutcome.Rejected -> "${capabilityId} rejected: ${outcome.reason}"
            },
            provenNoEffect = outcome is SideEffectOutcome.Failed &&
                (outcome.reason.contains(SideEffectRunner.NON_EFFECT_BLOCKER, true) ||
                    outcome.reason.contains("never dispatched", true)),
            rejectedBeforeAct = outcome is SideEffectOutcome.Rejected,
        )
    }

    companion object {
        /**
         * Canonical approval-binding content: the draft content PLUS every structured
         * input that changes the external effect. apply_soko_edit binds its exact
         * field/value; post_tiktok binds media content, caption, product/campaign,
         * publish-vs-draft mode AND target account. The executor binds approvals
         * against this and the router consumes against the same rule, so every
         * effect-changing input is exact.
         */
        fun approvalBindingContent(capabilityId: String, content: String, inputs: Map<String, String>, target: String = ""): String =
            when (capabilityId) {
                CapabilityIds.APPLY_SOKO_EDIT ->
                    "${content}|${inputs["field"].orEmpty().lowercase()}|${inputs["value"].orEmpty().ifBlank { content }}"
                CapabilityIds.POST_TIKTOK ->
                    listOf(
                        "caption=$content",
                        "media=${inputs["mediaUri"].orEmpty()}",
                        "product=${inputs["product_id"].orEmpty()}",
                        "mode=${inputs["publishMode"] ?: "publish"}",
                        "account=$target",
                    ).joinToString("|")
                else -> content
            }
    }

    /** How the chat send reaches its exact target; dispatch happens only inside act. */
    private enum class ChatSendKind { CONTACT, CURRENT_CHAT, PHONE }

    /** Shared exact-target WhatsApp send path with delivery-bound verification. */
    private suspend fun routeChatSend(
        capabilityId: String,
        contractId: String,
        stepId: String,
        resolvedTarget: String,
        content: String,
        approvalId: Long?,
        validateApproval: suspend (Long) -> Boolean,
        kind: ChatSendKind,
    ): SideEffectOutcome = queue.withExclusiveDeviceAction {
        val surface = actions()
        val verifiers = SurfaceVerifiers(surface)
        // Set when the commercial guard refused INSIDE the transaction boundary: nothing
        // was dispatched, so the caller must classify this as rejected-before-act
        // (release spend honestly instead of parking an uncertain cost).
        var commercialGuardRefused = false
        val outcome = sideEffects.execute(
            capabilityId = capabilityId,
            idempotencyKey = "workflow:$contractId:$stepId:${ContentHashing.hash("$resolvedTarget|$content")}",
            target = resolvedTarget, content = content,
            initiator = Initiator.AUTHORIZED_WORKFLOW,
            approvalId = approvalId,
            approvalValidator = validateApproval,
            inputs = mapOf("target" to resolvedTarget, "message" to content),
            preflight = {
                // Final pre-act boundary: the commercial guard re-runs here, AFTER the
                // transaction claim and BEFORE any device primitive is touched. Owner
                // notifications (PHONE kind) are exempt — the owner is always reachable.
                if (kind == ChatSendKind.PHONE || commercialPreflight == null) null
                else commercialPreflight.refuse(resolvedTarget, content, capabilityId)?.also { commercialGuardRefused = true }
            },
            act = {
                surface.transacted {
                    when (kind) {
                        ChatSendKind.CONTACT -> sendToWhatsAppContact(resolvedTarget, content)
                        ChatSendKind.CURRENT_CHAT -> sendInCurrentChat(content)
                        ChatSendKind.PHONE -> sendToWhatsAppPhone(resolvedTarget, content)
                    }
                }
            },
            verify = { verifiers.evaluateCurrentChat(resolvedTarget, content) },
        )
        if (commercialGuardRefused && outcome is SideEffectOutcome.Failed) {
            SideEffectOutcome.Rejected(outcome.reason)
        } else outcome
    }
}
