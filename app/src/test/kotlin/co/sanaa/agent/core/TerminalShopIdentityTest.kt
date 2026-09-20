package co.sanaa.agent.core
import android.util.Base64
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class TerminalShopIdentityTest {
    private val key=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun b64(bytes: ByteArray)=Base64.encodeToString(bytes,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun proof(expires: Long=1100): String {
        val payload=b64(JSONObject().put("aud","amara-shop-read").put("seller_id",22).put("shop_id",7).put("shop_name","Osa Gadgets").put("iat",1000).put("exp",expires).toString().toByteArray())
        val sig=Signature.getInstance("SHA256withRSA").apply{initSign(key.private);update(payload.toByteArray())}.sign()
        return "$payload.${b64(sig)}"
    }
    private fun pem()="-----BEGIN PUBLIC KEY-----\n${Base64.encodeToString(key.public.encoded,Base64.NO_WRAP)}\n-----END PUBLIC KEY-----"
    @Test fun verifiedProofSelectsImmutableShopInsteadOfDefaultName() {
        val shop=TerminalShopIdentity.verify(proof(),pem(),1050)
        assertEquals("22:7",shop.scope);assertEquals("Osa Gadgets",shop.name)
    }
    @Test fun expiredAndTamperedProofsAreRejected() {
        assertTrue(runCatching { TerminalShopIdentity.verify(proof(),pem(),1100) }.isFailure)
        assertTrue(runCatching { TerminalShopIdentity.verify(proof().replaceFirst(".","x."),pem(),1050) }.isFailure)
        assertTrue(runCatching { TerminalShopIdentity.verify(proof(1400),pem(),1050) }.isFailure)
    }
}
