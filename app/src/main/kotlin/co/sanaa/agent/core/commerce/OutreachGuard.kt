package co.sanaa.agent.core.commerce

import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.QuietHoursPolicy

/**
 * Mechanical anti-spam and customer-protection enforcement. Every customer-directed
 * commercial send passes [gate] BEFORE any approval request or transaction is created.
 *
 * Enforced here (never advisory): consent/contact eligibility, quiet hours, opt-out
 * suppression (immediate — the same call that records an opt-out also blocks), message
 * deduplication, per-recipient frequency caps, daily global caps, and the honesty scan:
 * deceptive scarcity, invented discounts, fabricated stock/popularity/testimonials/
 * savings/delivery promises all refuse with a precise reason.
 */
class OutreachGuard(
    private val store: RevenueStore,
    private val policy: () -> CommercialPolicy,
    /** Consent ledger lookup: true only for contacts with explicit owner-granted eligibility. */
    private val consentLookup: (contactKey: String) -> Boolean,
) {

    sealed class Verdict {
        data class Allow(val dedupeKey: String) : Verdict()
        data class Block(val reason: String) : Verdict()
        data class SuppressAndBlock(val reason: String) : Verdict()
    }

    /**
     * Full gate. [messageDedupeKey] should be derived from target+content; identical
     * content to the same recipient inside [DEDUP_WINDOW_MS] is blocked as a repeat.
     */
    fun gate(
        contactKey: String,
        productRef: String?,
        channel: String,
        content: String,
        localTime: java.time.LocalTime,
        messagesToCustomerToday: Int,
        messagesToCustomerInWindow: Int,
        globalMessagesToday: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): Verdict {
        // 1. Suppression first — immediate after any opt-out.
        if (store.isSuppressed(contactKey)) {
            return Verdict.Block("contact is on the suppression list")
        }
        // 2. Consent/eligibility.
        if (!consentLookup(contactKey)) {
            return Verdict.Block("no recorded contact eligibility/consent for this customer")
        }
        // 3. Honesty scan before anything else consumes budget or attention.
        honestyScan(content)?.let { return Verdict.Block(it) }
        // 4. Policy limits.
        val verdict = policy().evaluateOutreachEligibility(
            productRef = productRef, channel = channel, localTime = localTime,
            messagesToCustomerToday = messagesToCustomerToday + messagesToCustomerInWindow,
            globalMessagesToday = globalMessagesToday,
            followUpsSentForThisOpportunity = 0,
        )
        if (verdict is CommercialPolicy.PolicyVerdict.Blocked) {
            return if (verdict.reason.contains("cap") || verdict.reason.contains("Quiet")) {
                Verdict.Block(verdict.reason)
            } else Verdict.Block(verdict.reason)
        }
        // 5. Deduplication of identical outreach.
        val dedupeKey = ContentHashing.hash("outreach|$contactKey|${ContentHashing.hash(content)}")
        val recent = store.commercialActions(state = "EXECUTED_VERIFIED").any {
            it.dedupeKey == dedupeKey && nowMs - it.updatedAtMs < DEDUP_WINDOW_MS
        } || store.commercialActions(state = "AWAITING_APPROVAL").any { it.dedupeKey == dedupeKey }
        if (recent) return Verdict.Block("identical outreach already sent or awaiting approval inside the dedup window")
        return Verdict.Allow(dedupeKey)
    }

    /** Opt-outs suppress IMMEDIATELY and permanently until the owner removes them. */
    fun recordOptOut(contactKey: String, reason: String, nowMs: Long): Boolean =
        store.suppressContact(contactKey, "opt-out: $reason", nowMs)

    /** Complaint/refund/dispute/vulnerability escalations stop proactive work pending owner review. */
    fun escalationRequired(kind: EscalationKind, detail: String): Boolean = when (kind) {
        EscalationKind.COMPLAINT -> policy().stopOnComplaint
        EscalationKind.REFUND_SPIKE -> policy().stopOnRefundSpike
        EscalationKind.DISPUTE -> true
        EscalationKind.VULNERABLE_CUSTOMER -> true
        EscalationKind.NEGATIVE_REPLY_SPIKE -> policy().stopOnNegativeReplySpike
    }.also { if (it) store.suppressContact("__escalation__${kind.name}", detail.take(300), System.currentTimeMillis()) }

    enum class EscalationKind { COMPLAINT, REFUND_SPIKE, DISPUTE, VULNERABLE_CUSTOMER, NEGATIVE_REPLY_SPIKE }

    /**
     * Honesty scan: returns the refusal reason for fabricated claims. Patterns are
     * conservative (high precision): they fire on absolute/fabrication phrasing only.
     */
    fun honestyScan(content: String): String? {
        val text = content.lowercase()
        Regex("(only|last|final)\\s+\\d*\\s*(one|1|two|2|three|3|piece|items?)\\s+(left|remaining)").find(text)?.let {
            return "deceptive scarcity claim (${it.value}) — stock facts must come from verified Soko inventory"
        }
        Regex("\\b(\\d{1,3})\\s?%\\s+(off|discount)\\b").find(text)?.let { match ->
            val claimed = match.groupValues[1].toIntOrNull() ?: 0
            if (policy().discountCeilingPercent <= 0 || claimed > policy().discountCeilingPercent) {
                return "invented discount (${match.value}); no approved discount covers it"
            }
        }
        Regex("(everyone|everybody|all our customers) (is|are)? ?buying").find(text)?.let {
            return "fabricated popularity claim (${it.value.trim()})"
        }
        listOf("testimonials", "customer reviews say", "people are saying that").forEach { phrase ->
            if (phrase in text && "verified" !in text) return "unverifiable testimonial reference ('$phrase')"
        }
        Regex("save\\s+(?:up to\\s+)?(?:ugx\\s?)?[\\d,]{4,}").find(text)?.let {
            return "unverified savings promise (${it.value})"
        }
        Regex("(guaranteed|instant|same[- ]day|24[- ]hour) delivery").find(text)?.let {
            return "fabricated delivery promise (${it.value}) — no verified logistics commitment exists"
        }
        Regex("(selling fast|almost gone|hurry)\\b").find(text)?.let {
            return "pressure/scarcity filler without inventory evidence (${it.value})"
        }
        return null
    }

    companion object {
        const val DEDUP_WINDOW_MS = 7 * 24 * 60 * 60 * 1_000L
    }
}
