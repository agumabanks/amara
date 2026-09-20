package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoApiClient
import kotlinx.coroutines.withTimeoutOrNull

class WhatsAppCommerceAssistant internal constructor(
    private val loadProfile: suspend () -> org.json.JSONObject,
    private val loadOfferings: suspend () -> List<co.sanaa.agent.api.SokoListing>,
) {
    constructor(soko: SokoApiClient) : this(soko::commerceProfile, soko::promotableOfferings)
    suspend fun composeReply(message: String, history: String): String? {
        if (!WhatsAppCommerce.paymentQuestion(message) && !WhatsAppCommerce.deliveryQuestion(message)) return null
        return withTimeoutOrNull(12_000) {
            val profile = loadProfile()
            val replies = mutableListOf<String>()
            if (WhatsAppCommerce.paymentQuestion(message)) replies += run {
                val methods=WhatsAppCommerce.paymentInstructions(profile)
                val offerings=loadOfferings()
                val matches=offerings.filter { it.title.length>=5 && (history+"\n"+message).contains(it.title,true) }
                val item=matches.singleOrNull()
                val links=profile.optJSONArray("payment_links")
                val matching=if(item==null || links==null) emptyList() else (0 until links.length()).mapNotNull { links.optJSONObject(it) }.filter {
                    it.optString("linkable_id")==item.id.removePrefix("service:") &&
                        it.optString("linkable_type")==if(item.id.startsWith("service:")) "App\\Models\\ServiceOffering" else "App\\Models\\Product"
                }
                val link=matching.singleOrNull()?.takeIf { it.optString("currency").equals("UGX",true) && it.optDouble("amount",0.0)>0 }
                if(link==null) methods else {
                    val url=link.optString("url")
                    val uri=runCatching { java.net.URI(url) }.getOrNull()
                    if(uri?.scheme!="https" || uri.host !in setOf("soko24.co","www.soko24.co") || !uri.path.startsWith("/pay/")) methods
                    else "${item!!.title}: Soko payment link for UGX ${link.getDouble("amount").toLong()}:\n$url\nConfirm the quantity and delivery total at checkout before paying.\n\n$methods"
                }
            }
            if (WhatsAppCommerce.deliveryQuestion(message)) replies += run {
                val pin=WhatsAppCommerce.deliveryLocation(message, history)
                val quote=WhatsAppCommerce.quote(profile,pin)
                if(quote.optBoolean("eligible")) "Delivery to that pin is estimated at UGX ${quote.getLong("fee_ugx")}. You’ll see the final total at checkout."
                else if(pin==null) "Please send a Google Maps pin for the delivery address so I can check the fee."
                else "The manager needs to confirm the delivery fee for that address. I don’t have a reliable quote yet."
            }
            replies.joinToString("\n\n")
        } ?: "I can’t check the current delivery or payment details right now. The manager needs to confirm them before you pay."
    }
}
