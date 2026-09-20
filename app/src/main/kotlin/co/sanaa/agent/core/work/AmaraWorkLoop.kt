package co.sanaa.agent.core.work

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Wake reasons that can rouse the loop from SLEEPING.
 */
sealed class WakeReason {
    data class WhatsAppNotification(val sender: String, val message: String) : WakeReason()
    object ScheduledAlarm : WakeReason()
    object DailyCommercialCycle : WakeReason()
    object NetworkRestored : WakeReason()
    object BatteryCharging : WakeReason()
    data class ExternalEvent(val kind: String, val payload: String) : WakeReason()
}

/**
 * Session execution context.
 */
data class ExecutionSession(
    val grant: PhoneTimeBudgeter.SessionGrant,
    val snapshot: WorldSnapshot,
    val startTimeMs: Long = System.currentTimeMillis(),
)

/**
 * AmaraWorkLoop — the self-directing supervisor coroutine.
 * 
 * State machine: SLEEPING → SENSING → PLANNING → EXECUTING → REPORTING → SLEEPING
 * 
 * The loop sleeps until woken by an event, then:
 * 1. SENSE: reads world state
 * 2. PLAN: discovers work, scores it, checks budget
 * 3. EXECUTE: runs high-value work within budget/safety constraints
 * 4. REPORT: summarizes what was done
 * 
 * Key design: event-wakeable (not polling). Between wake events, it sleeps — zero battery drain.
 */
