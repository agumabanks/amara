package co.sanaa.agent.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.commerce.DailyCommercialCycle
import co.sanaa.agent.core.commerce.FunnelStage
import java.time.Instant
import java.time.LocalTime

/**
 * The production daily commercial cycle (charter daily loop), scheduled by WorkManager:
 *
 *  MORNING (owner-local 06:00–11:00): health + ledger snapshot → bounded, persisted day
 *  plan via DailyCommercialCycle.planMorning. Unique per owner-local day.
 *
 *  DURING DAY: policy and live-health re-check of every planned action. Refused
 *  actions park as BLOCKED_POLICY. Eligible actions remain PLANNED until the
 *  exact-bound commercial-workflow approval bridge exists; merely changing a row to
 *  AWAITING_APPROVAL would falsely imply that an approval request was created.
 *
 *  END OF DAY (18:00–23:00): reconciliation, targets, attribution sweep, experiment
 *  guardrails, rubric-enforced owner brief committed to the artifact store.
 *
 * All phase decisions use the OWNER-CONFIGURED timezone from commercial policy; each
 * phase claims its unique occurrence key ("commercial:<phase>:<dayKey>") so restarts,
 * reboots, and timezone changes can never duplicate a day's work.
 */
class CommercialCycleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val runtime = AgentRuntime.get(applicationContext)

    override suspend fun doWork(): Result {
        val ops = runtime.revenueOps
        val zone = ops.policy()?.takeIf { it.ownerTimeZoneId.isNotBlank() }?.ownerZone()
            ?: return Result.success() // fail closed: no owner timezone configured yet
        val now = System.currentTimeMillis()
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val dayKey = ops.cycle.dayKey(zone)
        return try {
            when (phaseAt(local.toLocalTime())) {
                CommercialPhase.MORNING -> runMorningOnce(dayKey)
                CommercialPhase.END_OF_DAY -> runEndOfDayOnce(dayKey)
                CommercialPhase.SIGNAL_PASS -> runSignalPass(dayKey)
            }
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    /** Morning plan runs at most once per owner-local day (unique occurrence key). */
    private suspend fun runMorningOnce(dayKey: String) {
        if (runtime.revenueStore.dailyPlan(dayKey) != null) return // already claimed
        val snapshot = buildSnapshot()
        val health = liveHealth(snapshot.inventory)
        runtime.revenueOps.cycle.planMorning(snapshot, health)
        runtime.reporter.report("Daily commercial plan ready", "Bounded plan for $dayKey prepared", co.sanaa.agent.notifications.NotificationReporter.Priority.INFO)
    }

    /** During-day signal pass: re-check every planned action under fresh durable state. */
    private fun runSignalPass(dayKey: String) {
        val now = System.currentTimeMillis()
        val health = liveHealth(buildSnapshot().inventory)
        runtime.revenueStore.commercialActions(state = "PLANNED", planDay = dayKey).forEach { action ->
            if (!health.deviceSessionHealthy || !health.sokoReachable || !health.whatsappChannelHealthy) {
                runtime.revenueStore.updateCommercialActionState(
                    action.id,
                    "PLANNED",
                    "health blocked before approval: ${health.blockers.joinToString("; ").take(420)}",
                    now,
                )
                return@forEach
            }
            when (val verdict = runtime.revenueOps.cycle.recheckBeforeApproval(action.id)) {
                is co.sanaa.agent.core.commerce.CommercialPolicy.PolicyVerdict.Allowed ->
                    // There is not yet a production bridge that binds this exact commercial
                    // action/draft to a resumable workflow approval. Keep the truthful state;
                    // never manufacture AWAITING_APPROVAL without a real request id.
                    runtime.revenueStore.updateCommercialActionState(
                        action.id,
                        "PLANNED",
                        "policy and live-health rechecks passed; exact-bound workflow approval bridge pending",
                        now,
                    )
                is co.sanaa.agent.core.commerce.CommercialPolicy.PolicyVerdict.Blocked ->
                    runtime.revenueStore.updateCommercialActionState(action.id, "BLOCKED_POLICY", verdict.reason.take(500), now)
            }
        }
    }

    /** End-of-day reconciliation + brief; unique per owner-local day. */
    private suspend fun runEndOfDayOnce(dayKey: String) {
        runSignalPass(dayKey)
        if (runtime.revenueStore.dailyBriefs(limit = 90).any { it.first == dayKey }) return // already claimed
        val result = runtime.revenueOps.cycle.closeOut(
            dayLessons = emptyList(),
            unresolved = liveHealth(buildSnapshot().inventory).blockers,
        )
        val revision = runtime.artifactStore.commit(
            artifactId = "commercial-brief-$dayKey",
            spec = result.briefSpec,
            nowMs = System.currentTimeMillis(),
        )
        runtime.revenueStore.saveDailyBrief(
            dayKey, revision.id,
            org.json.JSONObject()
                .put("targetsSummary", result.targetsSummary)
                .put("proposedExperiment", result.proposedExperiment?.id ?: "")
                .toString(),
            System.currentTimeMillis(),
        )
        runtime.reporter.report("Daily commercial brief ready", result.targetsSummary, co.sanaa.agent.notifications.NotificationReporter.Priority.ACTION_NEEDED)
    }

    /** Ledger-derived snapshot: only REAL cached inventory and REAL opportunities. */
    private fun buildSnapshot(): DailyCommercialCycle.BusinessSnapshot {
        val store = runtime.revenueStore
        val allowedProducts = runtime.revenueOps.policy()?.allowedProducts.orEmpty()
        val inventory = provenInventory(runtime.memory.recentProductNames(), allowedProducts)
        val followUps = store.opportunities(stages = setOf(FunnelStage.ENGAGED, FunnelStage.QUALIFIED_INQUIRY))
            .filter { !store.isSuppressed(it.contactKey) }
            // The generated follow-up says the product is available. Never create that
            // claim for a product absent from the durable observed inventory snapshot.
            .filter { it.productRef in inventory }
            .map { DailyCommercialCycle.BusinessSnapshot.PendingFollowUp(it.id, it.contactKey, it.productRef, it.updatedAtMs) }
        return DailyCommercialCycle.BusinessSnapshot(
            inventory = inventory, weakListings = emptyList(),
            orders = emptyList(), bookings = emptyList(), salesSignals = emptyList(),
            inboundInquiries = emptyList(), // live ingestion admits inquiries on arrival
            pendingFollowUps = followUps, campaignAnalytics = emptyList(),
        )
    }

    /**
     * Fail-closed production health derived from current device reachability plus durable
     * evidence. RevenueOperatorRuntime's composition defaults are deliberately not enough
     * to authorize progression: a recent successful live Soko inventory read is required.
     */
    private fun liveHealth(inventory: List<String>): DailyCommercialCycle.HealthSignals {
        val now = System.currentTimeMillis()
        val accessibilityReady = runCatching { runtime.actions.isAvailable() }.getOrDefault(false)
        val whatsappInstalled = runCatching {
            applicationContext.packageManager.getPackageInfo("com.whatsapp", 0)
            true
        }.getOrDefault(false)
        val recentSokoRead = inventory.isNotEmpty() && runCatching {
            runtime.memory.actionSucceededRecently(
                "soko_inventory_scan",
                "Read the Soko inventory",
                now - LIVE_SOKO_EVIDENCE_MAX_AGE_MS,
            )
        }.getOrDefault(false)
        return failClosedHealth(
            runtime.revenueOps.healthSignals(),
            accessibilityReady = accessibilityReady,
            whatsappInstalled = whatsappInstalled,
            recentSokoRead = recentSokoRead,
        )
    }

    internal enum class CommercialPhase { MORNING, SIGNAL_PASS, END_OF_DAY }

    companion object {
        private val MORNING_START: LocalTime = LocalTime.of(6, 0)
        private val MORNING_END_EXCLUSIVE: LocalTime = LocalTime.of(11, 0)
        private val END_OF_DAY_START: LocalTime = LocalTime.of(18, 0)
        private const val LIVE_SOKO_EVIDENCE_MAX_AGE_MS = 24 * 60 * 60 * 1_000L

        internal fun phaseAt(time: LocalTime): CommercialPhase = when {
            !time.isBefore(MORNING_START) && time.isBefore(MORNING_END_EXCLUSIVE) -> CommercialPhase.MORNING
            !time.isBefore(END_OF_DAY_START) -> CommercialPhase.END_OF_DAY
            else -> CommercialPhase.SIGNAL_PASS
        }

        internal fun provenInventory(observed: List<String>, allowed: Set<String>): List<String> =
            observed.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .filter { it in allowed }
                .distinct()
                .toList()

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
