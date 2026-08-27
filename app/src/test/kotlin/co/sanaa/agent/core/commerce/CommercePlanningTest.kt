package co.sanaa.agent.core.commerce

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.artifacts.ArtifactRubric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adaptive experimentation and the daily commercial loop: full experiment validation,
 * bounded ranked planning, anti-compensation guarantees, safe non-idle work, and the
 * rubric-enforced end-of-day brief.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommercePlanningTest {

    private lateinit var memory: AmaraMemory
    private lateinit var store: RevenueStore
    private lateinit var metrics: RevenueMetricEngine
    private lateinit var ownerPolicy: CommercialPolicy
    private lateinit var commerce: DailyCommercialPlanner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
        store = RevenueStore(memory)
        ownerPolicy = CommercialPolicy(
            allowedProducts = setOf("soko-listing-42"), approvedChannels = setOf("whatsapp"),
            permittedAudience = "qualified leads, consented only",
            ownerTimeZoneId = "Africa/Kampala",
        )
        metrics = RevenueMetricEngine(store, policy = { ownerPolicy })
        commerce = DailyCommercialPlanner(store, metrics, policy = { ownerPolicy })
    }

    private fun spec(
        dimension: ExperimentSpec.ChangeDimension = ExperimentSpec.ChangeDimension.TIMING,
        maxMessages: Int = 10,
        maxSpend: Long = 20_000,
    ) = ExperimentSpec(
        id = "exp-1", hypothesis = "Evening sends reply more often", baseline = "Morning sends, 8% reply rate",
        changeDimension = dimension, changeDescription = "Move send window to 19:00 local",
        approvedAudience = "qualified leads, consented only", approvedChannel = "whatsapp",
        maxMessagesPerDay = maxMessages, maxSpendUgx = maxSpend,
        startCondition = "next Monday 00:00", stopCondition = "7 days or safety breach",
        successMetric = "reply rate +3pp with p<0.2", safetyMetric = "block/opt-out rate stays under 1%",
        attributionWindowMs = 86_400_000L, minimumEvidenceCount = 20, rollbackRule = "revert to morning window immediately on opt-out spike",
    )

    // ---------- experiments ----------

    @Test fun completeExperimentWithinPolicyIsAccepted() {
        val accepted = ExperimentSpec.create(spec(), ExperimentSpec.OwnerPolicy(maxMessagesPerDay = 30, maxExperimentSpendUgx = 50_000))
        assertEquals(ExperimentSpec.ChangeDimension.TIMING, accepted.changeDimension)
    }

    @Test fun everyMandatedExperimentFieldIsRequired() {
        assertTrue(runCatching { spec().copy(hypothesis = " ") }.isFailure)
        assertTrue(runCatching { spec().copy(baseline = "") }.isFailure)
        assertTrue(runCatching { spec().copy(changeDescription = "") }.isFailure)
        assertTrue(runCatching { spec().copy(approvedAudience = "") }.isFailure)
        assertTrue(runCatching { spec().copy(approvedChannel = "") }.isFailure)
        assertTrue(runCatching { spec().copy(maxMessagesPerDay = 0) }.isFailure)
        assertTrue(runCatching { spec().copy(maxSpendUgx = -1) }.isFailure)
        assertTrue(runCatching { spec().copy(startCondition = "") }.isFailure)
        assertTrue(runCatching { spec().copy(stopCondition = "") }.isFailure)
        assertTrue(runCatching { spec().copy(successMetric = "") }.isFailure)
        assertTrue(runCatching { spec().copy(safetyMetric = "") }.isFailure)
        assertTrue(runCatching { spec().copy(attributionWindowMs = 0) }.isFailure)
        assertTrue(runCatching { spec().copy(minimumEvidenceCount = 0) }.isFailure)
        assertTrue(runCatching { spec().copy(rollbackRule = "") }.isFailure)
    }

    @Test fun capsBeyondOwnerPolicyAreRefused() {
        assertTrue(runCatching {
            ExperimentSpec.create(spec(maxMessages = 31), ExperimentSpec.OwnerPolicy(30, 50_000))
        }.isFailure)
        assertTrue(runCatching {
            ExperimentSpec.create(spec(maxSpend = 50_001), ExperimentSpec.OwnerPolicy(30, 50_000))
        }.isFailure)
    }

    @Test fun volumeAndDiscountCompensationCannotBeExpressedAsAnExperiment() {
        // The change-dimension vocabulary structurally excludes volume/discount/pricing.
        val dimensions = ExperimentSpec.ChangeDimension.entries.map { it.name }
        listOf("VOLUME", "DISCOUNT", "PRICING", "SPEND").forEach { banned ->
            assertFalse("dimension $banned must not exist", banned in dimensions)
        }
        assertEquals(7, dimensions.size)
    }

    // ---------- daily loop planner ----------

    private fun signal(
        contact: String,
        stage: String = FunnelStage.QUALIFIED_INQUIRY,
        value: Double = 300_000.0,
        confidence: Double = 0.6,
        urgency: Int = 2,
        awaiting: Boolean = false,
        lastTouch: Long = 0,
    ) = DailyCommercialPlanner.OpportunitySignal(
        contactKey = contact, productRef = "listing-$contact", stage = stage,
        expectedValueUgx = value, confidence = confidence, urgency = urgency,
        effortHours = 1.0, costUgx = 0, riskLevel = 1,
        lastTouchAtMs = lastTouch, awaitingOwnerReply = awaiting,
    )

    @Test fun rankingPicksHighestValueFirstAndStaysBounded() {
        val planner = DailyCommercialPlanner(store, metrics, policy = { ownerPolicy }, maxDailyExternalActions = 2)
        val plan = planner.plan(listOf(signal("a", value = 100_000.0), signal("b", value = 900_000.0), signal("c", value = 500_000.0)))
        val external = plan.ranked.filterIsInstance<DailyCommercialPlanner.PlannedAction.AuthorizedCandidate>()
        assertEquals("external actions bounded by daily budget", 2, external.size)
        assertEquals("b", external.first().target)
        assertEquals("c", external[1].target)
        assertFalse(external.any { it.draft.contains("still available", ignoreCase = true) })
        // The overflow opportunity becomes read-only preparation, never an extra send.
        assertTrue(plan.ranked.filterIsInstance<DailyCommercialPlanner.PlannedAction.ReadOnlyWork>()
            .any { it.label.contains("listing-a") })
    }

    @Test fun ownerAwaitingOpportunitiesBecomeDecisionRequestsNotSends() {
        val plan = DailyCommercialPlanner(store, metrics, policy = { ownerPolicy }).plan(listOf(signal("d", awaiting = true)))
        assertTrue(plan.ranked.filterIsInstance<DailyCommercialPlanner.PlannedAction.NeedsApproval>().isNotEmpty())
        assertTrue(plan.ranked.filterIsInstance<DailyCommercialPlanner.PlannedAction.AuthorizedCandidate>().isEmpty())
    }

    @Test fun safeIdleWorkAlwaysAvailableAndCharterAligned() {
        val idle = DailyCommercialPlanner(store, metrics, policy = { ownerPolicy }).safeIdleWork()
        val labels = idle.joinToString("|") { it.label.lowercase() }
        listOf("catalog audit", "lead prioritization", "follow-up", "reconciliation", "drafting",
            "experiment analysis", "artifact improvement", "decision brief").forEach { required ->
            assertTrue(labels.contains(required))
        }
    }

    @Test fun endOfDayBriefPassesTheArtifactRubricWithEvidenceBackedOutcomesOnly() {
        // With no durable events the brief must say the target is NOT met — never claim success.
        val brief = DailyCommercialPlanner(store, metrics, policy = { ownerPolicy }).endOfDayBrief(
            nowMs = System.currentTimeMillis(),
            observedFacts = listOf("Two stale listings found in catalog audit."),
            diagnosis = listOf("Price gap vs top competitor on listing-42."),
            executedOutcomes = emptyList(),
            costsAndAttribution = emptyList(),
            lessons = listOf("Audit cadence weekly is enough."),
            uncertainties = listOf("Competitor price source age unknown."),
            tomorrowAdjustments = listOf("Prepare price-change proposal for owner approval."),
        )
        val verdict = ArtifactRubric.evaluate(brief)
        assertTrue(verdict.failures.joinToString(), verdict.passed)
        val renderedSections = brief.sections.associate { it.heading to it.body }
        assertTrue(renderedSections.getValue("Verified Outcomes").contains("Daily target met: false"))
        assertTrue(renderedSections.getValue("Actions Taken").contains("read-only"))
    }

    @Test fun dailyStatusUsesOwnerDayBoundaryInsteadOfUtcOrDeviceBoundary() {
        // 20:30Z is Jan 1 in UTC but 23:30 Jan 1 in Kampala. At 22:00Z it is
        // already Jan 2 in Kampala, so the earlier inquiry must not count today.
        val inquiryAt = java.time.Instant.parse("2026-01-01T20:30:00Z").toEpochMilli()
        val now = java.time.Instant.parse("2026-01-01T22:00:00Z").toEpochMilli()
        assertTrue(metrics.recordQualifiedInquiry(
            channel = "whatsapp", contactKey = "customer-zone",
            contactKind = RevenueMetricEngine.ContactKind.CUSTOMER,
            productRef = "soko-listing-42", interactionId = "zone-1",
            messageText = "How much?", atMs = inquiryAt,
            evidenceKind = "whatsapp_chat", evidenceRef = "wa://zone-1", nowMs = now,
        ) is RevenueMetricEngine.Admission.Accepted)

        val status = commerce.dailyStatus(now)
        assertEquals(0, status.verifiedInquiries)
        assertEquals(
            java.time.Instant.parse("2026-01-01T21:00:00Z").toEpochMilli(),
            status.dayStartMs,
        )
        assertTrue(status.basis.contains("Africa/Kampala"))
    }
}
