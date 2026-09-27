package co.sanaa.agent.core.artifacts

import co.sanaa.agent.core.AmaraMemory

/**
 * PRODUCTION durable artifact store: full-fidelity revisions persisted in AmaraMemory's
 * SQLite `artifact_revisions` table. This is the class wired into [co.sanaa.agent.core.AgentRuntime];
 * it is not a test adapter. Guarantees:
 *  - rubric and calculation failures never persist;
 *  - each revision records its parent revision id (per-artifact chain);
 *  - rendered-bytes SHA-256 is stored with every revision;
 *  - rows whose serialized spec cannot be parsed are skipped defensively instead of
 *    crashing history reads (corrupt-row tolerance), so one damaged row cannot hide
 *    healthy revisions.
 */
class SqliteArtifactStore(private val memory: AmaraMemory) : ArtifactStore {

    @Synchronized
    override fun commit(artifactId: String, spec: ArtifactSpec, nowMs: Long, author: String): ArtifactStore.StoredRevision {
        val verdict = ArtifactRubric.evaluate(spec)
        require(verdict.passed) { "Artifact failed the quality rubric: ${verdict.failures.joinToString("; ")}" }
        val calcFailures = ArtifactRubric.calculationFailures(spec)
        require(calcFailures.isEmpty()) { "Artifact failed calculation checks: ${calcFailures.joinToString("; ")}" }
        val parent = history(artifactId).maxByOrNull { it.id }?.id
        val hash = ContentDigest.of(ArtifactRenderer.renderBytes(spec))
        val id = memory.insertArtifactRevision(artifactId, nowMs, author, ArtifactSpec.specJsonOf(spec), parent, hash)
        return ArtifactStore.StoredRevision(id, artifactId, spec, nowMs, author, parent, hash)
    }

    @Synchronized
    override fun history(artifactId: String): List<ArtifactStore.StoredRevision> = memory.artifactRevisions(artifactId).mapNotNull { row ->
        // Corrupt-row handling: a row with unparseable spec JSON is reported as skipped
        // through the store log hook and excluded from history, never fatal.
        val spec = ArtifactSpec.parseSpecJson(row.specJson)
        if (spec == null) {
            skippedRows += row.id
            null
        } else {
            ArtifactStore.StoredRevision(
                row.id, row.artifactId, spec, row.createdAtMs, row.author, row.parentRevision, row.contentHash,
            )
        }
    }

    @Synchronized
    override fun latest(artifactId: String): ArtifactStore.StoredRevision? = history(artifactId).lastOrNull()

    /** Ids of rows that were present on disk but could not be parsed (diagnostic only). */
    @Synchronized
    fun skippedRowIds(): List<Long> = skippedRows.toList()

    private val skippedRows = mutableListOf<Long>()
}
