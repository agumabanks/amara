package co.sanaa.agent.modules

import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import co.sanaa.agent.core.ContentHashing

/** WhatsApp-owned launch tokens, never name-based links or guessed contact IDs. */
object WhatsAppConversationRoutes {
    private data class Route(val intent: PendingIntent, val expiresAt: Long)
    private val routes = java.util.concurrent.ConcurrentHashMap<String, Route>()
    fun identity(shortcut: String?, notificationKey: String, isGroup: Boolean): String =
        "wa-origin:" + java.security.MessageDigest.getInstance("SHA-256").digest("${if(isGroup) "group" else "direct"}|${shortcut?.takeIf { it.isNotBlank() } ?: notificationKey}".toByteArray()).joinToString("") { "%02x".format(it) }
    fun register(sbn: StatusBarNotification, isGroup: Boolean): String {
        val id=identity(sbn.notification.shortcutId,"${sbn.key}|${sbn.postTime}",isGroup)
        val intent=sbn.notification.contentIntent
        val now=System.currentTimeMillis()
        routes.entries.removeIf { it.value.expiresAt < now }
        if(intent != null && intent.creatorPackage == "com.whatsapp") routes[id]=Route(intent,now+24*3_600_000L)
        return id
    }
    fun open(id: String): Boolean {
        val route=routes[id] ?: return false
        if(route.expiresAt<System.currentTimeMillis()) { routes.remove(id);return false }
        return try { route.intent.send();true } catch (_: PendingIntent.CanceledException) { routes.remove(id);false }
    }
}
