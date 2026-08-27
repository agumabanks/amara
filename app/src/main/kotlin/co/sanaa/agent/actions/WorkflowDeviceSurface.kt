package co.sanaa.agent.actions

/**
 * Narrow production seam the workflow effect router depends on. [AccessibilityActions]
 * implements it directly; certification tests inject a controlled fake of exactly this
 * interface while running the REAL router, REAL [co.sanaa.agent.core.SideEffectRunner],
 * and REAL durable ledger. Keeping the seam this small makes the fake trustworthy: it
 * cannot invent behavior the production surface does not expose.
 */
interface WorkflowDeviceSurface {
    /** STRUCTURAL boundary: device-writing primitives only run inside [transacted]. */
    suspend fun <T> transacted(block: suspend AccessibilityActions.TransactionScope.() -> T): T

    fun snapshot(): WhatsAppScreenSnapshot
    fun observeMessageNodes(content: String): MessageNodeFacts
    fun messageDeliveryState(message: String): String?
    suspend fun openWhatsAppTarget(contact: String): Boolean

    /** Approved Soko listing edit support (open form, save, reopen-compare). */
    suspend fun openSokoEditForm(productName: String): Boolean
    suspend fun setFirstEditableField(value: String): Boolean
    suspend fun saveSokoEditForm(): Boolean
    fun verifyEditFormFields(expected: Map<String, String>): Boolean
}
