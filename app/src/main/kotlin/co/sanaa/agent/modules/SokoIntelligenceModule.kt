package co.sanaa.agent.modules

import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.ActionRisk
import co.sanaa.agent.core.SokoCredentialAuditor
import co.sanaa.agent.api.BrainFailureFinalizer
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.api.ModelFailureKind
import co.sanaa.agent.api.ModelResponseException
import co.sanaa.agent.api.ModelSchemas
import org.json.JSONObject

data class SokoIntelligenceResult(
    val success: Boolean,
    val summary: String,
    val alerts: List<co.sanaa.agent.actions.SokoAlert> = emptyList(),
    val bookings: List<co.sanaa.agent.actions.SokoBooking> = emptyList(),
)

/** Grounded, read-only business intelligence gathered from the two Soko phone apps. */
class SokoIntelligenceModule(
    private val config: SecureConfig,
    private val actions: AccessibilityActions,
    private val memory: AmaraMemory,
    private val groq: GroqClient,
    /**
     * Credential authority seam. Fail-closed default: a construction site that
     * forgets to inject the vault-backed provider can never authenticate with
     * any secret. Production injects AgentRuntime's lockout-gated provider.
     */
    private val pin: () -> String = { "" },
    /** Records accepted/rejected terminal logins onto the vault; null disables auditing. */
    private val credentialAudit: SokoCredentialAuditor? = null,
) {

    suspend fun bookingsNeedingAction(): SokoIntelligenceResult {
        val currentPin = submittablePin("soko_bookings")
            ?: return SokoIntelligenceResult(false, PIN_WITHHELD_SUMMARY)
        val scan = actions.readSokoNeedsActionBookings(currentPin)
        auditLoginOutcome(scan.failure)
        val failure = scan.failure
        if (failure != null) return record("soko_bookings", false, failure)
        val summary = if (scan.bookings.isEmpty()) {
            "I opened Terminal Alerts → Needs action → Bookings and found no visible bookings waiting for action."
        } else {
            scan.bookings.forEach { booking ->
                memory.recordBusinessFinding(
                    "Soko Terminal", "${booking.service} — ${booking.customer}", "Booking is waiting for action",
                    "high", 1.0, "Terminal Alerts → Needs action → Bookings showed ${booking.service} for ${booking.customer}.",
                    "Review the booking and explicitly choose Confirm, Complete, or Cancel.",
                )
            }
            "I opened Terminal Alerts → Needs action → Bookings and found ${scan.bookings.size} visible booking${if (scan.bookings.size == 1) "" else "s"} waiting for action: " +
                scan.bookings.joinToString("; ") { "${it.service} for ${it.customer}" } +
                ". Available actions in Terminal are Confirm, Complete, or Cancel; I did not change any booking."
        }
        return record("soko_bookings", true, summary, bookings = scan.bookings)
    }

    suspend fun auditServices(): SokoIntelligenceResult {
        val currentPin = submittablePin("soko_service_audit")
            ?: return SokoIntelligenceResult(false, PIN_WITHHELD_SUMMARY)
        val scan = actions.auditSokoServices(currentPin)
        auditLoginOutcome(scan.failure)
        val failure = scan.failure
        if (failure != null) return record("soko_service_audit", false, failure)
        if (scan.listings.isEmpty()) return record("soko_service_audit", false, "I reached the Services manager but could not parse a service listing safely.")

        scan.listings.forEach { item ->
            memory.recordProductSeen(
                "Soko Terminal Services", item.name, item.priceUgx,
                "${item.status}. ${item.description}", if (item.weakReasons.isEmpty()) 1.0 else 0.5,
            )
        }
        val weak = scan.listings.filter { it.weakReasons.isNotEmpty() }
        weak.forEach { listing ->
            listing.weakReasons.forEach { issue ->
                memory.recordBusinessFinding(
                    "Soko Terminal Services", listing.name, issue,
                    if (issue.contains("price") || issue.contains("match")) "high" else "medium", 0.8,
                    "${listing.status}; price=${listing.priceUgx ?: "not shown"}; description=${listing.description.take(500)}",
                    when {
                        issue.contains("price") -> "Review and set an accurate service price after owner approval."
                        issue.contains("match") -> "Review the title and description together, then approve a consistent rewrite."
                        issue.contains("description") -> "Add a clear buyer-focused description with scope, outcome, and accurate terms."
                        else -> "Replace the title with a specific buyer-facing service name."
                    },
                )
            }
        }
        val proposalCount = proposeTextImprovements(weak)
        val coverage = when {
            scan.reachedEnd && scan.advertisedTotal != null && scan.listings.size >= scan.advertisedTotal -> "reached the visible end and matched the advertised total"
            scan.reachedEnd -> "reached the visible end"
            else -> "stopped at the safety limit"
        }
        val quality = if (weak.isEmpty()) {
            "No missing-price, thin-description, or obvious title/description mismatch was visible. Image/title matching still requires visual inspection and is reported separately rather than guessed."
        } else {
            "${weak.size} listings need follow-up: " + weak.take(12).joinToString("; ") { "${it.name} (${it.weakReasons.joinToString()})" } +
                if (weak.size > 12) "; plus ${weak.size - 12} more recorded in memory." else "."
        }
        val proposalSummary = if (proposalCount > 0) " I prepared $proposalCount exact text proposal${if (proposalCount == 1) "" else "s"} in Work control for approval." else ""
        val summary = "I audited ${scan.listings.size}${scan.advertisedTotal?.let { " of $it advertised" }.orEmpty()} services across ${scan.screensRead} screen states and $coverage. $quality$proposalSummary I made no edits."
        return record("soko_service_audit", true, summary)
    }

    private suspend fun proposeTextImprovements(weak: List<co.sanaa.agent.actions.SokoServiceListing>): Int {
        if (weak.isEmpty() || config.groqApiKey.isBlank()) return 0
        val candidates = weak.take(5)
        val payload = candidates.joinToString("\n") { listing ->
            "- NAME: ${listing.name}\n  DESCRIPTION: ${listing.description.take(350)}\n  VISIBLE ISSUES: ${listing.weakReasons.joinToString()}"
        }
        val prompt =
            """Prepare safe text-only improvements for these Soko Terminal service listings.
                |$payload
                |Rules: current_name must exactly match an input NAME. Keep all facts grounded in the current text. Do not invent turnaround, materials, prices, discounts, delivery, guarantees, contact details, or capabilities. A proposal may improve title and description only. Return concise Kampala-friendly buyer language.
                |Return ONLY JSON: {"proposals":[{"current_name":"","proposed_name":"","proposed_description":"","reason":""}]}""".trimMargin()
        // Contract §3 correlation threading (REQ-3-01): this module has no durable
        // run row of its own, so each proposal task gets a fresh UUID under the
        // module prefix; the same id finalizes the task's brain-failure rows.
        val correlationId = "soko-intelligence-${java.util.UUID.randomUUID()}"
        val schema = ModelSchemas.SOKO_SERVICE_TEXT_PROPOSALS
        val attempt = runCatching { groq.completeJson(prompt, schema, correlationId) }
        attempt.fold(
            onSuccess = { runCatching { BrainFailureFinalizer.markRecovered(memory, correlationId, schema.name) } },
            onFailure = { failure ->
                runCatching {
                    val kind = (failure as? ModelResponseException)?.kind ?: ModelFailureKind.TRANSPORT
                    BrainFailureFinalizer.finalizeFailed(
                        memory, correlationId, schema.name, kind,
                        "Soko service listing text proposals",
                    )
                }
            },
        )
        val response = attempt.getOrNull() ?: return 0
        val byName = candidates.associateBy { it.name }
        val proposals = response.optJSONArray("proposals") ?: return 0
        var created = 0
        for (index in 0 until proposals.length()) {
            val proposal = proposals.optJSONObject(index) ?: continue
            val currentName = proposal.optString("current_name").trim()
            val current = byName[currentName] ?: continue
            val proposedName = proposal.optString("proposed_name").trim()
            val proposedDescription = proposal.optString("proposed_description").trim()
            if (proposedName.length !in 5..220 || proposedDescription.length !in 35..1_500) continue
            val before = JSONObject().put("title", current.name).put("description", current.description)
            val after = JSONObject().put("title", proposedName).put("description", proposedDescription)
            memory.createApprovalRequest(
                "edit_soko_listing_text", current.name,
                "${proposal.optString("reason").trim().ifBlank { "Improve the weak service listing text." }} Review the exact before/after fields before saving.",
                before.toString(), after.toString(), ActionRisk.LOW_IMPACT_CHANGE,
            )
            created++
        }
        return created
    }

    suspend fun alertsNeedingAction(): SokoIntelligenceResult {
        val currentPin = submittablePin("soko_alerts")
            ?: return SokoIntelligenceResult(false, PIN_WITHHELD_SUMMARY)
        val scan = actions.readSokoNeedsActionAlerts(currentPin)
        auditLoginOutcome(scan.failure)
        scan.failure?.let { return record("soko_alerts", false, it) }
        scan.alerts.forEach { alert ->
            memory.recordBusinessFinding(
                "Soko Terminal Alerts", alert.subject, "${alert.type} needs action", if (alert.type == "Low stock") "high" else "medium",
                1.0, "${alert.subject}: ${alert.detail}",
                if (alert.type == "Low stock") "Review reorder level and replenish stock." else "Open the item and review the available action before changing it.",
            )
        }
        val summary = if (scan.alerts.isEmpty()) {
            "I opened Terminal Alerts → Needs action and found no visible items waiting for action."
        } else {
            "I found ${scan.alerts.size} visible Terminal items needing attention: " +
                scan.alerts.joinToString("; ") { "${it.type}: ${it.subject} — ${it.detail}" } + ". I made no changes."
        }
        return record("soko_alerts", true, summary, alerts = scan.alerts)
    }

    suspend fun auditBuyerServices(): SokoIntelligenceResult {
        val scan = actions.auditSokoBuyerServices()
        scan.failure?.let { return record("soko_buyer_services", false, it) }
        if (scan.services.isEmpty()) return record("soko_buyer_services", false, "I reached Soko Buyer Services but could not parse a service card safely.")
        scan.services.forEach { service ->
            memory.recordProductSeen(
                "Soko Buyer Services", service.name, service.priceUgx,
                "Seller ${service.seller}; ${service.turnaround}; rating ${service.rating ?: "not shown"}; reviews ${service.reviewCount ?: "not shown"}.",
                if (service.weakReasons.isEmpty()) 1.0 else 0.5,
            )
        }
        val coverage = if (scan.reachedEnd) "reached the visible end" else "stopped at the safety limit"
        val summary = "I read ${scan.services.size}${scan.advertisedTotal?.let { " of $it advertised" }.orEmpty()} buyer-visible services across ${scan.screensRead} screen states and $coverage. " +
            scan.services.take(10).joinToString("; ") { "${it.name} by ${it.seller}, UGX ${it.priceUgx ?: "price not shown"}, ${it.turnaround}" } +
            if (scan.services.size > 10) "; plus ${scan.services.size - 10} more recorded in memory." else "."
        return record("soko_buyer_services", true, summary)
    }

    fun storedShopHealth(): SokoIntelligenceResult {
        val findings = memory.openBusinessFindings()
        val summary = if (findings.isEmpty()) {
            "I have no open verified shop-health findings in memory. Run a fresh Soko audit before assuming the shop is clear."
        } else {
            val bySeverity = findings.groupingBy { it.severity }.eachCount()
            "I have ${findings.size} open verified shop findings (${bySeverity.entries.joinToString { "${it.value} ${it.key}" }}): " +
                findings.take(12).joinToString("; ") { "${it.subject} — ${it.issue}. ${it.recommendation}" } +
                if (findings.size > 12) "; plus ${findings.size - 12} more." else "."
        }
        return SokoIntelligenceResult(true, summary)
    }

    private fun record(
        type: String,
        success: Boolean,
        summary: String,
        alerts: List<co.sanaa.agent.actions.SokoAlert> = emptyList(),
        bookings: List<co.sanaa.agent.actions.SokoBooking> = emptyList(),
    ): SokoIntelligenceResult {
        memory.recordAction(type, null, "Soko Terminal", type, "Inspected live Soko screens without editing.", summary, null, success)
        return SokoIntelligenceResult(success, summary, alerts, bookings)
    }

    /**
     * Blank credentials can never reach authentication: when the authority hands
     * out nothing (unconfigured, incomplete, or LOCKED), the flow stops before
     * any login surface is touched. Returns null (and records the refusal).
     */
    private fun submittablePin(stage: String): String? {
        val candidate = runCatching { pin() }.getOrDefault("")
        if (candidate.isBlank()) {
            record(stage, false, PIN_WITHHELD_SUMMARY)
            return null
        }
        return candidate
    }

    /**
     * Feeds every terminal-login outcome back onto the vault so lockout reflects
     * PRODUCTION attempts. Only two shapes are conclusive: success (no failure)
     * and the static PIN-rejection marker; everything else (launch failures,
     * accessibility loss) says nothing about the secret and changes no counter.
     */
    internal fun auditLoginOutcome(failure: String?) {
        val auditor = credentialAudit ?: return
        when {
            failure == null -> runCatching { auditor.onLoginAccepted() }
            failure.contains(PIN_REJECTION_MARKER, ignoreCase = true) ->
                runCatching { auditor.onLoginRejected(AUTH_REJECTED_CODE) }
            else -> Unit
        }
    }

    companion object {
        /** Static substring produced by AccessibilityActions for a rejected staff PIN. */
        const val PIN_REJECTION_MARKER = "PIN was not accepted"
        /** Static redacted code fed to the vault; never carries terminal text. */
        const val AUTH_REJECTED_CODE = "soko_auth: stored Terminal PIN rejected by Staff Login"
        /** Owner-facing refusal issued instead of touching auth with a blank PIN. */
        const val PIN_WITHHELD_SUMMARY =
            "I could not use Soko Terminal because no usable Terminal PIN is available in the secure vault; " +
                "the owner must configure it or unlock it after repeated rejections."
    }
}
