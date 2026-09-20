package co.sanaa.agent.actions

import android.graphics.Rect
import android.graphics.Path
import android.accessibilityservice.GestureDescription
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import co.sanaa.agent.core.*
import co.sanaa.agent.core.social.TikTokSocialPolicy
import co.sanaa.agent.services.AccessibilityAgentService
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Version-observed controls, with exact creator/caption binding before every send. */
class TikTokSocialSurface(private val actions: AccessibilityActions) {
    companion object { const val PACKAGE = "com.zhiliaoapp.musically" }
    data class Post(val creator: String, val caption: String, val comments: List<String>) {
        val key get() = ContentHashing.hash("$creator\n$caption")
        fun json() = JSONObject().put("creator",creator).put("caption",Redactor.redactForExport(caption))
            .put("comments",org.json.JSONArray(comments.map(Redactor::redactForExport)))
    }
    private fun root() = AccessibilityAgentService.instance?.rootInActiveWindow?.takeIf { it.packageName?.toString()==PACKAGE }
    private fun nodes(id: String): List<AccessibilityNodeInfo> {
        val r=root() ?: return emptyList()
        return TikTokSocialControls.ids(id).flatMap { r.findAccessibilityNodeInfosByViewId("$PACKAGE:id/$it") }.filter { it.isVisibleToUser }.distinct()
    }
    private fun all(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n)+(0 until n.childCount).flatMap { n.getChild(it)?.let(::all).orEmpty() }
    private fun visible()=root()?.let(::all).orEmpty().filter { it.isVisibleToUser }
    private fun label(n: AccessibilityNodeInfo)=n.text?.toString().orEmpty().ifBlank { n.contentDescription?.toString().orEmpty() }.trim()
    private fun tapLabel(wanted: String): Boolean {
        val candidates=visible().filter { label(it).equals(wanted,true) && !Rect().also(it::getBoundsInScreen).isEmpty }
        return candidates.firstOrNull()?.let(::tap) ?: false
    }
    private fun text(id: String) = nodes(id).map { it.text?.toString().orEmpty().trim() }.filter(String::isNotBlank).distinct()
    private fun tap(node: AccessibilityNodeInfo): Boolean {
        if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val bounds=Rect().also(node::getBoundsInScreen);val window=root()?.let { Rect().also(it::getBoundsInScreen) } ?: return false
        return !bounds.isEmpty && window.contains(bounds) && actions.tapByPosition(bounds.centerX(),bounds.centerY())
    }
    suspend fun profile(): JSONObject? {
        if(!actions.openTikTok() || actions.waitForForegroundPackage(PACKAGE)==null) return null
        // Navigate by the visible tab; do not use an obfuscated ID to decide to press Back.
        for(attempt in 0 until 12) {
            if (co.sanaa.agent.core.social.TikTokProfileIdentity.handle(visible().map(::label)) != null) break
            val tab = visible().firstOrNull { label(it).equals("Profile", true) && !Rect().also(it::getBoundsInScreen).isEmpty }
            if (tab != null) {
                val bounds = Rect().also(tab::getBoundsInScreen)
                val window = root()?.let { Rect().also(it::getBoundsInScreen) }
                if (!bounds.isEmpty && window?.contains(bounds) == true &&
                    actions.tapByPosition(bounds.centerX(), bounds.centerY())) break
            }
            if (root() == null) {
                if (actions.snapshot().packageName.isNotBlank()) return null
                delay(400)
                continue
            }
            actions.globalBack();delay(400)
        }
        var profileNodes=emptyList<AccessibilityNodeInfo>()
        var handle: String? = null
        var display: String? = null
        for(wait in 0 until 40) {
            profileNodes=visible()
            handle=co.sanaa.agent.core.social.TikTokProfileIdentity.handle(profileNodes.map(::label))
            display=profileNodes.filter { it.viewIdResourceName in setOf("$PACKAGE:id/t0u","$PACKAGE:id/su7") }
                .map(::label).filter(String::isNotBlank).distinct().singleOrNull()
            if(handle != null) break
            // A dispatched gesture is not proof that navigation completed. Retry the
            // observed tab twice while waiting, without leaving the TikTok surface.
            if (wait in setOf(10, 25)) {
                val tab = visible().firstOrNull { label(it).equals("Profile", true) && !Rect().also(it::getBoundsInScreen).isEmpty }
                val bounds = tab?.let { Rect().also(it::getBoundsInScreen) }
                val window = root()?.let { Rect().also(it::getBoundsInScreen) }
                if (bounds != null && window?.contains(bounds) == true)
                    actions.tapByPosition(bounds.centerX(), bounds.centerY())
            }
            delay(400)
        }
        if(handle == null) {
            android.util.Log.w("SanaaAgentSocial", "Own profile incomplete: nodes=${profileNodes.size} edit=${profileNodes.any { label(it) in setOf("Edit","Edit profile") }} handle=${handle != null} display=${display != null}")
            return null
        }
        val result=JSONObject().put("handle",handle).put("display_name",display ?: handle.removePrefix("@"))
        for(stat in profileNodes.filter { label(it).lowercase() in setOf("following","followers","likes") }) {
            val values=stat.parent?.let(::all).orEmpty().map(::label).mapNotNull(TikTokSocialPolicy::parseCount).distinct()
            values.singleOrNull()?.let { result.put(label(stat).lowercase(),it) }
        }
        return result
    }
    suspend fun home(): Boolean {
        if(!actions.openTikTok() || actions.waitForForegroundPackage(PACKAGE)==null) return false
        for(attempt in 0 until 6) {
            if(text("desc").isNotEmpty() && nodes("user_avatar").isNotEmpty()) return true
            val tab=visible().firstOrNull { label(it).equals("Home",true) && !Rect().also(it::getBoundsInScreen).isEmpty }
            if(tab != null) {
                val bounds=Rect().also(tab::getBoundsInScreen)
                actions.tapByPosition(bounds.centerX(),bounds.centerY())
            } else actions.globalBack()
            delay(800)
        }
        return text("desc").isNotEmpty() && nodes("user_avatar").isNotEmpty()
    }
    suspend fun search(query: String): Boolean {
        if(query.isBlank() || query.length>80 || !home()) return false
        if(!actions.clickExactLabel("Search")) return false
        delay(600)
        if(root()==null || !actions.setFirstEditableField(query)) return false
        delay(300)
        fun editors(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            (if(node.isEditable && node.isVisibleToUser) listOf(node) else emptyList()) +
                (0 until node.childCount).flatMap { node.getChild(it)?.let(::editors).orEmpty() }
        val input=editors(root() ?: return false).singleOrNull { it.text?.toString()==query } ?: return false
        val searchButton=nodes("tv_search_textview").singleOrNull() ?: return false
        if(!tap(searchButton)) return false
        delay(1800)
        return root()!=null && text("tvl_unified_sug").isEmpty()
    }

    suspend fun discover(query: String): Boolean {
        if(!search(query)) return false
        for(attempt in 0 until 12) {
            val cards=nodes("desc").filter { it.text?.toString().orEmpty().length>=20 }
            if(cards.isNotEmpty()) {
                val candidate=cards.first()
                if(!tap(candidate)) return false
                for(wait in 0 until 24) {
                    if(root()==null) return false
                    if(text("desc").size==1 && (nodes("user_avatar").isNotEmpty() || nodes("eli").isNotEmpty())) return true
                    delay(500)
                }
                return false
            }
            delay(500)
        }
        return false
    }
    suspend fun readPost(): Post? {
        // Expanding the feed description exposes the full creator/post and comment sheet.
        if(text("efv").isEmpty()) nodes("desc").singleOrNull()?.let {
            tap(it)
            for(wait in 0 until 16) {
                if(root()==null || text("efv").isNotEmpty()) break
                delay(400)
            }
        }
        val caption=text("efv").singleOrNull()?.takeIf { it.length>=20 && it.length<=2200 } ?: return null
        val creator=text("user_name").singleOrNull() ?: return null
        return Post(creator,caption,text("f15").take(8).map { it.take(240) })
    }
    private fun samePost(post: Post): Boolean = text("user_name").singleOrNull()==post.creator && text("efv").singleOrNull()==post.caption
    suspend fun next(): Boolean {
        if(root()==null) return false
        if(nodes("ywb").isNotEmpty()) { tap(nodes("ywb").first());delay(400) }
        if(root()==null || text("desc").isEmpty()) return false
        return actions.swipeUpAndConfirm(PACKAGE)
    }
    @RequiresTransaction(reason = "public TikTok comment")
    suspend fun comment(post: Post, response: String): Boolean {
        if(!samePost(post) || text("f15").any { ContentHashing.normalize(it)==ContentHashing.normalize(response) }) return false
        val entry=nodes("l8h").singleOrNull() ?: return false
        if(!tap(entry)) return false
        delay(500)
        val editor=nodes("eg4").singleOrNull()?.takeIf { it.isEditable } ?: return false
        if(!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,response) })) return false
        delay(400)
        if(!samePost(post) || nodes("eg4").singleOrNull()?.text?.toString()!=response) return false
        val send=nodes("cz_").singleOrNull()?.takeIf { it.isEnabled } ?: return false
        return tap(send)
    }
    suspend fun verify(post: Post,response: String,ownName: String): VerificationEvidence {
        repeat(12) {
            // Send can dismiss or rebuild the expanded-caption sheet. Restore only
            // the current video's caption surface and re-prove exact identity.
            if(!samePost(post)) {
                if(root()==null) return VerificationEvidence.impossible("TikTok foreground lost",PACKAGE)
                if(nodes("eg4").isNotEmpty()) { actions.globalBack();delay(350) }
                val reopened=readPost()
                if(reopened!=null && reopened.key!=post.key) return VerificationEvidence.impossible("TikTok post identity changed",PACKAGE)
                if(!samePost(post)) { delay(500); return@repeat }
            }
            for(comment in nodes("f15").filter { ContentHashing.normalize(it.text?.toString().orEmpty())==ContentHashing.normalize(response) }) {
                var row=comment.parent
                repeat(4) {
                    val container=row
                    if(container!=null) {
                        val authors=container.findAccessibilityNodeInfosByViewId("$PACKAGE:id/title").map { it.text?.toString().orEmpty() }.distinct()
                        val texts=TikTokSocialControls.ids("f15").flatMap { container.findAccessibilityNodeInfosByViewId("$PACKAGE:id/$it") }.map { it.text?.toString().orEmpty() }.distinct()
                        val statuses=TikTokSocialControls.ids("ekn").flatMap { container.findAccessibilityNodeInfosByViewId("$PACKAGE:id/$it") }.map { it.text?.toString().orEmpty() }
                        if(authors.size==1 && authors.single()==ownName && texts.size==1 && statuses.isNotEmpty() &&
                            statuses.none { it.contains("sending",true) || it.contains("failed",true) })
                            return VerificationEvidence(true,0.9,PACKAGE,"comment_visible",System.currentTimeMillis())
                    }
                    row=row?.parent
                }
            }
            delay(500)
        }
        actions.captureScreenshot("tiktok_comment_unverified_${post.key.take(12)}")
        return VerificationEvidence.impossible("Own exact comment was not proven on the bound post",PACKAGE)
    }
}
