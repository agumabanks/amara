package co.sanaa.agent.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/** A working Wi-Fi connection does not prove that the configured backend exists. */
class BackendReadiness(context: Context) {
    private val prefs = context.getSharedPreferences("backend_readiness", Context.MODE_PRIVATE)
    fun snapshot(): Map<String, Any> = mapOf("checkedAt" to prefs.getLong("at", 0),
        "state" to prefs.getString("state", "NOT_CHECKED").orEmpty(),
        "detail" to prefs.getString("detail", "Backend has not been checked yet").orEmpty())
    suspend fun check(url: String): Map<String, Any> = withContext(Dispatchers.IO) {
        val host = runCatching { URI(url).host }.getOrNull()
        var state = "UNAVAILABLE"
        val detail = if (host.isNullOrBlank()) "Backend URL is invalid" else try {
            val client = OkHttpClient.Builder().dns(co.sanaa.agent.api.BackendDns.forUrl { url }).connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
                .followRedirects(false).build()
            client.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
                state = if (response.code >= 500) "SERVER_ERROR" else "REACHABLE"
                "Backend $host returned HTTP ${response.code}; catalogue authentication is checked separately"
            }
        } catch (e: java.net.UnknownHostException) {
            state = "DNS_FAILED"; "Backend DNS failed for $host; restore its DNS or configure the intended backend"
        } catch (e: Exception) { "Backend $host is unreachable: ${Redactor.safeDiagnostic(e)}" }
        prefs.edit().putString("state", state).putString("detail", detail).putLong("at", System.currentTimeMillis()).commit()
        snapshot()
    }
}
