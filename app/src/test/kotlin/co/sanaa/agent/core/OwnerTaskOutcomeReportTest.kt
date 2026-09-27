package co.sanaa.agent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerTaskOutcomeReportTest {
    @Test fun partialCatalogScanPreservesVisibleProductsAndNamesTheActualBlocker() {
        val report = OwnerTaskOutcomeReport.incomplete(listOf(
            Triple("scan_soko_inventory", true, "Read 14 unique products including pens and notebooks."),
            Triple("read_soko_dashboard", false, "Could not find Dashboard section"),
        ))
        assertTrue(report.contains("14 unique products"))
        assertTrue(report.contains("Could not find Dashboard section"))
        assertFalse(report.contains("no listings"))
    }

    @Test fun noVerifiedStepDoesNotClaimInventoryIsEmpty() {
        val report = OwnerTaskOutcomeReport.incomplete(listOf(
            Triple("scan_soko_inventory", false, "Terminal login could not be verified"),
        ))
        assertTrue(report.contains("Terminal login could not be verified"))
        assertFalse(report.contains("zero products"))
    }

    @Test fun businessGoalDoesNotTurnUnknownCatalogueIntoAnEmptyOne() {
        val prompt = SystemPromptBuilder.build(BusinessContext(businessName = "Free Line Stationery", approvalThreshold = "UGX 500000"))
        assertTrue(prompt.contains("qualified inquiries, verified sales, and profit"))
        assertTrue(prompt.contains("Catalogue sync status unknown"))
        assertFalse(prompt.contains("No listings synced yet"))
    }
}
