package co.sanaa.agent.actions

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import co.sanaa.agent.actions.TikTokSoundSelection.Bounds
import co.sanaa.agent.actions.TikTokSoundSelection.Frame
import co.sanaa.agent.actions.TikTokSoundSelection.Node

class TikTokSoundSelectionTest {
    @Test fun placeholderAndSilentOriginalAreNotSelectedSoundEvidence() {
        for(value in listOf("", "Add sound", "Music", "Original sound", "Loading...", "Search"))
            assertFalse(value,TikTokSoundSelection.isSelectedTitle(value))
        assertTrue(TikTokSoundSelection.isSelectedTitle("Pasture Hymn"))
    }

    private val pkg = TikTokSoundSelection.PACKAGE
    private val window = Bounds(0, 0, 1000, 2000)
    private val track = "Pasture Hymn"

    private fun node(path: String, id: String = "", text: String = "", bounds: Bounds = window,
        clickable: Boolean = false): Node = Node(path, path.substringBeforeLast('/', "").ifBlank { null },
        pkg, 7, if (id.isBlank()) "" else "$pkg:id/$id", "android.view.View", text, "", bounds,
        clickable = clickable)

    private fun composer(title: String = "Add sound"): Frame = Frame(pkg, 7, window, listOf(
        node("0"), node("0/0", bounds = Bounds(100, 40, 900, 200)),
        node("0/0/0", "tv_top_text", title, Bounds(100, 40, 700, 200), true),
        node("0/0/1", "e0m", bounds = Bounds(700, 40, 900, 200), clickable = true),
        node("0/1", text = "Next", bounds = Bounds(700, 1700, 950, 1900), clickable = true)))

    private fun picker(selected: Boolean = false, checked: Boolean = false, checkable: Boolean = false): Frame =
        Frame(pkg, 7, window, listOf(node("0"),
            node("0/2", "g0z", bounds = Bounds(0, 800, 1000, 2000)),
            node("0/2/0", bounds = Bounds(40, 900, 960, 1150), clickable = true)
                .copy(selected = selected, checked = checked, checkable = checkable),
            node("0/2/0/0", "title", track, Bounds(100, 950, 700, 1050))))

    private fun Frame.change(path: String, update: (Node) -> Node): Frame =
        copy(nodes = nodes.map { if (it.path == path) update(it) else it })

    private data class Result(val verified: Boolean, val events: List<String>)

    private fun flow(before: Frame = picker(), after: List<Frame> = listOf(picker(selected = true)),
        final: Frame = composer(track), tapAccepted: Boolean = true, dismissAccepted: Boolean = true): Result = runBlocking {
        var phase = 0
        var reads = 0
        val events = mutableListOf<String>()
        val verified = TikTokSoundSelection.select(
            observe = {
                when (phase) {
                    0 -> composer()
                    1 -> before
                    2 -> after[(reads++).coerceAtMost(after.lastIndex)]
                    else -> final
                }
            },
            openPicker = { events += "open"; phase = 1; true },
            tapRow = { _, row -> assertEquals(track, row.title.text); events += "tap"; phase = 2; tapAccepted },
            dismiss = { frame, row ->
                assertEquals(track, row.title.text)
                assertNotNull(TikTokSoundSelection.outsidePoint(frame))
                events += "outside"; phase = 3; dismissAccepted
            },
            pause = {},
            report = { state, _ -> if (state == "SELECTED") events += "confirmed" },
        )
        Result(verified, events)
    }

    @Test fun selectedRowTransitionThenOutsideAndComposerIsConfirmed() {
        val result = flow()
        assertTrue(result.verified)
        assertEquals(listOf("open", "tap", "outside", "confirmed"), result.events)
    }

    @Test fun checkedRowTransitionIsConfirmed() {
        assertTrue(flow(before = picker(checkable = true),
            after = listOf(picker(checkable = true, checked = true))).verified)
    }

    @Test fun selectedTitleWithinTheChosenRowIsConfirmed() {
        assertTrue(flow(after = listOf(picker().change("0/2/0/0") { it.copy(selected = true) })).verified)
    }

    @Test fun absentRowFlagCanBeInspectedButAnUnchangedComposerNeverConfirms() {
        val result = flow(after = listOf(picker()), final = composer())
        assertFalse(result.verified)
        assertEquals(listOf("open", "tap", "outside"), result.events)
    }

    @Test fun attachedComposerIsAuthoritativeWhenPickerExposesNoSelectionFlag() {
        assertTrue(flow(after = listOf(picker())).verified)
    }

    @Test fun previouslySelectedRowIsNotATransition() {
        assertFalse(flow(before = picker(selected = true)).verified)
    }

    @Test fun transientHighlightIsNotStableEvidence() {
        assertFalse(flow(after = listOf(picker(selected = true), picker()), final = composer()).verified)
    }

