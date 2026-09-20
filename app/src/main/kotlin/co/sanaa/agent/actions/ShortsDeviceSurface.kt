package co.sanaa.agent.actions

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import co.sanaa.agent.services.AccessibilityAgentService
import co.sanaa.agent.core.VerificationEvidence
import co.sanaa.agent.core.shorts.ShortsMediaInspector
import kotlinx.coroutines.delay
import java.io.File

/** Stage recognition uses live semantics and local OCR, never screen-specific coordinates. */
internal class ShortsDeviceSurface(private val context: Context, private val actions: AccessibilityActions) {
    var lastStage="import"
        private set
    private fun stage(value: String) {
        lastStage=value
        co.sanaa.agent.core.EvaluationJournal(context).record("youtube_preparation_stage",fields=org.json.JSONObject().put("stage",value))
    }
    private val ocr = LocalScreenText(actions)
    private suspend fun labels(): List<String> = (actions.snapshot().visibleText + ocr.read(PACKAGE).map { it.text })
        .flatMap { it.lines() }.map(String::trim).filter(String::isNotBlank).distinct()
    private suspend fun tap(label: String): Boolean {
        if(actions.snapshot().packageName != PACKAGE) return false
        val before=actions.snapshot().signature
        val accepted=actions.clickExactLabel(label) || ocr.tap(PACKAGE,label)
        val changed=accepted && actions.waitForScreenChange(before,4_000)
        co.sanaa.agent.core.AgentRuntime.get(context).memory.recordSelectorOutcome(PACKAGE,"shorts_${label.lowercase()}","semantic_or_local_text:$label",
            changed,if(changed) "Observed transition" else "Transition not proven")
        // Some custom-rendered controls change pixels or a checked state without
        // changing Accessibility labels. The next stage verifies their result.
        return accepted
    }
    private fun fields(): List<AccessibilityNodeInfo> {
        val root = AccessibilityAgentService.instance?.rootInActiveWindow ?: return emptyList()
        if(root.packageName?.toString()!=PACKAGE) return emptyList()
        fun collect(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            (if(node.isVisibleToUser && node.isEditable) listOf(node) else emptyList()) +
                (0 until node.childCount).flatMap { node.getChild(it)?.let(::collect).orEmpty() }
        return collect(root)
    }
    private suspend fun fill(value: String): Boolean {
        val input = fields().singleOrNull() ?: return false
        if(!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        })) return false
        repeat(5) {
            if(fields().singleOrNull()?.text?.toString()==value) return true
            delay(200)
        }
        return false
    }
    suspend fun prepare(file: File, title: String, description: String, channel: String,
                        visibility: String = "Public", madeForKids: Boolean = false): Boolean {
        stage("import")
        val tracks = ShortsMediaInspector.inspect(file)
        check(tracks.usable && !tracks.audioEndsEarly) { "Export needs audio duration normalization before YouTube import" }
        val uri = FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        context.startActivity(Intent(Intent.ACTION_SEND).apply {
            type="video/mp4"; setPackage(PACKAGE);putExtra(Intent.EXTRA_STREAM,uri)
            clipData=ClipData.newRawUri("Verified ad",uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        if(actions.waitForForegroundPackage(PACKAGE,15_000)==null) return false
        val budget=UiProgressBudget(30_000,180_000)
        var stage="import"
        while(!budget.expired()) {
            if(actions.snapshot().packageName!=PACKAGE) return false
            val text=labels()
            when {
                "Add details" in text -> break
                "Next" in text -> {
                    val observed=if("Add sound" in text) "editor" else "trim"
                    if(stage!=observed && tap("Next")) {stage=observed;budget.progress(stage)}
                }
            }
            delay(800)
        }
        var text=labels()
        stage("details_channel")
        val channelDeadline = android.os.SystemClock.elapsedRealtime() + 12_000L
        while ("Add details" !in text || text.none { it.equals(channel,true) }) {
            if (android.os.SystemClock.elapsedRealtime() >= channelDeadline) return false
            val activePackage = actions.snapshot().packageName
            if (activePackage.isNotBlank() && activePackage != PACKAGE) return false
            delay(500)
            text = labels()
        }
        stage("title")
        if(!fill(title.take(100))) return false
        stage("description")
        if("Add description" !in text) { if(!tap("Show more")) return false;delay(500) }
        if(!tap("Add description")) return false
        delay(500)
        if(!fill(description)) return false
        if(!actions.clickExactLabel("Navigate up","Back")) return false
        delay(500)
        stage("audience")
        if(!tap("Select audience")) return false
        delay(500)
        if(!tap(if(madeForKids) "Yes, it's made for kids" else "No, it's not made for kids")) return false
        if(!actions.clickExactLabel("Navigate up","Back")) return false
        delay(500)
        text=labels()
        stage("visibility")
        if(visibility !in text) {
            val current = listOf("Public", "Unlisted", "Private").singleOrNull { it in text } ?: return false
            if(!tap(current)) return false
            delay(500)
            if(!tap(visibility)) return false
            if(!actions.clickExactLabel("Navigate up","Back")) return false
            delay(500)
        }
        text=labels()
        stage("final_details")
        return "Add details" in text && text.any { it.equals(channel,true) } &&
            fields().singleOrNull()?.text?.toString()==title.take(100) && visibility in text &&
            audienceMatches(text, madeForKids)
    }
    /** Only invoked inside AccessibilityActions.TransactionScope. */
    suspend fun dispatch(channel: String, title: String, description: String): Boolean {
        val settings=co.sanaa.agent.core.shorts.ShortsSettings(context)
        var text=labels()
        if("Add details" !in text || text.none { it.equals(channel,true) }) return false
        if("Add description" !in text && !tap("Show more")) return false
        if(!tap("Add description")) return false
        delay(500)
        if(fields().singleOrNull()?.text?.toString()!=description) return false
        if(!actions.clickExactLabel("Navigate up","Back")) return false
        delay(500)
        text=labels()
        if(!settings.enabled || !settings.audioCleared || settings.channel!=channel ||
            settings.visibility !in text || !audienceMatches(text,settings.madeForKids)) return false
        if("Add details" !in text || text.none { it.equals(channel,true) } || fields().singleOrNull()?.text?.toString()!=title.take(100)) return false
        return actions.clickExactLabel("Upload Short")
    }
    suspend fun discardPreparation(): Boolean {
        repeat(6) {
            if(actions.snapshot().packageName!=PACKAGE) return true
            val text=labels()
            if ("Delete edits?" in text && "Continue" in text) {
                if(!tap("Continue")) return false
            } else if(text.any { it.equals("Discard",true) }) {
                if(!tap("Discard")) return false
            } else if(text.any { it in setOf("Add details","Add sound","Next","Exit editor") }) {
                if(!actions.clickExactLabel("Exit editor","Navigate up","Back")) {
                    if(!actions.globalBack()) return false
                }
            } else return text.any { it in setOf("You","Subscriptions","Home") }
            delay(700)
        }
        return false
    }
    suspend fun verify(title: String, channel: String): VerificationEvidence {
        val budget=UiProgressBudget(45_000,180_000)
        while(!budget.expired()) {
            if(actions.snapshot().packageName!=PACKAGE) break
            val text=labels()
            budget.progress(text.firstOrNull { it.matches(Regex(".*\\b[0-9]{1,3}%.*")) })
            // Require own-channel context and an explicit completed-upload message.
            if(text.any { it.equals(channel,true) } && text.any { it==title.take(100) } &&
                text.any { it.equals("Upload complete",true) || it.equals("Published",true) })
                return VerificationEvidence(true,0.9,PACKAGE,"own_channel_short_published",System.currentTimeMillis())
            delay(1500)
        }
        return VerificationEvidence.impossible("YouTube publication remains unproven; review without reuploading")
    }
    companion object { const val PACKAGE="com.google.android.youtube" }

    private fun audienceMatches(text: List<String>, madeForKids: Boolean): Boolean = text.any {
        if(madeForKids) it.equals("Yes, it's made for kids",true) || it.equals("Made for kids",true)
        else it.contains("not made for kids",true)
    }
}
