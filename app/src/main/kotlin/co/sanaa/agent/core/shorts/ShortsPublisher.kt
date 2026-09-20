package co.sanaa.agent.core.shorts

import android.content.Context
import co.sanaa.agent.actions.*
import co.sanaa.agent.core.*
import co.sanaa.agent.core.work.*
import co.sanaa.agent.core.work.WorkStatus
import kotlinx.coroutines.delay

class ShortsPublisher(private val context: Context, private val actions: AccessibilityActions, private val effects: SideEffectRunner) {
    suspend fun execute(item: WorkItem): WorkResult {
        val settings=ShortsSettings(context)
        val source=item.payload.optString("source_post_key")
        fun held(reason: String): WorkResult {
            settings.status(reason)
            ShortsQueue(context).use { it.update(source,"HELD",reason) }
            return WorkResult(item,WorkStatus.ESCALATED,failure=FailureInfo(FailureClass.PRECONDITION_GONE,reason,false))
        }
        if(!settings.enabled) return WorkResult(item,WorkStatus.SKIPPED)
        val channel=item.payload.optString("youtube_channel")
        val visibility=settings.visibility
        val madeForKids=settings.madeForKids
        val prepareOnly = item.payload.optBoolean("prepare_only")
        if(channel!=settings.channel || !ShortsMediaPolicy.validHandle(channel) || (!prepareOnly && !settings.audioCleared))
            return held("Channel or soundtrack permission is not ready")
        val runtime=AgentRuntime.get(context)
        val transactions=runtime.memory.allSideEffectTransactions()
        if(!prepareOnly) ShortsMediaPolicy.priorDispatch(source,transactions)?.let { prior ->
            val state=if(prior.state==SideEffectState.VERIFIED) "VERIFIED" else "UNCERTAIN"
            ShortsQueue(context).use { it.update(source,state,"Previous upload retained across restart; no replay") }
            settings.status("Previous upload $state; no new upload dispatched")
            return WorkResult(item,WorkStatus.SKIPPED)
        }
        if(runtime.memory.findSideEffectTransaction(source)?.let { it.capability==CapabilityIds.POST_TIKTOK && it.state==SideEffectState.VERIFIED } != true)
            return held("Source TikTok publication is not verified")
        val day=java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        if(!prepareOnly && transactions.count { it.capability==CapabilityIds.POST_YOUTUBE_SHORT && it.createdAt>=day }>=settings.dailyCap)
            return WorkResult(item,WorkStatus.SKIPPED,failure=FailureInfo(FailureClass.POLICY_BLOCKED,"YouTube daily cap reached",true))
        val scope=item.payload.optString("shop_scope")
        val shop=ShopSessionRecovery(read={TerminalShopIdentity.readFresh(context)},
            reopen={actions.openSokoTerminal() && actions.waitForForegroundPackage("com.soko24.soko_seller_terminal")!=null},
            settle={delay(1000)}).ensure()
        if(scope.isBlank() || scope!=shop.scope) return held("Shorts source belongs to a different Terminal shop")
        val caption=item.payload.optString("caption")
        val title=item.payload.optJSONObject("ad")?.optString("headline").orEmpty().take(100)
        if(title.isBlank()) return held("Source ad title is missing")
        settings.status("Retrieving verified TikTok video and soundtrack")
        val file=try { TikTokFinishedExport(context,actions).capture(caption,source) }
            catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;return held(e.message ?: "TikTok export failed") }
        val normalized=try { ShortsAudioNormalizer.normalize(file) }
            catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;return held(e.message ?: "Audio normalization failed") }
        val refreshedShop=ShopSessionRecovery(read={TerminalShopIdentity.readFresh(context)},
            reopen={actions.openSokoTerminal() && actions.waitForForegroundPackage("com.soko24.soko_seller_terminal")!=null},
            settle={delay(1000)}).ensure()
        if(refreshedShop.scope!=scope) return held("Terminal shop changed during media preparation")
        if(!settings.enabled || settings.channel!=channel || !OwnerPower(context).isOn())
            return held("Amara or YouTube settings changed during preparation")
        val digest=BoundTikTokMedia.sha256(normalized.readBytes())
        val surface=ShortsDeviceSurface(context,actions)
        settings.status("Preparing Short for $channel")
        if(!surface.prepare(normalized,title,caption,channel,visibility,madeForKids)) {
            val failedStage=surface.lastStage
            val closed=surface.discardPreparation()
            return held("YouTube $failedStage could not be verified; no upload dispatched" +
                if(closed) "; editor closed" else "; editor needs review")
        }
        if (prepareOnly) {
            if(!surface.discardPreparation()) return held("Details verified, but the preparation editor still needs to be closed; no upload dispatched")
            settings.status("Preparation verified for $channel; no upload dispatched")
            return WorkResult(item,WorkStatus.DONE,outcomeFacts=listOf("YouTube prepared original TikTok video and sound; channel and details checked; no upload dispatched"))
        }
        val outcome=effects.execute(capabilityId=CapabilityIds.POST_YOUTUBE_SHORT,
            inputs=mapOf("shop_scope" to scope,"target" to channel,"message" to "$title\n$caption\n$digest"),
            idempotencyKey=item.dedupeKey,target=channel,content="$title\n$caption\n$digest",initiator=Initiator.RECURRING_SCHEDULE,
            preflight={ if(!settings.enabled || settings.channel!=channel || !settings.audioCleared ||
                settings.visibility!=visibility || settings.madeForKids!=madeForKids) "YouTube settings changed" else null },
            act={ actions.transacted { publishYouTubeShort(channel,title,caption) } },verify={surface.verify(title,channel)})
        val verified=outcome is SideEffectOutcome.Verified || outcome is SideEffectOutcome.DuplicateBlocked
        val status=if(verified) "VERIFIED" else if(outcome is SideEffectOutcome.Uncertain) "UNCERTAIN" else "HELD"
        ShortsQueue(context).use { it.update(source,status,outcome.toString()) }
        settings.status("$status: $title")
        context.getSharedPreferences("youtube_shorts",Context.MODE_PRIVATE).edit().putLong("next_at",System.currentTimeMillis()+settings.intervalMinutes*60_000L).apply()
        return WorkResult(item,if(verified) WorkStatus.DONE else WorkStatus.ESCALATED,
            outcomeFacts=if(verified) listOf("Verified Short: $title on $channel") else emptyList(),
            failure=if(verified) null else FailureInfo(FailureClass.UNKNOWN,"Shorts outcome: $outcome",false))
    }
}