    @Test fun sameTitleWithChangedArtistDoesNotConfirm() {
        val before = picker().let { it.copy(nodes = it.nodes +
            node("0/2/0/1", text = "Artist A", bounds = Bounds(100, 1060, 700, 1140))) }
        val after = before.change("0/2/0") { it.copy(selected = true) }
            .change("0/2/0/1") { it.copy(text = "Artist B") }
        assertFalse(flow(before = before, after = listOf(after)).verified)
    }

    @Test fun wrongTrackAtSamePositionDoesNotConfirm() {
        assertFalse(flow(after = listOf(picker(selected = true).change("0/2/0/0") {
            it.copy(text = "Different track") })).verified)
    }

    @Test fun wrongComposerTrackDoesNotConfirm() {
        assertFalse(flow(final = composer("Different track")).verified)
    }

    @Test fun unrelatedSelectedNodeAndPlaybackAnimationDoNotConfirm() {
        val after = picker().let { it.copy(nodes = it.nodes +
            node("0/2/1", text = "Pause", bounds = Bounds(800, 1200, 950, 1400), clickable = true).copy(selected = true)) }
        assertFalse(flow(after = listOf(after), final = composer()).verified)
    }

    @Test fun selectedPlaybackChildInChosenRowDoesNotConfirm() {
        val after = picker().let { it.copy(nodes = it.nodes +
            node("0/2/0/1", text = "Pause", bounds = Bounds(800, 950, 900, 1050), clickable = true).copy(selected = true)) }
        assertFalse(flow(after = listOf(after), final = composer()).verified)
    }

    @Test fun duplicateTitleRowsAreAmbiguous() {
        val before = picker().let { it.copy(nodes = it.nodes + listOf(
            node("0/2/1", bounds = Bounds(40, 1200, 960, 1450), clickable = true),
            node("0/2/1/0", "title", track, Bounds(100, 1250, 700, 1350)))) }
        val result = flow(before = before)
        assertFalse(result.verified)
        assertEquals(listOf("open"), result.events)
    }

    @Test fun broadClickableAncestorWithMultipleTracksIsNotARow() {
        val before = picker().let { it.copy(nodes = it.nodes +
            node("0/2/0/1", "title", "Another track", Bounds(100, 1060, 700, 1140))) }
        assertTrue(TikTokSoundSelection.rows(before).isEmpty())
    }

    @Test fun packageAndWindowChangesFailAtEveryPhase() {
        for (bad in listOf(picker().copy(packageName = "com.other"), picker().copy(windowId = 8))) {
            assertFalse(flow(before = bad).verified)
            assertFalse(flow(after = listOf(bad)).verified)
        }
        assertFalse(flow(final = composer(track).copy(windowId = 8)).verified)
        assertFalse(flow(final = composer(track).copy(packageName = "com.other")).verified)
    }

    @Test fun foreignTitleNodeIsNotUsable() {
        assertFalse(flow(before = picker().change("0/2/0/0") { it.copy(windowId = 8) }).verified)
        assertFalse(flow(before = picker().change("0/2/0/0") { it.copy(packageName = "com.other") }).verified)
    }

    @Test fun sheetStillVisibleOverCorrectComposerDoesNotConfirm() {
        val final = composer(track).let { it.copy(nodes = it.nodes + picker(selected = true).nodes.drop(1)) }
        assertFalse(flow(final = final).verified)
    }

    @Test fun missingSheetIdButRemainingRowsDoesNotConfirmDismissal() {
        val final = composer(track).let { it.copy(nodes = it.nodes + picker().nodes.drop(2)) }
        assertFalse(flow(final = final).verified)
    }

    @Test fun titleAnywhereAndUnrelatedRemoveControlAreNotComposerEvidence() {
        assertFalse(flow(final = composer(track).change("0/0/0") { it.copy(id = "unrelated") }).verified)
        assertFalse(flow(final = composer(track).change("0/0/1") { it.copy(path = "0/3", parent = "0") }).verified)
        assertFalse(flow(final = composer(track).change("0/1") { it.copy(visible = false) }).verified)
    }

    @Test fun staleIdsAndMovedOrHiddenRowDoNotConfirm() {
        for (after in listOf(
            picker(selected = true).change("0/2") { it.copy(id = "obsolete_sheet") },
            picker(selected = true).change("0/2/0/0") { it.copy(id = "obsolete_title") },
            picker(selected = true).change("0/2/0/0") { it.copy(bounds = Bounds(200, 950, 800, 1050)) },
            picker(selected = true).change("0/2/0/0") { it.copy(visible = false) },
        )) assertFalse(flow(after = listOf(after), final = composer()).verified)
    }

    @Test fun uncheckedNonCheckableFlagDoesNotEstablishSelection() {
        assertFalse(flow(after = listOf(picker(checked = true)), final = composer()).verified)
    }

