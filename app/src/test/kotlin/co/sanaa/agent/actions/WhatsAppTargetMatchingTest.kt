package co.sanaa.agent.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppTargetMatchingTest {
    @Test fun searchReadbackIgnoresWhatsAppsInvisiblePrefixWithoutAcceptingPartialText() {
        assertTrue(WhatsAppPickerSearch.matches("\u200bNaalya Community Neighborhood", "Naalya Community Neighborhood"))
        assertFalse(WhatsAppPickerSearch.matches("\u200bNaalya Community", "Naalya Community Neighborhood"))
        assertFalse(WhatsAppPickerSearch.matches(null, "Naalya Community Neighborhood"))
    }
    @Test fun fullGroupIconLocatesOnlyTheExactGroupDespiteAnEllipsizedRow() {
        assertTrue(WhatsAppTargetMatching.groupIconMatches("Naalya Community Neighborhood, group profile icon", "Naalya Community Neighborhood"))
        assertFalse(WhatsAppTargetMatching.groupIconMatches("Naalya Community Neig…, group profile icon", "Naalya Community Neighborhood"))
        assertFalse(WhatsAppTargetMatching.groupIconMatches("Naalya Community Neighborhood Sales, group profile icon", "Naalya Community Neighborhood"))
        assertFalse(WhatsAppTargetMatching.groupIconMatches("Naalya Community Neighborhood", "Naalya Community Neighborhood"))
    }
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
        assertTrue(WhatsAppTargetMatching.matches("0700 123456", "+256700123456"))
        assertFalse(WhatsAppTargetMatching.matches("+256700123456", "700123456"))
    }

    @Test fun longOriginPrefixCanOnlyLocateExpansionAndNeverAuthorizesSend() {
        val prefix = "This is a long customer message with enough exact content to identify its row safely in the verified chat"
        val full = "$prefix and this is the remaining detail that WhatsApp collapsed on screen."
        assertTrue(WhatsAppOriginText.expandablePrefix("$prefix… Read more", full))
        assertFalse(WhatsAppOriginText.matches("$prefix… Read more", full))
        assertTrue(WhatsAppOriginText.matches(full, full))
        assertFalse(WhatsAppOriginText.matches(prefix, full))
        assertFalse(WhatsAppOriginText.expandablePrefix("${prefix.dropLast(1)}X… Read more", full))
        assertFalse(WhatsAppOriginText.expandablePrefix("Short… Read more", "Short message with more text"))
        // Two messages can share a collapsed prefix; neither is a delivery proof.
        assertTrue(WhatsAppOriginText.expandablePrefix("$prefix… Read more", "$prefix other details"))
        assertFalse(WhatsAppOriginText.matches("$prefix… Read more", "$prefix other details"))
    }
}
