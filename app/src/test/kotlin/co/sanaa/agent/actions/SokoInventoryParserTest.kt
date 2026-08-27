package co.sanaa.agent.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SokoInventoryParserTest {
    @Test fun parsesCombinedSokoProductCards() {
        val items = SokoInventoryParser.parse(
            listOf(
                "120,000 /= Thermal Mini Printer In stock",
                "75,000 /= Self-Inking Dater Stamp R-532D In stock",
                "Point of Sale",
            ),
        )
        assertEquals(2, items.size)
        assertEquals("Thermal Mini Printer", items[0].name)
        assertEquals(120_000L, items[0].priceUgx)
        assertEquals("In stock", items[0].stockState)
    }

    @Test fun flagsMissingPriceWithoutInventingOne() {
        val item = SokoInventoryParser.parse(listOf("Custom Business Stamp In stock")).single()
        assertEquals(null, item.priceUgx)
        assertTrue(item.weakReasons.contains("price is not shown"))
    }

    @Test fun deduplicatesCardsByNormalizedTitle() {
        val items = SokoInventoryParser.parse(
            listOf(
                "12,000 /= Id Card Holder In stock",
                "12,000 /= ID CARD HOLDER In stock",
            ),
        )
        assertEquals(1, items.size)
    }

    @Test fun parsesLaterCardsThatExposeTitleBeforePriceWithoutStockBadge() {
        val item = SokoInventoryParser.parse(
            listOf("Custom Payment Voucher Books – A4 | 2-Part NCR | 200 Pages | Custom Printed | Kampala\n80,000 /="),
        ).single()
        assertEquals("Custom Payment Voucher Books – A4 | 2-Part NCR | 200 Pages | Custom Printed | Kampala", item.name)
        assertEquals(80_000L, item.priceUgx)
        assertEquals("Stock not shown", item.stockState)
    }
}
