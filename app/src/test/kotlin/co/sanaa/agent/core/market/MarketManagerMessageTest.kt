package co.sanaa.agent.core.market

import co.sanaa.agent.core.growth.MarketGrowthReview
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class MarketManagerMessageTest {
    @Test fun reportIncludesOwnPriceAndEvidenceWithoutInventingARecommendation() {
        val rows = JSONArray().put(JSONObject().put("title","Printing service").put("ownPriceUgx",500)
            .put("comparableOffers",2))
        val body = MarketGrowthReview.managerMessage(JSONObject().put("comparisons",rows))
        assertTrue(body.contains("ours UGX 500"))
        assertTrue(body.contains("insufficient price evidence"))
        assertTrue(body.contains("Prices stay unchanged"))
    }
    @Test fun largeCataloguePreservesDecisionInstructionsAndDeliveryLimit() {
        val rows = JSONArray()
        repeat(100) { rows.put(JSONObject().put("title","Office printing service ".repeat(8))
            .put("ownPriceUgx",500).put("comparableOffers",4).put("marketMinimumUgx",400)
            .put("marketMaximumUgx",600).put("marketAverageUgx",500)) }
        val body = MarketGrowthReview.managerMessage(JSONObject().put("comparisons",rows))
        assertTrue(body.length <= 1600)
        assertTrue(body.contains("average 500"))
        assertTrue(body.endsWith("save verified."))
    }
}
