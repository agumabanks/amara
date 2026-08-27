package co.sanaa.agent.actions

import co.sanaa.agent.core.VerificationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Golden-hierarchy regression tests. Every fixture describes a real Accessibility
 * surface shape and the exact verdict the verifier logic must produce, including
 * stale-tick rejection via pre-state facts.
 */
class GoldenHierarchyTest {

    private fun fixturesDir(): File {
        val candidate = File("src/test/resources/golden")
        if (candidate.isDirectory) return candidate
        return File("app/src/test/resources/golden")
    }

    /**
     * Evaluates a WhatsApp-chat fixture through the same pure decision path the live
     * verifier uses (node roles + delivery binding + pre-state staleness rule).
     */
    private fun evaluateChat(fixture: GoldenFixture, content: String): VerificationEvidence {
        val snapshot = WhatsAppScreenSnapshot(fixture.pkg, fixture.visibleLines(), fixture.pkg)
        val facts = fixture.toNodeFacts(content)
        val (inBubble, draftOnlyByRoles) = SendVerificationLogic.evaluateNodeRoles(content, facts)
        val lineFallbackBubble = !inBubble && !draftOnlyByRoles &&
            SendVerificationLogic.contentMatches(snapshot.visibleText.filterNot { SendVerificationLogic.detectDraftOnly(listOf(it), content) }, content)
        var observation = SendObservation(
            observedPackage = snapshot.packageName,
            expectedPackage = PublicationSurfaces.WHATSAPP_PACKAGE,
            targetVisible = fixture.targetVisible && snapshot.contains("Sanaa Office"),
            contentVisibleOutsideDraft = inBubble || lineFallbackBubble,
            contentStillInDraftField = draftOnlyByRoles ||
                (!inBubble && SendVerificationLogic.detectDraftOnly(snapshot.visibleText, content)),
            deliveryState = facts.deliveryState,
            observedAtMs = 0L,
        )
        var evidence = SendVerificationLogic.evaluate(content, observation)
        // Staleness rule (mirrors TargetBoundVerifiers.applyStalenessRule).
        if (fixture.preExistingContent && evidence.verified) {
            evidence = VerificationEvidence.impossible(
                "An identical message with the same delivery state was already present before sending; this observation cannot prove a NEW send.",
                evidence.observedPackage,
            )
        }
        return evidence
    }

    @Test fun allGoldenFixturesExistAndParse() {
        val fixtures = GoldenFixture.loadAll(fixturesDir())
        assertTrue("Expected at least 13 golden fixtures, found ${fixtures.size}", fixtures.size >= 13)
        val names = fixtures.map { it.fixture }
        listOf(
            "sent_bubble", "unsent_draft", "quoted_message", "stale_earlier_message",
            "wrong_chat", "wrong_package", "status_composer", "published_status",
            "tiktok_draft", "tiktok_public_post", "save_succeeded", "save_failed", "save_uncertain",
        ).forEach { required -> assertTrue("Missing golden fixture: $required", names.contains(required)) }
    }

    @Test fun sentBubbleVerifiesWithDeliveryMarker() {
        val evidence = evaluateChat(load("sent_bubble"), "Hello team, the baskets are ready")
        assertTrue(evidence.verified)
        assertEquals("Delivered", evidence.deliveryState)
    }

    @Test fun unsentDraftNeverVerifies() {
        val evidence = evaluateChat(load("unsent_draft"), "Hello team, the baskets are ready")
        assertFalse(evidence.verified)
        assertNotNull(evidence.blocker)
    }

    @Test fun draftPlusSentCopyStillVerifiesViaNodeRoles() {
        // Regression for the predecessor's line-filter defect: identical text in both a
        // bubble and the draft field must verify via node editability roles.
        val fixture = load("draft_plus_sent_copy")
        val facts = fixture.toNodeFacts("Hello team, the baskets are ready")
        assertTrue(facts.inReadOnlyBubble)
        val (inBubble, draftOnly) = SendVerificationLogic.evaluateNodeRoles("Hello team, the baskets are ready", facts)
        assertTrue(inBubble)
        assertFalse(draftOnly)
        assertTrue(evaluateChat(fixture, "Hello team, the baskets are ready").verified)
    }

    @Test fun quotedMessageMarkerBelongsToTheReplyNotOurMessage() {
        val fixture = load("quoted_message")
        // Row binding: the Read marker sits on the reply row, not on our quoted content.
        val facts = fixture.toNodeFacts("Hello team, the baskets are ready")
        assertEquals(null, facts.deliveryState)
        val evidence = evaluateChat(fixture, "Hello team, the baskets are ready")
        assertFalse(evidence.verified)
        assertNotNull(evidence.blocker)
    }

