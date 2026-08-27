package co.sanaa.agent.core.knowledge

import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.Redactor

/** Epistemic status of a stored claim (Complete-Employee Phase C). */
enum class ClaimKind { FACT, ASSUMPTION, INFERENCE, RECOMMENDATION, UNKNOWN }

/**
 * One material claim with mandatory provenance and freshness. Facts without a source
 * cannot exist; unknowns are recorded as unknowns instead of being guessed.
 */
data class SourcedClaim(
    val kind: ClaimKind,
    val statement: String,
    val sourceRef: String,
    val capturedAtMs: Long,
    val freshnessMs: Long,
) {
    init {
        require(sourceRef.isNotBlank() || kind == ClaimKind.UNKNOWN || kind == ClaimKind.ASSUMPTION) {
            "Facts, inferences and recommendations need a source"
        }
        require(freshnessMs > 0) { "Freshness window must be positive" }
    }

    fun isStale(nowMs: Long): Boolean = nowMs - capturedAtMs > freshnessMs
}

data class Conflict(val winner: SourcedClaim, val superseded: SourcedClaim)

/**
 * Durable business knowledge with explicit conflict resolution: newer verified evidence
 * supersedes older, but superseded claims are surfaced, never silently dropped.
 */
class KnowledgeBase {

    private val claims = mutableListOf<SourcedClaim>()
    private val conflicts = mutableListOf<Conflict>()

    fun add(claim: SourcedClaim): SourcedClaim {
        require(Redactor.containsSecretShape(claim.statement).not()) { "Secret-shaped statements never enter the knowledge base" }
        val sameSubject = claims.filter { it.statement.equals(claim.statement, ignoreCase = false) }
        sameSubject.firstOrNull { existing ->
            existing.kind == ClaimKind.FACT && claim.kind == ClaimKind.FACT && existing.capturedAtMs < claim.capturedAtMs
        }?.let { stale -> conflicts += Conflict(winner = claim, superseded = stale) }
        claims += claim
        return claim
    }

    fun all(): List<SourcedClaim> = claims.toList()
    fun recordedConflicts(): List<Conflict> = conflicts.toList()

    /** Claims usable for reporting right now; stale ones are excluded and reported separately. */
    fun freshClaims(nowMs: Long): Pair<List<SourcedClaim>, List<SourcedClaim>> =
        claims.partition { !it.isStale(nowMs) }

    fun digest(nowMs: Long): String {
        val (fresh, stale) = freshClaims(nowMs)
        return buildString {
            appendLine("FACTS AND FINDINGS (each with source and age):")
            fresh.forEach { c ->
                appendLine("- [${c.kind}] ${c.statement.take(200)} (source: ${c.sourceRef}, ageMs: ${nowMs - c.capturedAtMs})")
            }
            if (stale.isNotEmpty()) {
                appendLine("STALE (surfaced, not silently used): ${stale.size}")
                stale.forEach { c -> appendLine("- [stale] ${c.statement.take(120)}") }
            }
            if (conflicts.isNotEmpty()) {
                appendLine("CONFLICTS RESOLVED TOWARD NEWER EVIDENCE: ${conflicts.size}")
                conflicts.forEach { c -> appendLine("- newer wins: ${c.winner.statement.take(120)}") }
            }
        }.trimEnd()
    }

    fun fingerprint(): String = ContentHashing.hash(all().joinToString("|") { "${it.kind}:${it.statement}" })
}

/** Task-scoped retrieval view over a knowledge base: only authorized subjects leak out. */
class ScopedRetrieval(private val base: KnowledgeBase, private val allowedSubjects: Set<String>) {
    fun retrieve(subjectPrefixes: Set<String>, nowMs: Long): List<SourcedClaim> {
        require(subjectPrefixes.all { p -> allowedSubjects.any { p.startsWith(it) || it == "*" } }) {
            "Retrieval scope exceeds the contract's allowed subjects"
        }
        val (fresh, _) = base.freshClaims(nowMs)
        return fresh.filter { claim -> subjectPrefixes.any { claim.statement.lowercase().contains(it.lowercase()) } }
    }
}
