package co.sanaa.agent.actions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WhatsAppGroupNavigationTest {
    @Test fun exactRouteAvoidsUnnecessaryNameSearch() = runBlocking {
        var matched=false
        var searches=0
        assertTrue(WhatsAppGroupNavigation.open({matched},{true},{matched=true},{searches++;false}))
        assertEquals(0,searches)
    }
    @Test fun wrongConversationFromRouteNeverCountsAsVerified() = runBlocking {
        var searches=0
        assertFalse(WhatsAppGroupNavigation.open({false},{true},{},{searches++;true}))
        assertEquals(1,searches)
    }
    @Test fun ExpiredRouteUsesExactSearch() = runBlocking {
        var matched=false
        assertTrue(WhatsAppGroupNavigation.open({matched},{false},{},{matched=true;true}))
    }
}
