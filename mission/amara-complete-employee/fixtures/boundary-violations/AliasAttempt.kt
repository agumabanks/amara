// NEGATIVE FIXTURE — alias/delegate attempt. Must not compile: postTikTok is private.
object AliasHolder { val target: co.sanaa.agent.actions.AccessibilityActions? = null }
suspend fun aliasPost(url: String, caption: String): Boolean =
    AliasHolder.target!!.postTikTok(url, caption, publish = true)
