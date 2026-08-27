package co.sanaa.agent.modules

import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.actions.ActionVerifier
import co.sanaa.agent.actions.TargetBoundVerifiers
import co.sanaa.agent.api.BackendSync
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.api.ModuleResult
import co.sanaa.agent.api.SokoApiClient
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.core.Initiator
import co.sanaa.agent.core.ModuleStateStore
import co.sanaa.agent.core.Redactor
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.SideEffectOutcome
import co.sanaa.agent.core.SideEffectRunner
import co.sanaa.agent.core.TrustedContent
import co.sanaa.agent.core.work.BroadcastKeys
import co.sanaa.agent.notifications.NotificationReporter
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Initiator per broadcast leg; overridable so tests and catalog fixes can align them. */
data class BroadcastInitiators(
    val status: Initiator = Initiator.RECURRING_SCHEDULE,
    val group: Initiator = Initiator.INTERNAL_RUNTIME,
    val tiktok: Initiator = Initiator.RECURRING_SCHEDULE,
)

internal enum class BroadcastLegState { VERIFIED, DUPLICATE_SUPPRESSED, FAILED, UNCERTAIN, SKIPPED }

internal data class BroadcastLeg(val leg: String, val state: BroadcastLegState, val detail: String) {
    fun toMetadata(): Map<String, String> = mapOf("leg" to leg, "state" to state.name, "detail" to detail)
}

