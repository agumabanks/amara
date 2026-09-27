package co.sanaa.agent.actions

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TikTokShareIntentTest {
    @Test fun importsBoundMediaInFreshTaskWithoutClearingExistingDrafts() {
        val uri = Uri.parse("content://co.sanaa.agent.files/bound/ad.mp4")
        val intent = TikTokShareIntent.create(uri, "video/mp4", "Exact caption")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("com.zhiliaoapp.musically", intent.`package`)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("Exact caption", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
