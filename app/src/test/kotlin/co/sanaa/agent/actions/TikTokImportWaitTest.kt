package co.sanaa.agent.actions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TikTokImportWaitTest {
    @Test fun emptyEditorWindowBelongsToTikTokAndCanEnterComposerWait() = runBlocking {
        assertTrue(TikTokImportWait.await({ TikTokSoundSelection.PACKAGE }, { true }, { error("Already present") }))
    }
    @Test fun importMayTakeMoreThanTenSecondsWithoutRelaunching() = runBlocking {
        var polls = 0
        assertTrue(TikTokImportWait.await({ if (++polls < 25) "" else TikTokSoundSelection.PACKAGE }, { true }, {}))
        assertEquals(25, polls)
    }
    @Test fun ownerPauseOrAnotherAppStopsImport() = runBlocking {
        assertFalse(TikTokImportWait.await({ TikTokSoundSelection.PACKAGE }, { false }, {}))
        assertFalse(TikTokImportWait.await({ "com.whatsapp" }, { true }, {}))
    }
    @Test fun absentWindowHasABoundedWait() = runBlocking {
        var polls = 0
        assertFalse(TikTokImportWait.await({ polls++; "" }, { true }, {}, attempts = 3))
        assertEquals(3, polls)
    }
}
