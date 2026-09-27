package co.sanaa.agent.actions

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class TikTokSocialTextTest {
    @Test fun expandedCurrentLayoutYieldsFullCreatorAndCaption() {
        val document = javaClass.getResourceAsStream("/tiktok/47.0.3/social-expanded.xml")!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val nodes = document.getElementsByTagName("node")
        val values = (0 until nodes.length).map { nodes.item(it) as Element }
        fun text(id: String) = values.filter { it.getAttribute("resource-id").endsWith(":id/$id") }
            .map { it.getAttribute("text").trim() }.filter(String::isNotBlank).distinct()
        assertEquals("sales representative", TikTokSocialText.creator(::text))
        val caption = TikTokSocialText.caption(::text)
        assertNotNull(caption)
        assertTrue(caption!!.startsWith("HOW TO SELL ONE PRODUCT DIFFERENT WAYS\n"))
        assertTrue(caption.endsWith("#BusinessGrowth"))
        assertTrue(caption.length > 1000)
    }

    @Test fun truncatedPreviewIsNeverTreatedAsCompletePost() {
        val values = mapOf("slp" to listOf("HOW TO SELL ONE PRODUCT DIFFERENT WAYS"),
            "skr" to listOf("One business has buyers who happily pay more…"),
            "k2w" to listOf("sales representative"))
        assertNull(TikTokSocialText.caption { values[it].orEmpty() })
        assertEquals("sales representative", TikTokSocialText.creator { values[it].orEmpty() })
    }

    @Test fun tabletExpandedFeedCaptionAndCreatorAreReadable() {
        val values = mapOf(
            "desc" to listOf("Need sharper packaging labels? This complete example explains the print finish and delivery options."),
            "tv_toggle" to listOf("less"),
            "title" to listOf("Example Business"),
        )
        assertEquals(values["desc"]!!.single(), TikTokSocialText.caption { values[it].orEmpty() })
        assertEquals("Example Business", TikTokSocialText.creator { values[it].orEmpty() })
    }

    @Test fun tabletCollapsedFeedPreviewIsRejected() {
        val values = mapOf(
            "desc" to listOf("Need sharper packaging labels? This preview is incomplete…"),
            "tv_toggle" to listOf("more"),
            "title" to listOf("Example Business"),
        )
        assertNull(TikTokSocialText.caption { values[it].orEmpty() })
    }

    @Test fun shortCompleteTabletCaptionIsReadableButTruncatedPreviewIsNot() {
        val complete = mapOf("desc" to listOf("New arrivals"), "title" to listOf("Example Business"))
        assertEquals("New arrivals", TikTokSocialText.caption { complete[it].orEmpty() })
        val truncated = mapOf("desc" to listOf("New arrivals"), "tv_toggle" to listOf("more"))
        assertNull(TikTokSocialText.caption { truncated[it].orEmpty() })
    }
}
