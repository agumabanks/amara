package co.sanaa.agent.actions

import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.VerificationEvidence

/** Supported publication surfaces with their exact expected packages. */
object PublicationSurfaces {
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
    const val OBSERVATION_WINDOW_MS = 12_000L
}

/**
 * Pure publication-surface rule: content on any package other than the expected one
 * proves nothing (the predecessor accepted matching text in an arbitrary foreground app).
 */
data class PublicationSurfaceRule(val expectedPackage: String) {
    fun evaluate(content: String, observedPackage: String, contentVisible: Boolean): VerificationEvidence = when {
        observedPackage != expectedPackage -> VerificationEvidence.impossible(
            "Expected $expectedPackage but ${observedPackage.ifBlank { "no app" }} was foreground while checking the publication; matching text elsewhere proves nothing.",
            observedPackage,
        )
        !contentVisible -> VerificationEvidence.impossible(
            "${SendVerificationLogic.NO_EFFECT_PROVEN}: the published content was not visible on $expectedPackage within the observation window.",
            observedPackage,
        )
        else -> VerificationEvidence(
            verified = true, confidence = 0.8,
            observedPackage = observedPackage, deliveryState = "published",
            evidenceTimestamp = System.currentTimeMillis(),
        )
    }
}

/**
 * Facts extracted from the live Accessibility node tree about one outgoing message.
 * Roles/classes/editability decide draft-vs-sent — never raw text alone.
 */
data class MessageNodeFacts(
    /** Normalized content appears inside at least one read-only text/bubble node. */
    val inReadOnlyBubble: Boolean,
    /** Normalized content appears ONLY inside editable input fields. */
    val onlyInsideEditableField: Boolean,
    /** Delivery marker reachable from the message row or visible container. */
    val deliveryState: String?,
)

/**
 * Pure decision logic for target-bound send verification.
 * A screen change or accepted tap proves nothing; verification requires:
 * expected package + exact target identity + sent-content presence in read-only
 * bubbles outside the draft field + a delivery marker, all inside one window.
 */
object SendVerificationLogic {

    const val NO_EFFECT_PROVEN = "NO_EFFECT_PROVEN"

    fun evaluate(content: String, observation: SendObservation): VerificationEvidence {
        if (observation.observedPackage != observation.expectedPackage) {
            return VerificationEvidence.impossible(
                "Expected ${observation.expectedPackage} but ${observation.observedPackage.ifBlank { "no app" }} was foreground.",
                observation.observedPackage,
            )
        }
        if (!observation.targetVisible) {
            return VerificationEvidence.impossible(
                "The exact target chat was not identified on screen, so any visible text could belong to another conversation.",
                observation.observedPackage,
            )
        }
        if (observation.contentStillInDraftField && !observation.contentVisibleOutsideDraft) {
            return VerificationEvidence.impossible(
                "The text still exists only inside the editable draft field; it has not been sent.",
                observation.observedPackage,
            )
        }
        if (!observation.contentVisibleOutsideDraft) {
            return VerificationEvidence.impossible(
                "$NO_EFFECT_PROVEN: the message content was not found as a sent bubble in the target chat.",
                observation.observedPackage,
            )
        }
        return when (observation.deliveryState) {
            null -> VerificationEvidence(
                verified = false, confidence = 0.4,
                observedPackage = observation.observedPackage, deliveryState = null,
                evidenceTimestamp = observation.observedAtMs,
                blocker = "Content is present in the target chat, but no delivery tick/state could be read, so the send cannot be proven yet.",
            )
            else -> VerificationEvidence(
                verified = true,
                confidence = if (observation.deliveryState.equals("sent", true)) 0.85 else 0.95,
                observedPackage = observation.observedPackage,
                deliveryState = observation.deliveryState,
                evidenceTimestamp = observation.observedAtMs,
            )
        }
    }

