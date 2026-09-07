package co.sanaa.agent.core.growth

import co.sanaa.agent.api.SokoListing
import co.sanaa.agent.core.market.MarketEvidence
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GrowthPolicyTest {
    private fun listing(id: String, service: Boolean = false) = SokoListing(id, "Office chair", "Blue fabric", 120000, "Furniture", 1, 0, 2,
        "https://soko24.co/uploads/chair.jpg", JSONObject().put("slug", "office-chair-$id").put("offering_type", if (service) "SERVICE" else "PRODUCT"))
    @Test fun evidencedDemandWinsWithinRotationWhenNotExploring() {
        assertEquals("2",GrowthStore.select(listOf(listing("1"),listing("2")),emptyList(),mapOf("2" to 5)) { 1 }!!.id)
        // Unseen products still get a turn; demand does not permit endless repetition.
        assertEquals("1",GrowthStore.select(listOf(listing("1"),listing("2")),listOf(GrowthStore.Promotion("2","PRODUCT")),mapOf("2" to 5)) { 1 }!!.id)
    }
    @Test fun rotatesToUnseenServiceAfterProduct() {
        val choices = listOf(listing("1"), listing("2"), listing("3", true))
        assertEquals("3", GrowthStore.select(choices, listOf(GrowthStore.Promotion("1", "PRODUCT"))) { 0 }!!.id)
    }
    @Test fun exhaustsUnseenBeforeRecyclingAndAvoidsImmediateRepeat() {
        val choices = listOf(listing("1"), listing("2"), listing("3"))
        val history = listOf(GrowthStore.Promotion("2", "PRODUCT"), GrowthStore.Promotion("1", "PRODUCT"))
        assertEquals("3", GrowthStore.select(choices, history) { 0 }!!.id)
        assertEquals("1", GrowthStore.select(choices, listOf(GrowthStore.Promotion("3", "PRODUCT")) + history) { 0 }!!.id)
    }
    @Test fun unavailableOrInvalidOfferingsNeverEnterRotation() {
        assertNull(GrowthStore.select(listOf(listing("1").copy(stock = 0), listing("2").copy(imageUrl = null)), emptyList()))
        assertEquals("1", GrowthStore.select(listOf(listing("1")), listOf(GrowthStore.Promotion("1", "PRODUCT")))!!.id)
    }
    @Test fun rejectsDifferentModelsConditionsAndBroadCategoryMatches() {
        assertFalse(MarketEvidence.comparable("Samsung Galaxy S23", "Samsung Galaxy S24"))
        assertFalse(MarketEvidence.comparable("Samsung Galaxy S23", "Used Samsung Galaxy S23"))
        assertFalse(MarketEvidence.comparable("Office chair", "Office printer"))
        assertTrue(MarketEvidence.comparable("Samsung Galaxy S23", "New Samsung Galaxy S23 Kampala"))
    }
    @Test fun cleanupKeepsExistingFacts() {
        assertEquals("Blue fabric & wood", MarketGrowthReview.plainText("<p>Blue fabric &amp; wood</p>"))
    }
}
