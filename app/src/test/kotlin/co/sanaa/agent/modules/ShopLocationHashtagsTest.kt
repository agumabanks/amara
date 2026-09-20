package co.sanaa.agent.modules
import org.junit.Assert.*
import org.junit.Test
class ShopLocationHashtagsTest {
    @Test fun tagsOnlyPlacesActuallyPresentInShopAddress() {
        assertEquals(listOf("#NasserRoad","#Kampala"),ShopLocationHashtags.fromAddress("Nasser road Kampala"))
        assertEquals(listOf("#Jinja","#Uganda"),ShopLocationHashtags.fromAddress("Plot 12, Jinja, Uganda"))
        assertTrue(ShopLocationHashtags.fromAddress("Kampalaville 123 +256700000001").isEmpty())
        assertTrue(ShopLocationHashtags.fromAddress("null").isEmpty())
    }
}
