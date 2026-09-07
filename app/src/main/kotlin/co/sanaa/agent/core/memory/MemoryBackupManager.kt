package co.sanaa.agent.core.memory

import co.sanaa.agent.api.BackendSync
import co.sanaa.agent.core.ChatStore
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.knowledge.LearningLoop
import org.json.JSONObject

/** Versioned, merge-only relationship/learning backup. Raw WhatsApp messages and secrets never leave the phone. */
class MemoryBackupManager(
    private val config: SecureConfig,
    private val backend: BackendSync,
    private val chats: ChatStore,
    private val learning: LearningLoop,
    private val social: co.sanaa.agent.core.social.TikTokSocialStore? = null,
) {
    suspend fun backupNow(): Result {
        if (!config.memoryBackupEnabled) return Result(false, "Cloud memory backup is off", 0)
        val snapshot = JSONObject()
            .put("schema_version", 1)
            .put("created_at", System.currentTimeMillis())
            .put("business_name", config.businessName)
            .put("relationship_memory", chats.exportMemory())
            .put("learning_memory", learning.exportMemory())
            .put("tiktok_social", social?.exportMemory())
        backend.saveMemorySnapshot(snapshot)
        config.lastMemoryBackupAt = System.currentTimeMillis()
        return Result(true, "Encrypted cloud snapshot saved", chats.summaryCount())
    }

    suspend fun restoreLatest(): Result {
        if (!config.memoryBackupEnabled) return Result(false, "Cloud memory backup is off", 0)
        val envelope = backend.latestMemorySnapshot()
        val snapshot = envelope.optJSONObject("snapshot") ?: return Result(false, "No backup exists yet", 0)
        if (snapshot.optInt("schema_version") != 1) return Result(false, "Unsupported backup version", 0)
        val chatCount = chats.importMemory(snapshot.optJSONObject("relationship_memory") ?: JSONObject())
        val learningCount = learning.importMemory(snapshot.optJSONObject("learning_memory") ?: JSONObject())
        val socialCount = social?.importMemory(snapshot.optJSONObject("tiktok_social") ?: JSONObject()) ?: 0
        return Result(true, "Memory merged from cards.sanaa.ug", chatCount + learningCount + socialCount)
    }

    data class Result(val success: Boolean, val message: String, val mergedItems: Int)
}
