package co.sanaa.agent.actions

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deadline/window and media-status verification rules.
 * Scope: JVM/Robolectric — construction needs any Context, but only pure seams run;
 * live WhatsApp/TikTok surface behavior stays device-gated and is not claimed here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TargetBoundVerifiersTest {

    private fun probe(): TargetBoundVerifiers =
        TargetBoundVerifiers(AccessibilityActions(ApplicationProvider.getApplicationContext()))

    @Test fun windowExpiryYieldsPreciseBlocker() {
        val rule = PublicationSurfaceRule(PublicationSurfaces.WHATSAPP_PACKAGE)
        val evidence = rule.evaluate("status text", "com.whatsapp", contentVisible = false)
        assertFalse(evidence.verified)
        assertNotNull(evidence.blocker)
        assertTrue(
            "blocker should name the window, got: ${evidence.blocker}",
            evidence.blocker!!.contains("observation window") || evidence.blocker.contains("not visible"),
        )
    }

    @Test fun captionlessMediaStatusYieldsPreciseBlocker() {
        // A caption-less media Status cannot be bound to content; the verifier refuses
        // proof instead of fabricating confidence (pure seam over the production rule).
        val verifiers = probe()
        val evidence = verifiers.captionlessMediaStatusEvidence("   ")
        assertNotNull(evidence)
        assertFalse(evidence!!.verified)
        assertTrue(evidence.blocker!!.contains("caption"))
        assertEquals(null, verifiers.captionlessMediaStatusEvidence("Beach day"))
    }

    @Test fun attachmentCaptionBindingRequiresRealMatch() {
        val lines = listOf("Sanaa Office", "Delivered")
        assertFalse(SendVerificationLogic.contentMatches(lines.filterNot { SendVerificationLogic.detectDraftOnly(listOf(it), "cap") }, "cap"))
        assertTrue(SendVerificationLogic.contentMatches(lines, "Delivered"))
    }

}
