package co.sanaa.agent.actions

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class TikTokProfileReturnTest {
    private fun captured(): TikTokSoundSelection.Frame {
        val doc = javaClass.getResourceAsStream("/tiktok/46.9.3/expanded-photo.xml")!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val elements = doc.getElementsByTagName("node")
        val nodes = (0 until elements.length).map { i ->
            val e = elements.item(i) as Element
            val b = Regex("-?\\d+").findAll(e.getAttribute("bounds")).map { it.value.toInt() }.toList()
            TikTokSoundSelection.Node("$i", null, e.getAttribute("package"), 1,
                e.getAttribute("resource-id"), e.getAttribute("class"), e.getAttribute("text"),
                e.getAttribute("content-desc"), TikTokSoundSelection.Bounds(b[0], b[1], b[2], b[3]),
                enabled = e.getAttribute("enabled") == "true", clickable = e.getAttribute("clickable") == "true",
                editable = e.getAttribute("class").endsWith("EditText"))
        }
        return TikTokSoundSelection.Frame(TikTokSoundSelection.PACKAGE, 1,
            TikTokSoundSelection.Bounds(0, 0, 720, 1600), nodes)
    }

    @Test fun capturedPhotoReaderCanReturnToProfileNavigation() {
        assertTrue(TikTokProfileReturn.canLeaveExpandedPhoto(captured()))
    }

    @Test fun commentDraftAndUploadEditorCannotBeDiscarded() {
        val frame = captured()
        assertFalse(TikTokProfileReturn.canLeaveExpandedPhoto(frame.copy(nodes = frame.nodes.map {
            if (it.editable) it.copy(text = "Unsent comment") else it
        })))
        assertFalse(TikTokProfileReturn.canLeaveExpandedPhoto(frame.copy(nodes = frame.nodes.map {
            if (it.editable) it.copy(id = "upload_caption", text = "") else it
        })))
    }

    @Test fun foreignHiddenOrIncompleteReaderIsRejected() {
        val frame = captured()
        assertFalse(TikTokProfileReturn.canLeaveExpandedPhoto(frame.copy(packageName = "other.app")))
        assertFalse(TikTokProfileReturn.canLeaveExpandedPhoto(frame.copy(nodes = frame.nodes.map {
            if (it.description == "Share") it.copy(visible = false) else it
        })))
        assertFalse(TikTokProfileReturn.canLeaveExpandedPhoto(frame.copy(nodes = frame.nodes.filterNot {
            it.id.endsWith("/skr")
        })))
    }
}
