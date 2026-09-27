package co.sanaa.agent.actions

import kotlinx.coroutines.delay

/** Import can briefly expose an empty TikTok window before editor controls load. */
internal object TikTokImportWait {
    suspend fun await(
        observePackage: () -> String,
        allowed: () -> Boolean,
        pause: suspend () -> Unit = { delay(500) },
        attempts: Int = 60,
        transientPackage: String = "",
        transientAttempts: Int = 12,
    ): Boolean {
        repeat(attempts) { attempt ->
            if (!allowed()) return false
            when (observePackage()) {
                TikTokSoundSelection.PACKAGE -> return true
                "", "co.sanaa.agent" -> Unit
                transientPackage -> if (transientPackage.isBlank() || attempt >= transientAttempts) return false
                else -> return false
            }
            pause()
        }
        return false
    }
}
