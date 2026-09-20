package co.sanaa.agent.modules

/** Only a fresh explicit TikTok success message can confirm this serialized dispatch. */
object TikTokStoryConfirmation {
    @Volatile var latest: Pair<Long,String>? = null
        private set
    fun isConfirmation(text: String): Boolean = text.lineSequence().any {
        it.trim().lowercase() in setOf("story posted", "your story was posted", "your story has been posted", "posted to your story", "your story is posted")
    }
    fun observe(packageName: String, text: String, at: Long) {
        if (packageName == "com.zhiliaoapp.musically" && isConfirmation(text)) latest = at to text
    }
}
