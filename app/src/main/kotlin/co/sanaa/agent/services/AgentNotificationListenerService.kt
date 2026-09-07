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
        // Recover still-visible inbound events after ColorOS/process reconnection.
        // Stable message timestamps keep this replay idempotent in the durable queue.
        runCatching { activeNotifications }.getOrNull()?.forEach(::onNotificationPosted)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
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

    companion object { private const val TAG = "SanaaInbound" }
}