    /**
     * Node-role evaluation of one outgoing message (golden-fixture friendly).
     * Draft detection uses editability roles: content confined to an editable input is a
     * draft; identical text in a read-only bubble is a sent copy even while the draft
     * field still holds it.
     */
    fun evaluateNodeRoles(content: String, facts: MessageNodeFacts): Pair<Boolean, Boolean> {
        val wanted = ContentHashing.normalize(content)
        if (wanted.isEmpty()) return false to false
        val readOnlyHit = facts.inReadOnlyBubble
        val draftOnly = facts.onlyInsideEditableField && !facts.inReadOnlyBubble
        return readOnlyHit to draftOnly
    }

    /** Normalized containment used by all verifiers so hashing and matching agree. */
    fun contentMatches(visibleLines: Collection<String>, content: String): Boolean {
        val wanted = ContentHashing.normalize(content)
        if (wanted.isEmpty()) return false
        return visibleLines.any { line ->
            val candidate = ContentHashing.normalize(line)
            candidate.contains(wanted) || (wanted.length >= 24 && candidate.contains(wanted.take(120)))
        }
    }

    /**
     * Legacy line-level draft detection retained only for surfaces without node access.
     * Exactly one visible line equals the whole trimmed content and no other line
     * contains it — consistent with an unsent EditText rather than a bubble plus metadata.
     */
    fun detectDraftOnly(lines: List<String>, content: String): Boolean {
        val matching = lines.filter { ContentHashing.normalize(it) == ContentHashing.normalize(content) }
        val othersContain = lines.any { ContentHashing.normalize(it) != ContentHashing.normalize(content) && contentMatches(listOf(it), content) }
        return matching.size == 1 && !othersContain
    }
}

/** Pre-action chat facts used to reject stale-tick verification. */
data class ChatPreState(
    val contentVisibleAsSentBubble: Boolean,
    val deliveryMarker: String?,
)

data class SendObservation(
    val observedPackage: String,
    val expectedPackage: String,
    val targetVisible: Boolean,
    val contentVisibleOutsideDraft: Boolean,
    val contentStillInDraftField: Boolean,
    val deliveryState: String?,
    val observedAtMs: Long,
)

/**
 * Capability-specific verifiers binding package, exact target/surface, normalized
 * content, and delivery/publication state (Phase A3, corrected). Each verifier observes
 * the real post-state on the exact expected package; none infers success from a
 * completed action or accepts matching text in an arbitrary foreground app.
 */
class TargetBoundVerifiers(private val actions: AccessibilityActions) {

    suspend fun verifyWhatsAppSend(target: String, content: String): VerificationEvidence {
        // Re-open the exact target chat so the observation binds to this conversation,
        // not whichever screen happened to be visible after the action.
        if (!actions.openWhatsAppTarget(target)) {
            return VerificationEvidence.impossible(
                "Could not reopen the '$target' chat to verify the send; the effect remains unproven.",
            )
        }
        return evaluateCurrentChat(target, content)
    }

