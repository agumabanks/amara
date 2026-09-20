package co.sanaa.agent.modules

/** A recovery schedule needs durable evidence about dispatch, not an error-string guess. */
object GroupRecoveryPolicy {
    fun canRetry(workKey: String, dispatchState: String, unresolvedDispatch: Boolean): Boolean =
        workKey.isNotBlank() && !unresolvedDispatch && dispatchState in setOf("NOT_STARTED", "CANCELLED", "EXPIRED", "FAILED")
}
