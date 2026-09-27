package co.sanaa.agent.actions

/**
 * Pure dispatch-guard evaluation for the final YouTube Shorts upload step.
 *
 * Captured release39 evidence: the authorized upload task reached ACTING then FAILED
 * 2879 ms later with "external trigger was never dispatched" because the dispatch
 * guards were false after [co.sanaa.agent.core.shorts.ShortsDeviceSurface.prepare]
 * had already filled the description. After returning from the description editor the
 * row shows the entered text (or a truncated preview), not the `Add description`
 * placeholder, so the old placeholder tap path found nothing and blocked the final
 * dispatch. These guards keep every verification gate while making that state
 * skippable, and give each rejection a distinguishable, privacy-safe reason.
 */
internal object ShortsDispatchGuards {
    const val WINDOW_NOT_READY = "window_not_ready"
    const val DETAILS_NOT_VISIBLE = "details_screen_not_visible"
    const val CHANNEL_NOT_VISIBLE = "channel_not_visible"
    const val DESCRIPTION_CONTROL = "description_control"
    const val DESCRIPTION_TEXT = "description_text"
    const val DESCRIPTION_NOT_OBSERVABLE = "description_not_observable"
    const val EDITOR_CLOSE = "editor_close"
    const val SHORTS_DISABLED = "shorts_disabled"
    const val SOUNDTRACK_NOT_CLEARED = "soundtrack_not_cleared"
    const val CHANNEL_CHANGED = "channel_changed"
    const val VISIBILITY_NOT_VISIBLE = "visibility_not_visible"
    const val AUDIENCE_MISMATCH = "audience_mismatch"
    const val TITLE_DRIFT = "title_drift"
    const val OWNER_OFF = "owner_off"
    const val UPLOAD_CONTROL = "upload_control"

    fun onDetailsScreen(labels: List<String>): Boolean = "Add details" in labels

    fun channelVisible(labels: List<String>, channel: String): Boolean =
        labels.any { it.equals(channel, true) }

    fun descriptionPlaceholder(labels: List<String>): Boolean = "Add description" in labels

    /** The filled description as its own line, or a uniquely matched truncated preview. */
    fun descriptionVisible(labels: List<String>, description: String): Boolean {
        if (description.isBlank()) return false
        return labels.any { it == description } ||
            labels.any { it.startsWith(description.take(40), true) }
    }

    fun visibilityVisible(labels: List<String>, visibility: String): Boolean = visibility in labels

    fun audienceMatches(labels: List<String>, madeForKids: Boolean): Boolean = labels.any {
        if (madeForKids) it.equals("Yes, it's made for kids", true) || it.equals("Made for kids", true)
        else it.contains("not made for kids", true)
    }

    fun titleFilled(fields: List<String>, title: String): Boolean =
        fields.singleOrNull() == title.take(100)

    /** Byte-equality check for the single editable field, e.g. the description editor. */
    fun singleFieldEquals(fields: List<String>, value: String): Boolean =
        fields.singleOrNull() == value

    /** Exact read-back from YouTube's newest own-channel video surface. */
    fun publicationVisible(labels: List<String>, title: String, channel: String, visibility: String): Boolean =
        labels.any { it.equals(title.take(100), true) } &&
            labels.any { it.equals(channel, true) || it.equals("Go to channel $channel", true) } &&
            labels.any { it.equals(visibility, true) }
}
