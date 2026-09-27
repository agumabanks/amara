package co.sanaa.agent.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TikTokPhotoSelectionTest {
    @Test fun cancellationStopsGalleryFallbackImmediately() {
        var attempts = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            TikTokPhotoSelection.decode("primary", listOf("second", "third"), 2) {
                attempts++
                throw java.util.concurrent.CancellationException("Task expired")
            }
        }
        assertEquals(1, attempts)
    }
    @Test fun unsupportedPrimaryUsesSameListingsGallery() {
        val tried = mutableListOf<String>()
        val result = TikTokPhotoSelection.decode("primary.avif", listOf("primary.avif", "gallery.webp", "gallery.jpg"), 2) {
            tried += it
            if (it == "primary.avif") throw IllegalArgumentException("Unsupported image")
            it
        }
        assertEquals(listOf("gallery.webp", "gallery.jpg"), result)
        assertEquals(listOf("primary.avif", "gallery.webp", "gallery.jpg"), tried)
    }

    @Test fun noDecodableImageStopsBeforePublication() {
        assertThrows(IllegalStateException::class.java) {
            TikTokPhotoSelection.decode("primary.avif", emptyList(), 1) { throw IllegalArgumentException("Unsupported") }
        }
    }
}