    @Test fun failedTapOrDismissalDoesNotConfirm() {
        assertFalse(flow(tapAccepted = false).verified)
        assertFalse(flow(dismissAccepted = false).verified)
    }

    @Test fun outsidePointRequiresValidSheetBoundsAndAvoidsControls() {
        assertEquals(500 to 400, TikTokSoundSelection.outsidePoint(picker()))
        assertNull(TikTokSoundSelection.outsidePoint(picker().change("0/2") { it.copy(bounds = window) }))
        val blocked = picker().let { it.copy(nodes = it.nodes +
            node("0/4", text = "Next", bounds = Bounds(400, 300, 600, 500), clickable = true)) }
        assertNull(TikTokSoundSelection.outsidePoint(blocked))
    }
    @Test fun pickerThatAutomaticallyClosesRequiresTwoAttachedTrackObservations() {
        val result = flow(after = listOf(composer(track)))
        assertTrue(result.verified)
        assertEquals(listOf("open", "tap", "confirmed"), result.events)
    }

    @Test fun transientAttachedTrackDoesNotConfirm() {
        assertFalse(flow(after = listOf(composer(track), composer("Different track"))).verified)
    }

    @Test fun encodedTrackTitlesMatchTheDecodedComposerWithoutLosingIdentity() {
        assertEquals("Don't Run Away", TikTokSoundSelection.normalizedTitle("Don&#x27;t Run Away"))
        assertTrue(TikTokSoundSelection.composerConfirmed(composer("Don't Run Away"), "Don&#x27;t Run Away"))
        assertFalse(TikTokSoundSelection.composerConfirmed(composer("Don't Run Away remix"), "Don&#x27;t Run Away"))
    }

    @Test fun decorativePlaybackChangesDoNotInvalidateTheSameLiveTarget() {
        val before = picker().let { it.copy(nodes = it.nodes +
            node("0/2/0/1", bounds = Bounds(50, 910, 90, 950))) }
        val expected = TikTokSoundSelection.rows(before).single()
        val animated = before.change("0/2/0/1") { it.copy(selected = true, bounds = Bounds(51, 910, 91, 950)) }
        assertNotNull(TikTokSoundSelection.matchingRow(animated, expected))
        assertNull(TikTokSoundSelection.matchingRow(animated.change("0/2/0/0") { it.copy(text = "Other track") }, expected))
        assertNull(TikTokSoundSelection.matchingRow(animated.change("0/2/0") { it.copy(bounds = Bounds(40, 901, 960, 1150)) }, expected))
    }

    @Test fun artistChangeStillInvalidatesLiveTarget() {
        val before = picker().let { it.copy(nodes = it.nodes +
            node("0/2/0/1", "tv_author", "Artist A", Bounds(100, 1060, 700, 1140))) }
        val expected = TikTokSoundSelection.rows(before).single()
        assertNull(TikTokSoundSelection.matchingRow(before.change("0/2/0/1") { it.copy(text = "Artist B") }, expected))
    }

    @Test fun transientMissingWindowRecoversWithoutReopeningOrRetapping() = runBlocking {
        var clock = 0L
        var opened = false
        var tapped = false
        var dismissed = false
        var opens = 0
        var taps = 0
        val result = TikTokSoundSelection.select(
            observe = { when {
                dismissed -> composer(track)
                tapped -> picker(selected = true)
                opened && clock < 20_000 -> null
                opened -> picker()
                else -> composer()
            } },
            openPicker = { opened = true; opens++; true },
            tapRow = { _, _ -> tapped = true; taps++; true },
            dismiss = { _, _ -> dismissed = true; true },
            pause = { clock += 1000 }, now = { clock })
        assertTrue(result)
        assertEquals(1, opens)
        assertEquals(1, taps)
    }

    @Test fun prolongedMissingWindowStopsWithSpecificReason() = runBlocking {
        var clock = 0L
        var opened = false
        var reason = ""
        assertFalse(TikTokSoundSelection.select(
            observe = { if(opened) null else composer() },
            openPicker = { opened = true; true }, tapRow = { _, _ -> fail("No row"); false },
            dismiss = { _, _ -> false }, pause = { clock += 1000 },
            report = { state, _ -> reason = state }, now = { clock }))
        assertEquals("PICKER_WINDOW_UNAVAILABLE", reason)
        assertTrue(clock <= 31_000)
    }

    @Test fun ownerPauseStopsBeforeOpeningPicker() = runBlocking {
        var reason = ""
        assertFalse(TikTokSoundSelection.select(observe = { composer() },
            openPicker = { fail("Owner paused"); false }, tapRow = { _, _ -> false },
            dismiss = { _, _ -> false }, pause = {}, allowed = { false },
            report = { state, _ -> reason = state }))
        assertEquals("OWNER_PAUSED", reason)
    }

}
