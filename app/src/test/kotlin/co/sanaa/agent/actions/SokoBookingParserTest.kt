package co.sanaa.agent.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class SokoBookingParserTest {
    @Test fun parsesTheTwoMultilineCardsObservedOnTheOppo() {
        val bookings = SokoBookingParser.parse(
            listOf(
                "Inbox",
                "Bookings",
                "Professional Bulk SMS Services\nsanaa inc",
                "Show menu",
                "Custom Cash Sale Receipt Book Printing A5  (400 Pages 200 top 200 bottom)\nAndrew sekitto",
                "More",
            ),
        )

        assertEquals(2, bookings.size)
        assertEquals("Professional Bulk SMS Services", bookings[0].service)
        assertEquals("sanaa inc", bookings[0].customer)
        assertEquals("Andrew sekitto", bookings[1].customer)
    }
}
