package co.sanaa.agent.actions

import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.core.SideEffectState
import co.sanaa.agent.core.SideEffectTransaction
import co.sanaa.agent.core.work.WorkQueue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class BoundMediaCleanupPolicy(
    private val root: File,
    private val memory: AmaraMemory,
    private val queue: WorkQueue,
) {
    private data class Candidate(
        val store: String,
        val file: File,
        val digest: String,
        val bytes: Long,
        val bindings: List<String>,
        val fingerprint: String,
    )

    private data class Snapshot(val stores: List<Map<String, Any>>, val candidates: List<Candidate>)

    private val approvals = mutableMapOf<String, Candidate>()
    private val storeNames = listOf("tiktok-bound-media", "tiktok-bound-video")
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val safeStates = setOf(SideEffectState.VERIFIED, SideEffectState.FAILED, SideEffectState.CANCELLED, SideEffectState.EXPIRED)
    // Proven non-publication outcomes: the attempt never dispatched an external
    // trigger, so its prepared media is not publication evidence. VERIFIED and
    // UNCERTAIN states are excluded; publication history stays protected.
    private val failureStates = setOf(SideEffectState.FAILED, SideEffectState.CANCELLED, SideEffectState.EXPIRED)
    private val terminalQueueStates = setOf("COMPLETED", "OWNER_CLOSED")
    private val queueStates = terminalQueueStates + setOf("PENDING", "IN_FLIGHT", "NEEDS_REVIEW")

    fun previewMediaCleanup(): Map<String, Any> = synchronized(BoundTikTokMedia) {
        approvals.clear()
        withReferences { transactions, rows ->
            val snapshot = snapshot(transactions, rows)
            val candidates = snapshot.candidates.map { candidate ->
                val id = UUID.randomUUID().toString()
                approvals[id] = candidate
                mapOf("id" to id, "store" to candidate.store, "bytes" to candidate.bytes)
            }
            mapOf("stores" to snapshot.stores, "candidates" to candidates,
                "totalReclaimableBytes" to snapshot.candidates.sumOf { it.bytes })
        }
    }

    fun confirmMediaCleanup(candidateIds: List<String>): Map<String, Any> = synchronized(BoundTikTokMedia) {
        require(candidateIds.size <= 1000 && candidateIds.all { it.isNotBlank() }) { "Invalid media cleanup selection" }
        val selected = candidateIds.distinct().mapNotNull { approvals[it] }
        approvals.clear()
        if (selected.isEmpty()) return@synchronized mapOf<String, Any>("reclaimedBytes" to 0L, "removedCount" to 0)
        withReferences { transactions, rows ->
            val current = snapshot(transactions, rows).candidates.associateBy { it.fingerprint }
            var reclaimedBytes = 0L
            var removedCount = 0
            for (approved in selected) {
                val candidate = current[approved.fingerprint] ?: continue
                if (BoundTikTokMedia.reclaim(candidate.file.parentFile!!, candidate.file, candidate.bindings, candidate.digest)) {
                    reclaimedBytes += candidate.bytes
                    removedCount++
                }
            }
            mapOf<String, Any>("reclaimedBytes" to reclaimedBytes, "removedCount" to removedCount)
        }
    }

    /**
     * Free provably-terminal publication evidence without waiting for owner
     * confirmation; publishing stays blocked for hours otherwise. Identical safety
     * proof as the owner-confirmed flow: a file leaves only when every binding maps
     * to a transaction in a terminal safe state and every referencing queue row is
     * closed. Reclaims oldest evidence first; the newest stays available for review.
     */
    fun autoReclaimSpace(directory: File, neededBytes: Long): Long = synchronized(BoundTikTokMedia) {
        require(directory.name in storeNames) { "Automatic reclaim only applies to TikTok media stores" }
        require(neededBytes in 1..BoundTikTokMedia.LIMIT_BYTES) { "Invalid reclaim request" }
        withReferences { transactions, rows ->
            val candidates = snapshot(transactions, rows).candidates
                .filter { it.store == directory.name }
                .sortedBy { it.file.lastModified() }
            var freed = 0L
            for (candidate in candidates) {
                if (freed >= neededBytes) break
                if (BoundTikTokMedia.reclaim(candidate.file.parentFile!!, candidate.file, candidate.bindings, candidate.digest)) {
                    freed += candidate.bytes
                }
            }
            freed
        }
    }

    private fun <T> withReferences(block: (List<SideEffectTransaction>, List<WorkQueue.MediaCleanupReference>) -> T): T =
        synchronized(memory) {
            val db = memory.writableDatabase
            db.beginTransaction()
            try {
                val transactions = memory.allSideEffectTransactions()
                // Media reclamation proof is validated per candidate in snapshot(); the ledger
                // legitimately stores heterogeneous rows (e.g. TargetRule.NONE with blank
                // target), so a single unusual row must not abort the whole preview.
                queue.withMediaCleanupReferences { rows ->
                    check(rows.all { it.status in queueStates }) { "Unknown media queue state" }
                    block(transactions, rows)
                }.also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
        }

    private fun snapshot(transactions: List<SideEffectTransaction>, rows: List<WorkQueue.MediaCleanupReference>): Snapshot {
        val transactionsByHash = transactions.groupBy { BoundTikTokMedia.sha256(it.idempotencyKey.toByteArray()) }
        val rowsByKey = rows.associateBy { it.key }
        val payloads = rows.associateWith { strings(JSONObject(it.payload)) }
        val candidates = mutableListOf<Candidate>()
        val stores = storeNames.map { name ->
            val directory = File(root.canonicalFile, name)
            val files = if (!directory.exists()) emptyList() else {
                check(directory.isDirectory && directory.canonicalFile == directory.absoluteFile) { "Invalid media store" }
                checkNotNull(directory.listFiles()) { "Cannot read media store" }.toList()
            }
            val usedBytes = files.sumOf { it.length() }
            val storeCandidates = runCatching {
                check(files.all { it.isFile && it.canonicalFile == it.absoluteFile })
                check(files.all { Regex("[a-f0-9]{64}\\.(binding|reclaimed|jpg|mp4)").matches(it.name) })
                val bindings = files.filter { it.extension == "binding" }.associate { file ->
                    check(file.length() == 64L)
                    val digest = file.readText(Charsets.US_ASCII)
                    check(hashPattern.matches(digest))
                    file.nameWithoutExtension to digest
                }
                files.filter { it.extension == "reclaimed" }.forEach { file ->
                    check(file.length() == 64L && file.readText(Charsets.US_ASCII) == bindings[file.nameWithoutExtension])
                }
                files.filter { it.extension in setOf("jpg", "mp4") }.mapNotNull { file ->
                    val digest = file.nameWithoutExtension
                    val hashes = bindings.filterValues { it == digest }.keys.sorted()
                    val bytes = file.length()
                    if (hashes.isEmpty() || bytes !in 1..40L * 1024 * 1024) return@mapNotNull null
                    val relatedTransactions = hashes.flatMap { transactionsByHash[it].orEmpty() }
                    if (hashes.any { transactionsByHash[it]?.size != 1 }) return@mapNotNull null
                    // Every binding must map to a proven-safe TikTok transaction. A
                    // VERIFIED or UNCERTAIN transaction keeps the caption+digest proof
                    // requirement; FAILED/CANCELLED/EXPIRED attempts never dispatched,
                    // so binding identity plus terminal owner closure suffices.
                    if (relatedTransactions.any { transaction ->
                        val expectedKind = when (transaction.capability) {
                            CapabilityIds.POST_TIKTOK -> "TIKTOK_POST_PUBLISH"
                            CapabilityIds.POST_TIKTOK_STORY -> "TIKTOK_STORY_PUBLISH"
                            else -> return@mapNotNull null
                        }
                        val row = rowsByKey[transaction.idempotencyKey]
                        if (transaction.state !in safeStates) return@mapNotNull null
                        if (transaction.state !in failureStates) {
                            val caption = row?.let { JSONObject(it.payload).opt("caption") as? String }
                            if (row == null || row.status !in terminalQueueStates ||
                                row.kind != expectedKind || caption.isNullOrBlank() ||
                                transaction.contentHash != co.sanaa.agent.core.ContentHashing.hash("$caption\nmedia-sha256:$digest") ||
                                transaction.target != (if (expectedKind == "TIKTOK_POST_PUBLISH") "tiktok" else "tiktok-story")) return@mapNotNull null
                        } else if (row != null && (row.status !in terminalQueueStates ||
                                row.kind != expectedKind ||
                                transaction.target != (if (expectedKind == "TIKTOK_POST_PUBLISH") "tiktok" else "tiktok-story"))) return@mapNotNull null
                        false
                    }) return@mapNotNull null
                    val keys = relatedTransactions.map { it.idempotencyKey }
                    val references = keys + hashes + digest
                    val relatedRows = rows.filter { row ->
                        row.key in keys || payloads.getValue(row).any { value -> references.any { value.contains(it) } }
                    }
                    if (relatedRows.any { it.status !in terminalQueueStates }) return@mapNotNull null
                    val referencedTransactions = relatedRows.map { row ->
                        transactions.singleOrNull { it.idempotencyKey == row.key } ?: return@mapNotNull null
                    }
                    if (referencedTransactions.any { it.state !in safeStates }) return@mapNotNull null
                    if (BoundTikTokMedia.sha256(file.readBytes()) != digest) return@mapNotNull null
                    val fingerprint = BoundTikTokMedia.sha256(listOf(name, file.name, bytes, file.lastModified(), hashes,
                        relatedTransactions, relatedRows, referencedTransactions).joinToString("\n").toByteArray())
                    Candidate(name, file, digest, bytes, hashes, fingerprint)
                }
            }.getOrDefault(emptyList())
            candidates.addAll(storeCandidates)
            mapOf("name" to name, "usedBytes" to usedBytes, "limitBytes" to BoundTikTokMedia.LIMIT_BYTES,
                "reclaimableBytes" to storeCandidates.sumOf { it.bytes })
        }
        return Snapshot(stores, candidates)
    }

    private fun strings(value: Any?): List<String> = when (value) {
        is JSONObject -> value.keys().asSequence().flatMap { strings(value.get(it)).asSequence() }.toList()
        is JSONArray -> (0 until value.length()).flatMap { strings(value.get(it)) }
        is String -> listOf(value)
        else -> emptyList()
    }
}
