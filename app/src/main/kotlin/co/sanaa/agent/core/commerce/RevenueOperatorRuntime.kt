package co.sanaa.agent.core.commerce

import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.SideEffectState

/**
 * The ONE authoritative production revenue system (single-authority rule).
 *
 * Composes the canonical ledger and every revenue-operator component so each is
 * reachable from the AgentRuntime composition root — never constructed anywhere else,
 * never bypassed by a competing implementation:
 *
 *  - [RevenueStore]          durable typed ledger over AmaraMemory's revenue tables;
 *  - [RevenueMetricEngine]   the only admission path for inquiries/sales/costs/attribution;
 *  - owner policy            fail-closed document loaded from `commercial_policy`;
 *  - [OutreachGuard]         consent + suppression + honesty + caps gate;
 *  - [OpportunityRanker]     deterministic typed ranking with persisted explanations;
 *  - [ExperimentEngine]      bounded experiment lifecycle;
 *  - [DailyCommercialCycle]  timezone-aware morning/during-day/end-of-day loop;
 *  - [RevenueDashboard]      evidence-linked owner figures.
 *
 * Commercial health: degraded channels force read-only days via DailyCommercialCycle.
 */
class RevenueOperatorRuntime private constructor(
    val store: RevenueStore,
    val metrics: RevenueMetricEngine,
    val policy: () -> CommercialPolicy?,
    val outreachGuard: OutreachGuard,
    val ranker: OpportunityRanker,
    val experiments: ExperimentEngine,
    val cycle: DailyCommercialCycle,
    val dashboard: RevenueDashboard,
    val commercialPlanner: DailyCommercialPlanner,
    /** The ONLY authorized ingestion path from live business signals into the ledger. */
    val revenueIngestion: RevenueIngestion,
    private val uncertainSideEffects: () -> Int = { 0 },
    /** Fresh device/channel observations supplied by the phone boundary. */
    private val liveHealth: () -> DailyCommercialCycle.HealthSignals,
) {

    /** Fail-closed policy accessor used by components that cannot run without one. */
    fun policyOrConservativeDefault(): CommercialPolicy = policy() ?: CommercialPolicy()

    /**
     * Live commercial channel-health snapshot feeding the daily cycle. Unresolved
     * uncertain outcomes and active escalation stop-states degrade the channel and
     * force read-only days until the owner reconciles them.
     */
    fun healthSignals(): DailyCommercialCycle.HealthSignals {
        val observed = liveHealth()
        val uncertain = uncertainSideEffects() +
            store.commercialActions(state = "AWAITING_RECONCILIATION").size
        val escalations = store.suppressions().any { it.first.startsWith("__escalation__") }
        return DailyCommercialCycle.HealthSignals(
            deviceSessionHealthy = observed.deviceSessionHealthy,
            sokoReachable = observed.sokoReachable,
            whatsappChannelHealthy = observed.whatsappChannelHealthy && !escalations && uncertain == 0,
            blockers = buildList {
                addAll(observed.blockers)
                if (!observed.deviceSessionHealthy) add("live device-session health is unavailable or degraded")
                if (!observed.sokoReachable) add("live Soko reachability is unavailable or degraded")
                if (!observed.whatsappChannelHealthy) add("live WhatsApp channel health is unavailable or degraded")
                if (escalations) add("open escalation stop-state is active")
                if (uncertain > 0) add("$uncertain unresolved uncertain commercial outcomes")
            }.distinct(),
        )
    }

    companion object {
        fun create(
            memory: AmaraMemory,
            uncertainSideEffects: () -> Int = { 0 },
            liveHealth: () -> DailyCommercialCycle.HealthSignals = {
                DailyCommercialCycle.HealthSignals(
                    deviceSessionHealthy = false,
                    sokoReachable = false,
                    whatsappChannelHealthy = false,
                    blockers = listOf("live commercial channel health has not been supplied"),
                )
            },
        ): RevenueOperatorRuntime {
            val store = RevenueStore(memory)
            val policy: () -> CommercialPolicy? = { store.loadPolicy() }
            // Consent comes ONLY from the durable ledger; a nonblank string such as
            // "all" is never treated as authorization.
            val guard = OutreachGuard(
                store = store,
                policy = { policy() ?: CommercialPolicy() },
                consentLookup = { contactKey ->
                    val ledgerConsent = store.hasLiveConsent(
                        contactKey, productRef = null, channel = "whatsapp",
                        nowMs = System.currentTimeMillis(),
                    )
                    // A resolved durable identity must ALSO be revenue-eligible:
                    // owner/test contacts and suppressed consent never qualify even
                    // when a ledger row exists. Unresolved keys fall back to the
                    // ledger decision alone.
                    val directory = co.sanaa.agent.core.ContactDirectoryProvider.instance
                    val identityEligible: Boolean? = directory?.let {
                        val phone = co.sanaa.agent.core.Normalizer.normalizeUganda(contactKey)
                        when (val resolution = it.resolve(co.sanaa.agent.core.ContactQuery(
                            name = contactKey.trim().takeIf { _ -> phone == null && contactKey.trim().length >= 4 },
                            phone = phone,
                        ))) {
                            is co.sanaa.agent.core.Resolution.Unique -> resolution.entry.isRevenueEligible()
                            else -> null
                        }
                    }
                    ledgerConsent && identityEligible != false
                },
            )
            val metrics = RevenueMetricEngine(store, policy = { policy() ?: CommercialPolicy() })
            val ranker = OpportunityRanker(policy = { policy() ?: CommercialPolicy() })
            val experiments = ExperimentEngine(store, policy = { policy() ?: CommercialPolicy() })
            val cycle = DailyCommercialCycle(
                store = store, metrics = metrics, ranker = ranker,
                guard = guard, experiments = experiments,
                policy = { policy() ?: CommercialPolicy() },
            )
            val dashboard = RevenueDashboard(
                store = store, metrics = metrics, policy = policy,
                uncertainOutcomes = uncertainSideEffects,
            )
            val planner = DailyCommercialPlanner(
                store = store, metrics = metrics, policy = { policy() ?: CommercialPolicy() },
            )
            val ingestion = RevenueIngestion(store = store, metrics = metrics, guard = guard)
            return RevenueOperatorRuntime(
                store, metrics, policy, guard, ranker, experiments, cycle, dashboard, planner,
                revenueIngestion = ingestion,
                uncertainSideEffects = uncertainSideEffects,
                liveHealth = liveHealth,
            )
        }
    }
}
