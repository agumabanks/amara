package co.sanaa.agent.core.work.sources

import co.sanaa.agent.core.ChatStore
import co.sanaa.agent.core.work.*

/**
 * WhatsAppFollowUpSource — proposes follow-up work for dormant conversations.
 * Reads from ChatStore to find chats that need re-engagement.
 */
class WhatsAppFollowUpSource(
    private val chatStore: ChatStore,
    private val enabled: () -> Boolean = { true },
    private val dormantDays: () -> Int = { 7 },
) : WorkSource {

    override val domain: Domain = Domain.WHATSAPP

    override suspend fun propose(snapshot: WorldSnapshot): List<WorkItem> {
        if (!enabled() || !snapshot.networkAvailable || !snapshot.canSendExternal) return emptyList()
        if (snapshot.ownerActive) return emptyList()

        val items = mutableListOf<WorkItem>()
        val dormantChats = chatStore.getDormantChats(dormantDays().coerceIn(1, 30))

        for (chatKey in dormantChats.take(3)) {
            val stage = chatStore.getStage(chatKey)
            if (stage == "CONFIRMED" || stage == "DORMANT") continue

            val summary = chatStore.getSummary(chatKey) ?: continue

            items.add(WorkItem(
                dedupeKey = "wa-followup:${co.sanaa.agent.core.ContentHashing.hash(chatKey)}:${co.sanaa.agent.core.ContentHashing.hash(summary)}",
                domain = Domain.WHATSAPP,
                kind = WorkKind.WA_FOLLOWUP,
                payload = org.json.JSONObject()
                    .put("target", chatKey)
                    .put("summary", summary)
                    .put("stage", stage),
                baseValueKes = WorkScorer.defaultBaseValue(WorkKind.WA_FOLLOWUP),
                urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.WA_FOLLOWUP),
                estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.WA_FOLLOWUP),
                requires = setOf(Capability.SCREEN, Capability.NETWORK, Capability.GROQ, Capability.CONSENT_TIER_2),
                riskTier = WorkScorer.defaultRiskTier(WorkKind.WA_FOLLOWUP),
            ))
        }

        return items
    }
}

/**
 * SokoWorkSource — proposes Soko Terminal audits and discovered work.
 * Proposes cheap audit items that, when executed, emit more specific work.
 */
class SokoWorkSource(private val enabled: () -> Boolean = { true }) : WorkSource {

    override val domain: Domain = Domain.SOKO

    override suspend fun propose(snapshot: WorldSnapshot): List<WorkItem> {
        if (!enabled() || !snapshot.networkAvailable) return emptyList()

        val items = mutableListOf<WorkItem>()

        // Propose an audit every 4 hours
        val lastAuditKey = "soko-audit-${System.currentTimeMillis() / (4 * 3600000)}"
        items.add(WorkItem(
            dedupeKey = lastAuditKey,
            domain = Domain.SOKO,
            kind = WorkKind.SOKO_AUDIT,
            payload = org.json.JSONObject(),
            baseValueKes = WorkScorer.defaultBaseValue(WorkKind.SOKO_AUDIT),
            urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.SOKO_AUDIT),
            estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.SOKO_AUDIT),
            requires = setOf(Capability.SCREEN, Capability.NETWORK),
            riskTier = WorkScorer.defaultRiskTier(WorkKind.SOKO_AUDIT),
        ))

        // Propose inventory check every 8 hours
        val inventoryCheckKey = "soko-inventory-${System.currentTimeMillis() / (8 * 3600000)}"
        items.add(WorkItem(
            dedupeKey = inventoryCheckKey,
            domain = Domain.SOKO,
            kind = WorkKind.SOKO_INVENTORY_CHECK,
            payload = org.json.JSONObject(),
            baseValueKes = WorkScorer.defaultBaseValue(WorkKind.SOKO_INVENTORY_CHECK),
            urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.SOKO_INVENTORY_CHECK),
            estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.SOKO_INVENTORY_CHECK),
            requires = setOf(Capability.SCREEN, Capability.NETWORK),
            riskTier = WorkScorer.defaultRiskTier(WorkKind.SOKO_INVENTORY_CHECK),
        ))

        return items
    }
}

/**
 * TikTokWorkSource — proposes TikTok comment replies and post publishing.
 */
