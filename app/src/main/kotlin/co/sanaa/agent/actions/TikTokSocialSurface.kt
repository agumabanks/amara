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
    private fun nodes(id: String) = root()?.findAccessibilityNodeInfosByViewId("$PACKAGE:id/$id").orEmpty().filter { it.isVisibleToUser }
    private fun text(id: String) = nodes(id).map { it.text?.toString().orEmpty().trim() }.filter(String::isNotBlank).distinct()
    private fun tap(node: AccessibilityNodeInfo): Boolean {
        if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val bounds=Rect().also(node::getBoundsInScreen);val window=root()?.let { Rect().also(it::getBoundsInScreen) } ?: return false
        return !bounds.isEmpty && window.contains(bounds) && actions.tapByPosition(bounds.centerX(),bounds.centerY())
    }
    suspend fun profile(): JSONObject? {
        if(!actions.openTikTok() || actions.waitForForegroundPackage(PACKAGE)==null) return null
        repeat(3) { if(nodes("oeg").isEmpty()) { actions.globalBack();delay(400) } }
        if(!actions.clickExactLabel("Profile")) return null
        delay(1200)
        val handle=text("swb").singleOrNull()?.takeIf { it.startsWith("@") } ?: return null
        val result=JSONObject().put("handle",handle).put("display_name",text("su7").firstOrNull().orEmpty())
        for(label in nodes("suu")) {
            val name=label.text?.toString()?.lowercase() ?: continue
            if(name !in setOf("following","followers","likes")) continue
            val amount=label.parent?.findAccessibilityNodeInfosByViewId("$PACKAGE:id/suv")?.singleOrNull()?.text?.toString()
            amount?.let(TikTokSocialPolicy::parseCount)?.let { result.put(name,it) }
        }
        return result
    }
    suspend fun home(): Boolean {
        if(!actions.openTikTok() || actions.waitForForegroundPackage(PACKAGE)==null) return false
        repeat(4) { if(text("desc").isEmpty()) { if(!actions.clickExactLabel("Home")) actions.globalBack();delay(400) } }
        actions.clickExactLabel("For You");delay(700)
        return root()!=null
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
        val window=Rect().also(root()!!::getBoundsInScreen)
        val path=Path().apply { moveTo(window.width()*0.45f,window.height()*0.72f);lineTo(window.width()*0.45f,window.height()*0.28f) }
        val ok=AccessibilityAgentService.instance?.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,450)).build(),null,null)==true
        delay(900);return ok
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
                        val texts=container.findAccessibilityNodeInfosByViewId("$PACKAGE:id/f15").map { it.text?.toString().orEmpty() }.distinct()
                        val statuses=container.findAccessibilityNodeInfosByViewId("$PACKAGE:id/ekn").map { it.text?.toString().orEmpty() }
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
