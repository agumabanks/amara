package co.sanaa.agent.modules

import org.junit.Assert.*
import org.junit.Test

class WhatsAppOriginIdentityTest {
    @Test fun originKeysDistinguishCaseGroupsAndSameNames() {
        val a=WhatsAppConversationRoutes.identity("chatA","notification",false)
        assertNotEquals(a,WhatsAppConversationRoutes.identity("chata","notification",false))
        assertNotEquals(a,WhatsAppConversationRoutes.identity("chatA","notification",true))
        assertEquals(a,WhatsAppConversationRoutes.identity("chatA","updated-notification",false))
        assertNotEquals(WhatsAppConversationRoutes.identity(null,"event1",false),WhatsAppConversationRoutes.identity(null,"event2",false))
        val first=WhatsAppInbound("Sam","hello","Sam",false,conversationIdentity=a)
        assertNotEquals(first.signature,first.copy(conversationIdentity="other-origin").signature)
    }
}
