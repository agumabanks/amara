package co.sanaa.agent.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.DeviceActivityMonitor
import co.sanaa.agent.modules.WhatsAppNotificationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class AccessibilityAgentService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastNotificationSignature = ""
    private var lastNotificationAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        runCatching {
            serviceInfo = serviceInfo?.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            }
        }.onFailure {
            // On some OEM builds and on Robolectric the AccessibilityServiceInfo
            // may be null at bind time; the default service-info from the
            // manifest remains in effect, which is acceptable for our needs.
            android.util.Log.w("SanaaA11y", "serviceInfo override skipped: ${it.message}")
        }
        co.sanaa.agent.permissions.SelfHealingPermissionManager(applicationContext)
            .markAccessibilityServiceAlive()
        android.util.Log.i("SanaaA11y", "Accessibility service connected and configured")
    }

    @androidx.annotation.VisibleForTesting
    internal fun invokeOnServiceConnectedForTest() {
        onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        try {
            DeviceActivityMonitor.observe(event.eventType, eventUptimeMillis = event.eventTime)
            if (event.packageName?.toString() != "com.whatsapp" || event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
            val text = event.text.joinToString(" ").trim()
            if (text.isBlank()) return
            val now = System.currentTimeMillis()
            if (text == lastNotificationSignature && now - lastNotificationAt < 30_000) return
            lastNotificationSignature = text
            lastNotificationAt = now
            val inbound = WhatsAppNotificationParser.parseAccessibility(text) ?: return
            scope.launch {
                val runtime = AgentRuntime.get(applicationContext).awaitReady()
                co.sanaa.agent.core.work.InboundWorkProposer.offerWhatsApp(runtime, inbound, observedAt = now)
            }
        } catch (e: Exception) {
            android.util.Log.e("SanaaA11y", "Event handling error: ${e.message}")
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
        android.util.Log.w("SanaaA11y", "Accessibility service destroyed - attempting rebind")
    }

    companion object {
        @Volatile
        var instance: AccessibilityAgentService? = null
            private set

        fun isBound(): Boolean = instance != null

        @androidx.annotation.VisibleForTesting
        internal fun setInstanceForTest(value: AccessibilityAgentService?) {
            instance = value
        }
    }
}
