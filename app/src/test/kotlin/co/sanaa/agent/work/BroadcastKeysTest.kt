package co.sanaa.agent.work

import co.sanaa.agent.core.work.BroadcastKeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Day-scoped broadcast keys: identical within a day+content, distinct across days. */
class BroadcastKeysTest {

    @Test fun sameDaySameContentCollidesForExactlyOnce() {
        assertEquals(BroadcastKeys.status("2026-08-23", "Good morning!"), BroadcastKeys.status("2026-08-23", "Good morning!"))
        assertEquals(BroadcastKeys.group("2026-08-23", "Sanaa Office", "Hello"), BroadcastKeys.group("2026-08-23", "Sanaa Office", "Hello"))
        assertEquals(BroadcastKeys.tiktok("2026-08-23", "listing-1", "cap"), BroadcastKeys.tiktok("2026-08-23", "listing-1", "cap"))
    }

    @Test fun differentDayOrContentOrGroupMintsFreshKey() {
        assertNotEquals(BroadcastKeys.status("2026-08-23", "m"), BroadcastKeys.status("2026-08-24", "m"))
        assertNotEquals(BroadcastKeys.group("2026-08-23", "A", "m"), BroadcastKeys.group("2026-08-23", "B", "m"))
        assertNotEquals(BroadcastKeys.tiktok("d", "l1", "c"), BroadcastKeys.tiktok("d", "l2", "c"))
    }
}