class TikTokWorkSource(
    private val enabled: () -> Boolean = { false },
    private val intervalMinutes: () -> Long = { 240 },
    private val alwaysOn: () -> Boolean = { false },
    private val commentsEnabled: () -> Boolean = { true },
    private val nextPostAt: (() -> Long)? = null,
) : WorkSource {

    override val domain: Domain = Domain.TIKTOK

    override suspend fun propose(snapshot: WorldSnapshot): List<WorkItem> {
        if (!enabled() || !snapshot.networkAvailable || snapshot.ownerActive) return emptyList()

        val items = mutableListOf<WorkItem>()
        val hour = snapshot.currentHour

        // Bounded interaction/research pulse every 30 minutes during owner-enabled hours
        if (commentsEnabled() && (alwaysOn() || !snapshot.quietHours)) {
            val commentCheckKey = "tiktok-comments-${System.currentTimeMillis() / (30 * 60000)}"
            items.add(WorkItem(
                dedupeKey = commentCheckKey,
                domain = Domain.TIKTOK,
                kind = WorkKind.TIKTOK_COMMENT_REPLY,
                payload = org.json.JSONObject().put("owner_always_on", alwaysOn()),
                baseValueKes = WorkScorer.defaultBaseValue(WorkKind.TIKTOK_COMMENT_REPLY),
                urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.TIKTOK_COMMENT_REPLY),
                estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.TIKTOK_COMMENT_REPLY),
                requires = setOf(Capability.SCREEN, Capability.NETWORK),
                riskTier = WorkScorer.defaultRiskTier(WorkKind.TIKTOK_COMMENT_REPLY),
            ))
        }

        // The owner's selected cadence is honored all day only under the explicit
        // always-on switch; otherwise it is restricted to the peak window.
        if (alwaysOn() || !snapshot.quietHours) {
            val intervalMs = intervalMinutes().coerceIn(10, 480) * 60_000L
            val due = nextPostAt?.invoke()
            if (due != null && System.currentTimeMillis() < due) return items
            val postKey = due?.let { "tiktok-due-$it" }
                ?: "tiktok-post-${System.currentTimeMillis() / intervalMs}"
            items.add(WorkItem(
                dedupeKey = postKey,
                domain = Domain.TIKTOK,
                kind = WorkKind.TIKTOK_POST_PUBLISH,
                payload = org.json.JSONObject().put("owner_always_on", alwaysOn()),
                baseValueKes = WorkScorer.defaultBaseValue(WorkKind.TIKTOK_POST_PUBLISH),
                urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.TIKTOK_POST_PUBLISH),
                estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.TIKTOK_POST_PUBLISH),
                requires = setOf(Capability.SCREEN, Capability.NETWORK, Capability.GROQ, Capability.CONSENT_TIER_2),
                riskTier = WorkScorer.defaultRiskTier(WorkKind.TIKTOK_POST_PUBLISH),
            ))
        }

        return items
    }
}

/**
 * InternalSource — proposes internal maintenance tasks.
 * Reconciliation, briefing prep, ledger compaction, health checks.
 */
