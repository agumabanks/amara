package co.sanaa.agent.actions

import org.junit.Assert.*
import org.junit.Test

class SokoFieldBindingTest {
    @Test fun namedPriceFieldCannotSelectNameOrAnotherNumber() {
        val nodes=listOf(
            SokoFieldBinding.Node("Printer",listOf("Product Name"),true),
            SokoFieldBinding.Node("Selling Price",listOf("Selling Price"),false),
            SokoFieldBinding.Node("45000",emptyList(),true),
            SokoFieldBinding.Node("8",listOf("Stock Qty"),true))
        assertEquals(2,SokoFieldBinding.select(nodes,"price"))
        assertEquals(3,SokoFieldBinding.select(nodes,"stock"))
        assertNull(SokoFieldBinding.select(nodes,"discount"))
        assertNull(SokoFieldBinding.select(nodes+nodes[3],"stock"))
    }
    @Test fun fieldValuesMustMatchExactlyAndRenamesReopenTheNewTitle() {
        assertFalse(SokoFieldBinding.matches("description","Clean","Clean but broken"))
        assertTrue(SokoFieldBinding.matches("selling price","45000","45,000.00"))
        assertFalse(SokoFieldBinding.matches("selling price","45000","UGX 45000 error"))
        assertEquals("New title",SokoFieldBinding.reopenTitle("Old title","name","New title"))
        assertEquals("Old title",SokoFieldBinding.reopenTitle("Old title","price","45000"))
    }
}
