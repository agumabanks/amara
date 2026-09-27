package co.sanaa.agent.modules

import org.junit.Assert.*
import org.junit.Test

class AdHeadlineTest {
    @Test fun selfInkingCatalogueStampDoesNotRequireModelAvailability() {
        val title = "Self-Inking Stamp - CONFIDENTIAL - Red Ink"
        val headline = AdHeadline.fallback(title)
        assertEquals("Self-inking Stamp", headline)
        assertEquals(headline, AdHeadline.validated(title, headline))
        assertNull(AdHeadline.validated(title, "Red Ink"))
    }
    @Test fun smartCardsAlwaysKeepTheirProductIdentity() {
        val title="Sanaa Smart Business Cards"
        assertEquals("Smart Business Cards", AdHeadline.fallback(title))
        assertNull(AdHeadline.validated(title,"Sanaa Smart"))
        assertNull(AdHeadline.validated(title,"Business Cards"))
        assertEquals("Smart Business Cards",AdHeadline.validated(title,"Smart Business Cards"))
    }
    @Test fun twoWordsWinWhenThirdWordOnlyAddsTheBrand() {
        assertEquals("Date Stamp", AdHeadline.fallback("Sanaa Date Stamp"))
        assertNull(AdHeadline.validated("Sanaa Date Stamp", "Sanaa Date Stamp"))
        assertNull(AdHeadline.validated("Sanaa Date Stamp", "Stamp Date"))
        assertEquals("Date Stamp", AdHeadline.validated("Sanaa Date Stamp", "Date Stamp"))
        assertEquals("Smart Business Cards", AdHeadline.fallback("Sanaa Smart Business Cards"))
        assertNull(AdHeadline.validated("Sanaa Smart Business Cards", "Sanaa Business Cards"))
    }
    @Test fun cannotInventClaimsOrReplaceAnUnknownProduct() {
        assertNull(AdHeadline.validated("Office Chair","Best Ergonomic Office Chair"))
        assertNull(AdHeadline.validated("Sanaa Solar Water Pump","Sanaa Solar"))
        assertNull(AdHeadline.validated("Sanaa Solar Water Pump","Solar Water Pump Sanaa"))
        assertEquals("Solar Water Pump",AdHeadline.validated("Sanaa Solar Water Pump","Solar Water Pump"))
        assertEquals("Office Chair",AdHeadline.fallback("Office Chair Deluxe"))
    }
}
