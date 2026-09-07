package co.sanaa.agent.actions

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoundTikTokMediaTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun retriesUseFrozenBytesAndNewJobsMayFetchChangedMedia() {
        val directory = temp.newFolder()
        val first = BoundTikTokMedia.prepare(directory, "job1") { byteArrayOf(1, 2, 3) }
        assertEquals(first, BoundTikTokMedia.prepare(directory, "job1") { error("Must not re-download") })
        val next = BoundTikTokMedia.prepare(directory, "job2") { byteArrayOf(3, 2, 1) }
        assertNotEquals(first, next)
    }

    @Test fun tamperedMediaFailsClosed() {
        val directory = temp.newFolder()
        val first = BoundTikTokMedia.prepare(directory, "job") { byteArrayOf(1, 2) }
        first.writeBytes(byteArrayOf(4, 5))
        assertThrows(IllegalStateException::class.java) {
            BoundTikTokMedia.prepare(directory, "job") { error("No silent replacement") }
        }
    }

    @Test fun missingMediaFailsClosedAcrossRestart() {
        val directory = temp.newFolder()
        BoundTikTokMedia.prepare(directory, "job") { byteArrayOf(1) }.delete()
        assertThrows(IllegalStateException::class.java) { BoundTikTokMedia.prepare(directory, "job") { byteArrayOf(2) } }
    }

    @Test fun hashesPreserveCaseAndBytes() {
        assertNotEquals(BoundTikTokMedia.sha256("A".toByteArray()), BoundTikTokMedia.sha256("a".toByteArray()))
    }
}
