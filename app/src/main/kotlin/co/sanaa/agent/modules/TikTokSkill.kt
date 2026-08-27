package co.sanaa.agent.modules

import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.actions.WhatsAppScreenSnapshot
import co.sanaa.agent.api.BrainFailureFinalizer
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.api.ModelResponseException
import co.sanaa.agent.api.ModelSchemas
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.SecureConfig
import org.json.JSONObject

data class TikTokContent(
    val caption: String,
    val hashtags: List<String>,
    val sound: String?,
    val effects: List<String>,
)

data class TikTokAnalytics(
    val views: String,
    val likes: String,
    val comments: String,
    val shares: String,
    val followers: String,
)

class TikTokSkill(
    private val config: SecureConfig,
    private val actions: AccessibilityActions,
    private val memory: AmaraMemory,
    private val groq: GroqClient,
) {
    companion object {
        const val PACKAGE = "com.zhiliaoapp.musically"
        const val PACKAGE_GLOBAL = "com.ss.android.ugc.trill"
        const val NAME = "tiktok"
        const val MODULE_TAG = "tiktok-skill-"
    }

    fun isInstalled(): Boolean {
        return actions.packageNameForApp("tiktok") != null
    }

    suspend fun openTikTok(): Boolean {
        if (!isInstalled()) return false
        return actions.openTikTok()
    }

    suspend fun createPost(
        imageUrl: String?,
        caption: String,
        sound: String? = null,
        publish: Boolean = false,
    ): Boolean {
        if (!openTikTok()) return false
        if (actions.waitForForegroundPackage(PACKAGE, 10_000) == null &&
            actions.waitForForegroundPackage(PACKAGE_GLOBAL, 5_000) == null) return false
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.clickExactLabel("Create", "Post", "+")) return false
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (imageUrl != null) {
            if (!actions.clickExactLabel("Upload", "Select video", "Photos")) return false
            actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        }
        if (caption.isNotBlank()) {
            if (!actions.clickExactLabel("Caption", "Describe your video", "Say something")) return false
            actions.pause(co.sanaa.agent.core.InteractionKind.TYPE_SETTLE)
            actions.typeAndSendInCurrentChat(caption)
        }
        memory.recordAction("tiktok_draft", null, "TikTok", "Create TikTok draft", "Created a TikTok draft: ${caption.take(80)}", "Draft created in TikTok.", null, true)
        if (!publish) return true
        if (!actions.clickExactLabel("Post", "Publish", "Share")) return false
        actions.pause(co.sanaa.agent.core.InteractionKind.NETWORK_CONTENT)
        val verified = actions.currentWindowContains(caption.take(20)) || actions.currentWindowContains("posted")
        memory.recordAction("tiktok_publish", null, "TikTok", "Publish TikTok", "Published TikTok: ${caption.take(80)}", if (verified) "Post verified." else "Post submitted.", null, verified)
        return verified
    }

    suspend fun readAnalytics(): TikTokAnalytics? {
        if (!openTikTok()) return null
        if (actions.waitForForegroundPackage(PACKAGE, 10_000) == null &&
            actions.waitForForegroundPackage(PACKAGE_GLOBAL, 5_000) == null) return null
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.clickExactLabel("Profile", "Me")) return null
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.clickExactLabel("Menu", "Creator tools", "Settings and privacy")) return null
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.clickExactLabel("Analytics", "Creator tools", "View analytics")) return null
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        val screen = actions.snapshot()
        val items = screen.visibleText.filter { it.isNotBlank() }
        val analytics = TikTokAnalytics(
            views = items.firstOrNull { "view" in it.lowercase() } ?: "0",
            likes = items.firstOrNull { "like" in it.lowercase() } ?: "0",
            comments = items.firstOrNull { "comment" in it.lowercase() } ?: "0",
            shares = items.firstOrNull { "share" in it.lowercase() } ?: "0",
            followers = items.firstOrNull { "follower" in it.lowercase() } ?: "0",
        )
        memory.recordAction("tiktok_analytics", null, "TikTok", "Read TikTok analytics", "Views: ${analytics.views}, Likes: ${analytics.likes}, Comments: ${analytics.comments}", "Analytics read.", null, true)
        return analytics
    }

    suspend fun readComments(videoTitle: String): List<String> {
        if (!openTikTok()) return emptyList()
        if (actions.waitForForegroundPackage(PACKAGE, 10_000) == null &&
            actions.waitForForegroundPackage(PACKAGE_GLOBAL, 5_000) == null) return emptyList()
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.clickExactLabel("Inbox", "Notifications")) return emptyList()
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.clickExactLabel("Comments", "All comments")) return emptyList()
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        val screen = actions.snapshot()
        val comments = screen.visibleText.filter { it.isNotBlank() && !setOf("Back", "More", "Search", "Filter").contains(it.trim()) }
        memory.recordAction("tiktok_comments", null, "TikTok", "Read TikTok comments", "Read ${comments.size} comment items", "Comments read.", null, true)
        return comments
    }

    suspend fun replyToComment(comment: String, reply: String): Boolean {
        if (!openTikTok()) return false
        if (actions.waitForForegroundPackage(PACKAGE, 10_000) == null &&
            actions.waitForForegroundPackage(PACKAGE_GLOBAL, 5_000) == null) return false
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.clickExactLabel("Inbox", "Notifications")) return false
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.clickExactLabel("Comments", "All comments")) return false
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.clickLabel(comment.take(30))) return false
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.typeAndSendInCurrentChat(reply)) return false
        memory.recordAction("tiktok_reply", null, "TikTok", "Reply to TikTok comment", "Replied: $reply", "Reply sent.", null, true)
        return true
    }

    suspend fun readFeed(limit: Int = 10): List<String> {
        if (!openTikTok()) return emptyList()
        if (actions.waitForForegroundPackage(PACKAGE, 10_000) == null &&
            actions.waitForForegroundPackage(PACKAGE_GLOBAL, 5_000) == null) return emptyList()
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        val descriptions = mutableListOf<String>()
        var seen = setOf<String>()
        var scrolls = 0
        while (scrolls < limit) {
            val screen = actions.snapshot()
            val newItems = screen.visibleText.filter { it.isNotBlank() && it.length > 10 && it !in seen }
            if (newItems.isNotEmpty()) {
                seen = seen + newItems.toSet()
                descriptions.addAll(newItems)
            }
            if (!actions.scrollDown()) break
            actions.pause(co.sanaa.agent.core.InteractionKind.SCROLL_SETTLE)
            scrolls++
        }
        memory.recordAction("tiktok_feed", null, "TikTok", "Read TikTok feed", "Read ${descriptions.size} items from feed", "Feed read.", null, true)
        return descriptions
    }

    suspend fun searchContent(query: String): List<String> {
        if (!openTikTok()) return emptyList()
        if (actions.waitForForegroundPackage(PACKAGE, 10_000) == null &&
            actions.waitForForegroundPackage(PACKAGE_GLOBAL, 5_000) == null) return emptyList()
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.clickExactLabel("Search", "Discover", "Search TikTok")) return emptyList()
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.typeAndSendInCurrentChat(query)) return emptyList()
        actions.pause(co.sanaa.agent.core.InteractionKind.NETWORK_CONTENT)
        val screen = actions.snapshot()
        val results = screen.visibleText.filter { it.isNotBlank() && it.length > 5 && !setOf("Back", "Search", "Clear").contains(it.trim()) }
        memory.recordAction("tiktok_search", null, "TikTok", "Search TikTok: $query", "Found ${results.size} results", "Search complete.", null, true)
        return results
    }

    suspend fun readTrendingSounds(): List<String> {
        if (!openTikTok()) return emptyList()
        if (actions.waitForForegroundPackage(PACKAGE, 10_000) == null &&
            actions.waitForForegroundPackage(PACKAGE_GLOBAL, 5_000) == null) return emptyList()
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.clickExactLabel("Create", "Post", "+")) return emptyList()
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.clickExactLabel("Sounds", "Add sound", "Music")) return emptyList()
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        if (!actions.clickExactLabel("Trending", "Discover sounds")) return emptyList()
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        val screen = actions.snapshot()
        val sounds = screen.visibleText.filter { it.isNotBlank() && !setOf("Back", "Search", "Use sound", "Play").contains(it.trim()) }
        memory.recordAction("tiktok_sounds", null, "TikTok", "Read trending sounds", "Found ${sounds.size} trending sounds", "Sounds read.", null, true)
        return sounds
    }

    /**
     * Drafts a TikTok caption through the schema-gated model stage. The publish
     * decision is never model-authored: callers pass [publish] explicitly from an
     * owner command. Correlation id (contract §3) is deterministic per product so
     * retries of the same caption task share one durable failure chain.
     */
    suspend fun generateCaption(productName: String, productDescription: String): String {
        val correlationId = "$MODULE_TAG${ContentHashing.hash(productName).take(24)}"
        val prompt = """Write a short, engaging TikTok caption for this product.
            |Product: $productName
            |Description: $productDescription
            |Rules: under 100 characters, 1-2 emojis, end with a call to action, include 2-3 relevant hashtags, never say "seamless" or "leverage".
            |Return ONLY JSON: {"caption":""}""".trimMargin()
        return try {
            val caption = groq.completeJson(prompt, ModelSchemas.TIKTOK_CAPTION, correlationId)
                .optString("caption").trim()
            BrainFailureFinalizer.markRecovered(memory, correlationId, ModelSchemas.TIKTOK_CAPTION.name)
            caption
        } catch (error: ModelResponseException) {
            BrainFailureFinalizer.finalizeFailed(
                memory, correlationId, ModelSchemas.TIKTOK_CAPTION.name, error.kind, "TikTok caption draft",
            )
            ""
        } catch (precondition: IllegalStateException) {
            // Consent/key preconditions are not model failures; stop safely with no record.
            ""
        }
    }
}
