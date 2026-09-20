package co.sanaa.agent.actions

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class TikTokSocialControlsTest {
    @Test fun capturedExpandedPostExposesCaptionCreatorEntryAndClose() {
        val document = javaClass.getResourceAsStream("/tiktok/46.9.3/social-expanded.xml")!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val nodes = document.getElementsByTagName("node")
        val ids = (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("resource-id").substringAfter(":id/") }.toSet()
        for (role in listOf("efv", "user_name", "l8h", "ywb"))
            assertTrue("Missing observed control $role", TikTokSocialControls.ids(role).any { it in ids })
    }
    @Test fun oldControlsRemainSupportedAndUnknownControlsAreNotGuessed() {
        assertTrue(TikTokSocialControls.ids("efv").containsAll(listOf("efv", "eir", "ej4")))
        assertEquals(listOf("unknown"), TikTokSocialControls.ids("unknown"))
    }
}
