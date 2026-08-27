package co.sanaa.agent.actions

import co.sanaa.agent.api.BackendSync
import co.sanaa.agent.api.SokoApiClient
import co.sanaa.agent.api.VerificationResult
import kotlinx.coroutines.delay

class ActionVerifier(private val accessibility: AccessibilityActions, private val soko: SokoApiClient, private val backend: BackendSync) {
    suspend fun verifyWhatsApp(message: String): VerificationResult {
        repeat(6) {
            if (accessibility.currentWindowContains(message.take(40))) {
                val state = accessibility.messageDeliveryState(message)
                return VerificationResult(true, "Message appears in the active WhatsApp chat${state?.let { "; state: $it" }.orEmpty()}")
            }
            delay(1_000)
        }
        return VerificationResult(false, "Message was not found in the active WhatsApp chat; tick state could not be confirmed")
    }

    suspend fun verifyListing(id: String, title: String, description: String): VerificationResult {
        delay(60_000)
        val listing = runCatching { soko.listing(id) }.getOrNull()
        return if (listing?.title == title && listing.description == description) VerificationResult(true, "Soko returned the submitted title and description")
        else VerificationResult(false, "Soko did not return the submitted listing changes")
    }

    suspend fun verifyTikTok(caption: String): VerificationResult {
        repeat(6) { if (accessibility.currentWindowContains(caption.take(30))) return VerificationResult(true, "Caption appears in TikTok"); delay(1_000) }
        return VerificationResult(false, "TikTok caption was not found in the active profile/post screen")
    }

    suspend fun executeVerified(
        module: String,
        action: String,
        platform: String,
        summary: String,
        act: suspend () -> Boolean,
        verify: suspend () -> VerificationResult,
        retrySafe: Boolean = false,
    ): VerificationResult {
        val attempts = if (retrySafe) 2 else 1
        repeat(attempts) { attempt ->
            val acted = executeLogged(module, action, platform, "$summary (attempt ${attempt + 1})") { if (!act()) error("Action returned false") }.isSuccess
            if (acted) verify().takeIf { it.verified }?.let { return it }
        }
        val retryDescription = if (retrySafe) "after one safe retry" else "without retrying the possibly completed side effect"
        backend.log(module, action, platform, "Verification failed $retryDescription: $summary", false, error = "ActionVerifier exhausted $attempts attempt(s)")
        return VerificationResult(false, "Action or verification failed $retryDescription")
    }

    suspend fun <T> executeLogged(module: String, action: String, platform: String, summary: String, act: suspend () -> T): Result<T> {
        backend.log(module, action, platform, "PENDING: $summary", success = false)
        return runCatching { act() }.also { result ->
            // Typed redacted diagnostic only: never stack traces or raw exception text.
            backend.log(module, action, platform, if (result.isSuccess) summary else "Failed: $summary", result.isSuccess,
                error = co.sanaa.agent.core.Redactor.safeDiagnostic(result.exceptionOrNull()).ifBlank { null })
        }
    }
}
