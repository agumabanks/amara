package co.sanaa.agent.actions

import co.sanaa.agent.modules.AmaraAdSpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AmaraCreativeLibraryTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun separatePostsReuseArtworkAndChangedPricesGetNewFiles() {
        val library=temp.newFolder();val posts=temp.newFolder()
        val ad=AmaraAdSpec("Date Stamp","UGX 85,000",null,"Sanaa Media")
        var renders=0
        fun creative(spec:AmaraAdSpec)=AmaraCreativeLibrary.prepare(library,"https://soko24.co/item.jpg",spec) { renders++;byteArrayOf(renders.toByte()) }
        val first=BoundTikTokMedia.prepare(posts,"post-one",extension="mp4") { creative(ad).readBytes() }
        val second=BoundTikTokMedia.prepare(posts,"post-two",extension="mp4") { creative(ad).readBytes() }
        assertEquals(first,second);assertEquals(1,renders)
        assertNotEquals(first.readBytes().toList(),creative(ad.copy(price="UGX 90,000")).readBytes().toList())
        assertEquals(2,renders)
        assertEquals("jpg",creative(ad.copy(format="photo")).extension)
    }
}
