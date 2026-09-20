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
    @Test fun boundVideoCanBeSharedThroughManifestProvider() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        // Each Robolectric test has a new filesDir; attach the manifest provider
        // to this context so AndroidX drops any prior sandbox's cached roots.
        androidx.core.content.FileProvider().attachInfo(context,
            context.packageManager.resolveContentProvider("${context.packageName}.files",
                android.content.pm.PackageManager.GET_META_DATA)!!)
        val directory = java.io.File(context.filesDir, "tiktok-bound-video")
        val video = BoundTikTokMedia.prepare(directory, "video-share", extension = "mp4") { byteArrayOf(1, 2, 3) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", video)
        assertEquals("content", uri.scheme)
        assertTrue(uri.path.orEmpty().startsWith("/bound_tiktok_video/"))
        assertArrayEquals(video.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }

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
