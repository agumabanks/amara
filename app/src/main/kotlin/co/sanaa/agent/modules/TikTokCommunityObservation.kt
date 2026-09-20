package co.sanaa.agent.modules

/** A failed read is not evidence that the feed contains no relevant posts. */
internal object TikTokCommunityObservation {
    fun blocker(observed: Int, outcome: String): String? =
        if (observed == 0 && outcome == "NO_RELEVANT_POST")
            "TikTok post captions could not be read; community discovery is unverified"
        else null
}