class MorningBroadcastModule(
    private val config: SecureConfig, private val soko: SokoApiClient, private val groq: GroqClient,
    private val backend: BackendSync, private val actions: AccessibilityActions, private val verifier: ActionVerifier,
    private val state: ModuleStateStore, private val reporter: NotificationReporter,
    private val sideEffects: SideEffectRunner,
    /** Durable sanitized failure records; null keeps legacy silent behavior in old tests. */
    private val memory: AmaraMemory? = null,
    private val initiators: BroadcastInitiators = BroadcastInitiators(),
) {
    suspend fun run(): ModuleResult {
        return try {
        val previous = state.string("last_broadcast_ids").split(',').filter(String::isNotBlank).toSet()
        val listings = soko.activeListings().sortedByDescending { it.viewCount }.filterNot { it.id in previous }.take(3)
        if (listings.isEmpty()) return fail(memory, "No eligible active Soko listings were returned")
        val today = LocalDate.now()
        // Retrieved listing data is untrusted document content inside the prompt.
        val products = listings.mapIndexed { index, item -> "PRODUCT_${index + 1}: ${item.title}, UGX ${item.priceUgx}, ${item.description.take(100)}" }.joinToString("\n")
        val prompt = """Write a morning WhatsApp broadcast for a small business in Kampala. Warm, energetic, personal tone, like a market vendor greeting regulars.
            |${TrustedContent.document(products).render()}
            |Rules: under 80 words, max 3 emojis, end with a clear call to action, and never say seamless, leverage, or innovative.
            |Today is ${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}, $today.
            |Return ONLY JSON: {"broadcast_message":"","tiktok_caption":"","featured_product":""}""".trimMargin()
        val dayKey = today.toString()
        val correlationId = "$NAME-$dayKey"
        val broadcastStage = co.sanaa.agent.api.ModelSchemas.BROADCAST_PLAN.name
        val decision = when (val planned = modelCall { groq.completeJson(prompt, co.sanaa.agent.api.ModelSchemas.BROADCAST_PLAN, correlationId) }) {
            is ModelCall.Failure -> {
                runCatching {
                    (planned.error as? co.sanaa.agent.api.ModelResponseException)?.let { failure ->
                        co.sanaa.agent.api.BrainFailureFinalizer.finalizeFailed(memory, correlationId, broadcastStage, failure.kind, "morning-broadcast")
                    }
                }
                memory?.recordFailure(
                    taskId = NAME, runId = today.toString(), stepId = "plan", capability = NAME,
                    targetPackage = "multi", stage = STAGE_BROADCAST,
                    cause = Redactor.safeDiagnostic(planned.error).ifBlank { "model call failed" },
                    retryable = (planned.error as? co.sanaa.agent.api.ModelResponseException)?.kind?.retryable ?: true,
                    attemptCount = (planned.error as? co.sanaa.agent.api.ModelResponseException)?.attemptCount?.coerceAtLeast(1) ?: 1,
                    screenEvidenceJson = "{}",
                    correctiveAction = "Retry on the next scheduled run", disposition = "FAILED_PERMANENT",
                    nextSafeAction = "Do not post anything; nothing was composed or sent",
                )
                return fail(memory, "Morning broadcast could not compose content safely; nothing was posted")
            }
            is ModelCall.Success -> planned.value
        }
        runCatching { co.sanaa.agent.api.BrainFailureFinalizer.markRecovered(memory, correlationId, broadcastStage) }
        val message = Redactor.redact(decision.getString("broadcast_message"))
        backend.log(NAME, "broadcast_plan", "multi", "PENDING: Broadcast featuring ${listings.joinToString { it.title }}", false)
        if (!actions.isAvailable()) {
            backend.log(NAME, "broadcast", "whatsapp", "Monitor-only: Accessibility is unavailable", false, error = "Accessibility service disabled")
            return fail(memory, "Broadcast prepared but Accessibility is unavailable; nothing was posted")
        }
        val verifiers = TargetBoundVerifiers(actions)
        val legs = mutableListOf<BroadcastLeg>()
        val statusOutcome = sideEffects.execute(
            capabilityId = CapabilityIds.POST_WHATSAPP_STATUS,
            idempotencyKey = BroadcastKeys.status(dayKey, message),
            target = "status", content = message,
            initiator = initiators.status,
            act = { actions.transacted { postWhatsAppTextStatus(message) } },
            verify = { verifiers.verifyWhatsAppStatus(message) },
        )
        legs += legFor("status", statusOutcome)
        when (legs.last().state) {
            BroadcastLegState.DUPLICATE_SUPPRESSED ->
                backend.log(NAME, "post_status", "whatsapp", "Duplicate broadcast suppressed; already verified today.", true)
            BroadcastLegState.VERIFIED -> Unit
            else -> backend.log(NAME, "post_status", "whatsapp", "Status not verified; no retry.", false)
        }
        // Single authority: broadcast groups come from the durable ContactDirectory
        // (group identities holding the SEND grant); the legacy JSON list is never read.
        val groups = co.sanaa.agent.core.ContactDirectoryProvider.instance?.broadcastGroups().orEmpty()
        for (group in groups) {
            // Dispatch-time re-check (CE-CONTACT-SENDGATE-01): the group must still
            // hold its SEND grant at the moment of dispatch; any refusal means zero
            // dispatch for this leg.
            val dispatchGate = co.sanaa.agent.core.ContactDirectoryProvider.instance?.authorizeOutgoingSend(
                presentedName = group, presentedNumber = null, visibleThreadLabel = group, isGroup = true,
            )
            if (dispatchGate is co.sanaa.agent.core.DispatchDecision.Refused) {
                legs += BroadcastLeg("group:$group", BroadcastLegState.FAILED, "send gate ${dispatchGate.reasonCode}")
                backend.log(NAME, "post_group", "whatsapp", "Group $group refused pre-dispatch (${dispatchGate.reasonCode}); nothing was sent.", false)
                continue
            }
            val outcome = sideEffects.execute(
                capabilityId = CapabilityIds.BROADCAST_GROUP_WHATSAPP,
                idempotencyKey = BroadcastKeys.group(dayKey, group, message),
                target = group, content = message,
                initiator = initiators.group,
                act = { actions.transacted { sendToWhatsAppGroup(group, message) } },
                verify = { verifiers.verifyWhatsAppSend(group, message) },
            )
            legs += legFor("group:$group", outcome)
            if (legs.last().state !in setOf(BroadcastLegState.VERIFIED, BroadcastLegState.DUPLICATE_SUPPRESSED)) {
                backend.log(NAME, "post_group", "whatsapp", "Group send to $group not verified; no retry.", false)
            }
        }
        val caption = decision.optString("tiktok_caption")
        val featured = listings.firstOrNull { it.title == decision.optString("featured_product") } ?: listings.first()
        val tiktokOutcome: SideEffectOutcome? = if (featured.imageUrl != null && caption.isNotBlank()) {
            sideEffects.execute(
                capabilityId = CapabilityIds.POST_TIKTOK,
                idempotencyKey = BroadcastKeys.tiktok(dayKey, featured.id, caption),
                target = featured.title, content = caption,
                initiator = initiators.tiktok,
                act = { actions.transacted { postTikTok(featured.imageUrl, caption, publish = false) } },
                verify = { verifiers.verifyTikTokPost(caption) },
            )
        } else null
        legs += if (tiktokOutcome == null) {
            backend.log(NAME, "post", "tiktok", "Skipped TikTok: featured listing has no usable image or caption", false)
            BroadcastLeg("tiktok", BroadcastLegState.SKIPPED, "no usable image or caption")
        } else legFor("tiktok", tiktokOutcome)
        val overall = overallOf(legs)
        recordLegFailures(legs, dayKey)
        // Featured-listing history only advances for work that did not fail outright;
        // failed broadcasts must be retried against the same listings next run.
        if (overall != Overall.FAILED) {
            state.putString("last_broadcast_ids", listings.joinToString(",") { it.id })
        }
        if (overall == Overall.VERIFIED) state.success(NAME) else state.failure(NAME, summaryOf(legs, overall))
        val summary = summaryOf(legs, overall)
        backend.log(NAME, "broadcast", "multi", summary, overall == Overall.VERIFIED)
        val title = when (overall) {
            Overall.VERIFIED -> "Morning done ✅"
            Overall.PARTIAL -> "Morning finished with issues"
            Overall.FAILED -> "Morning broadcast needs attention"
        }
        reporter.report(title, summary, if (overall == Overall.VERIFIED) NotificationReporter.Priority.INFO else NotificationReporter.Priority.ACTION_NEEDED)
        ModuleResult(
            NAME, overall != Overall.FAILED, summary,
            if (overall == Overall.VERIFIED) null else "One or more broadcast legs were not verified",
            metadata = mapOf("overall" to overall.name, "legs" to legs.map(BroadcastLeg::toMetadata)),
        )
        } catch (error: Exception) { fail(memory, "Morning broadcast failed", error) }
    }

    private suspend fun <T> modelCall(block: suspend () -> T): ModelCall<T> = try {
        ModelCall.Success(block())
    } catch (error: Exception) {
        // Typed gateway failures are per-run conditions, never crashes.
        when (error) {
            is co.sanaa.agent.api.ModelResponseException,
            is GroqClient.MalformedModelResponse, is org.json.JSONException, is IllegalStateException -> ModelCall.Failure(error)
            else -> throw error
        }
    }

    private sealed class ModelCall<out T> {
        data class Success<T>(val value: T) : ModelCall<T>()
        data class Failure(val error: Exception) : ModelCall<Nothing>()
    }

    private fun legFor(leg: String, outcome: SideEffectOutcome): BroadcastLeg = when (outcome) {
        is SideEffectOutcome.Verified -> BroadcastLeg(leg, BroadcastLegState.VERIFIED, "verified on device")
        is SideEffectOutcome.DuplicateBlocked -> BroadcastLeg(leg, BroadcastLegState.DUPLICATE_SUPPRESSED, "already posted today")
        is SideEffectOutcome.Uncertain -> BroadcastLeg(leg, BroadcastLegState.UNCERTAIN, outcome.reason.take(200))
        is SideEffectOutcome.Rejected -> BroadcastLeg(leg, BroadcastLegState.FAILED, outcome.reason.take(200))
        is SideEffectOutcome.Failed -> BroadcastLeg(leg, BroadcastLegState.FAILED, outcome.reason.take(200))
    }

    internal fun overallOf(legs: List<BroadcastLeg>): Overall {
        val attempted = legs.filter { it.state != BroadcastLegState.SKIPPED }
        if (attempted.isEmpty()) return Overall.FAILED
        val bad = attempted.any { it.state == BroadcastLegState.FAILED || it.state == BroadcastLegState.UNCERTAIN }
        val good = attempted.any { it.state == BroadcastLegState.VERIFIED || it.state == BroadcastLegState.DUPLICATE_SUPPRESSED }
        return when {
            bad && good -> Overall.PARTIAL
            bad -> Overall.FAILED
            else -> Overall.VERIFIED
        }
    }

    internal enum class Overall { VERIFIED, PARTIAL, FAILED }

    private fun summaryOf(legs: List<BroadcastLeg>, overall: Overall): String {
        val parts = legs.joinToString("; ") { leg ->
            val label = when (leg.state) {
                BroadcastLegState.VERIFIED -> "sent"
                BroadcastLegState.DUPLICATE_SUPPRESSED -> "already posted today"
                BroadcastLegState.FAILED -> "failed"
                BroadcastLegState.UNCERTAIN -> "unproven"
                BroadcastLegState.SKIPPED -> "skipped"
            }
            "${leg.leg}: $label"
        }
        val prefix = when (overall) {
            Overall.VERIFIED -> "Morning done ✅"
            Overall.PARTIAL -> "Morning finished with issues"
            Overall.FAILED -> "Morning broadcast failed"
        }
        val newlySent = legs.count { it.state == BroadcastLegState.VERIFIED }
        val suppressed = legs.count { it.state == BroadcastLegState.DUPLICATE_SUPPRESSED }
        val honesty = when {
            suppressed > 0 && newlySent == 0 -> " (duplicate-suppressed — no new sends today)"
            suppressed > 0 -> " ($newlySent newly sent, $suppressed already posted)"
            else -> ""
        }
        return "$prefix$honesty. $parts."
    }

    private fun recordLegFailures(legs: List<BroadcastLeg>, dayKey: String) {
        val currentMemory = memory ?: return
        legs.filter { it.state == BroadcastLegState.FAILED || it.state == BroadcastLegState.UNCERTAIN }.forEach { leg ->
            val capability = when {
                leg.leg.startsWith("group:") -> CapabilityIds.BROADCAST_GROUP_WHATSAPP
                leg.leg == "tiktok" -> CapabilityIds.POST_TIKTOK
                else -> CapabilityIds.POST_WHATSAPP_STATUS
            }
            val disposition = if (leg.state == BroadcastLegState.UNCERTAIN) "UNCERTAIN_EXTERNAL_EFFECT" else "FAILED_PERMANENT"
            runCatching {
                currentMemory.recordFailure(
                    taskId = NAME, runId = dayKey, stepId = leg.leg.take(60), capability = capability,
                    targetPackage = if (capability == CapabilityIds.POST_TIKTOK) "com.zhiliaoapp.musically" else "com.whatsapp",
                    stage = STAGE_BROADCAST, cause = leg.detail, retryable = false, attemptCount = 1,
                    screenEvidenceJson = "{}", correctiveAction = "No blind retry; next scheduled run composes fresh content",
                    disposition = disposition, nextSafeAction = "Owner reviews the unverified ${leg.leg} leg",
                )
            }.getOrNull()
        }
    }

    private suspend fun fail(currentMemory: AmaraMemory?, summary: String, error: Exception? = null): ModuleResult {
        // Typed redacted diagnostic only — never stackTraceToString, raw exception
        // text, or throwable payloads on state/backend/notification/chat surfaces.
        val detail = if (error != null) Redactor.safeDiagnostic(error) else summary
        state.failure(NAME, "$summary [$detail]".take(2_000))
        backend.log(NAME, "run", null, summary, false, error = detail.take(400))
        currentMemory?.runCatching {
            recordFailure(
                taskId = NAME, runId = LocalDate.now().toString(), stepId = "run", capability = NAME,
                targetPackage = "multi", stage = STAGE_BROADCAST, cause = summary, retryable = true,
                attemptCount = 1, screenEvidenceJson = "{}", correctiveAction = "Investigate before the next scheduled run",
                disposition = "FAILED_PERMANENT", nextSafeAction = "Nothing was sent; retry after the blocker is fixed",
            )
        }
        reporter.report("Morning broadcast needs attention", summary, NotificationReporter.Priority.ACTION_NEEDED)
        return ModuleResult(NAME, false, summary, null, metadata = mapOf("overall" to "FAILED"))
    }
    companion object {
        const val NAME = "morning_broadcast"
        const val STAGE_BROADCAST = "broadcast"
    }
}
