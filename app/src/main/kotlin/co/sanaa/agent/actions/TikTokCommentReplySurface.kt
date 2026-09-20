package co.sanaa.agent.actions

import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import co.sanaa.agent.core.*
import co.sanaa.agent.modules.TikTokCommentNotifications
import co.sanaa.agent.services.AccessibilityAgentService
import kotlinx.coroutines.delay

/** Refuses thread targeting unless the exact notification author/body share one visible comment row. */
class TikTokCommentReplySurface(private val actions: AccessibilityActions) {
    private fun root() = AccessibilityAgentService.instance?.rootInActiveWindow?.takeIf { it.packageName?.toString() in TikTokCommentNotifications.packages }
    private fun all(node:AccessibilityNodeInfo):List<AccessibilityNodeInfo> = listOf(node)+(0 until node.childCount).flatMap { node.getChild(it)?.let(::all).orEmpty() }
    private fun label(n:AccessibilityNodeInfo) = n.text?.toString().orEmpty().ifBlank { n.contentDescription?.toString().orEmpty() }.trim()
    fun row(author:String,body:String):AccessibilityNodeInfo? {
        val r=root() ?: return null
        val matches=all(r).filter { it.isVisibleToUser && label(it)==body }
        if(matches.size!=1) return null
        var parent=matches.single().parent
        repeat(4) {
            val container=parent ?: return null
            val children=all(container).filter { it.isVisibleToUser }
            if(children.count { label(it)==body }==1 && children.count { label(it).removePrefix("@")==author.removePrefix("@") }==1 &&
                children.count { label(it).equals("Reply",true) }==1) return container
            parent=container.parent
        }
        return null
    }
    private fun exactThread(author:String,body:String) = row(author,body)!=null
    @RequiresTransaction(reason="Reply to exact own-video TikTok comment")
    suspend fun reply(author:String,body:String,response:String):Boolean {
        val container=row(author,body) ?: return false
        val button=all(container).singleOrNull { it.isVisibleToUser && label(it).equals("Reply",true) } ?: return false
        if(!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return false
        delay(500)
        val r=root() ?: return false
        val expected=author.removePrefix("@")
        val labels=all(r).filter { it.isVisibleToUser }.map(::label)
        if(labels.none { it.equals("Reply to @$expected",true) || it.equals("Reply to $expected",true) || it.equals("Replying to $expected",true) }) return false
        val editor=all(r).singleOrNull { it.isVisibleToUser && it.isEditable } ?: return false
        if(!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,response) })) return false
        delay(350)
        val current=root() ?: return false
        if(all(current).singleOrNull { it.isVisibleToUser && it.isEditable }?.text?.toString()!=response || !exactThread(author,body)) return false
        val send=all(current).filter { it.isVisibleToUser && it.isEnabled && (label(it).equals("Send",true) || label(it).equals("Post",true)) }.singleOrNull() ?: return false
        return send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    suspend fun verify(author:String,body:String,response:String,ownName:String):VerificationEvidence {
        repeat(10) {
            val original=row(author,body)
            if(original!=null && ownName.isNotBlank()) {
                // Only a response beneath the same parent thread with one own author is proof.
                val thread=original.parent
                if(thread!=null) {
                    val ns=all(thread).filter { it.isVisibleToUser }
                    val texts=ns.map(::label)
                    if(texts.count { it==body }==1 && texts.count { it==response }==1 && texts.count { it==ownName }==1 &&
                        texts.none { it.contains("sending",true) || it.contains("failed",true) })
                        return VerificationEvidence(true,.9,root()?.packageName?.toString().orEmpty(),"exact_thread_reply_visible",System.currentTimeMillis())
                }
            }
            delay(500)
        }
        return VerificationEvidence.impossible("Reply under the exact notification thread was not proven",root()?.packageName?.toString().orEmpty())
    }
}
