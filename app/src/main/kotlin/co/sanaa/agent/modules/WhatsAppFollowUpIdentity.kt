package co.sanaa.agent.modules

import co.sanaa.agent.core.*

/** A notification identity is not a display name and must never become a search query. */
object WhatsAppFollowUpIdentity {
    fun target(directory: ContactDirectory, chatKey: String): String? {
        if (!chatKey.startsWith("wa-origin:")) return chatKey.takeIf(String::isNotBlank)
        val entry = directory.byId(chatKey) ?: return null
        if (entry.isGroup || !entry.canMonitor || entry.revocationEvidence != null) return null
        val phone = entry.normalizedPhone ?: return null
        val resolved = directory.resolve(ContactQuery(phone = phone, isGroup = false)) as? Resolution.Unique ?: return null
        return phone.takeIf { resolved.entry.id == entry.id }
    }
}