    suspend fun verifyAttachment(target: String, caption: String): VerificationEvidence {
        // Re-open the exact target chat so the observation binds to this conversation.
        if (!actions.openWhatsAppTarget(target)) {
            return VerificationEvidence.impossible("Could not reopen the '$target' chat to verify the attachment; the effect remains unproven.")
        }
        val snapshot = actions.snapshot()
        if (snapshot.packageName != PublicationSurfaces.WHATSAPP_PACKAGE) {
            return VerificationEvidence.impossible(
                "Expected ${PublicationSurfaces.WHATSAPP_PACKAGE} but ${snapshot.packageName.ifBlank { "no app" }} was foreground.",
                snapshot.packageName,
            )
        }
        if (!snapshot.contains(target)) {
            return VerificationEvidence.impossible("The exact target chat '$target' was not identified after sending.", snapshot.packageName)
        }
        // A captioned attachment proves itself by caption; an uncaptioned one by its
        // preview/delivery markers. Without either, the result stays unverified.
        val captionBound = ContentHashing.normalize(caption).isNotBlank() &&
            SendVerificationLogic.contentMatches(snapshot.visibleText.filterNot { SendVerificationLogic.detectDraftOnly(listOf(it), caption) }, caption)
        val deliveryMarker = actions.messageDeliveryState(if (captionBound) caption.take(40) else "")
            ?: snapshot.visibleText.lastOrNull { it.equals("Sent", true) || it.equals("Delivered", true) || it.equals("Read", true) }
        return when {
            captionBound && deliveryMarker != null -> VerificationEvidence(
                verified = true, confidence = 0.9,
                observedPackage = snapshot.packageName, deliveryState = deliveryMarker,
                evidenceTimestamp = System.currentTimeMillis(),
            )
            !captionBound && deliveryMarker != null -> VerificationEvidence(
                verified = true, confidence = 0.7,
                observedPackage = snapshot.packageName, deliveryState = "$deliveryMarker (media preview)",
                evidenceTimestamp = System.currentTimeMillis(),
            )
            !captionBound -> VerificationEvidence.impossible(
                "The attachment carried no caption and no delivery marker could be read, so the send cannot be proven.",
                snapshot.packageName,
            )
            else -> VerificationEvidence(
                verified = false, confidence = 0.5,
                observedPackage = snapshot.packageName, deliveryState = null,
                evidenceTimestamp = System.currentTimeMillis(),
                blocker = "Caption is present in the target chat, but no delivery state could be read for the attachment.",
            )
        }
    }

    suspend fun verifyWhatsAppStatus(content: String): VerificationEvidence {
        val unbindable = captionlessMediaStatusEvidence(content)
        if (unbindable != null) return unbindable
        return pollPublication(
            content, "WhatsApp Status", PublicationSurfaces.WHATSAPP_PACKAGE,
        ) { evaluatePublication(actions.snapshot(), content, "WhatsApp Status", PublicationSurfaces.WHATSAPP_PACKAGE) }
    }

    suspend fun verifyTikTokPost(caption: String): VerificationEvidence =
        pollPublication(
            caption, "TikTok post", PublicationSurfaces.TIKTOK_PACKAGE,
        ) { evaluatePublication(actions.snapshot(), caption, "TikTok post", PublicationSurfaces.TIKTOK_PACKAGE) }

    suspend fun evaluateCurrentChat(target: String, content: String, preState: ChatPreState? = null): VerificationEvidence {
        val evidence = evaluateChatSnapshot(actions.snapshot(), actions.observeMessageNodes(content), target, content)
        return applyStalenessRule(evidence, content, preState)
    }

    /**
     * A delivery marker that already existed before the action proves the OLD copy, not
     * this send. When identical content plus its marker were visible pre-action, the
     * result is downgraded to unverified with a stale blocker.
     */
    private fun applyStalenessRule(evidence: VerificationEvidence, content: String, preState: ChatPreState?): VerificationEvidence {
        if (preState == null || !evidence.verified) return evidence
        val sameMarker = preState.deliveryMarker != null &&
            preState.deliveryMarker.equals(evidence.deliveryState, true)
        if (preState.contentVisibleAsSentBubble && sameMarker) {
            return VerificationEvidence.impossible(
                "An identical message with the same delivery state was already present before sending; this observation cannot prove a NEW send.",
                evidence.observedPackage,
            )
        }
        return evidence
    }

