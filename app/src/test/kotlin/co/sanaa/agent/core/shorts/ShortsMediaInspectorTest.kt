package co.sanaa.agent.core.shorts

import org.junit.Assert.*
import org.junit.Test

class ShortsMediaInspectorTest {
    @Test fun downloadedTikTokWithShorterAudioNeedsDurationRepair() {
        val tracks = ShortsMediaInspector.Tracks(12_000_000, 10_213_878, 720, 1280)
        assertTrue(tracks.usable)
        assertTrue(tracks.audioEndsEarly)
    }
    @Test fun silentOrLandscapeExportsAreNotReadyForThisShortsWorkflow() {
        assertFalse(ShortsMediaInspector.Tracks(12_000_000, 0, 720, 1280).usable)
        assertFalse(ShortsMediaInspector.Tracks(12_000_000, 12_000_000, 1280, 720).usable)
        assertFalse(ShortsMediaInspector.Tracks(0, 12_000_000, 720, 1280).usable)
        assertFalse(ShortsMediaInspector.Tracks(12_000_000, 12_000_000, 720, 1280).audioEndsEarly)
    }
}
