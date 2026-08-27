package co.sanaa.agent.modules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualAssessmentGuardTest {
    private val valid = VisualListingAssessment(
        "Vehicle Branding", "A ceramic coffee mug on a white background", true,
        "The cup image does not match vehicle branding", 0.93, "/private/evidence.png",
    )

    @Test fun acceptsHighConfidenceAssessmentWithExactListingProvenance() {
        assertTrue(VisualAssessmentGuard.accepted(valid, "Vehicle Branding"))
    }

    @Test fun rejectsWrongListingLowConfidenceOrMissingEvidence() {
        assertFalse(VisualAssessmentGuard.accepted(valid.copy(listingName = "Cup Printing"), "Vehicle Branding"))
        assertFalse(VisualAssessmentGuard.accepted(valid.copy(confidence = 0.4), "Vehicle Branding"))
        assertFalse(VisualAssessmentGuard.accepted(valid.copy(screenshotPath = ""), "Vehicle Branding"))
    }

    @Test fun confidenceBoundaryAtPointSevenZeroIsAcceptedJustBelowIsRejected() {
        assertTrue(VisualAssessmentGuard.accepted(valid.copy(confidence = 0.70), "Vehicle Branding"))
        assertFalse(VisualAssessmentGuard.accepted(valid.copy(confidence = 0.69), "Vehicle Branding"))
        assertFalse(VisualAssessmentGuard.accepted(valid.copy(confidence = 1.01), "Vehicle Branding"))
    }

    @Test fun claimedMismatchWithoutASubstantiveIssueIsRejected() {
        assertFalse(VisualAssessmentGuard.accepted(valid.copy(mismatch = true, issue = "blur"), "Vehicle Branding"))
        assertFalse(VisualAssessmentGuard.accepted(valid.copy(mismatch = true, issue = ""), "Vehicle Branding"))
        assertTrue(VisualAssessmentGuard.accepted(valid.copy(mismatch = false, issue = ""), "Vehicle Branding"))
    }

    @Test fun caseInsensitiveListingMatchOnlyGoesSoFarAsTheExactName() {
        assertTrue(VisualAssessmentGuard.accepted(valid.copy(listingName = "VEHICLE branding"), "Vehicle Branding"))
        assertFalse(VisualAssessmentGuard.accepted(valid.copy(listingName = " Vehicle Branding Pro"), "Vehicle Branding"))
    }
}
