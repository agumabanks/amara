package co.sanaa.agent.core.social

/** Own-profile evidence must include owner-only controls and a unique handle. */
object TikTokProfileIdentity {
    /** Feed creators can expose the display name or the handle, with or without @. */
    fun isOwnCreator(creator: String, displayName: String, handle: String): Boolean {
        fun clean(value: String) = java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFC)
            .removePrefix("@").trim()
        val actual = clean(creator)
        return actual.isNotBlank() && listOf(displayName, handle).any {
            clean(it).isNotBlank() && actual.equals(clean(it), ignoreCase = true)
        }
    }

    fun hasOwnerControls(labels: List<String>): Boolean =
        labels.any { it.equals("Edit", true) || it.equals("Edit profile", true) || it.equals("Business Suite", true) } &&
            labels.any { it.equals("Followers", true) }

    fun handle(labels: List<String>): String? {
        if(!hasOwnerControls(labels)) return null
        return labels.distinct().singleOrNull { it.matches(Regex("@[A-Za-z0-9._]{2,40}")) }
    }
}
