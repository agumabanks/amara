package co.sanaa.agent.modules

import android.app.Notification
import android.os.Bundle

data class WhatsAppInbound(
    val sender: String,
    val message: String,
    val conversation: String,
    val isGroup: Boolean,
    val isMissedCall: Boolean = false,
    val messageTimestamp: Long = 0L,
    val conversationIdentity: String = "",
) {
    val target: String get() = WhatsAppNotificationParser.cleanConversationTitle(if (isGroup) conversation else sender)
    val signature: String get() = "$conversationIdentity|$sender|$conversation|$message|$isMissedCall|$messageTimestamp"
}

object WhatsAppNotificationParser {
    internal fun cleanConversationTitle(value: String): String = value
        .replace(Regex("\\s+\\(\\d+ messages?\\)$", RegexOption.IGNORE_CASE), "").trim()

    fun parse(notification: Notification): WhatsAppInbound? {
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        return parse(notification.extras ?: Bundle.EMPTY)
    }

    @Suppress("DEPRECATION")
    fun parseAll(notification: Notification): List<WhatsAppInbound> {
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return emptyList()
        val extras = notification.extras ?: Bundle.EMPTY
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty().filterIsInstance<Bundle>()
        if (messages.isEmpty()) return listOfNotNull(parse(extras))
        return messages.mapNotNull { message ->
            val single = Bundle(extras)
            single.putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(message))
            parse(single)
        }.distinctBy { it.signature }.sortedBy { it.messageTimestamp }
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
        val sender = messageSender.ifBlank { title.substringBefore(" @ ").substringBefore(":").trim() }
        val inferredGroup = if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION))
            extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) else conversationTitle.isNotBlank() || title.contains(" @ ")
        val conversation = conversationTitle.ifBlank { title.substringAfter(" @ ", title).trim() }.ifBlank { sender }
        val lower = "$title $text".lowercase()
        val missedCall = "missed voice call" in lower || "missed video call" in lower || "missed call" in lower
        if (sender.isBlank() || (text.isBlank() && !missedCall)) return null
        if (sender.equals("WhatsApp", true) || text.startsWith("Sending message", true) ||
            text.startsWith("Sending file", true)) return null
        if (!missedCall && isNonConversationalEvent(text)) return null
        return WhatsAppInbound(cleanConversationTitle(sender), text, cleanConversationTitle(conversation), inferredGroup, missedCall, latestMessage?.getLong("time", 0L) ?: 0L)
    }

    /** WhatsApp also emits status engagement, reaction, and media-placeholder
     * notifications. They contain no customer words Amara can answer safely. */
    internal fun isNonConversationalEvent(text: String): Boolean {
        val clean = text.trim().lowercase()
        return clean.matches(Regex("^(?:liked|reacted .+) (?:your status|to .+)$")) ||
            clean.matches(Regex("^(?:🎤\\s*)?(?:voice message|audio|video|photo|sticker|gif)(?:\\s*\\([^)]*\\))?$"))
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
