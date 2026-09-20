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
        assertTrue(post.caption.contains("120,000"))
        assertTrue(post.caption.contains("https://soko24.co/product/office-chair"))
        assertFalse(post.caption.contains("<p>"))
        assertTrue(post.caption.contains("confirm availability"))
    }
    @Test fun serviceCaptionAsksForScopeAndPreservesServiceRoute() {
        val post = GroupPromotionContent.from(item(true))!!
        assertTrue(post.caption.contains("/service/"))
        assertTrue(post.caption.contains("your brief"))
        assertFalse(post.caption.contains("confirm availability and delivery"))
    }
    @Test fun detailIsOptInAndVariantsStayBoundToTheOffer() {
        val listing = item().copy(description = "Adjustable height. " + "Blue fabric with a padded seat. ".repeat(12))
        val brief = GroupPromotionContent.from(listing)!!
        val detail = GroupPromotionContent.from(listing, detailed = true)!!
        assertTrue(detail.caption.length > brief.caption.length)
        assertFalse(brief.caption.contains("padded seat"))
        assertNotEquals(brief.caption, GroupPromotionContent.from(listing, variant = 1)!!.caption)
        assertEquals(brief.imageUrl, detail.imageUrl)
    }
    @Test fun missingMediaNeverCreatesTextOnlyPromotion() {
        assertNull(GroupPromotionContent.from(item().copy(imageUrl = null)))
    }
    @Test fun longHtmlDescriptionKeepsPriceAndLinkReadable() {
        val post=GroupPromotionContent.from(item().copy(description="<p>Customer&#039;s &quot;chair&quot; " + "adjustable comfort ".repeat(80) + "</p>"))!!
        assertTrue(post.caption.contains("Customer's \"chair\""))
        assertFalse(post.caption.contains("&#039;"))
        assertTrue(post.caption.length < 500)
        assertTrue(post.caption.contains("120,000"))
        assertTrue(post.caption.contains("/product/office-chair"))
    }
}
