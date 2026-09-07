package co.sanaa.agent.core.work

import org.json.JSONObject

/**
 * Domain categories for Amara's autonomous work.
 */
enum class Domain { SOKO, TIKTOK, WHATSAPP, INTERNAL }

/**
 * Types of work items that Amara can perform.
 */
enum class WorkKind {
    // WhatsApp
    WA_FOLLOWUP, WA_REPLY_INBOUND, WA_BROADCAST,
    // Soko
    SOKO_AUDIT, SOKO_ORDER_CONFIRM, SOKO_RESTOCK_DRAFT, SOKO_PRICE_ADJUST, SOKO_INVENTORY_CHECK,
    // TikTok
    TIKTOK_COMMENT_REPLY, TIKTOK_POST_PUBLISH, TIKTOK_ANALYTICS_CHECK,
    // Jiji / Market
    JIJI_SCRAPE, JUMIA_CAPTURE, MARKET_ANALYSIS,
    // Internal
    OWNER_SCHEDULED_COMMAND, INTERNAL_RECONCILIATION, INTERNAL_COMMERCIAL_CYCLE, INTERNAL_BRIEFING_PREP,
    INTERNAL_LEDGER_COMPACTION, INTERNAL_HEALTH_CHECK, INTERNAL_CONFIG_SYNC
}

/**
 * Risk tier for a work item.
 */
enum class RiskTier { LOW, MEDIUM, HIGH }

/**
 * Capabilities required to execute a work item.
 */
enum class Capability { SCREEN, NETWORK, GROQ, CONSENT_TIER_2, CONSENT_TIER_3 }

/**
 * A unit of work that Amara can perform autonomously.
 */
data class WorkItem(
    val dedupeKey: String,
    val domain: Domain,
    val kind: WorkKind,
    val payload: JSONObject = JSONObject(),
    val baseValueKes: Double,
    val deadline: Long? = null, // epoch millis
    val urgencyHalfLifeHours: Double,
    val estimatedScreenSeconds: Int,
    val requires: Set<Capability> = emptySet(),
    val riskTier: RiskTier = RiskTier.LOW,
    val createdAt: Long = System.currentTimeMillis(),
    val attempt: Int = 0,
) {
    val isExpired: Boolean get() = deadline?.let { System.currentTimeMillis() > it } ?: false
}

/**
 * Result of executing a work item.
 */
data class WorkResult(
    val item: WorkItem,
    val status: WorkStatus,
    val discoveredWork: List<WorkItem> = emptyList(),
    val outcomeFacts: List<String> = emptyList(),
    val screenSecondsUsed: Int = 0,
    val failure: FailureInfo? = null,
)

/**
 * Status of a work item after execution.
 */
enum class WorkStatus { DONE, FAILED, SKIPPED, PARTIAL, ESCALATED }

/**
 * Information about a failure.
 */
data class FailureInfo(
    val klass: FailureClass,
    val summary: String,
    val recoverable: Boolean = true,
)

/**
 * Classification of failures for recovery decisions.
 */
enum class FailureClass { TRANSIENT_NETWORK, UI_MISMATCH, APP_STATE, PRECONDITION_GONE, POLICY_BLOCKED, UNKNOWN }

/**
 * Interface for domain modules that propose work to Amara's queue.
 * Implementations MUST be cheap (<100ms) — they should read local state only.
 */
interface WorkSource {
    val domain: Domain
    /** Propose work items based on current world state. Never opens an app. */
    suspend fun propose(snapshot: WorldSnapshot): List<WorkItem>
}

/**
 * Snapshot of current device and business state.
 */
data class WorldSnapshot(
    val ownerActive: Boolean,
    val screenOn: Boolean,
    val batteryPercent: Int,
    val thermalState: ThermalState,
    val networkAvailable: Boolean,
    val currentHour: Int, // 0-23 in owner timezone
    val quietHours: Boolean,
    val messagesSentToday: Int,
    val screenMinutesUsedToday: Int,
    val groqCallsToday: Int,
) {
    val canSendExternal: Boolean get() = networkAvailable && !ownerActive && !quietHours
}

/**
 * Thermal state of the device.
 */
enum class ThermalState { NORMAL, WARM, HOT }
