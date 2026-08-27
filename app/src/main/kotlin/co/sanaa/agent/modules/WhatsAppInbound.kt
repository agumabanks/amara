package co.sanaa.agent.modules

import android.app.Notification
import android.os.Bundle

data class WhatsAppInbound(
    val sender: String,
    val message: String,
    val conversation: String,
    val isGroup: Boolean,
    val isMissedCall: Boolean = false,
) {
    val target: String get() = if (isGroup) conversation else sender
    val signature: String get() = "$sender|$conversation|$message|$isMissedCall"
}

object WhatsAppNotificationParser {
    fun parse(notification: Notification): WhatsAppInbound? {
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        return parse(notification.extras ?: Bundle.EMPTY)
    }

    @Suppress("DEPRECATION")
    internal fun parse(extras: Bundle): WhatsAppInbound? {
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val latestMessage = extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? Bundle
        val text = latestMessage?.getCharSequence("text")?.toString()?.trim().orEmpty()
            .ifBlank { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty() }
        val messageSender = if (android.os.Build.VERSION.SDK_INT >= 28) {
            (latestMessage?.getParcelable("sender_person") as? android.app.Person)?.name?.toString()?.trim().orEmpty()
        } else latestMessage?.getCharSequence("sender")?.toString()?.trim().orEmpty()
        val messagingPerson = if (android.os.Build.VERSION.SDK_INT >= 28) {
            (extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON) as? android.app.Person)?.name?.toString()?.trim().orEmpty()
        } else ""
        val sender = messageSender.ifBlank { messagingPerson }.ifBlank { title.substringBefore(" @ ").substringBefore(":").trim() }
        val inferredGroup = conversationTitle.isNotBlank() || title.contains(" @ ")
        val conversation = conversationTitle.ifBlank { title.substringAfter(" @ ", title).trim() }.ifBlank { sender }
        val lower = "$title $text".lowercase()
        val missedCall = "missed voice call" in lower || "missed video call" in lower || "missed call" in lower
        if (sender.isBlank() || (text.isBlank() && !missedCall)) return null
        return WhatsAppInbound(sender, text, conversation, inferredGroup, missedCall)
    }

    fun parseAccessibility(raw: String): WhatsAppInbound? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        val separator = clean.indexOf(':')
        val sender = if (separator > 0) clean.substring(0, separator).trim() else "Unknown"
        val message = if (separator > 0) clean.substring(separator + 1).trim() else clean
        val lower = clean.lowercase()
        return WhatsAppInbound(sender, message, sender, false, "missed call" in lower)
    }
}
