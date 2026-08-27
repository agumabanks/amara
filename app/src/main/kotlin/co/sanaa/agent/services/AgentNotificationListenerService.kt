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

class AgentNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSignature = ""
    private var lastAt = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != "com.whatsapp") return
        val inbound = WhatsAppNotificationParser.parse(sbn.notification) ?: return
        val now = System.currentTimeMillis()
        if (inbound.signature == lastSignature && now - lastAt < 30_000) return
        lastSignature = inbound.signature
        lastAt = now
        Log.i(TAG, "WhatsApp inbound received group=${inbound.isGroup}")
        scope.launch {
            val runtime = AgentRuntime.get(applicationContext).awaitReady()
            val result = runtime.conversation.observeWhatsApp(inbound)
            Log.i(TAG, "WhatsApp inbound handled success=${result.success} summary=${result.summary}")
        }
    }

    companion object { private const val TAG = "SanaaInbound" }
}
