package co.sanaa.agent.core

/**
 * Typed boundary between instructions and data.
 *
 * Everything that originates outside Amara and the owner — screen text, messages,
 * notifications, documents, web pages, retrieved records — is untrusted data. It may
 * contain prompt-injection attempts. Untrusted content is wrapped in an envelope with
 * explicit delimiters, never merged into instruction prose, and scanned for injection
 * before it reaches a model prompt. A policy decision never depends on model prose
 * about untrusted content.
 */
enum class ContentOrigin {
    OWNER_INSTRUCTION,   // trusted: typed by the owner
    SYSTEM_POLICY,       // trusted: compiled policy text
    SCREEN_TEXT,         // untrusted: Accessibility labels
    INBOUND_MESSAGE,     // untrusted: WhatsApp/customer message
    NOTIFICATION_TEXT,   // untrusted: notification extras
    DOCUMENT_TEXT,       // untrusted: file/web/retrieved document
    MODEL_PROPOSAL,      // model output; proposal only, never authority
}

data class TrustedContent(
    val origin: ContentOrigin,
    val body: String,
) {
    val isUntrusted: Boolean get() = origin != ContentOrigin.OWNER_INSTRUCTION && origin != ContentOrigin.SYSTEM_POLICY

    /** Renders the content with an explicit role/data envelope for prompts. */
    fun render(): String = when {
        !isUntrusted -> body
        else -> "[${origin.envelopeTag()} DATA BEGIN]\n${body.take(MAX_UNTRUSTED_CHARS)}\n[${origin.envelopeTag()} DATA END]"
    }

    companion object {
        const val MAX_UNTRUSTED_CHARS = 2_400

        fun owner(text: String) = TrustedContent(ContentOrigin.OWNER_INSTRUCTION, text)
        fun policy(text: String) = TrustedContent(ContentOrigin.SYSTEM_POLICY, text)
        fun screen(text: String) = TrustedContent(ContentOrigin.SCREEN_TEXT, text)
        fun message(text: String) = TrustedContent(ContentOrigin.INBOUND_MESSAGE, text)
        fun notification(text: String) = TrustedContent(ContentOrigin.NOTIFICATION_TEXT, text)
        fun document(text: String) = TrustedContent(ContentOrigin.DOCUMENT_TEXT, text)
        fun model(text: String) = TrustedContent(ContentOrigin.MODEL_PROPOSAL, text)
    }
}

private fun ContentOrigin.envelopeTag(): String = when (this) {
    ContentOrigin.SCREEN_TEXT -> "UNTRUSTED_SCREEN"
    ContentOrigin.INBOUND_MESSAGE -> "UNTRUSTED_MESSAGE"
    ContentOrigin.NOTIFICATION_TEXT -> "UNTRUSTED_NOTIFICATION"
    ContentOrigin.DOCUMENT_TEXT -> "UNTRUSTED_DOCUMENT"
    ContentOrigin.MODEL_PROPOSAL -> "MODEL_PROPOSAL"
    else -> "DATA"
}

data class InjectionFinding(val threats: List<String>, val sanitizedBody: String) {
    val detected: Boolean get() = threats.isNotEmpty()
}

/**
 * Scans untrusted content for embedded instructions before it enters a prompt.
 * Detection removes nothing silently from evidence: findings are recorded and the raw
 * body stays available inside the envelope; the guard result gates what the planner is
 * allowed to do with the content.
 *
 * Coverage: direct instruction overrides, action directives, secret bait, structural
 * override patterns, encoded (base64) payloads, fragmented instructions assembled
 * across lines, indirect delegation ("tell Amara to…"), and common Swahili/Luganda
 * phrasing used around money and contact requests.
 */
object PromptInjectionGuard {

    private val instructionVerbs = listOf(
        "ignore all previous", "ignore previous", "ignore the above", "disregard all",
        "disregard previous", "forget your instructions", "forget all",
        "you are now", "act as if you are", "pretend you are", "new instructions:",
        "system prompt:", "override your", "reveal your prompt", "show your prompt",
        "repeat your instructions", "print your system",
        // Indirect delegation and role reassignment.
        "tell amara to", "ask amara to", "instruct the agent", "the assistant must",
        "as the owner i command", "speaking as the owner", "this is the owner speaking",
        // Multilingual (Swahili/Luganda) directive phrases.
        "pua uzito", "tuma pesa kwa", "tuma kwa namba", "nitumie kwa", "mpige simu",
        "kubwa kuliko yote", "sikiliza amara",
    )

