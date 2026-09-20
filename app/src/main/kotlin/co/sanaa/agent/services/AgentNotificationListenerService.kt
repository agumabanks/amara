package co.sanaa.agent.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.modules.WhatsAppNotificationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class AgentNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSignature = ""
    private var lastAt = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        // Recover still-visible inbound events after ColorOS/process reconnection.
        // Stable message timestamps keep this replay idempotent in the durable queue.
        runCatching { activeNotifications }.getOrNull()?.forEach(::onNotificationPosted)
    }

    override fun onDestroy() {
        if(instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null && sbn.packageName in co.sanaa.agent.modules.TikTokCommentNotifications.packages) {
            scope.launch {
                val runtime=AgentRuntime.get(applicationContext).awaitReady()
                if(!runtime.config.tikTokCommentsEnabled) return@launch
                val inbox=co.sanaa.agent.modules.TikTokCommentInbox(applicationContext)
                val id=try { co.sanaa.agent.modules.TikTokCommentNotifications.capture(sbn,inbox) } finally { inbox.close() } ?: return@launch
                runtime.workQueue.offer(co.sanaa.agent.core.work.WorkItem(
                    dedupeKey="tiktok-notification:$id", domain=co.sanaa.agent.core.work.Domain.TIKTOK,
                    kind=co.sanaa.agent.core.work.WorkKind.TIKTOK_COMMENT_REPLY,
                    payload=org.json.JSONObject().put("notification_id",id), baseValueKes=25.0,
                    urgencyHalfLifeHours=1.0,estimatedScreenSeconds=60,
                    requires=setOf(co.sanaa.agent.core.work.Capability.SCREEN,co.sanaa.agent.core.work.Capability.NETWORK,
                        co.sanaa.agent.core.work.Capability.GROQ,co.sanaa.agent.core.work.Capability.CONSENT_TIER_2),
                    riskTier=co.sanaa.agent.core.work.RiskTier.MEDIUM))
                runtime.workLoop.wake(co.sanaa.agent.core.work.WakeReason.ExternalEvent("tiktok_comment_notification",""))
            }
            return
        }
        if (sbn?.packageName != "com.whatsapp") return
        WhatsAppNotificationParser.parseAll(sbn.notification).forEach { parsed ->
            propose(sbn, parsed)
        }
    }

    private fun propose(sbn: StatusBarNotification, parsed: co.sanaa.agent.modules.WhatsAppInbound) {
        val inbound = parsed.copy(messageTimestamp = parsed.messageTimestamp.takeIf { it>0 } ?: sbn.postTime,
            conversationIdentity = co.sanaa.agent.modules.WhatsAppConversationRoutes.register(sbn,parsed.isGroup))
        val now = System.currentTimeMillis()
        if (inbound.signature == lastSignature && now - lastAt < 30_000) return
        lastSignature = inbound.signature
        lastAt = now
        Log.i(TAG, "WhatsApp inbound received group=${inbound.isGroup}")
        scope.launch {
            val runtime = AgentRuntime.get(applicationContext).awaitReady()
            co.sanaa.agent.core.work.InboundWorkProposer.offerWhatsApp(runtime, inbound, observedAt = now)
            Log.i(TAG, "WhatsApp inbound proposed to the governed work queue; ingestion_ms=${System.currentTimeMillis() - now}")
        }
    }

    /** Rebuild only WhatsApp-owned routes; never send or enqueue a reply here. */
    fun refreshWhatsAppRoutes() {
        runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { it.packageName == "com.whatsapp" }.forEach { sbn ->
                WhatsAppNotificationParser.parseAll(sbn.notification).lastOrNull()?.let {
                    co.sanaa.agent.modules.WhatsAppConversationRoutes.register(sbn,it.isGroup)
                }
            }
    }

    fun refreshTikTokRoutes() {
        val notifications=runCatching { activeNotifications }.getOrNull().orEmpty()
        notifications.filter { it.packageName in co.sanaa.agent.modules.TikTokCommentNotifications.packages }
            .forEach { co.sanaa.agent.modules.TikTokCommentNotifications.refreshRoute(it) }
    }

    override fun onListenerDisconnected() {
        if(instance === this) instance = null
        super.onListenerDisconnected()
        requestRebind(android.content.ComponentName(this,AgentNotificationListenerService::class.java))
    }

    companion object {
        private const val TAG = "SanaaInbound"
        @Volatile var instance: AgentNotificationListenerService? = null
            private set
    }
}
