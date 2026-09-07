package co.sanaa.agent.actions

import org.junit.Assert.*
import org.junit.Test

class TikTokComposerChecksTest {
    @Test fun photoComposerDescriptionIsSelectedWhileTitleIsExcluded() {
        assertTrue(TikTokComposerChecks.isCaptionField("Writing a long description can help get 3x more views on average.", "", "", "Chair"))
        assertFalse(TikTokComposerChecks.isCaptionField("Add a catchy title", "", "", "Chair"))
    }

    @Test fun descriptionEditorAndImportedCaptionAreAccepted() {
        assertTrue(TikTokComposerChecks.isCaptionField("", "Add description", "", "Chair"))
        assertTrue(TikTokComposerChecks.isCaptionField("Chair", "", "", "Chair"))
    }
    @Test fun unrelatedOrSearchEditorsAreRejectedEvenWithMatchingText() {
        assertFalse(TikTokComposerChecks.isCaptionField("", "", "", "Chair"))
        assertFalse(TikTokComposerChecks.isCaptionField("Chair", "Search hashtags", "", "Chair"))
        assertFalse(TikTokComposerChecks.isCaptionField("", "Search", "Add description", "Chair"))
    }
}
