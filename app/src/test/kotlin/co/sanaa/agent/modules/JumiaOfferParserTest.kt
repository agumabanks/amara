package co.sanaa.agent.modules

import co.sanaa.agent.core.market.JumiaOfferParser
import org.junit.Assert.*
import org.junit.Test

class JumiaOfferParserTest {
    @Test fun visibleCardRequiresOneUnambiguousPriceAndTitle() {
        assertEquals(45000, JumiaOfferParser.card(listOf("Wireless optical mouse", "UGX 45,000"))!!.price)
        assertEquals("Wireless optical mouse", JumiaOfferParser.card(listOf("Wireless optical mouse", "USh 45,000"))!!.title)
        assertNull(JumiaOfferParser.card(listOf("Wireless optical mouse", "UGX 45,000", "UGX 60,000")))
        assertNull(JumiaOfferParser.card(listOf("Wireless optical mouse")))
        assertNull(JumiaOfferParser.card(listOf("Official Store", "UGX 45,000")))
    }
}
