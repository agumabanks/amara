package co.sanaa.agent.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SokoBuyerServiceParserTest {
    @Test fun parsesBuyerServiceCardObservedOnTheOppo() {
        val service = SokoBuyerServiceParser.parse(
            listOf("7-21 days\nBusiness Process Automation Services\nSanaa Media\n5.0\n(64)\n1,800,000/="),
        ).single()
        assertEquals("Business Process Automation Services", service.name)
        assertEquals("Sanaa Media", service.seller)
        assertEquals(1_800_000L, service.priceUgx)
        assertEquals(5.0, service.rating!!, 0.0)
        assertEquals(64, service.reviewCount)
        assertTrue(service.weakReasons.isEmpty())
    }

    @Test fun ignoresBuyerNavigationLabels() {
        assertTrue(SokoBuyerServiceParser.parse(listOf("Services", "Home\nTab 1 of 5")).isEmpty())
    }
}
