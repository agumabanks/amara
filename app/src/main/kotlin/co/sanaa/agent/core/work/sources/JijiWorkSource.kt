package co.sanaa.agent.core.work.sources

import co.sanaa.agent.core.market.JijiScraper
import co.sanaa.agent.core.market.MarketAnalyzer
import co.sanaa.agent.core.market.JumiaScraper
import co.sanaa.agent.core.work.*

/**
 * JijiWorkSource — proposes market intelligence work.
 * Scrapes Jiji for competitive data when the device is idle.
 */
class JijiWorkSource(
    private val scraper: JijiScraper,
    private val analyzer: MarketAnalyzer,
    private val jumia: JumiaScraper? = null,
    private val enabled: () -> Boolean = { true },
    private val jumiaEnabled: () -> Boolean = { false },
    private val jumiaVisionAllowed: () -> Boolean = { false },
    private val intervalHours: () -> Int = { 4 },
) : WorkSource {

    override val domain: Domain = Domain.INTERNAL  // Market research is internal

    override suspend fun propose(snapshot: WorldSnapshot): List<WorkItem> {
        if (!snapshot.networkAvailable) return emptyList()
        if (snapshot.ownerActive) return emptyList()

        val items = mutableListOf<WorkItem>()
        val hour = snapshot.currentHour

        // Propose Jiji scrape every 4 hours during business hours
        if (enabled() && hour in 8..20 && scraper.isInstalled()) {
            val intervalMs = intervalHours().coerceIn(1, 24) * 3_600_000L
            val scrapeKey = "jiji-scrape-${System.currentTimeMillis() / intervalMs}"
            items.add(WorkItem(
                dedupeKey = scrapeKey,
                domain = Domain.INTERNAL,
                kind = WorkKind.JIJI_SCRAPE,
                payload = org.json.JSONObject().put("category", "Printers & Scanners"),
                // Keep the default discovery score above the loop's execution floor.
                // At 180 seconds and the conservative 0.5 cold-start success rate,
                // 150 UGX scored 25 and could therefore starve forever.
                baseValueKes = 200.0,
                urgencyHalfLifeHours = 8.0,
                estimatedScreenSeconds = 180,
                requires = setOf(Capability.SCREEN, Capability.NETWORK),
                riskTier = RiskTier.LOW,
            ))
        }

        if (jumiaEnabled() && jumiaVisionAllowed() && hour in 8..20 && jumia?.isInstalled() == true) {
            val intervalMs = intervalHours().coerceIn(1, 24) * 3_600_000L
            items.add(WorkItem(
                dedupeKey = "jumia-capture-${System.currentTimeMillis() / intervalMs}",
                domain = Domain.INTERNAL,
                kind = WorkKind.JUMIA_CAPTURE,
                payload = org.json.JSONObject().put("surface", "featured offers"),
                baseValueKes = 140.0,
                urgencyHalfLifeHours = 8.0,
                estimatedScreenSeconds = 90,
                requires = setOf(Capability.SCREEN, Capability.NETWORK, Capability.GROQ),
                riskTier = RiskTier.LOW,
            ))
        }

        // Propose market analysis once per day
        if ((enabled() || jumiaEnabled()) && hour in 8..20) {
            val analysisKey = "market-analysis-${System.currentTimeMillis() / 86400000}"
            items.add(WorkItem(
                dedupeKey = analysisKey,
                domain = Domain.INTERNAL,
                kind = WorkKind.MARKET_ANALYSIS,
                payload = org.json.JSONObject(),
                baseValueKes = 100.0,
                urgencyHalfLifeHours = 12.0,
                estimatedScreenSeconds = 60,
                requires = setOf(Capability.NETWORK),
                riskTier = RiskTier.LOW,
            ))
        }

        return items
    }
}
