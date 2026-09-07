package co.sanaa.agent.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppTargetMatchingTest {
    @Test fun exactNamesIgnoreCaseAndOuterWhitespace() {
        assertTrue(WhatsAppTargetMatching.matches(" Sanaa Office ", "sanaa office"))
    }
    @Test fun invisibleDirectionMarksAndNonbreakingSpacesDoNotChangeIdentity() {
        assertTrue(WhatsAppTargetMatching.matches("\u200eSanaa\u00a0 Office\u200f", "Sanaa Office"))
        assertFalse(WhatsAppTargetMatching.matches("Sanaa Office Sales", "Sanaa Office"))
    }
    @Test fun partialNamesAndMessageSnippetsAreNotTargets() {
        assertFalse(WhatsAppTargetMatching.matches("Sanaa Office Sales", "Sanaa Office"))
        assertFalse(WhatsAppTargetMatching.matches("Ask Sanaa Office tomorrow", "Sanaa Office"))
        assertFalse(WhatsAppTargetMatching.matches("", ""))
    }
    @Test fun fullPhoneNumbersAllowDisplayFormattingButNotSuffixMatching() {
        assertTrue(WhatsAppTargetMatching.matches("+256 700-123-456", "+256700123456"))
        assertFalse(WhatsAppTargetMatching.matches("+256700123456", "700123456"))
    }
}
