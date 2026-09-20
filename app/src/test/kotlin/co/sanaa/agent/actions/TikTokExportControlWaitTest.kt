package co.sanaa.agent.actions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TikTokExportControlWaitTest {
    @Test fun delayedSheetIsTappedOnceWhenReady() = runBlocking {
        var polls = 0
        var dispatched = 0
        val result = TikTokExportControlWait.awaitDownload({ true }, { TikTokSoundSelection.PACKAGE },
            { if (polls >= 4) { dispatched++; true } else false }, { polls++ })
        assertTrue(result)
        assertEquals(4, polls)
        assertEquals(1, dispatched)
    }
    @Test fun foreignPackageStopsWithoutTouchingItsControls() = runBlocking {
        var taps = 0
        assertFalse(TikTokExportControlWait.awaitDownload({ true }, { "com.android.settings" },
            { taps++; true }, {}))
        assertEquals(0, taps)
    }
    @Test fun ownerPauseStopsPendingWait() = runBlocking {
        var pauses = 0
        var taps = 0
        assertFalse(TikTokExportControlWait.awaitDownload({ pauses < 2 }, { TikTokSoundSelection.PACKAGE },
            { taps++; false }, { pauses++ }))
        assertEquals(2, taps)
    }
    @Test fun missingWindowIsBoundedAndNeverTapped() = runBlocking {
        var pauses = 0
        var taps = 0
        assertFalse(TikTokExportControlWait.awaitDownload({ true }, { "" },
            { taps++; true }, { pauses++ }))
        assertEquals(24, pauses)
        assertEquals(0, taps)
    }
}
