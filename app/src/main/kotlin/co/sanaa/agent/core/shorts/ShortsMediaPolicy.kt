package co.sanaa.agent.core.shorts

/** Cross-post admission is based on the finished post, never its silent source render. */
object ShortsMediaPolicy {
    /** Recover a dispatch interrupted before its queue state was persisted.
     * Changing the configured destination must not authorize a second upload. */
    fun priorDispatch(source: String, receipts: List<co.sanaa.agent.core.SideEffectTransaction>) = receipts.firstOrNull {
        it.capability == co.sanaa.agent.core.CapabilityIds.POST_YOUTUBE_SHORT &&
            (it.idempotencyKey.startsWith("youtube:$source:") ||
                it.idempotencyKey.startsWith(co.sanaa.agent.core.Redactor.redact("youtube:$source:"))) && it.state.noAutoRetry
    }
    data class Evidence(
        val sourceVerified: Boolean,
        val sourceShop: String,
        val currentShop: String,
        val expectedCaption: String,
        val observedCaption: String,
        val soundtrackPresent: Boolean,
        val soundtrackCleared: Boolean,
        val selectedChannel: String,
        val observedChannel: String,
        val mediaSha256: String,
    )

    fun blocker(e: Evidence): String? = when {
        !e.sourceVerified -> "The TikTok source post is not verified"
        e.sourceShop.isBlank() || e.sourceShop != e.currentShop -> "The source ad belongs to a different shop"
        e.expectedCaption.isBlank() || normalize(e.expectedCaption) != normalize(e.observedCaption) ->
            "The saved video is not bound to the exact TikTok ad"
        !e.soundtrackPresent -> "The finished video has no audio track; do not substitute the silent original"
        !e.soundtrackCleared -> "Confirm permission to reuse this soundtrack on YouTube"
        !validHandle(e.selectedChannel) || !e.selectedChannel.equals(e.observedChannel, true) ->
            "The signed-in YouTube channel does not match the selected destination"
        !e.mediaSha256.matches(Regex("[a-f0-9]{64}")) -> "The saved video's content digest is missing"
        else -> null
    }

    fun validHandle(handle: String): Boolean = handle.startsWith("@") &&
        handle.length in 4..101 && handle.none { it.isWhitespace() || it in "/\\?#" }

    private fun normalize(value: String) = value.trim().replace(Regex("\\s+"), " ")
}
