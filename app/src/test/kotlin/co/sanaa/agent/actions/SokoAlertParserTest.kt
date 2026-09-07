package co.sanaa.agent.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class SokoAlertParserTest {
    @Test fun parsesOrdersLowStockAndBookingFromObservedOppoLabels() {
        val alerts = SokoAlertParser.parse(
            listOf(
                "Order 20260802-10265146\nCustomer",
                "Low stock\nShiny Printer R-532D Self-Inking Round Date Stamp • Stock 1 • Reorder at 5",
                "Professional Bulk SMS Services\nsanaa inc",
                "Show menu",
            ),
        )
        assertEquals(listOf("Order", "Low stock", "Booking"), alerts.map(SokoAlert::type))
        assertEquals("Customer", alerts.first().detail)
        assertEquals("Shiny Printer R-532D Self-Inking Round Date Stamp", alerts[1].subject)
    }
}
