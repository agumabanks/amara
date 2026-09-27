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
    @Test fun capturedHomeDistinguishesGlobalSearchFromContextSearch() {
        val document = javaClass.getResourceAsStream("/tiktok/47.0.3/home-search-controls.xml")!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val nodes = document.getElementsByTagName("node")
        val search = (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("content-desc") == "Search" }
        assertEquals(2, search.size)
        val global = search.filter { it.getAttribute("resource-id").substringAfter(":id/") in TikTokSocialControls.ids("global_search") }
        assertEquals(1, global.size)
        assertEquals("true", global.single().getAttribute("clickable"))
    }
    @Test fun capturedProfileResolvesDisplayNameSeparatelyFromHandle() {
        val document = javaClass.getResourceAsStream("/tiktok/47.0.3/owner-profile-controls.xml")!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val nodes = document.getElementsByTagName("node")
        val names = (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("resource-id").substringAfter(":id/") in TikTokSocialControls.ids("profile_name") }
            .map { it.getAttribute("text") }.distinct()
        assertEquals(listOf("Example Business"), names)
    }
    @Test fun capturedTabletHasUnlabelledSearchAndDistinctDisplayName() {
        fun nodes(file: String): List<Element> {
            val document=javaClass.getResourceAsStream("/tiktok/47.0.3/$file")!!.use {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
            }
            val nodes=document.getElementsByTagName("node")
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }
        val home=nodes("tps-home-controls.xml")
        val search=home.single { it.getAttribute("resource-id").substringAfter(":id/") in TikTokSocialControls.ids("global_search") }
        assertEquals("true", search.getAttribute("clickable"))
        assertEquals("", search.getAttribute("content-desc"))
        assertTrue(home.any { it.getAttribute("content-desc")=="Home" && it.getAttribute("selected")=="true" })
        val profile=nodes("tps-owner-profile-controls.xml")
        val name=profile.single { it.getAttribute("resource-id").substringAfter(":id/") in TikTokSocialControls.ids("profile_name") }
        assertEquals("Example Business",name.getAttribute("text"))
    }
    @Test fun oldControlsRemainSupportedAndUnknownControlsAreNotGuessed() {
        assertTrue(TikTokSocialControls.ids("efv").containsAll(listOf("efv", "eir", "ej4")))
        assertTrue(TikTokSocialControls.ids("l8h").contains("v_touch_area"))
        assertTrue(TikTokSocialControls.ids("eg4").contains("ejs"))
        assertTrue(TikTokSocialControls.ids("cz_").contains("d1m"))
        assertTrue(TikTokSocialControls.ids("f15").contains("f4t"))
        assertTrue(TikTokSocialControls.ids("ekn").contains("eob"))
        assertEquals(listOf("unknown"), TikTokSocialControls.ids("unknown"))
    }
}
