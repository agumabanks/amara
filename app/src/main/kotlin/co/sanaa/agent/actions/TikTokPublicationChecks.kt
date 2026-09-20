package co.sanaa.agent.actions

import co.sanaa.agent.core.ContentHashing

internal object TikTokPublicationChecks {
    fun matches(frame: TikTokSoundSelection.Frame?, caption: String): Boolean {
        if (frame == null || frame.packageName != TikTokSoundSelection.PACKAGE) return false
        val nodes = frame.nodes.filter(frame::usable)
        val expected = ContentHashing.normalize(caption)
        if (expected.isBlank() || nodes.any { it.editable }) return false
        val postControls = nodes.any { it.description.startsWith("Share video", true) }
        return postControls && nodes.any {
            !it.editable && ContentHashing.normalize(it.text) == expected
        }
    }
}
