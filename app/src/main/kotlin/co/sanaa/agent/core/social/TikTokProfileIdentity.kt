package co.sanaa.agent.core.social

/** Own-profile evidence must include editing controls and a unique handle. */
object TikTokProfileIdentity {
    fun handle(labels: List<String>): String? {
        if(labels.none { it in setOf("Edit","Edit profile") } || "Followers" !in labels) return null
        return labels.distinct().singleOrNull { it.matches(Regex("@[A-Za-z0-9._]{2,40}")) }
    }
}
