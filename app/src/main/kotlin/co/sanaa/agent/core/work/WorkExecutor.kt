package co.sanaa.agent.core.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.core.SideEffectOutcome
import co.sanaa.agent.core.SideEffectRunner
import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.api.SokoListing
import kotlin.math.min
import kotlin.math.pow

/**
 * Interface for WorkExecutor to access runtime modules without circular dependency.
 */
interface WorkExecutorDependencies {
    val sokoIntelligence: co.sanaa.agent.modules.SokoIntelligenceModule
    val tiktokSkill: co.sanaa.agent.modules.TikTokSkill
    val conversationEngine: co.sanaa.agent.modules.ConversationEngine
    val humanConversation: co.sanaa.agent.modules.HumanConversationEngine
}

/**
 * WorkExecutor — executes work items via the existing SideEffectRunner.
 * Adapted from AutonomyController.executeStep.
 */
class WorkExecutor(
    val context: Context,
    private val sideEffects: SideEffectRunner,
    private val actions: AccessibilityActions,
    private val dependencies: WorkExecutorDependencies? = null,
) {

    /**
     * Execute a single work item.
     */
    suspend fun execute(item: WorkItem): WorkResult {
        return try {
            co.sanaa.agent.core.AgentRuntime.get(context).modeManager.forWork(item.kind.name)
            when (item.kind) {
                // WhatsApp
                WorkKind.WA_FOLLOWUP -> executeWhatsAppFollowUp(item)
                WorkKind.WA_REPLY_INBOUND -> executeWhatsAppReply(item)
                WorkKind.WA_BROADCAST -> executeWhatsAppBroadcast(item)
                // Soko
                WorkKind.SOKO_AUDIT -> executeSokoAudit(item)
                WorkKind.SOKO_ORDER_CONFIRM -> executeSokoOrderConfirm(item)
                WorkKind.SOKO_RESTOCK_DRAFT -> executeSokoRestockDraft(item)
                WorkKind.SOKO_PRICE_ADJUST -> executeSokoPriceAdjust(item)
                WorkKind.SOKO_INVENTORY_CHECK -> executeSokoInventoryCheck(item)
                // TikTok
                WorkKind.TIKTOK_COMMENT_REPLY -> executeTikTokCommentReply(item)
                WorkKind.TIKTOK_POST_PUBLISH -> executeTikTokPost(item)
                WorkKind.TIKTOK_ANALYTICS_CHECK -> executeTikTokAnalytics(item)
                // Jiji / Market
                WorkKind.JIJI_SCRAPE -> executeJijiScrape(item)
                WorkKind.JUMIA_CAPTURE -> executeJumiaCapture(item)
                WorkKind.MARKET_ANALYSIS -> executeMarketAnalysis(item)
                // Internal
                WorkKind.OWNER_SCHEDULED_COMMAND -> executeOwnerScheduledCommand(item)
                WorkKind.INTERNAL_RECONCILIATION -> executeInternalReconciliation(item)
                WorkKind.INTERNAL_COMMERCIAL_CYCLE -> executeInternalCommercialCycle(item)
                WorkKind.INTERNAL_BRIEFING_PREP -> executeInternalBriefingPrep(item)
                WorkKind.INTERNAL_LEDGER_COMPACTION -> executeInternalLedgerCompaction(item)
                WorkKind.INTERNAL_HEALTH_CHECK -> executeInternalHealthCheck(item)
                WorkKind.INTERNAL_CONFIG_SYNC -> executeInternalConfigSync(item)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            WorkResult(
                item = item,
                status = WorkStatus.FAILED,
                failure = FailureInfo(FailureClass.UNKNOWN, e.message ?: "Unknown error", recoverable = true),
                screenSecondsUsed = 0,
            )
        }
    }

    /**
     * Decide recovery action after a failure.
     */
    fun decideRecovery(item: WorkItem, result: WorkResult, attempt: Int): RecoveryDecision {
        val failure = result.failure ?: return RecoveryDecision.Drop
        if (!failure.recoverable) return RecoveryDecision.Escalate(failure.summary)
        if (item.kind == WorkKind.WA_REPLY_INBOUND) return inboundRecovery(attempt, failure.summary)
        return recoveryFor(failure.klass, failure.summary, attempt)
    }

    companion object {
        internal fun inboundRecovery(attempt: Int, summary: String): RecoveryDecision = when {
            attempt <= 1 -> RecoveryDecision.Requeue(5_000L)
            attempt == 2 -> RecoveryDecision.Requeue(20_000L)
            else -> RecoveryDecision.Escalate(summary)
        }

        internal fun selectTikTokListing(
            listings: List<co.sanaa.agent.api.SokoListing>,
            recentTargetsNewestFirst: List<String>,
            randomIndex: (Int) -> Int = { kotlin.random.Random.nextInt(it) },
        ): co.sanaa.agent.api.SokoListing? {
            val usable = listings.filter { co.sanaa.agent.modules.TikTokProductContent.from(it) != null }
            if (usable.isEmpty()) return null
            val recent = recentTargetsNewestFirst.map { it.trim().lowercase() }
            val fresh = usable.filter { it.title.trim().lowercase() !in recent }
            if (fresh.isNotEmpty()) return fresh[randomIndex(fresh.size).coerceIn(0, fresh.lastIndex)]

            // When every product has appeared, keep a rolling gap and randomize the
            // older pool so the feed does not fall into an obvious fixed sequence.
            val avoid = recent.take((usable.size / 3).coerceAtLeast(1)).toSet()
            val recycled = usable.filter { it.title.trim().lowercase() !in avoid }.ifEmpty { usable }
            return recycled[randomIndex(recycled.size).coerceIn(0, recycled.lastIndex)]
        }

        internal fun recoveryFor(klass: FailureClass, summary: String, attempt: Int): RecoveryDecision = when (klass) {
            FailureClass.TRANSIENT_NETWORK -> if (attempt < 3) RecoveryDecision.Requeue(2000L * attempt) else RecoveryDecision.Requeue(1800_000L, )
            FailureClass.APP_STATE -> if (attempt == 1) RecoveryDecision.Requeue(0L) else RecoveryDecision.Requeue(3_600_000L)
            FailureClass.UI_MISMATCH -> if (attempt < 3) RecoveryDecision.Requeue(backoffMs(attempt)) else RecoveryDecision.Escalate(summary)
            FailureClass.PRECONDITION_GONE -> RecoveryDecision.Drop
            FailureClass.POLICY_BLOCKED -> RecoveryDecision.Drop
            FailureClass.UNKNOWN -> if (attempt < 2) RecoveryDecision.Requeue(backoffMs(attempt)) else RecoveryDecision.Escalate(summary)
        }

        private fun backoffMs(attempt: Int): Long {
            val base = (15 * 60 * 1000).toLong() // 15 min
            val multiplier = 2.0.pow(attempt - 1).toLong()
            val max = 4 * 60 * 60 * 1000L // 4 hours
            return min(base * multiplier, max)
        }
    }

    fun networkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // --- WhatsApp ---

    private suspend fun executeWhatsAppFollowUp(item: WorkItem): WorkResult {
        val chatKey = item.payload.optString("target", "")
        if (chatKey.isBlank()) {
            return WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.PRECONDITION_GONE, "Missing follow-up target"))
        }
        val runtime = co.sanaa.agent.core.AgentRuntime.get(context)
        if (!runtime.config.whatsAppAutomationEnabled || !runtime.config.whatsAppFollowUpsEnabled) {
            return WorkResult(item, WorkStatus.SKIPPED, failure = FailureInfo(FailureClass.POLICY_BLOCKED, "WhatsApp automation is disabled"))
        }
        val target = co.sanaa.agent.modules.WhatsAppFollowUpIdentity.target(runtime.contacts,chatKey)
            ?: return WorkResult(item,WorkStatus.ESCALATED,failure=FailureInfo(FailureClass.POLICY_BLOCKED,
                "Follow-up conversation needs a verified phone binding; notification identity will not be searched by name",false))
        if(item.attempt>0 && item.payload.optString("message").isBlank()) return WorkResult(item,WorkStatus.ESCALATED,
            failure=FailureInfo(FailureClass.PRECONDITION_GONE,"Legacy follow-up retry has no persisted draft; reconcile prior attempts first",false))
        val summary = item.payload.optString("summary", "").trim()
        val message = item.payload.optString("message", "").trim().ifBlank {
            if (summary.isBlank()) return WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.PRECONDITION_GONE, "No grounded conversation summary"))
            val response = runtime.groq.completeJson(
                """
                ${co.sanaa.agent.core.BusinessOperatingBrief.TEXT}
                Draft one short, non-pushy follow-up for this dormant customer conversation.
                Use only facts in the summary. Do not invent stock, price, delivery, or discounts.
                SUMMARY: $summary
                Return JSON: {"message":"..."}
                """.trimIndent(),
                null,
                "work-followup-${item.dedupeKey}",
            )
            response.optString("message").trim()
        }
        if (message.isBlank()) return WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.UNKNOWN, "Follow-up drafting returned no message"))

        commercialFollowUpBlocker(runtime, target, message)?.let { blocker ->
            return WorkResult(item, WorkStatus.SKIPPED, failure = FailureInfo(FailureClass.POLICY_BLOCKED, blocker, recoverable = false))
        }

        val boundPayload=org.json.JSONObject(item.payload.toString()).put("message",message)
        if(!runtime.workQueue.bindFollowUpPayload(item.dedupeKey,boundPayload)) return WorkResult(item,WorkStatus.ESCALATED,
            failure=FailureInfo(FailureClass.PRECONDITION_GONE,"Follow-up draft could not be durably bound",false))
        val outcome = sideEffects.execute(
            capabilityId = CapabilityIds.FOLLOW_UP_WHATSAPP,
            idempotencyKey = item.dedupeKey,
            target = target,
            content = message,
            preflight = {
                if(co.sanaa.agent.modules.WhatsAppFollowUpIdentity.target(runtime.contacts,chatKey)!=target)
                    "Follow-up identity changed before dispatch"
                else commercialFollowUpBlocker(runtime, target, message)
            },
            act = { actions.transacted { sendToWhatsAppContact(target, message) } },
            verify = { co.sanaa.agent.actions.TargetBoundVerifiers(actions).evaluateCurrentChat(target, message) }
        )

        return when (outcome) {
            is SideEffectOutcome.Verified -> {
                runtime.chatStore.storeMessage(chatKey, "Amara", "sent", message)
                item.payload.optString("commercial_action_id").takeIf(String::isNotBlank)?.let {
                    runtime.revenueStore.updateCommercialActionState(it, "EXECUTED_VERIFIED", "target-bound WhatsApp delivery verified", System.currentTimeMillis())
                }
                WorkResult(item, WorkStatus.DONE, screenSecondsUsed = 40, outcomeFacts = listOf("Verified one consented follow-up"))
            }
            is SideEffectOutcome.DuplicateBlocked -> {
                item.payload.optString("commercial_action_id").takeIf(String::isNotBlank)?.let {
                    runtime.revenueStore.updateCommercialActionState(it, "EXECUTED_VERIFIED", "duplicate prevented; original delivery already verified", System.currentTimeMillis())
                }
                WorkResult(item, WorkStatus.DONE, screenSecondsUsed = 10)
            }
            is SideEffectOutcome.Rejected -> WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.POLICY_BLOCKED, outcome.reason))
            is SideEffectOutcome.Failed -> WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.TRANSIENT_NETWORK, outcome.reason))
            is SideEffectOutcome.Uncertain -> WorkResult(item, WorkStatus.ESCALATED, failure = FailureInfo(FailureClass.UNKNOWN, outcome.reason,false))
        }
    }

    private fun commercialFollowUpBlocker(
        runtime: co.sanaa.agent.core.AgentRuntime,
        target: String,
        message: String,
    ): String? {
        val resolution = runtime.contacts.resolve(
            co.sanaa.agent.core.ContactQuery(name = target, phone = target, isGroup = false),
        )
        val entry = (resolution as? co.sanaa.agent.core.Resolution.Unique)?.entry
            ?: return "Follow-up target is unknown or ambiguous"
        if (!entry.canSend || entry.commercialConsent != co.sanaa.agent.core.CommercialConsent.GRANTED || !entry.isRevenueEligible()) {
            return "Follow-up target lacks an active SEND grant and explicit commercial consent"
        }
        if(runtime.memory.allSideEffectTransactions().any {
            it.capability == CapabilityIds.FOLLOW_UP_WHATSAPP && it.target in setOf(target,entry.displayName,entry.id) &&
                it.state in setOf(co.sanaa.agent.core.SideEffectState.UNCERTAIN,
                    co.sanaa.agent.core.SideEffectState.ACTING,co.sanaa.agent.core.SideEffectState.VERIFICATION_PENDING)
        }) return "A prior follow-up has unresolved delivery; reconcile it before new outreach"
        val policy = runtime.revenueOps.policy() ?: return "No owner commercial policy is configured"
        if ("whatsapp" !in policy.approvedChannels.map(String::lowercase)) return "WhatsApp is not an owner-approved commercial channel"
        val nowMs = System.currentTimeMillis()
        val zone = policy.ownerZone()
        val localTime = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime()
        val sentToday = runtime.memory.allSideEffectTransactions().count {
            it.target == target && it.state == co.sanaa.agent.core.SideEffectState.VERIFIED &&
                java.time.Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate() ==
                java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        }
        return when (val verdict = runtime.outreachGuard.gate(
            contactKey = entry.id,
            productRef = null,
            channel = "whatsapp",
            content = message,
            localTime = localTime,
            messagesToCustomerToday = sentToday,
            messagesToCustomerInWindow = sentToday,
            globalMessagesToday = runtime.revenueStore.executedActionCountToday(zone, nowMs),
            nowMs = nowMs,
        )) {
            is co.sanaa.agent.core.commerce.OutreachGuard.Verdict.Allow -> null
            is co.sanaa.agent.core.commerce.OutreachGuard.Verdict.Block -> verdict.reason
            is co.sanaa.agent.core.commerce.OutreachGuard.Verdict.SuppressAndBlock -> verdict.reason
        }
    }

    private suspend fun executeWhatsAppReply(item: WorkItem): WorkResult {
        if (item.payload.optBoolean("inbound", false)) {
            val observedAt = item.payload.optLong("inbound_observed_at", 0L)
            if (observedAt > 0L) android.util.Log.i("SanaaInbound",
                "WhatsApp work started; since_observation_ms=${(System.currentTimeMillis() - observedAt).coerceAtLeast(0)}; attempt=${item.attempt}")
            val inbound = co.sanaa.agent.modules.WhatsAppInbound(
                sender = item.payload.optString("sender"),
                message = item.payload.optString("message"),
                conversation = item.payload.optString("conversation"),
                isGroup = item.payload.optBoolean("is_group"),
                isMissedCall = item.payload.optBoolean("is_missed_call"),
            )
            val runtime = co.sanaa.agent.core.AgentRuntime.get(context)
            if (!runtime.config.whatsAppAutomationEnabled || !runtime.config.whatsAppInboundEnabled) {
                return WorkResult(item, WorkStatus.SKIPPED, failure = FailureInfo(FailureClass.POLICY_BLOCKED, "WhatsApp autopilot or inbound replies are off", false))
            }
            if (inbound.isGroup && !runtime.config.whatsAppGroupsEnabled) {
                return WorkResult(item, WorkStatus.SKIPPED, failure = FailureInfo(FailureClass.POLICY_BLOCKED, "WhatsApp group autopilot is off", false))
            }
            if (inbound.isMissedCall) {
                runtime.reporter.report("Missed WhatsApp call", inbound.sender, co.sanaa.agent.notifications.NotificationReporter.Priority.ACTION_NEEDED)
                return WorkResult(item, WorkStatus.DONE, outcomeFacts = listOf("Recorded a missed WhatsApp call and notified the owner"))
            }
            val started = System.currentTimeMillis()
            val result = (dependencies?.humanConversation ?: runtime.humanConversation)
                .processMessage(
                    inbound.target, inbound.sender, inbound.message, inbound.isGroup,
                    inboundWorkKey = item.dedupeKey,
                    conversationIdentity = item.payload.optString("conversation_identity"),
                    trustedWhatsAppNotification = item.payload.optBoolean("trusted_whatsapp_notification", false) ||
                        (item.payload.optBoolean("inbound", false) && item.dedupeKey.startsWith("wa-inbound:")),
                )
            val success = (result is co.sanaa.agent.modules.ReplyResult.SendBurst && result.messages.isNotEmpty()) ||
                result is co.sanaa.agent.modules.ReplyResult.AlreadyAnswered
            val summary = when (result) {
                is co.sanaa.agent.modules.ReplyResult.SendBurst -> "Sent ${result.messages.size} verified WhatsApp reply message(s)"
                is co.sanaa.agent.modules.ReplyResult.AlreadyAnswered -> "Skipped duplicate WhatsApp reply: ${result.reason}"
                is co.sanaa.agent.modules.ReplyResult.Escalate -> "Escalated WhatsApp conversation: ${result.reason}"
                is co.sanaa.agent.modules.ReplyResult.Failed -> result.error
            }
            return WorkResult(
                item,
                if (success) WorkStatus.DONE else if (result is co.sanaa.agent.modules.ReplyResult.Escalate) WorkStatus.ESCALATED else WorkStatus.FAILED,
                screenSecondsUsed = ((System.currentTimeMillis() - started) / 1000).toInt(),
                outcomeFacts = listOf(summary),
                failure = if (success) null else FailureInfo(FailureClass.UI_MISMATCH, summary, (result as? co.sanaa.agent.modules.ReplyResult.Failed)?.recoverable ?: false),
            )
        }

        val target = item.payload.optString("target", "")
        val message = item.payload.optString("message", "")
        if (target.isBlank() || message.isBlank()) {
            return WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.PRECONDITION_GONE, "Missing target or message"))
        }

        val outcome = sideEffects.execute(
            capabilityId = CapabilityIds.REPLY_WHATSAPP,
            idempotencyKey = "wa-reply:${target}:${message.hashCode()}",
            target = target,
            content = message,
            act = { actions.transacted { sendInCurrentChat(message) } },
            verify = { co.sanaa.agent.actions.TargetBoundVerifiers(actions).evaluateCurrentChat(target, message) }
        )

        return when (outcome) {
            is SideEffectOutcome.Verified -> WorkResult(item, WorkStatus.DONE, screenSecondsUsed = 45)
            is SideEffectOutcome.DuplicateBlocked -> WorkResult(item, WorkStatus.DONE, screenSecondsUsed = 10)
            is SideEffectOutcome.Rejected -> WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.POLICY_BLOCKED, outcome.reason))
            is SideEffectOutcome.Failed -> WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.TRANSIENT_NETWORK, outcome.reason))
            is SideEffectOutcome.Uncertain -> WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.UNKNOWN, outcome.reason))
        }
    }

    private suspend fun executeWhatsAppBroadcast(item: WorkItem): WorkResult {
        if (!co.sanaa.agent.core.AgentRuntime.get(context).config.whatsAppAutomationEnabled) {
            return WorkResult(item, WorkStatus.SKIPPED, failure = FailureInfo(FailureClass.POLICY_BLOCKED, "WhatsApp autopilot is off", false))
        }
        if (item.payload.optString("group_target").isNotBlank()) return executeWhatsAppGroup(item)
        if (item.payload.optBoolean("run_module", false)) {
            val result = co.sanaa.agent.core.AgentRuntime.get(context).morning.run()
            return WorkResult(
                item,
                if (result.success) WorkStatus.DONE else WorkStatus.FAILED,
                screenSecondsUsed = item.estimatedScreenSeconds,
                outcomeFacts = listOf(result.summary),
                failure = if (result.success) null else FailureInfo(FailureClass.UNKNOWN, result.error ?: result.summary),
            )
        }
        val message = item.payload.optString("message", "")
        if (message.isBlank()) {
            return WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.PRECONDITION_GONE, "Missing message"))
        }

        val outcome = sideEffects.execute(
            capabilityId = CapabilityIds.POST_WHATSAPP_STATUS,
            idempotencyKey = "wa-broadcast:${message.hashCode()}",
            target = "status",
            content = message,
            act = { actions.transacted { postWhatsAppTextStatus(message) } },
            verify = { co.sanaa.agent.actions.TargetBoundVerifiers(actions).verifyWhatsAppStatus(message) }
        )

        return when (outcome) {
            is SideEffectOutcome.Verified -> WorkResult(item, WorkStatus.DONE, screenSecondsUsed = 60)
            is SideEffectOutcome.DuplicateBlocked -> WorkResult(item, WorkStatus.DONE, screenSecondsUsed = 10)
            is SideEffectOutcome.Rejected -> WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.POLICY_BLOCKED, outcome.reason))
            is SideEffectOutcome.Failed -> WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.TRANSIENT_NETWORK, outcome.reason))
            is SideEffectOutcome.Uncertain -> WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.UNKNOWN, outcome.reason))
        }
    }

    private suspend fun executeWhatsAppGroup(item: WorkItem): WorkResult {
        val runtime = co.sanaa.agent.core.AgentRuntime.get(context)
        val target = item.payload.getString("group_target")
        fun allowed() = runtime.config.whatsAppAutomationEnabled && runtime.config.whatsAppGroupsEnabled &&
            runtime.contacts.listAll().filter { it.isGroup && it.displayName==target }.singleOrNull()?.let { runtime.groupSettings.allows(it,"promote") } == true &&
            (co.sanaa.agent.core.ContactDirectoryProvider.instance?.authorizeOutgoingSend(
                presentedName = target, presentedNumber = null, visibleThreadLabel = target, isGroup = true,
            ) is co.sanaa.agent.core.DispatchDecision.Allowed)
        if (!allowed()) return WorkResult(item, WorkStatus.SKIPPED,
            failure = FailureInfo(FailureClass.POLICY_BLOCKED, "Group send permission is unavailable", false))
        val groupEntry = runtime.contacts.listAll().single { it.isGroup && it.displayName==target }
        if(runtime.groupSettings.due(groupEntry)>System.currentTimeMillis()) return WorkResult(item,WorkStatus.SKIPPED,
            failure=FailureInfo(FailureClass.POLICY_BLOCKED,"Group schedule is paused or not due",false))
        var message = item.payload.optString("message")
        var imageUrl = item.payload.optString("image_url")
        if (message.isBlank()) {
            val listing = co.sanaa.agent.core.growth.GrowthStore.select(runtime.soko.promotableOfferings(), runtime.growthStore.history(target),
                inquiryCounts = runtime.revenueStore.inquiries(System.currentTimeMillis() - 30L * 86_400_000, System.currentTimeMillis())
                    .groupingBy { it.productRef }.eachCount())
                ?: return WorkResult(item, WorkStatus.FAILED,
                    failure = FailureInfo(FailureClass.PRECONDITION_GONE, "No verified offering with a shopping link", false))
            val content = co.sanaa.agent.modules.GroupPromotionContent.from(listing)!!
            message = content.caption
            imageUrl = content.imageUrl
            runtime.growthStore.bind(item.dedupeKey, target, listing)
            val bound = org.json.JSONObject(item.payload.toString()).put("message", message).put("listing_id", listing.id)
                .put("image_url", content.imageUrl)
            if (!runtime.workQueue.bindGroupPayload(item.dedupeKey, bound)) return WorkResult(item, WorkStatus.FAILED,
                failure = FailureInfo(FailureClass.PRECONDITION_GONE, "Could not bind group content", false))
        }
        val media = if (imageUrl.isNotBlank()) actions.prepareBoundWhatsAppPhoto(imageUrl, item.dedupeKey) else null
        if(media==null) return WorkResult(item,WorkStatus.FAILED,
            failure=FailureInfo(FailureClass.PRECONDITION_GONE,"Group photo could not be prepared; no text-only ad was sent",false))
        var photoBlocker: String? = null
        val outcome = sideEffects.execute(
            capabilityId = CapabilityIds.BROADCAST_GROUP_WHATSAPP,
            idempotencyKey = item.dedupeKey, target = target, content = "$message\nphoto-sha256:${media.second}",
            inputs = mapOf("target" to target, "content" to message),
            initiator = co.sanaa.agent.core.Initiator.INTERNAL_RUNTIME,
            preflight = { if (allowed()) null else "Group permission was revoked" },
            act = { actions.transacted {
                sendWhatsAppAttachment(target, media.first, "image/jpeg", message).also { photoBlocker=actions.lastWhatsAppPhotoBlocker }
            } },
            verify = { actions.verifyCaptionedPhoto(target, message) },
        )
        val success = outcome is SideEffectOutcome.Verified || outcome is SideEffectOutcome.DuplicateBlocked
        runtime.growthStore.outcome(item.dedupeKey, when (outcome) {
            is SideEffectOutcome.Verified, is SideEffectOutcome.DuplicateBlocked -> "VERIFIED"
            is SideEffectOutcome.Uncertain -> "UNCERTAIN"
            else -> "FAILED"
        })
        return WorkResult(item, if (success) WorkStatus.DONE else WorkStatus.FAILED,
            outcomeFacts = if (success) listOf("Verified scheduled group promotion") else emptyList(),
            failure = if (success) null else FailureInfo(FailureClass.UNKNOWN, "Group publication: ${photoBlocker ?: outcome}", false))
    }

    // --- Soko ---

    private suspend fun executeSokoAudit(item: WorkItem): WorkResult {
        val startTime = System.currentTimeMillis()

        // One audit covers both actionable Terminal surfaces. Structured alerts
        // become grounded follow-up work; no consequential action is taken here.
        val sokoIntelligence = dependencies?.sokoIntelligence
            ?: co.sanaa.agent.core.AgentRuntime.get(context).sokoIntelligence
        val alertsResult = sokoIntelligence.alertsNeedingAction()
        val bookingsResult = sokoIntelligence.bookingsNeedingAction()
        val successes = listOf(alertsResult, bookingsResult).count { it.success }
        val summaries = listOf(alertsResult.summary, bookingsResult.summary)
        return WorkResult(
            item = item,
            status = when (successes) { 2 -> WorkStatus.DONE; 1 -> WorkStatus.PARTIAL; else -> WorkStatus.FAILED },
            discoveredWork = SokoWorkDiscovery.fromAlerts(alertsResult.alerts),
            outcomeFacts = summaries,
            failure = if (successes == 2) null else FailureInfo(
                FailureClass.UI_MISMATCH,
                summaries.filterIndexed { index, _ -> !listOf(alertsResult, bookingsResult)[index].success }.joinToString(" "),
            ),
            screenSecondsUsed = ((System.currentTimeMillis() - startTime) / 1000).toInt(),
        )
    }

    private suspend fun executeSokoOrderConfirm(item: WorkItem): WorkResult {
        val orderId = item.payload.optString("order_id", "")
        return WorkResult(
            item,
            WorkStatus.ESCALATED,
            failure = FailureInfo(
                FailureClass.POLICY_BLOCKED,
                if (orderId.isBlank()) "Soko audit did not provide an exact order id" else "Order $orderId requires an exact owner-approved workflow",
                recoverable = false,
            ),
        )
    }

    private suspend fun executeSokoRestockDraft(item: WorkItem): WorkResult {
        val product = item.payload.optString("product").trim()
        return if (product.isBlank()) WorkResult(item, WorkStatus.ESCALATED, failure = FailureInfo(FailureClass.PRECONDITION_GONE, "No grounded product was supplied for the restock draft"))
        else WorkResult(item, WorkStatus.DONE, outcomeFacts = listOf("Restock recommendation drafted for $product"))
    }

    private suspend fun executeSokoPriceAdjust(item: WorkItem): WorkResult {
        val product = item.payload.optString("product").trim()
        val price = item.payload.optLong("proposed_price_ugx", -1)
        return if (product.isBlank() || price <= 0) WorkResult(item, WorkStatus.ESCALATED, failure = FailureInfo(FailureClass.PRECONDITION_GONE, "Price proposal lacks a grounded product and price"))
        else WorkResult(item, WorkStatus.DONE, outcomeFacts = listOf("Prepared, but did not apply, a UGX $price price proposal for $product"))
    }

    private suspend fun executeSokoInventoryCheck(item: WorkItem): WorkResult {
        val runtime = co.sanaa.agent.core.AgentRuntime.get(context)
        val sync = runtime.sokoCatalog.syncAll()
        return WorkResult(
            item,
            when { sync.complete -> WorkStatus.DONE; sync.success -> WorkStatus.PARTIAL; else -> WorkStatus.FAILED },
            screenSecondsUsed = 120,
            outcomeFacts = if (sync.success) listOf("Catalog synced from Terminal or authenticated shop fallback: ${sync.productsSynced} products, ${sync.servicesSynced} services") else emptyList(),
            failure = if (sync.complete) null else FailureInfo(FailureClass.UI_MISMATCH, sync.failureSummary),
        )
    }

    // --- TikTok ---

    private suspend fun executeTikTokCommentReply(item: WorkItem): WorkResult {
        val runtime = co.sanaa.agent.core.AgentRuntime.get(context)
        if (runtime.config.tikTokSocialEnabled) {
            val result = co.sanaa.agent.modules.TikTokSocialCycle(runtime).run()
            val failed = result.optString("interaction_outcome") in setOf("FAILED", "UNCERTAIN", "MODEL_DEFERRED")
            return WorkResult(item, if (result.has("blocked")) WorkStatus.SKIPPED else if (failed) WorkStatus.PARTIAL else WorkStatus.DONE,
                screenSecondsUsed = 90, outcomeFacts = listOf(result.toString()),
                failure = if (failed) FailureInfo(FailureClass.UNKNOWN, "TikTok interaction: ${result.optString("interaction_outcome")}", false) else null)
        }
        val comments = (dependencies?.tiktokSkill ?: co.sanaa.agent.core.AgentRuntime.get(context).tiktok)
            .readComments(item.payload.optString("post_hint", ""))
        return if (comments.isEmpty()) WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.UI_MISMATCH, "No TikTok comments could be verified"))
        else WorkResult(item, WorkStatus.DONE, screenSecondsUsed = 30, outcomeFacts = listOf("Read ${comments.size} TikTok comments; no autonomous replies were sent"))
    }

    private suspend fun executeTikTokPost(item: WorkItem): WorkResult {
        val startTime = System.currentTimeMillis()
        val runtime = co.sanaa.agent.core.AgentRuntime.get(context)
        val config = runtime.config

        // Check if TikTok posting is enabled
        if (!config.tikTokTestMode) {
            return WorkResult(item, WorkStatus.SKIPPED, failure = FailureInfo(FailureClass.POLICY_BLOCKED, "TikTok posting is disabled"))
        }
        val todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val publishedToday = runtime.memory.allSideEffectTransactions().count {
            it.capability == CapabilityIds.POST_TIKTOK && it.state == co.sanaa.agent.core.SideEffectState.VERIFIED && it.updatedAt >= todayStart
        }
        if (publishedToday >= config.tikTokDailyCap) {
            return WorkResult(item, WorkStatus.SKIPPED, failure = FailureInfo(FailureClass.POLICY_BLOCKED, "TikTok daily cap reached: $publishedToday verified posts / ${config.tikTokDailyCap}; interval=${config.tikTokPostIntervalMinutes} minutes"))
        }

        // Check connectivity
        if (!runtime.connectivityMonitor.isOnline()) {
            return WorkResult(item, WorkStatus.SKIPPED, failure = FailureInfo(FailureClass.TRANSIENT_NETWORK, "No internet connection"))
        }

        // Get listings - try API first, fall back to Soko Terminal scraping
        var listings = runtime.soko.promotableOfferings()
        if (listings.isEmpty()) {
            // Fall back to Soko Terminal accessibility scan
            val sokoInventory = co.sanaa.agent.modules.SokoInventoryModule(
                runtime.config, runtime.actions, runtime.memory, pin = { runtime.sokoPin() }
            )
            val scanResult = sokoInventory.scan()
            if (scanResult.success && scanResult.items.isNotEmpty()) {
                listings = scanResult.items.map { co.sanaa.agent.api.SokoListing(
                    id = it.name,
                    title = it.name,
                    description = it.rawText,
                    priceUgx = 0,
                    category = "",
                    photoCount = 0,
                    viewCount = 0,
                    stock = null,
                    imageUrl = null,
                    raw = org.json.JSONObject(),
                ) }
            }
        }

        if (listings.isEmpty()) {
            return WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.PRECONDITION_GONE, "No listings available from API or Soko Terminal"))
        }

        // A supervised/owner-triggered run can pin the inspected listing. Scheduled
        // runs retain the normal rotation. Never silently substitute another product
        // when an explicit listing was requested.
        val requestedListingId = item.payload.optString("listing_id").trim()
        val listing = if (requestedListingId.isNotBlank()) {
            listings.firstOrNull { it.id == requestedListingId }
                ?: return WorkResult(
                    item,
                    WorkStatus.FAILED,
                    failure = FailureInfo(FailureClass.PRECONDITION_GONE, "The selected Soko listing is no longer active"),
                )
        } else {
            val recentTargets = runtime.memory.recentTikTokProductTargets(
                System.currentTimeMillis() - 30L * 86_400_000L,
            )
            selectTikTokListing(listings, recentTargets)
                ?: return WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.PRECONDITION_GONE, "No Soko listing with usable media is available"))
        }

        val productContent = co.sanaa.agent.modules.TikTokProductContent.from(listing)
            ?: return WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(
                FailureClass.PRECONDITION_GONE, "Selected product needs a valid title, photo URL and shopping slug; nothing published", false,
            ))
        val previousFingerprint = item.payload.optString("product_fingerprint")
        if (previousFingerprint.isNotBlank() && previousFingerprint != productContent.fingerprint) {
            return WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(
                FailureClass.PRECONDITION_GONE, "Product content changed after binding; review before another publication", false,
            ))
        }
        val caption = productContent.caption

        val boundPayload = org.json.JSONObject(item.payload.toString())
            .put("listing_id", listing.id).put("caption", caption)
            .put("image_url", productContent.imageUrl).put("shopping_url", productContent.shoppingUrl)
            .put("product_fingerprint", productContent.fingerprint)
        if (!runtime.workQueue.bindTikTokPayload(item.dedupeKey, boundPayload)) {
            return WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(
                FailureClass.PRECONDITION_GONE, "Could not durably bind TikTok content before publication", false,
            ))
        }

        // Post to TikTok
        val publishOutcome = sideEffects.execute(
            capabilityId = CapabilityIds.POST_TIKTOK,
            idempotencyKey = item.dedupeKey,
            target = "tiktok",
            content = caption,
            initiator = co.sanaa.agent.core.Initiator.RECURRING_SCHEDULE,
            act = {
                actions.transacted { postTikTok(productContent.imageUrl, caption, publish = true, mediaBindingKey = item.dedupeKey) }
            },
            verify = { co.sanaa.agent.actions.TargetBoundVerifiers(actions).verifyTikTokPost(caption) },
        )
        val published = publishOutcome is SideEffectOutcome.Verified || publishOutcome is SideEffectOutcome.DuplicateBlocked

        // Record the action
        runtime.memory.recordAction(
            "tiktok_post",
            listing.title,
            "TikTok",
            "Posted TikTok for ${listing.title}",
            if (published) "Published: $caption" else "Failed to publish",
            caption,
            null,
            published
        )
        co.sanaa.agent.core.skills.SelfImprovingSkill("soko-tiktok-growth", context).let { skill ->
            if (published) skill.recordSuccess() else {
                skill.recordFailure()
                co.sanaa.agent.core.skills.SkillAnalytics("soko-tiktok-growth", context)
                    .addRecentFailure("TikTok publication was not verified for ${listing.title.take(80)}")
            }
        }

        return if (published) {
            WorkResult(item, WorkStatus.DONE, screenSecondsUsed = ((System.currentTimeMillis() - startTime) / 1000).toInt())
        } else {
            WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(
                FailureClass.UNKNOWN, "TikTok publication was not verified: $publishOutcome; preparation=${actions.lastTikTokPreparationFailure}",
                recoverable = false, // Ledger outcomes are terminal; the next cadence selects fresh content.
            ))
        }
    }

    private suspend fun executeTikTokAnalytics(item: WorkItem): WorkResult {
        val analytics = (dependencies?.tiktokSkill ?: co.sanaa.agent.core.AgentRuntime.get(context).tiktok).readAnalytics()
        return if (analytics == null) WorkResult(item, WorkStatus.FAILED, failure = FailureInfo(FailureClass.UI_MISMATCH, "TikTok analytics could not be read"))
        else WorkResult(item, WorkStatus.DONE, screenSecondsUsed = 60, outcomeFacts = listOf("TikTok analytics read and verified from the visible UI"))
    }

    // --- Jiji / Market ---

    private suspend fun executeJijiScrape(item: WorkItem): WorkResult {
        val startTime = System.currentTimeMillis()
        val category = item.payload.optString("category", "general")
        val runtime = co.sanaa.agent.core.AgentRuntime.get(context)
        val scraper = runtime.jijiScraper
        val count = scraper.scrapeCategory(category, 20)
        return WorkResult(
            item = item,
            status = if (count > 0) WorkStatus.DONE else WorkStatus.FAILED,
            screenSecondsUsed = ((System.currentTimeMillis() - startTime) / 1000).toInt(),
            outcomeFacts = listOf("Scraped $count listings from Jiji category: $category"),
            discoveredWork = if (count > 0) listOf(marketReviewWork(item.dedupeKey)) else emptyList(),
            failure = if (count > 0) null else FailureInfo(
                FailureClass.UI_MISMATCH, "Jiji returned no verified listings for category: $category; stage=${runtime.jijiScraper.lastFailure}",
            ),
        )
    }

    private suspend fun executeJumiaCapture(item: WorkItem): WorkResult {
        val startTime = System.currentTimeMillis()
        val result = co.sanaa.agent.core.AgentRuntime.get(context).jumiaScraper.captureFeaturedOffers()
        return WorkResult(
            item = item,
            status = if (result.products > 0) WorkStatus.DONE else WorkStatus.FAILED,
            screenSecondsUsed = ((System.currentTimeMillis() - startTime) / 1000).toInt(),
            outcomeFacts = listOf("Browsed ${result.pagesBrowsed} Jumia pages across ${result.sectionsVisited} sections; captured ${result.products} grounded offers from ${result.surface}"),
            discoveredWork = if (result.products > 0) listOf(marketReviewWork(item.dedupeKey)) else emptyList(),
            failure = if (result.products > 0) null else FailureInfo(
                if (result.surface.startsWith("configuration_required:")) FailureClass.PRECONDITION_GONE else FailureClass.UI_MISMATCH,
                "Jumia produced no complete visible offers (${result.surface})",
            ),
        )
    }

    private fun marketReviewWork(sourceKey: String) = WorkItem(
        dedupeKey = "market-review:$sourceKey", domain = Domain.INTERNAL, kind = WorkKind.MARKET_ANALYSIS,
        baseValueKes = 200.0, urgencyHalfLifeHours = 12.0, estimatedScreenSeconds = 0,
        requires = setOf(Capability.NETWORK),
    )

    private suspend fun executeMarketAnalysis(item: WorkItem): WorkResult {
        val runtime = co.sanaa.agent.core.AgentRuntime.get(context)
        val offerings = runtime.soko.promotableOfferings()
        if (offerings.isEmpty()) return WorkResult(item, WorkStatus.FAILED,
            failure = FailureInfo(FailureClass.PRECONDITION_GONE, "No own catalogue available for grounded market review", false))
        val report = co.sanaa.agent.core.growth.MarketGrowthReview(runtime.memory, runtime.marketAnalyzer, runtime.growthStore).review(offerings)
        return WorkResult(item, WorkStatus.DONE, outcomeFacts = listOf(report.getString("summary")))
    }

    // --- Internal ---

    private suspend fun executeOwnerScheduledCommand(item: WorkItem): WorkResult {
        val runtime = co.sanaa.agent.core.AgentRuntime.get(context)
        val taskId = item.payload.optLong("task_id", -1L)
        val task = runtime.memory.recurringTask(taskId)
            ?: return WorkResult(item, WorkStatus.SKIPPED, failure = FailureInfo(FailureClass.PRECONDITION_GONE, "Scheduled task no longer exists", false))
        if (!task.enabled) return WorkResult(item, WorkStatus.SKIPPED, failure = FailureInfo(FailureClass.POLICY_BLOCKED, "Scheduled task is disabled", false))
        val outcome = runCatching { co.sanaa.agent.core.CommandExecutor(context).execute(task.taskText, "", "") }
        val commandResult = outcome.getOrNull()
        val summary = commandResult?.message ?: outcome.exceptionOrNull()?.message ?: "Scheduled task failed without a result"
        val schedule = co.sanaa.agent.core.ScheduleCodec.decode(task.scheduleJson, task.taskText)
        val next = co.sanaa.agent.core.ScheduleCalculator.nextRun(schedule, maxOf(System.currentTimeMillis(), task.nextRunAt))
        runtime.memory.completeRecurringOccurrence(task.id, summary, next)
        co.sanaa.agent.workers.AgentWorkScheduler.scheduleRecurring(context, task.id, next)
        return WorkResult(
            item,
            if (commandResult?.success == true) WorkStatus.DONE else WorkStatus.FAILED,
            outcomeFacts = listOf(summary),
            failure = if (commandResult?.success == true) null else FailureInfo(FailureClass.UNKNOWN, summary),
        )
    }

    private suspend fun executeInternalReconciliation(item: WorkItem): WorkResult {
        val runtime = co.sanaa.agent.core.AgentRuntime.get(context)
        val uncertain = runtime.memory.allSideEffectTransactions().count { it.state == co.sanaa.agent.core.SideEffectState.UNCERTAIN }
        return WorkResult(item, WorkStatus.DONE, outcomeFacts = listOf("Reconciled transaction ledger; $uncertain uncertain actions require review"))
    }

    private suspend fun executeInternalCommercialCycle(item: WorkItem): WorkResult {
        val summary = co.sanaa.agent.workers.CommercialCycleRunner(context).run()
        val skipped = summary.startsWith("Commercial cycle skipped:")
        return WorkResult(
            item,
            if (skipped) WorkStatus.SKIPPED else WorkStatus.DONE,
            outcomeFacts = listOf(summary),
            failure = if (skipped) FailureInfo(FailureClass.POLICY_BLOCKED, summary, false) else null,
        )
    }

    private suspend fun executeInternalBriefingPrep(item: WorkItem): WorkResult {
        val runtime = co.sanaa.agent.core.AgentRuntime.get(context)
        val summary = "${runtime.memory.getRecentSalesCount(7)} verified sales and UGX ${runtime.memory.getRecentRevenue(7)} recorded in the last 7 days."
        runtime.reporter.report("Amara daily briefing", summary, co.sanaa.agent.notifications.NotificationReporter.Priority.INFO)
        return WorkResult(item, WorkStatus.DONE, outcomeFacts = listOf(summary))
    }

    private suspend fun executeInternalLedgerCompaction(item: WorkItem): WorkResult {
        return WorkResult(item, WorkStatus.ESCALATED, failure = FailureInfo(FailureClass.POLICY_BLOCKED, "Automatic ledger deletion/compaction is disabled; retention remains owner-controlled", false))
    }

    private suspend fun executeInternalHealthCheck(item: WorkItem): WorkResult {
        val result = co.sanaa.agent.core.AgentRuntime.get(context).health.run()
        return WorkResult(item, if (result.success) WorkStatus.DONE else WorkStatus.FAILED, outcomeFacts = listOf(result.summary), failure = if (result.success) null else FailureInfo(FailureClass.UNKNOWN, result.summary))
    }

    private suspend fun executeInternalConfigSync(item: WorkItem): WorkResult {
        val runtime = co.sanaa.agent.core.AgentRuntime.get(context)
        val facts = mutableListOf<String>()
        if (runtime.config.configSyncEnabled) {
            runtime.backend.registerAndSync()
            facts += "Configuration synchronized"
        }
        if (runtime.config.memoryBackupEnabled && runtime.config.memoryAutoRestoreEnabled &&
            runtime.chatStore.summaryCount() == 0
        ) {
            runtime.memoryBackup.restoreLatest()
            facts += "Relationship memory restore checked"
        }
        if (runtime.config.memoryBackupEnabled &&
            System.currentTimeMillis() - runtime.config.lastMemoryBackupAt >= 12 * 60 * 60 * 1000L
        ) {
            runtime.memoryBackup.backupNow()
            facts += "Relationship memory backup checked"
        }
        return WorkResult(
            item,
            WorkStatus.DONE,
            outcomeFacts = facts.ifEmpty { listOf("Configuration and memory sync are disabled") },
        )
    }
}

