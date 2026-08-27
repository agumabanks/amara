package co.sanaa.agent.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SokoServiceParserTest {
    @Test fun parsesACompleteServiceCardWithoutFlatteningItsFields() {
        val item = SokoServiceParser.parse(
            listOf(
                "Professional Website Development\nProfessional development of responsive business websites for growing Kampala businesses.\n1,800,000 /=\nLive",
            ),
        ).single()

        assertEquals("Professional Website Development", item.name)
        assertEquals(1_800_000L, item.priceUgx)
        assertEquals("Live", item.status)
        assertTrue(item.weakReasons.isEmpty())
    }

    @Test fun reportsAVisibleTitleDescriptionMismatchAsAReviewItem() {
        val item = SokoServiceParser.parse(
            listOf(
                "Vehicle Branding\nDurable ceramic coffee cup printing for gifts and office teams.\n85,000 /=\nLive",
            ),
        ).single()

        assertTrue(item.weakReasons.contains("description may not match the title"))
    }

    @Test fun keepsAListingWithNoVisiblePriceAndFlagsIt() {
        val item = SokoServiceParser.parse(
            listOf("Logo Design\nShort details\nDraft"),
        ).single()

        assertEquals(null, item.priceUgx)
        assertTrue(item.weakReasons.contains("price is missing"))
        assertTrue(item.weakReasons.contains("description is too thin"))
    }
}
