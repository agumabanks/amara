package co.sanaa.agent.integrations

/**
 * PRODUCTION owner-facing connector grant registry. Materializes one durable
 * [ConnectorGrant] per declared spec, backed by AmaraMemory's revocation ledger, and is
 * the only place grants are created or revoked at runtime. Wired through
 * [co.sanaa.agent.core.AgentRuntime]; the owner UI reads grant state and can revoke or
 * re-consent through the MainActivity channels.
 */
class ConnectorGrants(
    private val specs: List<ConnectorSpec>,
    private val ledger: RevocationLedger,
) {

    private val grants = specs.associate { spec ->
        spec.id to ConnectorGrant(
            object : Connector {
                override val spec = spec
                // No real provider adapter exists yet (CE-D-REAL-01 stays provider-gated):
                // calls are denied honestly instead of being faked.
                override fun call(operation: String, requestJson: String, nowMs: Long): ConnectorResult =
                    ConnectorResult.Denied("No real provider adapter is configured for '${spec.id}'; owner credentials required.")
            },
            grantedScopes = spec.declaredScopes,
            revocationLedger = ledger,
        )
    }

    fun ids(): List<String> = grants.keys.toList()

    fun status(id: String): Map<String, Any?> = grants[id]?.let { grant ->
        mapOf(
            "id" to grant.id,
            "revoked" to grant.isRevoked,
            "scopes" to grant.grantedScopes.sorted(),
        )
    } ?: mapOf("id" to id, "revoked" to true, "scopes" to emptyList<String>(), "error" to "unknown connector")

    /** Owner-initiated revocation — sticky across restarts via the durable ledger. */
    fun revoke(id: String) {
        checkNotNull(grants[id]) { "Unknown connector '$id'" }.revoke()
    }

    /** Re-consent restores a grant ONLY when it was never durably revoked. */
    fun reconsent(id: String): Boolean =
        grants[id]?.reconsent() ?: false
}