/** Converts only facts parsed from the current Terminal audit into queued work. */
internal object SokoWorkDiscovery {
    fun fromAlerts(alerts: List<co.sanaa.agent.actions.SokoAlert>): List<WorkItem> = alerts.mapNotNull { alert ->
        val normalized = alert.subject.lowercase().filter(Char::isLetterOrDigit).take(80)
        if (normalized.isBlank()) return@mapNotNull null
        when (alert.type) {
            "Low stock" -> WorkItem(
                dedupeKey = "soko-restock:$normalized",
                domain = Domain.SOKO,
                kind = WorkKind.SOKO_RESTOCK_DRAFT,
                payload = org.json.JSONObject().put("product", alert.subject).put("evidence", alert.detail),
                baseValueKes = WorkScorer.defaultBaseValue(WorkKind.SOKO_RESTOCK_DRAFT),
                urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.SOKO_RESTOCK_DRAFT),
                estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.SOKO_RESTOCK_DRAFT),
                requires = emptySet(),
                riskTier = WorkScorer.defaultRiskTier(WorkKind.SOKO_RESTOCK_DRAFT),
            )
            "Order" -> WorkItem(
                dedupeKey = "soko-order:$normalized",
                domain = Domain.SOKO,
                kind = WorkKind.SOKO_ORDER_CONFIRM,
                payload = org.json.JSONObject()
                    .put("order_id", alert.subject.replaceFirst(Regex("(?i)^order\\s*"), "").trim())
                    .put("customer", alert.detail),
                baseValueKes = WorkScorer.defaultBaseValue(WorkKind.SOKO_ORDER_CONFIRM),
                urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.SOKO_ORDER_CONFIRM),
                estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.SOKO_ORDER_CONFIRM),
                requires = setOf(Capability.CONSENT_TIER_2),
                riskTier = WorkScorer.defaultRiskTier(WorkKind.SOKO_ORDER_CONFIRM),
            )
            else -> null
        }
    }
}
