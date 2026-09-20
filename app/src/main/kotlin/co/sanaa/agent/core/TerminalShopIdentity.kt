package co.sanaa.agent.core

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

data class TerminalShopIdentity(val sellerId: Long, val shopId: Long, val name: String, val expiresAt: Long, val assertion: String) {
    val scope: String get() = "$sellerId:$shopId"
    companion object {
        /** Bounded, authenticated demand refresh; never reuse an expired proof. */
        @Synchronized fun readFresh(context: Context): TerminalShopIdentity {
            val current = runCatching { read(context) }.getOrNull()
            if (current != null && current.expiresAt - System.currentTimeMillis()/1000 > 30) {
                acknowledgeScope(context, current)
                return current
            }
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                "Shop verification refresh must run in background"
            }
            val response = context.contentResolver.call(Uri.parse("content://${SokoTerminalBridge.PACKAGE}.amara"),
                "refresh_identity", null, null)
            check(response?.getBoolean("refreshed") == true) { "Terminal shop verification could not refresh; open Terminal to restore its session." }
            val refreshed = read(context)
            check(refreshed.expiresAt - System.currentTimeMillis()/1000 > 30) { "Terminal shop identity refresh did not provide a fresh proof." }
            acknowledgeScope(context, refreshed, event = "terminal_identity_refreshed")
            return refreshed
        }
        private fun acknowledgeScope(context: Context, identity: TerminalShopIdentity, event: String = "terminal_identity_observed") {
            runCatching {
                val prefs = context.getSharedPreferences("terminal_shop_identity", Context.MODE_PRIVATE)
                val previous = prefs.getString("scope", null)
                prefs.edit().putString("scope", identity.scope).putString("name", identity.name).commit()
                EvaluationJournal(context).record(event, fields = JSONObject()
                    .put("shop_scope", identity.scope).put("shop_changed", previous != null && previous != identity.scope)
                    .put("expires_at", identity.expiresAt))
                if (previous != null && previous != identity.scope) {
                    EvaluationJournal(context).record("terminal_shop_changed", fields = JSONObject()
                        .put("old_scope", previous).put("new_scope", identity.scope).put("refresh_required", true))
                }
            }
        }
        fun read(context: Context): TerminalShopIdentity {
            val assertion = context.contentResolver.query(
                Uri.parse("content://${SokoTerminalBridge.PACKAGE}.amara/identity"), arrayOf("assertion"), null, null, null
            )?.use { c -> if(c.moveToFirst()) c.getString(0) else null }.orEmpty()
            check(assertion.isNotBlank()) { "Open Soko Terminal to verify the logged-in shop before business automation." }
            return verify(assertion, context.assets.open("soko-identity-public.pem").bufferedReader().use { it.readText() })
        }
        internal fun verify(assertion: String, pem: String, now: Long = System.currentTimeMillis()/1000): TerminalShopIdentity {
            val parts=assertion.split('.')
            require(parts.size==2 && assertion.length<4096) { "Terminal identity is invalid" }
            val bytes=Base64.decode(pem.replace(Regex("-----[^-]+-----|\\s"),""),Base64.DEFAULT)
            val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
            val signature=Signature.getInstance("SHA256withRSA").apply { initVerify(key);update(parts[0].toByteArray(Charsets.UTF_8)) }
            check(signature.verify(Base64.decode(parts[1],Base64.URL_SAFE or Base64.NO_WRAP))) { "Terminal identity signature is invalid" }
            val json=JSONObject(String(Base64.decode(parts[0],Base64.URL_SAFE or Base64.NO_WRAP),Charsets.UTF_8))
            val expires=json.optLong("exp");val issued=json.optLong("iat")
            check(json.optString("aud")=="amara-shop-read" && expires>now && issued<=now+30 && expires-issued in 1..180 && json.optLong("seller_id")>0 && json.optLong("shop_id")>0) { "Terminal shop identity expired; open Terminal to refresh it." }
            return TerminalShopIdentity(json.getLong("seller_id"),json.getLong("shop_id"),json.getString("shop_name"),expires,assertion)
        }
    }
}
