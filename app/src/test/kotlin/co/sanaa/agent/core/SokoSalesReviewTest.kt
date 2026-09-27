package co.sanaa.agent.core

import co.sanaa.agent.actions.SokoInventoryItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SokoSalesReviewTest {
    @Test fun threeActionsUseObservedProductsAndDoNotInventSalesOrMargin() {
        val items = listOf(
            SokoInventoryItem("Metal Stapler", 100_000, "100000", "In stock", "", emptyList()),
            SokoInventoryItem("Bic Pen", 500, "500", "In stock", "", emptyList()),
            SokoInventoryItem("Weak Notebook", 15_000, "15000", "In stock", "", listOf("weak title")),
        )
        val report = SokoSalesReview.report("Free Line Stationery", items, true)
        assertTrue(report.contains("Current signed shop: Free Line Stationery"))
        assertTrue(report.contains("1. Prepare"))
        assertTrue(report.contains("2. Test"))
        assertTrue(report.contains("3. Review"))
        assertTrue(report.contains("Metal Stapler (UGX 100000, In stock)"))
        assertTrue(report.contains("Bic Pen (UGX 500, In stock)"))
        assertTrue(report.contains("Nothing was published, sent or edited"))
        assertFalse(report.contains("made a sale"))
    }

    @Test fun noVerifiedStockDoesNotRecommendAnUnverifiedProduct() {
        val report = SokoSalesReview.report("Current Shop", listOf(
            SokoInventoryItem("Old listing", 5_000, "5000", "Not in stock", "", emptyList()),
        ), false)
        assertTrue(report.contains("could not verify an in-stock product"))
        assertFalse(report.contains("1. Prepare"))
    }
}
