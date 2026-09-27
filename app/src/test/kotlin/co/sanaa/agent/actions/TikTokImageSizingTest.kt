package co.sanaa.agent.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TikTokImageSizingTest {
    @Test fun commonLargeProductPhotosAreDownsampledBeforeDecode() {
        assertEquals(1, TikTokImageSizing.sampleSize(3000, 3000))
        assertEquals(2, TikTokImageSizing.sampleSize(8000, 6000))
        assertEquals(4, TikTokImageSizing.sampleSize(12000, 9000))
    }

    @Test fun corruptOrExtremeDimensionsAreRejectedWithoutAllocatingBitmap() {
        assertThrows(IllegalArgumentException::class.java) { TikTokImageSizing.sampleSize(-1, 4000) }
        assertThrows(IllegalArgumentException::class.java) { TikTokImageSizing.sampleSize(20000, 20000) }
    }
}
