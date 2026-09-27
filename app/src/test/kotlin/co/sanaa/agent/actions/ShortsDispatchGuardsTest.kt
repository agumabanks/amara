package co.sanaa.agent.actions

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regressions for the final YouTube Shorts dispatch guards, using the captured
 * final-details layout. Captured release39 evidence: the authorized upload task
 * reached ACTING then FAILED 2879 ms later with "external trigger was never
 * dispatched" because the old guards required the `Add description` placeholder
 * after prepare() had already filled the description.
 */
class ShortsDispatchGuardsTest {
    private val description = "Self-Inking Numbering machine available now. Order via WhatsApp +256706272481."

    /** Label list exactly as ShortsDeviceSurface.labels() would flatten it. */
    private fun labels(fixture: String, replace: Pair<String, String>? = null): List<String> {
        val doc = javaClass.getResourceAsStream(fixture)!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val elements = doc.getElementsByTagName("node")
        return (0 until elements.length).map { i ->
            (elements.item(i) as Element).getAttribute("text")
        }.flatMap { it.lines() }.map(String::trim).filter(String::isNotBlank).distinct()
            .map { if (replace != null && it == replace.first) replace.second else it }
    }

    @Test fun capturedFinalDetailsScreenIsReadyForDispatch() {
        val text = labels("/youtube/final-details.xml")
        assertTrue(ShortsDispatchGuards.onDetailsScreen(text))
        assertTrue(ShortsDispatchGuards.channelVisible(text, "@sanaasanaa1774"))
        assertTrue(ShortsDispatchGuards.visibilityVisible(text, "Public"))
        assertTrue(ShortsDispatchGuards.descriptionPlaceholder(text))
    }

    @Test fun release39FilledDescriptionSkipsPlaceholderReentry() {
        // Dispatch-time state after prepare() filled the description: the row shows
        // the entered text, not the placeholder. The OLD guard (placeholder required,
        // then Show more / Add description taps) fails this state and blocked the
        // final dispatch; the new guard must accept it without re-entry.
        val text = labels("/youtube/final-details.xml", "Add description" to description)
        assertFalse(ShortsDispatchGuards.descriptionPlaceholder(text))
        assertTrue(ShortsDispatchGuards.descriptionVisible(text, description))
        val oldGuardWouldTapShowMore = !ShortsDispatchGuards.descriptionPlaceholder(text)
        assertTrue(oldGuardWouldTapShowMore)
        assertTrue(text.none { it.equals("Show more", true) })
        assertTrue(text.none { it.equals("Add description", true) })
    }

    @Test fun filledDescriptionLineAndTruncatedPreviewAreVisible() {
        val full = labels("/youtube/final-details.xml", "Add description" to description)
        assertTrue(ShortsDispatchGuards.descriptionVisible(full, description))
        val truncated = labels("/youtube/final-details.xml",
            "Add description" to description.take(40) + "…")
        assertTrue(ShortsDispatchGuards.descriptionVisible(truncated, description))
    }

    @Test fun absentDescriptionWithNoPlaceholderIsRejectedNotGuessed() {
        val text = labels("/youtube/final-details.xml").filterNot { it == "Add description" }
        assertFalse(ShortsDispatchGuards.descriptionPlaceholder(text))
        assertFalse(ShortsDispatchGuards.descriptionVisible(text, description))
        // The dispatch must refuse with description_not_observable instead of
        // proceeding or substituting unknown state.
        assertFalse(ShortsDispatchGuards.descriptionVisible(emptyList(), description))
        assertFalse(ShortsDispatchGuards.descriptionVisible(listOf("other"), ""))
    }

