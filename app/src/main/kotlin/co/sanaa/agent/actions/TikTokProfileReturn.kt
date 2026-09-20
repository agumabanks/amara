package co.sanaa.agent.actions

/** Captured expanded photo-post reader has no Profile tab or labelled Back control. */
internal object TikTokProfileReturn {
    fun canLeaveExpandedPhoto(frame: TikTokSoundSelection.Frame?): Boolean {
        if (frame?.packageName != TikTokSoundSelection.PACKAGE) return false
        val nodes = frame.nodes.filter(frame::usable)
        fun has(id: String) = nodes.any { it.id == "${TikTokSoundSelection.PACKAGE}:id/$id" }
        // Do not discard a comment draft or mistake an upload editor for a reader.
        if (nodes.any { it.editable && (it.id != "${TikTokSoundSelection.PACKAGE}:id/sk0" ||
                    it.text !in setOf("", "Add comment...")) }) return false
        return has("skr") && nodes.any { it.id == "${TikTokSoundSelection.PACKAGE}:id/sk7" &&
            it.description == "Share" && it.clickable } && has("sk0")
    }
}
