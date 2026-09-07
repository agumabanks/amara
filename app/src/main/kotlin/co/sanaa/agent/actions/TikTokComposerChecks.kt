package co.sanaa.agent.actions

object TikTokComposerChecks {
    fun isCaptionField(text: String, hint: String, description: String, expectedCaption: String): Boolean {
        val labels = "$hint $description".lowercase()
        if (listOf("search", "mention", "hashtag").any { it in labels }) return false
        return text == expectedCaption || listOf("caption", "description", "describe", "say something")
            .any { it in labels || it in text.lowercase() }
    }
}
