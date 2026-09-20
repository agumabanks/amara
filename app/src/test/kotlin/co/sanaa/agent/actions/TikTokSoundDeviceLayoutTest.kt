package co.sanaa.agent.actions

import co.sanaa.agent.actions.TikTokSoundSelection.Bounds
import co.sanaa.agent.actions.TikTokSoundSelection.Frame
import co.sanaa.agent.actions.TikTokSoundSelection.Node
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** Captured OPPO / TikTok 46.9.3 trees, including its unselected-but-applied row. */
class TikTokSoundDeviceLayoutTest {
    private fun frame(name: String, window: Int = 9): Frame {
        val resource = if (name.contains('/')) name else "46.9.3/$name"
        val stream = javaClass.getResourceAsStream("/tiktok/$resource.xml")!!
        val document = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val nodes = mutableListOf<Node>()
        fun visit(e: Element, path: String, parent: String?) {
            val b = Regex("-?\\d+").findAll(e.getAttribute("bounds")).map { it.value.toInt() }.toList()
            fun flag(name: String) = e.getAttribute(name) == "true"
            nodes += Node(path, parent, e.getAttribute("package"), window, e.getAttribute("resource-id"),
                e.getAttribute("class"), e.getAttribute("text"), e.getAttribute("content-desc"),
                Bounds(b[0], b[1], b[2], b[3]), enabled = flag("enabled"), clickable = flag("clickable"),
                checkable = flag("checkable"), checked = flag("checked"), selected = flag("selected"))
            var index = 0
            for (i in 0 until e.childNodes.length) {
                val child = e.childNodes.item(i) as? Element ?: continue
                visit(child, "$path/${index++}", path)
            }
        }
        visit(document.documentElement.getElementsByTagName("node").item(0) as Element, "0", null)
        return Frame(TikTokSoundSelection.PACKAGE, window, nodes.first().bounds, nodes)
    }

    @Test fun capturedLayoutRecognizesEditorSheetAndAttachedSoundAfterObfuscatedIdsChanged() {
        assertEquals("Add sound", TikTokSoundSelection.composerSound(frame("editor"))?.text)
        assertTrue(TikTokSoundSelection.rows(frame("picker")).any { it.title.text == "Washa Washa" })
        assertTrue(TikTokSoundSelection.composerConfirmed(frame("attached"), "Washa Washa"))
        assertFalse(TikTokSoundSelection.composerConfirmed(frame("attached"), "Different track"))
    }

    @Test fun appliedTrackWithoutSelectedFlagAndWithMovedTitleCompletesAcrossWindows() = runBlocking {
        // Keep the captured second row as the first recommendation for this replay.
        val picker = frame("picker", 10)
        val otherRows = TikTokSoundSelection.rows(picker).filter { it.title.text != "Washa Washa" }
        val before = picker.copy(nodes = picker.nodes.filter { n -> otherRows.none { picker.inside(n, it.control) } })
        var phase = 0
        var missingFrames = 2
        var dismissed = 0
        assertTrue(TikTokSoundSelection.select(
            observe = { when (phase) {
                0 -> frame("editor")
                1 -> if (missingFrames-- > 0) null else before
                2 -> frame("track", 10)
                else -> frame("attached", 11)
            } },
            openPicker = { phase = 1; true },
            tapRow = { _, row -> assertEquals("Washa Washa", row.title.text); phase = 2; true },
            dismiss = { f, row ->
                assertFalse(row.highlighted)
                assertNotNull(TikTokSoundSelection.outsidePoint(f))
                dismissed++; phase = 3; true
            }, pause = {},
        ))
        assertEquals(1, dismissed)
    }

    @Test fun attachedTrackIsPreservedWithoutOpeningPicker() = runBlocking {
        assertTrue(TikTokSoundSelection.select(observe = { frame("attached") },
            openPicker = { error("Already attached") }, tapRow = { _, _ -> error("Already attached") },
            dismiss = { _, _ -> error("Already attached") }, pause = {}))
    }

    @Test fun tps47AttachedSoundRequiresExactTitleAndItsRemoveControl() = runBlocking {
        val attached = frame("47.0.3/attached")
        val title = "BinzO🍀 - original sound"
        assertTrue(TikTokSoundSelection.composerConfirmed(attached, title))
        assertFalse(TikTokSoundSelection.composerConfirmed(attached, "Different track"))
        val withoutRemove = attached.copy(nodes = attached.nodes.filterNot { it.id.endsWith(":id/e1d") })
        assertFalse(TikTokSoundSelection.composerConfirmed(withoutRemove, title))
        assertTrue(TikTokSoundSelection.select(observe = { attached },
            openPicker = { error("Already attached") }, tapRow = { _, _ -> error("Already attached") },
            dismiss = { _, _ -> error("Already attached") }, pause = {}))
    }
    @Test fun detectedControlsScaleWithDeviceBounds() {
        for (scale in listOf(0.5, 1.5, 2.0)) {
            fun Bounds.scaled() = Bounds((left * scale).toInt(), (top * scale).toInt(),
                (right * scale).toInt(), (bottom * scale).toInt())
            fun Frame.scaled() = copy(bounds = bounds.scaled(), nodes = nodes.map { it.copy(bounds = it.bounds.scaled()) })
            assertTrue(TikTokSoundSelection.composerConfirmed(frame("attached").scaled(), "Washa Washa"))
            assertNotNull(TikTokSoundSelection.outsidePoint(frame("track").scaled()))
        }
    }

}