class AmaraWorkLoop(
    private val queue: WorkQueue,
    private val sources: List<WorkSource>,
    private val budgeter: PhoneTimeBudgeter,
    private val governor: SafetyGovernor,
    private val ownerMonitor: OwnerPresenceMonitor,
    private val executor: WorkExecutor,
    private val executionBoundary: suspend (suspend () -> WorkResult) -> WorkResult = { it() },
    private val onOutcome: (WorkResult) -> Unit = {},
    private val onChosen: (WorkItem, Int) -> Unit = { _, _ -> },
    private val quietHoursStart: () -> String = { "22:00" },
    private val quietHoursEnd: () -> String = { "06:00" },
    private val whatsAppAlwaysOn: () -> Boolean = { false },
    private val tikTokAlwaysOn: () -> Boolean = { false },
    private val learnedTimeFactor: (WorkKind, Int) -> Double = { _, _ -> 1.0 },
    private val onReport: suspend (WorkReport) -> Unit = {},
    private val onEscalation: suspend (WorkItem, String) -> Unit = { _, _ -> },
) {
    private val wakeSignal = Channel<WakeReason>(Channel.CONFLATED)
    @Volatile private var inspectionUntil = 0L
    fun pauseForInspection(durationMs: Long) {
        if (co.sanaa.agent.BuildConfig.DEBUG) inspectionUntil = System.currentTimeMillis() + durationMs.coerceIn(0, 300_000)
    }


    data class WorkReport(
        val itemsExecuted: Int,
        val itemsFailed: Int,
        val itemsDiscovered: Int,
        val screenSecondsUsed: Int,
        val summary: String,
    )

    enum class LoopState { SLEEPING, WAITING, SENSING, PLANNING, EXECUTING, REPORTING, YIELDING, HALTED }

    @Volatile
    private var currentState: LoopState = LoopState.SLEEPING
    private val startedAt = AtomicLong(0L)
    private val lastWakeAt = AtomicLong(0L)
    private val lastCycleAt = AtomicLong(0L)
    private val lastReportAt = AtomicLong(0L)
    private val wakeCount = AtomicLong(0L)
    private val cycleCount = AtomicLong(0L)
    private val executedCount = AtomicLong(0L)
    private val failedCount = AtomicLong(0L)
    private val lastWakeReason = AtomicReference("not_started")
    private val lastSummary = AtomicReference("Waiting for the first autonomous cycle")
    private val waitingReasons = linkedSetOf<String>()
    private val blockers = WorkBlockers(executor.context)

    /**
     * Main loop entry point. Runs until cancelled.
     */
    suspend fun run(scope: CoroutineScope) {
        startedAt.compareAndSet(0L, System.currentTimeMillis())
        while (scope.isActive && currentState != LoopState.HALTED) {
            try {
                // SLEEPING: wait for a wake event
                currentState = LoopState.WAITING
                waitForWake()
                if (System.currentTimeMillis() < inspectionUntil) {
                    delay(1_000)
                    wake(WakeReason.ScheduledAlarm)
                    continue
                }

                // SENSING: read world state
                currentState = LoopState.SENSING
                if (!co.sanaa.agent.core.OwnerPower(executor.context).isOn()) {
                    lastSummary.set("Amara is off. Queued work is held until the owner turns me on.")
                    continue
                }
                val snapshot = sense()
                blockers.refreshBattery()
                lastCycleAt.set(System.currentTimeMillis())
                cycleCount.incrementAndGet()

                // YIELD: if owner is active, go back to sleep
                if (snapshot.ownerActive) {
                    currentState = LoopState.YIELDING
                    lastSummary.set("Cycle observed the phone in use and yielded to the owner")
                    // Do not lose an inbound wake merely because the owner touched the
                    // phone at that instant. Retry soon; the normal presence guard still
                    // prevents Amara from taking over while the owner remains active.
                    if (queue.allPending().any { it.payload.optBoolean("owner_always_on", false) }) {
                        scope.launch {
                            delay(30_000)
                            wake(WakeReason.ExternalEvent("owner_idle_retry", ""))
                        }
                    }
                    continue
                }

                // Check governor state
                if (governor.getState() == SafetyGovernor.GovernorState.HALTED) {
                    blockers.flag("device:governor", "Autonomy", "The safety governor has halted autonomy.", true,
                        "Open Work and review the safety governor before resuming.", global = true)
                    lastSummary.set("Cycle stopped at the safety governor because autonomy is halted")
                    continue
                }
                blockers.clear("device:governor")
                blockers.clear("device:loop")

                // PLANNING: discover work and check budget
                currentState = LoopState.PLANNING
                waitingReasons.clear()
                queue.expireStale(System.currentTimeMillis())
                discoverAndEnqueue(snapshot)
                queue.refreshPendingChannelHours(whatsAppAlwaysOn(), tikTokAlwaysOn())

                // Check if there's anything worth doing
                val now = System.currentTimeMillis()
                val grant = budgeter.selectEligible(
                    queue, snapshot, now,
                    successRate = governor::successRate,
                    timeFactor = { learnedTimeFactor(it, snapshot.currentHour) },
                ) { item, reason ->
                    blockers.deferred(item, reason)
                    waitingReasons.add(reason)
                    lastSummary.set("Waiting: " + waitingReasons.joinToString("; "))
                    co.sanaa.agent.core.EvaluationJournal(executor.context).record("session_budget_deferred", item.dedupeKey,
                        org.json.JSONObject().put("kind", item.kind.name).put("reason", reason)
                            .put("battery_percent", snapshot.batteryPercent).put("thermal", snapshot.thermalState.name)
                            .put("screen_minutes_used_today", snapshot.screenMinutesUsedToday)
                            .put("screen_limit_minutes", budgeter.dailyLimitMinutes())
                            .put("remaining_daily_seconds", budgeter.remainingDailySeconds(snapshot)))
                }
                if (grant == null) {
                    if (waitingReasons.isEmpty()) lastSummary.set("Cycle completed safely; no eligible work was waiting")
                    continue
                }

                // EXECUTING: run work within budget
                currentState = LoopState.EXECUTING
                val session = ExecutionSession(grant, snapshot)
                val results = executeSession(session, scope)

                // REPORTING: summarize
                currentState = LoopState.REPORTING
                val report = WorkReport(
                    itemsExecuted = results.count { it.status == WorkStatus.DONE },
                    itemsFailed = results.count { it.status == WorkStatus.FAILED },
                    itemsDiscovered = results.sumOf { it.discoveredWork.size },
                    screenSecondsUsed = results.sumOf { it.screenSecondsUsed },
                    summary = buildReportSummary(results),
                )
                onReport(report)
                lastReportAt.set(System.currentTimeMillis())
                executedCount.addAndGet(report.itemsExecuted.toLong())
                failedCount.addAndGet(report.itemsFailed.toLong())
                lastSummary.set(report.summary)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { blockers.flag("device:loop", "Autonomous loop", "Cycle failed: ${e.javaClass.simpleName}.",
                    action = "Amara will retry in 30 seconds. If this repeats, open Work for inspection.", global = true) }
                lastSummary.set("Loop recovery waiting after ${e.javaClass.simpleName}; retry in 30 seconds")
                co.sanaa.agent.core.EvaluationJournal(executor.context).record("loop_iteration_failed",
                    fields = org.json.JSONObject().put("error_class", e.javaClass.simpleName))
                android.util.Log.e("AmaraWorkLoop", "Loop iteration failed", e)
                delay(30_000) // Wait 30s before retrying
            }
        }
    }

    /**
     * Wake the loop from sleep.
     */
    fun wake(reason: WakeReason) {
        lastWakeAt.set(System.currentTimeMillis())
        lastWakeReason.set(reason.toLabel())
        wakeCount.incrementAndGet()
        wakeSignal.trySend(reason)
    }

    /** Wait-free owner-visible proof that the autonomous supervisor is alive. */
    fun diagnostics(): Map<String, Any> {
        blockers.refreshBattery()
        return mapOf(
        "blockers" to blockers.rows(),
        "tikTokPostingRemainingSeconds" to governor.tikTokPostingSecondsRemaining(),
        "tikTokPostingLimitMinutes" to governor.tikTokPostingLimitMinutes(),
        "ownerOn" to co.sanaa.agent.core.OwnerPower(executor.context).isOn(),
        "running" to (co.sanaa.agent.core.OwnerPower(executor.context).isOn() && startedAt.get() > 0L && currentState != LoopState.HALTED),
        "state" to currentState.name,
        "startedAt" to startedAt.get(),
        "lastWakeAt" to lastWakeAt.get(),
        "lastCycleAt" to lastCycleAt.get(),
        "lastReportAt" to lastReportAt.get(),
        "lastWakeReason" to lastWakeReason.get(),
        "wakeCount" to wakeCount.get(),
        "cycleCount" to cycleCount.get(),
        "itemsExecuted" to executedCount.get(),
        "itemsFailed" to failedCount.get(),
        "lastSummary" to lastSummary.get(),
        "sourceCount" to sources.size,
    )
    }

    private suspend fun waitForWake(): WakeReason {
        return withTimeoutOrNull(queue.nextWakeDelayMillis(System.currentTimeMillis())) { wakeSignal.receive() }
            ?: WakeReason.ScheduledAlarm
    }

    private fun WakeReason.toLabel(): String = when (this) {
        is WakeReason.WhatsAppNotification -> "whatsapp_notification"
        WakeReason.ScheduledAlarm -> "scheduled_alarm"
        WakeReason.DailyCommercialCycle -> "commercial_cycle"
        WakeReason.NetworkRestored -> "network_restored"
        WakeReason.BatteryCharging -> "battery_charging"
        is WakeReason.ExternalEvent -> "external:$kind"
    }

    private fun sense(): WorldSnapshot {
        val ownerActive = ownerMonitor.isOwnerActive()
        val powerManager = executor.context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val batteryIntent = executor.context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val batteryPct = batteryIntent?.let {
            val level = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) (level * 100 / scale) else 50
        } ?: 50

        val now = java.time.LocalTime.now()
        val hour = now.hour
        val quietStart = runCatching { java.time.LocalTime.parse(quietHoursStart()) }.getOrDefault(java.time.LocalTime.of(22, 0))
        val quietEnd = runCatching { java.time.LocalTime.parse(quietHoursEnd()) }.getOrDefault(java.time.LocalTime.of(6, 0))
        val quietHours = if (quietStart <= quietEnd) now >= quietStart && now < quietEnd else now >= quietStart || now < quietEnd

        return WorldSnapshot(
            ownerActive = ownerActive,
            screenOn = powerManager.isInteractive,
            batteryPercent = batteryPct,
            // ColorOS reports aggregate THERMAL_STATUS_SEVERE for normal sustained
            // workloads on this device, which previously disabled all autonomy. Use
            // battery temperature—the relevant hardware safety signal for unattended
            // phone work—while retaining conservative warm/hot thresholds.
            thermalState = batteryThermalState(
                batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1,
            ),
            networkAvailable = executor.networkAvailable(),
            currentHour = hour,
            quietHours = quietHours,
            messagesSentToday = governor.messagesSentToday(),
            screenMinutesUsedToday = governor.screenSecondsToday() / 60,
            groqCallsToday = 0,
        ).also { snapshot ->
            co.sanaa.agent.core.EvaluationJournal(executor.context).record("world",
                fields=org.json.JSONObject().put("owner_active",snapshot.ownerActive)
                    .put("screen_on",snapshot.screenOn).put("network",snapshot.networkAvailable)
                    .put("battery_percent",snapshot.batteryPercent).put("thermal",snapshot.thermalState.name)
                    .put("quiet_hours",snapshot.quietHours))
        }
    }

    private suspend fun discoverAndEnqueue(snapshot: WorldSnapshot) {
        for (source in sources) {
            try {
                val items = source.propose(snapshot)
                for (item in items) {
                    queue.offer(item)
                }
            } catch (e: Exception) {
                android.util.Log.w("AmaraWorkLoop", "WorkSource ${source.domain} failed", e)
            }
        }
    }

    private suspend fun executeSession(
        session: ExecutionSession,
        scope: CoroutineScope,
    ): List<WorkResult> {
        val results = mutableListOf<WorkResult>()
        val excludedKinds = mutableSetOf<WorkKind>()
        val excludedKeys = mutableSetOf<String>()

        while (System.currentTimeMillis() < session.grant.hardStopAt && scope.isActive) {
            if (!co.sanaa.agent.core.OwnerPower(executor.context).isOn()) break
            // Re-sense before every atomic item. A session grant is not permission
            // to ignore a phone that became hot or low on battery after it began.
            val liveSnapshot = sense()

            // Get best item
            val item = queue.peekBest(
                System.currentTimeMillis(),
                excludeKinds = excludedKinds,
                excludeKeys = excludedKeys,
                successRate = governor::successRate,
                timeFactor = { learnedTimeFactor(it, session.snapshot.currentHour) },
            ) ?: break
            if (System.currentTimeMillis() < inspectionUntil || liveSnapshot.ownerActive) break
            if (shouldStopForDeviceHealth(liveSnapshot, item)) {
                blockers.deferred(item, budgeter.denialReason(liveSnapshot, item))
                break
            }

            // A background/reply grant must never authorize a different item's
            // quiet-hours or daily-budget bypass later in the same session.
            if (budgeter.requestSession(liveSnapshot, item) == null) {
                val reason = budgeter.denialReason(liveSnapshot, item)
                blockers.deferred(item, reason)
                excludedKeys.add(item.dedupeKey)
                queue.deferPending(item.dedupeKey, System.currentTimeMillis() + 60_000L)
                waitingReasons.add(reason)
                continue
            }

            // Check safety governor
            val sessionScreenSeconds = ((System.currentTimeMillis() - session.startTimeMs) / 1000).toInt()
            val verdict = governor.isAllowed(
                item,
                sessionScreenSeconds,
                results.count { it.status == WorkStatus.DONE && Capability.CONSENT_TIER_2 in it.item.requires },
                liveSnapshot,
            )
            if (!verdict.allowed) {
                blockers.deferred(item, verdict.reason)
                co.sanaa.agent.core.EvaluationJournal(executor.context).record("policy_deferred", item.dedupeKey,
                    org.json.JSONObject().put("kind",item.kind.name).put("reason",verdict.reason))
                android.util.Log.i("AmaraWorkLoop", "Item blocked: ${verdict.reason}")
                val cooldown = governor.cooldownFor(item)
                if (cooldown > 0) queue.deferPending(item.dedupeKey, System.currentTimeMillis() + cooldown)
                waitingReasons.add(verdict.reason)
                excludedKeys.add(item.dedupeKey)
                continue
            }

            // Locked-screen autonomy still needs an honestly observable foreground.
            // Wake only for screen work, never attempt a secure keyguard, and leave
            // the item pending when Android cannot provide an available surface.
            if (Capability.SCREEN in item.requires) {
                val availability = co.sanaa.agent.core.DeviceAvailabilityGuard.ensureAvailable(executor.context)
                if (!availability.available) {
                    blockers.deferred(item, availability.reason)
                    android.util.Log.i(
                        "AmaraWorkLoop",
                        "Screen work ${item.kind} deferred: ${availability.blocker} (${availability.reason})",
                    )
                    queue.deferPending(item.dedupeKey, System.currentTimeMillis() + 60_000L)
                    waitingReasons.add(availability.reason)
                    lastSummary.set("Waiting: ${availability.reason}")
                    co.sanaa.agent.core.EvaluationJournal(executor.context).record("device_unavailable", item.dedupeKey,
                        org.json.JSONObject().put("kind", item.kind.name).put("blocker", availability.blocker.name))
                    excludedKinds.add(item.kind)
                    continue
                }
            }

            // Execute
            if (!queue.markInFlight(item.dedupeKey)) continue
            blockers.admitted(item)
            val result = kotlinx.coroutines.withTimeoutOrNull(itemTimeoutMs(item.kind)) {
                executionBoundary {
                    try {
                        safelyReport { onChosen(item, session.snapshot.currentHour) }
                        executor.execute(item)
                    } finally {
                        if (Capability.SCREEN in item.requires) ownerMonitor.rememberAutomationForeground()
                    }
                }
            } ?: WorkResult(item, WorkStatus.ESCALATED,
                screenSecondsUsed = if (Capability.SCREEN in item.requires) (itemTimeoutMs(item.kind) / 1000).toInt() else 0,
                failure = FailureInfo(FailureClass.UNKNOWN,
                    "Task exceeded its time budget; saved for review. Any external effect is unproven and must not be blindly retried.", false))
            results.add(result)
            co.sanaa.agent.core.EvaluationJournal(executor.context).record("outcome", item.dedupeKey,
                org.json.JSONObject().put("kind", item.kind.name).put("status", result.status.name)
                    .put("failure_class", result.failure?.klass?.name ?: "")
                    .put("failure_reason", result.failure?.summary?.let(co.sanaa.agent.core.Redactor::redact) ?: "")
                    .put("observed_at", item.payload.optLong("inbound_observed_at")))

            // Handle result
            if (result.status == WorkStatus.DONE) {
                queue.complete(item.dedupeKey)
                safelyReport { blockers.outcome(result) }
            } else if (result.status == WorkStatus.FAILED || result.status == WorkStatus.SKIPPED || result.status == WorkStatus.PARTIAL) {
                // Apply failure recovery
                val recovery = executor.decideRecovery(item, result, item.attempt + 1)
                when (recovery) {
                    is RecoveryDecision.Drop -> queue.complete(item.dedupeKey)
                    is RecoveryDecision.Requeue -> queue.requeue(
                        item.dedupeKey,
                        System.currentTimeMillis() + recovery.delayMs,
                        item.attempt + 1
                    )
                    is RecoveryDecision.Escalate -> {
                        if(item.kind in setOf(WorkKind.WA_REPLY_INBOUND,WorkKind.WA_FOLLOWUP)) queue.requireReview(item,recovery.reason)
                        else queue.complete(item.dedupeKey)
                        safelyReport { onEscalation(item, recovery.reason) }
                    }
                    else -> {}
                }
                safelyReport { blockers.outcome(result, recovery) }

                // Isolate this failing kind; do not strand unrelated work or replies.
                if (item.kind != WorkKind.WA_REPLY_INBOUND) excludedKinds.add(item.kind)
            } else if (result.status == WorkStatus.ESCALATED) {
                safelyReport { blockers.outcome(result) }
                if(item.kind in setOf(WorkKind.WA_REPLY_INBOUND,WorkKind.WA_FOLLOWUP)) queue.requireReview(item,result.failure?.summary ?: "Unresolved reply")
                else queue.complete(item.dedupeKey)
                result.failure?.let { failure -> safelyReport { onEscalation(item, failure.summary) } }
                if (item.kind != WorkKind.WA_REPLY_INBOUND) excludedKinds.add(item.kind)
            }

            // Durable disposition precedes fallible analytics; telemetry cannot strand work.
            safelyReport { governor.recordExecution(result) }
            safelyReport { onOutcome(result) }
            // Discovery is useful even when a later audit surface fails. Each
            // item has a durable dedupe key, so offering partial findings is safe.
            for (discovered in result.discoveredWork) {
                queue.offer(discovered)
            }

            lastSummary.set("${item.kind.name}: ${result.status.name}" + (result.failure?.let { " — ${it.summary}" } ?: ""))
            if (Capability.SCREEN in item.requires && !ownerMonitor.isOwnerActive()) {
                safelyReport { withTimeoutOrNull(5_000L) { executionBoundary {
                    if (!ownerMonitor.isOwnerActive()) {
                        val intent = executor.context.packageManager.getLaunchIntentForPackage(executor.context.packageName)
                        if (intent != null) {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            executor.context.startActivity(intent)
                            delay(300)
                            ownerMonitor.rememberAutomationForeground()
                            val returned=co.sanaa.agent.actions.AccessibilityActions(executor.context).snapshot().packageName == executor.context.packageName
                            co.sanaa.agent.core.EvaluationJournal(executor.context).record(
                                if(returned) "returned_to_amara" else "return_to_amara_unverified",item.dedupeKey)
                        }
                    }
                    result
                } } }
            }
            // Small delay between items
            delay(500)
        }

        return results
    }

    private suspend fun safelyReport(block: suspend () -> Unit) {
        isolateReporting(block) {
            co.sanaa.agent.core.EvaluationJournal(executor.context).record("reporting_failure")
            android.util.Log.e("AmaraWorkLoop", "Outcome reporting failed after durable disposition")
        }
    }

    companion object {
        internal suspend fun isolateReporting(block: suspend () -> Unit, onFailure: () -> Unit) {
            try { block() } catch (error: Exception) {
                if (error is CancellationException) throw error
                onFailure()
            }
        }
        internal fun itemTimeoutMs(kind: WorkKind): Long = when (kind) {
            WorkKind.WA_REPLY_INBOUND -> 90_000L
            WorkKind.YOUTUBE_SHORT_PUBLISH -> 600_000L
            WorkKind.TIKTOK_POST_PUBLISH -> 480_000L // Render/session recovery plus the bounded publication transaction.
            WorkKind.TIKTOK_STORY_PUBLISH -> 420_000L
            WorkKind.SOKO_AUDIT, WorkKind.SOKO_INVENTORY_CHECK -> 240_000L
            else -> 120_000L
        }

        internal fun batteryThermalState(tenthsCelsius: Int): ThermalState = when {
            tenthsCelsius < 0 -> ThermalState.NORMAL
            tenthsCelsius >= 450 -> ThermalState.HOT
            tenthsCelsius >= 400 -> ThermalState.WARM
            else -> ThermalState.NORMAL
        }

        internal fun shouldStopForDeviceHealth(snapshot: WorldSnapshot, item: WorkItem? = null): Boolean =
            (snapshot.thermalState == ThermalState.HOT &&
                item?.payload?.optBoolean("owner_canary", false) != true &&
                item?.payload?.optBoolean("owner_command", false) != true) ||
                snapshot.batteryPercent <= 15
    }

    private fun buildReportSummary(results: List<WorkResult>): String {
        if (results.isEmpty()) return if(waitingReasons.isNotEmpty()) "Waiting: " + waitingReasons.joinToString("; ")
            else "No eligible work is due."
        val succeeded = results.count { it.status == WorkStatus.DONE }
        val failed = results.count { it.status == WorkStatus.FAILED }
        val discovered = results.sumOf { it.discoveredWork.size }
        return "Session complete: $succeeded done, $failed failed, $discovered new work discovered."
    }
}

sealed class RecoveryDecision {
    object Drop : RecoveryDecision()
    data class Requeue(val delayMs: Long) : RecoveryDecision()
    data class Escalate(val reason: String) : RecoveryDecision()
}
