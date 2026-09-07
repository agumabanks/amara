package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GroupPromotionContentTest {
    private fun item(service: Boolean = false) = SokoListing("7", "Office chair", "<p>Blue fabric, adjustable height.</p>", 120000, "Furniture", 1, 0, 2,
        "https://soko24.co/uploads/chair.jpg", JSONObject().put("slug", "office-chair").put("offering_type", if (service) "SERVICE" else "PRODUCT"))
    @Test fun photoAndRichFactsRemainBoundToTheSameOffering() {
        val post = GroupPromotionContent.from(item())!!
        assertEquals(item().imageUrl, post.imageUrl)
        assertTrue(post.caption.contains("Blue fabric, adjustable height."))
        assertTrue(post.caption.contains("120000"))
        assertTrue(post.caption.contains("https://soko24.co/product/office-chair"))
        assertFalse(post.caption.contains("<p>"))
        assertTrue(post.caption.contains("confirm availability"))
    }
    @Test fun serviceCaptionAsksForScopeAndPreservesServiceRoute() {
        val post = GroupPromotionContent.from(item(true))!!
        assertTrue(post.caption.contains("/service/"))
        assertTrue(post.caption.contains("scope and quote"))
        assertFalse(post.caption.contains("confirm availability and delivery"))
    }
    @Test fun missingMediaNeverCreatesTextOnlyPromotion() {
        assertNull(GroupPromotionContent.from(item().copy(imageUrl = null)))
    }
}