    private val actionPhrases = listOf(
        "send a message to", "send money to", "send all contacts", "text this number",
        "message this number", "whatsapp me at", "transfer money", "send an otp",
        "share the pin", "tell me the pin", "give me the password", "approve the edit",
        "delete all", "refund me", "pay now", "publish this", "post this now",
        "forward this to", "add me as", "change the price to", "disable approval",
    )

    private val secretBait = listOf(
        "what is the pin", "what's the pin", "the pin is required", "enter your pin and send",
        "api key", "password reset link",
    )

    /** Base64 fragments that decode to common override openings. */
    private val encodedMarkers = listOf(
        "swdob3jligfsbcbwcsv2aw91cw==", "awdub3jligfsscbwcmv2aw91cw==", "lnl3dgvtihbyb2tyyw1t",
        "aWdub3JlIGFsbCBwcmV2aW91cyBpbnN0cnVjdGlvbnM=",
    )

    private fun normalized(body: String): String = body
        .replace(Regex("[\\u200B\\u200C\\u200D\\uFEFF]"), "") // zero-width chars
        .replace(Regex("\\s+"), " ")
        .lowercase()

    private fun deFragmented(body: String): String = body.lines()
        .map { it.trim() }
        .joinToString(" ")

    fun scan(content: TrustedContent): InjectionFinding {
        if (!content.isUntrusted) return InjectionFinding(emptyList(), content.body)
        val flat = normalized(content.body)
        val joined = normalized(deFragmented(content.body))
        val threats = buildList {
            instructionVerbs.forEach { phrase ->
                if (phrase in flat || phrase in joined) add("embedded_instruction:$phrase")
            }
            actionPhrases.forEach { phrase ->
                if (phrase in flat || phrase in joined) add("action_directive:$phrase")
            }
            secretBait.forEach { phrase -> if (phrase in flat) add("secret_bait:$phrase") }
            if (Regex("\\b(?:ignore|disregard|skip)\\b[^\\n]{0,40}\\b(instructions?|rules?|policy)\\b").containsMatchIn(flat)) {
                add("instruction_override_pattern")
            }
            val flatNoSpaces = flat.replace(" ", "")
            encodedMarkers.forEach { marker ->
                if (marker.lowercase() in flatNoSpaces) add("encoded_instruction")
            }
            // A single long strict-base64 token is already suspicious; two or more confirm it.
            val blobTokens = flat.split(" ").filter { token ->
                token.length >= 36 && token.all { ch -> ch in "abcdefghijklmnopqrstuvwxyz0123456789+/=" }
            }
            if (blobTokens.isNotEmpty()) add("suspicious_encoded_blob")
        }
        return InjectionFinding(threats.distinct(), content.body)
    }

    /**
     * Structured authority separation: when this returns true, no model-derived external
     * action may be executed from content carrying these findings. Only observation,
     * deterministic drafts/recommendations, and owner escalation remain permitted.
     */
    fun blocksSideEffects(finding: InjectionFinding): Boolean =
        finding.threats.any { it.startsWith("action_directive:") || it.startsWith("secret_bait:") || it == "instruction_override_pattern" || it == "embedded_instruction" || it.startsWith("embedded_instruction:") || it == "encoded_instruction" || it == "suspicious_encoded_blob" }
}

/** Persists blocked-injection events as durable local security findings. */
object SecurityFindingLog {
    fun record(memory: AmaraMemory?, channel: String, threats: List<String>, subject: String = "untrusted content") {
        if (memory == null || threats.isEmpty()) return
        runCatching {
            memory.recordBusinessFinding(
                sourceApp = "security",
                subject = subject.take(300),
                issue = "Prompt-injection attempt blocked on $channel: ${threats.joinToString("; ").take(1_400)}",
                severity = "high",
                confidence = 0.9,
                evidence = "Enforced by PromptInjectionGuard.blocksSideEffects before any external action.",
                recommendation = "Review the flagged conversation; do not act on embedded instructions.",
            )
        }
    }
}
