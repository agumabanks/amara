package co.sanaa.agent.modules

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WhatsAppCommerceAssistantTest {
    private fun assistant() = WhatsAppCommerceAssistant({ JSONObject("""{
        "seller_delivery_enabled":1,"radius_max_km":5,
        "shop":{"name":"Example","verification_status":1,"mtn_merchant_code":"123456"},
        "delivery":{"enabled":1,"origin_lat":0,"origin_lng":0,"radius_km":5,
        "pricing_mode":"flat","base_fee":2000}}
        """) }, { emptyList() })

    @Test fun answersBothPartsOfDeliveryAndPaymentQuestion() = runBlocking {
        val reply = assistant().composeReply("How do I pay and what is delivery for location: 0.01,0.01?", "")!!
        assertTrue(reply.contains("MTN MoMo merchant code: 123456"))
        assertTrue(reply.contains("Delivery to that pin is estimated at UGX 2000"))
        assertFalse(reply.contains("landmark"))
    }
    @Test fun replacementAddressNeedsFreshPinInsteadOfOldQuote() = runBlocking {
        val reply = assistant().composeReply("Actually deliver to Ntinda instead", "location: 0.01,0.01")!!
        assertTrue(reply.contains("Google Maps pin"))
        assertFalse(reply.contains("2000"))
    }
    @Test fun paymentComplaintGoesToConversationRatherThanPaymentInstructions() = runBlocking {
        assertNull(assistant().composeReply("I paid using momo but you haven't received it", ""))
    }
}
