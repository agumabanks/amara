package co.sanaa.agent.core.work

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Scores work items for priority ordering.
 * 
 * Score = (evKes × decay × deadlineBoost × domainStarvation × riskPenalty × todFactor) / effortMin
 */
object WorkScorer {

    /**
     * Score a work item given current context.
     */
    fun score(item: WorkItem, ctx: ScoringContext): Double {
        val ageHours = (ctx.now - item.createdAt) / 3_600_000.0
        val decay = 2.0.pow(-ageHours / item.urgencyHalfLifeHours)
        val pSuccess = ctx.successRate(item.kind)
        val evKes = item.baseValueKes * pSuccess
        val effortMin = max(0.5, item.estimatedScreenSeconds / 60.0)
        val deadlineBoost = item.deadline?.let {
            val hrsLeft = (it - ctx.now) / 3_600_000.0
            if (hrsLeft <= 0) 0.0 else 1.0 + (6.0 / max(hrsLeft, 1.0))
        } ?: 1.0
        val domainStarvation = 1.0 + 0.1 * ctx.hoursSinceDomainTouched(item.domain)
        val riskPenalty = when (item.riskTier) {
            RiskTier.LOW -> 1.0; RiskTier.MEDIUM -> 0.7; RiskTier.HIGH -> 0.4
        }
        val todFactor = ctx.todFactor(item.kind)
        return (evKes * decay * deadlineBoost * domainStarvation * riskPenalty * todFactor) / effortMin
    }

    /**
     * Default base values per kind (UGX).
     */
    fun defaultBaseValue(kind: WorkKind): Double = when (kind) {
        WorkKind.OWNER_SCHEDULED_COMMAND -> 500.0
        WorkKind.WA_FOLLOWUP -> 150.0
        WorkKind.WA_REPLY_INBOUND -> 300.0
        WorkKind.WA_BROADCAST -> 200.0
        WorkKind.SOKO_AUDIT -> 200.0
        WorkKind.SOKO_ORDER_CONFIRM -> 800.0
        WorkKind.SOKO_RESTOCK_DRAFT -> 400.0
        WorkKind.SOKO_PRICE_ADJUST -> 350.0
        WorkKind.SOKO_INVENTORY_CHECK -> 200.0
        WorkKind.TIKTOK_COMMENT_REPLY -> 40.0
        WorkKind.TIKTOK_POST_PUBLISH -> 250.0
        WorkKind.TIKTOK_ANALYTICS_CHECK -> 60.0
        WorkKind.JIJI_SCRAPE -> 200.0
        WorkKind.JUMIA_CAPTURE -> 140.0
        WorkKind.MARKET_ANALYSIS -> 100.0
        WorkKind.INTERNAL_RECONCILIATION -> 100.0
        WorkKind.INTERNAL_COMMERCIAL_CYCLE -> 180.0
        WorkKind.INTERNAL_BRIEFING_PREP -> 80.0
        WorkKind.INTERNAL_LEDGER_COMPACTION -> 50.0
        WorkKind.INTERNAL_HEALTH_CHECK -> 30.0
        WorkKind.INTERNAL_CONFIG_SYNC -> 45.0
    }

    /**
     * Default urgency half-life in hours.
     */
    fun defaultHalfLife(kind: WorkKind): Double = when (kind) {
        WorkKind.OWNER_SCHEDULED_COMMAND -> 2.0
        WorkKind.WA_FOLLOWUP -> 12.0
        WorkKind.WA_REPLY_INBOUND -> 1.0
        WorkKind.WA_BROADCAST -> 4.0
        WorkKind.SOKO_AUDIT -> 8.0
        WorkKind.SOKO_ORDER_CONFIRM -> 2.0
        WorkKind.SOKO_RESTOCK_DRAFT -> 24.0
        WorkKind.SOKO_PRICE_ADJUST -> 6.0
        WorkKind.SOKO_INVENTORY_CHECK -> 12.0
        WorkKind.TIKTOK_COMMENT_REPLY -> 4.0
        WorkKind.TIKTOK_POST_PUBLISH -> 6.0
        WorkKind.TIKTOK_ANALYTICS_CHECK -> 8.0
        WorkKind.JIJI_SCRAPE -> 8.0
        WorkKind.JUMIA_CAPTURE -> 8.0
        WorkKind.MARKET_ANALYSIS -> 12.0
        WorkKind.INTERNAL_RECONCILIATION -> 24.0
        WorkKind.INTERNAL_COMMERCIAL_CYCLE -> 8.0
        WorkKind.INTERNAL_BRIEFING_PREP -> 12.0
        WorkKind.INTERNAL_LEDGER_COMPACTION -> 48.0
        WorkKind.INTERNAL_HEALTH_CHECK -> 6.0
        WorkKind.INTERNAL_CONFIG_SYNC -> 12.0
    }

