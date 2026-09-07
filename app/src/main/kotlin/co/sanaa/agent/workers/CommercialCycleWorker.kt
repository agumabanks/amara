package co.sanaa.agent.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.commerce.DailyCommercialCycle
import co.sanaa.agent.core.commerce.FunnelStage
import co.sanaa.agent.core.work.WakeReason
import java.time.Instant
import java.time.LocalTime

/** WorkManager is only the durable alarm; the governed loop owns execution. */
class CommercialCycleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        AgentRuntime.get(applicationContext).awaitReady().workLoop.wake(WakeReason.DailyCommercialCycle)
        Result.success()
    }.getOrElse { Result.retry() }

    internal enum class CommercialPhase { MORNING, SIGNAL_PASS, END_OF_DAY }

    companion object {
        private val MORNING_START: LocalTime = LocalTime.of(6, 0)
        private val MORNING_END_EXCLUSIVE: LocalTime = LocalTime.of(11, 0)
        private val END_OF_DAY_START: LocalTime = LocalTime.of(18, 0)

        internal fun phaseAt(time: LocalTime): CommercialPhase = when {
            !time.isBefore(MORNING_START) && time.isBefore(MORNING_END_EXCLUSIVE) -> CommercialPhase.MORNING
            !time.isBefore(END_OF_DAY_START) -> CommercialPhase.END_OF_DAY
            else -> CommercialPhase.SIGNAL_PASS
        }

        internal fun provenInventory(observed: List<String>, allowed: Set<String>): List<String> =
            observed.asSequence().map(String::trim).filter(String::isNotBlank)
                .filter { it in allowed }.distinct().toList()

        internal fun failClosedHealth(
            base: DailyCommercialCycle.HealthSignals,
            accessibilityReady: Boolean,
            whatsappInstalled: Boolean,
            recentSokoRead: Boolean,
        ): DailyCommercialCycle.HealthSignals {
            val deviceHealthy = accessibilityReady && base.deviceSessionHealthy
            val whatsappHealthy = deviceHealthy && whatsappInstalled && base.whatsappChannelHealthy
            val sokoHealthy = deviceHealthy && recentSokoRead && base.sokoReachable
            val blockers = buildList {
                addAll(base.blockers)
                if (!accessibilityReady) add("accessibility device session is unavailable")
                if (!whatsappInstalled) add("WhatsApp is not installed or not visible to the package manager")
                if (!recentSokoRead) add("no successful live Soko inventory read exists within the last 24 hours")
            }.distinct()
            return DailyCommercialCycle.HealthSignals(deviceHealthy, sokoHealthy, whatsappHealthy, blockers)
        }
    }
}

/** Internal-only commercial planning, invoked exclusively by WorkExecutor. */
class CommercialCycleRunner(private val context: Context) {
    private val runtime = AgentRuntime.get(context)