class InternalSource(
    private val morningBroadcastEnabled: () -> Boolean = { false },
    private val broadcastTime: () -> String = { "07:00" },
    private val configSyncEnabled: () -> Boolean = { false },
    private val memoryBackupEnabled: () -> Boolean = { false },
) : WorkSource {

    override val domain: Domain = Domain.INTERNAL

    override suspend fun propose(snapshot: WorldSnapshot): List<WorkItem> {
        val items = mutableListOf<WorkItem>()
        val hour = snapshot.currentHour

        if (snapshot.networkAvailable && (configSyncEnabled() || memoryBackupEnabled())) {
            val syncKey = "internal-config-sync-${System.currentTimeMillis() / (12 * 3_600_000L)}"
            items.add(WorkItem(
                dedupeKey = syncKey,
                domain = Domain.INTERNAL,
                kind = WorkKind.INTERNAL_CONFIG_SYNC,
                payload = org.json.JSONObject(),
                baseValueKes = WorkScorer.defaultBaseValue(WorkKind.INTERNAL_CONFIG_SYNC),
                urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.INTERNAL_CONFIG_SYNC),
                estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.INTERNAL_CONFIG_SYNC),
                requires = setOf(Capability.NETWORK),
                riskTier = WorkScorer.defaultRiskTier(WorkKind.INTERNAL_CONFIG_SYNC),
            ))
        }

        val broadcastHour = runCatching { java.time.LocalTime.parse(broadcastTime()).hour }.getOrDefault(7)
        if (morningBroadcastEnabled() && snapshot.canSendExternal && hour == broadcastHour) {
            items.add(WorkItem(
                dedupeKey = "morning-broadcast-${java.time.LocalDate.now()}",
                domain = Domain.WHATSAPP,
                kind = WorkKind.WA_BROADCAST,
                payload = org.json.JSONObject().put("run_module", true),
                baseValueKes = WorkScorer.defaultBaseValue(WorkKind.WA_BROADCAST),
                urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.WA_BROADCAST),
                estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.WA_BROADCAST),
                requires = setOf(Capability.SCREEN, Capability.NETWORK, Capability.GROQ, Capability.CONSENT_TIER_2),
                riskTier = WorkScorer.defaultRiskTier(WorkKind.WA_BROADCAST),
            ))
        }

        // Health check every 30 minutes
        val healthKey = "internal-health-${System.currentTimeMillis() / 1800000}"
        items.add(WorkItem(
            dedupeKey = healthKey,
            domain = Domain.INTERNAL,
            kind = WorkKind.INTERNAL_HEALTH_CHECK,
            payload = org.json.JSONObject(),
            baseValueKes = WorkScorer.defaultBaseValue(WorkKind.INTERNAL_HEALTH_CHECK),
            urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.INTERNAL_HEALTH_CHECK),
            estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.INTERNAL_HEALTH_CHECK),
            requires = emptySet(),
            riskTier = WorkScorer.defaultRiskTier(WorkKind.INTERNAL_HEALTH_CHECK),
        ))

        // Internal planning still enters the shared safety, queue, scoring, and learning path.
        val commercialKey = "commercial-cycle-${System.currentTimeMillis() / (4 * 3_600_000L)}"
        items.add(WorkItem(
            dedupeKey = commercialKey,
            domain = Domain.INTERNAL,
            kind = WorkKind.INTERNAL_COMMERCIAL_CYCLE,
            payload = org.json.JSONObject(),
            baseValueKes = WorkScorer.defaultBaseValue(WorkKind.INTERNAL_COMMERCIAL_CYCLE),
            urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.INTERNAL_COMMERCIAL_CYCLE),
            estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.INTERNAL_COMMERCIAL_CYCLE),
            requires = emptySet(),
            riskTier = WorkScorer.defaultRiskTier(WorkKind.INTERNAL_COMMERCIAL_CYCLE),
        ))

        // Reconciliation every 6 hours
        if (hour in listOf(0, 6, 12, 18)) {
            val reconKey = "internal-recon-${System.currentTimeMillis() / (6 * 3600000)}"
            items.add(WorkItem(
                dedupeKey = reconKey,
                domain = Domain.INTERNAL,
                kind = WorkKind.INTERNAL_RECONCILIATION,
                payload = org.json.JSONObject(),
                baseValueKes = WorkScorer.defaultBaseValue(WorkKind.INTERNAL_RECONCILIATION),
                urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.INTERNAL_RECONCILIATION),
                estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.INTERNAL_RECONCILIATION),
                requires = emptySet(),
                riskTier = WorkScorer.defaultRiskTier(WorkKind.INTERNAL_RECONCILIATION),
            ))
        }

        // Briefing prep every morning
        if (hour == broadcastHour) {
            val briefingKey = "internal-briefing-${System.currentTimeMillis() / 86400000}"
            items.add(WorkItem(
                dedupeKey = briefingKey,
                domain = Domain.INTERNAL,
                kind = WorkKind.INTERNAL_BRIEFING_PREP,
                payload = org.json.JSONObject(),
                baseValueKes = WorkScorer.defaultBaseValue(WorkKind.INTERNAL_BRIEFING_PREP),
                urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.INTERNAL_BRIEFING_PREP),
                estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.INTERNAL_BRIEFING_PREP),
                requires = emptySet(),
                riskTier = WorkScorer.defaultRiskTier(WorkKind.INTERNAL_BRIEFING_PREP),
            ))
        }

        return items
    }
}
