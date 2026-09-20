package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AmaraAdSpecTest {
    @Test fun formatFollowsAvailableContentAndSavedSpecSurvivesRestart() {
        val single=AmaraAdSpec.from(item())
        assertEquals("photo",single.format)
        val gallery=item().apply { raw.put("gallery_urls",org.json.JSONArray(listOf(imageUrl,"https://soko24.co/detail.jpg"))) }
        val motion=AmaraAdSpec.from(gallery)
        assertEquals("video",motion.format)
        assertEquals(motion,AmaraAdSpec.fromJson(motion.toJson()))
        val service=item().apply { raw.put("offering_type","SERVICE") }
        assertEquals("video",AmaraAdSpec.from(service).format)
    }

    private fun item() = SokoListing("7", "Office Chair Deluxe", "", 120000, "Furniture", 1, 0, 2,
        "https://soko24.co/chair.jpg", JSONObject().put("slug", "chair"))
    @Test fun headlineAndContactAreGroundedAndContactChangesInvalidateBinding() {
        val listing = item()
        val first = TikTokProductContent.from(listing, "+256700000001")!!
        assertEquals("Office Chair", first.ad.headline)
        assertEquals("+256700000001", first.ad.whatsapp)
        assertTrue(first.caption.contains(first.ad.whatsapp!!))
        assertNotEquals(first.fingerprint, TikTokProductContent.from(listing, "+256700000002")!!.fingerprint)
        listing.raw.put("whatsapp_number", "0700000003")
        assertEquals("+256700000003", AmaraAdSpec.from(listing, "+256700000001").whatsapp)
    }
    @Test fun recognisedProductNameIsClearerThanBrandAndModel() {
        assertEquals("Date Stamp", AmaraAdSpec.from(item().copy(title = "Shiny Printer R-532D Self-Inking Round Date Stamp")).headline)
        assertEquals("Logo Design", AmaraAdSpec.from(item().copy(title = "Premium Logo Design Package")).headline)
    }
    @Test fun galleryUrlsStayBoundToItemAndRejectUploadIds() {
        val listing=item();listing.raw.put("gallery_urls", org.json.JSONArray(listOf("123", "file:///private", "https://soko24.co/detail.jpg", listing.imageUrl)))
        val spec=AmaraAdSpec.from(listing)
        assertEquals(listOf(listing.imageUrl,"https://soko24.co/detail.jpg"),spec.gallery)
        val before=TikTokProductContent.from(listing)!!.fingerprint
        listing.raw.put("gallery_urls",org.json.JSONArray(listOf("https://soko24.co/new.jpg")))
        assertNotEquals(before,TikTokProductContent.from(listing)!!.fingerprint)
    }
    @Test fun missingContactIsNotInventedAndMalformedContactCannotBecomeCta() {
        assertNull(AmaraAdSpec.from(item()).whatsapp)
        for (phone in listOf("null", "123", "+000000000", "call 0700000001", "https://evil.test/0700000001"))
            assertNull(AmaraAdSpec.phone(phone))
    }
    @Test fun serviceBasePriceAndUnknownPriceCannotBecomeFixedOrFreeOffers() {
        val listing = item(); listing.raw.put("offering_type", "SERVICE")
        assertEquals("From UGX 120,000", AmaraAdSpec.price(listing))
        listing.raw.put("pricing_type", "fixed")
        assertEquals("UGX 120,000", AmaraAdSpec.price(listing))
        assertEquals("Ask for a quote", AmaraAdSpec.price(listing.copy(priceUgx = 0)))
        assertFalse(GroupPromotionContent.from(listing.copy(priceUgx = 0))!!.caption.contains("UGX 0"))
    }
}