    /**
     * Pure evaluation over a chat snapshot plus node-role facts so golden fixtures can
     * drive exactly this decision path without a device.
     */
    fun evaluateChatSnapshot(
        snapshot: WhatsAppScreenSnapshot,
        nodeFacts: MessageNodeFacts,
        target: String,
        content: String,
    ): VerificationEvidence {
        val (inBubble, draftOnlyByRoles) = SendVerificationLogic.evaluateNodeRoles(content, nodeFacts)
        // Line fallback only when the node tree yielded nothing decisive.
        val lineFallbackBubble = !inBubble && !draftOnlyByRoles &&
            SendVerificationLogic.contentMatches(snapshot.visibleText.filterNot { SendVerificationLogic.detectDraftOnly(listOf(it), content) }, content)
        val observation = SendObservation(
            observedPackage = snapshot.packageName,
            expectedPackage = PublicationSurfaces.WHATSAPP_PACKAGE,
            targetVisible = snapshot.contains(target),
            contentVisibleOutsideDraft = inBubble || lineFallbackBubble,
            contentStillInDraftField = draftOnlyByRoles ||
                (!inBubble && SendVerificationLogic.detectDraftOnly(snapshot.visibleText, content)),
            deliveryState = actions.messageDeliveryState(content.take(40)) ?: nodeFacts.deliveryState
                ?: snapshot.visibleText.lastOrNull { it.equals("Sent", true) || it.equals("Delivered", true) || it.equals("Read", true) },
            observedAtMs = System.currentTimeMillis(),
        )
        return SendVerificationLogic.evaluate(content, observation)
    }

    /** Pure seam: null when the content binds; otherwise the precise refusal. */
    fun captionlessMediaStatusEvidence(content: String): VerificationEvidence? =
        if (ContentHashing.normalize(content).isBlank()) VerificationEvidence.impossible(
            "No caption is bound to this media Status, so publication cannot be proven; check the Status surface manually.",
        ) else null

    private suspend fun pollPublication(
        content: String,
        surface: String,
        expectedPackage: String,
        observeOnce: suspend () -> VerificationEvidence,
    ): VerificationEvidence {
        val deadline = System.currentTimeMillis() + PublicationSurfaces.OBSERVATION_WINDOW_MS
        var lastBlocker = "The $surface surface never became observable."
        while (System.currentTimeMillis() < deadline) {
            val evidence = observeOnce()
            if (evidence.verified) return evidence
            lastBlocker = evidence.blocker ?: lastBlocker
            kotlinx.coroutines.delay(800)
        }
        return VerificationEvidence.impossible(lastBlocker)
    }

    private fun evaluatePublication(
        snapshot: WhatsAppScreenSnapshot,
        content: String,
        surface: String,
        expectedPackage: String,
    ): VerificationEvidence = PublicationSurfaceRule(expectedPackage)
        .evaluate(content, snapshot.packageName, SendVerificationLogic.contentMatches(snapshot.visibleText, content))
}

/**
 * Pure decision for Soko approved-edit save verification (golden-fixture friendly):
 * the saved form must reopen on the Terminal package and show the exact approved value.
 */
object SokoSaveVerification {

    const val SOKO_PACKAGE = "com.soko24.soko_seller_terminal"

    data class ReopenObservation(
        val reopened: Boolean,
        val observedPackage: String,
        val fieldMatchesApprovedValue: Boolean,
        val observedAtMs: Long,
    )

    fun evaluate(fieldName: String, expectedValue: String, observation: ReopenObservation): VerificationEvidence = when {
        !observation.reopened -> VerificationEvidence.impossible(
            "Could not reopen the saved form to confirm '$fieldName'; whether the save persisted is unknown.",
            observation.observedPackage,
        )
        observation.observedPackage != SOKO_PACKAGE -> VerificationEvidence.impossible(
            "Expected $SOKO_PACKAGE after saving but ${observation.observedPackage.ifBlank { "no app" }} was foreground.",
            observation.observedPackage,
        )
        !observation.fieldMatchesApprovedValue -> VerificationEvidence.impossible(
            "${NO_EFFECT_PROVEN_SAFE_SAVE}: the reopened form did not show '$fieldName' as the approved value.",
            observation.observedPackage,
        )
        else -> VerificationEvidence(
            verified = true, confidence = 0.95,
            observedPackage = observation.observedPackage,
            deliveryState = "saved_and_reopened_matched",
            evidenceTimestamp = observation.observedAtMs,
        )
    }

    const val NO_EFFECT_PROVEN_SAFE_SAVE = "SAVE_NOT_PROVEN"
}