    @Test fun staleEarlierMessageIsNotAcceptedAsFreshProof() {
        val evidence = evaluateChat(load("stale_earlier_message"), "Hello team, the baskets are ready")
        assertFalse(evidence.verified)
        assertTrue(evidence.blocker!!.contains("already"))
    }

    @Test fun wrongChatTargetIsRejected() {
        val evidence = evaluateChat(load("wrong_chat"), "Hello team, the baskets are ready")
        assertFalse(evidence.verified)
        assertTrue(evidence.blocker!!.contains("target"))
    }

    @Test fun wrongPackageIsRejected() {
        val evidence = evaluateChat(load("wrong_package"), "Hello team, the baskets are ready")
        assertFalse(evidence.verified)
        assertTrue(evidence.blocker!!.contains("com.whatsapp"))
    }

    @Test fun statusComposerIsADraftNotAPublication() {
        // On the composer surface the text lives only inside an editable field; node roles
        // prove it is not published even though the package matches.
        val fixture = load("status_composer")
        val facts = fixture.toNodeFacts("Fresh stock today!")
        assertTrue(facts.onlyInsideEditableField)
        assertFalse(facts.inReadOnlyBubble)
    }

    @Test fun publishedStatusOnWhatsAppSurfaceVerifies() {
        val fixture = load("published_status")
        val rule = PublicationSurfaceRule(PublicationSurfaces.WHATSAPP_PACKAGE)
        val evidence = rule.evaluate(
            "Fresh stock today!", fixture.pkg,
            SendVerificationLogic.contentMatches(fixture.visibleLines(), "Fresh stock today!"),
        )
        assertTrue(evidence.verified)
        assertEquals("published", evidence.deliveryState)
    }

    @Test fun statusPublicationInWrongPackageFails() {
        val fixture = load("wrong_package")
        val rule = PublicationSurfaceRule(PublicationSurfaces.WHATSAPP_PACKAGE)
        val evidence = rule.evaluate("Fresh stock today!", fixture.pkg, contentVisible = true)
        assertFalse(evidence.verified)
    }

    @Test fun tiktokDraftIsNotAPublication() {
        val fixture = load("tiktok_draft")
        val rule = PublicationSurfaceRule(PublicationSurfaces.TIKTOK_PACKAGE)
        val evidence = rule.evaluate("Market day vibes #kampala", fixture.pkg, contentVisible = false)
        assertFalse(evidence.verified)
    }

    @Test fun tiktokPublicPostOnTheRightPackageVerifies() {
        val fixture = load("tiktok_public_post")
        val rule = PublicationSurfaceRule(PublicationSurfaces.TIKTOK_PACKAGE)
        val evidence = rule.evaluate(
            "Market day vibes #kampala", fixture.pkg,
            SendVerificationLogic.contentMatches(fixture.visibleLines(), "Market day vibes #kampala"),
        )
        assertTrue(evidence.verified)
    }

    @Test fun sokoSaveSuccessReopenCompareVerifies() {
        val fixture = load("save_succeeded")
        val evidence = SokoSaveVerification.evaluate(
            "Product Name", "Thermal Mini Printer X8",
            SokoSaveVerification.ReopenObservation(
                reopened = true,
                observedPackage = fixture.pkg,
                fieldMatchesApprovedValue = fixture.visibleLines().contains("Thermal Mini Printer X8"),
                observedAtMs = 0,
            ),
        )
        assertTrue(evidence.verified)
        assertEquals("saved_and_reopened_matched", evidence.deliveryState)
    }

    @Test fun sokoSaveFailureIsPreciselyBlocked() {
        val evidence = SokoSaveVerification.evaluate(
            "Product Name", "Thermal Mini Printer X8",
            SokoSaveVerification.ReopenObservation(true, SokoSaveVerification.SOKO_PACKAGE, false, 0),
        )
        assertFalse(evidence.verified)
        assertTrue(evidence.blocker!!.contains("SAVE_NOT_PROVEN"))
    }

    @Test fun sokoSaveUncertainNamesTheBlocker() {
        val evidence = SokoSaveVerification.evaluate(
            "Product Name", "Thermal Mini Printer X8",
            SokoSaveVerification.ReopenObservation(false, "", false, 0),
        )
        assertFalse(evidence.verified)
        assertTrue(evidence.blocker!!.contains("reopen"))
    }

    private fun load(name: String): GoldenFixture =
        GoldenFixture.loadAll(fixturesDir()).first { it.fixture == name }
}
