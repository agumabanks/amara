package co.sanaa.agent.modules

import org.junit.Assert.*
import org.junit.Test

class SokoShopReportTest {
    @Test fun failedSectionPreventsFalseOverallSuccess() {
        val report = SokoShopReport(listOf(
            SokoSectionReport("Dashboard", "Read dashboard", listOf("Sales")),
            SokoSectionReport("Products", "Inventory unavailable", emptyList(), "Login failed"),
        ))
        assertFalse(report.success)
        assertTrue(report.summary.contains("Inventory unavailable"))
    }
    @Test fun emptyReportIsNotSuccess() { assertFalse(SokoShopReport(emptyList()).success) }
    @Test fun allVerifiedSectionsCanSucceed() {
        assertTrue(SokoShopReport(listOf(SokoSectionReport("Products", "Read one product", listOf("Printer")))).success)
    }
}
