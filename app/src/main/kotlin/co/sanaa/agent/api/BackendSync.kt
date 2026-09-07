package co.sanaa.agent.api

import android.content.Context
import android.provider.Settings
import co.sanaa.agent.core.Redactor
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.AmaraMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BackendSync(private val context: Context, private val config: SecureConfig, private val memory: AmaraMemory? = null) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    suspend fun registerAndSync(): JSONObject {
        require(config.configSyncEnabled) { "Configuration sync is disabled by the owner." }
        ensureDeviceId()
        if (config.agentToken.isBlank()) {
            val response = post("register", JSONObject()
                .put("device_id", config.deviceId).put("agent_name", config.agentName)
                .put("business_name", config.businessName).put("owner_phone", config.ownerPhone), authenticated = false)
            config.agentToken = response.getString("agent_token")
        }
        return fetchConfig()
    }

    suspend fun fetchConfig(): JSONObject {
        require(config.configSyncEnabled) { "Configuration sync is disabled by the owner." }
        return get("config/${config.deviceId}").also(config::saveRemoteConfig)
    }

    suspend fun status(): JSONObject {
        require(config.configSyncEnabled) { "Configuration sync is disabled by the owner." }
        return get("status/${config.deviceId}")
    }

    suspend fun log(module: String, action: String, platform: String?, summary: String, success: Boolean, escalated: Boolean = false, error: String? = null, metadata: JSONObject? = null, recordMemory: Boolean = true): Boolean {
        if (recordMemory) runCatching {
            memory?.recordAction(
                type = "$module:$action", targetContact = null, targetApp = platform,
                command = Redactor.redact(summary), whatAmaraDid = Redactor.redact(summary),
                result = if (success) "Completed." else (error?.let(Redactor::redact) ?: "Failed."), groqResponse = null, success = success,
            )
        }
        // Telemetry is strictly opt-in and always redacted before export.
        val decision = co.sanaa.agent.core.TelemetryPolicy.gate(config.telemetryOptIn, summary)
        if (!decision.allowed) return false
        val errorDecision = error?.let { co.sanaa.agent.core.TelemetryPolicy.gateError(true, it) }
        return runCatching {
            post("log", JSONObject().put("device_id", config.deviceId).put("module", module).put("action", action)
            .put("platform", platform).put("summary", decision.payload).put("success", success).put("escalated", escalated)
            .put("error_message", errorDecision?.payload).put("metadata", metadata))
            true
        }.getOrDefault(false)
    }

    // Owner-directed control channel: escalations reach the owner-configured backend so
    // handoffs are never silently dropped. Content is still redacted before export;
    // bulk operational logging remains gated behind telemetryOptIn.
    suspend fun escalate(message: String, context: String, urgency: String, replies: List<String>): Boolean = runCatching {
        post("escalate", JSONObject().put("device_id", config.deviceId).put("agent_name", config.agentName)
            .put("message", Redactor.redactForExport(message)).put("context", Redactor.redactForExport(context)).put("urgency", urgency).put("suggested_replies", JSONArray(replies.map(Redactor::redact))))
        true
    }.getOrDefault(false)

    suspend fun generateAd(listing: SokoListing): JSONObject {
        // Listing content leaves the device only after the owner opted into artifact upload.
        require(config.artifactUploadOptIn) { "Artifact upload is not opted in; listing data stayed on the device." }
        return post("generate-ad", JSONObject().put("device_id", config.deviceId).put("listing", listing.raw))
    }

    suspend fun saveMemorySnapshot(snapshot: JSONObject): JSONObject {
        require(config.memoryBackupEnabled) { "Memory backup is disabled by the owner." }
        ensureRegistered()
        return post("memory", JSONObject()
            .put("device_id", config.deviceId)
            .put("schema_version", snapshot.optInt("schema_version", 1))
            .put("snapshot", snapshot))
    }

    suspend fun latestMemorySnapshot(): JSONObject {
        require(config.memoryBackupEnabled) { "Memory backup is disabled by the owner." }
        ensureRegistered()
        return get("memory/${config.deviceId}")
    }

    private suspend fun ensureRegistered() {
        ensureDeviceId()
        if (config.agentToken.isBlank()) registerAndSync()
    }

    private fun ensureDeviceId() {
        if (config.deviceId.isBlank()) config.deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
    }

    private suspend fun get(path: String): JSONObject = request(Request.Builder().url(url(path)).get().authenticated().build())
    private suspend fun post(path: String, body: JSONObject, authenticated: Boolean = true): JSONObject {
        val builder = Request.Builder().url(url(path)).post(body.toString().toRequestBody(JSON)).header("Accept", "application/json")
        if (authenticated) builder.authenticated()
        return request(builder.build())
    }

    private suspend fun request(request: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Backend returned HTTP ${response.code}: $body")
            JSONObject(body)
        }
    }

    private fun Request.Builder.authenticated() = apply {
        if (config.agentToken.isBlank()) throw IllegalStateException("Agent is not registered")
        header("Authorization", "Bearer ${config.agentToken}")
    }
    private fun url(path: String) = "${config.backendUrl.trimEnd('/')}/${path.trimStart('/')}"
    companion object { private val JSON = "application/json".toMediaType() }
}
