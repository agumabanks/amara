package co.sanaa.agent.api

import android.content.Context
import android.provider.Settings
import co.sanaa.agent.core.Redactor
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.TerminalShopIdentity
import co.sanaa.agent.core.work.AgentEventOutbox
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BackendSync(private val context: Context, private val config: SecureConfig, private val memory: AmaraMemory? = null) {
    val eventOutbox = AgentEventOutbox(context)
    private val eventSyncMutex = Mutex()
    private val client = OkHttpClient.Builder().dns(BackendDns.forUrl { config.backendUrl })
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build()

    suspend fun registerAndSync(): JSONObject {
        require(co.sanaa.agent.core.OwnerPower(context).isOn()) { "Amara is off by owner request" }
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

    fun pairingDeviceId(): String { ensureDeviceId(); return config.deviceId }

    suspend fun pair(code: String): Boolean {
        require(config.ownerAllowsWork()) { "Turn Amara on before connecting to Cards." }
        require(config.configSyncEnabled) { "Configuration sync is disabled on this device." }
        require(code.matches(Regex("[a-fA-F0-9]{24}"))) { "Enter the 24-character code from Cards Devices." }
        ensureDeviceId()
        val response = post("pair", JSONObject().put("device_id", config.deviceId).put("code", code.lowercase()), authenticated = false)
        config.agentToken = response.getString("agent_token")
        return runCatching { fetchConfig() }.isSuccess
    }

    suspend fun fetchConfig(): JSONObject {
        require(co.sanaa.agent.core.OwnerPower(context).isOn()) { "Amara is off by owner request" }
        require(config.configSyncEnabled) { "Configuration sync is disabled by the owner." }
        return get("config/${config.deviceId}").also(config::saveRemoteConfig)
    }

    suspend fun status(): JSONObject {
        require(co.sanaa.agent.core.OwnerPower(context).isOn()) { "Amara is off by owner request" }
        require(config.configSyncEnabled) { "Configuration sync is disabled by the owner." }
        return get("status/${config.deviceId}")
    }

    /** Report only the shop named in Terminal's signed, short-lived assertion. */
    suspend fun observeTerminalShop(shop: TerminalShopIdentity): Boolean {
        if (!config.configSyncEnabled || !config.ownerAllowsWork() || config.agentToken.isBlank()) return false
        ensureDeviceId()
        val response = request(Request.Builder().url(url("shop-observation"))
            .post(JSONObject().put("device_id", config.deviceId).toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .header("X-Terminal-Identity", shop.assertion)
            .authenticated().build())
        val confirmed = response.getJSONObject("shop_identity")
        check(confirmed.getLong("seller_id") == shop.sellerId && confirmed.getLong("shop_id") == shop.shopId) {
            "Cards confirmed a different Terminal shop"
        }
        eventOutbox.bindShop(shop.scope, response.getLong("binding_revision"))
        return true
    }

    /** Replays a small durable batch; only server acknowledged IDs leave the outbox. */
    suspend fun syncEvents(): Int = eventSyncMutex.withLock {
        if (!config.telemetryOptIn || !config.configSyncEnabled || !config.ownerAllowsWork() || config.agentToken.isBlank()) return@withLock 0
        val batch = eventOutbox.pending()
        if (batch.isEmpty()) return@withLock 0
        ensureDeviceId()
        val payload = JSONArray().apply { batch.forEach { put(it.second) } }
        val response = post("events", JSONObject().put("device_id", config.deviceId).put("events", payload))
        val acknowledged = mutableListOf<String>()
        for (field in listOf("accepted", "duplicates")) {
            val ids = response.optJSONArray(field) ?: continue
            for (i in 0 until ids.length()) ids.getString(i).takeIf { id -> batch.any { it.first == id } }?.let(acknowledged::add)
        }
        eventOutbox.acknowledge(acknowledged)
        val rejected = response.optJSONArray("rejected") ?: JSONArray()
        for (i in 0 until rejected.length()) {
            val item = rejected.getJSONObject(i)
            val id = item.optString("event_id")
            if (batch.none { it.first == id }) continue
            val code = item.optString("code")
            if (code == "unknown_binding_revision") eventOutbox.waitForBinding(id)
            else eventOutbox.reject(id, code)
        }
        acknowledged.size
    }

    /** One bounded health sample. Consent is independent of bulk telemetry. */
    suspend fun heartbeat(snapshot: Map<String, Any>): Boolean {
        if (!config.operationalReportingEnabled || !config.configSyncEnabled ||
            !config.ownerAllowsWork() || config.agentToken.isBlank()) return false
        ensureDeviceId()
        val body = JSONObject()
            .put("device_id", config.deviceId)
            .put("agent_version", co.sanaa.agent.BuildConfig.VERSION_NAME)
            .put("uptime_ms", android.os.SystemClock.elapsedRealtime())
            .put("accessibility_bound", snapshot["accessibilityBound"] == true)
        (snapshot["pendingWorkCount"] as? Number)?.toInt()?.takeIf { it >= 0 }
            ?.let { body.put("pending_tasks", it) }
        (snapshot["batteryPercent"] as? Number)?.toInt()?.takeIf { it in 0..100 }
            ?.let { body.put("battery_level", it) }
        (snapshot["charging"] as? Boolean)?.let { body.put("is_charging", it) }
        (snapshot["network"] as? String)?.let { body.put("network_state", it) }
        (snapshot["remoteCommandRevision"] as? Number)?.let { body.put("remote_command_revision", it.toLong()) }
        (snapshot["remoteWorkPaused"] as? Boolean)?.let { body.put("remote_work_paused", it) }
        (snapshot["systemLockout"] as? Boolean)?.let { body.put("system_lockout", it) }
        post("heartbeat", body)
        return true
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
        if (config.deviceId.isBlank()) config.deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: java.util.UUID.randomUUID().toString()
    }

    private suspend fun get(path: String): JSONObject = request(Request.Builder().url(url(path)).get().authenticated().build())
    private suspend fun post(path: String, body: JSONObject, authenticated: Boolean = true): JSONObject {
        val builder = Request.Builder().url(url(path)).post(body.toString().toRequestBody(JSON)).header("Accept", "application/json")
        if (authenticated) builder.authenticated()
        return request(builder.build())
    }

    private suspend fun request(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val requestId = UUID.randomUUID().toString()
        val correlated = request.newBuilder().header("X-Amara-Request-ID", requestId).build()
        val call = client.newCall(correlated)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    try {
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IllegalStateException(when (response.code) {
                            401 -> "Cards connection needs pairing. Open Settings → Connect to Cards admin."
                            429 -> "Cards is receiving too many requests. Try again in a minute."
                            else -> "Cards could not complete the request (HTTP ${response.code}; reference $requestId). Try again shortly."
                        })
                        continuation.resume(JSONObject(body))
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    private fun Request.Builder.authenticated() = apply {
        if (config.agentToken.isBlank()) throw IllegalStateException("Agent is not registered")
        header("Authorization", "Bearer ${config.agentToken}")
    }
    private fun url(path: String) = "${config.backendUrl.trimEnd('/')}/${path.trimStart('/')}"
    companion object { private val JSON = "application/json".toMediaType() }
}
