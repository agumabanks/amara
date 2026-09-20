package co.sanaa.agent.actions

import org.junit.Assert.*
import org.junit.Test

class TikTokPublicationChecksTest {
    private val bounds = TikTokSoundSelection.Bounds(0, 0, 720, 1600)
    private val pkg = TikTokSoundSelection.PACKAGE
    private fun frame(editable: Boolean = false, share: Boolean = true, text: String = "Exact full ad caption") =
        TikTokSoundSelection.Frame(pkg, 1, bounds, listOf(
            TikTokSoundSelection.Node("0/0", "0", pkg, 1, "new_caption_id", "TextView", text, "", bounds, editable = editable),
            TikTokSoundSelection.Node("0/1", "0", pkg, 1, "new_share_id", "Button", "", if (share) "Share video" else "Post", bounds)))
    @Test fun exactPublishedCaptionSurvivesRenamedIds() {
        assertTrue(TikTokPublicationChecks.matches(frame(), "Exact full ad caption"))
    }
    @Test fun composerAndTruncatedCaptionCannotProvePublication() {
        assertFalse(TikTokPublicationChecks.matches(frame(editable = true), "Exact full ad caption"))
        assertFalse(TikTokPublicationChecks.matches(frame(share = false), "Exact full ad caption"))
        assertFalse(TikTokPublicationChecks.matches(frame(text = "Exact full ad"), "Exact full ad caption"))
        assertFalse(TikTokPublicationChecks.matches(frame().copy(packageName = "another.app"), "Exact full ad caption"))
    }
}
