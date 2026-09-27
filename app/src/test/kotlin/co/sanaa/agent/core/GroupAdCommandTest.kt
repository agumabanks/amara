package co.sanaa.agent.core

import org.junit.Assert.*
import org.junit.Test

class GroupAdCommandTest {
    @Test fun exactGroupRequestWorksWithoutPlanning() {
        assertEquals("Naalya E-Trade", GroupAdCommand.target("Post one ad to Naalya E-Trade"))
        assertEquals("Naalya E-Trade", GroupAdCommand.target("Please share one Soko Studio ad to WhatsApp group Naalya E-Trade"))
    }
    @Test fun negationsQuestionsAndExtraInstructionsNeverBecomeKnownTargets() {
        assertNull(GroupAdCommand.target("Don't post one ad to Naalya E-Trade"))
        assertNull(GroupAdCommand.target("Should I post one ad to Naalya E-Trade?"))
        assertNotEquals("Naalya E-Trade", GroupAdCommand.target("Post one ad to Naalya E-Trade tomorrow"))
        assertNotEquals("Naalya E-Trade", GroupAdCommand.target("Post one ad to Naalya E-Trade and another group"))
    }
}
