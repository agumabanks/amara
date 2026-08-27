package co.sanaa.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalCommandParserTest {
    @Test fun parsesOrdinalApproval() {
        val value = ApprovalCommandParser.parse("Approve the second one")!!
        assertTrue(value.approve)
        assertEquals(1, value.ordinal)
    }

    @Test fun parsesNamedRejection() {
        val value = ApprovalCommandParser.parse("Reject Vehicle Branding")!!
        assertFalse(value.approve)
        assertEquals("vehicle branding", value.targetHint)
    }

    @Test fun doesNotTreatOrdinaryYesAsApproval() {
        assertNull(ApprovalCommandParser.parse("Yes, tell me about the bookings"))
    }
}
