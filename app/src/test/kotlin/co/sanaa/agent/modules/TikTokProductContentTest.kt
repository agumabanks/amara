package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TikTokProductContentTest {
    private fun listing() = SokoListing("7", "Office chair", "Blue fabric", 120000, "Furniture", 1, 0, 2,
        "https://soko24.co/uploads/chair.jpg", JSONObject().put("slug", "office-chair-7"))

    @Test fun sameProductSuppliesPhotoTitlePriceAndShoppingLink() {
        val content = TikTokProductContent.from(listing())!!
        assertEquals(listing().imageUrl, content.imageUrl)
        assertEquals("https://soko24.co/product/office-chair-7", content.shoppingUrl)
        assertEquals("Office chair — UGX 120000\nShop: ${content.shoppingUrl}\n#soko24 #sokoug", content.caption)
    }

    @Test fun missingOrUnsafeMediaAndSlugsFailClosed() {
        assertNull(TikTokProductContent.from(listing().copy(imageUrl = null)))
        assertNull(TikTokProductContent.from(listing().copy(imageUrl = "file:///private/photo")))
        for (slug in listOf("", "null", "../another", "chair?redirect=other", "chair/other")) {
            assertNull(TikTokProductContent.from(listing().copy(raw = JSONObject().put("slug", slug))))
        }
    }

    @Test fun anyChangedProductContentInvalidatesRetryBinding() {
        val original = TikTokProductContent.from(listing())!!.fingerprint
        for (changed in listOf(listing().copy(title = "Desk"), listing().copy(description = "Red fabric"),
            listing().copy(imageUrl = "https://soko24.co/uploads/desk.jpg"), listing().copy(priceUgx = 42),
            listing().copy(raw = JSONObject().put("slug", "another-product")))) {
            assertNotEquals(original, TikTokProductContent.from(changed)!!.fingerprint)
        }
        assertEquals(original, TikTokProductContent.from(listing())!!.fingerprint)
    }

    @Test fun servicesUseTheirOwnBookingRoute() {
        val service = listing().copy(id = "service:7", raw = JSONObject().put("slug", "design").put("offering_type", "SERVICE"))
        val content = TikTokProductContent.from(service)!!
        assertEquals("https://soko24.co/service/design", content.shoppingUrl)
        assertTrue(content.caption.contains("Book: "))
    }

    @Test fun blankTitleDoesNotPublishGenericAdvertisement() {
        assertNull(TikTokProductContent.from(listing().copy(title = " ")))
    }
}
