// NEGATIVE FIXTURE — wrapper attempt. Must not compile: sendInCurrentChat is private.
class WrapperAttempt(private val actions: co.sanaa.agent.actions.AccessibilityActions) {
    suspend fun send(message: String): Boolean = actions.sendInCurrentChat(message)
}
