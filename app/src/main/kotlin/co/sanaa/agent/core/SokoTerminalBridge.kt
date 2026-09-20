package co.sanaa.agent.core

import android.content.Context
import android.net.Uri

/** Discover Terminal's supported contract; never open or copy another app's database. */
class SokoTerminalBridge(private val context: Context) {
    fun status(): Map<String, Any> {
        val installed = context.packageManager.getLaunchIntentForPackage(PACKAGE) != null
        val base = mapOf<String, Any>("installed" to installed, "package" to PACKAGE,
            "businessData" to "authenticated seller-scoped backend",
            "sharedFolderReady" to SokoSharedFolder(context).isReady())
        if (!installed) return base + ("state" to "terminal_missing")
        return runCatching {
            context.contentResolver.query(Uri.parse("content://$PACKAGE.amara/context"),
                arrayOf("protocol_version", "package_name", "database_name", "database_access"), null, null, null)?.use { c ->
                check(c.moveToFirst() && c.getInt(0) in 1..2 && c.getString(1) == PACKAGE) { "Unsupported Terminal bridge" }
                base + mapOf("state" to "connected", "protocolVersion" to c.getInt(0),
                    "databaseName" to c.getString(2), "databaseAccess" to c.getString(3))
            } ?: (base + ("state" to "terminal_update_needed"))
        }.getOrElse { base + ("state" to "terminal_update_needed") }
    }
    fun shopStatus(): Map<String, Any> = runCatching {
        val shop=TerminalShopIdentity.read(context)
        mapOf<String,Any>("verified" to true, "shopName" to shop.name, "scope" to shop.scope, "expiresAt" to shop.expiresAt)
    }.getOrElse { mapOf("verified" to false,"reason" to (it.message ?: "Open Terminal to verify its logged-in shop")) }
    companion object { const val PACKAGE = "com.soko24.soko_seller_terminal" }
}
