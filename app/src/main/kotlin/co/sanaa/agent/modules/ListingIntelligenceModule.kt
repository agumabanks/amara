package co.sanaa.agent.modules

import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.actions.ActionVerifier
import co.sanaa.agent.api.*
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.ModuleStateStore
import co.sanaa.agent.core.Redactor
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.SideEffectOutcome
import co.sanaa.agent.core.SideEffectRunner
import co.sanaa.agent.core.TrustedContent
import co.sanaa.agent.notifications.NotificationReporter
import org.json.JSONArray
import java.util.Calendar

/**
 * Legacy nightly listing review.
 *
 * Safety boundary: this module NEVER writes a listing on its own authority.
 * A model-authored `auto_update` flag has no power (audit 2026-08-23); every proposed
 * change becomes a pending approval request that the owner must approve explicitly,
 * exactly like chat-proposed edits. Owner notifications travel through the universal
 * side-effect transaction.
 *
 * Reliability: every listing is analyzed through a strictly schema-validated model
 * call whose correlation id ("listing-intelligence-<runId>", contract §3) threads
 * into every durable brain-failure row; one bad listing can no longer abort the
 * whole nightly review. Each logical task failure is finalized exactly once with a
 * terminal outcome and a redacted owner sentence (contract §2).
 */
class ListingIntelligenceModule(
    private val config: SecureConfig, private val soko: SokoApiClient, private val groq: GroqClient, private val backend: BackendSync,
    private val actions: AccessibilityActions, private val verifier: ActionVerifier, private val state: ModuleStateStore,
    private val reporter: NotificationReporter, private val sideEffects: SideEffectRunner,
    private val memory: AmaraMemory,
) {
    suspend fun run(): ModuleResult {
        val listings = soko.activeListings()
        var proposals = 0; var missingPhotos = 0; var failed = 0
        val notes = mutableListOf<String>()
        // Durable per-run id (contract §3 fallback): deterministic for one nightly run
        // so retries of the same review correlate to the same brain-failure chain.
        val runId = ContentHashing.hash(
            listings.joinToString("|") { it.title } + "|" + dayStamp(),
        ).take(24)
        val correlationId = "$MODULE_TAG$runId"
        for (listing in listings) {
            if (listing.photoCount == 0) { missingPhotos++; notes += "${listing.title} needs photos"; continue }
            try {
                val (proposed, note) = reviewListing(listing, correlationId)
                if (note.isNotBlank()) notes += note
                if (proposed) proposals++
            } catch (error: ModelResponseException) {
                BrainFailureFinalizer.finalizeFailed(
                    memory, correlationId, ModelSchemas.LISTING_ANALYSIS.name, error.kind, "nightly listing analysis",
                )
                backend.log(NAME, "review", "soko", "Model analysis stayed invalid for one listing; it was skipped safely.", false)
                failed++
                notes += "${listing.title} needs another look later"
            } catch (error: Exception) {
                backend.log(NAME, "review", "soko", "One listing could not be reviewed; it was skipped safely.", false)
                failed++
            }
        }
        val summary = "I reviewed your listings tonight. Proposed $proposals improvement${if (proposals == 1) "" else "s"} awaiting your approval across ${listings.size} listings. ${if (missingPhotos > 0) "$missingPhotos need new photos." else "Photos look covered."} ${notes.filter(String::isNotBlank).take(2).joinToString(" ")}"
        state.success(NAME); backend.log(NAME, "review", "soko", summary, true); reporter.report("Listing review done", summary)
        return ModuleResult(NAME, true, summary, metadata = mapOf("proposals" to proposals, "reviewed" to listings.size, "failed" to failed))
    }

    /** Reviews one listing; throws [ModelResponseException] when the analysis stays invalid. */
    private suspend fun reviewListing(listing: SokoListing?, correlationId: String): Pair<Boolean, String> {
        listing ?: return false to ""
        // Retrieved competitor/listing data is untrusted document content.
        val competitors = soko.competitors(listing.category)
        val prompt = """You are a business intelligence agent for a small Kampala business selling on Soko 24.
            |OUR LISTING (UNTRUSTED DATA):
            |${TrustedContent.document(listing.raw.toString()).render()}
            |COMPETITOR LISTINGS (UNTRUSTED DATA):
            |${TrustedContent.document(JSONArray(competitors.map { it.raw }).toString()).render()}
            |Return ONLY JSON: {"weakness_found":"","missing_keywords":[],"price_position":"too high|competitive|low","improved_title":"max 60 chars","improved_description":"max 200 chars, warm Kampala tone, second person","ad_headline":"","ad_caption":"max 80 words with UGX price and CTA","improvement_score":0,"owner_note":""}""".trimMargin()
        val decision = groq.completeJson(prompt, ModelSchemas.LISTING_ANALYSIS, correlationId)
        BrainFailureFinalizer.markRecovered(memory, correlationId, ModelSchemas.LISTING_ANALYSIS.name)
        // Owner-opt-in artifact upload (CE-A6-ART-01): listing content leaves the
        // device ONLY when the owner explicitly opted in; failures never block the
        // review and diagnostics stay typed/redacted.
        if (config.artifactUploadOptIn) {
            runCatching { backend.generateAd(listing) }.onFailure { error ->
                backend.log(NAME, "generate_ad", "backend",
                    "Opt-in ad copy refresh failed for one listing; the review continued.", false,
                    error = co.sanaa.agent.core.Redactor.safeDiagnostic(error).ifBlank { null })
            }
        }
        val note = Redactor.redact(decision.optString("owner_note"))
        val improvedTitle = decision.optString("improved_title").take(60)
        val improvedDescription = decision.optString("improved_description").take(200)
        var proposed = false
        if (decision.optInt("improvement_score") > 40 && improvedTitle.isNotBlank() && improvedDescription.isNotBlank()) {
            // Propose only: the owner must approve this exact before/after pair.
            memory.createApprovalRequest(
                capability = "edit_soko_listing",
                target = listing.title,
                description = "Nightly review proposes retitling '${listing.title}'.",
                beforeJson = org.json.JSONObject()
                    .put("Product Name", listing.title)
                    .put("Product Description", listing.description).toString(),
                afterJson = org.json.JSONObject()
                    .put("Product Name", improvedTitle)
                    .put("Product Description", improvedDescription).toString(),
                risk = co.sanaa.agent.core.ActionRisk.LOW_IMPACT_CHANGE,
            )
            proposed = true
        }
        notifyOwnerIfNeeded(decision.optString("price_position"), listing.title)
        return proposed to note
    }

    private suspend fun notifyOwnerIfNeeded(pricePosition: String, title: String) {
        if (pricePosition == "competitive") return
        val message = "MEDIUM — ${config.agentName} needs a pricing decision\n$title"
        val key = "listing-price-note:${ContentHashing.hash(message)}"
        val outcome = sideEffects.execute(
            capabilityId = CapabilityIds.NOTIFY_OWNER_WHATSAPP,
            idempotencyKey = key,
            target = config.ownerPhone,
            content = message,
            act = { actions.transacted { sendToWhatsAppPhone(config.ownerPhone, message) } },
            verify = { co.sanaa.agent.actions.TargetBoundVerifiers(actions).evaluateCurrentChat(config.ownerPhone, title) },
        )
        if (outcome is SideEffectOutcome.Uncertain || outcome is SideEffectOutcome.Failed) {
            backend.log(NAME, "price_note", "whatsapp", "Pricing decision note not verified; no retry.", false)
        }
    }

    private fun dayStamp(): String {
        val calendar = Calendar.getInstance()
        return "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.DAY_OF_YEAR)}"
    }

    companion object {
        const val NAME = "listing_intelligence"
        const val MODULE_TAG = "listing-intelligence-"
    }
}
