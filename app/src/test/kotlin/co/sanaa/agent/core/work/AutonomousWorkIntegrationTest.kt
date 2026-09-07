package co.sanaa.agent.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.actions.WhatsAppScreenSnapshot
import co.sanaa.agent.actions.SokoAlert
import co.sanaa.agent.core.ChatStore
import co.sanaa.agent.core.AutonomyController
import co.sanaa.agent.core.market.JijiScraper
import co.sanaa.agent.core.market.MarketDatabase
import co.sanaa.agent.core.knowledge.LearningDatabase
import co.sanaa.agent.core.knowledge.LearningLoop
import co.sanaa.agent.modules.HumanConversationEngine
import co.sanaa.agent.core.work.sources.InternalSource
import co.sanaa.agent.core.work.sources.TikTokWorkSource
import co.sanaa.agent.core.work.sources.WhatsAppFollowUpSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutonomousWorkIntegrationTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("amara_work_queue.db")
        context.deleteDatabase("amara_chats.db")
        context.deleteDatabase("amara_market.db")
        context.deleteDatabase("amara_learning.db")
    }

    private fun snapshot(
        ownerActive: Boolean = false,
        network: Boolean = true,
        hour: Int = 12,
        quiet: Boolean = false,
    ) = WorldSnapshot(ownerActive, false, 80, ThermalState.NORMAL, network, hour, quiet, 0, 0, 0)

    @Test fun sameNameDistinctOriginsNeverSupersedeEachOther() {
        WorkQueue(context).use { queue ->
            fun item(key:String,id:String)=WorkItem(key,Domain.WHATSAPP,WorkKind.WA_REPLY_INBOUND,
                payload=org.json.JSONObject().put("conversation","Sam").put("conversation_identity",id).put("message","hello"),
                baseValueKes=1.0,urgencyHalfLifeHours=1.0,estimatedScreenSeconds=20)
            queue.offer(item("sam1","origin1"));queue.offer(item("sam2","origin2"));queue.offer(item("sam1-new","origin1"))
            queue.compactPendingBacklog()
            assertEquals(setOf("sam2","sam1-new"),queue.allPending().map { it.dedupeKey }.toSet())
        }
    }

    @Test fun unresolvedReplySurvivesExpiryWithoutBlockingOtherWork() {
        WorkQueue(context).use { queue ->
            val item=WorkItem("unresolved",Domain.WHATSAPP,WorkKind.WA_REPLY_INBOUND,
                baseValueKes=1.0,urgencyHalfLifeHours=1.0,estimatedScreenSeconds=30)
            queue.offer(item);queue.requireReview(item,"Delivery unproven")
            queue.expireStale(System.currentTimeMillis()+72*3_600_000L)
            assertEquals(1,(queue.dashboard()["needsReview"] as List<*>).size)
            assertNull(queue.peekBest(System.currentTimeMillis()))
            queue.offer(item.copy(dedupeKey="next-customer"))
            assertEquals("next-customer",queue.peekBest(System.currentTimeMillis())!!.dedupeKey)
        }
    }

    @Test fun duePublishingPrecedesBrowsingButCustomerReplyWins() {
        WorkQueue(context).use { queue ->
            val base=WorkItem("social",Domain.TIKTOK,WorkKind.TIKTOK_COMMENT_REPLY,
                baseValueKes=99999.0,urgencyHalfLifeHours=1.0,estimatedScreenSeconds=30)
            queue.offer(base)
            queue.offer(base.copy(dedupeKey="due",kind=WorkKind.TIKTOK_POST_PUBLISH,baseValueKes=1.0))
            assertEquals("due",queue.peekBest(System.currentTimeMillis())!!.dedupeKey)
            queue.offer(base.copy(dedupeKey="inbound",domain=Domain.WHATSAPP,kind=WorkKind.WA_REPLY_INBOUND,baseValueKes=0.1))
            assertEquals("inbound",queue.peekBest(System.currentTimeMillis())!!.dedupeKey)
        }
    }

    @Test fun brokenOutcomeReporterDoesNotPreventNextReporter(): Unit = runBlocking {
        var failures=0;var nextRan=false
        AmaraWorkLoop.isolateReporting({ throw IllegalStateException("analytics unavailable") }, { failures++ })
        AmaraWorkLoop.isolateReporting({ nextRan=true }, { failures++ })
        assertEquals(1,failures);assertTrue(nextRan)
        try {
            AmaraWorkLoop.isolateReporting({ throw kotlinx.coroutines.CancellationException("stop") }, { failures++ })
            throw AssertionError("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { assertEquals(1,failures) }
    }

    @Test fun taskDeadlinesBoundQueueOccupancy() {
        assertEquals(90_000L, AmaraWorkLoop.itemTimeoutMs(WorkKind.WA_REPLY_INBOUND))
        assertEquals(120_000L, AmaraWorkLoop.itemTimeoutMs(WorkKind.JUMIA_CAPTURE))
        assertTrue(WorkKind.entries.all { AmaraWorkLoop.itemTimeoutMs(it) in 1..240_000L })
    }

    @Test fun groupWorkCatchesUpDuringDayAndUsesOnlyAllowedTargets() = runBlocking {
        var enabled = true
        var targets = listOf("Naalya E-Trade")
        val source = co.sanaa.agent.core.work.sources.WhatsAppGroupSource({ enabled }, { targets }, { "2026-09-05" })
        val first = source.propose(snapshot(hour = 15)).single()
        assertEquals("Naalya E-Trade", first.payload.getString("group_target"))
        assertEquals(first.dedupeKey, source.propose(snapshot(hour = 19)).single().dedupeKey)
        assertTrue(source.propose(snapshot(quiet = true)).isEmpty())
        targets = emptyList()
        assertTrue(source.propose(snapshot()).isEmpty())
        targets = listOf("Naalya E-Trade")
        enabled = false
        assertTrue(source.propose(snapshot()).isEmpty())
    }

    @Test fun successfulRecoveryDoesNotReopenBreakerFromHistoricalFailureRate() {
        context.deleteDatabase("amara_safety.db")
        SafetyGovernor(context).use { governor ->
            governor.writableDatabase.execSQL("INSERT INTO kind_breakers(kind, attempts_total, failures_total, consecutive_failures) VALUES ('WA_REPLY_INBOUND', 10, 9, 2)")
            val item = WorkItem("recovered", Domain.WHATSAPP, WorkKind.WA_REPLY_INBOUND,
                baseValueKes = 1.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 0)
            governor.recordExecution(WorkResult(item, WorkStatus.DONE))
            assertFalse(governor.isKindBreakerOpen(item.kind))
            governor.recordExecution(WorkResult(item, WorkStatus.FAILED))
            assertTrue(governor.getKindBreakerCooldown(item.kind) in 1..120_000)
        }
    }

    @Test fun inboundRecoveryIsFastBoundedAndEndsWithOwnerAttention() {
        assertEquals(RecoveryDecision.Requeue(5_000), WorkExecutor.inboundRecovery(1, "chat unavailable"))
        assertEquals(RecoveryDecision.Requeue(20_000), WorkExecutor.inboundRecovery(2, "chat unavailable"))
        assertTrue(WorkExecutor.inboundRecovery(3, "chat unavailable") is RecoveryDecision.Escalate)
    }

    @Test fun customerRepliesRemainAllowedAfterDailyPromotionalCap() {
        context.deleteDatabase("amara_safety.db")
        SafetyGovernor(context).use { governor ->
            val reply = WorkItem("reply", Domain.WHATSAPP, WorkKind.WA_REPLY_INBOUND,
                baseValueKes = 1.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 0)
            repeat(40) { governor.recordExecution(WorkResult(reply.copy(dedupeKey = "reply-$it"), WorkStatus.DONE)) }
            assertTrue(governor.isAllowed(reply, 0, 0, snapshot()).allowed)
            assertFalse(governor.isAllowed(reply.copy(kind = WorkKind.WA_BROADCAST), 0, 0, snapshot()).allowed)
        }
    }

    @Test fun inboundCannotBeStarvedByFiftyHigherValueBackgroundItems() {
        WorkQueue(context).use { queue ->
            repeat(60) { queue.offer(WorkItem("background-$it", Domain.INTERNAL, WorkKind.INTERNAL_HEALTH_CHECK,
                baseValueKes = 100000.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 1)) }
            queue.offer(WorkItem("customer", Domain.WHATSAPP, WorkKind.WA_REPLY_INBOUND,
                baseValueKes = 1.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 30))
            assertEquals("customer", queue.peekBest(System.currentTimeMillis())!!.dedupeKey)
        }
    }

    @Test fun queueClaimsRequeuesAndCompletesOneDurableItem() {
        val queue = WorkQueue(context)
        val item = WorkItem("health-1", Domain.INTERNAL, WorkKind.INTERNAL_HEALTH_CHECK,
            baseValueKes = 30.0, urgencyHalfLifeHours = 6.0, estimatedScreenSeconds = 10)
        assertEquals(WorkQueue.OfferResult.ACCEPTED, queue.offer(item))
        assertNotNull(queue.peekBest(System.currentTimeMillis()))
        assertTrue(queue.markInFlight(item.dedupeKey))
        assertNull(queue.peekBest(System.currentTimeMillis()))
        queue.requeue(item.dedupeKey, 0, 1)
        assertEquals(1, queue.peekBest(System.currentTimeMillis())!!.attempt)
        queue.complete(item.dedupeKey)
        assertEquals(0, queue.pendingCount())
        assertEquals(WorkQueue.OfferResult.DEDUPED, queue.offer(item))
        assertNull(queue.peekBest(System.currentTimeMillis()))
    }

    @Test fun inboundWaitsThirtySecondsAndQueuesWakeAtItsDueTime() {
        WorkQueue(context).use { queue ->
            val now = System.currentTimeMillis()
            queue.offer(WorkItem("takeover-test", Domain.WHATSAPP, WorkKind.WA_REPLY_INBOUND,
                payload = org.json.JSONObject().put("owner_takeover_at", now + 30_000L),
                baseValueKes = 300.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 30))
            assertNull(queue.peekBest(now + 29_999L))
            assertEquals(10_000L, queue.nextWakeDelayMillis(now + 20_000L))
            assertNotNull(queue.peekBest(now + 30_000L))
        }
    }

    @Test fun selectedTikTokContentSurvivesDurableRetry() {
        WorkQueue(context).use { queue ->
            val item = WorkItem("post-binding", Domain.TIKTOK, WorkKind.TIKTOK_POST_PUBLISH,
                baseValueKes = 250.0, urgencyHalfLifeHours = 6.0, estimatedScreenSeconds = 120)
            queue.offer(item)
            val payload = org.json.JSONObject().put("listing_id", "product-1").put("caption", "Bound caption")
            assertFalse(queue.bindTikTokPayload(item.dedupeKey, payload))
            assertTrue(queue.markInFlight(item.dedupeKey))
            assertTrue(queue.bindTikTokPayload(item.dedupeKey, payload))
            queue.requeue(item.dedupeKey, 0, 1)
            assertEquals("Bound caption", queue.peekBest(System.currentTimeMillis())!!.payload.getString("caption"))
        }
    }

    @Test fun errorHistoryPersistsRepeatedFailuresWithoutCallingThemResolved() {
        LearningDatabase(context).use { db ->
            val learning = LearningLoop(context, db)
            repeat(2) { learning.recordAction("WA_REPLY_INBOUND", "WHATSAPP", false,
                error = "search_surface: unavailable", atMs = 1000L + it) }
            learning.recordAction("WA_REPLY_INBOUND", "WHATSAPP", true, atMs = 2000)
        }
        LearningDatabase(context).use { db ->
            val errors = LearningLoop(context, db).errorHistory()
            assertEquals(1, errors.size)
            assertEquals(2, errors.first()["count"])
            assertEquals(1001L, errors.first()["lastSeen"])
        }
    }

    @Test fun ownerCanCapOrDisableExistingFailureCooldownWithoutBypassingQuietHours() {
        context.deleteDatabase("amara_safety.db")
        var cap = 5
        SafetyGovernor(context, maxRetryCooldownMinutes = { cap }).use { governor ->
            val now = System.currentTimeMillis()
            governor.writableDatabase.execSQL(
                "INSERT INTO kind_breakers(kind,last_trip_at,cooldown_until) VALUES (?,?,?)",
                arrayOf<Any>(WorkKind.WA_REPLY_INBOUND.name, now, now + 24 * 3_600_000L),
            )
            assertTrue(governor.getKindBreakerCooldown(WorkKind.WA_REPLY_INBOUND) in 1..300_000)
            cap = 0
            assertFalse(governor.isKindBreakerOpen(WorkKind.WA_REPLY_INBOUND))
            val item = WorkItem("quiet-test", Domain.WHATSAPP, WorkKind.WA_REPLY_INBOUND,
                baseValueKes = 300.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 30,
                requires = setOf(Capability.CONSENT_TIER_2))
            assertFalse(governor.isAllowed(item, 0, 0, snapshot(quiet = true)).allowed)
        }
    }

    @Test fun inactiveForegroundCanYieldButFreshOwnerInteractionWins() {
        assertFalse(OwnerPresenceMonitor.screenIndicatesOwnerActive(true, false,
            foregroundPackage = "com.whatsapp", foregroundIdle = true))
        assertTrue(OwnerPresenceMonitor.screenIndicatesOwnerActive(true, false,
            recentHumanInteraction = true, foregroundPackage = "com.whatsapp", foregroundIdle = true))
    }

    @Test fun ownerCadenceRunsOutsideEveningWindowButHonorsQuietHours() = runBlocking {
        val source = TikTokWorkSource(enabled = { true }, intervalMinutes = { 30 })
        assertTrue(source.propose(snapshot(hour = 10)).any { it.kind == WorkKind.TIKTOK_POST_PUBLISH })
        assertFalse(source.propose(snapshot(hour = 10, quiet = true)).any { it.kind == WorkKind.TIKTOK_POST_PUBLISH })
    }

    @Test fun savedSokoTikTokCommandIsRecognizedWithoutMatchingUnrelatedRequests() {
        assertTrue(AutonomyController.isSokoTikTokPostCommand("Post a Soko product on TikTok"))
        assertTrue(AutonomyController.isSokoTikTokPostCommand("Promote Soko services on TikTok"))
        assertFalse(AutonomyController.isSokoTikTokPostCommand("Read my TikTok analytics"))
        assertFalse(AutonomyController.isSokoTikTokPostCommand("Post a WhatsApp status"))
    }

    @Test fun disablingAChannelRemovesOnlyItsPendingWork() {
        val queue = WorkQueue(context)
        val whatsapp = WorkItem(
            "wa:disable-test", Domain.WHATSAPP, WorkKind.WA_REPLY_INBOUND,
            baseValueKes = 30.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 10,
        )
        val internal = WorkItem(
            "internal:disable-test", Domain.INTERNAL, WorkKind.INTERNAL_HEALTH_CHECK,
            baseValueKes = 30.0, urgencyHalfLifeHours = 6.0, estimatedScreenSeconds = 10,
        )
        queue.offer(whatsapp)
        queue.offer(internal)

        assertEquals(1, queue.cancelPending(setOf(WorkKind.WA_REPLY_INBOUND)))
        assertTrue(queue.allPending().none { it.dedupeKey == whatsapp.dedupeKey })
        assertTrue(queue.allPending().any { it.dedupeKey == internal.dedupeKey })
    }

    @Test fun supervisedCanaryCannotSurviveAsRecurringAutonomousWork() {
        val queue = WorkQueue(context)
        queue.offer(WorkItem(
            "owner-canary", Domain.TIKTOK, WorkKind.TIKTOK_POST_PUBLISH,
            payload = org.json.JSONObject().put("owner_canary", true),
            baseValueKes = 100.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 120,
        ))
        assertEquals(1, queue.cancelPendingOwnerCanaries())
        queue.offer(WorkItem(
            "regular-post", Domain.TIKTOK, WorkKind.TIKTOK_POST_PUBLISH,
            baseValueKes = 100.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 120,
        ))
        assertEquals(listOf("regular-post"), queue.allPending().map { it.dedupeKey })
    }

    @Test fun periodicWorkKeepsOnlyTheNewestPendingOpportunity() {
        val queue = WorkQueue(context)
        repeat(4) { bucket ->
            queue.offer(WorkItem(
                "tiktok-post-$bucket", Domain.TIKTOK, WorkKind.TIKTOK_POST_PUBLISH,
                baseValueKes = 100.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 120,
            ))
        }
        assertEquals(listOf("tiktok-post-3"), queue.allPending().map { it.dedupeKey })
    }

    @Test fun backlogCompactionKeepsNewestInboundPerConversation() {
        val queue = WorkQueue(context)
        fun inbound(key: String, conversation: String, message: String) = WorkItem(
            key, Domain.WHATSAPP, WorkKind.WA_REPLY_INBOUND,
            payload = org.json.JSONObject().put("conversation", conversation).put("conversation_identity", "origin:$conversation").put("message", message),
            baseValueKes = 100.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 45,
        )
        queue.offer(inbound("a1", "Customer A", "first"))
        queue.offer(inbound("b1", "Customer B", "only"))
        queue.offer(inbound("a2", "Customer A", "latest"))

        // Simulate unsafe legacy rows produced before notification filtering existed.
        queue.writableDatabase.execSQL(
            "INSERT INTO work_items (dedupe_key, domain, kind, payload, base_value_kes, urgency_half_life_hours, estimated_screen_seconds, created_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')",
            arrayOf("legacy-transport", Domain.WHATSAPP.name, WorkKind.WA_REPLY_INBOUND.name,
                org.json.JSONObject().put("conversation", "WhatsApp").put("message", "Sending file").toString(),
                "100", "1", "45", System.currentTimeMillis().toString()),
        )
        queue.compactPendingBacklog()

        assertEquals(setOf("a2", "b1"), queue.allPending().map { it.dedupeKey }.toSet())
    }

    @Test fun lockedOrSleepingPhoneIsAvailableForAutonomousWork() {
        assertFalse(OwnerPresenceMonitor.screenIndicatesOwnerActive(interactive = false, deviceLocked = true))
        assertFalse(OwnerPresenceMonitor.screenIndicatesOwnerActive(interactive = true, deviceLocked = true))
        assertFalse(OwnerPresenceMonitor.screenIndicatesOwnerActive(
            interactive = true, deviceLocked = false, foregroundPackage = "co.sanaa.agent"))
        assertTrue(OwnerPresenceMonitor.screenIndicatesOwnerActive(
            interactive = true, deviceLocked = false, foregroundPackage = "com.whatsapp"))
        assertTrue(OwnerPresenceMonitor.screenIndicatesOwnerActive(
            interactive = true, deviceLocked = false, recentHumanInteraction = true,
            foregroundPackage = "co.sanaa.agent"))
    }

    @Test fun automationForegroundDoesNotMasqueradeAsOwnerButTouchStillWins() {
        assertFalse(OwnerPresenceMonitor.screenIndicatesOwnerActive(
            interactive = true, deviceLocked = false, foregroundPackage = "com.zhiliaoapp.musically",
            automationOwnsForeground = true))
        assertTrue(OwnerPresenceMonitor.screenIndicatesOwnerActive(
            interactive = true, deviceLocked = false, foregroundPackage = "com.zhiliaoapp.musically",
            automationOwnsForeground = true, recentHumanInteraction = true))
    }

    @Test fun alwaysOnInboundGetsNightBudgetButStillRespectsOwnerAndLowBattery() {
        val budgeter = PhoneTimeBudgeter(context)
        val item = WorkItem("night-reply", Domain.WHATSAPP, WorkKind.WA_REPLY_INBOUND,
            baseValueKes = 300.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 45,
            requires = setOf(Capability.SCREEN, Capability.CONSENT_TIER_2),
            payload = org.json.JSONObject().put("owner_always_on", true))
        assertNotNull(budgeter.requestSession(snapshot(hour = 23, quiet = true), item))
        assertNull(budgeter.requestSession(snapshot(hour = 23, ownerActive = true), item))
        assertNull(budgeter.requestSession(snapshot(hour = 23).copy(batteryPercent = 15), item))
        assertNotNull(budgeter.requestSession(snapshot(hour = 23), item.copy(payload = org.json.JSONObject())))
        assertNull(budgeter.requestSession(snapshot(hour = 23, quiet = true), item.copy(payload = org.json.JSONObject())))
    }

    @Test fun activeSessionStopsWhenPhoneBecomesHotOrLowOnBattery() {
        assertTrue(AmaraWorkLoop.shouldStopForDeviceHealth(snapshot().copy(thermalState = ThermalState.HOT)))
        val ownerCanary = WorkItem(
            "owner-canary", Domain.TIKTOK, WorkKind.TIKTOK_POST_PUBLISH,
            payload = org.json.JSONObject().put("owner_canary", true),
            baseValueKes = 100.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 120,
        )
        assertFalse(AmaraWorkLoop.shouldStopForDeviceHealth(snapshot().copy(thermalState = ThermalState.HOT), ownerCanary))
        val directOwnerCommand = ownerCanary.copy(
            dedupeKey = "owner-command",
            payload = org.json.JSONObject().put("owner_command", true),
        )
        assertFalse(AmaraWorkLoop.shouldStopForDeviceHealth(snapshot().copy(thermalState = ThermalState.HOT), directOwnerCommand))
        assertTrue(AmaraWorkLoop.shouldStopForDeviceHealth(snapshot().copy(batteryPercent = 20)))
        assertTrue(AmaraWorkLoop.shouldStopForDeviceHealth(snapshot().copy(batteryPercent = 20), ownerCanary))
        assertFalse(AmaraWorkLoop.shouldStopForDeviceHealth(snapshot().copy(batteryPercent = 21)))
    }

    @Test fun colorOsAggregateThermalNoiseDoesNotReplaceBatterySafety() {
        assertEquals(ThermalState.NORMAL, AmaraWorkLoop.batteryThermalState(355))
        assertEquals(ThermalState.WARM, AmaraWorkLoop.batteryThermalState(410))
        assertEquals(ThermalState.HOT, AmaraWorkLoop.batteryThermalState(450))
    }

    @Test fun failuresInOneKindDoNotDisableUnattemptedWhatsappReplies() {
        val governor = SafetyGovernor(context)
        repeat(10) { index ->
            val failed = WorkItem(
                "jiji-failure-$index", Domain.INTERNAL, WorkKind.JIJI_SCRAPE,
                baseValueKes = 100.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 30,
            )
            governor.recordExecution(WorkResult(
                failed, WorkStatus.FAILED,
                failure = FailureInfo(FailureClass.UI_MISMATCH, "fixture"),
            ))
        }
        val whatsapp = WorkItem(
            "wa-new", Domain.WHATSAPP, WorkKind.WA_REPLY_INBOUND,
            baseValueKes = 300.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 45,
        )
        assertTrue(governor.isAllowed(whatsapp, 0, 0, snapshot()).allowed)
    }

    @Test fun allDayAutopilotCanEnrollOnlyTrustedDirectInboundChats() {
        assertTrue(HumanConversationEngine.shouldEnrollTrustedInbound(true, true, false))
        assertFalse(HumanConversationEngine.shouldEnrollTrustedInbound(false, true, false))
        assertFalse(HumanConversationEngine.shouldEnrollTrustedInbound(true, false, false))
        assertFalse(HumanConversationEngine.shouldEnrollTrustedInbound(true, true, true))
    }

    @Test fun uiMismatchRecoveryIsBoundedAndEscalatesOnThirdAttempt() {
        assertTrue(WorkExecutor.recoveryFor(FailureClass.UI_MISMATCH, "selector drift", 1) is RecoveryDecision.Requeue)
        assertTrue(WorkExecutor.recoveryFor(FailureClass.UI_MISMATCH, "selector drift", 2) is RecoveryDecision.Requeue)
        val terminal = WorkExecutor.recoveryFor(FailureClass.UI_MISMATCH, "selector drift", 3)
        assertTrue(terminal is RecoveryDecision.Escalate)
        assertEquals("selector drift", (terminal as RecoveryDecision.Escalate).reason)
    }

    @Test fun routineSuggestionsRequireThreeDistinctDaysAndRemainReadOnly() {
        val learning = LearningLoop(context, LearningDatabase(context))
        val base = java.time.ZonedDateTime.now().withHour(10).withMinute(0).withSecond(0).withNano(0)
        repeat(3) { day ->
            learning.recordAction(
                actionType = "JIJI_SCRAPE",
                domain = "INTERNAL",
                success = true,
                screenSeconds = 45,
                atMs = base.minusDays(day.toLong()).toInstant().toEpochMilli(),
            )
            learning.recordAction(
                actionType = "TIKTOK_POST_PUBLISH",
                domain = "TIKTOK",
                success = true,
                atMs = base.minusDays(day.toLong()).toInstant().toEpochMilli(),
            )
        }

        val suggestions = learning.routineSuggestions()
        assertEquals(1, suggestions.size)
        assertEquals("JIJI_SCRAPE", suggestions.single().actionType)
        assertEquals(3, suggestions.single().evidenceCount)
        assertTrue(suggestions.none { it.actionType == "TIKTOK_POST_PUBLISH" })
    }

    @Test fun expiredLeaseCanBeRecoveredAndStaleWorkIsActuallyDeleted() {
        val queue = WorkQueue(context)
        val now = System.currentTimeMillis()
        val leased = WorkItem("lease", Domain.INTERNAL, WorkKind.INTERNAL_HEALTH_CHECK,
            baseValueKes = 30.0, urgencyHalfLifeHours = 6.0, estimatedScreenSeconds = 10)
        queue.offer(leased)
        assertTrue(queue.markInFlight(leased.dedupeKey, leaseMs = 1))
        queue.writableDatabase.execSQL("UPDATE work_items SET in_flight_until = ? WHERE dedupe_key = ?", arrayOf((now - 1).toString(), leased.dedupeKey))
        assertNotNull(queue.peekBest(now))

        val stale = WorkItem("stale", Domain.INTERNAL, WorkKind.INTERNAL_RECONCILIATION,
            baseValueKes = 100.0, urgencyHalfLifeHours = 24.0, estimatedScreenSeconds = 30,
            createdAt = now - 49L * 3_600_000L)
        queue.offer(stale)
        queue.writableDatabase.execSQL("UPDATE work_items SET created_at = ? WHERE dedupe_key = ?", arrayOf(stale.createdAt.toString(), stale.dedupeKey))
        assertEquals(1, queue.expireStale(now))
    }

    @Test fun followUpSourceHonorsSettingsAndProducesExecutorInput() = runBlocking {
        val chats = ChatStore(context)
        chats.updateSummary("Customer One", "Asked about the verified blue item.", "DISCOVERY")
        chats.writableDatabase.execSQL("UPDATE chat_summaries SET last_activity = 0 WHERE chat_key = ?", arrayOf("Customer One"))

        val disabled = WhatsAppFollowUpSource(chats, enabled = { false })
        assertTrue(disabled.propose(snapshot()).isEmpty())

        val enabled = WhatsAppFollowUpSource(chats, enabled = { true }, dormantDays = { 7 })
        val item = enabled.propose(snapshot()).single()
        assertEquals(WorkKind.WA_FOLLOWUP, item.kind)
        assertEquals("Customer One", item.payload.getString("target"))
        assertTrue(item.payload.getString("summary").isNotBlank())
        assertTrue(Capability.GROQ in item.requires)
    }

    @Test fun publishingAndBroadcastSourcesHonorOwnerSwitches() = runBlocking {
        assertTrue(TikTokWorkSource(enabled = { false }).propose(snapshot(hour = 18)).isEmpty())
        val tiktok = TikTokWorkSource(enabled = { true }, intervalMinutes = { 30 })
            .propose(snapshot(hour = 18))
        assertTrue(tiktok.any { it.kind == WorkKind.TIKTOK_POST_PUBLISH })
        val peakOnly = TikTokWorkSource(enabled = { true }, intervalMinutes = { 10 })
            .propose(snapshot(hour = 3, quiet = true))
        assertFalse(peakOnly.any { it.kind == WorkKind.TIKTOK_POST_PUBLISH })
        val ownerAlwaysOn = TikTokWorkSource(
            enabled = { true }, intervalMinutes = { 10 }, alwaysOn = { true }, commentsEnabled = { false },
        ).propose(snapshot(hour = 3))
        assertTrue(ownerAlwaysOn.any { it.kind == WorkKind.TIKTOK_POST_PUBLISH })
        assertTrue(ownerAlwaysOn.first { it.kind == WorkKind.TIKTOK_POST_PUBLISH }.payload.getBoolean("owner_always_on"))
        assertFalse(ownerAlwaysOn.any { it.kind == WorkKind.TIKTOK_COMMENT_REPLY })

        val nowHour = LocalTime.now().hour
        val disabled = InternalSource(morningBroadcastEnabled = { false }, broadcastTime = { "%02d:00".format(nowHour) })
        assertFalse(disabled.propose(snapshot(hour = nowHour)).any { it.kind == WorkKind.WA_BROADCAST })
        val enabled = InternalSource(morningBroadcastEnabled = { true }, broadcastTime = { "%02d:00".format(nowHour) })
        assertTrue(enabled.propose(snapshot(hour = nowHour)).any { it.kind == WorkKind.WA_BROADCAST })
    }

    @Test fun tiktokSelectionRandomizesFreshProductsAndKeepsARepeatGap() {
        fun listing(id: String, title: String) = co.sanaa.agent.api.SokoListing(
            id, title, "", 1_000, "", 1, 0, 1, "https://example.test/$id.jpg", org.json.JSONObject().put("slug", "product-$id"),
        )
        val products = listOf(listing("1", "One"), listing("2", "Two"), listing("3", "Three"), listing("4", "Four"))
        val fresh = WorkExecutor.selectTikTokListing(products, listOf("One", "Two")) { size -> size - 1 }
        assertEquals("Four", fresh?.title)

        val recycled = WorkExecutor.selectTikTokListing(products, listOf("One", "Two", "Three", "Four")) { 0 }
        assertTrue(recycled?.title != "One")
        assertNull(WorkExecutor.selectTikTokListing(products.map { it.copy(imageUrl = null) }, emptyList()))
    }

    @Test fun sokoAuditFindingsBecomeGroundedDeduplicatedWork() {
        val discovered = SokoWorkDiscovery.fromAlerts(listOf(
            SokoAlert("Low stock", "Receipt Paper", "Receipt Paper • Stock 1 • Reorder at 5"),
            SokoAlert("Order", "Order 20260903-42", "Customer One"),
            SokoAlert("Booking", "Logo design", "Customer Two"),
        ))

        assertEquals(listOf(WorkKind.SOKO_RESTOCK_DRAFT, WorkKind.SOKO_ORDER_CONFIRM), discovered.map { it.kind })
        assertEquals("Receipt Paper", discovered[0].payload.getString("product"))
        assertEquals("20260903-42", discovered[1].payload.getString("order_id"))
        assertTrue(discovered.map { it.dedupeKey }.distinct().size == discovered.size)
    }

    @Test fun repeatedOfferingSyncReplacesCachedRowInsteadOfDuplicatingIt() {
        val chats = ChatStore(context)
        chats.storeOffering("PRODUCT", "Receipt Paper", 10_000, null, 1, null, false, "old", "Soko Terminal")
        chats.storeOffering("PRODUCT", "receipt paper", 12_000, null, 1, null, false, "fresh", "Soko Terminal")

        val count = chats.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM offerings WHERE type='PRODUCT' AND name = ? COLLATE NOCASE",
            arrayOf("Receipt Paper"),
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        assertEquals(1, count)
        assertTrue(chats.getProducts().single().contains("12000"))
    }

    @Test fun jijiParserMatchesTheInstalledUgandaCardOrder() {
        val scraper = JijiScraper(context, MarketDatabase(context), AccessibilityActions(context))
        val screen = WhatsAppScreenSnapshot(
            packageName = JijiScraper.PACKAGE_JIJI,
            visibleText = listOf(
                "Found 6,505 ads",
                "ENTERPRISE",
                "USh 340,000",
                "Barcode Label Printer Xb 370",
                "Kampala, Central Division • Thermal Printer • Brand New",
                "Popular",
                "Yosiah Ofumbi",
            ),
            signature = "jiji-printers",
        )

        val listing = scraper.parseListingsFromScreen(screen, "Printers & Scanners").single()
        assertEquals("Barcode Label Printer Xb 370", listing.title)
        assertEquals(340_000, listing.priceUgx)
        assertEquals("Yosiah Ofumbi", listing.sellerName)
        assertTrue(listing.listingKey.startsWith("jiji:"))
    }
}
