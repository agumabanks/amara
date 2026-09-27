package co.sanaa.agent.actions

/** Captions from observed expanded TikTok layouts; truncated previews are not posts. */
internal object TikTokSocialText {
    private fun complete(text: String): String? = text.trim().takeIf {
        // Short captions are common on the tablet feed. The creator and post
        // still have to bind separately before any interaction is considered.
        it.length in 8..2200 && !it.endsWith("…") &&
            !it.endsWith("...more", true) && !it.endsWith("…more", true)
    }

    fun caption(values: (String) -> List<String>): String? {
        values("efv").singleOrNull()?.let(::complete)?.let { return it }
        val heading = values("slp").singleOrNull()?.trim().orEmpty()
        val body = values("skr").singleOrNull()?.trim().orEmpty()
        if (body.isNotBlank())
            complete(listOf(heading, body).filter(String::isNotBlank).joinToString("\n"))?.let { return it }

        // TPS450M's feed keeps an expanded caption in desc. Only accept it when
        // TikTok exposes the expanded "less" state (or no toggle for a short,
        // complete caption); a visible "more" state is still only a preview.
        val toggle = values("tv_toggle").singleOrNull()?.trim().orEmpty()
        if (toggle.isNotBlank() && !toggle.equals("less", true)) return null
        return values("desc").singleOrNull()?.let(::complete)
    }

    fun creator(values: (String) -> List<String>): String? =
        values("user_name").singleOrNull() ?: values("k2w").singleOrNull()
            ?: values("title").singleOrNull()
}
