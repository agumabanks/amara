package co.sanaa.agent.modules

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WhatsAppCommerceTest {
    private fun profile()=JSONObject("""{"seller_delivery_enabled":1,"radius_max_km":5,"shop":{"name":"Example","verification_status":1},"delivery":{"enabled":1,"origin_lat":0,"origin_lng":0,"radius_km":5,"pricing_mode":"base_per_km","base_fee":1000,"per_km_fee":500,"min_fee":1000,"max_fee":4000}}""")
    @Test fun requiresPinAndConfiguredRates() {
        assertNull(WhatsAppCommerce.location("Deliver to Ntinda"))
        assertFalse(WhatsAppCommerce.quote(profile(),null).getBoolean("eligible"))
        assertFalse(WhatsAppCommerce.quote(JSONObject(),0.0 to 0.0).getBoolean("eligible"))
    }
    @Test fun computesRoundedTerminalFeeAndRejectsOutsideRadius() {
        assertEquals(1600L,WhatsAppCommerce.quote(profile(),0.01 to 0.0).getLong("fee_ugx"))
        assertFalse(WhatsAppCommerce.quote(profile(),1.0 to 1.0).getBoolean("eligible"))
    }
    @Test fun rejectsDisabledAndInvalidRates() {
        val p=profile();p.getJSONObject("delivery").put("enabled",0)
        assertFalse(WhatsAppCommerce.quote(p,0.0 to 0.0).getBoolean("eligible"))
        p.getJSONObject("delivery").put("enabled",1).put("base_fee",-100)
        assertFalse(WhatsAppCommerce.quote(p,0.0 to 0.0).getBoolean("eligible"))
    }
    @Test fun paymentCodesAreTerminalOnlyAndNeverNullStrings() {
        val p=profile();p.getJSONObject("shop").put("mtn_merchant_code","123456").put("airtel_merchant_code",JSONObject.NULL)
        val reply=WhatsAppCommerce.paymentInstructions(p)
        assertTrue(reply.contains("MTN MoMo merchant code: 123456"))
        assertFalse(reply.contains("null"));assertFalse(reply.contains("Airtel"))
        assertTrue(WhatsAppCommerce.paymentInstructions(profile()).contains("hasn’t provided"))
    }
    @Test fun parsesCoordinatesOnlyWhenExplicitAndValid() {
        assertEquals(0.32 to 32.58,WhatsAppCommerce.location("https://maps.google.com/?q=0.32,32.58"))
        assertNull(WhatsAppCommerce.location("location: 99,200"))
        assertNull(WhatsAppCommerce.location("My budget is 20,000"))
    }
    @Test fun changedAddressNeverReusesAnOldPin() {
        val previous = "Please deliver here: location: 0.32,32.58"
        assertEquals(0.32 to 32.58, WhatsAppCommerce.deliveryLocation("What is the delivery fee?", previous))
        assertNull(WhatsAppCommerce.deliveryLocation("Actually deliver to Ntinda instead", previous))
        assertNull(WhatsAppCommerce.deliveryLocation("location: 99,200", previous))
        assertNull(WhatsAppCommerce.deliveryLocation("What is the delivery fee?", "$previous\nChange the address to Ntinda"))
        assertEquals(0.35 to 32.6, WhatsAppCommerce.deliveryLocation("Instead use location: 0.35,32.6", previous))
    }
    @Test fun paymentProblemsDoNotTriggerInstructionsToPayAgain() {
        assertFalse(WhatsAppCommerce.paymentQuestion("I paid using airtel"))
        assertFalse(WhatsAppCommerce.paymentQuestion("My payment failed"))
        assertFalse(WhatsAppCommerce.paymentQuestion("I need a momo refund"))
        assertTrue(WhatsAppCommerce.paymentQuestion("How do I pay with momo?"))
    }
}
