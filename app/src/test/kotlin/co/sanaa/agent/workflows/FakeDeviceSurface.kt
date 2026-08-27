package co.sanaa.agent.workflows

import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.actions.MessageNodeFacts
import co.sanaa.agent.actions.WhatsAppScreenSnapshot
import co.sanaa.agent.actions.WorkflowDeviceSurface
import co.sanaa.agent.core.ContentHashing

/**
 * CONTROLLED fake of the narrow [WorkflowDeviceSurface] seam. It can only do what the
 * production surface exposes, so certification through it exercises the REAL router,
 * REAL [co.sanaa.agent.core.SideEffectRunner], REAL catalog, and REAL durable ledgers.
 */
class FakeDeviceSurface : WorkflowDeviceSurface {

    var foregroundPackage: String = "com.whatsapp"
    val screenText = mutableListOf<String>()
    /** When false, every device primitive provably fails to dispatch (no effect). */
    var dispatchResult: Boolean = true
    var deliveryMarker: String? = "sent"
    /** Simulate node-tree facts: content found as sent bubble vs stuck in draft field. */
    var contentStuckInDraft: Boolean = false
    var dispatchedCount: Int = 0
        private set
    val transactedMessages = mutableListOf<String>()
    private val sokoFieldValues = mutableMapOf<String, String>()

    fun seedChat(target: String) {
        foregroundPackage = "com.whatsapp"
        if (screenText.none { it.contains(target, true) }) screenText += target
    }

    fun verifyWillSucceed(target: String, content: String) {
        seedChat(target)
        deliveryMarker = "sent"
        contentStuckInDraft = false
    }

    private fun record(message: String): Boolean {
        dispatchedCount++
        if (!dispatchResult) return false
        // A successful dispatch makes the content visible on the live surface (sent
        // bubble / published status); the verifier must observe it like reality would.
        transactedMessages += message
        screenText += message
        return true
    }

    override suspend fun <T> transacted(block: suspend AccessibilityActions.TransactionScope.() -> T): T {
        val scope = object : AccessibilityActions.TransactionScope {
            override suspend fun sendToWhatsAppPhone(phone: String, message: String): Boolean = record(message)
            override suspend fun sendToWhatsAppContact(contact: String, message: String): Boolean = record(message)
            override suspend fun sendToWhatsAppGroup(group: String, message: String): Boolean = record(message)
            override suspend fun sendWhatsAppAttachment(target: String, uri: android.net.Uri, mimeType: String, caption: String): Boolean = record(caption.ifBlank { "media:${uri.lastPathSegment}" })
            override suspend fun sendInCurrentChat(message: String): Boolean = record(message)
            override suspend fun postWhatsAppTextStatus(message: String): Boolean = record(message)
            override suspend fun postWhatsAppMediaStatus(uri: android.net.Uri, mimeType: String, caption: String): Boolean = record(caption)
            override suspend fun postTikTok(imageUrl: String, caption: String, publish: Boolean): Boolean = record(caption)
            override suspend fun updateSokoListing(currentTitle: String, newTitle: String, newDescription: String): Boolean = record(newTitle)
            override suspend fun saveEditForm(): Boolean = record("Update Product")
            override fun setFirstEditable(text: String): Boolean = record(text)
        }
        return block(scope)
    }

    override fun snapshot(): WhatsAppScreenSnapshot =
        WhatsAppScreenSnapshot(foregroundPackage, screenText.toList(), screenText.joinToString("|").hashCode().toString())

    override fun observeMessageNodes(content: String): MessageNodeFacts {
        val wanted = ContentHashing.normalize(content)
        val bubbleHit = !contentStuckInDraft && wanted.isNotEmpty() &&
            screenText.any { ContentHashing.normalize(it).contains(wanted) }
        return MessageNodeFacts(
            inReadOnlyBubble = bubbleHit,
            onlyInsideEditableField = contentStuckInDraft,
            deliveryState = deliveryMarker,
        )
    }

    override fun messageDeliveryState(message: String): String? = deliveryMarker

    override suspend fun openWhatsAppTarget(contact: String): Boolean {
        seedChat(contact)
        return dispatchResult
    }

    override suspend fun openSokoEditForm(productName: String): Boolean {
        if (foregroundPackage != co.sanaa.agent.actions.SokoSaveVerification.SOKO_PACKAGE) return false
        return true
    }

    override suspend fun setFirstEditableField(value: String): Boolean =
        if (record(value)) {
            sokoFieldValues["product name"] = value
            true
        } else false

    override suspend fun saveSokoEditForm(): Boolean = transacted { saveEditForm() }

    override fun verifyEditFormFields(expected: Map<String, String>): Boolean =
        expected.all { (field, value) -> sokoFieldValues[field] == value }

    fun stageSokoField(field: String, value: String) {
        sokoFieldValues[field.lowercase()] = value
    }
}
