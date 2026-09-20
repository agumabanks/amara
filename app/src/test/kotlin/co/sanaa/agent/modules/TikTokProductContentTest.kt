package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TikTokProductContentTest {
    @Test fun publicShopAddressAddsLocationTagsAndChangesContentBinding() {
        val original = TikTokProductContent.from(listing())!!
        val located = listing().copy(raw = JSONObject().put("slug", "office-chair-7").put("shop_address", "Nasser road Kampala"))
        val content = TikTokProductContent.from(located)!!
        assertTrue(content.caption.endsWith("#soko24 #OfficeChair #SanaaMedia #NasserRoad #Kampala"))
        assertNotEquals(original.fingerprint, content.fingerprint)
        val service = located.copy(raw = JSONObject(located.raw.toString()).put("offering_type", "SERVICE"))
        assertTrue(TikTokProductContent.from(service)!!.caption.endsWith("#NasserRoad #Kampala"))
    }

    private fun listing() = SokoListing("7", "Office chair", "Blue fabric", 120000, "Furniture", 1, 0, 2,
        "https://soko24.co/uploads/chair.jpg", JSONObject().put("slug", "office-chair-7"))

    @Test fun sameProductSuppliesPhotoTitlePriceAndShoppingLink() {
        val content = TikTokProductContent.from(listing())!!
        assertEquals(listing().imageUrl, content.imageUrl)
        assertEquals("https://soko24.co/product/office-chair-7", content.shoppingUrl)
        assertTrue(content.caption.contains("Office chair — UGX 120,000"))
        assertTrue(content.caption.contains("Blue fabric"))
        assertTrue(content.caption.contains("desk"))
        assertFalse(content.caption.contains("Take a closer look"))
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
    @Test fun publicWhatsAppSurvivesVideoSpecSerializationAndChangesCreativeIdentity() {
        val video = listing().copy(raw = JSONObject(listing().raw.toString())
            .put("gallery_urls", org.json.JSONArray(listOf(listing().imageUrl, "https://soko24.co/detail.jpg"))))
        val without = TikTokProductContent.from(video)!!
        val withContact = TikTokProductContent.from(video, "+256706272481")!!
        assertEquals("video", withContact.ad.format)
        assertEquals("+256706272481", AmaraAdSpec.fromJson(withContact.ad.toJson()).whatsapp)
        assertTrue(withContact.caption.contains("WhatsApp: +256706272481"))
        assertNotEquals(without.fingerprint, withContact.fingerprint)
        assertEquals(withContact.fingerprint, TikTokProductContent.from(video, "+256706272481")!!.fingerprint)
    }

}