    suspend fun run(): String {
        val ops = runtime.revenueOps
        val zone = ops.policy()?.takeIf { it.ownerTimeZoneId.isNotBlank() }?.ownerZone()
            ?: return "Commercial cycle skipped: owner timezone is not configured"
        val local = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone)
        val dayKey = ops.cycle.dayKey(zone)
        return when (CommercialCycleWorker.phaseAt(local.toLocalTime())) {
            CommercialCycleWorker.CommercialPhase.MORNING -> runMorningOnce(dayKey)
            CommercialCycleWorker.CommercialPhase.END_OF_DAY -> runEndOfDayOnce(dayKey)
            CommercialCycleWorker.CommercialPhase.SIGNAL_PASS -> runSignalPass(dayKey)
        }
    }

    private suspend fun runMorningOnce(dayKey: String): String {
        if (runtime.revenueStore.dailyPlan(dayKey) != null) return "Commercial morning plan already exists for $dayKey"
        val snapshot = buildSnapshot()
        runtime.revenueOps.cycle.planMorning(snapshot, liveHealth(snapshot.inventory))
        runtime.reporter.report("Daily commercial plan ready", "Bounded plan for $dayKey prepared", co.sanaa.agent.notifications.NotificationReporter.Priority.INFO)
        return "Prepared the bounded commercial plan for $dayKey"
    }

    private fun runSignalPass(dayKey: String): String {
        val now = System.currentTimeMillis()
        val health = liveHealth(buildSnapshot().inventory)
        var checked = 0
        runtime.revenueStore.commercialActions(state = "PLANNED", planDay = dayKey).forEach { action ->
            checked++
            if (!health.deviceSessionHealthy || !health.sokoReachable || !health.whatsappChannelHealthy) {
                runtime.revenueStore.updateCommercialActionState(action.id, "PLANNED", "health blocked before approval: ${health.blockers.joinToString("; ").take(420)}", now)
                return@forEach
            }
            when (val verdict = runtime.revenueOps.cycle.recheckBeforeApproval(action.id)) {
                is co.sanaa.agent.core.commerce.CommercialPolicy.PolicyVerdict.Allowed -> {
                    if (!runtime.config.whatsAppAutomationEnabled || !runtime.config.whatsAppFollowUpsEnabled) {
                        runtime.revenueStore.updateCommercialActionState(action.id, "PLANNED", "eligible; waiting for owner to enable WhatsApp follow-up autopilot", now)
                    } else {
                        val product = runCatching { org.json.JSONObject(action.rankingJson).optString("productRef") }.getOrDefault("")
                        if (product.isBlank()) {
                            runtime.revenueStore.updateCommercialActionState(action.id, "BLOCKED_POLICY", "planned action has no grounded product", now)
                        } else {
                            val message = "Hello! You previously asked about $product. Would you like me to check current availability and details for you?"
                            runtime.workQueue.offer(co.sanaa.agent.core.work.WorkItem(
                                dedupeKey = "commercial-followup:${action.id}",
                                domain = co.sanaa.agent.core.work.Domain.WHATSAPP,
                                kind = co.sanaa.agent.core.work.WorkKind.WA_FOLLOWUP,
                                payload = org.json.JSONObject().put("target", action.target).put("message", message).put("commercial_action_id", action.id),
                                baseValueKes = co.sanaa.agent.core.work.WorkScorer.defaultBaseValue(co.sanaa.agent.core.work.WorkKind.WA_FOLLOWUP),
                                urgencyHalfLifeHours = co.sanaa.agent.core.work.WorkScorer.defaultHalfLife(co.sanaa.agent.core.work.WorkKind.WA_FOLLOWUP),
                                estimatedScreenSeconds = co.sanaa.agent.core.work.WorkScorer.defaultScreenSeconds(co.sanaa.agent.core.work.WorkKind.WA_FOLLOWUP),
                                requires = setOf(co.sanaa.agent.core.work.Capability.SCREEN, co.sanaa.agent.core.work.Capability.NETWORK, co.sanaa.agent.core.work.Capability.GROQ, co.sanaa.agent.core.work.Capability.CONSENT_TIER_2),
                                riskTier = co.sanaa.agent.core.work.WorkScorer.defaultRiskTier(co.sanaa.agent.core.work.WorkKind.WA_FOLLOWUP),
                            ))
                            runtime.revenueStore.updateCommercialActionState(action.id, "QUEUED", "standing WhatsApp policy admitted this action to the governed queue", now)
                        }
                    }
                }
                is co.sanaa.agent.core.commerce.CommercialPolicy.PolicyVerdict.Blocked ->
                    runtime.revenueStore.updateCommercialActionState(action.id, "BLOCKED_POLICY", verdict.reason.take(500), now)
            }
        }
        runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("commercial_actions_queued", dayKey))
        return "Rechecked $checked commercial actions for $dayKey"
    }

    private suspend fun runEndOfDayOnce(dayKey: String): String {
        runSignalPass(dayKey)
        if (runtime.revenueStore.dailyBriefs(limit = 90).any { it.first == dayKey }) return "Commercial close-out already exists for $dayKey"
        val result = runtime.revenueOps.cycle.closeOut(emptyList(), liveHealth(buildSnapshot().inventory).blockers)
        val revision = runtime.artifactStore.commit("commercial-brief-$dayKey", result.briefSpec, System.currentTimeMillis())
        runtime.revenueStore.saveDailyBrief(
            dayKey, revision.id,
            org.json.JSONObject().put("targetsSummary", result.targetsSummary)
                .put("proposedExperiment", result.proposedExperiment?.id ?: "").toString(),
            System.currentTimeMillis(),
        )
        runtime.reporter.report("Daily commercial brief ready", result.targetsSummary, co.sanaa.agent.notifications.NotificationReporter.Priority.ACTION_NEEDED)
        return "Committed the verified commercial close-out for $dayKey"
    }

    private fun buildSnapshot(): DailyCommercialCycle.BusinessSnapshot {
        val store = runtime.revenueStore
        val allowed = runtime.revenueOps.policy()?.allowedProducts.orEmpty()
        val inventory = CommercialCycleWorker.provenInventory(runtime.memory.recentProductNames(), allowed)
        val followUps = store.opportunities(stages = setOf(FunnelStage.ENGAGED, FunnelStage.QUALIFIED_INQUIRY))
            .filter { !store.isSuppressed(it.contactKey) && it.productRef in inventory }
            .map { DailyCommercialCycle.BusinessSnapshot.PendingFollowUp(it.id, it.contactKey, it.productRef, it.updatedAtMs) }
        return DailyCommercialCycle.BusinessSnapshot(
            inventory = inventory, weakListings = emptyList(), orders = emptyList(), bookings = emptyList(),
            salesSignals = emptyList(), inboundInquiries = emptyList(), pendingFollowUps = followUps,
            campaignAnalytics = emptyList(),
        )
    }

    private fun liveHealth(inventory: List<String>): DailyCommercialCycle.HealthSignals {
        val accessibilityReady = runCatching { runtime.actions.isAvailable() }.getOrDefault(false)
        val whatsappInstalled = runCatching { context.packageManager.getPackageInfo("com.whatsapp", 0); true }.getOrDefault(false)
        val recentSokoRead = inventory.isNotEmpty() && runCatching {
            runtime.memory.actionSucceededRecently("soko_inventory_scan", "Read the Soko inventory", System.currentTimeMillis() - 86_400_000L)
        }.getOrDefault(false)
        return CommercialCycleWorker.failClosedHealth(runtime.revenueOps.healthSignals(), accessibilityReady, whatsappInstalled, recentSokoRead)
    }
}
