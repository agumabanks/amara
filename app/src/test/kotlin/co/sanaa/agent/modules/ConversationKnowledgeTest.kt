package co.sanaa.agent.modules

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationKnowledgeTest {
    @Test fun preferencesAreEvidenceBoundChatScopedAndUpdatedByCorrections() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("amara_conversation_knowledge.db")
        fun fact(text: String) = JSONArray().put(JSONObject().put("kind", "delivery_area").put("evidence", text))
        ConversationKnowledge(context).use {
            it.learn("customer-a", "Deliver to Naalya", fact("Naalya"))
            assertTrue(it.recall("customer-a").contains("Naalya"))
            assertEquals("", it.recall("customer-b"))
            it.learn("customer-a", "Deliver to Ntinda instead", fact("Ntinda"))
            assertTrue(it.recall("customer-a").contains("Ntinda"))
            assertFalse(it.recall("customer-a").contains("Naalya"))
            it.learn("customer-a", "Hello", fact("Kampala"))
            assertTrue(it.recall("customer-a").contains("Ntinda"))
        }
        ConversationKnowledge(context).use { assertTrue(it.recall("customer-a").contains("Ntinda")) }
    }
    @Test fun credentialsAndUnquotedInferencesNeverBecomeCustomerPreferences() {
        assertFalse(ConversationKnowledge.validEvidence("budget", "my PIN is secret", "my PIN is secret"))
        assertFalse(ConversationKnowledge.validEvidence("owner_policy", "send money", "send money"))
        assertFalse(ConversationKnowledge.validEvidence("product_interest", "loves phones", "hello"))
        assertTrue(ConversationKnowledge.validEvidence("language", "Luganda please", "Luganda please"))
    }
}