    /**
     * Default estimated screen time in seconds.
     */
    fun defaultScreenSeconds(kind: WorkKind): Int = when (kind) {
        WorkKind.OWNER_SCHEDULED_COMMAND -> 120
        WorkKind.WA_FOLLOWUP -> 40
        WorkKind.WA_REPLY_INBOUND -> 45
        WorkKind.WA_BROADCAST -> 60
        WorkKind.SOKO_AUDIT -> 180
        WorkKind.SOKO_ORDER_CONFIRM -> 60
        WorkKind.SOKO_RESTOCK_DRAFT -> 90
        WorkKind.SOKO_PRICE_ADJUST -> 75
        WorkKind.SOKO_INVENTORY_CHECK -> 120
        WorkKind.TIKTOK_COMMENT_REPLY -> 30
        WorkKind.TIKTOK_POST_PUBLISH -> 120
        WorkKind.TIKTOK_ANALYTICS_CHECK -> 60
        WorkKind.JIJI_SCRAPE -> 180
        WorkKind.JUMIA_CAPTURE -> 90
        WorkKind.MARKET_ANALYSIS -> 60
        WorkKind.INTERNAL_RECONCILIATION -> 30
        WorkKind.INTERNAL_COMMERCIAL_CYCLE -> 0
        WorkKind.INTERNAL_BRIEFING_PREP -> 20
        WorkKind.INTERNAL_LEDGER_COMPACTION -> 15
        WorkKind.INTERNAL_HEALTH_CHECK -> 10
        WorkKind.INTERNAL_CONFIG_SYNC -> 0
    }

    /**
     * Default risk tier per kind.
     */
    fun defaultRiskTier(kind: WorkKind): RiskTier = when (kind) {
        WorkKind.OWNER_SCHEDULED_COMMAND -> RiskTier.MEDIUM
        WorkKind.WA_FOLLOWUP -> RiskTier.MEDIUM
        WorkKind.WA_REPLY_INBOUND -> RiskTier.MEDIUM
        WorkKind.WA_BROADCAST -> RiskTier.MEDIUM
        WorkKind.SOKO_AUDIT -> RiskTier.LOW
        WorkKind.SOKO_ORDER_CONFIRM -> RiskTier.LOW
        WorkKind.SOKO_RESTOCK_DRAFT -> RiskTier.LOW
        WorkKind.SOKO_PRICE_ADJUST -> RiskTier.MEDIUM
        WorkKind.SOKO_INVENTORY_CHECK -> RiskTier.LOW
        WorkKind.TIKTOK_COMMENT_REPLY -> RiskTier.LOW
        WorkKind.TIKTOK_POST_PUBLISH -> RiskTier.MEDIUM
        WorkKind.TIKTOK_ANALYTICS_CHECK -> RiskTier.LOW
        WorkKind.JIJI_SCRAPE -> RiskTier.LOW
        WorkKind.JUMIA_CAPTURE -> RiskTier.LOW
        WorkKind.MARKET_ANALYSIS -> RiskTier.LOW
        WorkKind.INTERNAL_RECONCILIATION -> RiskTier.LOW
        WorkKind.INTERNAL_COMMERCIAL_CYCLE -> RiskTier.LOW
        WorkKind.INTERNAL_BRIEFING_PREP -> RiskTier.LOW
        WorkKind.INTERNAL_LEDGER_COMPACTION -> RiskTier.LOW
        WorkKind.INTERNAL_HEALTH_CHECK -> RiskTier.LOW
        WorkKind.INTERNAL_CONFIG_SYNC -> RiskTier.LOW
    }
}

/**
 * Context for scoring work items.
 */
data class ScoringContext(
    val now: Long,
    val successRate: (WorkKind) -> Double,
    val hoursSinceDomainTouched: (Domain) -> Double,
    val todFactor: (WorkKind) -> Double,
)
