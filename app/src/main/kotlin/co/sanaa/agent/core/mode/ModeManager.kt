package co.sanaa.agent.core.mode

/**
 * AmaraMode — Behavioral modes for different work types.
 * Different personality overlay depending on task.
 */
enum class AmaraMode(val displayName: String, val description: String) {
    SALES(
        "Sales",
        "You are in sales mode. Goal: close deals. Be persuasive, urgent, warm. Every message should move toward a sale."
    ),
    SUPPORT(
        "Support",
        "You are in support mode. Goal: resolve issues. Be patient, empathetic, solution-focused. Never rush the customer."
    ),
    INVENTORY(
        "Inventory",
        "You are in inventory mode. Goal: accurate data. Be precise, methodical, verify everything. Numbers matter."
    ),
    CONTENT_CREATOR(
        "Content Creator",
        "You are in content mode. Goal: engaging posts. Be creative, trend-aware, authentic. Hook in first 3 seconds."
    ),
    LEARNING(
        "Learning",
        "You are in learning mode. Goal: understand. Be curious, ask questions, take notes. Document what you find."
    ),
    MARKET_RESEARCH(
        "Market Research",
        "You are in research mode. Goal: market intelligence. Be thorough, analytical, compare systematically. Find the edge."
    );

    /**
     * Get the mode-specific prompt overlay.
     */
    fun promptOverlay(): String = description

    companion object {
        /**
         * Detect mode from work kind.
         */
        fun forWorkKind(kind: String): AmaraMode = when (kind) {
            "WA_FOLLOWUP", "WA_REPLY_INBOUND", "WA_BROADCAST" -> SALES
            "TIKTOK_COMMENT_REPLY" -> SUPPORT
            "SOKO_AUDIT", "SOKO_INVENTORY_CHECK" -> INVENTORY
            "TIKTOK_POST_PUBLISH", "TIKTOK_ANALYTICS_CHECK" -> CONTENT_CREATOR
            "JIJI_SCRAPE", "MARKET_ANALYSIS" -> MARKET_RESEARCH
            "INTERNAL_RECONCILIATION" -> LEARNING
            else -> SALES
        }
    }
}

/**
 * ModeManager — Tracks current mode and provides mode-specific behavior.
 */
class ModeManager {
    @Volatile
    var currentMode: AmaraMode = AmaraMode.SALES
        private set

    fun switchTo(mode: AmaraMode) {
        currentMode = mode
    }

    fun forWork(kind: String): AmaraMode {
        val mode = AmaraMode.forWorkKind(kind)
        switchTo(mode)
        return mode
    }
}
