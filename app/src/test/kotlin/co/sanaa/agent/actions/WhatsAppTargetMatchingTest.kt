package co.sanaa.agent.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppTargetMatchingTest {
    @Test fun phoneIdentityRejectsNamesAndSuffixes() {
        org.junit.Assert.assertEquals("256700123456",WhatsAppTargetMatching.phone("0700 123 456"))
        org.junit.Assert.assertEquals("256700123456",WhatsAppTargetMatching.phone("+256 700-123-456"))
        org.junit.Assert.assertNull(WhatsAppTargetMatching.phone("Manager 0700123456"))
        org.junit.Assert.assertNull(WhatsAppTargetMatching.phone("123456"))
        org.junit.Assert.assertNotEquals(WhatsAppTargetMatching.phone("+256700123456"),WhatsAppTargetMatching.phone("+256700123457"))
    }

    @Test fun ellipsisIsOnlyRemovedForSearchNeverIdentity() {
        assertTrue(WhatsAppTargetMatching.isTruncated("Naalya Community…"))
        assertTrue(WhatsAppTargetMatching.isTruncated("Naalya Community..."))
        assertTrue(WhatsAppTargetMatching.searchQuery("Naalya Community…") == "Naalya Community")
        assertFalse(WhatsAppTargetMatching.matches("Naalya Community…", "Naalya Community Neighborhood"))
        assertFalse(WhatsAppTargetMatching.matches("Naalya Community Neighborhood", "Naalya Community…"))
        assertFalse(WhatsAppTargetMatching.matches("Naalya Community…", "Naalya Community…"))
        assertTrue(WhatsAppTargetMatching.searchQuery("Sanaa Office") == "Sanaa Office")
    }
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
