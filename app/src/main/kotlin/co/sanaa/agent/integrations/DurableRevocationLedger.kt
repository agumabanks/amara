package co.sanaa.agent.integrations

import co.sanaa.agent.core.AmaraMemory

/**
 * PRODUCTION durable connector revocation ledger over AmaraMemory's SQLite
 * `connector_revocations` table (Phase D). Revocation survives process restarts: a new
 * [ConnectorGrant] constructed over a freshly reopened [AmaraMemory] still refuses calls
 * for a revoked connector, and re-consent cannot resurrect the grant. Wired through
 * [co.sanaa.agent.core.AgentRuntime]; real-provider grants (CE-D-REAL-01) will receive
 * this ledger at construction.
 */
class DurableRevocationLedger(val memory: AmaraMemory) : RevocationLedger {
    override fun recordRevocation(connectorId: String) { memory.recordConnectorRevocation(connectorId) }
    override fun isRevoked(connectorId: String): Boolean = memory.isConnectorRevoked(connectorId)
}
