package co.sanaa.agent.actions

/** Wait for the verified post's share sheet without reopening it or dispatching twice. */
internal object TikTokExportControlWait {
    suspend fun awaitDownload(
        allowed: () -> Boolean,
        packageName: () -> String,
        tapDownload: () -> Boolean,
        pause: suspend () -> Unit,
    ): Boolean {
        repeat(24) {
            if (!allowed()) return false
            val active = packageName()
            if (active.isNotBlank() && active != TikTokSoundSelection.PACKAGE) return false
            if (active == TikTokSoundSelection.PACKAGE && tapDownload()) return true
            pause()
        }
        return false
    }
}