    @Test fun channelTitleAudienceAndVisibilityRejections() {
        val filled = labels("/youtube/final-details.xml", "Add description" to description)
        assertFalse(ShortsDispatchGuards.channelVisible(filled, "@otherchannel"))
        assertFalse(ShortsDispatchGuards.channelVisible(emptyList(), "@sanaasanaa1774"))
        assertFalse(ShortsDispatchGuards.onDetailsScreen(filled.filterNot { it == "Add details" }))
        assertFalse(ShortsDispatchGuards.visibilityVisible(filled, "Unlisted"))
        assertFalse(ShortsDispatchGuards.audienceMatches(filled, true))
        // The captured audience row shows only "Select audience": not yet confirmed,
        // so neither audience state matches until the explicit choice is visible.
        assertFalse(ShortsDispatchGuards.audienceMatches(filled, false))
        assertTrue(ShortsDispatchGuards.audienceMatches(
            filled + "No, it's not made for kids", false))
        assertTrue(ShortsDispatchGuards.audienceMatches(
            filled + "Yes, it's made for kids", true))
        assertTrue(ShortsDispatchGuards.audienceMatches(filled + "Made for kids", true))
    }

    @Test fun singleFieldChecksRequireExactlyOneEditableField() {
        assertTrue(ShortsDispatchGuards.titleFilled(listOf("My Short"), "My Short"))
        assertTrue(ShortsDispatchGuards.singleFieldEquals(listOf(description), description))
        // A second editable field or drifted text must fail, never pass.
        assertFalse(ShortsDispatchGuards.titleFilled(emptyList(), "My Short"))
        assertFalse(ShortsDispatchGuards.titleFilled(listOf("My Short", "extra"), "My Short"))
        assertFalse(ShortsDispatchGuards.singleFieldEquals(listOf(description + " "), description))
        assertFalse(ShortsDispatchGuards.singleFieldEquals(listOf("drifted"), description))
    }

    @Test fun rejectionReasonsCoverEveryDistinguishableFailure() {
        val reasons = listOf(
            ShortsDispatchGuards.WINDOW_NOT_READY, ShortsDispatchGuards.DETAILS_NOT_VISIBLE,
            ShortsDispatchGuards.CHANNEL_NOT_VISIBLE, ShortsDispatchGuards.DESCRIPTION_CONTROL,
            ShortsDispatchGuards.DESCRIPTION_TEXT, ShortsDispatchGuards.DESCRIPTION_NOT_OBSERVABLE,
            ShortsDispatchGuards.EDITOR_CLOSE, ShortsDispatchGuards.SHORTS_DISABLED,
            ShortsDispatchGuards.SOUNDTRACK_NOT_CLEARED, ShortsDispatchGuards.CHANNEL_CHANGED,
            ShortsDispatchGuards.VISIBILITY_NOT_VISIBLE, ShortsDispatchGuards.AUDIENCE_MISMATCH,
            ShortsDispatchGuards.TITLE_DRIFT, ShortsDispatchGuards.OWNER_OFF,
            ShortsDispatchGuards.UPLOAD_CONTROL)
        // Channel, title, description, audience, visibility, owner state, window
        // readiness and final control failures must all be distinguishable.
        assertEquals(reasons.size, reasons.distinct().size)
        assertTrue(reasons.containsAll(listOf(
            ShortsDispatchGuards.CHANNEL_NOT_VISIBLE, ShortsDispatchGuards.TITLE_DRIFT,
            ShortsDispatchGuards.DESCRIPTION_TEXT, ShortsDispatchGuards.AUDIENCE_MISMATCH,
            ShortsDispatchGuards.VISIBILITY_NOT_VISIBLE, ShortsDispatchGuards.OWNER_OFF,
            ShortsDispatchGuards.WINDOW_NOT_READY, ShortsDispatchGuards.UPLOAD_CONTROL)))
    }

    @Test fun newestOwnChannelVideoRequiresTitleChannelAndVisibilityTogether() {
        val labels = listOf("Go to channel @sanaasanaa1774", "@sanaasanaa1774", "Self-Inking Stamp", "Public")
        assertTrue(ShortsDispatchGuards.publicationVisible(labels,"Self-Inking Stamp","@sanaasanaa1774","Public"))
        assertFalse(ShortsDispatchGuards.publicationVisible(labels,"Photo Booth","@sanaasanaa1774","Public"))
        assertFalse(ShortsDispatchGuards.publicationVisible(labels,"Self-Inking Stamp","@other","Public"))
        assertFalse(ShortsDispatchGuards.publicationVisible(labels,"Self-Inking Stamp","@sanaasanaa1774","Private"))
    }
}
